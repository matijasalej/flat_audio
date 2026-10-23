package io.github.matijasalej.wholeflat.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class LatenessTest {
    @Test
    fun aPeriodHasTheCountTheAverageTheWorstAndTheNumberOverTheLimit() {
        val l = Lateness(50.0)
        l.note(1.0)
        l.note(3.0)
        l.note(120.0)
        l.note(-5.0) // early is none
        val s = l.take()
        assertEquals(4, s.count)
        assertEquals(31.0, s.avgMs, 1e-9)
        assertEquals(120.0, s.maxMs, 0.0)
        assertEquals(1, s.over)
        // the next period starts empty; the worst since the start stays
        val t = l.take()
        assertEquals(0, t.count)
        assertEquals(0.0, t.maxMs, 0.0)
        assertEquals(120.0, l.worstMs, 0.0)
    }
}
