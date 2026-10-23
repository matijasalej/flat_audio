package io.github.matijasalej.wholeflat.sync

/**
 * The two ways the rooms are fed in capture mode (the setting `mode`).
 *
 * - **Synced**: the living room is fed through the TV's own DLNA player and the kitchen from the same capture, both held to one delay,
 *   so the rooms are in step. The TV shows its player (or its picture is off).
 * - **Lyrics**: the living room plays straight from the Chromecast over HDMI (the Spotify app stays on the screen, with its lyrics) and
 *   the kitchen follows at the lowest lag it keeps clean; the rooms are about 0.1 s apart.
 */
enum class Mode(val key: String) {
    SYNCED("synced"),
    LYRICS("lyrics"),
    ;

    companion object {
        /** The mode of a setting value, any case; null for anything else. */
        fun of(text: String?): Mode? = values().firstOrNull { it.key.equals(text?.trim(), ignoreCase = true) }
    }
}
