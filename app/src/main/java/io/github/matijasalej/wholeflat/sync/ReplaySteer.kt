package io.github.matijasalej.wholeflat.sync

/**
 * Keeps the replay of the capture in the living room (see [ReplayGate]) at its delay: where in the capture the next block of the track is
 * read, and how many captured frames it takes, so that an error melts away without being heard. Pure logic, one per replay.
 *
 * The track's timestamps (smoothed by [AnchorFilter]) say when a frame of the track is presented at the box's HDMI output, the capture clock
 * when a captured frame was captured. The next frame the track is given is presented at [presentNanos] of the frames written so far; it is
 * to carry the frame captured the delay before that (the wanted frame). The error is the cursor (the next captured frame to go out) less the
 * wanted frame: positive, the room is early (it plays newer audio than it should); negative, late.
 *
 * - The first block, and an error beyond [jumpFrames], put the cursor where it is to be: a jump, heard as a cut (at a start, after a
 *   capture that stalled or a track that ran dry).
 * - Beyond [startFrames] a block of n frames takes n - k captured frames when early and n + k when late (k = 1, or 2 beyond [fastFrames]),
 *   stretched over the n by [Stretch]: a quarter or half of a percent of speed, which is not heard, until the error is below [stopFrames].
 */
class ReplaySteer(
    rate: Int,
    private val startFrames: Double = rate * START_MS / 1000.0,
    private val stopFrames: Double = rate * STOP_MS / 1000.0,
    private val fastFrames: Double = rate * FAST_MS / 1000.0,
    private val jumpFrames: Double = rate * JUMP_MS / 1000.0,
) {
    /** Read [inFrames] captured frames from [from] for the block; [jumped]: the cursor was put there (it was not where the last block ended). */
    class Plan(val from: Long, val inFrames: Int, val jumped: Boolean)

    private var correcting = false

    /** Blocks that were stretched, and jumps after the first placement, since the start (for the status). */
    var stretched = 0L
        private set

    var jumps = 0
        private set

    /**
     * @param cursor the next captured frame to go out (where the last block ended), or -1 when nothing is placed yet
     * @param wanted the captured frame, as a fraction, that the next frame of the track is to carry
     * @param blockFrames the frames of the track the block fills
     */
    fun plan(cursor: Long, wanted: Double, blockFrames: Int): Plan {
        if (cursor < 0L) {
            correcting = false
            return Plan(Math.round(wanted), blockFrames, jumped = true)
        }
        val error = cursor - wanted
        val size = Math.abs(error)
        if (size > jumpFrames) {
            jumps++
            correcting = false
            return Plan(Math.round(wanted), blockFrames, jumped = true)
        }
        if (!correcting && size > startFrames) correcting = true
        if (correcting && size < stopFrames) correcting = false
        if (!correcting) return Plan(cursor, blockFrames, jumped = false)
        stretched++
        val k = if (size > fastFrames) 2 else 1
        return Plan(cursor, if (error > 0) blockFrames - k else blockFrames + k, jumped = false)
    }

    /** A new track or a new capture: the next block is placed afresh. */
    fun reset() {
        correcting = false
    }

    companion object {
        const val START_MS = 2.0
        const val STOP_MS = 0.5
        const val FAST_MS = 10.0
        const val JUMP_MS = 40.0

        /** When frame [frame] of the track is presented, by its anchor ([anchorFrame] was presented at [anchorNanos]). */
        fun presentNanos(anchorFrame: Long, anchorNanos: Long, frame: Long, rate: Int): Long =
            anchorNanos + Math.round((frame - anchorFrame).toDouble() * 1e9 / rate)
    }
}

/** Stretches a block of stereo frames to another length. Plain Kotlin. */
object Stretch {
    /**
     * Fills [out] with [outFrames] interleaved stereo frames made from the first [inFrames] frames of [input], by linear interpolation:
     * output frame i is read at i * (inFrames - 1) / (outFrames - 1) of the input, so the first and the last frames are kept and the next
     * block goes on from the frame after the last. Equal lengths are a copy.
     */
    fun linear(input: ShortArray, inFrames: Int, out: ShortArray, outFrames: Int) {
        if (outFrames <= 0) return
        if (inFrames == outFrames) {
            System.arraycopy(input, 0, out, 0, outFrames * 2)
            return
        }
        if (inFrames <= 1 || outFrames == 1) {
            val l: Short = if (inFrames >= 1) input[0] else 0
            val r: Short = if (inFrames >= 1) input[1] else 0
            for (i in 0 until outFrames) {
                out[i * 2] = l
                out[i * 2 + 1] = r
            }
            return
        }
        val ratio = (inFrames - 1).toDouble() / (outFrames - 1)
        for (i in 0 until outFrames) {
            val x = i * ratio
            val j = minOf(x.toInt(), inFrames - 2)
            val f = x - j
            for (c in 0..1) {
                val a = input[j * 2 + c].toDouble()
                val b = input[(j + 1) * 2 + c].toDouble()
                out[i * 2 + c] = Math.round(a + (b - a) * f).toInt().coerceIn(-32768, 32767).toShort()
            }
        }
    }
}
