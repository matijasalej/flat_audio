package io.github.matijasalej.wholeflat.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureClockTest {
    private val rate = 48_000
    private val base = 7_000_000_000_000L

    private fun nanosOf(frame: Long, offset: Long = 0): Long = base + offset + frame * 1_000_000_000L / rate

    @Test
    fun withoutAnAnchorNothingIsKnown() {
        val c = CaptureClock(rate)
        assertFalse(c.hasAnchor)
        assertNull(c.nanosOfFrame(100))
        assertNull(c.frameAtNanos(base))
    }

    @Test
    fun framesAndTimesConvertBothWays() {
        val c = CaptureClock(rate)
        c.add(48_000, nanosOf(48_000))
        assertEquals(nanosOf(0), c.nanosOfFrame(0))
        assertEquals(nanosOf(96_000), c.nanosOfFrame(96_000))
        assertEquals(96_000L, c.frameAtNanos(nanosOf(96_000)))
        assertEquals(48_000L + 480, c.frameAtNanos(nanosOf(48_000) + 10_000_000L))
    }

    @Test
    fun jitterOfTheReadTimesIsSmoothedAway() {
        val c = CaptureClock(rate)
        val rnd = java.util.Random(3)
        var worst = 0L
        var sum = 0L
        var n = 0
        for (i in 1..300) {
            val frame = 960L * i
            val jitter = (rnd.nextGaussian() * 1_500_000.0).toLong().coerceIn(-5_000_000L, 5_000_000L)
            c.add(frame, nanosOf(frame, 20_000_000L) + jitter)
            if (i > 20) {
                val err = Math.abs(c.nanosOfFrame(frame)!! - nanosOf(frame, 20_000_000L))
                worst = maxOf(worst, err)
                sum += err
                n++
            }
        }
        assertTrue("worst error $worst ns", worst < 3_500_000L)
        assertTrue("mean error ${sum / n} ns", sum / n < 1_000_000L)
    }

    @Test
    fun resetForgetsTheAnchor() {
        val c = CaptureClock(rate)
        c.add(1_000, nanosOf(1_000))
        c.reset()
        assertFalse(c.hasAnchor)
        assertNull(c.nanosOfFrame(0))
    }
}
