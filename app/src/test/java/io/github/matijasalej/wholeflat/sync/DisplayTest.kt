package io.github.matijasalej.wholeflat.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayTest {
    @Test
    fun theThreeDisplaysAndWhatTheyAre() {
        assertEquals(listOf("listen", "ambilight", "screen"), Display.values().map { it.key })
        assertEquals(Display.LISTEN, Display.DEFAULT)
        assertFalse(Display.LISTEN.screenOn)
        assertFalse(Display.LISTEN.lights)
        assertFalse(Display.AMBILIGHT.screenOn)
        assertTrue(Display.AMBILIGHT.lights)
        assertTrue(Display.SCREEN.screenOn)
        assertTrue(Display.SCREEN.lights)
    }

    @Test
    fun theButtonOfTheTvAppCyclesThroughThem() {
        assertEquals(Display.AMBILIGHT, Display.LISTEN.next())
        assertEquals(Display.SCREEN, Display.AMBILIGHT.next())
        assertEquals(Display.LISTEN, Display.SCREEN.next())
    }

    @Test
    fun aKeyIsFoundInAnyCaseAndOtherTextIsNone() {
        assertEquals(Display.SCREEN, Display.of(" Screen "))
        assertEquals(Display.AMBILIGHT, Display.of("AMBILIGHT"))
        assertNull(Display.of("party"))
        assertNull(Display.of(""))
        assertNull(Display.of(null))
    }
}
