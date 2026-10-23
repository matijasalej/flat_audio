package io.github.matijasalej.wholeflat.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class AnchorFilterTest {
    private val rate = TrackTimeline.RATE
    private val base = 5_000_000_000_000L // the clock is far from zero

    private fun nanosOf(frame: Long, offset: Long): Long = base + offset + frame * 1_000_000_000L / rate

    @Test
    fun theFirstReadingIsTheAnchor() {
        val f = AnchorFilter()
        val a = f.add(44_100, nanosOf(44_100, 123_456))
        assertEquals(44_100L, a.frame)
        assertEquals(nanosOf(44_100, 123_456), a.nanos)
    }

    @Test
    fun jitterIsSmoothedAway() {
        val f = AnchorFilter()
        val rnd = Random(7)
        var worst = 0L
        for (i in 0 until 200) {
            val frame = 4_410L * (i + 1) // every 100 ms
            val jitter = (rnd.nextGaussian() * 1_500_000.0).toLong().coerceIn(-6_000_000L, 6_000_000L)
            val a = f.add(frame, nanosOf(frame, 50_000_000L) + jitter)
            if (i >= 10) worst = maxOf(worst, Math.abs(a.nanos - nanosOf(frame, 50_000_000L)))
        }
        // single readings are off by up to 6 ms, the anchor by well under 3 ms
        assertTrue("worst error $worst ns", worst < 3_000_000L)
    }

    @Test
    fun aSingleOutlierIsIgnored() {
        val f = AnchorFilter()
        for (i in 1..9) f.add(4_410L * i, nanosOf(4_410L * i, 0))
        val frame = 4_410L * 10
        val a = f.add(frame, nanosOf(frame, 40_000_000L)) // 40 ms late, once
        assertTrue(Math.abs(a.nanos - nanosOf(frame, 0)) < 100_000L)
        val b = f.add(4_410L * 11, nanosOf(4_410L * 11, 0))
        assertEquals(nanosOf(4_410L * 11, 0), b.nanos)
    }

    @Test
    fun aRealStepIsFollowedAfterThreeReadings() {
        val f = AnchorFilter()
        for (i in 1..9) f.add(4_410L * i, nanosOf(4_410L * i, 0))
        var last: AnchorFilter.Anchor? = null
        for (i in 10..12) last = f.add(4_410L * i, nanosOf(4_410L * i, 30_000_000L))
        assertEquals(nanosOf(4_410L * 12, 30_000_000L), last!!.nanos)
    }

    @Test
    fun strayReadingsAndStepsAreCounted() {
        val f = AnchorFilter()
        for (i in 1..9) f.add(4_410L * i, nanosOf(4_410L * i, 0))
        assertEquals(0, f.outliers)
        f.add(4_410L * 10, nanosOf(4_410L * 10, 40_000_000L)) // stray
        assertEquals(1, f.outliers)
        assertEquals(0, f.restarts)
        f.add(4_410L * 11, nanosOf(4_410L * 11, 40_000_000L))
        f.add(4_410L * 12, nanosOf(4_410L * 12, 40_000_000L)) // the third in a row: a step
        assertEquals(1, f.restarts)
    }

    @Test
    fun resetForgetsEverything() {
        val f = AnchorFilter()
        for (i in 1..9) f.add(4_410L * i, nanosOf(4_410L * i, 0))
        f.reset()
        assertEquals(0, f.size)
        val a = f.add(4_410L * 10, nanosOf(4_410L * 10, 90_000_000L))
        assertEquals(nanosOf(4_410L * 10, 90_000_000L), a.nanos)
    }

    @Test
    fun theWindowFollowsASlowDrift() {
        val f = AnchorFilter()
        // 50 ppm: the offset grows by 50 us per second; readings every 100 ms for 60 s
        var worst = 0L
        for (i in 1..600) {
            val frame = 4_410L * i
            val drift = (i * 0.1 * 50_000.0).toLong() // ns
            val a = f.add(frame, nanosOf(frame, drift))
            if (i > 20) worst = maxOf(worst, Math.abs(a.nanos - nanosOf(frame, drift)))
        }
        // the median of the last 0.9 s lags a 50 ppm drift by about 25 us
        assertTrue("worst error $worst ns", worst < 100_000L)
    }
}
