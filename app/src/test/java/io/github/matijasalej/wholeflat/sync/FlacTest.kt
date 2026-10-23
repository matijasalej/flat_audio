package io.github.matijasalej.wholeflat.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

class FlacTest {
    /** Bitwise CRC-8 (polynomial 0x07), independent of the table in the encoder. */
    private fun crc8(data: ByteArray, from: Int, to: Int): Int {
        var crc = 0
        for (i in from until to) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) { crc = if (crc and 0x80 != 0) ((crc shl 1) xor 0x07) and 0xFF else (crc shl 1) and 0xFF }
        }
        return crc
    }

    /** Bitwise CRC-16 (polynomial 0x8005), independent of the table in the encoder. */
    private fun crc16(data: ByteArray, from: Int, to: Int): Int {
        var crc = 0
        for (i in from until to) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) { crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x8005) and 0xFFFF else (crc shl 1) and 0xFFFF }
        }
        return crc
    }

    private fun pcm(seed: Int) = ShortArray(Flac.BLOCK * 2) { i -> ((i * 37 + seed * 1_001) % 65_536 - 32_768).toShort() }

    private fun u8(b: Byte) = b.toInt() and 0xFF

    @Test
    fun theReferenceCrcsMatchTheirPublishedCheckValues() {
        val check = "123456789".toByteArray(Charsets.US_ASCII)
        assertEquals(0xF4, crc8(check, 0, check.size))
        assertEquals(0xFEE8, crc16(check, 0, check.size))
    }

    @Test
    fun theStreamHeaderIsTheMarkerAndAStreamInfoWithUnknownLength() {
        val h = Flac.streamHeader()
        assertEquals(42, h.size)
        assertEquals("fLaC", String(h, 0, 4, Charsets.US_ASCII))
        assertEquals("last metadata block, type STREAMINFO", 0x80, u8(h[4]))
        assertArrayEquals("length 34", byteArrayOf(0, 0, 34), h.copyOfRange(5, 8))
        assertEquals(4096, ByteBuffer.wrap(h, 8, 2).short.toInt() and 0xFFFF)
        assertEquals(4096, ByteBuffer.wrap(h, 10, 2).short.toInt() and 0xFFFF)
        assertArrayEquals("frame sizes unknown", ByteArray(6), h.copyOfRange(12, 18))
        val packed = ByteBuffer.wrap(h, 18, 8).long
        assertEquals(44_100L, packed ushr 44)
        assertEquals(2L, ((packed ushr 41) and 7L) + 1)
        assertEquals(16L, ((packed ushr 36) and 31L) + 1)
        assertEquals("total samples unknown", 0L, packed and 0xFFFFFFFFFL)
        assertArrayEquals("no MD5", ByteArray(16), h.copyOfRange(26, 42))
    }

    @Test
    fun aFrameHasASyncCodeAHeaderCrcAndAFrameCrc() {
        val frame = Flac.frame(7, pcm(1))
        assertEquals(6 + 2 * (1 + 8192) + 2, frame.size)
        assertEquals(0xFF, u8(frame[0]))
        assertEquals("sync, fixed block size", 0xF8, u8(frame[1]))
        assertEquals("4096 frames, 44.1 kHz", 0xC9, u8(frame[2]))
        assertEquals("two independent channels, 16 bit", 0x18, u8(frame[3]))
        assertEquals("frame number", 7, u8(frame[4]))
        assertEquals("header crc", crc8(frame, 0, 5), u8(frame[5]))
        val crc = crc16(frame, 0, frame.size - 2)
        assertEquals(crc shr 8, u8(frame[frame.size - 2]))
        assertEquals(crc and 0xFF, u8(frame[frame.size - 1]))
    }

    @Test
    fun theSamplesAreStoredPerChannelAndBigEndian() {
        val p = pcm(3)
        val frame = Flac.frame(0, p)
        assertEquals("verbatim subframe, left", 0x02, u8(frame[6]))
        for (i in 0 until Flac.BLOCK) {
            val s = ((u8(frame[7 + 2 * i]) shl 8) or u8(frame[8 + 2 * i])).toShort()
            assertEquals("left $i", p[2 * i], s)
        }
        val right = 7 + 2 * Flac.BLOCK
        assertEquals("verbatim subframe, right", 0x02, u8(frame[right]))
        for (i in 0 until Flac.BLOCK) {
            val s = ((u8(frame[right + 1 + 2 * i]) shl 8) or u8(frame[right + 2 + 2 * i])).toShort()
            assertEquals("right $i", p[2 * i + 1], s)
        }
    }

    @Test
    fun a48kHzStreamSaysSoInTheStreamInfoAndInEveryFrame() {
        val h = Flac.streamHeader(48_000)
        assertEquals(42, h.size)
        val packed = ByteBuffer.wrap(h, 18, 8).long
        assertEquals(48_000L, packed ushr 44)
        assertEquals(2L, ((packed ushr 41) and 7L) + 1)
        assertEquals(16L, ((packed ushr 36) and 31L) + 1)
        assertEquals(0L, packed and 0xFFFFFFFFFL)
        val frame = Flac.frame(3, pcm(1), 48_000)
        assertEquals("4096 frames, 48 kHz", 0xCA, u8(frame[2]))
        assertEquals("header crc", crc8(frame, 0, 5), u8(frame[5]))
        val crc = crc16(frame, 0, frame.size - 2)
        assertEquals(crc shr 8, u8(frame[frame.size - 2]))
        assertEquals(crc and 0xFF, u8(frame[frame.size - 1]))
        // the default is still 44.1 kHz
        assertArrayEquals(Flac.streamHeader(), Flac.streamHeader(44_100))
        assertEquals(0xC9, u8(Flac.frame(3, pcm(1))[2]))
    }

    @Test(expected = IllegalArgumentException::class)
    fun aRateWithoutACodeIsRefused() {
        Flac.streamHeader(12_345)
    }

    @Test
    fun frameNumbersAreCodedLikeUtf8() {
        val numbers = intArrayOf(0, 1, 127, 128, 2047, 2048, 55_295, 57_344, 65_535, 65_536, 130_000, 1_114_111)
        for (n in numbers) {
            val expected = String(Character.toChars(n)).toByteArray(Charsets.UTF_8)
            val frame = Flac.frame(n.toLong(), pcm(2))
            val headerEnd = 4 + expected.size
            assertArrayEquals("number $n", expected, frame.copyOfRange(4, headerEnd))
            assertEquals("header crc for $n", crc8(frame, 0, headerEnd), u8(frame[headerEnd]))
            assertEquals(headerEnd + 1 + 2 * (1 + 8192) + 2, frame.size)
        }
    }
}
