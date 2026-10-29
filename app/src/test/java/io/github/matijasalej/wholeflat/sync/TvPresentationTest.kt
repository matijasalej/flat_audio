package io.github.matijasalej.wholeflat.sync

import io.github.matijasalej.wholeflat.sync.TvPresentation.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvPresentationTest {
    private fun synced(display: Display, playing: Boolean = true, offByUs: Boolean = false) =
        TvPresentation.want(Mode.SYNCED, tvPlaying = playing, display = display, source = "capture", screenOffByUs = offByUs)

    @Test
    fun listeningIsTheScreenOffAndTheLightsOff() {
        val w = synced(Display.LISTEN)
        assertEquals(Screen.OFF, w.screen)
        assertFalse(w.lights)
        // already off by us: still the same wish (nothing more to do)
        assertEquals(Screen.OFF, synced(Display.LISTEN, offByUs = true).screen)
    }

    @Test
    fun partyAndChillAreTheScreenOffAndTheLightsOn() {
        for (d in listOf(Display.PARTY, Display.CHILL)) {
            val w = synced(d)
            assertEquals(Screen.OFF, w.screen)
            assertTrue(w.lights)
        }
    }

    @Test
    fun karaokeIsTheScreenAndTheLightsOn() {
        val w = synced(Display.KARAOKE)
        assertEquals(Screen.LEAVE, w.screen)
        assertTrue(w.lights)
        // a screen that we switched off is switched on again for the lyrics
        assertEquals(Screen.ON, synced(Display.KARAOKE, offByUs = true).screen)
    }

    @Test
    fun changingTheDisplayOnlyChangesTheScreenAndTheLights() {
        // from Screen to Listening: off and no lights; from Listening to Screen: on again (we had switched it off)
        assertEquals(Screen.OFF, synced(Display.LISTEN).screen)
        assertEquals(Screen.ON, synced(Display.KARAOKE, offByUs = true).screen)
        assertTrue(synced(Display.KARAOKE, offByUs = true).lights)
        assertFalse(synced(Display.LISTEN, offByUs = false).lights)
    }

    @Test
    fun inLyricsModeTheLightsComeOnlyWhenTheDisplayAsksForThemAndTheScreenComesBack() {
        val w = TvPresentation.want(Mode.LYRICS, tvPlaying = false, display = Display.LISTEN, source = "capture", screenOffByUs = true)
        assertEquals(Screen.ON, w.screen)
        // Listening: the music alone does not light the Ambilight (it came on with every song in Lyrics mode)
        assertFalse(w.lights)
        assertFalse(TvPresentation.want(Mode.LYRICS, false, Display.LISTEN, "capture", false).lights)
        // the user picked the lights: on while the music plays
        assertTrue(TvPresentation.want(Mode.LYRICS, false, Display.PARTY, "capture", false).lights)
        assertTrue(TvPresentation.want(Mode.LYRICS, false, Display.KARAOKE, "capture", false).lights)
        // nothing plays: no lights (what they were before is put back)
        assertFalse(TvPresentation.want(Mode.LYRICS, false, Display.PARTY, "idle", false).lights)
        // Whole Flat plays: Spotify's own playback has no lyrics on the TV
        assertFalse(TvPresentation.want(Mode.LYRICS, false, Display.PARTY, "librespot", false).lights)
        assertEquals(Screen.LEAVE, TvPresentation.want(Mode.LYRICS, false, Display.LISTEN, "capture", false).screen)
    }

    @Test
    fun inSyncedModeBeforeTheTvPlaysNothingIsDone() {
        val w = synced(Display.KARAOKE, playing = false)
        assertEquals(Screen.LEAVE, w.screen)
        assertFalse(w.lights)
        // and a screen we had switched off comes back when the TV stops playing our stream
        assertEquals(Screen.ON, synced(Display.LISTEN, playing = false, offByUs = true).screen)
    }
}
