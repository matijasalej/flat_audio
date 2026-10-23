package io.github.matijasalej.wholeflat.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class H264Test {
    private val sc4 = byteArrayOf(0, 0, 0, 1)
    private val sc3 = byteArrayOf(0, 0, 1)
    private val sps = byteArrayOf(0x67, 0x64, 0x00, 0x29, 0xAC.toByte(), 0x13)
    private val pps = byteArrayOf(0x68, 0xEE.toByte(), 0x3C, 0x80.toByte())
    private val sei = byteArrayOf(0x06, 0x05, 0x04, 0x11, 0x22, 0x33)
    private val idr = byteArrayOf(0x65, 0x88.toByte(), 0x84.toByte(), 0x00, 0x21, 0xFF.toByte())
    private val slice = byteArrayOf(0x41, 0x9A.toByte(), 0x24, 0x6C)

    @Test
    fun nalUnitsAreSplitAtThreeAndFourByteStartCodes() {
        val data = sc4 + sps + sc3 + pps + sc4 + idr
        val n = H264.nals(data)
        assertEquals(listOf(7, 8, 5), n.map { it.type })
        assertArrayEquals(sps, data.copyOfRange(n[0].start, n[0].end))
        assertArrayEquals(pps, data.copyOfRange(n[1].start, n[1].end))
        assertArrayEquals(idr, data.copyOfRange(n[2].start, n[2].end))
    }

    @Test
    fun theZerosOfTheNextStartCodeAreNotPartOfTheUnit() {
        // a four byte start code is a zero byte and a three byte one; trailing zeros before the next start code are dropped too
        val data = sc4 + slice + byteArrayOf(0, 0) + sc4 + idr
        val n = H264.nals(data)
        assertEquals(2, n.size)
        assertArrayEquals(slice, data.copyOfRange(n[0].start, n[0].end))
    }

    @Test
    fun noStartCodeIsNoUnitAndAnEmptyBufferHasNone() {
        assertTrue(H264.nals(ByteArray(0)).isEmpty())
        assertTrue(H264.nals(byteArrayOf(1, 2, 3, 4, 5)).isEmpty())
    }

    @Test
    fun anIdrPictureIsRecognised() {
        assertTrue(H264.isKey(sc4 + sps + sc4 + pps + sc4 + idr))
        assertFalse(H264.isKey(sc4 + slice))
    }

    @Test
    fun theParameterSetsOfTheCodecConfigAreKeptWithFourByteStartCodes() {
        // the encoder may say PPS first or add other units; the sets are given SPS then PPS
        val config = sc4 + pps + sc3 + sps + sc4 + sei
        assertArrayEquals(sc4 + sps + sc4 + pps, H264.parameterSets(config))
        assertEquals(0, H264.parameterSets(sc4 + slice).size)
    }

    @Test
    fun everyAccessUnitBeginsWithADelimiter() {
        val frame = sc4 + slice
        val au = H264.accessUnit(frame, 0, frame.size, sc4 + sps + sc4 + pps)
        assertArrayEquals(H264.AUD + sc4 + slice, au)
        assertEquals(listOf(9, 1), H264.types(au))
    }

    @Test
    fun anIdrPictureWithoutParameterSetsGetsThemFromTheConfigBeforeThePicture() {
        val frame = sc3 + sei + sc4 + idr
        val config = sc4 + sps + sc4 + pps
        val au = H264.accessUnit(frame, 0, frame.size, config)
        assertEquals(listOf(9, 7, 8, 6, 5), H264.types(au))
        // four byte start codes throughout
        assertArrayEquals(H264.AUD + config + sc4 + sei + sc4 + idr, au)
    }

    @Test
    fun anIdrPictureThatHasItsOwnParameterSetsKeepsThemAndGetsNoSecondSet() {
        val frame = sc4 + pps + sc4 + sps + sc4 + idr
        val au = H264.accessUnit(frame, 0, frame.size, sc4 + byteArrayOf(0x67, 1, 2, 3) + sc4 + byteArrayOf(0x68, 4))
        // SPS then PPS then the picture, once each
        assertEquals(listOf(9, 7, 8, 5), H264.types(au))
        assertArrayEquals(H264.AUD + sc4 + sps + sc4 + pps + sc4 + idr, au)
    }

    @Test
    fun aDelimiterOfTheEncoderIsDroppedSoThereIsOne() {
        val frame = sc4 + byteArrayOf(0x09, 0x10) + sc4 + slice
        val au = H264.accessUnit(frame, 0, frame.size, null)
        assertEquals(listOf(9, 1), H264.types(au))
        assertArrayEquals(H264.AUD + sc4 + slice, au)
    }

    @Test
    fun theRangeOfTheBufferIsHonoured() {
        val frame = byteArrayOf(9, 9, 9) + sc4 + slice + byteArrayOf(7, 7)
        val au = H264.accessUnit(frame, 3, 4 + slice.size, null)
        assertArrayEquals(H264.AUD + sc4 + slice, au)
    }
}
