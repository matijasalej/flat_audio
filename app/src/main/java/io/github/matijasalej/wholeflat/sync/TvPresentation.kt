package io.github.matijasalej.wholeflat.sync

/**
 * What the TV shows and what its Ambilight does, from the mode.
 *
 * - **Synced**, the TV playing our stream: with **karaoke** (lyrics on the TV) the screen is on and the Ambilight follows the audio;
 *   without it the screen is off (the stream goes on, the sound is all) and so are the lights.
 * - **Lyrics** (the Spotify app is on the screen over HDMI, with its lyrics): the Ambilight follows the audio while the music plays.
 * - Otherwise the screen is left as it is, and the lights are off.
 *
 * The rule of the lights: on whenever lyrics are visible on the TV, off when the screen is off; what they were before is put back
 * when they are not wanted any more. A screen that this app switched off is switched on again as soon as the TV is not playing our
 * stream (the user has left Synced mode and wants to see the Spotify app).
 */
object TvPresentation {
    enum class Screen {
        /** Do not touch it. */
        LEAVE,
        OFF,
        ON,
    }

    class Want(val screen: Screen, val lights: Boolean)

    /**
     * @param tvPlaying the TV plays our stream (Synced mode holds)
     * @param source "capture", "librespot" or "idle"
     * @param lightsEnabled the setting `ambilight_synced`
     * @param screenOffByUs this app switched the screen off and has not switched it on again
     */
    fun want(mode: Mode, tvPlaying: Boolean, karaoke: Boolean, source: String, lightsEnabled: Boolean, screenOffByUs: Boolean): Want {
        val restore = if (screenOffByUs) Screen.ON else Screen.LEAVE
        if (mode == Mode.SYNCED && tvPlaying) {
            return if (karaoke) Want(restore, lightsEnabled) else Want(Screen.OFF, false)
        }
        val lyricsOnTheScreen = mode == Mode.LYRICS && source == "capture"
        return Want(restore, lightsEnabled && lyricsOnTheScreen)
    }
}
