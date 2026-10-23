package io.github.matijasalej.wholeflat.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TsWriterTest {
    private val ms = 1_000_000L

    private fun setup(): Triple<TsMuxer, TsHistory, TsWriter> {
        val mux = TsMuxer().also { it.setClock(0L, 0L) }
        val history = TsHistory()
        return Triple(mux, history, TsWriter(mux, history))
    }

    private fun au(n: Int) = ByteArray(n) { (it + 1).toByte() }

    private fun everything(h: TsHistory): TsReader {
        val r = h.read(h.oldestPos, 100_000_000, 10) as TsHistory.Read.Data
        return TsReader(r.bytes)
    }

    @Test
    fun itemsComeOutInTheOrderTheyAreDueNotTheOrderTheyCame() {
        val (_, history, w) = setup()
        w.submitAudio(30 * ms, au(100), 3_000)
        w.submitVideo(10 * ms, au(500), 1_000, true)
        w.submitAudio(20 * ms, au(100), 2_000)
        w.step(40 * ms)
        val r = everything(history)
        assertEquals(emptyList<String>(), r.errors)
        val order = r.packets.filter { it.pusi && (it.pid == TsMuxer.VIDEO_PID || it.pid == TsMuxer.AUDIO_PID) }.map { it.pid }
        assertEquals(listOf(TsMuxer.VIDEO_PID, TsMuxer.AUDIO_PID, TsMuxer.AUDIO_PID), order)
        assertEquals(listOf(2_000L, 3_000L), r.pes[TsMuxer.AUDIO_PID]!!.map { it.pts })
    }

    @Test
    fun nothingIsWrittenBeforeItIsDue() {
        val (_, history, w) = setup()
        w.submitAudio(100 * ms, au(100), 3_000)
        val next = w.step(10 * ms)
        // the clock of the stream runs on: a PCR packet and the tables may have been written, but no audio
        val packets = history.endPos
        val r = if (packets > 0) everything(history) else null
        assertTrue(r == null || r.pes.isEmpty())
        // and it says when to come back: no later than the item's time
        assertTrue(next <= 100 * ms)
        w.step(100 * ms)
        assertEquals(1, everything(history).pes[TsMuxer.AUDIO_PID]!!.size)
    }

    @Test
    fun theWaitIsNeverLongerThanTheLimit() {
        val (_, _, w) = setup()
        assertEquals(10 * ms + 5 * ms, w.step(10 * ms))
        w.submitAudio(1_000 * ms, au(10), 1)
        assertEquals(20 * ms + 5 * ms, w.step(20 * ms, 5 * ms))
        assertEquals(20 * ms + 50 * ms, w.step(20 * ms, 50 * ms))
    }

    @Test
    fun aKeyAccessUnitIsAPlaceToStartInTheRing() {
        val (_, history, w) = setup()
        w.submitVideo(1 * ms, au(2_000), 1_000, true)
        w.submitAudio(2 * ms, au(100), 1_100)
        w.submitVideo(40 * ms, au(300), 4_000, false)
        w.submitVideo(1_001 * ms, au(2_000), 91_000, true)
        w.step(50 * ms)
        assertEquals(1, history.keyUnits)
        w.step(1_100 * ms)
        assertEquals(2, history.keyUnits)
        assertEquals(2L, w.keyUnits)
        assertEquals(0L, history.startFor(2_000 * ms, 2_000 * ms)) // the first unit begins with the tables of the first key unit
        assertTrue(history.startFor(1_100 * ms, 0)!! > 0L)
    }

    @Test
    fun theRingGetsTheMomentOfTheWriteNotTheDueTime() {
        val (_, history, w) = setup()
        w.submitVideo(1 * ms, au(500), 1_000, true) // due at 1 ms, written at 70 ms: 69 ms late
        w.step(70 * ms)
        assertEquals(70 * ms, history.nanosAt(0))
        val s = w.takeStats()
        assertEquals(69.0, s.videoLateMaxMs, 0.01)
        assertEquals(1, s.videoCount)
        assertEquals(0, s.audioCount)
    }

    @Test
    fun theStatisticsStartAgainAfterTheyAreTaken() {
        val (_, _, w) = setup()
        w.submitAudio(0, au(10), 1)
        w.submitAudio(10 * ms, au(10), 2)
        w.step(30 * ms)
        val a = w.takeStats()
        assertEquals(2, a.audioCount)
        assertEquals(25.0, a.audioLateAvgMs, 0.01) // 30 and 20 ms late
        assertEquals(30.0, a.audioLateMaxMs, 0.01)
        val b = w.takeStats()
        assertEquals(0, b.audioCount)
        assertEquals(0.0, b.audioLateMaxMs, 0.0)
    }

    @Test
    fun whenNothingComesThePcrAndTheTablesKeepComing() {
        val (_, history, w) = setup()
        var t = 0L
        while (t < 500 * ms) {
            w.step(t)
            t += 5 * ms
        }
        val r = everything(history)
        assertEquals(emptyList<String>(), r.errors)
        val pcrs = r.packets.filter { it.pcr != null }
        assertTrue("pcr packets ${pcrs.size}", pcrs.size >= 20)
        for (i in 1 until pcrs.size) assertTrue((pcrs[i].pcr!! - pcrs[i - 1].pcr!!) / 27_000.0 <= 40.0)
        assertTrue(r.packets.count { it.pid == 0 } >= 4)
    }

    @Test
    fun theQueueOrdersEqualTimesByArrival() {
        val q = DueQueue<String>()
        q.add(5, "a")
        q.add(5, "b")
        q.add(3, "c")
        assertEquals(3L, q.nextDue())
        assertNull(q.popDue(2))
        assertEquals("c", q.popDue(10)!!.second)
        assertEquals("a", q.popDue(10)!!.second)
        assertEquals("b", q.popDue(10)!!.second)
        assertNull(q.popDue(10))
        assertNull(q.nextDue())
    }
}
