package io.github.matijasalej.wholeflat.sync

/**
 * What the TV shows and what its Ambilight does, from the mode, the [Display] and the mood light.
 *
 * - **Synced**, the TV playing our stream, by the display: **Listening** the screen is off and so are the lights (the stream goes on, the
 *   sound is all); **Ambilight** the screen is off and the lights follow the audio; **Screen + Ambilight** the lyrics video is on the screen
 *   and the lights follow the audio.
 * - **Lyrics** (the Spotify app is on the screen over HDMI, with its lyrics, which the user left the TV's player for): the Ambilight
 *   follows the audio while the music plays, since lyrics are visible.
 * - Otherwise the screen is left as it is, and the lights are off.
 *
 * **The mood light** (the Ambilight as a lamp of one colour, see [MoodLight]) has the lights whenever the music does not, and also while
 * it does if it was chosen after the display (the last tap wins); with the lights it takes the screen, which is switched off, except while
 * someone uses the box (the screen saver was woken, or a video app was left) or watches something on it (a video app, the Spotify app, Lyrics).
 *
 * What the lights were before is put back when they are not wanted any more. A screen that this app switched off is switched on again as
 * soon as nothing wants it off (the user has left Synced mode and wants to see the Spotify app, the display wants the lyrics, the mood light
 * is off).
 */
object TvPresentation {
    enum class Screen {
        /** Do not touch it. */
        LEAVE,
        OFF,
        ON,
    }

    /** Who has the lights: nobody (what they were before is put back), the music (they follow the audio), the mood light. */
    enum class Lights {
        NONE,
        MUSIC,
        MOOD,
    }

    /**
     * The mood light, when it is on.
     *
     * @param first it was chosen after the display: it has the TV also while the music plays
     * @param atBox someone uses the box (woke its screen saver, or left a video app): the picture is left on
     * @param watching a video app or the Spotify app is in front
     */
    class Lamp(val first: Boolean, val atBox: Boolean, val watching: Boolean)

    class Want(val screen: Screen, val lights: Lights)

    /**
     * @param tvPlaying the TV plays our music (Synced mode holds, the warm-up is over)
     * @param source "capture", "librespot" or "idle"
     * @param screenOffByUs this app switched the screen off and has not switched it on again
     * @param lamp the mood light, null when it is off
     */
    fun want(mode: Mode, tvPlaying: Boolean, display: Display, source: String, screenOffByUs: Boolean, lamp: Lamp? = null): Want {
        val restore = if (screenOffByUs) Screen.ON else Screen.LEAVE
        val synced = mode == Mode.SYNCED && tvPlaying
        val lyricsOnTheScreen = mode == Mode.LYRICS && source == "capture"
        val music = if (synced) display.lights else lyricsOnTheScreen
        val lights = when {
            lamp != null && (lamp.first || !music) -> Lights.MOOD
            music -> Lights.MUSIC
            else -> Lights.NONE
        }
        val screen = when {
            synced -> if ((lamp != null && lamp.first) || !display.screenOn) Screen.OFF else restore
            lamp != null && !lamp.atBox && !lamp.watching && !lyricsOnTheScreen -> Screen.OFF
            else -> restore
        }
        return Want(screen, lights)
    }
}
