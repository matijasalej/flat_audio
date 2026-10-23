package io.github.matijasalej.wholeflat.capture

import io.github.matijasalej.wholeflat.sync.FrameSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureBufferTest {
    /** Frames whose left sample is the absolute index (low 16 bits) and the right one minus it. */
    private fun frames(from: Long, n: Int): ShortArray {
        val out = ShortArray(n * 2)
        for (i in 0 until n) {
            out[i * 2] = (from + i).toShort()
            out[i * 2 + 1] = (-(from + i)).toShort()
        }
        return out
    }

    @Test
    fun whatIsWrittenCanBeReadByAbsoluteIndex() {
        val b = CaptureBuffer(48_000, 1_000)
        b.newGeneration(1)
        b.write(frames(0, 300), 300)
        val out = ShortArray(200)
        assertEquals(100, b.read(50, 1, out, 100, 10))
        assertEquals(50.toShort(), out[0])
        assertEquals((-50).toShort(), out[1])
        assertEquals(149.toShort(), out[198])
        assertEquals(300L, b.writeIndex)
        assertEquals(0L, b.validStart)
    }

    @Test
    fun readingDoesNotConsumeSoTwoReadersSeeTheSame() {
        val b = CaptureBuffer(48_000, 1_000)
        b.newGeneration(1)
        b.write(frames(0, 100), 100)
        val a = ShortArray(20)
        val c = ShortArray(20)
        assertEquals(10, b.read(5, 1, a, 10, 10))
        assertEquals(10, b.read(5, 1, c, 10, 10))
        assertEquals(a.toList(), c.toList())
    }

    @Test
    fun theBufferWrapsAndKeepsTheLastFrames() {
        val b = CaptureBuffer(48_000, 100)
        b.newGeneration(1)
        b.write(frames(0, 70), 70)
        b.write(frames(70, 70), 70) // wraps: 140 written, capacity 100
        assertEquals(40L, b.validStart)
        val out = ShortArray(200)
        assertEquals(100, b.read(40, 1, out, 100, 10))
        for (i in 0 until 100) assertEquals("frame ${40 + i}", (40 + i).toShort(), out[i * 2])
        assertEquals(FrameSource.LAPPED, b.read(39, 1, out, 10, 10))
    }

    @Test
    fun aChunkBiggerThanTheBufferKeepsOnlyItsEnd() {
        val b = CaptureBuffer(48_000, 100)
        b.newGeneration(1)
        b.write(frames(0, 250), 250)
        assertEquals(250L, b.writeIndex)
        assertEquals(150L, b.validStart)
        val out = ShortArray(20)
        assertEquals(10, b.read(240, 1, out, 10, 10))
        assertEquals(240.toShort(), out[0])
        assertEquals(FrameSource.LAPPED, b.read(100, 1, out, 10, 10))
    }

    @Test
    fun aReaderAheadOfTheWriterWaitsAndGetsTheFramesWhenTheyCome() {
        val b = CaptureBuffer(48_000, 1_000)
        b.newGeneration(1)
        b.write(frames(0, 10), 10)
        val out = ShortArray(40)
        assertEquals("nothing at the cursor yet: timeout", 0, b.read(10, 1, out, 20, 20))
        val writer = Thread {
            Thread.sleep(50)
            b.write(frames(10, 30), 30)
        }
        writer.start()
        val n = b.read(10, 1, out, 20, 2_000)
        writer.join()
        assertTrue("got frames after the write: $n", n > 0)
        assertEquals(10.toShort(), out[0])
    }

    @Test
    fun aNewGenerationTellsReadersOfTheOldOneAndRestartsNumbering() {
        val b = CaptureBuffer(48_000, 1_000)
        b.newGeneration(1)
        b.write(frames(0, 100), 100)
        val out = ShortArray(20)
        b.newGeneration(2)
        assertEquals(FrameSource.EPOCH, b.read(0, 1, out, 10, 10))
        assertEquals(0L, b.writeIndex)
        b.write(frames(0, 50), 50)
        assertEquals(10, b.read(0, 2, out, 10, 10))
        assertEquals(2L, b.generation)
    }

    @Test
    fun aClosedBufferReleasesReaders() {
        val b = CaptureBuffer(48_000, 100)
        b.newGeneration(1)
        val out = ShortArray(20)
        b.close()
        assertEquals(FrameSource.CLOSED, b.read(0, 1, out, 10, 10))
        b.open()
        assertEquals(0, b.read(0, 1, out, 10, 5))
    }

    @Test
    fun theFrameSourceAdapterReadsItsGeneration() {
        val b = CaptureBuffer(48_000, 1_000)
        b.newGeneration(7)
        b.write(frames(0, 100), 100)
        val source: FrameSource = CaptureFrames(b, 7)
        val out = ShortArray(20)
        assertEquals(10, source.read(0, out, 10, 10))
        b.newGeneration(8)
        assertEquals(FrameSource.EPOCH, source.read(0, out, 10, 10))
    }
}
