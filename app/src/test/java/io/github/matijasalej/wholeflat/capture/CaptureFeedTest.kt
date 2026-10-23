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

    /** Feeds [chunks] chunks of 10 ms that the pipeline delivers [pipelineMs] after they were captured, with timestamps. */
    private fun feedWithTimestamps(f: CaptureFeed, chunks: Int, pipelineMs: Long): Long {
        val per = rate / 100
        var lastRead = 0L
        for (i in 1..chunks) {
            val lastFrame = i.toLong() * per - 1
            lastRead = t0 + lastFrame * 1_000_000_000L / rate + pipelineMs * 1_000_000L
            f.writeAt(chunk(per, 1_000), per, lastRead)
            // the timestamp is of the capture point, one chunk ahead of what has been read
            val p = (i + 1).toLong() * per
            f.onTimestamp(p, t0 + p * 1_000_000_000L / rate)
        }
        return lastRead
    }

    @Test
    fun theAgeOfTheNewestFrameComesFromTheTimestamps() {
        val f = CaptureFeed()
        f.newGeneration(1)
        assertTrue(f.newestAgeMs(t0).isNaN())
        assertTrue(f.pipelineMs().isNaN())
        val lastRead = feedWithTimestamps(f, 150, pipelineMs = 40)
        assertEquals("timestamp", f.clockSource)
        assertEquals(40.0, f.pipelineMs(), 1.5)
        assertEquals(40.0, f.pipelineMaxMs, 2.5)
        // 5 ms after the last read, the newest frame is 5 ms older
        assertEquals(45.0, f.newestAgeMs(lastRead + 5_000_000L), 1.5)
    }

    @Test
    fun theAgeAtPlayIsKeptWithTheClockThatSaidIt() {
        val f = CaptureFeed()
        f.newGeneration(1)
        // with the clock of the reads the age is only the time since the last read
        f.writeAt(chunk(rate / 100, 1_000), rate / 100, t0)
        assertEquals(7.0, f.markPlay(t0 + 7_000_000L), 1.0)
        assertEquals("read", f.agePlaySource)
        assertEquals(1, f.playMarks)
        f.newGeneration(2)
        val lastRead = feedWithTimestamps(f, 150, pipelineMs = 55)
        val age = f.markPlay(lastRead + 3_000_000L)
        assertEquals(58.0, age, 1.5)
        assertEquals(age, f.agePlayMs, 0.0)
        assertEquals("timestamp", f.agePlaySource)
        assertEquals(2, f.playMarks)
    }

    @Test
    fun theReadsOfTheTimestampsAreNotCountedWithoutTimestamps() {
        val f = CaptureFeed()
        f.newGeneration(1)
        for (i in 1..30) f.writeAt(chunk(rate / 100, 1_000), rate / 100, t0 + i * 10_000_000L)
        assertTrue("no pipeline figure from the reads alone", f.pipelineMs().isNaN())
        feedWithTimestamps(f, 30, pipelineMs = 20)
        assertFalse(f.pipelineMs().isNaN())
        f.newGeneration(2)
        assertTrue(f.pipelineMs().isNaN())
        assertTrue(f.pipelineMaxMs.isNaN())
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
