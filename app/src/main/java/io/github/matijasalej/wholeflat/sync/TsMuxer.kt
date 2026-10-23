package io.github.matijasalej.wholeflat.sync

import java.io.ByteArrayOutputStream

/**
 * A small MPEG transport stream muxer for one program of H.264 video and AAC audio (ADTS), made for a live stream to the Philips TV:
 *
 *  - **PAT and PMT** are written at the start of every IDR access unit and at least every [psiEveryNanos] (100 ms), so that a player that
 *    joins (the TV opens several connections) finds them at once.
 *  - **PCR** is carried on [pcrPid] (the audio PID by default) at least every [pcrEveryNanos]: on the first packet of every audio PES when
 *    it is due, and in packets of their own ([idle]) when the audio stalls. The PCR value comes from [pcrOf], the clock of the stream.
 *  - One PES per video access unit (PTS only: there are no B-frames, so DTS equals PTS; the length is unbounded as the format allows for
 *    video) and one PES per ADTS frame. The first packet of an IDR access unit carries the random access indicator.
 *  - Continuity counters per PID; packets are padded with adaptation field stuffing, never with payload bytes.
 *
 * The muxer decides nothing about time: whoever calls it says when each access unit is written ([nowNanos]) and what its timestamps are
 * (90 kHz, 33 bits). It is not thread safe. Plain Kotlin.
 */
