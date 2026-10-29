package io.github.matijasalej.wholeflat.sync

/**
 * What the TV shows while the music plays (the kitchen is the same in all four); the setting `display`, chosen on the phone page or with
 * the button of the TV app, kept until it is changed (and put back to Listening after ten minutes without music). Changing it is only the
 * screen and the Ambilight of the TV: the stream is not restarted. Its lights hold in Lyrics mode too: the Ambilight is on only when the
 * display asks for it.
 *
 * The older keys "ambilight" (screen off, lights on) and "screen" (screen and lights on) are read as Party and Karaoke.
 */
enum class Display(val key: String, val label: String) {
    /** The screen is off, the Ambilight is off. */
    LISTEN("listen", "Listening"),

    /** The screen is off, the Ambilight is one colour of his choice (`light_color`, `light_level`; see [MoodLight]). */
    CHILL("chill", "Chill"),

    /** The screen is off, the Ambilight is the mood lights: the song's colours, the TV's beat modes, Party at a chorus. */
    PARTY("party", "Party"),

    /** The screen is on (the lyrics), the Ambilight is the mood lights as in Party. */
    KARAOKE("karaoke", "Karaoke"),
    ;

    /** The screen is to be seen. */
    val screenOn: Boolean get() = this == KARAOKE

    /** The Ambilight is on. */
    val lights: Boolean get() = this != LISTEN

    /** The Ambilight is the mood lights (`light.LightEngine`). */
    val vibe: Boolean get() = this == PARTY || this == KARAOKE

    /** The next one in the order of the button of the TV app: Listening, Chill, Party, Karaoke, Listening. */
    fun next(): Display = values()[(ordinal + 1) % values().size]

    companion object {
        val DEFAULT = LISTEN

        private val OLD = mapOf("ambilight" to PARTY, "screen" to KARAOKE)

        /** The setting [key] (any case, the older keys too); null if it is none of them. */
        fun of(key: String?): Display? {
            val k = key?.trim()?.lowercase() ?: return null
            return values().firstOrNull { it.key == k } ?: OLD[k]
        }
    }
}
