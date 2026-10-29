package io.github.matijasalej.wholeflat.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplaySteerTest {
    private val rate = 44_100
    private val block = 441
    private val ms = rate / 1000.0

    @Test
    fun theFirstBlockIsPlacedAtTheWantedFrame() {
        val s = ReplaySteer(rate)
        val p = s.plan(-1L, 1_000.4, block)
        assertEquals(1_000L, p.from)
        assertEquals(block, p.inFrames)
        assertTrue(p.jumped)
        assertEquals(0, s.jumps)
    }

    @Test
    fun anErrorWithinTwoMillisecondsIsLeftAlone() {
        val s = ReplaySteer(rate)
        val p = s.plan(10_000L, 10_000L - 1.9 * ms, block)
        assertEquals(10_000L, p.from)
        assertEquals(block, p.inFrames)
        assertFalse(p.jumped)
        assertEquals(0L, s.stretched)
    }

    @Test
    fun anEarlyRoomTakesOneFrameLessUntilItIsWithinHalfAMillisecond() {
        val s = ReplaySteer(rate)
        // 3 ms early: the cursor is newer than wanted
        assertEquals(block - 1, s.plan(10_000L, 10_000L - 3 * ms, block).inFrames)
        // still correcting at 1 ms (inside the 2 ms that start it, outside the 0.5 that stop it)
        assertEquals(block - 1, s.plan(10_000L, 10_000L - 1 * ms, block).inFrames)
        // within 0.5 ms: done
        assertEquals(block, s.plan(10_000L, 10_000L - 0.4 * ms, block).inFrames)
        assertEquals(2L, s.stretched)
    }

    @Test
    fun aLateRoomTakesOneFrameMoreAndTwoBeyondTenMilliseconds() {
        val s = ReplaySteer(rate)
        assertEquals(block + 1, s.plan(10_000L, 10_000L + 5 * ms, block).inFrames)
        assertEquals(block + 2, s.plan(10_000L, 10_000L + 15 * ms, block).inFrames)
        assertEquals(block - 2, s.plan(10_000L, 10_000L - 15 * ms, block).inFrames)
    }

    @Test
    fun beyondFortyMillisecondsTheCursorJumps() {
        val s = ReplaySteer(rate)
        val p = s.plan(10_000L, 10_000L + 41 * ms, block)
        assertTrue(p.jumped)
        assertEquals(Math.round(10_000L + 41 * ms), p.from)
        assertEquals(block, p.inFrames)
        assertEquals(1, s.jumps)
        // and the next block is in step: nothing is stretched
        assertEquals(block, s.plan(p.from + p.inFrames, (p.from + p.inFrames).toDouble(), block).inFrames)
    }

    @Test
    fun theAnchorIsExtrapolatedAtTheRate() {
        // frame 1000 presented at 5 s: frame 1000 + 44100 one second later
        assertEquals(6_000_000_000L, ReplaySteer.presentNanos(1_000L, 5_000_000_000L, 45_100L, rate))
        assertEquals(4_000_000_000L, ReplaySteer.presentNanos(45_100L, 5_000_000_000L, 1_000L, rate))
    }

    /**
     * The loop as the replay runs it, against a track whose clock runs 150 ppm fast and a capture with a clock 80 ppm slow: the drift is
     * let grow to the 2 ms that start a correction and is then melted to half a millisecond, so the frame that the track presents is
     * never more than a little over 2 ms from the wanted one, and nothing jumps.
     */
    @Test
    fun theLoopKeepsTheRoomWithinTwoMillisecondsThroughClockDrift() {
        val s = ReplaySteer(rate)
        val trackRate = rate * (1 + 150e-6)
        val captureRate = rate * (1 - 80e-6)
        val delayNanos = 800_000_000L
        val bufferFrames = 8_820L
        val t0 = 10_000_000_000.0
        fun trackPresent(frame: Long): Double = t0 + frame / trackRate * 1e9
        fun captured(nanos: Double): Double = (nanos - 2_000_000_000.0) * captureRate / 1e9
        var cursor = -1L
        var written = 0L
        var worst = 0.0
        for (i in 0 until 60_000) {
            // the anchor: the frame the HAL is presenting now (the buffer behind what is written), at its true time
            val anchorFrame = maxOf(0L, written - bufferFrames)
            val present = ReplaySteer.presentNanos(anchorFrame, trackPresent(anchorFrame).toLong(), written, rate)
            val p = s.plan(cursor, captured(present.toDouble() - delayNanos), block)
            cursor = p.from + p.inFrames
            // the error that is heard: what the frame carries against what it should, at its true presentation
            if (i > 100) worst = maxOf(worst, Math.abs(p.from - captured(trackPresent(written) - delayNanos)))
            written += block
        }
        assertEquals(0, s.jumps)
        assertTrue("worst error ${worst / ms} ms", worst < 2.2 * ms)
        assertTrue("the drift was corrected", s.stretched > 0L)
    }

    @Test
    fun equalLengthsAreACopy() {
        val input = shortArrayOf(1, 2, 3, 4, 5, 6)
        val out = ShortArray(6)
        Stretch.linear(input, 3, out, 3)
        assertArrayEquals(input, out)
    }

    @Test
    fun aStretchKeepsTheFirstAndTheLastFrameAndInterpolatesBetween() {
        // left 0, 100, 200; right 0, -100, -200: five frames out of three
        val input = shortArrayOf(0, 0, 100, -100, 200, -200)
        val out = ShortArray(10)
        Stretch.linear(input, 3, out, 5)
        assertArrayEquals(shortArrayOf(0, 0, 50, -50, 100, -100, 150, -150, 200, -200), out)
    }

    @Test
    fun aSqueezeTakesEveryOtherPointOfARamp() {
        val input = ShortArray(10) { i -> (i / 2 * 10).toShort() } // frames 0, 10, 20, 30, 40 on both channels
        val out = ShortArray(6)
        Stretch.linear(input, 5, out, 3)
        assertArrayEquals(shortArrayOf(0, 0, 20, 20, 40, 40), out)
    }

    @Test
    fun oneFrameFillsTheBlock() {
        val out = ShortArray(6)
        Stretch.linear(shortArrayOf(7, -7), 1, out, 3)
        assertArrayEquals(shortArrayOf(7, -7, 7, -7, 7, -7), out)
    }
}
