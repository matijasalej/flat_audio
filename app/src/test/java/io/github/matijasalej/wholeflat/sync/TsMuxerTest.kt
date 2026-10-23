package io.github.matijasalej.wholeflat.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class TsMuxerTest {
    private val ms = 1_000_000L

    /** A muxer whose PCR is the time itself (27 MHz). */
    private fun newMux(pcrPid: Int = TsMuxer.AUDIO_PID) = TsMuxer(pcrPid = pcrPid).also { it.setClock(0L, 0L) }

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun au(size: Int, seed: Int = 1): ByteArray {
        val b = ByteArray(size)
        for (i in b.indices) b[i] = ((i * 31 + seed) and 0xFF).toByte()
        return b
    }

    private fun collect(vararg parts: TsMuxer.Out?): ByteArray {
        val o = ByteArrayOutputStream()
        for (p in parts) if (p != null) o.write(p.bytes)
        return o.toByteArray()
    }

    @Test
    fun theProgramTablesAreByteForByteWhatFfmpegWritesForTheSamePids() {
        // the PAT and PMT of the file that the TV played in the device test (video PID 0x100 carrying the PCR, audio 0x101, PMT 0x1000)
        val r = TsReader(newMux(pcrPid = TsMuxer.VIDEO_PID).idle(0)!!.bytes)
        assertEquals(emptyList<String>(), r.errors)
        assertArrayEquals(hex("00b00d0001c100000001f0002ab104b2"), r.sections(0)[0])
        assertArrayEquals(hex("02b0170001c10000e100f0001be100f0000fe101f0002f44b99b"), r.sections(0x1000)[0])
    }

    @Test
    fun withThePcrOnTheAudioPidThePmtSaysSoAndItsCrcHolds() {
        val r = TsReader(newMux().idle(0)!!.bytes)
        val pmt = r.sections(0x1000)[0]
        assertEquals(0x02, pmt[0].toInt())
        // PCR PID 0x101, AVC on 0x100, AAC on 0x101
        assertEquals(0xE1, pmt[8].toInt() and 0xFF)
        assertEquals(0x01, pmt[9].toInt() and 0xFF)
        assertEquals(0x1B, pmt[12].toInt())
        assertEquals(0x0F, pmt[17].toInt())
        // a section with its own CRC at the end has the CRC 0
        assertEquals(0, TsMuxer.crc32(pmt, pmt.size))
        assertEquals(0, TsMuxer.crc32(r.sections(0)[0], 16))
    }

    @Test
    fun everyPacketIsWholeAndStartsWithTheSyncByte() {
        val m = newMux()
        val out = collect(
            m.video(au(40_000), 90_000, true, 0),
            m.audio(au(300), 90_000, 10 * ms),
            m.video(au(1_234), 93_000, false, 33 * ms),
            m.audio(au(1), 92_090, 40 * ms),
        )
        val r = TsReader(out)
        assertEquals(emptyList<String>(), r.errors)
        assertEquals(out.size / 188, r.packets.size)
        assertEquals(m.packets, r.packets.size.toLong())
    }

    @Test
    fun aKeyAccessUnitBeginsWithThePatAndThePmtAndItsFirstPacketMarksRandomAccess() {
        val m = newMux()
        m.video(au(500), 90_000, false, 0) // not a key unit: written without waiting for a key
        val key = m.video(au(5_000), 93_000, true, 33 * ms)
        assertTrue(key.keyStart)
        val r = TsReader(key.bytes)
        assertEquals(0, r.packets[0].pid)
        assertTrue(r.packets[0].pusi)
        assertEquals(0x1000, r.packets[1].pid)
        val firstVideo = r.packets.first { it.pid == TsMuxer.VIDEO_PID }
        assertTrue(firstVideo.pusi)
        assertTrue(firstVideo.rai)
        assertEquals(2, firstVideo.index)
        // only that packet has the indicator
        assertEquals(1, r.packets.count { it.rai })
        // a picture that is not a key one has no indicator and does not start a key place
        val p = m.video(au(500), 96_000, false, 66 * ms)
        assertFalse(p.keyStart)
        assertEquals(0, TsReader(p.bytes).packets.count { it.rai })
    }

    @Test
    fun theTablesComeBackEvery100MsAndNeverLater() {
        val m = newMux()
        val o = ByteArrayOutputStream()
        // 3 s of audio frames of 23 ms and video of 33 ms, no key pictures after the first
        var t = 0L
        var nextVideo = 0L
        var nextAudio = 0L
        val patTimes = ArrayList<Long>()
        var count = 0
        while (t < 3_000 * ms) {
            if (t >= nextAudio) {
                val out = m.audio(au(300), 90_000 + t / ms * 90, t)
                o.write(out.bytes)
                nextAudio += 23 * ms
                if (TsReader(out.bytes).packets.any { it.pid == 0 }) patTimes.add(t)
            }
            if (t >= nextVideo) {
                val out = m.video(au(900), 90_000 + t / ms * 90, count == 0, t)
                nextVideo += 33 * ms
                if (TsReader(out.bytes).packets.any { it.pid == 0 }) patTimes.add(t)
                o.write(out.bytes)
                count++
            }
            t += ms
        }
        val distinct = patTimes.distinct()
        assertTrue("tables ${distinct.size} times", distinct.size >= 24)
        for (i in 1 until distinct.size) {
            val gap = (distinct[i] - distinct[i - 1]) / ms
            assertTrue("tables $gap ms apart", gap in 100..125)
        }
        assertEquals(emptyList<String>(), TsReader(o.toByteArray()).errors)
    }

    @Test
    fun continuityCountersCountEveryPidSeparately() {
        val m = newMux()
        val o = ByteArrayOutputStream()
        var t = 0L
        for (i in 0 until 200) {
            o.write(m.video(au(700 + i * 13), 90_000 + i * 3_000L, i % 30 == 0, t).bytes)
            o.write(m.audio(au(250), 90_000 + i * 2_090L, t + 5 * ms).bytes)
            m.idle(t + 15 * ms)?.let { o.write(it.bytes) }
            t += 33 * ms
        }
        val r = TsReader(o.toByteArray())
        assertEquals(emptyList<String>(), r.errors)
        val last = HashMap<Int, Int>()
        for (p in r.packets) {
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
    fun aPesComesBackWholeWhateverItsSize() {
        // every size across the packet boundaries (184, 176 with a PCR, 183 and so on) goes through the stuffing
        for (size in (0..420) + listOf(1_000, 4_095, 4_096, 60_000)) {
            val m = newMux()
            val video = au(size + 1, 3)
            val audio = au(size + 1, 5)
            val out = collect(m.video(video, 123_456, false, 0), m.audio(audio, 123_456 + 2_090, 1 * ms))
            val r = TsReader(out)
            assertEquals("size $size: ${r.errors}", emptyList<String>(), r.errors)
            val v = r.pes[TsMuxer.VIDEO_PID]!![0]
            val a = r.pes[TsMuxer.AUDIO_PID]!![0]
            assertArrayEquals("video $size", video, v.payload)
            assertArrayEquals("audio $size", audio, a.payload)
            assertEquals(0xE0, v.streamId)
            assertEquals(0xC0, a.streamId)
            assertEquals(123_456L, v.pts)
            assertEquals(123_456L + 2_090, a.pts)
            // video has no length (it may be longer than 65535), audio says its length
            assertEquals(0, v.declaredLength)
            assertEquals(3 + 5 + size + 1, a.declaredLength)
        }
    }

    @Test
    fun theTimestampsAreThirtyThreeBitsAndWrap() {
        val m = newMux()
        val pts = (1L shl 33) + 12_345
        val r = TsReader(m.audio(au(10), pts, 0).bytes)
        assertEquals(12_345L, r.pes[TsMuxer.AUDIO_PID]!![0].pts)
        val r2 = TsReader(newMux().audio(au(10), (1L shl 33) - 1, 0).bytes)
        assertEquals((1L shl 33) - 1, r2.pes[TsMuxer.AUDIO_PID]!![0].pts)
    }

    @Test
    fun thePcrIsOnTheAudioPidAtLeastEvery40MsAndItRunsWithTheClock() {
        val m = newMux()
        val o = ByteArrayOutputStream()
        var t = 1_000 * ms
        var nextAudio = t
        var nextVideo = t
        // 5 s: audio frames every 23.2 ms, video every 33.3 ms, and idle() looked at every 5 ms
        val end = t + 5_000 * ms
        var n = 0
        while (t < end) {
            if (t >= nextAudio) {
                o.write(m.audio(au(300), 90_000 + n * 2_090L, t).bytes)
                nextAudio += 23_220_000L
                n++
            }
            if (t >= nextVideo) {
                o.write(m.video(au(2_000), 90_000 + t / ms * 90, false, t).bytes)
                nextVideo += 33_333_333L
            }
            m.idle(t)?.let { o.write(it.bytes) }
            t += 5 * ms
        }
        val r = TsReader(o.toByteArray())
        assertEquals(emptyList<String>(), r.errors)
        val pcrs = r.packets.filter { it.pcr != null }
        assertTrue(pcrs.all { it.pid == TsMuxer.AUDIO_PID })
        assertTrue("pcr packets: ${pcrs.size}", pcrs.size > 200)
        for (i in 1 until pcrs.size) {
            val gap = (pcrs[i].pcr!! - pcrs[i - 1].pcr!!) / 27_000.0
            assertTrue("pcr gap $gap ms", gap > 0 && gap <= 40.0)
        }
        // the clock was the time: 27 MHz ticks per 37 ns
        val span = (pcrs.last().pcr!! - pcrs.first().pcr!!) / 27_000.0
        assertTrue("span $span ms", span > 4_900 && span < 5_000)
    }

    @Test
    fun whenTheAudioStallsIdleKeepsThePcrAndTheTablesGoing() {
        val m = newMux()
        m.audio(au(300), 90_000, 0)
        assertNull(m.idle(5 * ms)) // not due yet
        val first = m.idle(25 * ms)
        assertNotNull(first)
        val r = TsReader(first!!.bytes)
        val pcrPackets = r.packets.filter { it.pcr != null }
        assertEquals(1, pcrPackets.size)
        assertEquals(TsMuxer.AUDIO_PID, pcrPackets[0].pid)
        assertEquals(2, pcrPackets[0].afc)
        assertEquals(183, pcrPackets[0].afLength)
        assertEquals(25 * 27_000L, pcrPackets[0].pcr)
        // after 100 ms the tables come too
        val later = TsReader(m.idle(130 * ms)!!.bytes)
        assertEquals(listOf(0, 0x1000, TsMuxer.AUDIO_PID), later.packets.map { it.pid })
    }

    @Test
    fun aPcrOnlyPacketRepeatsTheCounterOfThePacketBeforeIt() {
        val m = newMux()
        val a = TsReader(m.audio(au(100), 90_000, 0).bytes).packets.last { it.pid == TsMuxer.AUDIO_PID }
        val p = TsReader(m.idle(30 * ms)!!.bytes).packets.first { it.pid == TsMuxer.AUDIO_PID }
        assertEquals(a.cc, p.cc)
        // the next packet with payload goes on from there
        val b = TsReader(m.audio(au(100), 92_090, 31 * ms).bytes).packets.first { it.pid == TsMuxer.AUDIO_PID }
        assertEquals((a.cc + 1) and 0xF, b.cc)
    }

    @Test
    fun thePcrNeverGoesBackWhenTheClockDoes() {
        val m = TsMuxer()
        var step = 0
        m.pcrOf = { _ -> (1_000_000L + if (step++ % 2 == 0) 0 else -5_000) }
        val o = ByteArrayOutputStream()
        for (i in 0 until 10) o.write(m.audio(au(100), 90_000L + i, i * 30 * ms).bytes)
        val pcrs = TsReader(o.toByteArray()).packets.mapNotNull { it.pcr }
        assertEquals(10, pcrs.size)
        for (i in 1 until pcrs.size) assertTrue(pcrs[i] >= pcrs[i - 1])
    }

    @Test
    fun theStuffingNeverEatsTheStartOfAnAccessUnitDelimiter() {
        // a real access unit goes through whole: delimiter, parameter sets, a slice
        val sps = byteArrayOf(0x67, 0x64, 0x00, 0x29, 0xAC.toByte(), 0x13)
        val pps = byteArrayOf(0x68, 0xEE.toByte(), 0x3C, 0x80.toByte())
        val slice = ByteArray(3_000) { (it % 250 + 1).toByte() }.also { it[0] = 0x65 }
        val frame = H264.START_CODE + sps + H264.START_CODE + pps + H264.START_CODE + slice
        val unit = H264.accessUnit(frame, 0, frame.size, null)
        val m = newMux()
        val r = TsReader(m.video(unit, 90_000, true, 0).bytes)
        assertEquals(emptyList<String>(), r.errors)
        val payload = r.pes[TsMuxer.VIDEO_PID]!![0].payload
        assertArrayEquals(unit, payload)
        assertEquals(listOf(9, 7, 8, 5), H264.types(payload))
    }
}
