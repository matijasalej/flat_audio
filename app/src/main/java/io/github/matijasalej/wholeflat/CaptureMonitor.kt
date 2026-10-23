package io.github.matijasalej.wholeflat

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The delayed copy of the capture: plays what CaptureProbe captured through an AudioTrack whose
 * AudioAttributes carry the chosen usage (content type music), [delayMs] after each chunk was
 * captured. The capture thread hands every read to [offer]; the writer thread sleeps until a
 * chunk is due and writes it.
 *
 * The audible delay is [delayMs] plus [PRIME_MS] (the track is primed with that much silence so
 * that it never underruns), plus the capture and output latency of the device.
 */
class CaptureMonitor(val usageName: String, val usage: Int, val delayMs: Int) {

    companion object {
        const val PRIME_MS = 100
        private const val BUFFER_MS = 300
    }

    private class Item(val dueNs: Long, val pcm: ShortArray)

    val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(usage)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private val queue = LinkedBlockingQueue<Item>()
    private val delayNs = delayMs * 1_000_000L
    private val maxQueued = (delayMs + 2_000) / 20 + 20

    @Volatile
    private var stopped = false

    @Volatile
    private var track: AudioTrack? = null
    private var writer: Thread? = null

    @Volatile
    var framesWritten = 0L
        private set

    @Volatile
    var underruns = 0
        private set

    @Volatile
    var bufferFrames = 0
        private set

    /** What AudioTrack.getStreamType() says about a track built from attributes. */
    @Volatile
    var trackStreamType = Int.MIN_VALUE
        private set

    @Volatile
    var error: String? = null
        private set

    /** Opens the track and starts the writer thread. Returns an error text, or null when it runs. */
    fun start(): String? {
        return try {
            val rate = CaptureProbe.SAMPLE_RATE
            val minBytes = AudioTrack.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val wantBytes = rate * 4 * BUFFER_MS / 1000 // 4 bytes per stereo frame
            val bytes = maxOf(minBytes, wantBytes) / 4 * 4
            val t = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build(),
                )
                .setBufferSizeInBytes(bytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            if (t.state != AudioTrack.STATE_INITIALIZED) {
                t.release()
                return "AudioTrack did not initialise"
            }
            bufferFrames = t.bufferSizeInFrames
            trackStreamType = t.streamType
            // The cushion of silence: playback starts at once, the audio follows behind it.
            val silence = ShortArray(rate * 2 * PRIME_MS / 1000)
            t.write(silence, 0, silence.size)
            t.play()
            track = t
            writer = thread(name = "wf-monitor") { writeLoop(t) }
            AppLog.add(
                "monitor",
                "AudioTrack open for usage $usageName: $bytes bytes buffer (min $minBytes), session ${t.audioSessionId}, " +
                    "delay $delayMs ms + $PRIME_MS ms primed",
            )
            null
        } catch (e: Exception) {
            AppLog.error("monitor", "cannot create the AudioTrack", e)
            "cannot create the AudioTrack: $e"
        }
    }

    /** Called by the capture thread for every read; [capturedAtNs] is System.nanoTime() of the read. */
    fun offer(capturedAtNs: Long, pcm: ShortArray) {
        if (stopped) return
        queue.offer(Item(capturedAtNs + delayNs, pcm))
        while (queue.size > maxQueued) queue.poll()
    }

    private fun writeLoop(t: AudioTrack) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        try {
            while (!stopped) {
                val item = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                val waitNs = item.dueNs - System.nanoTime()
                if (waitNs > 0) Thread.sleep(waitNs / 1_000_000L, (waitNs % 1_000_000L).toInt())
                var offset = 0
                while (offset < item.pcm.size && !stopped) {
                    val n = t.write(item.pcm, offset, item.pcm.size - offset)
                    if (n < 0) {
                        error = "AudioTrack.write returned $n"
                        return
                    }
                    if (n == 0) Thread.sleep(5)
                    offset += n
                }
                framesWritten += item.pcm.size / 2
            }
        } catch (e: InterruptedException) {
            // stopping
        } catch (e: Exception) {
            error = e.toString()
        }
    }

    /** Ends the playback and releases the track. Safe to call twice. */
    @Synchronized
    fun stop() {
        if (stopped) return
        stopped = true
        val t = track
        track = null
        try {
            t?.pause()
            t?.flush()
        } catch (e: IllegalStateException) {
            // already stopped
        }
        try {
            writer?.join(1500)
        } catch (e: InterruptedException) {
            // shutting down anyway
        }
        try {
            if (t != null) underruns = t.underrunCount
            t?.release()
        } catch (e: Exception) {
            AppLog.error("monitor", "releasing the AudioTrack failed", e)
        }
        queue.clear()
        AppLog.add("monitor", "stopped after $framesWritten frames, $underruns underruns" + (error?.let { ", error $it" } ?: ""))
    }
}
