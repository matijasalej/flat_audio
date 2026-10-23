package io.github.matijasalej.wholeflat.capture

/**
 * Which source feeds the speakers. Two can play at once (the Whole Flat device in the Spotify app, and the
 * Cast receiver or the Spotify TV app on the same box); librespot wins and the capture is ignored while it
 * plays. When it stops and the captured player still plays, the capture takes over.
 *
 * "Capture active" means the player is playing or paused within the hold (the speaker streams silence);
 * it ends when the detector says it has been silent for the whole hold.
 */
class SourceArbiter {
    enum class Source(val label: String) {
        LIBRESPOT("librespot"),
        CAPTURE("capture"),
        IDLE("idle"),
    }

    /** The source changed from [from] to [to]. */
    class Change(val from: Source, val to: Source)

    @Volatile
    var source = Source.IDLE
        private set

    private var librespot = false
    private var capture = false

    val captureWanted: Boolean get() = capture

    /** librespot started or stopped playing (a start event, a pause, the end). */
    @Synchronized
    fun librespotPlaying(playing: Boolean): Change? {
        librespot = playing
        return update()
    }

    /** The captured player started (or resumed) or was silent for the whole hold. */
    @Synchronized
    fun captureActive(active: Boolean): Change? {
        capture = active
        return update()
    }

    /** The capture is gone (the projection was lost): nothing to take over with. */
    @Synchronized
    fun captureLost(): Change? = captureActive(false)

    private fun update(): Change? {
        val want = when {
            librespot -> Source.LIBRESPOT
            capture -> Source.CAPTURE
            else -> Source.IDLE
        }
        if (want == source) return null
        val change = Change(source, want)
        source = want
        return change
    }
}
