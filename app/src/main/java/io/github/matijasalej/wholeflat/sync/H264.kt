package io.github.matijasalej.wholeflat.sync

import java.io.ByteArrayOutputStream

/**
 * H.264 in Annex B form (start codes), as the hardware encoder of MediaCodec hands it out, and what a transport stream wants of it:
 * every access unit starts with an access unit delimiter, and every IDR picture is preceded by the sequence and the picture
 * parameter sets, so that a decoder that joins at any IDR can start. Plain Kotlin.
 */
object H264 {
    const val NAL_SLICE = 1
    const val NAL_IDR = 5
    const val NAL_SEI = 6
    const val NAL_SPS = 7
    const val NAL_PPS = 8
    const val NAL_AUD = 9

    /** The start code written before every NAL unit of an access unit. */
    val START_CODE = byteArrayOf(0, 0, 0, 1)

    /** An access unit delimiter that allows every slice type (`primary_pic_type` 7). */
    val AUD = byteArrayOf(0, 0, 0, 1, 0x09, 0xF0.toByte())

    /** A NAL unit of a buffer: its type and the range of its bytes (the header byte included, the start code and trailing zeros not). */
    class Nal(val type: Int, val start: Int, val end: Int) {
        val length: Int get() = end - start
    }

    /** The NAL units of the Annex B bytes `data[off, off + len)`, in order. */
    fun nals(data: ByteArray, off: Int = 0, len: Int = data.size - off): List<Nal> {
        val end = off + len
        val result = ArrayList<Nal>()
        var i = off
        var start = -1
        while (i + 2 < end) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                if (start >= 0) nal(data, start, i)?.let { result.add(it) }
                start = i + 3
                i += 3
            } else {
                i++
            }
        }
        if (start in 0 until end) nal(data, start, end)?.let { result.add(it) }
        return result
    }

    private fun nal(data: ByteArray, start: Int, endExclusive: Int): Nal? {
        // the zero byte that belongs to the next start code (or trailing zeros) is not part of the unit
        var e = endExclusive
        while (e > start && data[e - 1] == 0.toByte()) e--
        if (e <= start) return null
        return Nal(data[start].toInt() and 0x1F, start, e)
    }

    /** Whether the access unit holds an IDR picture. */
    fun isKey(data: ByteArray, off: Int = 0, len: Int = data.size - off): Boolean = nals(data, off, len).any { it.type == NAL_IDR }

    /** The sequence and picture parameter sets of [config] (what the encoder says in its codec-config buffer), with start codes; empty if there are none. */
    fun parameterSets(config: ByteArray, off: Int = 0, len: Int = config.size - off): ByteArray {
        val out = ByteArrayOutputStream()
        for (n in nals(config, off, len)) {
            if (n.type == NAL_SPS) {
                out.write(START_CODE)
                out.write(config, n.start, n.length)
            }
        }
        for (n in nals(config, off, len)) {
            if (n.type == NAL_PPS) {
                out.write(START_CODE)
                out.write(config, n.start, n.length)
            }
        }
        return out.toByteArray()
    }

    /**
     * The access unit for a transport stream from one buffer of the encoder: an access unit delimiter first, then (for an IDR picture)
     * the parameter sets (the ones in the buffer, else [parameterSets] from the codec-config buffer), then the other NAL units as
     * they came. Start codes are four bytes throughout. A delimiter that the encoder made itself is dropped, there is one only.
     * [key]: the encoder says it is a key picture even if it has no IDR slice, which gets the parameter sets all the same.
     */
    fun accessUnit(frame: ByteArray, off: Int, len: Int, parameterSets: ByteArray?, key: Boolean = false): ByteArray {
        val list = nals(frame, off, len)
        val idr = key || list.any { it.type == NAL_IDR }
        val haveSps = list.any { it.type == NAL_SPS }
        val havePps = list.any { it.type == NAL_PPS }
        val out = ByteArrayOutputStream(len + 64)
        out.write(AUD)
        if (idr) {
            if (haveSps && havePps) {
                for (n in list) if (n.type == NAL_SPS) writeNal(out, frame, n)
                for (n in list) if (n.type == NAL_PPS) writeNal(out, frame, n)
            } else if (parameterSets != null && parameterSets.isNotEmpty()) {
                out.write(parameterSets)
            }
        }
        for (n in list) {
            if (n.type == NAL_AUD) continue
            if (idr && (n.type == NAL_SPS || n.type == NAL_PPS) && haveSps && havePps) continue // written above
            writeNal(out, frame, n)
        }
        return out.toByteArray()
    }

    private fun writeNal(out: ByteArrayOutputStream, data: ByteArray, n: Nal) {
        out.write(START_CODE)
        out.write(data, n.start, n.length)
    }

    /** The types of the NAL units of an access unit, for the logs and the tests. */
    fun types(data: ByteArray): List<Int> = nals(data).map { it.type }
}
