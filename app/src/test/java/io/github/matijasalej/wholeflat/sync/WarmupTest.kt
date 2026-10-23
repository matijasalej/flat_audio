package io.github.matijasalej.wholeflat.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WarmupTest {
    private val s = 1_000_000_000L
    private fun ms(v: Long) = v * 1_000_000L

    private fun started(): Warmup = Warmup().also { it.start(0) }

    @Test
    fun theTvHasSettledAfterTwoAndAHalfSecondsOnOneConnectionWithoutAnotherGet() {
        val w = started()
        w.onGet(ms(1_400))
        assertFalse(w.poll(ms(3_000), 1, true))
        assertFalse(w.poll(ms(3_899), 1, true))
        assertTrue(w.poll(ms(3_900), 1, true))
        assertTrue(w.live)
        assertEquals(3_900L, w.durationMs(ms(9_999)))
        assertTrue(w.reason.startsWith("settled"))
    }

    @Test
    fun everyNewGetStartsTheWaitAgain() {
        val w = started()
        w.onGet(ms(1_400))
        w.onGet(ms(1_900)) // the TV opens its second connection
        w.onGet(ms(2_200))
        w.onGet(ms(3_500)) // and the fourth, which it stays on
        assertFalse(w.poll(ms(5_000), 1, true))
        assertTrue(w.poll(ms(6_000), 1, true))
        assertEquals(4, w.gets)
        assertEquals(6_000L, w.durationMs(ms(7_000)))
    }

    @Test
    fun itNeedsExactlyOneConnectionOpenAndTheTvPlaying() {
        val w = started()
        w.onGet(ms(1_000))
        assertFalse(w.poll(ms(5_000), 2, true))
        assertFalse(w.poll(ms(5_000), 0, true))
        assertFalse(w.poll(ms(5_000), 1, false))
        assertTrue(w.poll(ms(5_000), 1, true))
    }

    @Test
    fun noGetMeansNoSettling() {
        val w = started()
        assertFalse(w.poll(ms(10_000), 1, true))
        assertEquals(-1L, w.sinceLastGetMs(ms(10_000)))
    }

    @Test
    fun itEndsAfterTheLongestWarmUpWhateverTheTvDoes() {
        val w = started()
        for (t in 1..20) w.onGet(ms(t * 1_000L)) // a TV that keeps reconnecting
        assertFalse(w.poll(ms(24_999), 3, true))
        assertTrue(w.poll(ms(25_000), 3, true))
        assertTrue(w.reason.startsWith("timed out"))
        assertEquals(25_000L, w.durationMs(ms(30_000)))
    }

    @Test
    fun itEndsOnlyOnce() {
        val w = started()
        w.onGet(ms(100))
        assertTrue(w.poll(ms(3_000), 1, true))
        assertFalse(w.poll(ms(4_000), 1, true))
        assertFalse(w.poll(ms(40_000), 5, false))
        assertTrue(w.live)
    }

    @Test
    fun startingAgainBeginsAnotherWarmUp() {
        val w = started()
        w.onGet(ms(100))
        w.poll(ms(3_000), 1, true)
        w.start(ms(100_000))
        assertFalse(w.live)
        assertEquals(0, w.gets)
        assertFalse(w.poll(ms(105_000), 1, true))
        w.onGet(ms(101_000))
        assertTrue(w.poll(ms(103_500), 1, true))
        assertEquals(3_500L, w.durationMs(ms(200_000)))
    }

    @Test
    fun theWaitCanBeSetToOtherTimes() {
        val w = Warmup(settleNanos = 1 * s, maxNanos = 4 * s)
        w.start(0)
        w.onGet(ms(500))
        assertTrue(w.poll(ms(1_500), 1, true))
        val x = Warmup(settleNanos = 1 * s, maxNanos = 4 * s).also { it.start(0) }
        assertTrue(x.poll(ms(4_000), 0, false))
    }
}
