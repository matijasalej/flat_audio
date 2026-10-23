package io.github.matijasalej.wholeflat.sync

import io.github.matijasalej.wholeflat.sync.TvPresentation.Lamp
import io.github.matijasalej.wholeflat.sync.TvPresentation.Lights
import io.github.matijasalej.wholeflat.sync.TvPresentation.Screen
import org.junit.Assert.assertEquals
import org.junit.Test

class TvPresentationTest {
    private fun synced(display: Display, playing: Boolean = true, offByUs: Boolean = false, lamp: Lamp? = null) =
        TvPresentation.want(Mode.SYNCED, tvPlaying = playing, display = display, source = "capture", screenOffByUs = offByUs, lamp = lamp)

    /** The mood light chosen last (it has the TV), with nobody at the box. */
    private val lampFirst = Lamp(first = true, atBox = false, watching = false)

    /** The mood light chosen before the display (the music has the TV while it plays). */
    private val lampBehind = Lamp(first = false, atBox = false, watching = false)

    @Test
    fun listeningIsTheScreenOffAndTheLightsOff() {
        val w = synced(Display.LISTEN)
        assertEquals(Screen.OFF, w.screen)
        assertEquals(Lights.NONE, w.lights)
        // already off by us: still the same wish (nothing more to do)
        assertEquals(Screen.OFF, synced(Display.LISTEN, offByUs = true).screen)
    }

    @Test
    fun ambilightIsTheScreenOffAndTheLightsOn() {
        val w = synced(Display.AMBILIGHT)
        assertEquals(Screen.OFF, w.screen)
        assertEquals(Lights.MUSIC, w.lights)
    }

    @Test
    fun theScreenDisplayIsTheLyricsVideoAndTheLightsOn() {
        val w = synced(Display.SCREEN)
        assertEquals(Screen.LEAVE, w.screen)
        assertEquals(Lights.MUSIC, w.lights)
        // a screen that we switched off is switched on again for the lyrics
        assertEquals(Screen.ON, synced(Display.SCREEN, offByUs = true).screen)
    }

    @Test
    fun changingTheDisplayOnlyChangesTheScreenAndTheLights() {
        // from Screen to Listening: off and no lights; from Listening to Screen: on again (we had switched it off)
        assertEquals(Screen.OFF, synced(Display.LISTEN).screen)
        assertEquals(Screen.ON, synced(Display.SCREEN, offByUs = true).screen)
        assertEquals(Lights.MUSIC, synced(Display.SCREEN, offByUs = true).lights)
        assertEquals(Lights.NONE, synced(Display.LISTEN, offByUs = false).lights)
    }

    @Test
    fun inLyricsModeTheLightsFollowTheAudioWhileTheMusicPlaysAndTheScreenComesBack() {
        val w = TvPresentation.want(Mode.LYRICS, tvPlaying = false, display = Display.LISTEN, source = "capture", screenOffByUs = true)
        assertEquals(Screen.ON, w.screen)
        assertEquals(Lights.MUSIC, w.lights)
        // whatever the display says: lyrics are visible, so the lights are on
        assertEquals(Lights.MUSIC, TvPresentation.want(Mode.LYRICS, false, Display.AMBILIGHT, "capture", false).lights)
        // nothing plays: no lights (what they were before is put back)
        assertEquals(Lights.NONE, TvPresentation.want(Mode.LYRICS, false, Display.LISTEN, "idle", false).lights)
        // Whole Flat plays: Spotify's own playback has no lyrics on the TV
        assertEquals(Lights.NONE, TvPresentation.want(Mode.LYRICS, false, Display.LISTEN, "librespot", false).lights)
        assertEquals(Screen.LEAVE, TvPresentation.want(Mode.LYRICS, false, Display.LISTEN, "capture", false).screen)
    }

    @Test
    fun inSyncedModeBeforeTheTvPlaysNothingIsDone() {
        val w = synced(Display.SCREEN, playing = false)
        assertEquals(Screen.LEAVE, w.screen)
        assertEquals(Lights.NONE, w.lights)
        // and a screen we had switched off comes back when the TV stops playing our stream
        assertEquals(Screen.ON, synced(Display.LISTEN, playing = false, offByUs = true).screen)
    }

