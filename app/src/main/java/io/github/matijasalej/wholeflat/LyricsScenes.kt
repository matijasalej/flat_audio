package io.github.matijasalej.wholeflat

import android.graphics.Bitmap
import io.github.matijasalej.wholeflat.sync.CoverColor
import io.github.matijasalej.wholeflat.sync.LyricsRepository
import io.github.matijasalej.wholeflat.sync.NowPlaying

/**
 * The scene of the lyrics picture for a capture time: the track that plays, its cover and the colour of it, the synced lyrics when LRCLIB
 * has them (asked for the first time the track is seen) and the position in the track that goes with that time. The picture that is shown
 * at the moment the sound of capture time c is heard is the one made for c, so the position is that of c and not of now; [offsetMs]
 * (the setting `lyrics_offset_ms`) moves the lyrics earlier when positive, for what the media session's clock does not tell.
 */
class LyricsScenes(
    private val nowPlaying: () -> NowPlaying?,
    private val art: () -> Bitmap?,
    private val covers: CoverCache,
    private val lyrics: LyricsRepository,
    private val offsetMs: () -> Int,
    private val fallback: () -> Pair<String, String?>,
    private val instantScroll: () -> Boolean = { false },
) : SceneSource {
    override fun scene(captureNanos: Long): TvScene {
        val np = nowPlaying()
        if (np == null || !np.hasTrack) {
            val (title, artist) = fallback()
            return TvScene(title, artist ?: "", null, CoverColor.DEFAULT, null, LyricsStatus.NONE, -1L, -1L)
        }
        val cover = covers.coverFor(np, art())
        val state = lyrics.state(LyricsRepository.Track(np.key, np.title!!, np.artist ?: "", np.album, np.durationMs))
        val position = np.positionAt(captureNanos)
        val shifted = if (position >= 0) maxOf(0L, position + offsetMs()) else -1L
        val status: LyricsStatus
        val lines = when {
            state is LyricsRepository.State.Synced && position >= 0 -> {
                status = LyricsStatus.SYNCED
                state.lines
            }
            state is LyricsRepository.State.Loading -> {
                status = LyricsStatus.LOADING
                null
            }
            else -> {
                status = LyricsStatus.NONE
                null
            }
        }
        return TvScene(
            np.title!!, np.artist ?: "", cover?.bitmap, cover?.color ?: CoverColor.DEFAULT, lines, status, shifted, np.durationMs,
            np.key, cover?.small, cover?.big, instantScroll(),
        )
    }
}
