package io.github.matijasalej.wholeflat.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class StreamBuilderTest {
    /** Frame n is (n, -n): the left sample says which ring frame it is. */
    private class CountingSource(var limit: Long = 30_000) : FrameSource {
        var failWith = 0

        override fun read(cursor: Long, out: ShortArray, maxFrames: Int, timeoutMs: Int): Int {
            if (failWith != 0) return failWith
            if (cursor >= limit) return 0
            val n = minOf(maxFrames.toLong(), limit - cursor).toInt()
            for (i in 0 until n) {
                out[2 * i] = (cursor + i).toShort()
                out[2 * i + 1] = (-(cursor + i)).toShort()
            }
            return n
        }
    }

    private fun left(builder: StreamBuilder, i: Int) = builder.block[2 * i].toInt()
    private fun right(builder: StreamBuilder, i: Int) = builder.block[2 * i + 1].toInt()

    @Test
    fun withoutTrimsTheStreamIsAPlainCopyOfTheRing() {
        val builder = StreamBuilder(CountingSource(), KitchenMap(100))
        assertEquals(4096, builder.nextBlock(0))
        for (i in 0 until 4096) assertEquals(100 + i, left(builder, i))
        assertEquals(4096, builder.nextBlock(0))
        assertEquals(100 + 4096, left(builder, 0))
        assertEquals(100L + 8192, builder.ringCursor)
        assertEquals(8192L, builder.streamFrames)
        assertEquals(8192L, builder.map.streamEnd)
    }

    @Test
    fun aDropLeavesOutOneRingFrame() {
        val builder = StreamBuilder(CountingSource(), KitchenMap(100))
        builder.addTrim(1)
        assertEquals(4096, builder.nextBlock(0))
        assertEquals("ring frame 100 never reaches the stream", 101, left(builder, 0))
        assertEquals(101.0, builder.map.ringOfStream(0.0)!!, 0.0)
        assertEquals(101.0 + 4095, builder.map.ringOfStream(4095.0)!!, 0.0)
        assertEquals(0, builder.unappliedBudget())
        assertEquals(100L + 1 + 4096, builder.ringCursor)
    }

    @Test
    fun aRepeatSendsTheLastFrameTwice() {
        val builder = StreamBuilder(CountingSource(), KitchenMap(100))
        assertEquals(4096, builder.nextBlock(0))
        builder.addTrim(-1)
        assertEquals(4096, builder.nextBlock(0))
        assertEquals("the copy", 100 + 4095, left(builder, 0))
        assertEquals("then the ring goes on", 100 + 4096, left(builder, 1))
        assertEquals(4195.0, builder.map.ringOfStream(4096.0)!!, 0.0)
        assertEquals(4196.0, builder.map.ringOfStream(4097.0)!!, 0.0)
        assertEquals(-1.0, builder.map.netDropsAt(4097.0), 0.0)
        assertEquals(0, builder.unappliedBudget())
    }

    @Test
    fun trimsAreAtLeast441FramesApart() {
        val builder = StreamBuilder(CountingSource(), KitchenMap(100))
        builder.addTrim(5)
        assertEquals(4096, builder.nextBlock(0))
        val map = builder.map
        assertEquals(1.0, map.netDropsAt(0.0), 0.0)
        assertEquals(1.0, map.netDropsAt(440.0), 0.0)
        assertEquals(2.0, map.netDropsAt(441.0), 0.0)
        assertEquals(2.0, map.netDropsAt(881.0), 0.0)
        assertEquals(3.0, map.netDropsAt(882.0), 0.0)
        assertEquals(4.0, map.netDropsAt(1323.0), 0.0)
        assertEquals(5.0, map.netDropsAt(1764.0), 0.0)
        assertEquals(5.0, map.netDropsAt(4095.0), 0.0)
        assertEquals(6, map.checkpoints())
    }

    @Test
    fun theMapAlwaysSaysWhichRingFrameEachStreamFrameCarries() {
        val rnd = Random(5)
        val builder = StreamBuilder(CountingSource(), KitchenMap(1_000))
        val emitted = ArrayList<Int>()
        for (blockNo in 0 until 6) {
            // a random mix of drops and repeats asked for between the blocks
            builder.addTrim(rnd.nextInt(7) - 3)
            assertEquals(4096, builder.nextBlock(0))
            for (i in 0 until 4096) emitted.add(left(builder, i))
        }
        for (s in emitted.indices) {
            assertEquals("stream frame $s", emitted[s].toDouble(), builder.map.ringOfStream(s.toDouble())!!, 0.0)
        }
    }

    @Test
    fun theRightChannelCanPlayOnBothSides() {
        val builder = StreamBuilder(CountingSource(), KitchenMap(10), StreamBuilder.ChannelMode.RIGHT_ONLY)
        assertEquals(4096, builder.nextBlock(0))
        for (i in listOf(0, 1, 500, 4095)) {
            assertEquals(-(10 + i), left(builder, i))
            assertEquals(-(10 + i), right(builder, i))
        }
    }

    @Test
    fun theLeftChannelCanPlayOnBothSides() {
        val builder = StreamBuilder(CountingSource(), KitchenMap(10), StreamBuilder.ChannelMode.LEFT_ONLY)
        assertEquals(4096, builder.nextBlock(0))
        for (i in listOf(0, 1, 500, 4095)) {
            assertEquals(10 + i, left(builder, i))
            assertEquals(10 + i, right(builder, i))
        }
    }

    @Test
    fun aStarvedRingLosesNothingAndRepeatsNothing() {
        val source = CountingSource(limit = 1_000)
        val builder = StreamBuilder(source, KitchenMap(100))
        assertEquals("the decoder has only produced part of a block", 0, builder.nextBlock(0))
        assertEquals(1_000L, builder.ringCursor)
        source.limit = 30_000
        assertEquals(4096, builder.nextBlock(0))
        for (i in 0 until 4096) assertEquals(100 + i, left(builder, i))
    }

    @Test
    fun aDropWaitsForTheFrameItHasToSkip() {
        val source = CountingSource(limit = 100)
        val builder = StreamBuilder(source, KitchenMap(100))
        builder.addTrim(1)
        assertEquals(0, builder.nextBlock(0))
        assertEquals(1, builder.unappliedBudget())
        source.limit = 30_000
        assertEquals(4096, builder.nextBlock(0))
        assertEquals(101, left(builder, 0))
        assertEquals(0, builder.unappliedBudget())
    }

    @Test
    fun errorsFromTheRingArePassedOn() {
        val source = CountingSource()
        val builder = StreamBuilder(source, KitchenMap(0))
        source.failWith = FrameSource.LAPPED
        assertEquals(FrameSource.LAPPED, builder.nextBlock(0))
        source.failWith = FrameSource.EPOCH
        assertEquals(FrameSource.EPOCH, builder.nextBlock(0))
        source.failWith = FrameSource.CLOSED
        assertEquals(FrameSource.CLOSED, builder.nextBlock(0))
        source.failWith = 0
        assertTrue(builder.nextBlock(0) == 4096)
    }
}
