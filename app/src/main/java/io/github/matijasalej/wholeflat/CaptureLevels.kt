package io.github.matijasalej.wholeflat

import java.util.ArrayDeque
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Level statistics of the captured PCM, kept per read (about 20 ms) for the last 30 s, so that the
 * status route (last 1 s), the frame rate (last 5 s) and the volume test (2 s windows) are all
 * answered from the same history.
 *
 * Levels are dBFS against 16 bit full scale (a full scale sine has an RMS of -3 dBFS). Digital
 * zero is reported as [FLOOR_DB] because JSON has no -Infinity.
 */
class LevelHistory(private val keepNs: Long = 30_000_000_000L) {

    companion object {
        const val FLOOR_DB = -200.0

        /** One LSB of 16 bit audio is -90.3 dBFS, so a peak below this means every sample is 0. */
        const val SILENCE_DB = -90.0

        fun toDb(linear: Double): Double =
            if (linear <= 0.0) FLOOR_DB else maxOf(FLOOR_DB, 20.0 * log10(linear))

        /** Measures [shorts] interleaved stereo samples of [pcm], read at [tNs] (System.nanoTime). */
        fun measure(pcm: ShortArray, shorts: Int, tNs: Long): Chunk {
            var sumL = 0.0
            var sumR = 0.0
            var peakL = 0
            var peakR = 0
            var i = 0
            while (i + 1 < shorts) {
                val l = pcm[i].toInt()
                val r = pcm[i + 1].toInt()
                sumL += (l * l).toDouble()
                sumR += (r * r).toDouble()
                val al = if (l < 0) -l else l
                val ar = if (r < 0) -r else r
                if (al > peakL) peakL = al
                if (ar > peakR) peakR = ar
                i += 2
            }
            return Chunk(tNs, shorts / 2, sumL, sumR, peakL, peakR)
        }
    }

    /** One read: when it returned, how many frames, the sums of squares and the peaks per channel. */
    class Chunk(
        val tNs: Long,
        val frames: Int,
        val sumSqL: Double,
        val sumSqR: Double,
        val peakL: Int,
        val peakR: Int,
    ) {
        val peakDb: Double get() = toDb(maxOf(peakL, peakR) / 32768.0)
    }

    /** The reads of a time span added together. [frames] is 0 when nothing was read in the span. */
    class Window(
        val frames: Long,
        private val sumSqL: Double,
        private val sumSqR: Double,
        peakL: Int,
        peakR: Int,
    ) {
        val rmsDbL: Double = if (frames > 0) toDb(sqrt(sumSqL / frames) / 32768.0) else FLOOR_DB
        val rmsDbR: Double = if (frames > 0) toDb(sqrt(sumSqR / frames) / 32768.0) else FLOOR_DB

        /** RMS over both channels together. */
        val rmsDbMean: Double =
            if (frames > 0) toDb(sqrt((sumSqL + sumSqR) / (2.0 * frames)) / 32768.0) else FLOOR_DB
        val peakDbL: Double = toDb(peakL / 32768.0)
        val peakDbR: Double = toDb(peakR / 32768.0)

        /** True when data came in and every sample of it was (digitally) zero. */
        val digitalSilence: Boolean = frames > 0 && peakDbL < SILENCE_DB && peakDbR < SILENCE_DB
    }

    private val chunks = ArrayDeque<Chunk>()

    fun add(chunk: Chunk) {
        synchronized(this) {
            chunks.addLast(chunk)
            while (true) {
                val oldest = chunks.peekFirst() ?: break
                if (chunk.tNs - oldest.tNs <= keepNs) break
                chunks.removeFirst()
            }
        }
    }

    /** The reads that returned between [fromNs] and [toNs] (System.nanoTime values). */
    fun window(fromNs: Long, toNs: Long): Window {
        var frames = 0L
        var sumL = 0.0
        var sumR = 0.0
        var peakL = 0
        var peakR = 0
        synchronized(this) {
            for (c in chunks) {
                if (c.tNs < fromNs || c.tNs > toNs) continue
                frames += c.frames
                sumL += c.sumSqL
                sumR += c.sumSqR
                if (c.peakL > peakL) peakL = c.peakL
                if (c.peakR > peakR) peakR = c.peakR
            }
        }
        return Window(frames, sumL, sumR, peakL, peakR)
    }

    /**
     * Frames per second over the last [spanNs]: the frames of every read after the first one in
     * the span, divided by the time from the first read to the last.
     */
    fun framesPerSecond(nowNs: Long, spanNs: Long): Double {
        var first: Chunk? = null
        var last: Chunk? = null
        var frames = 0L
        synchronized(this) {
            for (c in chunks) {
                if (c.tNs < nowNs - spanNs) continue
                if (first == null) first = c else frames += c.frames
                last = c
            }
        }
        val a = first
        val b = last
        if (a == null || b == null || a === b) return 0.0
        val seconds = (b.tNs - a.tNs) / 1e9
        return if (seconds > 0.0) frames / seconds else 0.0
    }
}
