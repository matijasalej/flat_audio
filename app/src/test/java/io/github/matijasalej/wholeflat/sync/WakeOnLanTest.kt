package io.github.matijasalej.wholeflat.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WakeOnLanTest {
    @Test
    fun aMacIsReadWithColonsOrDashesInAnyCase() {
        val want = byteArrayOf(0xC0.toByte(), 0xD2.toByte(), 0xF3.toByte(), 0x87.toByte(), 0xBF.toByte(), 0xF4.toByte())
        assertArrayEquals(want, WakeOnLan.parseMac("C0:D2:F3:87:BF:F4"))
        assertArrayEquals(want, WakeOnLan.parseMac(" c0-d2-f3-87-bf-f4 "))
        for (bad in listOf("", "C0:D2:F3:87:BF", "C0:D2:F3:87:BF:F4:00", "C0:D2:F3:87:BF:ZZ", "C0D2F387BFF4", "C:D2:F3:87:BF:F4")) assertNull(bad, WakeOnLan.parseMac(bad))
    }

    @Test
    fun theMagicPacketIsSixFFAndTheMacSixteenTimes() {
        val mac = WakeOnLan.parseMac("01:02:03:04:05:06")!!
        val p = WakeOnLan.packet(mac)
        assertEquals(102, p.size)
        for (i in 0 until 6) assertEquals(0xFF.toByte(), p[i])
        for (r in 0 until 16) assertArrayEquals(mac, p.copyOfRange(6 + r * 6, 12 + r * 6))
    }

    @Test
    fun theInterfaceThatCanWakeTheTvIsPickedFromTheListOfDevices() {
        val json = """[{"id":"eth0","mac":"AA:BB:CC:00:00:01","wake-on-lan":"Disabled"},{"id":"wifi0","mac":"c0:d2:f3:87:bf:f4","wake-on-lan":"Enabled"}]"""
        assertEquals("C0:D2:F3:87:BF:F4", WakeOnLan.macFromDevices(json))
        // none with Wake-on-LAN: the first with an address
        assertEquals("AA:BB:CC:00:00:01", WakeOnLan.macFromDevices("""[{"id":"eth0","mac":"AA:BB:CC:00:00:01"},{"id":"wifi0","mac":"c0:d2:f3:87:bf:f4"}]"""))
        assertNull(WakeOnLan.macFromDevices("""[{"id":"x"}]"""))
        assertNull(WakeOnLan.macFromDevices("not json"))
    }
}
