package io.github.matijasalej.wholeflat

import android.graphics.Bitmap
import io.github.matijasalej.wholeflat.sync.LyricLine

/** Where the lyrics of the track stand. */
enum class LyricsStatus {
    /** Being looked for: the picture shows the cover and the track and no remark. */
    LOADING,

    /** There are none (not found, or an instrumental). */
    NONE,

    /** Synced lyrics are there and the position is known. */
    SYNCED,
}

/**
 * What the picture of the TV's stream shows of the track at one moment: the title, the artist, the cover and the colour behind it, the
 * lyrics (if there are some and the position in the track is known) and the position in the track that goes with this moment.
 */
class TvScene(
    val title: String,
    val artist: String,
    val cover: Bitmap?,
    /** The colour behind the lyrics (ARGB), from the cover. */
    val background: Int,
    val lines: List<LyricLine>?,
    val lyricsStatus: LyricsStatus,
    /** Position in the track at the moment of the picture (ms), negative if it is not known. */
    val positionMs: Long,
    /** Length of the track (ms), negative if it is not known. */
    val durationMs: Long,
    /** What tells this track from another: a change of it is a new track, which the picture fades to. Empty: no track. */
    val key: String = "",
    /**
     * The cover cut square and scaled to twice the size it is drawn at, for the footer and for the big cover: made once, away from the thread
     * that draws (null: the drawer makes the square from [cover] itself).
     */
    val coverSmall: Bitmap? = null,
    val coverBig: Bitmap? = null,
)

/** Makes the scene for the picture that goes with the capture time [captureNanos] (System.nanoTime): the picture and the sound of that moment are shown together. */
fun interface SceneSource {
    fun scene(captureNanos: Long): TvScene
}
