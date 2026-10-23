package io.github.matijasalej.wholeflat.light

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SongMemoryTest {
    private class MemoryStore(var text: String? = null) : SongStore {
        override fun read(): String? = text

        override fun write(json: String) {
            text = json
        }
    }

    @Test
    fun aSongIsRememberedAndHisOverrideOutlivesTheApp() {
        val store = MemoryStore()
        val m = SongMemory(store)
        m.remember("a", "Title", "Artist", 121.5, 0.7, 0.6, 0.4, LightClass.DUO, 1000, newPlay = true)
        m.override("a", LightClass.MULTI, 2000)
        val again = SongMemory(store).get("a")!!
        assertEquals(121.5, again.bpm, 1e-9)
        assertEquals(LightClass.DUO, again.cls)
        assertEquals(LightClass.MULTI, again.override)
        assertEquals("Title", again.title)
        assertEquals(1, again.plays)
    }

    @Test
    fun aNewMeasurementKeepsHisOverride() {
        val m = SongMemory(MemoryStore())
        m.override("a", LightClass.MONO, 1)
        m.remember("a", "T", null, 90.0, 0.8, 0.8, 0.5, LightClass.MULTI, 2, newPlay = true)
        assertEquals(LightClass.MONO, m.get("a")!!.override)
        m.override("a", null, 3)
        assertNull(m.get("a")!!.override)
    }

    @Test
    fun theOldestSongsGoFirstAndTheNewestComesFirst() {
        val m = SongMemory(MemoryStore(), max = 3)
        for (k in listOf("a", "b", "c", "d")) m.remember(k, null, null, 100.0, 0.5, 0.5, 0.5, LightClass.DUO, 0, newPlay = true)
        assertNull(m.get("a"))
        assertEquals(listOf("d", "c", "b"), m.all().map { it.key })
        m.remember("b", null, null, 100.0, 0.5, 0.5, 0.5, LightClass.DUO, 0, newPlay = true)
        assertEquals("b", m.all().first().key)
        assertEquals(2, m.get("b")!!.plays)
    }

    @Test
    fun aMemoryThatCannotBeReadStartsEmpty() {
        assertTrue(SongMemory(MemoryStore("{not json")).all().isEmpty())
    }
}
