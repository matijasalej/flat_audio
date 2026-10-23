package io.github.matijasalej.wholeflat.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class TsMuxerTest {
    private val P = TsMuxer.PACKET

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun au(size: Int, seed: Int = 1): ByteArray {
        val b = ByteArray(size)
        for (i in b.indices) b[i] = ((i * 31 + seed) and 0xFF).toByte()
        return b
    }

    /** Everything the muxer emits for [slots] slots from [from], in one piece, and the key offsets it reported (in bytes). */
    private class Run(val bytes: ByteArray, val keyOffsets: List<Int>)

    private fun run(m: TsMuxer, slots: Int, from: Long = 0): Run {
        val e = m.emit(from + slots - 1)
        return Run(e.bytes, e.keyOffsets.toList())
    }

    private fun concat(vararg parts: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        for (p in parts) o.write(p)
        return o.toByteArray()
    }

    // ---- the tables --------------------------------------------------------------------------------------

    @Test
    fun theProgramTablesAreByteForByteWhatFfmpegWritesForTheSamePids() {
        // the PAT and PMT of the file that the TV played in the device test (video PID 0x100 carrying the PCR, audio 0x101, PMT 0x1000)
        val r = TsReader(run(TsMuxer(), 3).bytes)
        assertEquals(emptyList<String>(), r.errors)
        assertArrayEquals(hex("00b00d0001c100000001f0002ab104b2"), r.sections(0)[0])
        assertArrayEquals(hex("02b0170001c10000e100f0001be100f0000fe101f0002f44b99b"), r.sections(0x1000)[0])
    }

    @Test
    fun withThePcrOnTheAudioPidThePmtSaysSoAndItsCrcHolds() {
        val r = TsReader(run(TsMuxer(pcrPid = TsMuxer.AUDIO_PID), 3).bytes)
        val pmt = r.sections(0x1000)[0]
        assertEquals(0x02, pmt[0].toInt())
        assertEquals(0xE1, pmt[8].toInt() and 0xFF)
        assertEquals(0x01, pmt[9].toInt() and 0xFF)
        assertEquals(0x1B, pmt[12].toInt())
        assertEquals(0x0F, pmt[17].toInt())
        assertEquals(0, TsMuxer.crc32(pmt, pmt.size))
        assertEquals(0, TsMuxer.crc32(r.sections(0)[0], 16))
        // and the clock is on that PID
        assertTrue(r.packets.filter { it.pcr != null }.all { it.pid == TsMuxer.AUDIO_PID })
    }

    // ---- constant rate -----------------------------------------------------------------------------------

    @Test
    fun aNineMegabitStreamHasAPacketForEverySlotAndMostOfThemAreNullWhenNothingIsSent() {
        val m = TsMuxer()
        assertEquals(5984.04, m.slotsPerSecond, 0.01)
        assertEquals(4512.0, m.ticksPerSlot, 0.0)
        val r = run(m, 6_000) // about a second
        assertEquals(6_000 * P, r.bytes.size)
        val reader = TsReader(r.bytes)
        assertEquals(emptyList<String>(), reader.errors)
        assertEquals(6_000, reader.packets.size)
        val nulls = reader.packets.count { it.pid == TsMuxer.NULL_PID }
        // the clock every 20 ms (50) and the tables every 100 ms (10 of two)
        assertTrue("nulls $nulls", nulls in 5_900..5_940)
        assertEquals(nulls.toLong(), m.nullPackets)
        // a null packet is what the standard says: PID 0x1FFF, payload only, stuffing bytes
        val nullPacket = reader.packets.first { it.pid == TsMuxer.NULL_PID }
        assertEquals(1, nullPacket.afc)
        assertTrue(nullPacket.payload.all { it == 0xFF.toByte() })
    }

    @Test
    fun thePcrIsExactlyWhatThePositionInTheStreamSays() {
        val m = TsMuxer()
        m.pcrBase27 = 123_456_789L
        val reader = TsReader(run(m, 12_000).bytes)
        val pcrs = reader.packets.filter { it.pcr != null }
        assertTrue(pcrs.size >= 99)
        for (p in pcrs) assertEquals("packet ${p.index}", 123_456_789L + p.index * 4_512L, p.pcr)
        // never more than 20.1 ms between two (120 slots), far inside the 40 ms of the standard
        for (i in 1 until pcrs.size) assertTrue((pcrs[i].pcr!! - pcrs[i - 1].pcr!!) / 27_000.0 <= 20.1)
    }

    @Test
    fun theClockIsCountedFromThePcrBaseAndWrapsAt33Bits() {
        val m = TsMuxer()
        m.pcrBase27 = 0
        assertEquals(4_512L, m.pcrAt(1))
        assertEquals(4_512L * 1000, m.pcrAt(1000))
        m.pcrBase27 = (1L shl 33) * 300 - 4_512
        assertEquals(0L, m.pcrAt(1))
        assertEquals(4_512L, m.pcrAt(2))
    }

    @Test
    fun theTablesComeBackEveryHundredMillisecondsAndNeverLater() {
        val m = TsMuxer()
        val r = TsReader(run(m, 12_000).bytes)
        val pats = r.packets.filter { it.pid == 0 }.map { it.index }
        assertTrue("pats ${pats.size}", pats.size in 19..21)
        for (i in 1 until pats.size) {
            val ms = (pats[i] - pats[i - 1]) / m.slotsPerSecond * 1000
            assertTrue("tables $ms ms apart", ms in 99.0..101.5)
        }
        // each PAT is followed by its PMT, or by the clock first when that is due in the same slot
        for (i in pats) {
            val next = r.packets.drop(i + 1).first { it.pcr == null }
            assertEquals(0x1000, next.pid)
        }
    }

    // ---- what is sent ------------------------------------------------------------------------------------

    @Test
    fun aPesComesBackWholeWhateverItsSize() {
        // every size across the packet boundaries goes through the stuffing
        for (size in (0..420) + listOf(1_000, 4_095, 4_096, 60_000)) {
            val m = TsMuxer()
            val video = au(size + 1, 3)
            val audio = au(size + 1, 5)
            m.queueVideo(video, 123_456, false)
            m.queueAudio(audio, 123_456 + 2_090)
            val r = TsReader(run(m, 2_000).bytes)
            assertEquals("size $size: ${r.errors}", emptyList<String>(), r.errors)
            val v = r.pes[TsMuxer.VIDEO_PID]!![0]
            val a = r.pes[TsMuxer.AUDIO_PID]!![0]
            assertArrayEquals("video $size", video, v.payload)
            assertArrayEquals("audio $size", audio, a.payload)
            assertEquals(0xE0, v.streamId)
            assertEquals(0xC0, a.streamId)
            assertEquals(123_456L, v.pts)
            assertEquals(123_456L + 2_090, a.pts)
            assertEquals(0, v.declaredLength)
            assertEquals(3 + 5 + size + 1, a.declaredLength)
        }
    }

    @Test
    fun theTimestampsAreThirtyThreeBitsAndWrap() {
        val m = TsMuxer()
        m.queueAudio(au(10), (1L shl 33) + 12_345)
        m.queueVideo(au(10), (1L shl 33) - 1, false)
        val r = TsReader(run(m, 100).bytes)
        assertEquals(12_345L, r.pes[TsMuxer.AUDIO_PID]!![0].pts)
        assertEquals((1L shl 33) - 1, r.pes[TsMuxer.VIDEO_PID]!![0].pts)
    }

    @Test
    fun continuityCountersCountWhatWasSentOnEveryPid() {
        val m = TsMuxer()
        for (i in 0 until 150) {
            m.queueVideo(au(700 + i * 13), 90_000 + i * 3_000L, i % 30 == 0)
            m.queueAudio(au(250), 90_000 + i * 2_090L)
        }
        val r = TsReader(run(m, 8_000).bytes)
        assertEquals(emptyList<String>(), r.errors)
        val last = HashMap<Int, Int>()
        for (p in r.packets) {
            if (p.pid == TsMuxer.NULL_PID) continue
            val hasPayload = p.afc and 1 != 0
            val prev = last[p.pid]
            if (prev != null) {
                val expected = if (hasPayload) (prev + 1) and 0xF else prev
                assertEquals("pid ${p.pid} packet ${p.index}", expected, p.cc)
            }
            last[p.pid] = p.cc
        }
        assertTrue(last.keys.containsAll(listOf(0, 0x1000, TsMuxer.VIDEO_PID, TsMuxer.AUDIO_PID)))
    }

    @Test
    fun aPcrOnlyPacketRepeatsTheCounterOfThePacketBeforeItOnItsPid() {
        val m = TsMuxer()
        m.queueVideo(au(5_000), 90_000, false)
        val r = TsReader(run(m, 1_000).bytes)
        val onPid = r.packets.filter { it.pid == TsMuxer.VIDEO_PID }
        var checked = 0
        for (i in 1 until onPid.size) {
            if (onPid[i].pcr != null) {
                assertEquals(onPid[i - 1].cc, onPid[i].cc)
                checked++
            }
        }
        assertTrue(checked > 5)
    }

    // ---- the key pictures --------------------------------------------------------------------------------

    @Test
    fun aKeyPictureIsPrecededByThePatThePmtAndTheClockAndItsFirstPacketMarksRandomAccess() {
        val m = TsMuxer()
        run(m, 50) // the start of the stream
        m.queueVideo(au(5_000), 93_000, true)
        val r = run(m, 400, from = 50)
        assertEquals(1, r.keyOffsets.size)
        val reader = TsReader(r.bytes)
        val pat = r.keyOffsets[0] / P
        assertEquals(0, reader.packets[pat].pid)
        assertEquals(0x1000, reader.packets[pat + 1].pid)
        // the clock follows at once, then the picture (nothing else is queued here)
        assertEquals(TsMuxer.VIDEO_PID, reader.packets[pat + 2].pid)
        assertNotNull(reader.packets[pat + 2].pcr)
        val first = reader.packets[pat + 3]
        assertEquals(TsMuxer.VIDEO_PID, first.pid)
        assertTrue(first.pusi)
        assertTrue(first.rai)
        // only that packet has the indicator
        assertEquals(1, reader.packets.count { it.rai })
    }

    @Test
    fun aPictureThatIsNotAKeyOneIsNoPlaceToStart() {
        val m = TsMuxer()
        run(m, 50)
        m.queueVideo(au(5_000), 96_000, false)
        val r = run(m, 400, from = 50)
        assertTrue(r.keyOffsets.isEmpty())
        assertEquals(0, TsReader(r.bytes).packets.count { it.rai })
    }

    @Test
    fun everyKeyPictureOfALongerStreamIsFoundWherePatAndPmtAreRightBeforeIt() {
        val m = TsMuxer()
        val offsets = ArrayList<Int>()
        val all = ByteArrayOutputStream()
        var slot = 0L
        for (second in 0 until 4) {
            m.queueVideo(au(30_000, second), 90_000L + second * 90_000L, true)
            for (f in 1 until 30) m.queueVideo(au(800, f), 90_000L + second * 90_000L + f * 3_000L, false)
            for (a in 0 until 43) m.queueAudio(au(300, a), 90_000L + second * 90_000L + a * 2_090L)
            val e = m.emit(slot + 5_983)
            for (k in e.keyOffsets) offsets.add(all.size() + k)
            all.write(e.bytes)
            slot += 5_984
        }
        assertEquals(4, offsets.size)
        val r = TsReader(all.toByteArray())
        assertEquals(emptyList<String>(), r.errors)
        for (o in offsets) {
            val i = o / P
            assertEquals(0, r.packets[i].pid)
            assertEquals(0x1000, r.packets[i + 1].pid)
        }
        // every key picture has its random access mark, 4 of them
        assertEquals(4, r.packets.count { it.rai })
        // and the stream plays back whole
        assertEquals(4 * 30, r.pes[TsMuxer.VIDEO_PID]!!.size)
        assertEquals(4 * 43, r.pes[TsMuxer.AUDIO_PID]!!.size)
    }

    // ---- priority ----------------------------------------------------------------------------------------

    @Test
    fun aBlockOfAudioIsNeverHeldUpByAKeyPicture() {
        val m = TsMuxer()
        run(m, 10)
        m.queueVideo(au(150_000), 93_000, true) // about 800 packets, a hundred and thirty milliseconds
        run(m, 20, from = 10) // the picture is on its way
        assertTrue("video waiting ${m.queuedVideoPackets}", m.queuedVideoPackets > 500)
        m.queueAudio(au(400), 94_000)
        val r = TsReader(run(m, 6, from = 30).bytes)
        // the audio is in the very next slots, not behind the picture
        assertTrue(r.packets.any { it.pid == TsMuxer.AUDIO_PID && it.pusi })
    }

    @Test
    fun aKeyPictureTakesAboutAsLongAsItsBytesTakeAtTheMuxRate() {
        val m = TsMuxer()
        run(m, 10)
        m.queueVideo(au(150_000), 93_000, true)
        var slots = 0
        var from = 10L
        while (m.queuedVideoPackets > 0 || slots == 0) {
            m.emit(from)
            from++
            slots++
            assertTrue(slots < 3_000)
        }
        // about 150 KB at 9 Mbit/s: 133 ms
        val ms = slots / m.slotsPerSecond * 1000
        assertTrue("took $ms ms", ms in 120.0..150.0)
    }

    // ---- emitting ----------------------------------------------------------------------------------------

    @Test
    fun emittingGoesOnFromWhereItStoppedAndAnOldSlotGivesNothing() {
        val m = TsMuxer()
        val a = m.emit(4)
        assertEquals(0L, a.firstSlot)
        assertEquals(5, a.slots)
        assertEquals(0, m.emit(4).slots)
        assertEquals(0, m.emit(2).slots)
        val b = m.emit(9)
        assertEquals(5L, b.firstSlot)
        assertEquals(5, b.slots)
        assertEquals(10L, m.nextSlotToEmit)
    }

    @Test
    fun aSlotBeforeTheStartGivesNothing() {
        val m = TsMuxer()
        assertEquals(0, m.emit(-5).slots)
        assertEquals(0L, m.nextSlotToEmit)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aClockThatJumpedByMinutesIsRefused() {
        TsMuxer().emit(5_000_000)
    }

    @Test
    fun theStuffingNeverEatsTheStartOfAnAccessUnitDelimiter() {
        // a real access unit goes through whole: delimiter, parameter sets, a slice
        val sps = byteArrayOf(0x67, 0x64, 0x00, 0x29, 0xAC.toByte(), 0x13)
        val pps = byteArrayOf(0x68, 0xEE.toByte(), 0x3C, 0x80.toByte())
        val slice = ByteArray(3_000) { (it % 250 + 1).toByte() }.also { it[0] = 0x65 }
        val frame = concat(H264.START_CODE, sps, H264.START_CODE, pps, H264.START_CODE, slice)
        val unit = H264.accessUnit(frame, 0, frame.size, null)
        val m = TsMuxer()
        m.queueVideo(unit, 90_000, true)
        val r = TsReader(run(m, 500).bytes)
        assertEquals(emptyList<String>(), r.errors)
        val payload = r.pes[TsMuxer.VIDEO_PID]!![0].payload
        assertArrayEquals(unit, payload)
        assertEquals(listOf(9, 7, 8, 5), H264.types(payload))
    }

    @Test
    fun theCountersOfWhatWasMade() {
        val m = TsMuxer()
        m.queueVideo(au(500), 1, true)
        m.queueVideo(au(500), 2, false)
        m.queueAudio(au(100), 3)
        assertEquals(2L, m.videoAccessUnits)
        assertEquals(1L, m.keyUnits)
        assertEquals(1L, m.audioFrames)
        run(m, 100)
        assertEquals(100L, m.packets)
        assertFalse(m.queuedPackets > 0)
    }
}
