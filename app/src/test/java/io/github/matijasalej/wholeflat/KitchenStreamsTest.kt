package io.github.matijasalej.wholeflat

import io.github.matijasalej.wholeflat.sync.FrameSource
import io.github.matijasalej.wholeflat.sync.StreamBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KitchenStreamsTest {
    @Test
    fun onlyTheStreamPathHasAToken() {
        assertEquals("1a2b3c4d5e6f", KitchenStreams.tokenOf("/k/1a2b3c4d5e6f.flac"))
        assertNull(KitchenStreams.tokenOf("/k/.flac"))
        assertNull(KitchenStreams.tokenOf("/k/abc"))
        assertNull(KitchenStreams.tokenOf("/status.json"))
        assertNull(KitchenStreams.tokenOf("/k/../etc.flac"))
        assertNull(KitchenStreams.tokenOf("/x/abc.flac"))
        // a WAV stream has its own extension
        assertEquals("1a2b3c4d5e6f", KitchenStreams.tokenOf("/k/1a2b3c4d5e6f.wav"))
        assertNull(KitchenStreams.tokenOf("/k/.wav"))
        assertNull(KitchenStreams.tokenOf("/k/abc.mp3"))
    }

    @Test
    fun theWavResponseSaysItsLengthAndTheFlacOneDoesNot() {
        val wav = KitchenStreams.responseHead(io.github.matijasalej.wholeflat.sync.StreamFormat.WAV)
        assertTrue(wav.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(wav.contains("Content-Type: audio/wav\r\n"))
        assertTrue(wav.contains("Content-Length: 2147479596\r\n"))
        assertTrue(wav.contains("Accept-Ranges: none\r\n"))
        assertTrue(wav.contains("Connection: close\r\n"))
        assertTrue(wav.endsWith("\r\n\r\n"))
        assertEquals(KitchenStreams.RESPONSE_HEAD, KitchenStreams.responseHead(io.github.matijasalej.wholeflat.sync.StreamFormat.FLAC))
    }

    @Test
    fun theResponseIsFlacWithoutALengthAndWithoutRanges() {
        // what the spikes found the Sonos plays: no Content-Length, no chunking, the stream ends by closing
        val head = KitchenStreams.RESPONSE_HEAD
        assertTrue(head.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(head.contains("Content-Type: audio/flac\r\n"))
        assertTrue(head.contains("Accept-Ranges: none\r\n"))
        assertTrue(head.contains("Connection: close\r\n"))
        assertTrue(head.contains("transferMode.dlna.org: Streaming\r\n"))
        assertFalse(head.contains("Content-Length"))
        assertFalse(head.contains("Transfer-Encoding"))
        assertTrue(head.endsWith("\r\n\r\n"))
    }

    @Test
    fun theLeadIsOneAndAHalfSeconds() {
        assertEquals(66_150L, KitchenStreams.LEAD_FRAMES)
    }

    private class Scripted(private val answers: IntArray) : FrameSource {
        var calls = 0
        var lastTimeout = -1

        override fun read(cursor: Long, out: ShortArray, maxFrames: Int, timeoutMs: Int): Int {
            lastTimeout = timeoutMs
            val a = answers[minOf(calls, answers.size - 1)]
            calls++
            if (a > 0) {
                for (i in 0 until a * 2) out[i] = 7
                return a
            }
            return a
        }
    }

    @Test
    fun theProbeGetsSilenceWhenTheRingHasNothing() {
        val probe = ProbeSource(Scripted(intArrayOf(0)))
        val out = ShortArray(882) { 5 }
        assertEquals(441, probe.read(0, out, 441, 150))
        assertTrue(out.all { it == 0.toShort() })
    }

    @Test
    fun theProbePassesRealFramesAndErrorsOn() {
        val data = ProbeSource(Scripted(intArrayOf(100)))
        val out = ShortArray(882)
        assertEquals(100, data.read(0, out, 441, 150))
        assertEquals(7.toShort(), out[0])
        assertEquals(FrameSource.LAPPED, ProbeSource(Scripted(intArrayOf(FrameSource.LAPPED))).read(0, out, 441, 150))
        assertEquals(FrameSource.EPOCH, ProbeSource(Scripted(intArrayOf(FrameSource.EPOCH))).read(0, out, 441, 150))
    }

    @Test
    fun theProbeNeverWaitsLong() {
        val inner = Scripted(intArrayOf(0))
        ProbeSource(inner).read(0, ShortArray(882), 441, 150)
        assertTrue("waited up to ${inner.lastTimeout} ms", inner.lastTimeout <= 30)
    }

    @Test
    fun silenceFillsWhatIsAsked() {
        val out = ShortArray(882) { 9 }
        assertEquals(441, SilenceSource.read(0, out, 441, 0))
        assertTrue(out.all { it == 0.toShort() })
    }

    @Test
    fun aSilentProbeStreamBuildsBlocks() {
        val map = io.github.matijasalej.wholeflat.sync.KitchenMap(0)
        val builder = StreamBuilder(SilenceSource, map)
        assertEquals(4096, builder.nextBlock(0))
        assertEquals(4096L, builder.streamFrames)
    }

    @Test
    fun theTrimsOfACapturedStreamAreTwiceAsFastAsSpotifysButStayUnderAHalfPercent() {
        val ring = KitchenStreams.trimSpacing(44_100, captured = false)
        val captured = KitchenStreams.trimSpacing(44_100, captured = true)
        assertEquals(441, ring)
        assertEquals(220, captured)
        // a trim drops or repeats one frame per spacing: the correction in ms per second, and the speed change it makes
        fun msPerSecond(spacing: Int) = 1000.0 * 44_100 / (spacing + 1) / 44_100
        assertEquals(2.26, msPerSecond(ring), 0.01)
        assertEquals(4.52, msPerSecond(captured), 0.01)
        assertTrue("at most 5 ms/s and 0.5 %", msPerSecond(captured) <= 5.0 && msPerSecond(captured) / 1000.0 <= 0.005)
    }
}
