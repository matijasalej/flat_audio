package io.github.matijasalej.wholeflat

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.Process
import io.github.matijasalej.wholeflat.sync.AnchorFilter
import io.github.matijasalej.wholeflat.sync.StartPlanner
import io.github.matijasalej.wholeflat.sync.TrackTimeline
import org.json.JSONObject

/** What the living room asks of whoever runs the other rooms while it starts an epoch. */
interface StartCoordinator {
    /** A start event is waiting (once per epoch, on the audio thread: return at once). */
    fun begin(epoch: Long, kind: Int, startRingFrame: Long)

    /** Polled by the audio thread while the start is pending. */
    fun advice(epoch: Long): StartAdvice

    /** The living room has placed its first frame: send Play at `plan.tCmdNanos`. */
    fun commit(epoch: Long, plan: StartPlanner.Plan)

    /** The start went ahead without the other rooms, or was overtaken by something else. */
    fun abandon(epoch: Long)

    /** Nothing plays any more (pause, end of the queue, end of the test signal). */
    fun playbackStopped()
}

sealed class StartAdvice {
    /** The other rooms are still being prepared. */
    object Pending : StartAdvice()

    /** There are no other rooms: start at once. */
    object Alone : StartAdvice()

    /** Ready: the kitchen's Play-to-sound delay and the offset it should be late by. */
    class Ready(val lkNanos: Long, val offsetNanos: Long) : StartAdvice()
}

/**
 * The living room: one `AudioTrack` that is fed all the time (silence when nothing plays, so that the
 * HDMI link stays up and an epoch can start at an exact track frame) by a pull thread that reads
 * from the Rust ring.
 *
 * It is the clock master of the two rooms. `AudioTrack.getTimestamp` says when the HAL presents which
 * track frame; [timeline] turns that into "when is ring frame f heard", which the kitchen follows.
 * A start event (seek, skip, resume, calibration, recovery) is placed at the track frame that is heard
 * when the kitchen's first sound arrives, with silence in front of it.
 *
 * A watchdog thread releases the track when its position stops advancing (a sleeping HDMI sink froze
 * it once), and the audio thread then builds a new one and restarts the music where it was heard.
 */
class LivingRoom(private val context: Context) {

    val timeline = TrackTimeline()

    @Volatile
    var coordinator: StartCoordinator? = null

    /** The track's volume, 1.0 unless "living room fixed volume" is off. */
    @Volatile
    var gain: Float = 1.0f
        set(value) {
            field = value
            try {
                track?.setVolume(value)
            } catch (e: Exception) {
                AppLog.error(TAG, "setVolume failed", e)
            }
        }

    // ---- status ------------------------------------------------------------------------------

    /** starting, idle, playing, preparing, error */
    @Volatile
    var state = "starting"
        private set

    @Volatile
    var framesPlayed = 0L
        private set

    @Volatile
    var underruns = 0
        private set

    @Volatile
    var stalls = 0
        private set

    @Volatile
    var recoveries = 0
        private set

    @Volatile
    var starves = 0
        private set

    @Volatile
    var starts = 0
        private set

    @Volatile
    var routeChanges = 0
        private set

    @Volatile
    var lastStart = ""
        private set

    @Volatile
    var epoch = 0L
        private set

    @Volatile
    var epochKind = "none"
        private set

    /** True while an epoch is playing (the pull thread is handing out frames). */
    @Volatile
    var dataFlowing = false
        private set

    @Volatile
    private var bufferFrames = 0

    // ---- threads and the track -----------------------------------------------------------------

    @Volatile
    private var running = false
    private var audioThread: Thread? = null
    private var watchdogThread: Thread? = null

    @Volatile
    private var track: AudioTrack? = null

    /** The watchdog (or a failed write) found the track unusable. */
    @Volatile
    private var broken = false

    /** The watchdog should see the head position advance. */
    @Volatile
    private var expectAdvance = false

    @Volatile
    private var lastHeadAtBreak = -1L

    private val meta = RingMeta()
    private val buf = ShortArray(CHUNK_FRAMES * 2)
    private val silence = ShortArray(CHUNK_FRAMES * 2)
    private val timestamp = AudioTimestamp()
    private val filter = AnchorFilter()

    /** Frames written to the current track since it was created or flushed. */
    private var written = 0L
    private var flushedAtNanos = 0L
    private var lastAnchorPollNanos = 0L
    private var lastUnderrunCount = 0

