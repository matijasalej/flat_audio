package io.github.matijasalej.wholeflat.capture

import kotlin.math.log10
import kotlin.math.sqrt

/** Level of captured 16 bit stereo audio in dBFS (a full scale sine has an RMS of -3 dBFS). */
object Levels {
    /** Digital zero (JSON has no minus infinity). */
    const val FLOOR_DB = -200.0

    fun toDb(linear: Double): Double = if (linear <= 0.0) FLOOR_DB else maxOf(FLOOR_DB, 20.0 * log10(linear))

    /** Peak over both channels of [frames] interleaved stereo frames, in dBFS. */
    fun peakDb(pcm: ShortArray, frames: Int): Double {
        var peak = 0
        for (i in 0 until frames * 2) {
            val v = pcm[i].toInt()
            val a = if (v < 0) -v else v
            if (a > peak) peak = a
        }
        return toDb(peak / 32768.0)
    }

    /** Sum of the squares over both channels (for an RMS over several chunks). */
    fun sumSquares(pcm: ShortArray, frames: Int): Double {
        var sum = 0.0
        for (i in 0 until frames * 2) {
            val v = pcm[i].toDouble()
            sum += v * v
        }
        return sum
    }

    fun rmsDb(sumSquares: Double, frames: Long): Double =
        if (frames <= 0) FLOOR_DB else toDb(sqrt(sumSquares / (2.0 * frames)) / 32768.0)
}

/** The RMS level over the last [windowChunks] chunks (about a second of the 10 ms chunks of the capture). */
class RollingLevel(private val windowChunks: Int = 100) {
    private val sums = DoubleArray(windowChunks)
    private val frames = LongArray(windowChunks)
    private var pos = 0

    @Synchronized
    fun add(sumSquares: Double, chunkFrames: Int) {
        sums[pos] = sumSquares
        frames[pos] = chunkFrames.toLong()
        pos = (pos + 1) % windowChunks
    }

    @Synchronized
    fun rmsDb(): Double = Levels.rmsDb(sums.sum(), frames.sum())
}
