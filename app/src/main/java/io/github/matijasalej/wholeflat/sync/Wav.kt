package io.github.matijasalej.wholeflat.sync

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * WAV for a stream of unknown length: a 44-byte RIFF header that claims a data length just under 2 GiB (as the
 * Sonos spikes' "endless" WAV does, and the HTTP response says the same in its Content-Length), then plain little-endian
 * 16 bit stereo samples. There is no block structure, so a stream can be sent in chunks as small as it likes: the
 * speaker starts sooner than with FLAC (0.19 s from Play to sound against 0.29 s, measured), and a live stream has no
 * frame to wait for. The claimed length lasts about 3.4 hours at 44.1 kHz; then the stream ends.
 */
object Wav {
    const val HEADER_SIZE = 44

    /** The data length the header claims: 0x7FFFF000, a multiple of 4096 just under 2 GiB. */
    const val DATA_BYTES = 0x7FFFF000L

    /** What the response says in its Content-Length: the header and the data it claims. */
    const val CONTENT_LENGTH = HEADER_SIZE + DATA_BYTES

    /** The RIFF header for 16 bit stereo at [sampleRate], claiming [dataBytes] of samples. */
    fun header(sampleRate: Int, dataBytes: Long = DATA_BYTES): ByteArray {
        val channels = 2
        val bits = 16
        val blockAlign = channels * bits / 8
        val b = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray(Charsets.US_ASCII))
        b.putInt((36 + dataBytes).toInt())
        b.put("WAVE".toByteArray(Charsets.US_ASCII))
        b.put("fmt ".toByteArray(Charsets.US_ASCII))
        b.putInt(16) // size of the format chunk
        b.putShort(1) // PCM
        b.putShort(channels.toShort())
        b.putInt(sampleRate)
        b.putInt(sampleRate * blockAlign)
        b.putShort(blockAlign.toShort())
        b.putShort(bits.toShort())
        b.put("data".toByteArray(Charsets.US_ASCII))
        b.putInt(dataBytes.toInt())
        return b.array()
    }

    /** [frames] stereo frames of [pcm] (interleaved shorts) as little-endian bytes. */
    fun pcm(pcm: ShortArray, frames: Int): ByteArray {
        val out = ByteArray(frames * 4)
        var p = 0
        for (i in 0 until frames * 2) {
            val s = pcm[i].toInt()
            out[p++] = s.toByte()
            out[p++] = (s shr 8).toByte()
        }
        return out
    }

    /** True if a chunk of [chunkBytes] still fits in the length that the header claims, after [sentBytes] of samples. */
    fun fits(sentBytes: Long, chunkBytes: Int): Boolean = sentBytes + chunkBytes <= DATA_BYTES
}
