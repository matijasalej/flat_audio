package io.github.matijasalej.wholeflat.sync

/**
 * What the TV shows and what its Ambilight does, from the mode and the [Display].
 *
 * - **Synced**, the TV playing our stream, by the display: **Listening** the screen is off and so are the lights (the stream goes on, the
 *   sound is all); **Ambilight** the screen is off and the lights follow the audio; **Screen + Ambilight** the lyrics video is on the screen
 *   and the lights follow the audio.
 * - **Lyrics** (the Spotify app is on the screen over HDMI, with its lyrics, which the user left the TV's player for): the lights are
 *   the display's too, while the music plays: on for **Ambilight** and **Screen + Ambilight**, off for **Listening** (they used to come
 *   on with every song in Lyrics mode, which the user did not ask for).
 * - Otherwise the screen is left as it is, and the lights are off.
 *
 * What the lights were before is put back when they are not wanted any more. A screen that this app switched off is switched on again as
 * soon as the TV is not playing our stream (the user has left Synced mode and wants to see the Spotify app) or the display wants it on.
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
     * @param tvPlaying the TV plays our music (Synced mode holds, the warm-up is over)
     * @param source "capture", "librespot" or "idle"
     * @param screenOffByUs this app switched the screen off and has not switched it on again
     */
    fun want(mode: Mode, tvPlaying: Boolean, display: Display, source: String, screenOffByUs: Boolean): Want {
        val restore = if (screenOffByUs) Screen.ON else Screen.LEAVE
        if (mode == Mode.SYNCED && tvPlaying) {
            return Want(if (display.screenOn) restore else Screen.OFF, display.lights)
        }
        val lyricsLights = mode == Mode.LYRICS && source == "capture" && display.lights
        return Want(restore, lyricsLights)
    }
}
