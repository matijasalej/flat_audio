package io.github.matijasalej.wholeflat.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TvVideoSpecTest {
    @Test
    fun theDefaultIsWhatTheVideoWasUntilNow() {
        val d = TvVideoSpec.DEFAULT
        assertEquals("1080p30 cbr 6000 gop1", d.text())
        assertEquals(1920, d.width)
        assertEquals(1080, d.height)
        assertEquals(30, d.fps)
        assertEquals(6_000_000, d.bitrate)
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
        assertEquals("1080p30 vbr 6000 gop1", s.text())
        assertEquals("720p30 cbr 6000 gop1", TvVideoSpec.parse("720p")!!.text())
        assertEquals("1080p30 cq 6000 gop1", TvVideoSpec.parse("  CQ , ")!!.text())
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
}
