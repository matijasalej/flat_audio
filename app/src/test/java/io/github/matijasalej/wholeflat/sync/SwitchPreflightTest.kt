package io.github.matijasalej.wholeflat.sync

import io.github.matijasalej.wholeflat.sync.SwitchPreflight.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwitchPreflightTest {
    @Test
    fun anEmptyOrStoppedTransportIsSetAtOnceWithNoStop() {
        for (t in listOf("NO_MEDIA_PRESENT", "STOPPED")) {
            val p = SwitchPreflight()
            assertEquals(t, Step.SET, p.next(0, t))
            assertFalse(p.stopped)
            assertEquals(t, p.first)
        }
    }

    @Test
    fun aTransportThatDoesNotAnswerIsLeftToTheTriesOfTheItem() {
        assertEquals(Step.SET, SwitchPreflight().next(0, ""))
    }

    @Test
    fun aTransportThatHoldsSomethingIsStoppedOnceThenSet() {
        val p = SwitchPreflight(afterStopMs = 3_000)
        assertEquals(Step.STOP, p.next(0, "PLAYING"))
        assertTrue(p.stopped)
        assertEquals(Step.WAIT, p.next(1_000, "PLAYING"))
        // it stopped
        assertEquals(Step.SET, p.next(2_000, "STOPPED"))
        // or it did not, in time: the item is set anyway (its own tries take the 705s)
        val q = SwitchPreflight(afterStopMs = 3_000)
        q.next(0, "PAUSED_PLAYBACK")
        assertEquals(Step.WAIT, q.next(2_000, "PAUSED_PLAYBACK"))
        assertEquals(Step.SET, q.next(3_000, "PAUSED_PLAYBACK"))
    }

    @Test
    fun aTransitioningTransportIsWaitedForThenStoppedOnceThenHung() {
        val p = SwitchPreflight(busyWaitMs = 5_000, afterStopMs = 3_000)
        assertEquals(Step.WAIT, p.next(0, "TRANSITIONING"))
        assertEquals(Step.WAIT, p.next(4_000, "TRANSITIONING"))
        assertEquals(Step.STOP, p.next(5_000, "TRANSITIONING"))
        assertEquals(Step.WAIT, p.next(7_000, "TRANSITIONING"))
        assertEquals(Step.HUNG, p.next(8_000, "TRANSITIONING"))
        assertEquals("TRANSITIONING", p.first)
    }

    @Test
    fun aTransitionThatEndsGoesOn() {
        val p = SwitchPreflight()
        assertEquals(Step.WAIT, p.next(0, "TRANSITIONING"))
        assertEquals(Step.SET, p.next(1_000, "NO_MEDIA_PRESENT"))
        val q = SwitchPreflight(busyWaitMs = 1_000)
        q.next(0, "TRANSITIONING")
        assertEquals(Step.STOP, q.next(1_000, "TRANSITIONING"))
        assertEquals(Step.SET, q.next(2_000, "STOPPED"))
    }
}
