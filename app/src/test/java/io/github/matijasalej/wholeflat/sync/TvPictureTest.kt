package io.github.matijasalej.wholeflat.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class TvPictureTest {
    @Test
    fun theSplashShowsUntilTheTvHasSettledWhateverTheOtherSettingsAre() {
        assertEquals(Picture.SPLASH, TvPicture.choose(warming = true, karaoke = false))
        assertEquals(Picture.SPLASH, TvPicture.choose(warming = true, karaoke = true))
    }

    @Test
    fun afterwardsKaraokeShowsTheLyricsAndNoKaraokeIsBlack() {
        assertEquals(Picture.LYRICS, TvPicture.choose(warming = false, karaoke = true))
        assertEquals(Picture.BLACK, TvPicture.choose(warming = false, karaoke = false))
    }
}
