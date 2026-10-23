package io.github.matijasalej.wholeflat.sync

/**
 * What the screen says when Spotify refuses the audio keys of the account (see native/src/keyguard.rs for
 * what the engine does about it). Plain Kotlin, so that the wording is tested.
 */
object KeyMessage {
    private const val REFUSING = "Spotify is refusing playback for this account right now"

    /**
     * @param state "ok", "key_refused" or "key_gave_up" (the `spotify_state` of the status)
     * @param phase the engine's phase: waiting, retrying, user_paused, gave_up
     * @param retryInSeconds seconds to the next try, negative if none is scheduled
     * @param count refusals in this episode
     * @return the sentence, or null when all is well
     */
    fun text(state: String, phase: String, retryInSeconds: Long, count: Int): String? {
        return when (state) {
            "key_refused" -> when (phase) {
                "waiting" -> {
                    val wait = if (retryInSeconds < 0) "a moment" else duration(retryInSeconds)
                    "$REFUSING, retrying in $wait (refusal $count)"
                }
                "retrying" -> "$REFUSING, trying again now (refusal $count)"
                "user_paused" -> "$REFUSING. Paused: press play on the phone to try again"
                else -> REFUSING
            }
            "key_gave_up" ->
                "Spotify kept refusing playback for this account for 30 minutes. Press play on the phone to try again"
            else -> null
        }
    }

    /** 45 s, 2 min, 15 min. */
    fun duration(seconds: Long): String = if (seconds >= 120) "${(seconds + 30) / 60} min" else "$seconds s"
}
