package io.github.matijasalej.wholeflat.sync

/**
 * ADTS, the framing of AAC in a transport stream: the encoder of MediaCodec makes raw AAC frames, each is given a seven byte header
 * that says its length, sample rate and channel count. AAC-LC only (the audio object type 2). Plain Kotlin.
 */
object Adts {
    const val HEADER_BYTES = 7

    private val RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

    /** The index of [rate] in the ADTS table of sample rates, or -1. */
    fun rateIndex(rate: Int): Int = RATES.indexOf(rate)

    /** The header of an AAC-LC frame of [payloadBytes] bytes. */
    fun header(payloadBytes: Int, rate: Int, channels: Int): ByteArray {
        val index = rateIndex(rate)
        require(index >= 0) { "no ADTS index for $rate Hz" }
        require(channels in 1..7) { "channels $channels" }
        val length = payloadBytes + HEADER_BYTES
        require(length < (1 shl 13)) { "frame of $payloadBytes bytes is too long for ADTS" }
        val profile = 2 - 1 // AAC-LC, as "object type - 1"
        val h = ByteArray(HEADER_BYTES)
        h[0] = 0xFF.toByte()
        h[1] = 0xF1.toByte() // sync, MPEG-4, layer 0, no CRC
        h[2] = ((profile shl 6) or (index shl 2) or (channels shr 2)).toByte()
        h[3] = (((channels and 3) shl 6) or (length shr 11)).toByte()
        h[4] = ((length shr 3) and 0xFF).toByte()
        h[5] = (((length and 7) shl 5) or 0x1F).toByte() // buffer fullness: variable
        h[6] = 0xFC.toByte() // one raw data block
        return h
    }

    /** The ADTS frame for the raw AAC frame `raw[off, off + len)`. */
    fun frame(raw: ByteArray, off: Int, len: Int, rate: Int, channels: Int): ByteArray {
        val out = ByteArray(HEADER_BYTES + len)
        System.arraycopy(header(len, rate, channels), 0, out, 0, HEADER_BYTES)
        System.arraycopy(raw, off, out, HEADER_BYTES, len)
        return out
    }

    /** The frame length that the header at the start of [data] says, or -1 if it is not an ADTS header. */
    fun frameLength(data: ByteArray, off: Int = 0): Int {
        if (data.size - off < HEADER_BYTES) return -1
        if (data[off] != 0xFF.toByte() || (data[off + 1].toInt() and 0xF0) != 0xF0) return -1
        return ((data[off + 3].toInt() and 3) shl 11) or ((data[off + 4].toInt() and 0xFF) shl 3) or ((data[off + 5].toInt() and 0xFF) shr 5)
    }
}
