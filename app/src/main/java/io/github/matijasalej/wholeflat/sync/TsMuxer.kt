package io.github.matijasalej.wholeflat.sync

import java.util.ArrayDeque

/**
 * A small MPEG transport stream muxer for one program of H.264 video and AAC audio (ADTS), made for a live stream to the Philips TV, and
 * **constant in bit rate**: the output is a train of 188 byte packets at a fixed rate ([muxRate], 9 Mbit/s: one packet every 167 us), and
 * a slot that has nothing to send carries a null packet (PID 0x1FFF). In the device tests the TV held its latency to 1 ms through every
 * song change on such a stream, and with the stream as it came out of the encoder (bursts at the key pictures, little between them) its
 * latency wandered between 0.2 and 1.2 s from one song to the next.
 *
 * Time is the number of the slot. The caller says how many slots are due ([emit]); the PCR of a slot is [pcrBase27] plus the slot
 * number times the 4512 ticks of 27 MHz that one packet takes at 9 Mbit/s, so the PCR is exactly what the position in the stream says
 * (what a constant rate stream is). What goes into the slots:
 *
 *  1. a **PCR** packet on [pcrPid] every 20 ms;
 *  2. the **PAT and PMT**, every 100 ms and before every IDR picture (followed there by a PCR packet, so that a player that joins at that
 *     point has the clock before its first picture);
 *  3. **audio** packets (they go before video, a block of audio is never held up by a key picture that takes 130 ms to send);
 *  4. **video** packets, in the order the pictures came;
 *  5. else a null packet.
 *
 * One PES per video access unit (PTS only: there are no B-frames, so DTS equals PTS; the length is unbounded as the format allows for
 * video) and one PES per ADTS frame. The first packet of an IDR access unit carries the random access indicator. Continuity counters
 * per PID are put in as a packet leaves, so they count what was sent. Packets are padded with adaptation field stuffing, never with
 * payload bytes. Not thread safe. Plain Kotlin.
 */
