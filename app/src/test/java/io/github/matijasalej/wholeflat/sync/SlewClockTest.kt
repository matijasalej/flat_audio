package io.github.matijasalej.wholeflat.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlewClockTest {
    private val rate = 44_100
    private val s = 1_000_000_000L

    @Test
    fun withoutNewsFromTheCaptureItCountsFramesInRealTime() {
        val c = SlewClock(rate, 1_000, 5 * s)
        assertEquals(1_000.0, c.frameAt(5 * s), 1e-9)
        assertEquals(1_000.0 + rate, c.frameAt(6 * s), 1e-6)
        assertEquals(5 * s, c.nanosOf(1_000))
        assertEquals(6 * s, c.nanosOf(1_000L + rate))
    }

    @Test
    fun frameAndTimeAreInversesOfEachOther() {
        val c = SlewClock(rate, 123_456, 77 * s)
        for (f in listOf(123_456L, 200_000L, 5_000_000L)) {
            val t = c.nanosOf(f)
            assertEquals(f.toDouble(), c.frameAt(t), 0.05)
        }
    }

    @Test
    fun aFastCaptureClockIsFollowedSlowlyAndNeverByAStep() {
        val c = SlewClock(rate, 0, 0, maxSlewPpm = 200.0)
        // the capture clock runs 100 ppm fast: after t seconds it is at t * rate * (1 + 1e-4) frames
        var lastFrames = c.frameAt(0)
        var maxJump = 0.0
        for (i in 1..600) {
            val t = i * s / 10 // 60 s in steps of 0.1 s
            c.update(t, t.toDouble() / s * rate * (1 + 1e-4))
            val f = c.frameAt(t)
            maxJump = maxOf(maxJump, f - lastFrames - rate / 10.0)
            lastFrames = f
        }
        // it caught up with the 100 ppm (6 ms in 60 s = 265 frames) and each step was only the slew of 0.1 s at most
        val expected = 60.0 * rate * 1e-4
        assertEquals(expected, c.adjustMs * rate / 1000.0, 3.0)
        assertTrue("max extra frames in 0.1 s: $maxJump", maxJump <= 200e-6 * 0.1 * rate + 1e-6)
    }

    @Test
    fun aJumpOfTheCaptureClockIsTakenInAtTheMostOfTheSlewRate() {
        val c = SlewClock(rate, 0, 0, maxSlewPpm = 200.0)
        c.update(1 * s, 1.0 * rate + 441) // 10 ms ahead at once
        // 1 s at 200 ppm is 0.2 ms: 8.8 frames of the 441
        assertEquals(8.82, c.frameAt(1 * s) - rate, 0.01)
        assertTrue(c.adjustMs < 0.3)
    }

    @Test
    fun noModelFrameChangesNothing() {
        val c = SlewClock(rate, 0, 0)
        c.update(1 * s, null)
        assertEquals(0.0, c.adjustMs, 0.0)
        assertEquals(rate.toDouble(), c.frameAt(1 * s), 1e-6)
    }

    @Test
    fun timeThatDoesNotAdvanceAddsNothing() {
        val c = SlewClock(rate, 0, 0)
        c.update(1 * s, 1.0 * rate + 100)
        val a = c.adjustMs
        c.update(1 * s, 1.0 * rate + 100)
        c.update(1 * s - 5, 1.0 * rate + 100)
        assertEquals(a, c.adjustMs, 1e-9)
    }

    @Test
    fun aStepOfTheCaptureClockIsNotFollowedAtAllButASlowDriftIs() {
        val c = SlewClock(rate, 0, 0, maxSlewPpm = 10.0, stepMs = 8.0)
        // 2 ms off: inside the band, taken in at the slew rate
        c.update(1 * s, 1.0 * rate + 88)
        assertEquals(0, c.stepsIgnored)
        assertTrue(c.adjustMs > 0.0 && c.adjustMs < 0.02)
        val before = c.adjustMs
        // the timestamps step by 46.8 ms: ignored, however long it lasts
        for (i in 2..2000) c.update(i * s / 10, i * s / 10.0 / s * rate + 2064)
        assertTrue(c.stepsIgnored > 1000)
        assertEquals("not followed: ${c.adjustMs}", before, c.adjustMs, 0.01)
        // and when they come back the clock goes on as before
        c.update(300 * s, 300.0 * rate + 88)
        assertTrue(c.stepsIgnored > 0)
    }

    @Test
    fun theClockIsTheFrameTimesOfTheStream() {
        val c: FrameTimes = SlewClock(rate, 1_000, 5 * s)
        assertEquals(5 * s, c.nanosOfFrame(1_000))
        assertEquals(6 * s, c.nanosOfFrame(1_000L + rate))
    }
}
