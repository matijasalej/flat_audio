package io.github.matijasalej.wholeflat.sync

/** What the picture of the TV's stream shows. */
enum class Picture(val key: String) {
    /** The splash ("Whole Flat" on the colour of the cover) while the TV warms up. */
    SPLASH("splash"),

    /** The lyrics and the cover (display "Screen + Ambilight"), or the cover alone when there are no lyrics. */
    LYRICS("lyrics"),

    /** Black: the other two displays (the screen is switched off, and if the TV refuses that, it is dark all the same). */
    BLACK("black"),
}

object TvPicture {
    /** The picture for the state of the stream: the splash until the warm-up is over, then the lyrics if the screen is to show them, else black. */
    fun choose(warming: Boolean, lyrics: Boolean): Picture = when {
        warming -> Picture.SPLASH
        lyrics -> Picture.LYRICS
        else -> Picture.BLACK
    }
}
