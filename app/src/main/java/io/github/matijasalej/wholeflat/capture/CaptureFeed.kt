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

    /** RMS level over about the last second (dBFS). */
    fun levelDb(): Double = rolling.rmsDb()

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
        /** Android's AudioPlaybackCapture is asked for 48 kHz stereo. */
        const val RATE = 48_000
        const val BUFFER_SECONDS = 8
    }
}
