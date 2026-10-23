package io.github.matijasalej.wholeflat.sync

/**
 * What the TV shows while it plays the music of Synced mode (the lyrics video and the kitchen are the same in all three); the setting
 * `display`, chosen on the phone page or with the button of the TV app, kept until it is changed (and put back to Listening after ten
 * minutes without music). Changing it is only the screen and the Ambilight of the TV: the stream is not restarted.
 */
enum class Display(val key: String, val label: String) {
    /** The screen is off, the Ambilight is off. */
    LISTEN("listen", "Listening"),

    /** The screen is off, the Ambilight follows the audio. */
    AMBILIGHT("ambilight", "Ambilight"),

    /** The lyrics video is on the screen, the Ambilight follows the audio. */
    SCREEN("screen", "Screen + Ambilight"),
    ;

    /** The lyrics video is to be seen. */
    val screenOn: Boolean get() = this == SCREEN

    /** The Ambilight follows the audio. */
    val lights: Boolean get() = this != LISTEN

    /** The next one in the order of the button of the TV app: Listening, Ambilight, Screen + Ambilight, Listening. */
    fun next(): Display = values()[(ordinal + 1) % values().size]

    companion object {
        val DEFAULT = LISTEN

        /** The setting [key] (any case); null if it is none of the three. */
        fun of(key: String?): Display? = values().firstOrNull { it.key.equals(key?.trim(), ignoreCase = true) }
    }
}
