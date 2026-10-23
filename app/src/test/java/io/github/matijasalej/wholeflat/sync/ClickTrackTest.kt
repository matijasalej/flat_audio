package io.github.matijasalej.wholeflat.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClickTrackTest {
    private val rate = 44_100

    private fun track(first: Long, frames: Int, hz: Int): ShortArray {
        val out = ShortArray(frames * 2)
        ClickTrack.fill(out, frames, first, rate, hz)
        return out
    }

    private fun crossings(a: ShortArray, from: Int, to: Int): Int {
        var n = 0
        var prev = 0
        for (i in from until to) {
            val v = a[i * 2].toInt()
            if (v == 0) continue
            if (prev != 0 && (v > 0) != (prev > 0)) n++
            prev = v
        }
        return n
    }

    @Test
    fun aClickBeginsAtEveryFullSecondAndLastsFiveMilliseconds() {
        val len = ClickTrack.clickFrames(rate)
        assertEquals(220, len)
        // second 3 (not a marker): the click is in its first 220 frames and the rest of the second is silent
        val t = track(3L * rate, rate, 2_000)
        assertTrue((0 until len).any { t[it * 2].toInt() != 0 })
        for (i in len until rate) assertEquals("frame $i", 0, t[i * 2].toInt())
    }

    @Test
    fun bothChannelsAreAlike() {
        val t = track(3L * rate, 400, 2_000)
        for (i in 0 until 400) assertEquals(t[i * 2], t[i * 2 + 1])
    }

    @Test
    fun theLevelIsHalfOfFullScaleAtTheMost() {
        val t = track(3L * rate, 400, 2_000)
        val peak = (0 until 400).maxOf { Math.abs(t[it * 2].toInt()) }
        assertTrue("peak $peak", peak in 15_000..16_400)
    }

    @Test
    fun theWindowIsHannSoTheEndsAreQuiet() {
        val t = track(3L * rate, 400, 3_000)
        assertTrue(Math.abs(t[1 * 2].toInt()) < 100)
        assertTrue(Math.abs(t[219 * 2].toInt()) < 100)
    }

    @Test
    fun everyTenthClickIsAtTwiceThePitch() {
        // a click of 5 ms at 2 kHz is 10 cycles (about 20 crossings), at 4 kHz 20 cycles (about 40)
        val normal = crossings(track(3L * rate, 220, 2_000), 0, 220)
        val mark = crossings(track(10L * rate, 220, 2_000), 0, 220)
        assertTrue("normal $normal", normal in 17..21)
        assertTrue("mark $mark", mark in 37..41)
        val markKitchen = crossings(track(20L * rate, 220, 3_000), 0, 220)
        val kitchen = crossings(track(21L * rate, 220, 3_000), 0, 220)
        assertTrue("kitchen $kitchen", kitchen in 27..31)
        assertTrue("kitchen mark $markKitchen", markKitchen in 57..61)
        // the first second (k = 0) is a marker too
        assertTrue(crossings(track(0, 220, 2_000), 0, 220) in 37..41)
    }

    @Test
    fun theSameFrameHasTheSameSampleWhereverTheReadBegins() {
        val whole = track(5L * rate - 100, 500, 2_000)
        val tail = track(5L * rate + 50, 100, 2_000)
        // frame 5 * rate + 50 is index 150 of the whole read
        assertArrayEquals(whole.copyOfRange(150 * 2, 250 * 2), tail)
    }

    @Test
    fun theSourceKeepsTheFramesOfTheInnerOneWhileOffAndReplacesThemWhileOn() {
        val inner = object : FrameSource {
            override fun read(cursor: Long, out: ShortArray, maxFrames: Int, timeoutMs: Int): Int {
                for (i in 0 until maxFrames * 2) out[i] = 1234
                return maxFrames
            }
        }
        var on = false
        val src = ClickSource(inner, rate, 2_000) { on }
        val out = ShortArray(220 * 2)
        assertEquals(220, src.read(3L * rate, out, 220, 10))
        assertTrue(out.all { it == 1234.toShort() })
        on = true
        assertEquals(220, src.read(3L * rate, out, 220, 10))
        assertFalse(out.all { it == 1234.toShort() })
        assertArrayEquals(track(3L * rate, 220, 2_000), out)
    }

    @Test
    fun theSourcePassesOnWhatTheInnerOneSaysWhenItHasNoFrames() {
        for (code in listOf(0, FrameSource.CLOSED, FrameSource.LAPPED, FrameSource.EPOCH)) {
            val inner = object : FrameSource {
                override fun read(cursor: Long, out: ShortArray, maxFrames: Int, timeoutMs: Int): Int = code
            }
            val src = ClickSource(inner, rate, 2_000) { true }
            assertEquals(code, src.read(0, ShortArray(20), 10, 1))
        }
    }

    @Test
    fun theSwitchIsOnForTheTimeAskedAndNoLonger() {
        CalibrationClicks.stop()
        assertFalse(CalibrationClicks.active(0))
        CalibrationClicks.start(30, 1_000_000_000L)
        assertTrue(CalibrationClicks.active(2_000_000_000L))
        assertEquals(29L, CalibrationClicks.secondsLeft(2_000_000_000L))
        assertFalse(CalibrationClicks.active(31_000_000_001L))
        CalibrationClicks.stop()
        assertFalse(CalibrationClicks.active(2_000_000_000L))
    }
}