    @Test
    fun withoutMusicTheMoodLightIsALampWithTheScreenOff() {
        val w = synced(Display.SCREEN, playing = false, lamp = lampFirst)
        assertEquals(Lights.MOOD, w.lights)
        assertEquals(Screen.OFF, w.screen)
        // chosen before the display or after it: without music it has the TV
        assertEquals(Lights.MOOD, synced(Display.AMBILIGHT, playing = false, lamp = lampBehind).lights)
        assertEquals(Screen.OFF, synced(Display.AMBILIGHT, playing = false, lamp = lampBehind).screen)
        // and in Lyrics mode with nothing playing
        assertEquals(Lights.MOOD, TvPresentation.want(Mode.LYRICS, false, Display.LISTEN, "idle", false, lampBehind).lights)
    }

    @Test
    fun theMoodLightLeavesThePictureToSomeoneAtTheBoxOrWatching() {
        val atBox = Lamp(first = true, atBox = true, watching = false)
        val watching = Lamp(first = true, atBox = false, watching = true)
        for (lamp in listOf(atBox, watching)) {
            assertEquals(Lights.MOOD, synced(Display.LISTEN, playing = false, lamp = lamp).lights)
            // a screen that we switched off for the lamp comes back; one we did not touch is left alone
            assertEquals(Screen.ON, synced(Display.LISTEN, playing = false, offByUs = true, lamp = lamp).screen)
            assertEquals(Screen.LEAVE, synced(Display.LISTEN, playing = false, offByUs = false, lamp = lamp).screen)
        }
    }

    @Test
    fun theLastTapWinsWhileTheMusicPlays() {
        // the mood chosen after the display: the lamp, whatever the display
        for (d in Display.values()) {
            val w = synced(d, lamp = lampFirst)
            assertEquals(d.key, Lights.MOOD, w.lights)
            assertEquals(d.key, Screen.OFF, w.screen)
        }
        // a display chosen after the mood: the display as without the lamp, except that Listening keeps the mood's colour
        assertEquals(Lights.MUSIC, synced(Display.AMBILIGHT, lamp = lampBehind).lights)
        assertEquals(Screen.OFF, synced(Display.AMBILIGHT, lamp = lampBehind).screen)
        assertEquals(Lights.MUSIC, synced(Display.SCREEN, offByUs = true, lamp = lampBehind).lights)
        assertEquals(Screen.ON, synced(Display.SCREEN, offByUs = true, lamp = lampBehind).screen)
        assertEquals(Lights.MOOD, synced(Display.LISTEN, lamp = lampBehind).lights)
        assertEquals(Screen.OFF, synced(Display.LISTEN, lamp = lampBehind).screen)
    }

    @Test
    fun whenTheMusicEndsTheMoodComesBack() {
        assertEquals(Lights.MUSIC, synced(Display.AMBILIGHT, playing = true, lamp = lampBehind).lights)
        val after = synced(Display.AMBILIGHT, playing = false, offByUs = true, lamp = lampBehind)
        assertEquals(Lights.MOOD, after.lights)
        assertEquals(Screen.OFF, after.screen)
    }

    @Test
    fun inLyricsModeTheSpotifyAppStaysOnTheScreenWithTheLightOfTheLastTap() {
        val first = TvPresentation.want(Mode.LYRICS, false, Display.LISTEN, "capture", true, lampFirst)
        assertEquals(Lights.MOOD, first.lights)
        assertEquals(Screen.ON, first.screen)
        val behind = TvPresentation.want(Mode.LYRICS, false, Display.LISTEN, "capture", true, lampBehind)
        assertEquals(Lights.MUSIC, behind.lights)
        assertEquals(Screen.ON, behind.screen)
    }

    @Test
    fun withTheMoodLightOffTheScreenComesBackAndTheLightsArePutBack() {
        val w = synced(Display.LISTEN, playing = false, offByUs = true, lamp = null)
        assertEquals(Lights.NONE, w.lights)
        assertEquals(Screen.ON, w.screen)
    }
}
