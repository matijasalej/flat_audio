package io.github.matijasalej.wholeflat.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionHistoryTest {
    private val ms = 1_000_000L
    private val t0 = 50_000L * ms

    private fun record(): SessionRecord = SessionRecord("abc123", "capture", t0, 1_700_000_000_000L)

    @Test
    fun aConnectionIsRecordedWithItsStartEndBytesAndWhoClosedIt() {
        val r = record()
        val c = r.openConnection("GET", "192.168.0.212:40000", "Linux UPnP/1.0 Sonos", true, t0 + 1_200 * ms)
        c.bytes = 65_752
        c.blocks = 4
        c.firstByteMs = 1_201
        r.closeConnection(c, t0 + 1_240 * ms, "peer", "peer: Connection reset by peer")
        val j = r.toJson(t0 + 9_000 * ms)
        val conn = j.getJSONArray("connections").getJSONObject(0)
        assertEquals(1, conn.getInt("conn"))
        assertEquals("GET", conn.getString("method"))
        assertEquals(1_200L, conn.getLong("start_ms"))
        assertEquals(1_240L, conn.getLong("end_ms"))
        assertEquals(40L, conn.getLong("duration_ms"))
        assertEquals(65_752L, conn.getLong("bytes"))
        assertEquals(4L, conn.getLong("blocks"))
        assertEquals("peer", conn.getString("closed_by"))
        assertEquals("peer: Connection reset by peer", conn.getString("reason"))
        assertFalse(conn.getBoolean("open"))
        assertTrue(conn.getBoolean("armed"))
    }

    @Test
    fun anOpenConnectionShowsItsAgeAndNoEnd() {
        val r = record()
        r.openConnection("GET", "192.168.0.212:40001", "ua", false, t0 + 100 * ms)
        val conn = r.toJson(t0 + 600 * ms).getJSONArray("connections").getJSONObject(0)
        assertTrue(conn.getBoolean("open"))
        assertTrue(conn.isNull("end_ms"))
        assertTrue(conn.isNull("closed_by"))
        assertEquals(500L, conn.getLong("duration_ms"))
    }

    @Test
    fun aConnectionIsClosedOnlyOnce() {
        val r = record()
        val c = r.openConnection("GET", "x", "ua", true, t0)
        r.closeConnection(c, t0 + 10 * ms, "peer", "eof")
        r.closeConnection(c, t0 + 99 * ms, "server", "session ended")
        assertEquals(10L, c.endMs)
        assertEquals("peer", c.closedBy)
    }

    @Test
    fun theConnectionsAreNumberedInTheOrderTheyCameHeadsIncluded() {
        val r = record()
        r.openConnection("GET", "x", "ua", false, t0)
        r.openConnection("HEAD", "x", "ua", true, t0 + 1 * ms)
        r.openConnection("GET", "x", "ua", true, t0 + 2 * ms)
        val a = r.toJson(t0 + 10 * ms).getJSONArray("connections")
        assertEquals(listOf(1, 2, 3), (0 until 3).map { a.getJSONObject(it).getInt("conn") })
        assertEquals(listOf("GET", "HEAD", "GET"), (0 until 3).map { a.getJSONObject(it).getString("method") })
    }

    @Test
    fun theSessionRemembersPlayEndAndFailure() {
        val r = record()
        r.playSent(t0 + 400 * ms)
        r.failed("the speaker reports IDLE", "transport STOPPED status OK")
        r.failed("a second reason is ignored", "x")
        r.ended(t0 + 7_000 * ms, "re-join: the speaker reports IDLE")
        r.ended(t0 + 9_000 * ms, "ignored")
        val j = r.toJson(t0 + 10_000 * ms)
        assertEquals(400L, j.getLong("play_sent_ms"))
        assertEquals(7_000L, j.getLong("ended_ms"))
        assertEquals("re-join: the speaker reports IDLE", j.getString("end_reason"))
        assertEquals("the speaker reports IDLE", j.getJSONObject("failed").getString("why"))
        assertEquals("transport STOPPED status OK", j.getJSONObject("failed").getString("sonos"))
        assertEquals("capture", j.getString("kind"))
        assertEquals(10L, j.getLong("age_s"))
    }

    @Test
    fun aSessionWithoutFailureSaysSo() {
        val j = record().toJson(t0)
        assertTrue(j.isNull("failed"))
        assertTrue(j.isNull("ended_ms"))
        assertTrue(j.isNull("play_sent_ms"))
    }

    @Test
    fun theTransportNotesKeepChangesAndSkipRepeats() {
        val r = record()
        r.noteTransport(t0 + 1_000 * ms, "STOPPED", "OK", "ours", "", "")
        r.noteTransport(t0 + 2_000 * ms, "STOPPED", "OK", "ours", "", "") // the same again, soon: skipped
        r.noteTransport(t0 + 4_000 * ms, "PLAYING", "OK", "ours", "PLAYBACK_STATE_PLAYING", "")
        r.noteTransport(t0 + 7_000 * ms, "PLAYING", "OK", "ours", "PLAYBACK_STATE_PLAYING", "") // 3 s later: skipped
        r.noteTransport(t0 + 10_000 * ms, "PLAYING", "OK", "ours", "PLAYBACK_STATE_PLAYING", "") // 6 s later: noted
        r.noteTransport(t0 + 11_000 * ms, "STOPPED", "ERROR_OCCURRED", "ours", "PLAYBACK_STATE_IDLE", "ERROR_PLAYBACK_FAILED stream unreadable")
        val a = r.toJson(t0 + 12_000 * ms).getJSONArray("transport")
        assertEquals(4, a.length())
        assertEquals("STOPPED", a.getJSONObject(0).getString("state"))
        assertEquals("PLAYING", a.getJSONObject(1).getString("state"))
        assertEquals(10_000L, a.getJSONObject(2).getLong("t_ms"))
        assertEquals("ERROR_OCCURRED", a.getJSONObject(3).getString("status"))
        assertEquals("ERROR_PLAYBACK_FAILED stream unreadable", a.getJSONObject(3).getString("error"))
        assertTrue(a.getJSONObject(0).isNull("error"))
    }

    @Test
    fun theNotesAreBounded() {
        val r = record()
        for (i in 0 until 200) r.noteTransport(t0 + i * 10_000L * ms, if (i % 2 == 0) "A" else "B", "OK", "", "", "")
        assertEquals(60, r.toJson(t0 + 3_000_000L * ms).getJSONArray("transport").length())
    }

    @Test
    fun theSpeakerIsWatchedWhileTheSessionRunsAndThirtySecondsAfter() {
        val r = record()
        assertTrue(r.wantsNotes(t0 + 5_000 * ms))
        r.ended(t0 + 6_000 * ms, "done")
        assertTrue(r.wantsNotes(t0 + 30_000 * ms))
        assertFalse(r.wantsNotes(t0 + 36_001 * ms))
    }

    @Test
    fun onlyTheLastTenSessionsAreKeptNewestFirst() {
        val h = SessionHistory(10)
        assertNull(h.latest())
        for (i in 1..13) h.begin("t$i", if (i % 2 == 0) "ring" else "capture", t0 + i * ms, 1_700_000_000_000L + i)
        val a = h.toJson(t0 + 100 * ms)
        assertEquals(10, a.length())
        assertEquals("t13", a.getJSONObject(0).getString("token"))
        assertEquals("t4", a.getJSONObject(9).getString("token"))
        assertNotNull(h.latest())
        assertEquals("t13", h.latest()!!.token)
    }
}
