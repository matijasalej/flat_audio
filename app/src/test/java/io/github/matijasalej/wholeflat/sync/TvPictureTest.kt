package io.github.matijasalej.wholeflat.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class TvPictureTest {
    @Test
    fun theSplashShowsUntilTheTvHasSettledWhateverTheOtherSettingsAre() {
        assertEquals(Picture.SPLASH, TvPicture.choose(warming = true, lyrics = false))
        assertEquals(Picture.SPLASH, TvPicture.choose(warming = true, lyrics = true))
    }

    @Test
    fun afterwardsTheScreenDisplayShowsTheLyricsAndTheOtherTwoDisplaysAreBlack() {
        assertEquals(Picture.LYRICS, TvPicture.choose(warming = false, lyrics = true))
        assertEquals(Picture.BLACK, TvPicture.choose(warming = false, lyrics = false))
    }
}
