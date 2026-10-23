package io.github.matijasalej.wholeflat

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import java.nio.ByteOrder

/**
 * The AAC-LC encoder of the lyrics video: blocks of 1024 stereo frames of PCM go in, raw AAC frames come out (the muxer puts the ADTS
 * header on them). The encoder of this box is `c2.android.aac.encoder` (tried first, then whatever makes `audio/mp4a-latm`).
 * [onFrame] is called for each frame on a thread of its own.
 */
class TvAudioEncoder(private val rate: Int, private val bitrate: Int, private val onFrame: (ByteArray) -> Unit) {
    private var codec: MediaCodec? = null
    private var drain: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    var name = ""
        private set

    @Volatile
    var framesIn = 0L
        private set

    @Volatile
    var framesOut = 0L
        private set

    @Volatile
    var droppedBlocks = 0L
        private set

    @Volatile
    var lastError: String? = null
        private set

    fun start() {
        var c: MediaCodec? = null
        try {
            c = create()
            val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, CHANNELS)
            f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
            c.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
            codec = c
            running = true
            drain = Thread({ drainLoop(c) }, "wf-tv-aenc").also {
                it.isDaemon = true
                it.start()
            }
            AppLog.add(TAG, "audio encoder $name, AAC-LC $rate Hz stereo, ${bitrate / 1000} kbit/s")
        } catch (t: Throwable) {
            lastError = t.toString()
            try {
                c?.release()
            } catch (e: Exception) {
                // gone
            }
            throw t
        }
    }

    private fun create(): MediaCodec {
        for (n in PREFERRED) {
            try {
                val c = MediaCodec.createByCodecName(n)
                name = n
                return c
            } catch (e: Exception) {
                // not on this device
            }
        }
        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        name = c.name
        return c
    }

    /** Gives the encoder [frames] stereo frames of [pcm]; [ptsUs] is the time of the first. False if the encoder had no room within a moment (the block is lost). */
    fun queue(pcm: ShortArray, frames: Int, ptsUs: Long): Boolean {
        val c = codec ?: return false
        return try {
            val index = c.dequeueInputBuffer(WAIT_US)
            if (index < 0) {
                droppedBlocks++
                false
            } else {
                val buffer = c.getInputBuffer(index)!!
                buffer.clear()
                buffer.order(ByteOrder.nativeOrder())
                buffer.asShortBuffer().put(pcm, 0, frames * CHANNELS)
                c.queueInputBuffer(index, 0, frames * CHANNELS * 2, ptsUs, 0)
                framesIn++
                true
            }
        } catch (e: IllegalStateException) {
            lastError = "queue: $e"
            false
        }
    }

    private fun drainLoop(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val index = c.dequeueOutputBuffer(info, 20_000)
                if (index >= 0) {
                    val buffer = c.getOutputBuffer(index)
                    if (buffer != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val bytes = ByteArray(info.size)
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        buffer.get(bytes)
                        framesOut++
                        onFrame(bytes)
                    }
                    c.releaseOutputBuffer(index, false)
                }
            }
        } catch (e: IllegalStateException) {
            if (running) lastError = "drain: $e"
        } catch (t: Throwable) {
            lastError = "drain: $t"
            AppLog.error(TAG, "the audio encoder's output failed", t)
        }
    }

    fun stop() {
        running = false
        try {
            drain?.join(500)
        } catch (e: InterruptedException) {
            // stopping anyway
        }
        val c = codec
        codec = null
        try {
            c?.stop()
        } catch (e: Exception) {
            // already stopped
        }
        try {
            c?.release()
        } catch (e: Exception) {
            // gone
        }
    }

    private companion object {
        const val TAG = "tvvideo"
        const val CHANNELS = 2
        const val WAIT_US = 20_000L
        val PREFERRED = listOf("c2.android.aac.encoder")
    }
}
