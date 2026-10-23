package io.github.matijasalej.wholeflat

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.view.Surface
import io.github.matijasalej.wholeflat.sync.H264
import io.github.matijasalej.wholeflat.sync.TvVideoSpec

/**
 * The H.264 encoder of the lyrics video: the size, the rate of pictures, the bitrate and its mode, and the time between IDR pictures of
 * the [TvVideoSpec] (the setting `tv_video`; by default 1920 x 1080 at 30 frames a second, constant bitrate 6 Mbit/s, an IDR picture every
 * second), from the input surface (the pictures are drawn on it), High profile if the encoder has it (else Main, else what it gives), no
 * B-frames, the lowest latency the encoder offers, and the previous picture repeated if no new one comes for 100 ms. The hardware
 * encoder of this box is `c2.amlogic.avc.encoder` (tried first, then whatever makes `video/avc`).
 *
 * The encoded pictures are handed to [onFrame] as they come out (on a thread of their own): the bytes as the encoder wrote them, the
 * time the picture was put on the surface (microseconds, System.nanoTime based) and whether the encoder calls it a key picture.
 */
class TvVideoEncoder(
    private val spec: TvVideoSpec,
    private val onFrame: (ByteArray, Long, Boolean) -> Unit,
) {
    private val width = spec.width
    private val height = spec.height
    private val fps = spec.fps
    private val bitrate = spec.bitrate

    private var codec: MediaCodec? = null
    private var drain: Thread? = null

    @Volatile
    private var running = false

    /** The name of the encoder in use and the profile it was configured with, for the status. */
    @Volatile
    var name = ""
        private set

    @Volatile
    var profile = ""
        private set

    /** The sequence and picture parameter sets that the encoder announced (Annex B), null until it has. */
    @Volatile
    var parameterSets: ByteArray? = null
        private set

    @Volatile
    var framesOut = 0L
        private set

    @Volatile
    var keyFramesOut = 0L
        private set

    @Volatile
    var bytesOut = 0L
        private set

    @Volatile
    var syncRequests = 0L
        private set

    @Volatile
    var lastError: String? = null
        private set

    /** Latency of the encoder (ms): from the moment a picture was put on the surface to the moment its bytes came out; the latest. */
    @Volatile
    var latencyMs = 0.0
        private set

    @Volatile
    var latencyMaxMs = 0.0
        private set

    private var latencySum = 0.0
    private var latencyCount = 0L
    private var lastSlowLogNanos = 0L
    private var periodSum = 0.0
    private var periodCount = 0L
    private var periodMax = 0.0

    /** The average and the worst latency (ms) of the pictures since the last call (and a fresh start of them). */
    @Synchronized
    fun takePeriod(): Pair<Double, Double> {
        val r = Pair(if (periodCount == 0L) 0.0 else periodSum / periodCount, periodMax)
        periodSum = 0.0
        periodCount = 0L
        periodMax = 0.0
        return r
    }

    /** What the encoder gave for each of the last pictures, for a look at a scroll: when it came (ms of System.nanoTime), the time of the picture (us), its size, whether it is a key picture, and the time from the picture to its bytes (ms). */
    private class Out(val wallMs: Long, val ptsUs: Long, val bytes: Int, val key: Boolean, val latencyMs: Double)

    private val recent = java.util.ArrayDeque<Out>()

    private fun record(wallMs: Long, ptsUs: Long, bytes: Int, key: Boolean, latencyMs: Double) {
        synchronized(recent) {
            recent.addLast(Out(wallMs, ptsUs, bytes, key, latencyMs))
            while (recent.size > RECENT_FRAMES) recent.removeFirst()
        }
    }

    /** The pictures that came out last as CSV lines `enc,wall_ms,pts_us,bytes,key,encode_ms` (about the last 20 s). */
    fun recentCsv(): String {
        val sb = StringBuilder()
        synchronized(recent) {
            for (o in recent) {
                sb.append("enc,").append(o.wallMs).append(',').append(o.ptsUs).append(',').append(o.bytes).append(',').append(if (o.key) 1 else 0).append(',')
                    .append(String.format(java.util.Locale.US, "%.1f", o.latencyMs)).append('\n')
            }
        }
        return sb.toString()
    }

    val latencyAvgMs: Double get() = if (latencyCount == 0L) 0.0 else latencySum / latencyCount

    /** Makes and starts the encoder; returns its input surface. Throws if no encoder takes the format. */
    fun start(): Surface {
        var failure: Throwable? = null
        for (variant in VARIANTS) {
            var c: MediaCodec? = null
            try {
                c = createCodec()
                c.configure(format(variant), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val surface = c.createInputSurface()
                c.start()
                codec = c
                profile = variant.label
                running = true
                drain = Thread({ drainLoop(c) }, "wf-tv-venc").also {
                    it.isDaemon = true
                    it.start()
                }
                AppLog.add(TAG, "video encoder $name, ${variant.label}, ${width}x$height at $fps fps, ${bitrate / 1000} kbit/s ${spec.mode.key}, an IDR every ${spec.gopS} s (asked for: $spec)")
                return surface
            } catch (t: Throwable) {
                failure = t
                lastError = "${variant.label}: $t"
                AppLog.add(TAG, "the video encoder does not take ${variant.label}: $t")
                try {
                    c?.release()
                } catch (e: Exception) {
                    // gone anyway
                }
            }
        }
        throw IllegalStateException("no H.264 encoder takes the format ($failure)", failure)
    }

    private fun createCodec(): MediaCodec {
        for (n in PREFERRED) {
            try {
                val c = MediaCodec.createByCodecName(n)
                name = n
                return c
            } catch (e: Exception) {
                // not on this device
            }
        }
        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        name = c.name
        return c
    }

    private class Variant(val label: String, val profile: Int?, val level: Int?)

    private fun format(v: Variant): MediaFormat {
        val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        f.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, spec.gopS)
        f.setInteger(
            MediaFormat.KEY_BITRATE_MODE,
            when (spec.mode) {
                TvVideoSpec.Mode.CBR -> MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                TvVideoSpec.Mode.VBR -> MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                TvVideoSpec.Mode.CQ -> MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ
            },
        )
        f.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        f.setInteger(MediaFormat.KEY_LATENCY, 1)
        f.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, REPEAT_AFTER_US)
        f.setInteger("prepend-sps-pps-to-idr-frames", 1)
        if (v.profile != null) f.setInteger(MediaFormat.KEY_PROFILE, v.profile)
        if (v.level != null) f.setInteger(MediaFormat.KEY_LEVEL, v.level)
        return f
    }

    /** Asks for an IDR picture as the next one. */
    fun requestSync() {
        val c = codec ?: return
        try {
            val b = Bundle()
            b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            c.setParameters(b)
            syncRequests++
        } catch (e: Exception) {
            lastError = "sync request: $e"
        }
    }

    private fun drainLoop(c: MediaCodec) {
        ThreadBoost.urgent()
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val index = c.dequeueOutputBuffer(info, 20_000)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> AppLog.add(TAG, "video encoder output format: ${c.outputFormat}")
                    index >= 0 -> {
                        val arrived = System.nanoTime()
                        val buffer = c.getOutputBuffer(index)
                        if (buffer != null && info.size > 0) {
                            val bytes = ByteArray(info.size)
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            buffer.get(bytes)
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                parameterSets = H264.parameterSets(bytes)
                            } else {
                                framesOut++
                                bytesOut += bytes.size
                                val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                                if (key) keyFramesOut++
                                val latency = (arrived - info.presentationTimeUs * 1000L) / 1e6
                                noteLatency(latency)
                                if (latency > SLOW_MS && latency < 2_000 && arrived - lastSlowLogNanos > 2_000_000_000L) {
                                    lastSlowLogNanos = arrived
                                    AppLog.add(TAG, "a picture took ${latency.toLong()} ms to come out of the encoder (${bytes.size} bytes${if (key) ", a key picture" else ""}, ${syncRequests} sync requests so far)")
                                }
                                record(arrived / 1_000_000L, info.presentationTimeUs, bytes.size, key, latency)
                                onFrame(bytes, info.presentationTimeUs, key)
                            }
                        }
                        c.releaseOutputBuffer(index, false)
                    }
                }
            }
        } catch (e: IllegalStateException) {
            if (running) lastError = "drain: $e"
        } catch (t: Throwable) {
            lastError = "drain: $t"
            AppLog.error(TAG, "the video encoder's output failed", t)
        }
    }

    @Synchronized
    private fun noteLatency(ms: Double) {
        // a timestamp that is not the time of the picture (a repeated one) gives a nonsense: only plausible ones count
        if (ms < -50 || ms > 2_000) return
        periodSum += ms
        periodCount++
        if (ms > periodMax) periodMax = ms
        latencyMs = ms
        if (ms > latencyMaxMs) latencyMaxMs = ms
        latencySum += ms
        latencyCount++
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
        const val REPEAT_AFTER_US = 100_000L
        const val RECENT_FRAMES = 600

        /** A picture that takes longer than this to come out of the encoder is put in the log (ms). */
        const val SLOW_MS = 150.0
        val PREFERRED = listOf("c2.amlogic.avc.encoder")
        val VARIANTS = listOf(
            Variant("High", MediaCodecInfo.CodecProfileLevel.AVCProfileHigh, MediaCodecInfo.CodecProfileLevel.AVCLevel41),
            Variant("Main", MediaCodecInfo.CodecProfileLevel.AVCProfileMain, MediaCodecInfo.CodecProfileLevel.AVCLevel41),
            Variant("the encoder's own profile", null, null),
        )
    }
}
