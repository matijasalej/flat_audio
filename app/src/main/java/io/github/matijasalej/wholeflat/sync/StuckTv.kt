package io.github.matijasalej.wholeflat.sync

/**
 * The player of the TV that is hung: it takes the item and says TRANSITIONING but never asks for the stream ("the TV did not ask for the
 * stream within 25 s (transport TRANSITIONING)"), then answers 705 to everything and jumps to its tuner. A standby and on did not clear it
 * on 23.10., the plug did. Twice in a row is hung (or once after the user said it was free, [probation]), and so is a transport that still
 * says TRANSITIONING after a Stop before anything was set ([onHungBeforeSet]): Synced is not tried again, because every try ends the same
 * way and each leaves the TV on its tuner; the app falls back to Lyrics and says what to do.
 *
 * At rest a hung player looks like a free one (28.10.: NO_MEDIA_PRESENT, no URI, no actions, after three days of standbys), and the network
 * cannot tell a TV in standby from one that is unplugged (neither answers), so only the user can say that it is free again: picking a
 * setting on the phone page after the plug ([clear]). The next start is then on probation. Plain Kotlin; the renderer keeps [stuck] across
 * restarts of the app.
 */
class StuckTv(private val noGetsToStick: Int = 2) {
    @Volatile
    var stuck = false
        private set

    @Volatile
    var stuckSinceMs = 0L
        private set

    /** Starts that ended in TRANSITIONING without the TV asking for the stream, in a row. */
    @Volatile
    var noGetsInARow = 0
        private set

    /** The user said the player is free (it was hung): one more start like that and it is hung again at once. */
    @Volatile
    var probation = false
        private set

    /** A start ended with the TV in TRANSITIONING that never asked for the stream, at [nowMs]; true if that made the player hung just now. */
    @Synchronized
    fun onNoGet(nowMs: Long): Boolean {
        noGetsInARow++
        if (!stuck && (probation || noGetsInARow >= noGetsToStick)) {
            stick(nowMs)
            return true
        }
        return false
    }

    /** The transport still said TRANSITIONING after a Stop, before the start set anything: hung. True if it was not hung before. */
    @Synchronized
    fun onHungBeforeSet(nowMs: Long): Boolean {
        if (stuck) return false
        stick(nowMs)
        return true
    }

    /** A start failed in another way: it is not the same fault, the count starts again. */
    @Synchronized
    fun onOtherFailure() {
        if (!stuck) noGetsInARow = 0
    }

    /** The TV played our stream: whatever was said of its player is over. */
    @Synchronized
    fun onPlaying() {
        noGetsInARow = 0
        probation = false
    }

    /** The user says the player is free (they picked a setting on the phone page, after the plug): the next start is on probation. */
    @Synchronized
    fun clear() {
        if (stuck) probation = true
        stuck = false
        noGetsInARow = 0
    }

    /** The app started again and the player was hung when it stopped. */
    @Synchronized
    fun restore(nowMs: Long) {
        stuck = true
        stuckSinceMs = nowMs
    }

    private fun stick(nowMs: Long) {
        stuck = true
        stuckSinceMs = nowMs
        probation = false
    }

    companion object {
        const val ADVICE = "The TV's player is stuck: unplug the TV for a minute, then pick a setting on the phone page"
    }
}
