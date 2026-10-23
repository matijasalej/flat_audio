package io.github.matijasalej.wholeflat.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneFadeTest {
    private val s = 1_000_000_000L

    @Test
    fun theFirstSceneIsShownAsItIs() {
        val f = SceneFade<String>()
        val a = f.step("a", "A1", 10 * s)
        assertNull(a.from)
        assertEquals(1f, a.alpha, 0f)
        val b = f.step("a", "A2", 11 * s)
        assertNull(b.from)
    }

    @Test
    fun aNewTrackFadesInOverAboutASecondFromTheLastPictureOfTheOldOne() {
        val f = SceneFade<String>()
        f.step("a", "A1", 10 * s)
        f.step("a", "A2", 11 * s)
        val start = f.step("b", "B1", 12 * s)
        assertEquals("A2", start.from)
        assertEquals(0f, start.alpha, 0f)
        val mid = f.step("b", "B2", 12 * s + s / 2)
        assertEquals("A2", mid.from)
        assertEquals(0.5f, mid.alpha, 0.001f)
        val quarter = f.step("b", "B3", 12 * s + s / 4)
        assertTrue(quarter.alpha < 0.2f && quarter.alpha > 0.1f)
        val done = f.step("b", "B4", 13 * s)
        assertNull(done.from)
        assertEquals(1f, done.alpha, 0f)
        // and stays done
        assertNull(f.step("b", "B5", 13 * s + 33_000_000L).from)
    }

    @Test
    fun theFadeFollowsTheCaptureTimeNotTheNumberOfPictures() {
        // a picture drawn twice for the same time gets the same alpha: the pictures of a stall are not a faster fade
        val f = SceneFade<String>()
        f.step("a", "A", 0)
        f.step("b", "B", 5 * s)
        val x = f.step("b", "B", 5 * s + 300_000_000L)
        val y = f.step("b", "B", 5 * s + 300_000_000L)
        assertEquals(x.alpha, y.alpha, 0f)
    }

    @Test
    fun aTrackThatChangesInTheMiddleOfAFadeFadesFromTheSceneThatWasComingIn() {
        val f = SceneFade<String>()
        f.step("a", "A", 0)
        f.step("b", "B1", 1 * s)
        val mid = f.step("b", "B2", 1 * s + s / 2)
        assertEquals("A", mid.from)
        val c = f.step("c", "C1", 1 * s + 600_000_000L)
        assertEquals("B2", c.from)
        assertEquals(0f, c.alpha, 0f)
    }

    @Test
    fun aSceneAfterAResetComesInWithoutAFade() {
        val f = SceneFade<String>()
        f.step("a", "A", 0)
        f.reset()
        val b = f.step("b", "B", 1 * s)
        assertNull(b.from)
        assertEquals(1f, b.alpha, 0f)
    }

    @Test
    fun theCurveIsSlowAtBothEnds() {
        assertEquals(0.0, SceneFade.smooth(0.0), 0.0)
        assertEquals(1.0, SceneFade.smooth(1.0), 0.0)
        assertEquals(0.5, SceneFade.smooth(0.5), 1e-9)
        assertTrue(SceneFade.smooth(0.1) < 0.1)
        assertTrue(SceneFade.smooth(0.9) > 0.9)
        assertEquals(0.0, SceneFade.smooth(-3.0), 0.0)
        assertEquals(1.0, SceneFade.smooth(7.0), 0.0)
    }
}
