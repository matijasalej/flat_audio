package io.github.matijasalej.wholeflat.sync

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A minimal FLAC encoder: every subframe is VERBATIM, so there is no compression and no dependency,
 * just a valid stream. STREAMINFO says "total samples unknown" (0), blocks are a fixed number of frames
 * (4096 unless asked for another size), each frame header carries the frame number (UTF-8 coded) and a
 * CRC-8, each frame ends with a CRC-16.
 *
 * A smaller block is a shorter wait in a live stream (a frame can only be sent once all of its samples
 * exist): 4096 frames are 93 ms at 44.1 kHz, 1152 are 26 ms, 576 are 13 ms.
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

    /** The sample rate code of the frame header (FLAC format, section "frame header"). */
    private fun rateCode(sampleRate: Int): Int = when (sampleRate) {
        44_100 -> 0b1001
        48_000 -> 0b1010
        32_000 -> 0b1000
        96_000 -> 0b1011
        else -> throw IllegalArgumentException("sample rate $sampleRate has no code in the FLAC frame header")
    }

    /** The block size code of the frame header; only the sizes that have a code of their own are possible. */
    private fun blockCode(blockSize: Int): Int = when (blockSize) {
        192 -> 0b0001
        576 -> 0b0010
        1152 -> 0b0011
        2304 -> 0b0100
        4608 -> 0b0101
        256 -> 0b1000
        512 -> 0b1001
        1024 -> 0b1010
        2048 -> 0b1011
        4096 -> 0b1100
        8192 -> 0b1101
        16384 -> 0b1110
        32768 -> 0b1111
        else -> throw IllegalArgumentException("block size $blockSize has no code in the FLAC frame header")
    }

    /** The block sizes that can be asked for. */
    val BLOCK_SIZES = intArrayOf(192, 256, 512, 576, 1024, 1152, 2048, 2304, 4096, 4608, 8192, 16384, 32768)

    /** "fLaC" plus one STREAMINFO block (the last metadata block). */
    fun streamHeader(sampleRate: Int = 44_100, blockSize: Int = BLOCK): ByteArray {
        rateCode(sampleRate) // refuses a rate the frames cannot carry
        blockCode(blockSize) // and a block size
        val b = ByteBuffer.allocate(4 + 4 + 34).order(ByteOrder.BIG_ENDIAN)
        b.put("fLaC".toByteArray(Charsets.US_ASCII))
        // last metadata block, type 0 = STREAMINFO, then its length (34) as 24 bits
        b.put(byteArrayOf(0x80.toByte(), 0, 0, 34))
        b.putShort(blockSize.toShort()) // min block size
        b.putShort(blockSize.toShort()) // max block size
        b.put(byteArrayOf(0, 0, 0)) // min frame size: unknown
        b.put(byteArrayOf(0, 0, 0)) // max frame size: unknown
        // 20 bits sample rate, 3 bits channels - 1, 5 bits bits per sample - 1, 36 bits total samples (0 = unknown)
        b.putLong((sampleRate.toLong() shl 44) or (1L shl 41) or (15L shl 36))
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

    /** One frame of [blockSize] stereo frames from [pcm] (interleaved, at least blockSize * 2 shorts). */
    fun frame(number: Long, pcm: ShortArray, sampleRate: Int = 44_100, blockSize: Int = BLOCK): ByteArray {
        val coded = utf8Number(number)
        val headerLength = 4 + coded.size + 1
        val out = ByteArray(headerLength + 2 * (1 + blockSize * 2) + 2)
        var p = 0
        out[p++] = 0xFF.toByte()
        out[p++] = 0xF8.toByte() // sync, fixed block size
        out[p++] = ((blockCode(blockSize) shl 4) or rateCode(sampleRate)).toByte() // block size code, then the sample rate code
        out[p++] = 0x18.toByte() // channel assignment 0001 = 2 independent channels, 16 bit
        for (b in coded) out[p++] = b
        out[p] = crc8(out, p).toByte()
        p++
        for (channel in 0 until 2) {
            out[p++] = 0x02.toByte() // subframe header: VERBATIM, no wasted bits
            for (i in 0 until blockSize) {
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
