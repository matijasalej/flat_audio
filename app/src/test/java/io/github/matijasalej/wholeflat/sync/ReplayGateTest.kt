package io.github.matijasalej.wholeflat.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayGateTest {
    private val mediashell = ForegroundPolicy.MEDIASHELL

    @Test
    fun theMutedSpotifyAppIsReplayedWhileTheCaptureRuns() {
        val d = ReplayGate.decide(muted = true, captureRunning = true, otherPlaying = null)
        assertTrue(d.replay)
        assertFalse(d.silent)
        assertNull(ReplayGate.warning(d))
        // the Spotify app's own session playing is no other player
        assertTrue(ReplayGate.decide(muted = true, captureRunning = true, otherPlaying = ReplayGate.SPOTIFY_TV).replay)
    }

    @Test
    fun aSpotifyAppThatIsNotMutedIsHeardDirectlyAndNotReplayed() {
        val d = ReplayGate.decide(muted = false, captureRunning = true, otherPlaying = null)
        assertFalse(d.replay)
        assertFalse(d.silent)
        assertNull(ReplayGate.warning(d))
    }

    @Test
    fun aMutedAppWithoutTheCaptureLeavesTheRoomSilentAndThePhoneSaysSo() {
        val d = ReplayGate.decide(muted = true, captureRunning = false, otherPlaying = null)
        assertFalse(d.replay)
        assertTrue(d.silent)
        val w = ReplayGate.warning(d)
        assertNotNull(w)
        assertTrue(w!!.contains(ReplayGate.UNMUTE_COMMAND))
    }

    @Test
    fun aMuteThatCannotBeReadIsNotReplayedAndWarned() {
        val d = ReplayGate.decide(muted = null, captureRunning = true, otherPlaying = null)
        assertFalse(d.replay)
        assertTrue(d.silent)
    }

    @Test
    fun aSleepingBoxIsNotReplayedAndThatIsNoWarning() {
        val d = ReplayGate.decide(muted = true, captureRunning = false, otherPlaying = null, boxAwake = false)
        assertFalse(d.replay)
        assertFalse(d.silent)
        assertNull(ReplayGate.warning(d))
        // awake again: replayed
        assertTrue(ReplayGate.decide(muted = true, captureRunning = true, otherPlaying = null, boxAwake = true).replay)
    }

    @Test
    fun aPlayingCastReceiverIsCapturedButNotMutedSoItIsNotReplayed() {
        val d = ReplayGate.decide(muted = true, captureRunning = true, otherPlaying = mediashell)
        assertFalse(d.replay)
        assertFalse(d.silent)
        assertTrue(d.why.contains(mediashell))
    }

    @Test
    fun theCommandsNameTheSpotifyAppAndTheOp() {
        assertEquals("adb shell cmd appops set com.spotify.tv.android PLAY_AUDIO ignore", ReplayGate.MUTE_COMMAND)
        assertEquals("adb shell cmd appops set com.spotify.tv.android PLAY_AUDIO allow", ReplayGate.UNMUTE_COMMAND)
    }
}
