package io.github.matijasalej.wholeflat.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureFeedTest {
    private val rate = CaptureFeed.RATE

    /** A chunk of 20 ms. */
    private val frames = rate / 50
    private val t0 = 5_000_000_000_000L

    private fun chunk(frames: Int, value: Short): ShortArray = ShortArray(frames * 2) { value }

    @Test
    fun chunksLandInTheBufferAndSetTheLevels() {
        val f = CaptureFeed()
        f.newGeneration(1)
        val peak = f.writeAt(chunk(frames, 3_000), frames, t0)
        assertEquals(frames.toLong(), f.buffer.writeIndex)
        assertEquals(peak, f.peakDb, 0.0)
        assertEquals(-20.8, peak, 0.1)
        assertEquals(-20.8, f.levelDb(), 0.1)
        assertEquals(t0, f.lastChunkNanos)
    }

    @Test
    fun withoutTimestampsTheClockComesFromTheReads() {
        val f = CaptureFeed()
        f.newGeneration(1)
        assertEquals("none", f.clockSource)
        for (i in 1..30) f.writeAt(chunk(frames, 1_000), frames, t0 + i * 20_000_000L)
        assertEquals("read", f.clockSource)
        assertTrue(f.clock.hasAnchor)
        // the last frame of the 30th chunk was read at t0 + 30 * 20 ms
        val n = f.clock.nanosOfFrame(30L * frames - 1)!!
        assertEquals((t0 + 30 * 20_000_000L).toDouble(), n.toDouble(), 1_000_000.0)
    }

    @Test
    fun timestampsTakeOverFromTheReads() {
        val f = CaptureFeed()
        f.newGeneration(1)
        f.writeAt(chunk(frames, 1_000), frames, t0)
        assertEquals("read", f.clockSource)
        f.onTimestamp(frames.toLong(), t0 - 30_000_000L)
        assertEquals("timestamp", f.clockSource)
        assertEquals((t0 - 30_000_000L).toDouble(), f.clock.nanosOfFrame(frames.toLong())!!.toDouble(), 1_000.0)
        // further chunks do not add read-based anchors any more
        f.writeAt(chunk(frames, 1_000), frames, t0 + 20_000_000L)
        assertEquals((t0 - 30_000_000L).toDouble(), f.clock.nanosOfFrame(frames.toLong())!!.toDouble(), 1_000.0)
    }

    @Test
    fun theLongestGapBetweenChunksIsKept() {
        val f = CaptureFeed()
        f.newGeneration(1)
        f.writeAt(chunk(frames, 1), frames, t0)
        f.writeAt(chunk(frames, 1), frames, t0 + 20_000_000L)
        f.writeAt(chunk(frames, 1), frames, t0 + 20_000_000L + 150_000_000L)
        f.writeAt(chunk(frames, 1), frames, t0 + 190_000_000L)
        assertEquals(150L, f.maxGapMs)
    }

    @Test
    fun aNewGenerationStartsFresh() {
        val f = CaptureFeed()
        f.newGeneration(1)
        f.writeAt(chunk(frames, 5_000), frames, t0)
        f.newGeneration(2)
        assertEquals(0L, f.buffer.writeIndex)
        assertEquals(2L, f.generation)
        assertEquals("none", f.clockSource)
        assertFalse(f.clock.hasAnchor)
        assertEquals(Levels.FLOOR_DB, f.peakDb, 0.0)
        assertEquals(0L, f.maxGapMs)
    }

    @Test
    fun asASinkItTakesItsOwnRateOnly() {
        val f = CaptureFeed()
        f.newGeneration(1)
        assertEquals(listOf(rate), f.supportedRates.toList())
        assertFalse(f.write(chunk(100, 1), 100, 48_000))
        assertTrue(f.write(chunk(100, 1), 100, rate))
        assertEquals(100L, f.buffer.writeIndex)
    }
}
