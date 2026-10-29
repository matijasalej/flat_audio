package io.github.matijasalej.wholeflat.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplaySwitchTest {
    @Test
    fun theCommandsAreTheMacsWithoutAdbShell() {
        assertEquals(ReplayGate.MUTE_COMMAND, "adb shell " + ReplaySwitch.command(true))
        assertEquals(ReplayGate.UNMUTE_COMMAND, "adb shell " + ReplaySwitch.command(false))
        assertEquals("adb tcpip 5555", ReplaySwitch.ARM_COMMAND)
    }

    @Test
    fun whatStandsInTheWayIsSaidFirst() {
        assertTrue(ReplaySwitch.text(ReplaySwitch.Link.WAITING_ALLOW, false, false, true, null).contains("Allow"))
        assertTrue(ReplaySwitch.text(ReplaySwitch.Link.NOT_ARMED, true, true, null, null).contains(ReplaySwitch.ARM_COMMAND))
        assertTrue(ReplaySwitch.text(ReplaySwitch.Link.FAILED, true, true, null, "boom").contains("boom"))
    }

    @Test
    fun theSwitchSaysWhatTheMuteIs() {
        val ready = ReplaySwitch.Link.READY
        assertTrue(ReplaySwitch.text(ready, true, true, null, null).startsWith("On:"))
        assertTrue(ReplaySwitch.text(ready, false, false, null, null).startsWith("Off:"))
        assertTrue(ReplaySwitch.text(ReplaySwitch.Link.UNKNOWN, true, false, null, null).startsWith("On, but"))
        // asked for and not seen yet
        assertEquals("Switching on…", ReplaySwitch.text(ready, false, false, true, null))
        // asked for and seen
        assertTrue(ReplaySwitch.text(ready, true, true, true, null).startsWith("On:"))
    }
}
