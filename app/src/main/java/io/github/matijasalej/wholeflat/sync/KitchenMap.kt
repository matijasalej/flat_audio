package io.github.matijasalej.wholeflat.sync

/**
 * Which ring frame is at which position of a Sonos stream.
 *
 * The stream is the ring from frame `firstRingFrame` on, with single frames dropped or repeated by the
 * trims that keep the kitchen in step. Between two trims the mapping is a line of slope 1, so the map
 * stores one checkpoint per trim: from stream frame `s` on, stream frame `s + k` is ring frame `r + k`.
 * "Dropped" means a ring frame that never made it into the stream (the stream gets ahead of the
 * ring), "inserted" a frame that was sent twice (the stream falls back).
 */
class KitchenMap(val firstRingFrame: Long) {
    private val streamStarts = ArrayList<Long>()
    private val ringStarts = ArrayList<Long>()

    @Volatile
    var streamEnd = 0L
        private set

    init {
        streamStarts.add(0L)
        ringStarts.add(firstRingFrame)
    }

    /** From stream frame `streamFrame` on, the stream carries ring frames starting at `ringFrame`. */
    @Synchronized
    fun addCheckpoint(streamFrame: Long, ringFrame: Long) {
        streamStarts.add(streamFrame)
        ringStarts.add(ringFrame)
    }

    /** The stream has been built up to (not including) this frame. */
    @Synchronized
    fun setStreamEnd(frames: Long) {
        streamEnd = frames
    }

    /** The ring frame at stream position `s` (fractional frames are carried along). */
    @Synchronized
    fun ringOfStream(s: Double): Double? {
        if (s < 0) return null
        var i = streamStarts.size - 1
        while (i > 0 && streamStarts[i] > s) i--
        return ringStarts[i] + (s - streamStarts[i])
    }

    /**
     * Net frames dropped (positive) or inserted (negative) in the stream up to position `s`: how far the
     * stream's content is ahead of where a plain copy of the ring would be.
     */
    @Synchronized
    fun netDropsAt(s: Double): Double {
        val ring = ringOfStream(s) ?: return 0.0
        return (ring - firstRingFrame) - s
    }

    @Synchronized
    fun checkpoints(): Int = streamStarts.size
}
