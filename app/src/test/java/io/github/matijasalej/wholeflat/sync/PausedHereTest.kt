package io.github.matijasalej.wholeflat.sync

import io.github.matijasalej.wholeflat.sync.SonosPolicy.Availability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PausedHereTest {
    private val ours = "http://192.168.0.165:8080/k/ef5a471aa109.wav"

    @Test
    fun aStreamOfOursPausedOnTheSpeakerKeepsTheKitchenOutWhileItStaysPausedThere() {
        val p = PausedHere()
        p.paused("ef5a471aa109")
        assertEquals("ef5a471aa109", p.held)
        assertTrue(p.busy(Availability.OURS, "PAUSED_PLAYBACK", ours))
        assertTrue(p.busy(Availability.OURS, "PAUSED_PLAYBACK", ours))
        assertEquals("ef5a471aa109", p.held)
    }

    @Test
    fun theSpeakerThatPlaysOrStopsFreesTheKitchenAndTheMemoryOfThePauseIsGone() {
        val p = PausedHere()
        p.paused("ef5a471aa109")
        assertFalse(p.busy(Availability.OURS, "PLAYING", ours))
        assertNull(p.held)
        // paused again later: it is nobody's now
        assertFalse(p.busy(Availability.OURS, "PAUSED_PLAYBACK", ours))
        val q = PausedHere()
        q.paused("ef5a471aa109")
        assertFalse(q.busy(Availability.OURS, "STOPPED", ours))
        assertNull(q.held)
    }

    @Test
    fun aPausedStreamOfAnEarlierSessionOrRunIsNobodys() {
        val p = PausedHere()
        assertFalse(p.busy(Availability.OURS, "PAUSED_PLAYBACK", ours))
        p.paused("ef5a471aa109")
        assertFalse(p.busy(Availability.OURS, "PAUSED_PLAYBACK", "http://192.168.0.165:8080/k/00112233.wav"))
        assertNull(p.held)
    }

    @Test
    fun somethingElseThatPlaysIsBusyWhateverWasPaused() {
        val p = PausedHere()
        assertTrue(p.busy(Availability.BUSY, "PLAYING", "x-sonos-spotify:abc"))
        assertFalse(p.busy(Availability.FREE, "PAUSED_PLAYBACK", "x-sonos-vli:abc"))
    }

    @Test
    fun aPowerCycleEndsThePauseSoThePausedLeftoverOfOursIsFree() {
        // the power button pauses first, then the speaker says goodbye and later answers again with our stream still loaded and paused
        val p = PausedHere()
        p.paused("ef5a471aa109")
        assertTrue(p.busy(Availability.OURS, "PAUSED_PLAYBACK", ours))
        assertTrue(p.offline())
        assertNull(p.held)
        assertFalse(p.busy(Availability.OURS, "PAUSED_PLAYBACK", ours))
    }

    @Test
    fun goingOfflineWithoutAPauseChangesNothing() {
        val p = PausedHere()
        assertFalse(p.offline())
        assertFalse(p.busy(Availability.OURS, "PAUSED_PLAYBACK", ours))
    }
}
