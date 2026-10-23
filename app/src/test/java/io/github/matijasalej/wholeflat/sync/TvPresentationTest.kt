package io.github.matijasalej.wholeflat.sync

import io.github.matijasalej.wholeflat.sync.TvPresentation.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvPresentationTest {
    @Test
    fun inSyncedModeWithoutKaraokeTheScreenIsOffAndSoAreTheLights() {
        val w = TvPresentation.want(Mode.SYNCED, tvPlaying = true, karaoke = false, source = "capture", lightsEnabled = true, screenOffByUs = false)
        assertEquals(Screen.OFF, w.screen)
        assertFalse(w.lights)
        // already off by us: still the same wish (nothing more to do)
        assertEquals(Screen.OFF, TvPresentation.want(Mode.SYNCED, true, false, "capture", true, true).screen)
    }

    @Test
    fun withKaraokeTheScreenIsOnAndTheLightsFollowTheAudio() {
        val w = TvPresentation.want(Mode.SYNCED, tvPlaying = true, karaoke = true, source = "capture", lightsEnabled = true, screenOffByUs = false)
        assertEquals(Screen.LEAVE, w.screen)
        assertTrue(w.lights)
        // a screen that we switched off is switched on again for the lyrics
        assertEquals(Screen.ON, TvPresentation.want(Mode.SYNCED, true, true, "capture", true, true).screen)
        // the lights can be switched off by their setting
        assertFalse(TvPresentation.want(Mode.SYNCED, true, true, "capture", false, false).lights)
    }

    @Test
    fun inLyricsModeTheLightsFollowTheAudioWhileTheMusicPlaysAndTheScreenComesBack() {
        val w = TvPresentation.want(Mode.LYRICS, tvPlaying = false, karaoke = false, source = "capture", lightsEnabled = true, screenOffByUs = true)
        assertEquals(Screen.ON, w.screen)
        assertTrue(w.lights)
        // nothing plays: no lights (what they were before is put back)
        assertFalse(TvPresentation.want(Mode.LYRICS, false, false, "idle", true, false).lights)
        // Whole Flat plays: Spotify's own playback has no lyrics on the TV
        assertFalse(TvPresentation.want(Mode.LYRICS, false, false, "librespot", true, false).lights)
        assertEquals(Screen.LEAVE, TvPresentation.want(Mode.LYRICS, false, false, "capture", true, false).screen)
    }

    @Test
    fun inSyncedModeBeforeTheTvPlaysNothingIsDone() {
        val w = TvPresentation.want(Mode.SYNCED, tvPlaying = false, karaoke = true, source = "capture", lightsEnabled = true, screenOffByUs = false)
        assertEquals(Screen.LEAVE, w.screen)
        assertFalse(w.lights)
        // and a screen we had switched off comes back when the TV stops playing our stream
        assertEquals(Screen.ON, TvPresentation.want(Mode.SYNCED, false, false, "capture", true, true).screen)
    }
}
