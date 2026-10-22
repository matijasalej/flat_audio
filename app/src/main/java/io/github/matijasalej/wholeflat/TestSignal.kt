package io.github.matijasalej.wholeflat

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * The test signal for the Sonos spike: 44.1 kHz, 16 bit, stereo. A quiet 440 Hz tone with a click
 * at the start of every second (5 ms of 2 kHz, every 10th click 4 kHz instead) so that the delay
 * between this device and a Sonos can be heard or measured from a recording.
 *
 * The signal repeats every 10 s; it is built once as two one-second buffers (a 440 Hz period is
 * 2205 samples, which divides 44100 exactly, so the tone is continuous across the seconds).
 */
class ToneGenerator {
    companion object {
        const val RATE = 44_100
        const val CHANNELS = 2
        private const val TONE_HZ = 440.0
        private const val TONE_LEVEL = 0.03
        private const val CLICK_LEVEL = 0.7
        private const val CLICK_FRAMES = 220 // 5 ms
        private const val CLICK_RAMP = 16.0 // frames of fade in and out, avoids a DC pop
        private const val CLICK_LOW_HZ = 2_000.0
        private const val CLICK_HIGH_HZ = 4_000.0
    }

    private val secondLow = buildSecond(CLICK_LOW_HZ)
    private val secondHigh = buildSecond(CLICK_HIGH_HZ)

    /** Absolute index of the next frame to produce. */
    var frame: Long = 0
        private set

    private fun buildSecond(clickHz: Double): ShortArray {
        val out = ShortArray(RATE * CHANNELS)
        for (n in 0 until RATE) {
            var v = TONE_LEVEL * sin(2.0 * PI * TONE_HZ * n / RATE)
            if (n < CLICK_FRAMES) {
                val ramp = minOf(1.0, (n + 1.0) / CLICK_RAMP, (CLICK_FRAMES - n) / CLICK_RAMP)
                v += CLICK_LEVEL * ramp * sin(2.0 * PI * clickHz * n / RATE)
            }
            val s = (v.coerceIn(-1.0, 1.0) * 32767.0).toInt().toShort()
            out[2 * n] = s
            out[2 * n + 1] = s
        }
        return out
    }

    /** Fills [dst] with the next [frames] stereo frames (interleaved, so frames * 2 shorts). */
    fun fill(dst: ShortArray, frames: Int) {
        var done = 0
        while (done < frames) {
            val second = frame / RATE
            val inSecond = (frame % RATE).toInt()
            val source = if (second % 10 == 9L) secondHigh else secondLow
            val n = minOf(frames - done, RATE - inSecond)
            System.arraycopy(source, inSecond * CHANNELS, dst, done * CHANNELS, n * CHANNELS)
            done += n
            frame += n
        }
    }
}

object Wav {
    const val HEADER_BYTES = 44

    /**
     * Declared size of the whole file. Just under 2^31, so players that read the RIFF sizes or the
     * Content-Length as a signed 32 bit number still take it as "very long" (about 3.4 hours).
     */
    const val TOTAL_BYTES = 2_147_483_000L
    const val DATA_BYTES = TOTAL_BYTES - HEADER_BYTES

    fun header(): ByteArray {
        val b = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray(Charsets.US_ASCII))
        b.putInt((TOTAL_BYTES - 8).toInt())
        b.put("WAVE".toByteArray(Charsets.US_ASCII))
        b.put("fmt ".toByteArray(Charsets.US_ASCII))
        b.putInt(16)
        b.putShort(1) // PCM
        b.putShort(ToneGenerator.CHANNELS.toShort())
        b.putInt(ToneGenerator.RATE)
        b.putInt(ToneGenerator.RATE * ToneGenerator.CHANNELS * 2)
        b.putShort((ToneGenerator.CHANNELS * 2).toShort())
        b.putShort(16)
        b.put("data".toByteArray(Charsets.US_ASCII))
        b.putInt(DATA_BYTES.toInt())
        return b.array()
    }

    /** Little-endian bytes of [frames] stereo frames from [pcm]. */
    fun pcmBytes(pcm: ShortArray, frames: Int, out: ByteArray) {
        var o = 0
        for (i in 0 until frames * ToneGenerator.CHANNELS) {
            val s = pcm[i].toInt()
            out[o++] = s.toByte()
            out[o++] = (s shr 8).toByte()
        }
    }
}

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
        b.put(0x80.toByte()) // last metadata block, type 0 = STREAMINFO
        b.put(0).put(0).put(34) // block length
        b.putShort(BLOCK.toShort()) // min block size
        b.putShort(BLOCK.toShort()) // max block size
        b.put(0).put(0).put(0) // min frame size: unknown
        b.put(0).put(0).put(0) // max frame size: unknown
        // 20 bits sample rate (44100 = 0x0AC44), 3 bits channels - 1, 5 bits bits per sample - 1,
        // 36 bits total samples (0 = unknown)
        b.put(0x0A.toByte())
        b.put(0xC4.toByte())
        b.put(0x42.toByte()) // low 4 bits of the rate (0x4), channels - 1 = 1 (001), top bit of bps - 1
        b.put(0xF0.toByte()) // low 4 bits of bps - 1 = 15 (1111), top 4 bits of the total samples
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
            out[p++] = 0x02 // subframe header: VERBATIM, no wasted bits
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
