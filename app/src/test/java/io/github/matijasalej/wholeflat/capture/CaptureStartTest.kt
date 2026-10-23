package io.github.matijasalej.wholeflat.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureStartTest {
    private val rate = 48_000

    @Test
    fun theStreamStartsTheLeadBeforeNow() {
        val p = CaptureStart.plan(nowFrame = 480_000, writeIndex = 478_000, validStart = 94_000, rate = rate, leadMs = 200, offsetMs = 0, lkMs = 290.0)
        assertEquals(480_000L - 9_600L, p.startFrame)
        assertEquals(9_600L, p.aheadFrames)
        assertEquals(490.0, p.plannedLagMs, 0.001)
        assertFalse(p.limited)
    }

    @Test
    fun aKitchenOffsetMovesTheStartBackOrForward() {
        val later = CaptureStart.plan(480_000, 478_000, 94_000, rate, leadMs = 200, offsetMs = 100, lkMs = 290.0)
        assertEquals(480_000L - 14_400L, later.startFrame)
        assertEquals(590.0, later.plannedLagMs, 0.001)
        val earlier = CaptureStart.plan(480_000, 478_000, 94_000, rate, leadMs = 200, offsetMs = -100, lkMs = 290.0)
        assertEquals(480_000L - 4_800L, earlier.startFrame)
        assertEquals(390.0, earlier.plannedLagMs, 0.001)
    }

    @Test
    fun theLeadIsNeverLessThanTheMinimum() {
        val p = CaptureStart.plan(480_000, 480_000, 94_000, rate, leadMs = 100, offsetMs = -90, lkMs = 290.0)
        assertEquals(480_000L - 1_920L, p.startFrame)
        assertTrue(p.limited)
        assertEquals(290.0 + CaptureStart.MIN_LEAD_MS, p.plannedLagMs, 0.001)
    }

    @Test
    fun theStartIsNeverPastTheNewestFrames() {
        // the clock thinks it is further on than what has been written
        val p = CaptureStart.plan(nowFrame = 500_000, writeIndex = 480_000, validStart = 96_000, rate = rate, leadMs = 20, offsetMs = 0, lkMs = 290.0)
        assertEquals(480_000L - 1_920L, p.startFrame)
        assertTrue(p.limited)
    }

    @Test
    fun theStartIsNeverBeforeWhatTheBufferHolds() {
        // the buffer holds only the last 2 s; an offset of 2 s wants more than that
        val p = CaptureStart.plan(nowFrame = 1_000_000, writeIndex = 998_000, validStart = 902_000, rate = rate, leadMs = 200, offsetMs = 2_000, lkMs = 290.0)
        assertEquals(902_000L + 12_000L, p.startFrame)
        assertTrue(p.limited)
        assertEquals(290.0 + (1_000_000L - 914_000L) / 48.0, p.plannedLagMs, 0.001)
    }

    @Test
    fun aCaptureThatJustStartedBeginsAtItsFirstFrame() {
        val p = CaptureStart.plan(nowFrame = 3_000, writeIndex = 2_000, validStart = 0, rate = rate, leadMs = 200, offsetMs = 0, lkMs = 290.0)
        assertEquals(0L, p.startFrame)
        assertTrue(p.limited)
        assertTrue(p.aheadFrames >= 0)
    }

    @Test
    fun theStartIsNeverNegative() {
        val p = CaptureStart.plan(nowFrame = 100, writeIndex = 90, validStart = 0, rate = rate, leadMs = 200, offsetMs = 0, lkMs = 290.0)
        assertEquals(0L, p.startFrame)
    }
}
