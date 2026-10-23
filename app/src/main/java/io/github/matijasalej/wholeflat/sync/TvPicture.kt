package io.github.matijasalej.wholeflat.sync

/** What the picture of the TV's stream shows. */
enum class Picture(val key: String) {
    /** The splash ("Whole Flat" on the colour of the cover) while the TV warms up. */
    SPLASH("splash"),

    /** The lyrics and the cover (Synced with karaoke), or the cover alone when there are no lyrics. */
    LYRICS("lyrics"),

    /** Black: Synced without karaoke (the screen is switched off, and if the TV refuses that, it is dark all the same). */
    BLACK("black"),
}

object TvPicture {
    /** The picture for the state of the stream: the splash until the warm-up is over, then the lyrics if karaoke is on, else black. */
    fun choose(warming: Boolean, karaoke: Boolean): Picture = when {
        warming -> Picture.SPLASH
        karaoke -> Picture.LYRICS
        else -> Picture.BLACK
    }
}
