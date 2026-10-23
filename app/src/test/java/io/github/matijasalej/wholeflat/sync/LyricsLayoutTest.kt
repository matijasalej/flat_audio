package io.github.matijasalej.wholeflat.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsLayoutTest {
    private val bg = CoverColor.rgb(64, 92, 140)

    @Test
    fun theActiveLineSitsAtItsPlaceWhenTheScrollIsOver() {
        val rows = LyricsLayout.rows(lineCount = 10, active = 4, sinceActiveMs = 400)
        val active = rows.single { it.role == LyricsLayout.Role.ACTIVE }
        assertEquals(4, active.index)
        assertEquals(250f, active.y, 0.01f)
        assertEquals(1.0, active.fade, 1e-9)
        // the lines before it are one line height apart above it, those to come below
        assertEquals(250f - 118f, rows.single { it.index == 3 }.y, 0.01f)
        assertEquals(250f + 118f, rows.single { it.index == 5 }.y, 0.01f)
        assertEquals(LyricsLayout.Role.SUNG, rows.single { it.index == 3 }.role)
        assertEquals(LyricsLayout.Role.AHEAD, rows.single { it.index == 5 }.role)
    }

    @Test
    fun aNewLineScrollsUpFromTheNextPlaceInThreeHundredFiftyMilliseconds() {
        val start = LyricsLayout.rows(10, 4, 0).single { it.index == 4 }
        assertEquals(250f + 118f, start.y, 0.01f)
        assertEquals(0.0, start.fade, 1e-9)
        val mid = LyricsLayout.rows(10, 4, 175).single { it.index == 4 }
        // an ease-out: more than half of the way at half of the time
        assertTrue("y ${mid.y}", mid.y < 250f + 59f && mid.y > 250f)
        assertTrue(mid.fade > 0.5 && mid.fade < 1.0)
        val end = LyricsLayout.rows(10, 4, 350).single { it.index == 4 }
        assertEquals(250f, end.y, 0.01f)
        assertEquals(1.0, end.fade, 1e-9)
        // the line that was sung moves up with it
        assertEquals(250f, LyricsLayout.rows(10, 4, 0).single { it.index == 3 }.y, 0.01f)
    }

    @Test
    fun beforeTheFirstLineTheFirstWaitsInTheNextPlaceAndNothingMoves() {
        val rows = LyricsLayout.rows(10, -1, 0)
        assertTrue(rows.none { it.role != LyricsLayout.Role.AHEAD })
        assertEquals(250f + 118f, rows.single { it.index == 0 }.y, 0.01f)
        assertEquals(rows.map { it.y }, LyricsLayout.rows(10, -1, 5_000).map { it.y })
    }

    @Test
    fun onlyTheLinesInTheTextAreaAreDrawn() {
        val rows = LyricsLayout.rows(100, 50, 400)
        assertTrue(rows.all { it.y >= LyricsLayout.VISIBLE_TOP && it.y <= LyricsLayout.VISIBLE_BOTTOM })
        // one above (the second is out of the area), the active one, and as many below as fit above y 820
        assertEquals(listOf(49, 50, 51, 52, 53, 54), rows.map { it.index })
    }

    @Test
    fun theLastLinesLeaveNothingBelowThem() {
        val rows = LyricsLayout.rows(5, 4, 1_000)
        // line 2 would be at y 14, above the text area
        assertEquals(listOf(3, 4), rows.map { it.index })
        assertTrue(LyricsLayout.rows(0, -1, 0).isEmpty())
    }

    @Test
    fun theColoursAreTheOnesOfTheMock() {
        val c = LyricsLayout.Colors(bg)
        // sung: white at 55 % over the background; ahead: black at 45 %
        assertEquals(CoverColor.mix(bg, CoverColor.rgb(255, 255, 255), 0.55), c.sung)
        assertEquals(CoverColor.mix(bg, CoverColor.rgb(0, 0, 0), 0.45), c.ahead)
        assertEquals(CoverColor.rgb(255, 255, 255), c.active(1.0))
        assertEquals(c.ahead, c.active(0.0))
        // the numbers for the background (64, 92, 140) of the mock
        assertEquals(CoverColor.rgb(169, 182, 203), c.sung)
        assertEquals(CoverColor.rgb(35, 51, 77), c.ahead)
        assertEquals(c.sung, c.of(LyricsLayout.Role.SUNG, 1.0))
        assertEquals(c.active(0.3), c.of(LyricsLayout.Role.ACTIVE, 0.3))
        assertEquals(c.ahead, c.of(LyricsLayout.Role.AHEAD, 1.0))
    }

    @Test
    fun theFooterColoursAreTheTrackAndTheSecondaryText() {
        val c = LyricsLayout.Colors(bg)
        assertEquals(CoverColor.mix(bg, CoverColor.rgb(255, 255, 255), 0.30), c.track)
        assertEquals(CoverColor.mix(bg, CoverColor.rgb(255, 255, 255), 0.70), c.secondary)
    }

    @Test
    fun aLongLineIsShrunkToFitButNeverBelowTheMinimum() {
        val width = { size: Float -> 40 * size * 0.5f } // 40 characters, half a size wide each
        val fit = LyricsLayout.fitSize(width, 1520f, 78f, 50f)
        assertTrue("fit $fit", fit < 78f && width(fit) <= 1520f)
        assertEquals(50f, LyricsLayout.fitSize({ s -> 100 * s }, 1520f, 78f, 50f), 0.01f)
        assertEquals(78f, LyricsLayout.fitSize({ s -> 10 * s }, 1520f, 78f, 50f), 0.01f)
    }

    @Test
    fun theClockShowsMinutesAndSeconds() {
        assertEquals("0:00", LyricsLayout.clock(0))
        assertEquals("0:08", LyricsLayout.clock(8_400))
        assertEquals("1:07", LyricsLayout.clock(67_999))
        assertEquals("12:00", LyricsLayout.clock(720_000))
        assertEquals("1:01:01", LyricsLayout.clock(3_661_000))
        assertEquals("0:00", LyricsLayout.clock(-5_000))
    }

    @Test
    fun theTimeLeftHasAMinusAndIsLeftOutWithoutALength() {
        assertEquals("-1:07", LyricsLayout.remaining(8_000, 75_000))
        assertEquals("-0:00", LyricsLayout.remaining(80_000, 75_000))
        assertEquals("", LyricsLayout.remaining(8_000, -1))
    }

    @Test
    fun theProgressIsAFractionOfTheLength() {
        assertEquals(0.0, LyricsLayout.progress(0, 100_000), 1e-9)
        assertEquals(0.25, LyricsLayout.progress(25_000, 100_000), 1e-9)
        assertEquals(1.0, LyricsLayout.progress(120_000, 100_000), 1e-9)
        assertEquals(0.0, LyricsLayout.progress(5_000, 0), 1e-9)
        assertEquals(0.0, LyricsLayout.progress(-5_000, 100_000), 1e-9)
    }

    @Test
    fun theDesignNumbersAreTheOnesOfTheMock() {
        assertEquals(1920, LyricsLayout.W)
        assertEquals(1080, LyricsLayout.H)
        assertEquals(200f, LyricsLayout.LINE_X, 0f)
        assertEquals(118f, LyricsLayout.LINE_H, 0f)
        assertEquals(78f, LyricsLayout.LINE_SIZE, 0f)
        assertEquals(132f, LyricsLayout.COVER_SIZE, 0f)
        assertEquals(900f, LyricsLayout.BAR_X0, 0f)
        assertEquals(1720f, LyricsLayout.BAR_X1, 0f)
        assertEquals(940f, LyricsLayout.BAR_Y, 0f)
        assertEquals(6f, LyricsLayout.BAR_H, 0f)
    }

    @Test
    fun theInstantScrollNeverMovesALineAndOnlyFadesTheColoursOfTheTwoLinesThatChange() {
        for (since in listOf(0L, 40L, 75L, 150L, 400L)) {
            val rows = LyricsLayout.rows(10, 4, since, instant = true)
            // the lines stand in their final places from the first picture: the active one at the top mark, the next one a line below
            assertEquals(LyricsLayout.ACTIVE_Y, rows.single { it.index == 4 }.y, 0.001f)
            assertEquals(LyricsLayout.ACTIVE_Y + LyricsLayout.LINE_H, rows.single { it.index == 5 }.y, 0.001f)
            assertEquals(LyricsLayout.ACTIVE_Y - LyricsLayout.LINE_H, rows.single { it.index == 3 }.y, 0.001f)
        }
        val start = LyricsLayout.rows(10, 4, 0, instant = true)
        assertEquals(0.0, start.single { it.index == 4 }.fade, 1e-9)
        assertTrue(start.single { it.index == 3 }.settling)
        assertEquals(0.0, start.single { it.index == 3 }.fade, 1e-9)
        val mid = LyricsLayout.rows(10, 4, 75, instant = true)
        assertEquals(LyricsLayout.ease(0.5), mid.single { it.index == 4 }.fade, 1e-9)
        assertEquals(LyricsLayout.ease(0.5), mid.single { it.index == 3 }.fade, 1e-9)
        // after 150 ms both are done: the active line is white and the one before it is plain sung
        val done = LyricsLayout.rows(10, 4, 150, instant = true)
        assertEquals(1.0, done.single { it.index == 4 }.fade, 1e-9)
        assertFalse(done.single { it.index == 3 }.settling)
        // no other line is settling
        assertEquals(listOf(3), start.filter { it.settling }.map { it.index })
    }

    @Test
    fun theColoursOfTheLinesThatChangeMeetAtWhiteAndTheSungColour() {
        val colors = LyricsLayout.Colors(CoverColor.rgb(40, 80, 120))
        val start = LyricsLayout.rows(10, 4, 0, instant = true)
        val done = LyricsLayout.rows(10, 4, 150, instant = true)
        assertEquals(colors.white, colors.of(start.single { it.index == 3 }))
        assertEquals(colors.sung, colors.of(done.single { it.index == 3 }))
        assertEquals(colors.ahead, colors.of(start.single { it.index == 4 }))
        assertEquals(colors.white, colors.of(done.single { it.index == 4 }))
        // the smooth scroll is as it was
        assertEquals(colors.sung, colors.of(LyricsLayout.rows(10, 4, 0).single { it.index == 3 }))
    }
}
