package io.github.matijasalej.wholeflat.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SwapWatchTest {
    @Test
    fun theCountersOfVmstatAreRead() {
        val text = "nr_free_pages 12345\npswpin 1200\npswpout 98765\npgfault 5\n"
        assertEquals(Pair(1200L, 98765L), SwapWatch.parseVmstat(text))
        assertNull(SwapWatch.parseVmstat("nr_free_pages 1\n"))
        assertNull(SwapWatch.parseVmstat(""))
    }

    @Test
    fun aRateAboveThreeHundredPagesASecondIsABurstAndIsCounted() {
        val w = SwapWatch(300.0)
        assertNull(w.add(0, 1000, 5000)) // the first reading has nothing before it
        val calm = w.add(5_000, 1100, 5400)!! // 20 in, 80 out a second
        assertFalse(calm.burst)
        assertEquals(20.0, calm.inPerS, 1e-9)
        assertEquals(80.0, calm.outPerS, 1e-9)
        val burst = w.add(10_000, 1150, 8400)!! // 10 in, 600 out a second
        assertTrue(burst.burst)
        assertEquals(1, w.bursts)
        assertEquals(10_000L, w.lastBurstAtMs)
        assertEquals(610.0, w.worstPerS, 1e-9)
        assertFalse(w.add(15_000, 1160, 8500)!!.burst)
        assertEquals(1, w.bursts)
    }

    @Test
    fun countersThatGoBackAreNoRate() {
        val w = SwapWatch()
        w.add(0, 5000, 5000)
        assertNull(w.add(5_000, 10, 10)) // a reboot
        assertEquals(0.0, w.add(10_000, 10, 10)!!.outPerS, 1e-9)
    }
}
