package io.github.matijasalej.wholeflat.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdtsTest {
    @Test
    fun theHeaderOfAnAacLcFrameAt44100HzStereoIsWhatEncodersWrite() {
        // FF F1 50 80 .. .. FC: MPEG-4, no CRC, LC, 44.1 kHz, 2 channels; the length (here 7 + 400) is in the 13 bits across bytes 3 to 5
        val h = Adts.header(400, 44_100, 2)
        assertEquals(7, h.size)
        assertEquals(0xFF, h[0].toInt() and 0xFF)
        assertEquals(0xF1, h[1].toInt() and 0xFF)
        assertEquals(0x50, h[2].toInt() and 0xFF)
        assertEquals(0x80, (h[3].toInt() and 0xFF) and 0xFC)
        assertEquals(407, Adts.frameLength(h))
        assertEquals(0xFC, h[6].toInt() and 0xFF)
        assertEquals(0x1F, h[5].toInt() and 0x1F)
    }

    @Test
    fun theLengthSurvivesTheRoundTripAcrossTheRange() {
        for (n in listOf(0, 1, 7, 255, 256, 1_000, 2_047, 2_048, 8_000)) {
            assertEquals(n + 7, Adts.frameLength(Adts.header(n, 44_100, 2)))
        }
    }

    @Test
    fun aFrameIsTheHeaderAndTheRawBytes() {
        val raw = byteArrayOf(1, 2, 3, 4, 5, 6)
        val f = Adts.frame(raw, 1, 4, 48_000, 2)
        assertEquals(11, f.size)
        assertArrayEquals(byteArrayOf(2, 3, 4, 5), f.copyOfRange(7, 11))
        assertEquals(11, Adts.frameLength(f))
        // 48 kHz is index 3
        assertEquals(3, (f[2].toInt() shr 2) and 0xF)
    }

    @Test
    fun theTableOfRates() {
        assertEquals(4, Adts.rateIndex(44_100))
        assertEquals(3, Adts.rateIndex(48_000))
        assertEquals(-1, Adts.rateIndex(12_345))
    }

    @Test
    fun whatIsNotAHeaderHasNoLength() {
        assertEquals(-1, Adts.frameLength(ByteArray(10)))
        assertEquals(-1, Adts.frameLength(ByteArray(3)))
        assertTrue(Adts.frameLength(Adts.header(10, 44_100, 2)) > 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aRateThatAdtsHasNoIndexForIsRefused() {
        Adts.header(10, 12_345, 2)
    }
}