class TsMuxer(
    val videoPid: Int = VIDEO_PID,
    val audioPid: Int = AUDIO_PID,
    val pmtPid: Int = PMT_PID,
    val pcrPid: Int = VIDEO_PID,
    val muxRate: Int = MUX_RATE,
    pcrEveryMs: Int = 20,
    psiEveryMs: Int = 100,
    /**
     * False: a stream of the audio alone (the screen of the TV is off): the PMT lists the audio only and the caller puts the PCR on the audio
     * PID (`pcrPid = AUDIO_PID`). There are no key pictures then, so every PAT of the periodic tables (every 100 ms) is a place to start at.
     */
    val hasVideo: Boolean = true,
) {
    /**
     * The packets of the slots that were emitted: whole 188 byte packets, one for each slot from [firstSlot] on, and the byte offsets
     * in [bytes] of the PAT that begins a place where a player can start (before an IDR picture).
     */
    class Emitted(val bytes: ByteArray, val keyOffsets: IntArray, val firstSlot: Long) {
        val slots: Int get() = bytes.size / PACKET
    }

    private class Packet(val bytes: ByteArray, val pid: Int, val keyFirst: Boolean)

    /** Slots in a second at this mux rate. */
    val slotsPerSecond: Double = muxRate / (PACKET * 8.0)

    /** 27 MHz ticks that one slot lasts: 4512 at 9 Mbit/s. */
    val ticksPerSlot: Double = PACKET * 8.0 * 27_000_000.0 / muxRate

    /** The PCR (27 MHz) of slot 0. */
    @Volatile
    var pcrBase27 = 0L

    private val pcrEverySlots = maxOf(1L, Math.round(pcrEveryMs / 1000.0 * slotsPerSecond))
    private val psiEverySlots = maxOf(1L, Math.round(psiEveryMs / 1000.0 * slotsPerSecond))
    private val counters = IntArray(8192)
    private val audioQueue = ArrayDeque<Packet>()
    private val videoQueue = ArrayDeque<Packet>()
    private val pat = psiPacket(0, patSection())
    private val pmt = psiPacket(pmtPid, pmtSection())

    private var nextSlot = 0L
    private var nextPcrSlot = 0L
    private var nextTablesSlot = 0L
    private var tablesStage = 0 // 1: the PAT is next, 2: the PMT is next
    private var tablesForKey = false
    private var headPrefaced = false

    var videoAccessUnits = 0L
        private set
    var audioFrames = 0L
        private set
    var keyUnits = 0L
        private set
    var packets = 0L
        private set
    var nullPackets = 0L
        private set

    /** The number of the next slot that [emit] will fill. */
    val nextSlotToEmit: Long get() = nextSlot

    /** Packets waiting to go out, and how many of them are video (the queue of a key picture drains over a hundred slots or more). */
    val queuedPackets: Int get() = audioQueue.size + videoQueue.size
    val queuedVideoPackets: Int get() = videoQueue.size

    /** The PCR (27 MHz) of slot [slot]. */
    fun pcrAt(slot: Long): Long = Math.floorMod(pcrBase27 + Math.round(slot * ticksPerSlot), PCR_MODULUS)

    // ---- what goes in --------------------------------------------------------------------------------

    /** A video access unit (Annex B, delimiter first: see [H264.accessUnit]) with the presentation time [pts90]. */
    fun queueVideo(au: ByteArray, pts90: Long, key: Boolean) {
        packetize(videoPid, pes(STREAM_ID_VIDEO, au, pts90, bounded = false), rai = key, key = key, into = videoQueue)
        videoAccessUnits++
        if (key) keyUnits++
    }

    /** One ADTS frame with the presentation time [pts90]. */
    fun queueAudio(adts: ByteArray, pts90: Long) {
        packetize(audioPid, pes(STREAM_ID_AUDIO, adts, pts90, bounded = true), rai = false, key = false, into = audioQueue)
        audioFrames++
    }

    /** Cuts a PES packet into transport packets of [pid]; the continuity counter is put in when a packet leaves. */
    private fun packetize(pid: Int, pes: ByteArray, rai: Boolean, key: Boolean, into: ArrayDeque<Packet>) {
        var pos = 0
        var first = true
        while (pos < pes.size) {
            val remaining = pes.size - pos
            val wantRai = first && rai
            val afContent = if (wantRai) 1 else 0 // the flags byte
            val capacity = if (afContent == 0) 184 else 184 - 1 - afContent
            val take: Int
            val afTotal: Int // bytes of the adaptation field with its length byte; 0 if none
            if (remaining >= capacity) {
                take = capacity
                afTotal = if (afContent == 0) 0 else 1 + afContent
            } else {
                take = remaining
                afTotal = 184 - remaining // stuffs the packet to its size; at least 1
            }
            val p = ByteArray(PACKET)
            p[0] = SYNC
            p[1] = ((if (first) 0x40 else 0) or (pid shr 8)).toByte()
            p[2] = pid.toByte()
            p[3] = ((if (afTotal > 0) 0x3 else 0x1) shl 4).toByte()
            var at = 4
            if (afTotal > 0) {
                p[at++] = (afTotal - 1).toByte()
                if (afTotal >= 2) {
                    p[at++] = (if (wantRai) 0x40 else 0).toByte()
                    while (at < 4 + afTotal) p[at++] = 0xFF.toByte()
                }
            }
            System.arraycopy(pes, pos, p, at, take)
            into.addLast(Packet(p, pid, keyFirst = first && key))
            pos += take
            first = false
        }
    }

    // ---- what goes out -------------------------------------------------------------------------------

    /**
     * Emits a packet for every slot from the next one up to and including [slot] (nothing if [slot] is not past the last emitted one, or is
     * before the start). The slots are the rate of the stream: each call gives as many packets as the time that passed says.
     */
    fun emit(slot: Long): Emitted {
        if (slot < nextSlot) return Emitted(ByteArray(0), IntArray(0), nextSlot)
        val first = nextSlot
        val count = (slot - first + 1)
        require(count <= MAX_SLOTS_PER_CALL) { "$count slots in one call" }
        val out = ByteArray(count.toInt() * PACKET)
        var keyOffsets = IntArray(4)
        var keys = 0
        for (i in 0 until count.toInt()) {
            val n = first + i
            if (fill(n, out, i * PACKET)) {
                if (keys == keyOffsets.size) keyOffsets = keyOffsets.copyOf(keys * 2)
                keyOffsets[keys++] = i * PACKET
            }
        }
        nextSlot = slot + 1
        packets += count
        return Emitted(out, keyOffsets.copyOf(keys), first)
    }

    /** Writes the packet of slot [n] at [off] of [dest]; true if it is the PAT of a place to start at. */
    private fun fill(n: Long, dest: ByteArray, off: Int): Boolean {
        // 1. the clock
        if (n >= nextPcrSlot) {
            nextPcrSlot = n + pcrEverySlots
            writePcr(n, dest, off)
            return false
        }
        // 2. the tables: PAT, PMT (then at a key picture the clock again, at once)
        if (tablesStage == 2) {
            tablesStage = 0
            writeTable(pmt, pmtPid, dest, off)
            if (tablesForKey) nextPcrSlot = n + 1
            return false
        }
        if (n >= nextTablesSlot) {
            tablesForKey = !hasVideo
            nextTablesSlot = n + psiEverySlots
            tablesStage = 2
            writeTable(pat, 0, dest, off)
            return !hasVideo
        }
        // 3. audio goes before video
        val audio = audioQueue.pollFirst()
        if (audio != null) {
            writePayload(audio, dest, off)
            return false
        }
        // 4. video; a key picture is preceded by the tables
        val head = videoQueue.peekFirst()
        if (head != null) {
            if (head.keyFirst && !headPrefaced) {
                headPrefaced = true
                tablesForKey = true
                tablesStage = 2
                nextTablesSlot = n + psiEverySlots
                writeTable(pat, 0, dest, off)
                return true
            }
            headPrefaced = false
            videoQueue.pollFirst()
            writePayload(head, dest, off)
            return false
        }
        // 5. nothing to send
        System.arraycopy(NULL_PACKET, 0, dest, off, PACKET)
        nullPackets++
        return false
    }

    private fun writePayload(p: Packet, dest: ByteArray, off: Int) {
        System.arraycopy(p.bytes, 0, dest, off, PACKET)
        dest[off + 3] = ((dest[off + 3].toInt() and 0xF0) or counters[p.pid]).toByte()
        counters[p.pid] = (counters[p.pid] + 1) and 0xF
    }

    private fun writeTable(template: ByteArray, pid: Int, dest: ByteArray, off: Int) {
        System.arraycopy(template, 0, dest, off, PACKET)
        dest[off + 3] = ((dest[off + 3].toInt() and 0xF0) or counters[pid]).toByte()
        counters[pid] = (counters[pid] + 1) and 0xF
    }

    /** A packet of the PCR PID with an adaptation field only; its counter is the one of the last packet with payload on that PID (it does not count). */
    private fun writePcr(slot: Long, dest: ByteArray, off: Int) {
        java.util.Arrays.fill(dest, off, off + PACKET, 0xFF.toByte())
        dest[off] = SYNC
        dest[off + 1] = (pcrPid shr 8).toByte()
        dest[off + 2] = pcrPid.toByte()
        dest[off + 3] = ((0x2 shl 4) or ((counters[pcrPid] - 1) and 0xF)).toByte()
        dest[off + 4] = 183.toByte()
        dest[off + 5] = 0x10 // PCR flag
        val pcr = pcrAt(slot)
        val base = (pcr / 300L) and MASK33
        val ext = (pcr % 300L).toInt()
        dest[off + 6] = (base shr 25).toByte()
        dest[off + 7] = (base shr 17).toByte()
        dest[off + 8] = (base shr 9).toByte()
        dest[off + 9] = (base shr 1).toByte()
        dest[off + 10] = (((base and 1L).toInt() shl 7) or 0x7E or ((ext shr 8) and 1)).toByte()
        dest[off + 11] = ext.toByte()
    }

    // ---- building blocks -----------------------------------------------------------------------------

    private fun pes(streamId: Int, payload: ByteArray, pts90: Long, bounded: Boolean): ByteArray {
        val optional = 3 + 5 // two flag bytes, the header length byte, the PTS
        val b = ByteArray(6 + optional + payload.size)
        b[0] = 0
        b[1] = 0
        b[2] = 1
        b[3] = streamId.toByte()
        val length = if (bounded) optional + payload.size else 0
        require(!bounded || length <= 0xFFFF) { "a PES of $length bytes does not fit" }
        b[4] = (length shr 8).toByte()
        b[5] = length.toByte()
        b[6] = 0x84.toByte() // marker, not scrambled, data alignment indicator
        b[7] = 0x80.toByte() // PTS only
        b[8] = 5
        putTimestamp(b, 9, 0x2, pts90)
        System.arraycopy(payload, 0, b, 6 + optional, payload.size)
        return b
    }

    private fun putTimestamp(b: ByteArray, off: Int, prefix: Int, ts: Long) {
        val t = ts and MASK33
        b[off] = ((prefix shl 4) or (((t shr 30).toInt() and 7) shl 1) or 1).toByte()
        b[off + 1] = (t shr 22).toByte()
        b[off + 2] = ((((t shr 15).toInt() and 0x7F) shl 1) or 1).toByte()
        b[off + 3] = (t shr 7).toByte()
        b[off + 4] = (((t.toInt() and 0x7F) shl 1) or 1).toByte()
    }

    private fun patSection(): ByteArray {
        val s = ByteArray(16)
        s[0] = 0x00 // table id: PAT
        s[1] = 0xB0.toByte() // section syntax, reserved
        s[2] = 13 // section length: what follows, with the CRC
        s[3] = 0x00
        s[4] = 0x01 // transport stream id
        s[5] = 0xC1.toByte() // version 0, current
        s[6] = 0x00
        s[7] = 0x00 // section numbers
        s[8] = 0x00
        s[9] = 0x01 // program number 1
        s[10] = (0xE0 or (pmtPid shr 8)).toByte()
        s[11] = pmtPid.toByte()
        putCrc(s, 12)
        return s
    }

    private fun pmtSection(): ByteArray {
        val s = ByteArray(12 + (if (hasVideo) 2 else 1) * 5 + 4)
        s[0] = 0x02 // table id: PMT
        s[1] = 0xB0.toByte()
        s[2] = (s.size - 3).toByte()
        s[3] = 0x00
        s[4] = 0x01 // program number
        s[5] = 0xC1.toByte()
        s[6] = 0x00
        s[7] = 0x00
        s[8] = (0xE0 or (pcrPid shr 8)).toByte()
        s[9] = pcrPid.toByte()
        s[10] = 0xF0.toByte() // program info length 0
        s[11] = 0x00
        var at = 12
        if (hasVideo) {
            s[at++] = 0x1B // AVC video
            s[at++] = (0xE0 or (videoPid shr 8)).toByte()
            s[at++] = videoPid.toByte()
            s[at++] = 0xF0.toByte()
            s[at++] = 0x00
        }
        s[at++] = 0x0F // AAC with ADTS
        s[at++] = (0xE0 or (audioPid shr 8)).toByte()
        s[at++] = audioPid.toByte()
        s[at++] = 0xF0.toByte()
        s[at++] = 0x00
        putCrc(s, at)
        return s
    }

    private fun putCrc(section: ByteArray, at: Int) {
        val crc = crc32(section, at)
        section[at] = (crc ushr 24).toByte()
        section[at + 1] = (crc ushr 16).toByte()
        section[at + 2] = (crc ushr 8).toByte()
        section[at + 3] = crc.toByte()
    }

    /** A packet with the section behind a pointer field, padded with 0xFF; the continuity counter is put in when it leaves. */
    private fun psiPacket(pid: Int, section: ByteArray): ByteArray {
        val p = ByteArray(PACKET) { 0xFF.toByte() }
        p[0] = SYNC
        p[1] = (0x40 or (pid shr 8)).toByte()
        p[2] = pid.toByte()
        p[3] = 0x10 // payload only
        p[4] = 0 // pointer field
        System.arraycopy(section, 0, p, 5, section.size)
        return p
    }

    companion object {
        const val PACKET = 188
        const val SYNC: Byte = 0x47
        const val VIDEO_PID = 0x100
        const val AUDIO_PID = 0x101
        const val PMT_PID = 0x1000
        const val NULL_PID = 0x1FFF
        const val STREAM_ID_VIDEO = 0xE0
        const val STREAM_ID_AUDIO = 0xC0
        const val MASK33 = (1L shl 33) - 1

        /** The rate that the TV was tested with: 9 Mbit/s, a packet every 167 microseconds. */
        const val MUX_RATE = 9_000_000

        /** The PCR counts 27 MHz ticks modulo 2^33 * 300. */
        private const val PCR_MODULUS = (MASK33 + 1) * 300L

        /** A call asks for no more than this many slots (about 6 s at 9 Mbit/s): a clock that jumped is a bug, not a request. */
        private const val MAX_SLOTS_PER_CALL = 40_000L

        private val NULL_PACKET = ByteArray(PACKET) { 0xFF.toByte() }.also {
            it[0] = SYNC
            it[1] = (NULL_PID shr 8).toByte()
            it[2] = NULL_PID.toByte()
            it[3] = 0x10
        }

        /** The CRC-32 of MPEG sections: polynomial 0x04C11DB7, start value all ones, most significant bit first, no final inversion. */
        fun crc32(data: ByteArray, length: Int): Int {
            var crc = -1
            for (i in 0 until length) {
                crc = crc xor ((data[i].toInt() and 0xFF) shl 24)
                for (b in 0 until 8) crc = if (crc < 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1
            }
            return crc
        }
    }
}
