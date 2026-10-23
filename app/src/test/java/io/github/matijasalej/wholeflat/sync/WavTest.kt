package io.github.matijasalej.wholeflat.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavTest {
    private fun le(h: ByteArray) = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)

    @Test
    fun theHeaderIsARiffWaveOfLittleEndianSixteenBitStereo() {
        val h = Wav.header(44_100)
        assertEquals(44, h.size)
        assertEquals("RIFF", String(h, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(h, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(h, 12, 4, Charsets.US_ASCII))
        assertEquals("data", String(h, 36, 4, Charsets.US_ASCII))
        val b = le(h)
        assertEquals("riff size = 36 + data", 36 + Wav.DATA_BYTES, b.getInt(4).toLong())
        assertEquals("fmt chunk size", 16, b.getInt(16))
        assertEquals("PCM", 1, b.getShort(20).toInt())
        assertEquals("stereo", 2, b.getShort(22).toInt())
        assertEquals("sample rate", 44_100, b.getInt(24))
        assertEquals("byte rate", 44_100 * 4, b.getInt(28))
        assertEquals("block align", 4, b.getShort(32).toInt())
        assertEquals("bits per sample", 16, b.getShort(34).toInt())
        assertEquals("data size", Wav.DATA_BYTES, b.getInt(40).toLong())
    }

    @Test
    fun theClaimedLengthIsJustUnderTwoGibibytesAndTheContentLengthAddsTheHeader() {
        assertEquals(0x7FFFF000L, Wav.DATA_BYTES)
        assertEquals(0L, Wav.DATA_BYTES % 4096)
        assertTrue(Wav.DATA_BYTES < Int.MAX_VALUE)
        assertEquals(Wav.DATA_BYTES + 44, Wav.CONTENT_LENGTH)
        // about 3.4 hours of 44.1 kHz stereo
        assertEquals(3.38, Wav.DATA_BYTES / 176_400.0 / 3600.0, 0.01)
    }

    @Test
    fun anotherRateAndLengthAreWrittenAsAsked() {
        val h = Wav.header(48_000, 1_000_000L)
        val b = le(h)
        assertEquals(48_000, b.getInt(24))
        assertEquals(192_000, b.getInt(28))
        assertEquals(1_000_036, b.getInt(4))
        assertEquals(1_000_000, b.getInt(40))
    }

    @Test
    fun theSamplesAreLittleEndianAndInterleaved() {
        val pcm = shortArrayOf(0x0102, -2, 0x7FFF, -0x8000, 5, 6)
        val bytes = Wav.pcm(pcm, 2) // two frames: the first four shorts
        assertEquals(8, bytes.size)
        assertEquals(listOf(0x02, 0x01, 0xFE, 0xFF, 0xFF, 0x7F, 0x00, 0x80), bytes.map { it.toInt() and 0xFF })
    }

    @Test
    fun aStreamEndsWhereTheHeaderSaidItWould() {
        assertTrue(Wav.fits(0, 1764))
        assertTrue(Wav.fits(Wav.DATA_BYTES - 1764, 1764))
        assertFalse(Wav.fits(Wav.DATA_BYTES - 1763, 1764))
        assertFalse(Wav.fits(Wav.DATA_BYTES, 4))
    }

    @Test
    fun theFormatsAreFoundByKeyAndByPath() {
        assertEquals(StreamFormat.WAV, StreamFormat.of("wav"))
        assertEquals(StreamFormat.FLAC, StreamFormat.of(" FLAC "))
        assertEquals(null, StreamFormat.of("mp3"))
        assertEquals(null, StreamFormat.of(null))
        assertEquals(StreamFormat.WAV, StreamFormat.ofPath("/k/abc.wav"))
        assertEquals(StreamFormat.FLAC, StreamFormat.ofPath("/k/abc.flac"))
        assertEquals(null, StreamFormat.ofPath("/k/abc"))
        assertEquals("audio/wav", StreamFormat.WAV.mime)
        assertEquals("audio/flac", StreamFormat.FLAC.mime)
    }
}
