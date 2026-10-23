package io.github.matijasalej.wholeflat.sync

/**
 * Raw 16 bit stereo PCM, big-endian, as the Philips TV's DLNA renderer takes it (`audio/L16;rate=44100;channels=2`,
 * protocolInfo `...:DLNA.ORG_PN=LPCM`). There is no header, no length and no block structure: a stream of any length is sent in
 * chunks as small as it likes, and it ends when the connection does. It is the only format of the app that is endless.
 */
object L16 {
    /** The Content-Type of the stream (44.1 kHz stereo). */
    const val CONTENT_TYPE = "audio/L16;rate=44100;channels=2"

    /** The DLNA features that the renderer was seen to accept: LPCM, no seeking (OP=00), a live stream (the flags). */
    const val CONTENT_FEATURES = "DLNA.ORG_PN=LPCM;DLNA.ORG_OP=00;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"

    /** [frames] stereo frames of [pcm] (interleaved shorts) as big-endian bytes. */
    fun pcm(pcm: ShortArray, frames: Int): ByteArray {
        val out = ByteArray(frames * 4)
        var p = 0
        for (i in 0 until frames * 2) {
            val s = pcm[i].toInt()
            out[p++] = (s shr 8).toByte()
            out[p++] = s.toByte()
        }
        return out
    }

    /** [frames] frames of digital silence. */
    fun silence(frames: Int): ByteArray = ByteArray(frames * 4)
}
