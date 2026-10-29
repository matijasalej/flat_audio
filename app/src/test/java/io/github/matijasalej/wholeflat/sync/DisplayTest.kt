package io.github.matijasalej.wholeflat.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayTest {
    @Test
    fun theFourDisplaysAndWhatTheyAre() {
        assertEquals(listOf("listen", "chill", "party", "karaoke"), Display.values().map { it.key })
        assertEquals(Display.LISTEN, Display.DEFAULT)
        assertFalse(Display.LISTEN.screenOn)
        assertFalse(Display.LISTEN.lights)
        assertFalse(Display.LISTEN.vibe)
        assertFalse(Display.CHILL.screenOn)
        assertTrue(Display.CHILL.lights)
        assertFalse(Display.CHILL.vibe)
        assertFalse(Display.PARTY.screenOn)
        assertTrue(Display.PARTY.lights)
        assertTrue(Display.PARTY.vibe)
        assertTrue(Display.KARAOKE.screenOn)
        assertTrue(Display.KARAOKE.lights)
        assertTrue(Display.KARAOKE.vibe)
    }

    @Test
    fun theButtonOfTheTvAppGoesRound() {
        assertEquals(Display.CHILL, Display.LISTEN.next())
        assertEquals(Display.PARTY, Display.CHILL.next())
        assertEquals(Display.KARAOKE, Display.PARTY.next())
        assertEquals(Display.LISTEN, Display.KARAOKE.next())
    }

    @Test
    fun theSettingIsReadInAnyCaseAndTheOlderKeysAreTheNewDisplays() {
        assertEquals(Display.KARAOKE, Display.of(" Karaoke "))
        assertEquals(Display.PARTY, Display.of("PARTY"))
        assertEquals(Display.PARTY, Display.of("ambilight"))
        assertEquals(Display.KARAOKE, Display.of(" Screen "))
        assertNull(Display.of("disco"))
        assertNull(Display.of(""))
        assertNull(Display.of(null))
    }
}
