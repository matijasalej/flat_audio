package io.github.matijasalej.wholeflat.capture

import io.github.matijasalej.wholeflat.source.PcmSink

/**
 * What the capture hands over: the buffer of captured audio with its clock, and the levels. The capture
 * thread writes here ([writeAt]); the kitchen's stream reads from [buffer]; the status reads the rest.
 *
 * It is the [PcmSink] of the capture source: the interface of the sources (see source/AudioSource.kt) is
 * how audio gets in, the buffer is where it ends up (the living room does not play a captured player: the
 * Cast receiver plays it itself, directly to the TV).
 */
class CaptureFeed(val rate: Int = RATE) : PcmSink {
    val buffer = CaptureBuffer(rate, rate * BUFFER_SECONDS)
    val clock = CaptureClock(rate)
    private val rolling = RollingLevel()

    // how old the newest frame is when it has been read: the pipeline from the capture point to this app
    private val ages = DoubleArray(AGE_SAMPLES)
    private var ageCount = 0
    private var agePos = 0

    override val supportedRates: IntArray = intArrayOf(rate)

    /** Peak level of the last chunk (dBFS). */
    @Volatile
    var peakDb = Levels.FLOOR_DB
        private set

    /** The generation of the capture in progress (an attempt number); frame numbers restart with each. */
    @Volatile
    var generation = 0L
        private set

    /** "none", "timestamp" (AudioRecord.getTimestamp) or "read" (the moments the reads returned). */
    @Volatile
    var clockSource = "none"
        private set

    /** When the last chunk was read (System.nanoTime), 0 before the first. */
    @Volatile
    var lastChunkNanos = 0L
        private set

    /** The longest time between two chunks in this generation (ms): a capture that stalls shows here. */
    @Volatile
    var maxGapMs = 0L
        private set

    /** The longest age of a chunk's newest frame when it was read in this generation (ms), NaN before the first. */
    @Volatile
    var pipelineMaxMs = Double.NaN
        private set

    /** Age of the newest frame at the last Play (ms; NaN before the first) and the clock that said so. */
    @Volatile
    var agePlayMs = Double.NaN
        private set

    @Volatile
    var agePlaySource = ""
        private set

    /** Plays that were marked in this run. */
    @Volatile
    var playMarks = 0
        private set

    /** RMS level over about the last second (dBFS). */
    fun levelDb(): Double = rolling.rmsDb()

    /**
     * How long before [nanos] the newest frame in the buffer was captured (ms), by the capture clock: the pipeline
     * from the point where the audio is taken to this app, and the time since the last read. With the clock of
     * `AudioRecord.getTimestamp` that is the real age; with the clock of the reads ("read") it is only the time
     * since the last read. NaN before the first frame or the first anchor.
     */
    fun newestAgeMs(nanos: Long): Double {
        val written = buffer.writeIndex
        if (written <= 0L) return Double.NaN
        val captured = clock.nanosOfFrame(written - 1) ?: return Double.NaN
        return (nanos - captured) / 1e6
    }

    /** Play is sent at [nanos]: remembers how old the newest frame was then, and returns it. */
    fun markPlay(nanos: Long): Double {
        val age = newestAgeMs(nanos)
        agePlayMs = age
        agePlaySource = clockSource
        playMarks++
        return age
    }

    /** The median age of the newest frame of the last chunks when they were read (ms), NaN without the clock of the timestamps. */
    @Synchronized
    fun pipelineMs(): Double {
        if (ageCount == 0) return Double.NaN
        val sorted = ages.copyOf(ageCount).also { it.sort() }
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    }

    @Synchronized
    private fun addAge(ms: Double) {
        ages[agePos] = ms
        agePos = (agePos + 1) % AGE_SAMPLES
        if (ageCount < AGE_SAMPLES) ageCount++
        if (pipelineMaxMs.isNaN() || ms > pipelineMaxMs) pipelineMaxMs = ms
    }

    /**
     * The frame being captured at [nanos], counted from the last chunk that was read: the number of frames
     * written plus the time since that read. It does not depend on the clock's anchors, which is what a
     * start needs (a start must not be placed in the future or the past by a wrong anchor).
     */
    fun frameAtReadClock(nanos: Long): Long {
        val last = lastChunkNanos
        val written = buffer.writeIndex
        if (last == 0L) return written
        return written + maxOf(0L, nanos - last) * rate / 1_000_000_000L
    }

    /** A new capture begins. */
    fun newGeneration(generation: Long) {
        this.generation = generation
        buffer.newGeneration(generation)
        clock.reset()
        clockSource = "none"
        peakDb = Levels.FLOOR_DB
        lastChunkNanos = 0L
        maxGapMs = 0L
        synchronized(this) {
            ageCount = 0
            agePos = 0
            pipelineMaxMs = Double.NaN
        }
    }

    /** The capture ended; readers are released. */
    fun close() = buffer.close()

    fun open() = buffer.open()

    /** [PcmSink]: audio from a source other than the capture thread, timed by this call. */
    override fun write(pcm: ShortArray, frames: Int, sampleRate: Int): Boolean {
        if (sampleRate != rate) return false
        writeAt(pcm, frames, System.nanoTime())
        return true
    }

    /** One chunk of [frames] frames that was read at [tEndNanos] (System.nanoTime). Returns its peak level. */
    fun writeAt(pcm: ShortArray, frames: Int, tEndNanos: Long): Double {
        val peak = Levels.peakDb(pcm, frames)
        rolling.add(Levels.sumSquares(pcm, frames), frames)
        peakDb = peak
        val first = buffer.writeIndex
        buffer.write(pcm, frames)
        if (clockSource != "timestamp") {
            if (clockSource == "none") clockSource = "read"
            // the last frame of the chunk was captured a little before the read returned
            clock.add(first + frames - 1, tEndNanos)
        } else {
            // the timestamps say when the audio was captured: the read comes that much later
            val captured = clock.nanosOfFrame(first + frames - 1)
            if (captured != null) addAge((tEndNanos - captured) / 1e6)
        }
        val previous = lastChunkNanos
        if (previous != 0L) maxGapMs = maxOf(maxGapMs, (tEndNanos - previous) / 1_000_000L)
        lastChunkNanos = tEndNanos
        return peak
    }

    /** `AudioRecord.getTimestamp`: frame [framePosition] was captured at [nanos]. */
    fun onTimestamp(framePosition: Long, nanos: Long) {
        if (framePosition < 0) return
        if (clockSource != "timestamp") {
            clockSource = "timestamp"
            clock.reset()
        }
        clock.add(framePosition, nanos)
    }

    companion object {
        /**
         * Android's AudioPlaybackCapture is asked for 44.1 kHz stereo: what the Cast receiver plays, and what the ring of
         * the Spotify engine and the stream to the Sonos use, so nothing is resampled anywhere and both modes are alike.
         */
        const val RATE = 44_100
        const val BUFFER_SECONDS = 8

        /** Chunks the median age is taken over: about a second of 10 ms chunks. */
        private const val AGE_SAMPLES = 100
    }
}
