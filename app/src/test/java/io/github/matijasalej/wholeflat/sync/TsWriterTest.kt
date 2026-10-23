package io.github.matijasalej.wholeflat.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TsWriterTest {
    private val ms = 1_000_000L

    /** The slot that is due at a time: 5984.04 a second, from the time 0. */
    private val slotOf: (Long) -> Long = { t -> Math.floorDiv(t * 5_984_040L, 1_000_000_000_000L) }

    private fun setup(): Triple<TsMuxer, TsHistory, TsWriter> {
        val mux = TsMuxer()
        val history = TsHistory()
        return Triple(mux, history, TsWriter(mux, history, slotOf))
    }

    private fun au(n: Int) = ByteArray(n) { (it + 1).toByte() }

    private fun everything(h: TsHistory): TsReader {
        val r = h.read(h.oldestPos, 100_000_000, 10) as TsHistory.Read.Data
        return TsReader(r.bytes)
    }

    @Test
    fun theRingHoldsExactlyTheSlotsThatHaveComeByWhetherAnythingWasSentOrNot() {
        val (_, history, w) = setup()
        w.step(1_000 * ms)
        val slots = slotOf(1_000 * ms) + 1
        assertEquals(slots * 188, history.endPos)
        assertEquals(slots * 188, w.bytes)
        w.step(1_500 * ms)
        assertEquals((slotOf(1_500 * ms) + 1) * 188, history.endPos)
        val r = everything(history)
        assertEquals(emptyList<String>(), r.errors)
        // padding, the clock and the tables
        assertTrue(r.packets.count { it.pid == TsMuxer.NULL_PID } > r.packets.size * 0.95)
    }

    @Test
    fun itemsReachTheStreamInTheOrderTheyAreDueNotTheOrderTheyCame() {
        val (_, history, w) = setup()
        w.submitAudio(30 * ms, au(100), 3_000)
        w.submitVideo(10 * ms, au(500), 1_000, true)
        w.submitAudio(20 * ms, au(100), 2_000)
        w.step(60 * ms)
        val r = everything(history)
        assertEquals(emptyList<String>(), r.errors)
        assertEquals(listOf(2_000L, 3_000L), r.pes[TsMuxer.AUDIO_PID]!!.map { it.pts })
        assertEquals(1, r.pes[TsMuxer.VIDEO_PID]!!.size)
        // the audio that was due first is first among the audio, and every item went out whole
        assertEquals(500, r.pes[TsMuxer.VIDEO_PID]!![0].payload.size)
    }

    @Test
    fun nothingIsQueuedBeforeItIsDue() {
        val (mux, history, w) = setup()
        w.submitAudio(100 * ms, au(100), 3_000)
        val next = w.step(10 * ms)
        assertEquals(0L, mux.audioFrames)
        assertTrue(next <= 100 * ms)
        w.step(100 * ms)
        assertEquals(1L, mux.audioFrames)
        w.step(150 * ms)
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
    fun aKeyPictureIsAPlaceToStartInTheRing() {
        val (_, history, w) = setup()
        w.step(5 * ms)
        w.submitVideo(10 * ms, au(2_000), 1_000, true)
        w.submitAudio(12 * ms, au(100), 1_100)
        w.submitVideo(40 * ms, au(300), 4_000, false)
        w.submitVideo(1_001 * ms, au(2_000), 91_000, true)
        w.step(50 * ms)
        assertEquals(1, history.keyUnits)
        w.step(1_100 * ms)
        assertEquals(2, history.keyUnits)
        assertEquals(2L, w.keyUnits)
        // the places begin with the PAT
        val first = history.startFor(2_000 * ms, 2_000 * ms)!!
        val bytes = (history.read(first, 188, 10) as TsHistory.Read.Data).bytes
        assertEquals(0, ((bytes[1].toInt() and 0x1F) shl 8) or (bytes[2].toInt() and 0xFF))
        assertTrue(history.newestKey()!! > first)
        // everything that came was kept whole
        val r = everything(history)
        assertEquals(emptyList<String>(), r.errors)
    }

    @Test
    fun theRingGetsTheMomentOfTheStepNotTheDueTime() {
        val (_, history, w) = setup()
        w.submitVideo(1 * ms, au(500), 1_000, true) // due at 1 ms, written at 70 ms: 69 ms late
        w.step(70 * ms)
        assertEquals(70 * ms, history.nanosAt(history.newestKey()!!))
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
    fun theClockOfTheStreamNeverStandsStillAndAKeyPictureIsNotPaidForByTheAudio() {
        val (mux, history, w) = setup()
        var t = 0L
        w.submitVideo(5 * ms, au(150_000), 90_000, true)
        while (t < 400 * ms) {
            // audio every 23 ms
            if (t % (23 * ms) == 0L) w.submitAudio(t, au(300), 90_000 + t / ms * 90)
            w.step(t)
            t += ms
        }
        val r = everything(history)
        assertEquals(emptyList<String>(), r.errors)
        // the picture took the 130 ms it takes, and the audio was all there in the meantime
        assertEquals(1, r.pes[TsMuxer.VIDEO_PID]!!.size)
        assertEquals(150_000, r.pes[TsMuxer.VIDEO_PID]!![0].payload.size)
        assertTrue("audio frames ${r.pes[TsMuxer.AUDIO_PID]!!.size}", r.pes[TsMuxer.AUDIO_PID]!!.size >= 15)
        val pcrs = r.packets.filter { it.pcr != null }
        for (i in 1 until pcrs.size) assertTrue((pcrs[i].pcr!! - pcrs[i - 1].pcr!!) / 27_000.0 <= 20.1)
        assertEquals(0, mux.queuedVideoPackets)
    }

    @Test
    fun aStallOfTheWriterIsMadeUpWithoutBreakingTheStream() {
        val (_, history, w) = setup()
        w.step(100 * ms)
        // nothing for 20 s (a thread that was starved): the slots that passed are all emitted, in pieces
        w.step(20_100 * ms)
        assertEquals((slotOf(20_100 * ms) + 1) * 188, history.endPos)
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

    @Test
    fun aHigherRateOfTheStreamIsStillConstantWithTheClockEveryTwentyMilliseconds() {
        // the video setting can ask for more bits (tv_video): the stream then runs at 1.5 times the bitrate
        val mux = TsMuxer(muxRate = 15_000_000)
        val history = TsHistory()
        val w = TsWriter(mux, history) { t -> Math.floor(t / 1e9 * mux.slotsPerSecond).toLong() }
        w.submitVideo(10 * ms, au(120_000), 1_000, true)
        for (i in 0 until 40) w.submitAudio((20L + i * 23) * ms, au(300), 1_100L + i * 2_070)
        var t = 0L
        while (t <= 1_000 * ms) {
            w.step(t)
            t += ms
        }
        val r = everything(history)
        assertEquals(emptyList<String>(), r.errors)
        assertEquals((Math.floor(1_000 * ms / 1e9 * mux.slotsPerSecond).toLong() + 1) * 188, history.endPos)
        val pcrs = r.packets.filter { it.pcr != null }
        for (i in 1 until pcrs.size) assertTrue((pcrs[i].pcr!! - pcrs[i - 1].pcr!!) / 27_000.0 <= 20.1)
        assertEquals(1, r.pes[TsMuxer.VIDEO_PID]!!.size)
        assertEquals(120_000, r.pes[TsMuxer.VIDEO_PID]!![0].payload.size)
        assertEquals(40, r.pes[TsMuxer.AUDIO_PID]!!.size)
    }

    @Test
    fun aLowRateOfTheStreamIsStillConstantAndCarriesAPictureInTheTimeItNeeds() {
        // 3 Mbit/s: a 60 KB picture takes 160 ms of the stream, the audio goes by meanwhile and the clock is never more than 20 ms apart
        val mux = TsMuxer(muxRate = 3_000_000)
        val history = TsHistory()
        val w = TsWriter(mux, history) { t -> Math.floor(t / 1e9 * mux.slotsPerSecond).toLong() }
        w.submitVideo(10 * ms, au(60_000), 1_000, true)
        for (i in 0 until 40) w.submitAudio((20L + i * 23) * ms, au(300), 1_100L + i * 2_070)
        var t = 0L
        while (t <= 1_000 * ms) {
            w.step(t)
            t += ms
        }
        val r = everything(history)
        assertEquals(emptyList<String>(), r.errors)
        assertEquals((Math.floor(1_000 * ms / 1e9 * mux.slotsPerSecond).toLong() + 1) * 188, history.endPos)
        val pcrs = r.packets.filter { it.pcr != null }
        for (i in 1 until pcrs.size) assertTrue((pcrs[i].pcr!! - pcrs[i - 1].pcr!!) / 27_000.0 <= 20.1)
        assertEquals(60_000, r.pes[TsMuxer.VIDEO_PID]!![0].payload.size)
        assertEquals(40, r.pes[TsMuxer.AUDIO_PID]!!.size)
        assertEquals(0, mux.queuedVideoPackets)
    }

    // ---- video that comes after its time ---------------------------------------------------------

    /** A writer with the gate on, stepped every millisecond from [from] to [to] (ms), with the audio of 23 ms frames. */
    private fun run(w: TsWriter, from: Long, to: Long, audio: Boolean = true) {
        var t = from * ms
        while (t <= to * ms) {
            if (audio && t % (23 * ms) == 0L) w.submitAudio(t, au(300), 90_000 + t / ms * 90)
            w.step(t)
            t += ms
        }
    }

    private fun gated(): Triple<TsHistory, TsWriter, LateVideoGate> {
        val (_, history, w) = setup()
        val gate = LateVideoGate(9_000_000)
        w.gate = gate
        return Triple(history, w, gate)
    }

    @Test
    fun aPictureThatComesTooLateIsDroppedAndTheAudioAndTheClockGoOnAsIfItWasNeverExpected() {
        val (history, w, gate) = gated()
        run(w, 0, 899)
        // due at 110 ms, but the encoder gave it at 900 ms: 790 ms late
        w.submitVideo(110 * ms, au(20_000), 10_000, true)
        run(w, 900, 1_200)
        val r = everything(history)
        assertEquals(emptyList<String>(), r.errors)
        assertNull("no picture reached the stream", r.pes[TsMuxer.VIDEO_PID])
        // the audio came on every one of its slots: 23 ms apart over 1.2 s
        assertTrue("audio frames ${r.pes[TsMuxer.AUDIO_PID]!!.size}", r.pes[TsMuxer.AUDIO_PID]!!.size >= 50)
        val pcrs = r.packets.filter { it.pcr != null }
        for (i in 1 until pcrs.size) assertTrue((pcrs[i].pcr!! - pcrs[i - 1].pcr!!) / 27_000.0 <= 20.1)
        assertEquals((slotOf(1_200 * ms) + 1) * 188, history.endPos)
        assertEquals(1L, gate.dropped)
        assertTrue(gate.waitingForKey)
        assertEquals(1, w.takeStats().videoDropped)
    }

    @Test
    fun theStreamIsTheSameWhateverTheVideoDoesAudioPcrAndPaddingAreNotAffected() {
        // the same second with no picture at all, with a picture on time and with a picture a second late
        fun stream(video: (TsWriter) -> Unit): TsReader {
            val (history, w, _) = gated()
            video(w)
            run(w, 0, 1_000)
            return everything(history)
        }
        val none = stream { }
        val onTime = stream { it.submitVideo(100 * ms, au(30_000), 9_000, true) }
        val late = stream { it.submitVideo(0, au(30_000), 9_000, true) } // due at 0, written at its first step: on time
        fun audioOf(r: TsReader) = r.pes[TsMuxer.AUDIO_PID]!!.map { it.pts to it.payload.size }
        assertEquals(audioOf(none), audioOf(onTime))
        assertEquals(audioOf(none), audioOf(late))
        assertEquals(none.packets.count { it.pcr != null }, onTime.packets.count { it.pcr != null })
        assertNull(none.pes[TsMuxer.VIDEO_PID])
        assertEquals(1, onTime.pes[TsMuxer.VIDEO_PID]!!.size)
    }

    @Test
    fun theFollowingPicturesAreDroppedUntilAKeyPictureThatIsInTimeAndThenTheVideoGoesOn() {
        val (history, w, gate) = gated()
        // a key picture 700 ms late, then three pictures on time that refer to it, then a key picture on time, then two more
        run(w, 0, 800)
        w.submitVideo(100 * ms, au(20_000), 9_000, true) // 700 ms late
        w.submitVideo(800 * ms, au(2_000), 72_000, false)
        w.submitVideo(833 * ms, au(2_000), 75_000, false)
        w.submitVideo(866 * ms, au(2_000), 78_000, false)
        w.step(800 * ms)
        assertTrue(gate.waitingForKey)
        w.submitVideo(900 * ms, au(12_000), 81_000, true)
        w.submitVideo(933 * ms, au(1_500), 84_000, false)
        w.submitVideo(966 * ms, au(1_500), 87_000, false)
        run(w, 801, 1_300)
        val r = everything(history)
        assertEquals(emptyList<String>(), r.errors)
        val video = r.pes[TsMuxer.VIDEO_PID]!!
        assertEquals(listOf(81_000L, 84_000L, 87_000L), video.map { it.pts })
        assertEquals(false, gate.waitingForKey)
        assertEquals(4L, gate.dropped)
        assertEquals(1L, gate.droppedLate)
        assertEquals(3L, gate.droppedBroken)
        assertEquals(1L, gate.episodes)
    }

    @Test
    fun aPictureThatIsLateButStillInTimeIsSentAndCounted() {
        val (history, w, gate) = gated()
        run(w, 0, 300)
        w.submitVideo(100 * ms, au(8_000), 9_000, true) // 200 ms late, 8 KB takes 8 ms
        run(w, 301, 600)
        val r = everything(history)
        assertEquals(1, r.pes[TsMuxer.VIDEO_PID]!!.size)
        assertEquals(1L, gate.sentLate)
        assertEquals(0L, gate.dropped)
        assertEquals(1, w.takeStats().videoCount)
    }

    @Test
    fun theStepsThatCameLateAreCountedByTheSlotsTheyHadToMakeUp() {
        val (_, _, w) = setup()
        w.step(100 * ms)
        w.takeStats()
        w.step(110 * ms)
        w.step(900 * ms) // the writer slept 790 ms
        val s = w.takeStats()
        assertTrue("slots at once ${s.maxStepSlots}", s.maxStepSlots in 4_700L..4_760L)
        assertEquals(0L, w.takeStats().maxStepSlots)
    }
}
