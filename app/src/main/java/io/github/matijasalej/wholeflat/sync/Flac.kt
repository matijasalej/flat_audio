package io.github.matijasalej.wholeflat.sync

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A minimal FLAC encoder: every subframe is VERBATIM, so there is no compression and no dependency,
 * just a valid stream. STREAMINFO says "total samples unknown" (0), blocks are a fixed 4096 frames,
 * each frame header carries the frame number (UTF-8 coded) and a CRC-8, each frame ends with a
 * CRC-16.
 */
object Flac {
    const val BLOCK = 4096

    private val crc8Table = IntArray(256) { i ->
        var c = i
        repeat(8) { c = if (c and 0x80 != 0) ((c shl 1) xor 0x07) and 0xFF else (c shl 1) and 0xFF }
        c
    }

    private val crc16Table = IntArray(256) { i ->
        var c = i shl 8
        repeat(8) { c = if (c and 0x8000 != 0) ((c shl 1) xor 0x8005) and 0xFFFF else (c shl 1) and 0xFFFF }
        c
    }

    private fun crc8(data: ByteArray, length: Int): Int {
        var c = 0
        for (i in 0 until length) c = crc8Table[c xor (data[i].toInt() and 0xFF)]
        return c
    }

    private fun crc16(data: ByteArray, length: Int): Int {
        var c = 0
        for (i in 0 until length) {
            c = ((c shl 8) and 0xFFFF) xor crc16Table[((c shr 8) xor (data[i].toInt() and 0xFF)) and 0xFF]
        }
        return c
    }

    /** "fLaC" plus one STREAMINFO block (the last metadata block). */
    fun streamHeader(): ByteArray {
        val b = ByteBuffer.allocate(4 + 4 + 34).order(ByteOrder.BIG_ENDIAN)
        b.put("fLaC".toByteArray(Charsets.US_ASCII))
        // last metadata block, type 0 = STREAMINFO, then its length (34) as 24 bits
        b.put(byteArrayOf(0x80.toByte(), 0, 0, 34))
        b.putShort(BLOCK.toShort()) // min block size
        b.putShort(BLOCK.toShort()) // max block size
        b.put(byteArrayOf(0, 0, 0)) // min frame size: unknown
        b.put(byteArrayOf(0, 0, 0)) // max frame size: unknown
        // 20 bits sample rate (44100 = 0x0AC44), 3 bits channels - 1, 5 bits bits per sample - 1,
        // 36 bits total samples (0 = unknown)
        b.put(
            byteArrayOf(
                0x0A.toByte(),
                0xC4.toByte(),
                0x42.toByte(), // low 4 bits of the rate (0x4), channels - 1 = 1 (001), top bit of bps - 1
                0xF0.toByte(), // low 4 bits of bps - 1 = 15 (1111), top 4 bits of the total samples
            ),
        )
        b.putInt(0) // low 32 bits of the total samples
        b.put(ByteArray(16)) // MD5 of the audio: unknown
        return b.array()
    }

    private fun utf8Number(n: Long): ByteArray {
        if (n < 0x80) return byteArrayOf(n.toByte())
        val count: Int
        val lead: Int
        when {
            n < 0x800L -> { count = 2; lead = 0xC0 }
            n < 0x10000L -> { count = 3; lead = 0xE0 }
            n < 0x200000L -> { count = 4; lead = 0xF0 }
            n < 0x4000000L -> { count = 5; lead = 0xF8 }
            n < 0x80000000L -> { count = 6; lead = 0xFC }
            else -> { count = 7; lead = 0xFE }
        }
        val out = ByteArray(count)
        var v = n
        for (i in count - 1 downTo 1) {
            out[i] = (0x80 or (v and 0x3F).toInt()).toByte()
            v = v shr 6
        }
        out[0] = (lead or v.toInt()).toByte()
        return out
    }

    /** One frame of [BLOCK] stereo frames from [pcm] (interleaved, at least BLOCK * 2 shorts). */
    fun frame(number: Long, pcm: ShortArray): ByteArray {
        val coded = utf8Number(number)
        val headerLength = 4 + coded.size + 1
        val out = ByteArray(headerLength + 2 * (1 + BLOCK * 2) + 2)
        var p = 0
        out[p++] = 0xFF.toByte()
        out[p++] = 0xF8.toByte() // sync, fixed block size
        out[p++] = 0xC9.toByte() // block size code 1100 = 4096, sample rate code 1001 = 44.1 kHz
        out[p++] = 0x18.toByte() // channel assignment 0001 = 2 independent channels, 16 bit
        for (b in coded) out[p++] = b
        out[p] = crc8(out, p).toByte()
        p++
        for (channel in 0 until 2) {
            out[p++] = 0x02.toByte() // subframe header: VERBATIM, no wasted bits
            for (i in 0 until BLOCK) {
                val s = pcm[i * 2 + channel].toInt()
                out[p++] = (s shr 8).toByte()
                out[p++] = s.toByte()
            }
        }
        val crc = crc16(out, p)
        out[p++] = (crc shr 8).toByte()
        out[p] = crc.toByte()
        return out
    }
}
