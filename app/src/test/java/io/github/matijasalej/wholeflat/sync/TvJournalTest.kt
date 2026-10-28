package io.github.matijasalej.wholeflat.sync

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class TvJournalTest {
    private fun tempFile(): File = File(Files.createTempDirectory("journal").toFile(), "tv_journal.jsonl")

    @Test
    fun anEventKeepsItsFieldsWithTheTimeAndTheKind() {
        val j = TvJournal(null, wallMs = { 0L })
        j.add("grab", JSONObject().put("outcome", "playing").put("get_ms", 2400).put("kind", "ignored"))
        val e = j.toJson().getJSONObject(0)
        assertEquals("grab", e.getString("kind"))
        assertEquals("playing", e.getString("outcome"))
        assertEquals(2400, e.getInt("get_ms"))
        assertTrue(e.getString("t").isNotEmpty())
        assertEquals(4, e.length())
    }

    @Test
    fun onlyTheLastEventsAreKept() {
        val j = TvJournal(null, capacity = 3)
        for (i in 1..5) j.add("grab", JSONObject().put("n", i))
        assertEquals(3, j.size())
        assertEquals(listOf(3, 4, 5), (0 until 3).map { j.toJson().getJSONObject(it).getInt("n") })
    }

    @Test
    fun theJournalOutlivesARestartAndTheFileIsCutBack() {
        val f = tempFile()
        val a = TvJournal(f, capacity = 4)
        for (i in 1..9) a.add(if (i % 2 == 0) "end" else "grab", JSONObject().put("n", i))
        assertNull(a.lastError)
        // 8 lines are twice the capacity: the 9th cut the file back to the 4 kept
        assertTrue("lines in the file: ${f.readLines().size}", f.readLines().size <= 8)
        val b = TvJournal(f, capacity = 4)
        assertEquals(listOf(6, 7, 8, 9), (0 until 4).map { b.toJson().getJSONObject(it).getInt("n") })
        assertEquals(listOf(7, 9), b.last(5, "grab").map { it.getInt("n") })
        assertEquals(listOf(8, 9), b.last(2).map { it.getInt("n") })
    }

    @Test
    fun aLineCutByACrashIsLeftOut() {
        val f = tempFile()
        f.writeText("{\"t\":\"x\",\"kind\":\"grab\",\"n\":1}\n{\"t\":\"x\",\"kind\":\"gr")
        val j = TvJournal(f)
        assertEquals(1, j.size())
        // the next line starts on a line of its own, not glued to the cut one
        j.add("end", JSONObject().put("n", 2))
        val again = TvJournal(f)
        assertEquals(2, again.size())
        assertEquals("end", again.toJson().getJSONObject(1).getString("kind"))
    }
}
