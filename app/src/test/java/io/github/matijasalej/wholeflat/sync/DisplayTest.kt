package io.github.matijasalej.wholeflat.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayTest {
    @Test
    fun theFiveDisplaysAndWhatTheyAre() {
        assertEquals(listOf("view", "listen", "chill", "party", "karaoke"), Display.values().map { it.key })
        assertEquals(Display.VIEW, Display.DEFAULT)
        assertTrue(Display.VIEW.screenOn)
        assertFalse(Display.VIEW.lights)
        assertFalse(Display.VIEW.vibe)
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
        assertEquals(Display.LISTEN, Display.VIEW.next())
        assertEquals(Display.CHILL, Display.LISTEN.next())
        assertEquals(Display.PARTY, Display.CHILL.next())
        assertEquals(Display.KARAOKE, Display.PARTY.next())
        assertEquals(Display.VIEW, Display.KARAOKE.next())
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
