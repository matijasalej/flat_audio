package io.github.matijasalej.wholeflat.sync

/**
 * What the media session of the captured player says about the track that is playing: who, which track, how long, where in it, and when
 * that position was valid. The position is a point of the player's own clock ([positionAtNanos], already brought to System.nanoTime);
 * from it the position at any other moment follows ([positionAt]), as long as the player plays at [speed]. Plain Kotlin.
 */
class NowPlaying(
    val packageName: String,
    val playing: Boolean,
    val title: String?,
    val artist: String?,
    val album: String?,
    /** Length of the track (ms), 0 or negative if it is not known. */
    val durationMs: Long,
    /** The id the player gives the track (its media id), if any. */
    val mediaId: String?,
    /** The address of the cover (http or https), if the session gives one. */
    val artUri: String?,
    /** Position in the track (ms) at [positionAtNanos]; negative if the session does not say. */
    val positionMs: Long,
    val positionAtNanos: Long,
    val speed: Double,
) {
    val hasTrack: Boolean get() = !title.isNullOrBlank()

    /** What tells one track from another: the media id, else title, artist and length in whole seconds. */
    val key: String
        get() = mediaId?.takeIf { it.isNotBlank() } ?: "${title ?: ""}|${artist ?: ""}|${durationMs / 1000}"

    /** The position (ms) at [nanos] (System.nanoTime): the position of the session carried on at its speed while it plays; -1 if it is not known. */
    fun positionAt(nanos: Long): Long {
        if (positionMs < 0) return -1L
        val p = if (playing) positionMs + Math.round((nanos - positionAtNanos) / 1e6 * speed) else positionMs
        return p.coerceIn(0L, if (durationMs > 0) durationMs else Long.MAX_VALUE)
    }

    companion object {
        /**
         * A position that was valid at [lastUpdateElapsedMs] on the elapsed-realtime clock (what `PlaybackState.getLastPositionUpdateTime` is)
         * as a time on the nanoTime clock, from one reading of both clocks ([elapsedNowMs], [nanoNow]).
         */
        fun toNanoTime(lastUpdateElapsedMs: Long, elapsedNowMs: Long, nanoNow: Long): Long = nanoNow - (elapsedNowMs - lastUpdateElapsedMs) * 1_000_000L
    }
}
