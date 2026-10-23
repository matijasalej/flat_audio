package io.github.matijasalej.wholeflat.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureLeadTest {
    @Test
    fun withoutAnythingLearnedTheSettingIsUsed() {
        val lead = CaptureLead()
        assertEquals(150, lead.effectiveMs(150))
        assertEquals(0, lead.learnedMs)
    }

    @Test
    fun aLagClearlyLaterThanExpectedTeachesTheLeadItIsMadeOf() {
        val lead = CaptureLead()
        // expected 440 ms, settled at 790: Play-to-sound 290 leaves 500 ms of lead
        assertTrue(lead.onLocked(790.0, 440.0, 290.0, 0))
        assertEquals(500, lead.learnedMs)
        assertEquals(500, lead.effectiveMs(150))
        // a setting bigger than what was learned still wins
        assertEquals(800, lead.effectiveMs(800))
    }

    @Test
    fun theKitchenOffsetIsNotPartOfTheLead() {
        val lead = CaptureLead()
        assertTrue(lead.onLocked(890.0, 540.0, 290.0, 100))
        assertEquals(500, lead.learnedMs)
    }

    @Test
    fun aDifferenceWithinTheMarginTeachesNothing() {
        val lead = CaptureLead()
        assertFalse(lead.onLocked(530.0, 440.0, 290.0, 0))
        assertFalse(lead.onLocked(540.0, 440.0, 290.0, 0))
        assertEquals(0, lead.learnedMs)
    }

    @Test
    fun theLeadIsOnlyEverRaised() {
        val lead = CaptureLead()
        lead.onLocked(1_090.0, 440.0, 290.0, 0)
        assertEquals(800, lead.learnedMs)
        // a later, smaller step does not lower it
        assertFalse(lead.onLocked(700.0, 440.0, 290.0, 0))
        assertEquals(800, lead.learnedMs)
    }

    @Test
    fun theLeadIsCapped() {
        val lead = CaptureLead(maxMs = 2_000)
        lead.onLocked(9_000.0, 440.0, 290.0, 0)
        assertEquals(2_000, lead.learnedMs)
    }

    @Test
    fun anUnknownExpectationTeachesNothingAndResetForgets() {
        val lead = CaptureLead()
        assertFalse(lead.onLocked(900.0, Double.NaN, 290.0, 0))
        lead.onLocked(900.0, 440.0, 290.0, 0)
        assertTrue(lead.learnedMs > 0)
        lead.reset()
        assertEquals(0, lead.learnedMs)
    }
}
