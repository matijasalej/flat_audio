package io.github.matijasalej.wholeflat.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyMessageTest {
    @Test
    fun nothingIsSaidWhileAllIsWell() {
        assertNull(KeyMessage.text("ok", "ok", -1, 0))
    }

    @Test
    fun theCountdownIsInTheSentence() {
        assertEquals(
            "Spotify is refusing playback for this account right now, retrying in 12 s (refusal 3)",
            KeyMessage.text("key_refused", "waiting", 12, 3),
        )
        assertEquals(
            "Spotify is refusing playback for this account right now, retrying in 5 min (refusal 5)",
            KeyMessage.text("key_refused", "waiting", 300, 5),
        )
    }

    @Test
    fun aTryUnderWayAndAPauseHaveTheirOwnWords() {
        assertTrue(KeyMessage.text("key_refused", "retrying", -1, 2)!!.contains("trying again now (refusal 2)"))
        assertTrue(KeyMessage.text("key_refused", "user_paused", -1, 2)!!.contains("press play on the phone"))
    }

    @Test
    fun givingUpTellsWhatToDo() {
        val text = KeyMessage.text("key_gave_up", "gave_up", -1, 7)!!
        assertTrue(text.startsWith("Spotify kept refusing playback for this account"))
        assertTrue(text.contains("Press play on the phone"))
    }

    @Test
    fun durationsAreShortAndRoundedToMinutes() {
        assertEquals("3 s", KeyMessage.duration(3))
        assertEquals("119 s", KeyMessage.duration(119))
        assertEquals("2 min", KeyMessage.duration(120))
        assertEquals("15 min", KeyMessage.duration(900))
    }

    @Test
    fun aMissingCountdownSaysAMoment() {
        assertTrue(KeyMessage.text("key_refused", "waiting", -1, 1)!!.contains("retrying in a moment"))
    }
}