    /** The ring frame after the last one written, while the current run goes on. */
    private var runOpen = false
    private var runNextRing = 0L

    private var pendingEpoch = -1L
    private var pendingSinceNanos = 0L
    private var pendingKind = 0

    @Volatile
    private var resetFilter = false

    private val handler = Handler(Looper.getMainLooper())
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            routeChanged("added", addedDevices)
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            routeChanged("removed", removedDevices)
        }
    }

    fun start() {
        if (running) return
        running = true
        try {
            context.getSystemService(AudioManager::class.java)?.registerAudioDeviceCallback(deviceCallback, handler)
        } catch (e: Exception) {
            AppLog.error(TAG, "cannot watch the audio devices", e)
        }
        audioThread = Thread({ audioLoop() }, "wf-living").also { it.start() }
        watchdogThread = Thread({ watchdogLoop() }, "wf-watchdog").also {
            it.isDaemon = true
            it.start()
        }
    }

    fun stop() {
        running = false
        try {
            context.getSystemService(AudioManager::class.java)?.unregisterAudioDeviceCallback(deviceCallback)
        } catch (e: Exception) {
            // not registered
        }
        // a write that blocks on a stuck track only ends when the track goes
        try {
            track?.release()
        } catch (e: Exception) {
            // released already
        }
        for (t in listOf(audioThread, watchdogThread)) {
            try {
                t?.join(1500)
            } catch (e: InterruptedException) {
                // shutting down anyway
            }
        }
        track = null
        state = "stopped"
        dataFlowing = false
    }

    /** The engine was restarted: the ring is new, whatever was prepared for the old one is void. */
    fun engineReset() {
        pendingEpoch = -1L
        dataFlowing = false
    }

    // ---- the audio thread ----------------------------------------------------------------------

    private fun audioLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        AppLog.add(TAG, "audio thread started")
        while (running) {
            try {
                step()
            } catch (t: Throwable) {
                AppLog.error(TAG, "audio loop failed", t)
                sleepQuietly(200)
            }
        }
        try {
            track?.let {
                it.pause()
                it.flush()
                it.release()
            }
        } catch (e: Exception) {
            // released already
        }
        AppLog.add(TAG, "audio thread ended")
    }

    private fun step() {
        val t = ensureTrack()
        if (t == null) {
            sleepQuietly(500)
            return
        }
        maintainAnchor(t)
        if (NativeBridge.loadError != null) {
            // no native library, no music: keep the track alive anyway
            state = "error"
            feedIdle(t)
            return
        }
        val n = NativeBridge.lrRead(buf, CHUNK_FRAMES, READ_TIMEOUT_MS, heardHint(), meta.a)
        epoch = meta.epoch
        epochKind = meta.kindName()
        when {
            n > 0 -> onData(t, n)
            n == 0 -> onStarved(t)
            n == NativeBridge.START_PENDING -> onStartPending(t)
            n == NativeBridge.PAUSED -> onNotPlaying(t, "idle")
            else -> onNotPlaying(t, "no engine")
        }
    }

    private fun onData(t: AudioTrack, frames: Int) {
        val first = meta.firstFrame
        if (meta.kind == KIND_CALIBRATION) {
            // the test signal: the living room plays the left channel, on both speakers
            for (i in 0 until frames) buf[i * 2 + 1] = buf[i * 2]
        }
        if (!runOpen || first != runNextRing) {
            timeline.startRun(first, written)
            runOpen = true
        }
        if (!writeFrames(t, buf, frames)) return
        timeline.extend(frames.toLong())
        runNextRing = first + frames
        framesPlayed += frames
        dataFlowing = true
        state = "playing"
    }

    /** Playing, but the decoder has nothing: keep the track fed, the next data opens a new run. */
    private fun onStarved(t: AudioTrack) {
        if (dataFlowing) {
            starves++
            if (starves <= 20 || starves % 100 == 0) AppLog.add(TAG, "no data from the decoder (#$starves)")
        }
        runOpen = false
        writeFrames(t, silence, STARVE_CHUNK_FRAMES)
    }

    /** The ring says nothing plays: stop at once and keep the track fed with silence. */
    private fun onNotPlaying(t: AudioTrack, why: String) {
        if (dataFlowing || pendingEpoch != -1L) {
            val wasPending = pendingEpoch != -1L
            dataFlowing = false
            pendingEpoch = -1L
            flushTrack(t)
            coordinator?.playbackStopped()
            AppLog.add(TAG, "stopped ($why)" + if (wasPending) ", the start was cancelled" else "")
        }
        state = if (why == "idle") "idle" else why
        feedIdle(t)
    }

    // ---- start events --------------------------------------------------------------------------

    private fun onStartPending(t: AudioTrack) {
        val e = meta.epoch
        val now = System.nanoTime()
        if (pendingEpoch != e) {
            if (pendingEpoch != -1L) coordinator?.abandon(pendingEpoch)
            pendingEpoch = e
            pendingSinceNanos = now
            pendingKind = meta.kind
            dataFlowing = false
            runOpen = false
            state = "preparing"
            starts++
            coordinator?.begin(e, meta.kind, meta.epochStart)
        }
        val waitedMs = (now - pendingSinceNanos) / 1_000_000L

        // 1. the decoder has to have produced something of the new epoch
        val have = meta.writeIdx - meta.epochStart
        if (have < MIN_START_FRAMES && waitedMs < DATA_WAIT_MS) {
            feedIdle(t)
            return
        }
        // 2. the clock: where the track is in time
        if (!timeline.hasAnchor) {
            if (waitedMs < ANCHOR_WAIT_MS) {
                feedIdle(t)
                return
            }
            AppLog.error(TAG, "no audio timestamp after $waitedMs ms, rebuilding the track")
            broken = true
            return
        }
        // 3. the kitchen
        val coordinator = this.coordinator
        val advice = if (coordinator == null) StartAdvice.Alone else coordinator.advice(e)
        val plan: StartPlanner.Plan
        var together = false
        when (advice) {
            is StartAdvice.Pending -> {
                if (waitedMs < PREP_WAIT_MS) {
                    feedIdle(t)
                    return
                }
                AppLog.add(TAG, "the kitchen was not ready after $waitedMs ms, starting the living room alone")
                plan = StartPlanner.planImmediate(written, timeline)
            }
            is StartAdvice.Alone -> plan = StartPlanner.planImmediate(written, timeline)
            is StartAdvice.Ready -> {
                together = true
                plan = StartPlanner.planTogether(now, written, timeline, advice.lkNanos, advice.offsetNanos)
            }
        }
        if (together) {
            coordinator?.commit(e, plan)
        } else {
            coordinator?.abandon(e)
        }

        // 4. silence in front of the first frame, then the music
        var remaining = plan.firstTrackFrame - written
        val padMs = remaining * 1000 / RATE
        while (remaining > 0 && running) {
            val chunk = minOf(remaining, CHUNK_FRAMES.toLong()).toInt()
            if (!writeFrames(t, silence, chunk)) return
            remaining -= chunk
            if (remaining > 0 && ringMovedOn(e)) {
                AppLog.add(TAG, "the start of epoch $e was overtaken while padding")
                pendingEpoch = -1L
                if (together) coordinator?.abandon(e)
                return
            }
        }
        NativeBridge.lrAck(e)
        pendingEpoch = -1L
        dataFlowing = true
        runOpen = false
        state = "playing"
        lastStart = "epoch $e ${meta.kindName()}" +
            (if (together) ", together with the kitchen, tCmd in ${(plan.tCmdNanos - now) / 1_000_000L} ms" else ", alone") +
            ", pad $padMs ms, waited $waitedMs ms"
        AppLog.add(TAG, "start: $lastStart")
    }

    /** True if the epoch is not the current playing one any more. */
    private fun ringMovedOn(epoch: Long): Boolean {
        val m = RingMeta()
        if (NativeBridge.ringState(m.a) != 0) return true
        return m.epoch != epoch || !m.playing
    }

    // ---- the track -----------------------------------------------------------------------------

    private fun ensureTrack(): AudioTrack? {
        val current = track
        if (current != null && !broken && current.state == AudioTrack.STATE_INITIALIZED) return current
        return rebuild(if (current == null) "initial" else if (broken) "broken" else "uninitialised")
    }

    private fun createTrack(): AudioTrack? {
        return try {
            val minBytes = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val bytes = maxOf(minBytes, RATE * 4 * BUFFER_MS / 1000) / 4 * 4
            val created = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build(),
                )
                .setBufferSizeInBytes(bytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            if (created.state != AudioTrack.STATE_INITIALIZED) {
                created.release()
                AppLog.error(TAG, "AudioTrack did not initialise")
                null
            } else {
                bufferFrames = bytes / 4
                AppLog.add(TAG, "AudioTrack open: $bytes bytes ($bufferFrames frames, min $minBytes), session ${created.audioSessionId}")
                created
            }
        } catch (e: Exception) {
            AppLog.error(TAG, "cannot create the AudioTrack", e)
            null
        }
    }

    /** Builds a new track (the first one, or after a failure). */
    private fun rebuild(reason: String): AudioTrack? {
        val old = track
        val wasActive = dataFlowing
        // the ring frame that was heard last, for the restart
        val heardRing = if (lastHeadAtBreak >= 0) timeline.ringOfTrack(lastHeadAtBreak) else heardHintNow()
        expectAdvance = false
        track = null
        try {
            old?.release()
        } catch (e: Exception) {
            // released already
        }
        resetClockState()
        val created = createTrack()
        if (created == null) {
            state = "error"
            return null
        }
        try {
            created.setVolume(gain)
            created.play()
        } catch (e: Exception) {
            AppLog.error(TAG, "cannot start the new AudioTrack", e)
            try {
                created.release()
            } catch (e2: Exception) {
                // gone
            }
            state = "error"
            return null
        }
        track = created
        broken = false
        lastHeadAtBreak = -1L
        expectAdvance = true
        lastUnderrunCount = created.underrunCount
        if (reason != "initial") {
            recoveries++
            AppLog.add(TAG, "AudioTrack rebuilt ($reason), recovery #$recoveries")
        }
        if (wasActive) {
            dataFlowing = false
            // the music goes on from the frame that was heard last, as a new start event
            NativeBridge.lrRestart(heardRing ?: -1L)
            AppLog.add(TAG, "restarting playback from ring frame ${heardRing ?: -1L}")
        }
        state = "idle"
        return created
    }

    private fun resetClockState() {
        written = 0L
        runOpen = false
        timeline.clear()
        timeline.clearAnchor()
        filter.reset()
        flushedAtNanos = System.nanoTime()
        lastAnchorPollNanos = 0L
    }

    /** Pause, discard what is queued, play again: the room falls silent within a few milliseconds. */
    private fun flushTrack(t: AudioTrack) {
        try {
            t.pause()
            t.flush()
            t.play()
        } catch (e: IllegalStateException) {
            AppLog.error(TAG, "flush failed", e)
            broken = true
        }
        resetClockState()
        lastUnderrunCount = try {
            t.underrunCount
        } catch (e: Exception) {
            0
        }
    }

    private fun headFrames(t: AudioTrack): Long = try {
        t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
    } catch (e: Exception) {
        0L
    }

    private fun writeFrames(t: AudioTrack, data: ShortArray, frames: Int): Boolean {
        val total = frames * 2
        var offset = 0
        var zeroWrites = 0
        while (offset < total) {
            if (!running) return false
            val w = t.write(data, offset, total - offset)
            if (w < 0) {
                AppLog.error(TAG, "AudioTrack.write returned $w")
                lastHeadAtBreak = headFrames(t)
                broken = true
                return false
            }
            if (w == 0) {
                if (++zeroWrites > 500) {
                    // 500 refusals, a second or more: the watchdog will deal with it
                    return false
                }
                sleepQuietly(2)
                continue
            }
            zeroWrites = 0
            offset += w
        }
        written += frames
        return true
    }

    /** Keeps about 100 ms of silence queued, without running ahead of real time. */
    private fun feedIdle(t: AudioTrack) {
        if (written > RENUMBER_FRAMES && !dataFlowing && pendingEpoch == -1L) {
            // the 32-bit head position wraps after 27 hours: start the numbering again, now and then
            flushTrack(t)
            return
        }
        val queued = written - headFrames(t)
        if (queued > IDLE_QUEUE_FRAMES) {
            sleepQuietly(6)
            return
        }
        writeFrames(t, silence, SILENCE_CHUNK_FRAMES)
    }

    // ---- the clock -----------------------------------------------------------------------------

    private fun maintainAnchor(t: AudioTrack) {
        val now = System.nanoTime()
        if (resetFilter) {
            resetFilter = false
            filter.reset()
        }
        if (now - lastAnchorPollNanos < ANCHOR_POLL_NANOS) return
        lastAnchorPollNanos = now
        val u = try {
            t.underrunCount
        } catch (e: Exception) {
            lastUnderrunCount
        }
        if (u != lastUnderrunCount) {
            lastUnderrunCount = u
            underruns = u
            filter.reset()
            AppLog.add(TAG, "AudioTrack underrun count is $u")
        }
        val ok = try {
            t.getTimestamp(timestamp)
        } catch (e: Exception) {
            false
        }
        if (!ok) return
        val frame = timestamp.framePosition
        val nanos = timestamp.nanoTime
        if (frame <= 0 || frame > written || nanos < flushedAtNanos || nanos > now + 20_000_000L) return
        val anchor = filter.add(frame, nanos)
        timeline.setAnchor(anchor.frame, anchor.nanos, now)
    }

    /** The ring frame heard about 40 ms from now (where a pause would take effect), or -1. */
    private fun heardHint(): Long = heardHintNow() ?: -1L

    private fun heardHintNow(): Long? {
        if (!dataFlowing || !timeline.hasAnchor) return null
        return timeline.ringAtHalNanos(System.nanoTime() + HINT_AHEAD_NANOS)
    }

    private fun routeChanged(what: String, devices: Array<out AudioDeviceInfo>?) {
        routeChanges++
        resetFilter = true
        val names = devices?.joinToString { "type " + it.type + " " + it.productName } ?: ""
        AppLog.add(TAG, "audio devices $what: $names")
    }

    // ---- the watchdog --------------------------------------------------------------------------

    private fun watchdogLoop() {
        var lastHead = -1L
        var lastAdvance = System.nanoTime()
        while (running) {
            sleepQuietly(250)
            val t = track
            if (t == null || !expectAdvance || broken) {
                lastHead = -1L
                continue
            }
            val head = headFrames(t)
            val now = System.nanoTime()
            if (head != lastHead) {
                lastHead = head
                lastAdvance = now
                continue
            }
            if (now - lastAdvance > STALL_NANOS) {
                stalls++
                AppLog.error(TAG, "AudioTrack stalled: position stuck at $head for ${(now - lastAdvance) / 1_000_000L} ms (stall #$stalls)")
                lastHeadAtBreak = head
                broken = true
                try {
                    t.release() // ends a write that is blocked on the stuck track
                } catch (e: Exception) {
                    // already gone
                }
                lastHead = -1L
                lastAdvance = now
            }
        }
    }

    // ---- status --------------------------------------------------------------------------------

    fun statusJson(): JSONObject {
        val o = JSONObject()
        o.put("state", state)
        o.put("epoch", epoch)
        o.put("epoch_kind", epochKind)
        o.put("data_flowing", dataFlowing)
        o.put("frames_played", framesPlayed)
        o.put("underruns", underruns)
        o.put("stalls", stalls)
        o.put("recoveries", recoveries)
        o.put("decoder_starves", starves)
        o.put("start_events", starts)
        o.put("route_changes", routeChanges)
        o.put("buffer_ms", bufferFrames * 1000 / RATE)
        o.put("has_anchor", timeline.hasAnchor)
        val setAt = timeline.anchorSetNanos
        o.put("anchor_age_ms", if (setAt == 0L) -1L else (System.nanoTime() - setAt) / 1_000_000L)
        o.put("gain", gain.toDouble())
        o.put("last_start", lastStart)
        return o
    }

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            // the loops check `running`
        }
    }

    companion object {
        private const val TAG = "living"
        private const val RATE = 44_100
        private const val BUFFER_MS = 200
        private const val CHUNK_FRAMES = 1024
        private const val SILENCE_CHUNK_FRAMES = 1024
        private const val STARVE_CHUNK_FRAMES = 441
        private const val IDLE_QUEUE_FRAMES = 4_410
        private const val RENUMBER_FRAMES = 1_000_000_000L
        private const val READ_TIMEOUT_MS = 10
        private const val MIN_START_FRAMES = 8_192L
        private const val DATA_WAIT_MS = 4_000L
        private const val ANCHOR_WAIT_MS = 3_000L
        private const val PREP_WAIT_MS = 3_000L
        private const val ANCHOR_POLL_NANOS = 200_000_000L
        private const val HINT_AHEAD_NANOS = 40_000_000L
        private const val STALL_NANOS = 1_200_000_000L
        private const val KIND_CALIBRATION = 3
    }
}
