package io.github.matijasalej.wholeflat.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcTest {
    @Test
    fun linesAreReadWithTheirTimes() {
        val lines = Lrc.parse("[00:12.00]First line\n[00:17.50]Second line\n[01:03.250]Third\n")
        assertEquals(listOf(12_000L, 17_500L, 63_250L), lines.map { it.timeMs })
        assertEquals(listOf("First line", "Second line", "Third"), lines.map { it.text })
    }

    @Test
    fun theFractionMayHaveOneTwoOrThreeDigitsOrNone() {
        val lines = Lrc.parse("[00:01.5]a\n[00:02.50]b\n[00:03.500]c\n[00:04]d\n[00:05:25]e")
        assertEquals(listOf(1_500L, 2_500L, 3_500L, 4_000L, 5_250L), lines.map { it.timeMs })
    }

    @Test
    fun severalTimeTagsMakeSeveralLinesOfOneText() {
        val lines = Lrc.parse("[00:10.00][00:50.00][01:30.00]Refrain\n[00:20.00]Verse")
        assertEquals(listOf(10_000L, 20_000L, 50_000L, 90_000L), lines.map { it.timeMs })
        assertEquals(listOf("Refrain", "Verse", "Refrain", "Refrain"), lines.map { it.text })
    }

    @Test
    fun linesOutOfOrderAreSortedAndEqualTimesKeepTheirOrder() {
        val lines = Lrc.parse("[00:30.00]c\n[00:10.00]a\n[00:10.00]b\n")
        assertEquals(listOf("a", "b", "c"), lines.map { it.text })
    }

    @Test
    fun anEmptyLineIsAGapThatIsKept() {
        val lines = Lrc.parse("[00:10.00]Sing\n[00:20.00]\n[00:30.00]Again")
        assertEquals(3, lines.size)
        assertEquals("", lines[1].text)
    }

    @Test
    fun tagsThatAreNotTimesAreIgnored() {
        val lines = Lrc.parse("[ar:Artist]\n[ti:Title]\n[al:Album]\n[by:someone]\n[00:05.00]Hello")
        assertEquals(1, lines.size)
        assertEquals("Hello", lines[0].text)
    }

    @Test
    fun theOffsetMovesEveryLineEarlierWhenItIsPositive() {
        assertEquals(listOf(9_500L), Lrc.parse("[offset:+500]\n[00:10.00]x").map { it.timeMs })
        assertEquals(listOf(10_500L), Lrc.parse("[offset:-500]\n[00:10.00]x").map { it.timeMs })
        // never before the start
        assertEquals(listOf(0L), Lrc.parse("[offset:+5000]\n[00:01.00]x").map { it.timeMs })
    }

    @Test
    fun theWordTimesOfTheEnhancedFormatAreRemoved() {
        val lines = Lrc.parse("[00:10.00]<00:10.00>Hel<00:10.40>lo <00:10.80>world")
        assertEquals("Hello world", lines[0].text)
    }

    @Test
    fun windowsLineEndingsAndABomAreHandled() {
        val lines = Lrc.parse("﻿[00:01.00]one\r\n[00:02.00]two\r\n")
        assertEquals(listOf("one", "two"), lines.map { it.text })
    }

    @Test
    fun textWithoutTimesGivesNoLines() {
        assertTrue(Lrc.parse("just words\nno times").isEmpty())
        assertTrue(Lrc.parse("").isEmpty())
    }

    @Test
    fun theLineAtAPositionIsTheLastOneThatHasBegun() {
        val lines = Lrc.parse("[00:10.00]a\n[00:20.00]b\n[00:30.00]c")
        assertEquals(-1, Lrc.lineAt(lines, 0))
        assertEquals(-1, Lrc.lineAt(lines, 9_999))
        assertEquals(0, Lrc.lineAt(lines, 10_000))
        assertEquals(0, Lrc.lineAt(lines, 19_999))
        assertEquals(1, Lrc.lineAt(lines, 20_000))
        assertEquals(2, Lrc.lineAt(lines, 30_000))
        assertEquals(2, Lrc.lineAt(lines, 10_000_000))
        assertEquals(-1, Lrc.lineAt(emptyList(), 5_000))
    }

    @Test
    fun theLineAtAPositionWorksOnLongLyrics() {
        val text = (0 until 500).joinToString("\n") { "[%02d:%02d.00]line $it".format(it * 3 / 60, it * 3 % 60) }
        val lines = Lrc.parse(text)
        assertEquals(500, lines.size)
        assertEquals(250, Lrc.lineAt(lines, 250 * 3_000L + 1))
        assertEquals(333, Lrc.lineAt(lines, 999_999))
        assertEquals(499, Lrc.lineAt(lines, 5_000_000))
    }
}
