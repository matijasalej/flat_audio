package io.github.matijasalej.wholeflat.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TvVideoSpecTest {
    @Test
    fun theDefaultIsTheLightVideo() {
        val d = TvVideoSpec.DEFAULT
        assertEquals("720p30 cbr 2500 gop1", d.text())
        assertEquals(1280, d.width)
        assertEquals(720, d.height)
        assertEquals(30, d.fps)
        assertEquals(2_500_000, d.bitrate)
        assertEquals(3000L, d.frameTicks)
        assertEquals(9_000_000, d.muxRate)
    }

    @Test
    fun aSizeAModeAndABitrateAreRead() {
        val s = TvVideoSpec.parse("720p30 vbr 8000")!!
        assertEquals(1280, s.width)
        assertEquals(720, s.height)
        assertEquals(TvVideoSpec.Mode.VBR, s.mode)
        assertEquals(8000, s.kbps)
        assertEquals(1, s.gopS)
        // room in the transport stream for the peaks of a variable bitrate
        assertEquals(12_000_000, s.muxRate)
    }

    @Test
    fun theRateOfPicturesAndTheKeyPictureIntervalAreRead() {
        val s = TvVideoSpec.parse("1080p25 vbr 10000 gop2")!!
        assertEquals(25, s.fps)
        assertEquals(3600L, s.frameTicks)
        assertEquals(2, s.gopS)
        assertEquals(15_000_000, s.muxRate)
    }

    @Test
    fun theOrderOfTheWordsDoesNotMatterAndWhatIsLeftOutKeepsItsDefault() {
        assertEquals(TvVideoSpec.parse("720p30 vbr 8000"), TvVideoSpec.parse("8000 vbr 720p30"))
        val s = TvVideoSpec.parse("vbr")!!
        assertEquals("720p30 vbr 2500 gop1", s.text())
        assertEquals("1080p30 cbr 2500 gop1", TvVideoSpec.parse("1080p")!!.text())
        assertEquals("720p30 cq 2500 gop1", TvVideoSpec.parse("  CQ , ")!!.text())
    }

    @Test
    fun theTextComesBackAsItWasRead() {
        for (t in listOf("1080p30 cbr 6000 gop1", "720p24 vbr 3000 gop4", "1080p25 cq 12000 gop2")) {
            assertEquals(t, TvVideoSpec.parse(t)!!.text())
        }
    }

    @Test
    fun whatIsNotUnderstoodOrOutOfRangeIsNothing() {
        for (t in listOf("", "   ", "480p30", "1080p60", "turbo", "cbr 100", "cbr 90000", "gop9", "gop0", "1080p30 cbr fast")) {
            assertNull(t, TvVideoSpec.parse(t))
        }
        assertNotNull(TvVideoSpec.parse("1080p30 cbr 500"))
        assertNotNull(TvVideoSpec.parse("1080p30 cbr 20000"))
    }

    @Test
    fun theRateOfTheTransportStreamCanBeSetLowForAVideoThatNeedsLittle() {
        val s = TvVideoSpec.parse("720p30 vbr 2500 mux3500")!!
        assertEquals(3_500_000, s.muxRate)
        assertEquals("720p30 vbr 2500 gop1 mux3500", s.text())
        assertEquals(s, TvVideoSpec.parse(s.text()))
        // without it the rate is made from the bitrate, as before
        assertEquals(12_000_000, TvVideoSpec.parse("720p30 vbr 8000")!!.muxRate)
        assertEquals(9_000_000, TvVideoSpec.DEFAULT.muxRate)
        assertEquals("720p30 cbr 2500 gop1", TvVideoSpec.DEFAULT.text())
        // the video before the light default is still one word away
        assertEquals("1080p30 cbr 6000 gop1", TvVideoSpec.parse("1080p30 cbr 6000 gop1")!!.text())
    }

    @Test
    fun aRateOfTheTransportStreamThatCannotCarryTheVideoIsNothing() {
        assertNull(TvVideoSpec.parse("720p30 vbr 3000 mux3200")) // less than the video plus 400 kbit/s
        assertNull(TvVideoSpec.parse("720p30 vbr 1000 mux1000"))
        assertNull(TvVideoSpec.parse("720p30 vbr 1000 mux99999"))
        assertNull(TvVideoSpec.parse("mux12"))
        assertNotNull(TvVideoSpec.parse("720p30 vbr 2000 mux2400"))
    }
}
