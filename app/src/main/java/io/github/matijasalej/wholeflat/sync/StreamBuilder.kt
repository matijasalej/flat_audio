package io.github.matijasalej.wholeflat.sync

import java.util.concurrent.atomic.AtomicInteger

/** Where the kitchen's stream takes its frames from: the ring, or a fake one in the tests. */
interface FrameSource {
    /**
     * Copies up to `maxFrames` frames starting at the absolute ring index `cursor` into `out`
     * (interleaved stereo). Returns the number of frames, 0 on timeout, or [CLOSED], [LAPPED] (the
     * frames are gone), [EPOCH] (a new epoch started: the session is obsolete).
     */
    fun read(cursor: Long, out: ShortArray, maxFrames: Int, timeoutMs: Int): Int

    companion object {
        const val CLOSED = -1
        const val LAPPED = -2
        const val EPOCH = -3
    }
}

/**
 * Turns the ring into the blocks of a Sonos stream and applies the trims.
 *
 * A trim is a single frame dropped from the stream (the Sonos's content moves ahead) or repeated (it
 * falls back). The controller adds them to a budget; the builder applies at most one per
 * `trimSpacing` frames (10 ms), at the start of a segment, and records each one in the [KitchenMap] so
 * that stream positions can be turned back into ring frames.
 *
 * `nextBlock` returns once a whole block of `blockFrames` frames is in [block]; if the ring has
 * nothing yet it returns 0 and keeps what it has, a later call carries on.
 */
class StreamBuilder(
    private val source: FrameSource,
    val map: KitchenMap,
    private val channels: ChannelMode = ChannelMode.STEREO,
    val blockFrames: Int = 4096,
    private val trimSpacing: Int = 441,
) {
    enum class ChannelMode { STEREO, LEFT_ONLY, RIGHT_ONLY }

    /** The block being built; complete when `nextBlock` returned `blockFrames`. */
    val block = ShortArray(blockFrames * 2)

    @Volatile
    var ringCursor: Long = map.firstRingFrame
        private set

    /** Frames in the blocks handed out so far. */
    @Volatile
    var streamFrames = 0L
        private set

    private val budget = AtomicInteger(0)
    private val segment = ShortArray(trimSpacing * 2)
    private val one = ShortArray(2)
    private var filled = 0
    private var sinceTrim = trimSpacing
    private var lastLeft: Short = 0
    private var lastRight: Short = 0
    private var haveLast = false

    /** The controller asks for `frames` more trims: positive drops frames, negative repeats them. */
    fun addTrim(frames: Int) {
        budget.addAndGet(frames)
    }

    /** Trims asked for and not applied yet. */
    fun unappliedBudget(): Int = budget.get()

    /** Builds the next block; see the class comment for the result. */
    fun nextBlock(timeoutMs: Int): Int {
        while (filled < blockFrames) {
            if (sinceTrim >= trimSpacing) {
                val applied = applyTrim(timeoutMs)
                if (applied < 0) return applied
            }
            if (filled >= blockFrames) break
            // a segment never reaches past the next moment a trim may be applied
            val untilTrim = if (sinceTrim >= trimSpacing) trimSpacing else trimSpacing - sinceTrim
            val want = minOf(blockFrames - filled, untilTrim)
            val got = source.read(ringCursor, segment, want, timeoutMs)
            if (got <= 0) return got
            System.arraycopy(segment, 0, block, filled * 2, got * 2)
            lastLeft = segment[got * 2 - 2]
            lastRight = segment[got * 2 - 1]
            haveLast = true
            ringCursor += got
            filled += got
            sinceTrim += got
        }
        applyChannels()
        filled = 0
        streamFrames += blockFrames
        map.setStreamEnd(streamFrames)
        return blockFrames
    }

    /** Applies one trim from the budget if there is one. Returns 0, or a negative source error. */
    private fun applyTrim(timeoutMs: Int): Int {
        val wanted = budget.get()
        if (wanted > 0) {
            // drop: the next ring frame never reaches the stream
            val got = source.read(ringCursor, one, 1, timeoutMs)
            if (got < 0) return got
            if (got == 0) return 0
            ringCursor += 1
            budget.decrementAndGet()
            sinceTrim = 0
            map.addCheckpoint(streamFrames + filled, ringCursor)
        } else if (wanted < 0 && haveLast) {
            // repeat: the last frame goes out twice; the duplicate is the same ring frame as the one before it
            block[filled * 2] = lastLeft
            block[filled * 2 + 1] = lastRight
            map.addCheckpoint(streamFrames + filled, ringCursor - 1)
            filled += 1
            budget.incrementAndGet()
            sinceTrim = 0
        }
        return 0
    }

    private fun applyChannels() {
        when (channels) {
            ChannelMode.STEREO -> Unit
            ChannelMode.LEFT_ONLY -> for (i in 0 until blockFrames) block[i * 2 + 1] = block[i * 2]
            ChannelMode.RIGHT_ONLY -> for (i in 0 until blockFrames) block[i * 2] = block[i * 2 + 1]
        }
    }
}