class TsMuxer(
    val videoPid: Int = VIDEO_PID,
    val audioPid: Int = AUDIO_PID,
    val pmtPid: Int = PMT_PID,
    val pcrPid: Int = AUDIO_PID,
    private val psiEveryNanos: Long = 100_000_000L,
    private val pcrEveryNanos: Long = 20_000_000L,
) {
    /** What one call made: whole 188 byte packets, and whether they begin with the PAT of an IDR access unit (a place where a player can start). */
    class Out(val bytes: ByteArray, val keyStart: Boolean)

    /** The PCR (27 MHz ticks) to write at [nowNanos] (System.nanoTime). Set it with [setClock] or replace it with the clock of the stream. */
    @Volatile
    var pcrOf: (Long) -> Long = { 0L }

    private val counters = IntArray(8192)
    private var lastPsiNanos = Long.MIN_VALUE
    private var lastPcrNanos = Long.MIN_VALUE
    private var lastPcr = Long.MIN_VALUE
    private val out = ByteArrayOutputStream(64 * 1024)
    private val pat = psiPacket(0, patSection())
    private val pmt = psiPacket(pmtPid, pmtSection())

    var videoAccessUnits = 0L
        private set
    var audioFrames = 0L
        private set
    var keyUnits = 0L
        private set
    var packets = 0L
        private set

    /** The PCR is [anchorPcr27] at [anchorNanos] and runs in real time from there. */
    fun setClock(anchorNanos: Long, anchorPcr27: Long) {
        pcrOf = { now -> anchorPcr27 + (now - anchorNanos) * 27L / 1000L }
    }

    /** A video access unit (Annex B, delimiter first: see [H264.accessUnit]) with the presentation time [pts90]. */
    fun video(au: ByteArray, pts90: Long, key: Boolean, nowNanos: Long): Out {
        out.reset()
        if (key || psiDue(nowNanos)) writePsi(nowNanos)
        val pes = pes(STREAM_ID_VIDEO, au, pts90, bounded = false)
        writePes(videoPid, pes, rai = key, pcrOnFirst = pcrPid == videoPid, nowNanos = nowNanos)
        videoAccessUnits++
        if (key) keyUnits++
        return Out(out.toByteArray(), key)
    }

    /** One ADTS frame with the presentation time [pts90]. */
    fun audio(adts: ByteArray, pts90: Long, nowNanos: Long): Out {
        out.reset()
        if (psiDue(nowNanos)) writePsi(nowNanos)
        val pes = pes(STREAM_ID_AUDIO, adts, pts90, bounded = true)
        writePes(audioPid, pes, rai = false, pcrOnFirst = pcrPid == audioPid, nowNanos = nowNanos)
        audioFrames++
        return Out(out.toByteArray(), false)
    }

    /** What is due with no data to carry it: the PAT and PMT, and a PCR packet. Null if nothing is due. */
    fun idle(nowNanos: Long): Out? {
        out.reset()
        if (psiDue(nowNanos)) writePsi(nowNanos)
        if (pcrDue(nowNanos)) writePcrOnly(nowNanos)
        return if (out.size() == 0) null else Out(out.toByteArray(), false)
    }

    private fun psiDue(now: Long): Boolean = lastPsiNanos == Long.MIN_VALUE || now - lastPsiNanos >= psiEveryNanos

    private fun pcrDue(now: Long): Boolean = lastPcrNanos == Long.MIN_VALUE || now - lastPcrNanos >= pcrEveryNanos

    private fun writePsi(now: Long) {
        writeCounted(pat, 0)
        writeCounted(pmt, pmtPid)
        lastPsiNanos = now
    }

    /** A PSI packet with the continuity counter of its PID put in, written. */
    private fun writeCounted(template: ByteArray, pid: Int) {
        val p = template.copyOf()
        p[3] = ((p[3].toInt() and 0xF0) or counters[pid]).toByte()
        counters[pid] = (counters[pid] + 1) and 0xF
        out.write(p)
        packets++
    }

    private fun nextPcr(now: Long): Long {
        var pcr = Math.floorMod(pcrOf(now), PCR_MODULUS)
        // the clock of the stream may step back a little (it is estimated); the PCR never does
        if (lastPcr != Long.MIN_VALUE && pcr < lastPcr && lastPcr - pcr < PCR_MODULUS / 2) pcr = lastPcr
        lastPcr = pcr
        lastPcrNanos = now
        return pcr
    }

    private fun writePcrOnly(now: Long) {
        val p = ByteArray(PACKET)
        p[0] = SYNC
        p[1] = (pcrPid shr 8).toByte()
        p[2] = pcrPid.toByte()
        // adaptation field only: the counter is the one of the last packet with payload on this PID
        p[3] = ((0x2 shl 4) or ((counters[pcrPid] - 1) and 0xF)).toByte()
        p[4] = 183.toByte()
        p[5] = 0x10 // PCR flag
        putPcr(p, 6, nextPcr(now))
        for (i in 12 until PACKET) p[i] = 0xFF.toByte()
        out.write(p)
        packets++
    }

    /** Cuts a PES packet into transport packets of [pid]. */
    private fun writePes(pid: Int, pes: ByteArray, rai: Boolean, pcrOnFirst: Boolean, nowNanos: Long) {
        var pos = 0
        var first = true
        while (pos < pes.size) {
            val remaining = pes.size - pos
            val wantPcr = first && pcrOnFirst && pcrDue(nowNanos)
            val wantRai = first && rai
            // content of the adaptation field: the flags byte, and the PCR
            val afContent = if (wantPcr || wantRai) 1 + (if (wantPcr) 6 else 0) else 0
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
            val afc = if (afTotal > 0) 0x3 else 0x1
            p[3] = ((afc shl 4) or counters[pid]).toByte()
            counters[pid] = (counters[pid] + 1) and 0xF
            var at = 4
            if (afTotal > 0) {
                p[at++] = (afTotal - 1).toByte()
                if (afTotal >= 2) {
                    var flags = 0
                    if (wantRai) flags = flags or 0x40
                    if (wantPcr) flags = flags or 0x10
                    p[at++] = flags.toByte()
                    if (wantPcr) {
                        putPcr(p, at, nextPcr(nowNanos))
                        at += 6
                    }
                    while (at < 4 + afTotal) p[at++] = 0xFF.toByte()
                }
            }
            System.arraycopy(pes, pos, p, at, take)
            out.write(p)
            packets++
            pos += take
            first = false
        }
    }

    private fun putPcr(p: ByteArray, off: Int, pcr27: Long) {
        val base = (pcr27 / 300L) and MASK33
        val ext = (pcr27 % 300L).toInt()
        p[off] = (base shr 25).toByte()
        p[off + 1] = (base shr 17).toByte()
        p[off + 2] = (base shr 9).toByte()
        p[off + 3] = (base shr 1).toByte()
        p[off + 4] = (((base and 1L).toInt() shl 7) or 0x7E or ((ext shr 8) and 1)).toByte()
        p[off + 5] = ext.toByte()
    }

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
        val s = ByteArray(12 + 2 * 5 + 4)
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
        s[at++] = 0x1B // AVC video
        s[at++] = (0xE0 or (videoPid shr 8)).toByte()
        s[at++] = videoPid.toByte()
        s[at++] = 0xF0.toByte()
        s[at++] = 0x00
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

    /** A packet with the section behind a pointer field, padded with 0xFF; the continuity counter is put in when it is written. */
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
        const val STREAM_ID_VIDEO = 0xE0
        const val STREAM_ID_AUDIO = 0xC0
        const val MASK33 = (1L shl 33) - 1

        /** The PCR counts 27 MHz ticks modulo 2^33 * 300. */
        private const val PCR_MODULUS = (MASK33 + 1) * 300L

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
