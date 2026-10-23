package io.github.matijasalej.wholeflat.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class MeminfoDumpTest {
    private val dump = """
Applications Memory Usage (in Kilobytes):
Uptime: 12345 Realtime: 12345

Total RSS by process:
    231,060K: com.google.android.apps.tv.launcherx (pid 1570 / activities)
     98,112K: com.netflix.ninja (pid 4021 / activities)
     45,300K: com.netflix.ninja:service (pid 4100 / services)
     21,000K: io.github.matijasalej.wholeflat (pid 900)

Total RSS by OOM adjustment:
    231,060K: Foreground
"""

    @Test
    fun theProcessesAndWhatTheyHoldAreRead() {
        val p = MeminfoDump.parse(dump)
        assertEquals(4, p.size)
        assertEquals("com.google.android.apps.tv.launcherx", p[0].name)
        assertEquals(231_060L, p[0].kb)
        assertEquals(1570, p[0].pid)
        assertEquals("com.netflix.ninja", p[2].packageName)
        assertEquals(45_300L, p[2].kb)
    }

    @Test
    fun withoutRssTheSectionOfPssIsUsedAndNothingGivesAnEmptyList() {
        val pss = "Total PSS by process:\n     10,000K: a.b.c (pid 5 / activities)\n\nTotal PSS by OOM adjustment:\n"
        assertEquals(listOf("a.b.c"), MeminfoDump.parse(pss).map { it.name })
        assertEquals(emptyList<String>(), MeminfoDump.parse("nothing here").map { it.name })
    }
}
