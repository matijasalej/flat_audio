package io.github.matijasalej.wholeflat

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import io.github.matijasalej.wholeflat.capture.CaptureFeed
import io.github.matijasalej.wholeflat.capture.MediaWatch
import io.github.matijasalej.wholeflat.sync.AnchorFilter
import io.github.matijasalej.wholeflat.sync.CalibrationClicks
import io.github.matijasalej.wholeflat.sync.ClickTrack
import io.github.matijasalej.wholeflat.sync.ReplayGate
import io.github.matijasalej.wholeflat.sync.ReplaySteer
import io.github.matijasalej.wholeflat.sync.Stretch
import org.json.JSONObject

/**
 * Synced on HDMI: the living room as a replay of the capture (see [ReplayGate]). While the Spotify TV app's own sound is muted (its
 * PLAY_AUDIO app-op), this plays what the capture took on the box's HDMI output, so that each frame sounds in the room `living_replay_ms`
 * after it was captured; the kitchen is held to the same delay (SyncedController), and the two rooms are in step without the TV's DLNA
 * player. The TV stays on its HDMI input, with the Spotify app on the screen.
 *
 * One AudioTrack at the capture's rate, fed in blocks of 10 ms by a thread of its own. The track's timestamps say when the next frame written
 * is presented at the HDMI output; it is to carry the frame captured `living_replay_ms - living_replay_tv_ms` before that (the TV takes
 * `living_replay_tv_ms` from its HDMI input to the sound). [ReplaySteer] keeps the cursor there: small errors melt by a quarter of a percent
 * of speed, big ones jump. Frames that the capture does not have go out as silence. While the click track of the calibration runs
 * (`/tv/clicks`), the TV's clicks take the place of the music at the same capture frames as the kitchen's, so `spikes/tvclicks.py` measures the
 * two rooms against each other.
 *
 * Whether to replay is looked at every second: the mute is read with AppOpsManager (reading another app's PLAY_AUDIO needs no permission),
 * the capture and the session that plays come from the app. Without a replay the track is released.
 */
class LivingReplay(
    private val context: Context,
    private val feed: CaptureFeed,
    private val captureRunning: () -> Boolean,
) {
    /** "off" (no replay), "starting" (the track runs on silence until its timestamps and the capture's clock are there) or "playing". */
    @Volatile
    var state = STATE_OFF
        private set

    @Volatile
    var why = "not started"
        private set

    /** Whether the Spotify app's own sound is muted, as last read; null: not known. */
    @Volatile
    var muted: Boolean? = null
        private set

    @Volatile
    private var decision = ReplayGate.Decision(false, "not looked at yet")

    /** The error of the last block (ms; positive: the room is early), NaN while nothing is placed. */
    @Volatile
    private var errorMs = Double.NaN

    /** How long before its presentation the last block was written (ms): the buffers of the track and of the HAL. */
    @Volatile
    private var aheadMs = Double.NaN

    /** How much captured audio there was beyond the cursor after the last block (ms); below 0 the capture did not have the frames yet. */
    @Volatile
    private var spareMs = Double.NaN

    @Volatile
    private var missingFrames = 0L

    @Volatile
    private var writtenFrames = 0L

    @Volatile
    private var stretched = 0L

    @Volatile
    private var jumps = 0

    @Volatile
    private var tracks = 0

    @Volatile
    private var writeErrors = 0

    /** Tracks that took nothing for [STUCK_MS], in all and in a row (the wait before the next one grows with them). */
    @Volatile
    private var stucks = 0
    private var stuckInARow = 0
    private var reopenAtMs = 0L

    /** When the room began to be replayed (elapsedRealtime), 0 while it is not. */
    @Volatile
    private var sinceMs = 0L

    private val steer = ReplaySteer(RATE)
    private val lock = Any()

    @Volatile
    private var running = false
    private var thread: Thread? = null
    private var track: AudioTrack? = null
    private var spotifyUid = -1
    private var readFailed = false

    /** The room is replayed (the track runs for it). */
    val replaying: Boolean get() = state != STATE_OFF

    /** The delay both rooms are held to while the room is replayed (ms after the capture); NaN while it is not. */
    fun holdTargetMs(): Double = if (replaying) Settings.values.livingReplayMs.toDouble() else Double.NaN

    /** What the phone page says while the living room cannot hear the Spotify app (it is muted and nothing is replayed); null when it can. */
    fun warning(): String? = ReplayGate.warning(decision)

    @Synchronized
    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "living-replay").also { it.start() }
    }

    @Synchronized
    fun stop() {
        running = false
        val t = thread
        thread = null
        t?.interrupt()
        try {
            t?.join(JOIN_MS)
        } catch (e: InterruptedException) {
            // shutting down anyway
        }
        closeTrack()
        state = STATE_OFF
    }

    private fun loop() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (e: Exception) {
            // the default priority then
        }
        val ts = AudioTimestamp()
        val filter = AnchorFilter(rate = RATE)
        val input = ShortArray((BLOCK + MAX_STRETCH) * 2)
        val scratch = ShortArray((BLOCK + MAX_STRETCH) * 2)
        val out = ShortArray(BLOCK * 2)
        var lookedAtMs = 0L
        var cursor = -1L
        var generation = Long.MIN_VALUE
        var written = 0L
        var anchor: AnchorFilter.Anchor? = null
        while (running) {
            val nowMs = SystemClock.elapsedRealtime()
            if (nowMs - lookedAtMs >= LOOK_EVERY_MS) {
                lookedAtMs = nowMs
                look()
            }
            if (!decision.replay) {
                if (track != null) {
                    closeTrack()
                    AppLog.add(TAG, "the replay stops: ${decision.why}")
                }
                state = STATE_OFF
                sinceMs = 0L
                cursor = -1L
                anchor = null
                filter.reset()
                steer.reset()
                errorMs = Double.NaN
                aheadMs = Double.NaN
                spareMs = Double.NaN
                sleepQuietly(IDLE_MS)
                continue
            }
            var t = track
            if (t == null) {
                // a track that took nothing (an HDMI output that sleeps or moved) is made again after 2, 4, 8 ... up to 60 s, not at once
                if (nowMs < reopenAtMs) {
                    sleepQuietly(IDLE_MS)
                    continue
                }
                t = openTrack()
                if (t == null) {
                    sleepQuietly(RETRY_MS)
                    continue
                }
                synchronized(lock) { track = t }
                written = 0L
                anchor = null
                filter.reset()
                cursor = -1L
                steer.reset()
                state = STATE_STARTING
                if (sinceMs == 0L) sinceMs = nowMs
            }
            // when the next frame written is presented: from the track's timestamps, once it plays
            if (written > 0L && t.getTimestamp(ts) && ts.framePosition > 0L) anchor = filter.add(ts.framePosition, ts.nanoTime)
            val gen = feed.buffer.generation
            if (gen != generation) {
                // a new capture: its frames are numbered afresh
                generation = gen
                cursor = -1L
                steer.reset()
            }
            val a = anchor
            val presentNanos = if (a != null) ReplaySteer.presentNanos(a.frame, a.nanos, written, RATE) else 0L
            val wanted = if (a != null) feed.clock.frameAtNanosExact(presentNanos - delayNanos()) else null
            if (wanted == null) {
                // no timestamp of the track yet (it starts on this silence), or no clock of the capture
                java.util.Arrays.fill(out, 0.toShort())
            } else {
                val plan = steer.plan(cursor, wanted, BLOCK)
                if (plan.jumped && cursor >= 0L) AppLog.add(TAG, "a jump of ${fmt((plan.from - cursor) * 1000.0 / RATE)} ms to the frame wanted")
                errorMs = if (cursor >= 0L && !plan.jumped) (cursor - wanted) * 1000.0 / RATE else 0.0
                val got = read(plan.from, generation, input, plan.inFrames, scratch)
                if (got < 0) {
                    // the frames are gone, or the capture began again: a block of silence keeps the pace, the next is placed afresh
                    java.util.Arrays.fill(out, 0.toShort())
                    cursor = -1L
                } else {
                    if (got < plan.inFrames) {
                        java.util.Arrays.fill(input, got * 2, plan.inFrames * 2, 0.toShort())
                        missingFrames += (plan.inFrames - got).toLong()
                    }
                    if (CalibrationClicks.active()) ClickTrack.fill(input, plan.inFrames, plan.from, RATE, ClickTrack.TV_HZ)
                    Stretch.linear(input, plan.inFrames, out, BLOCK)
                    cursor = plan.from + plan.inFrames
                    spareMs = (feed.buffer.writeIndex - cursor) * 1000.0 / RATE
                    aheadMs = (presentNanos - System.nanoTime()) / 1e6
                    stretched = steer.stretched
                    jumps = steer.jumps
                    if (state != STATE_PLAYING) {
                        state = STATE_PLAYING
                        AppLog.add(TAG, "the living room plays the capture ${Settings.values.livingReplayMs} ms after it was taken (the TV's own ${Settings.values.livingReplayTvMs} ms included)")
                    }
                }
            }
            val w = writeBlock(t, out)
            if (w < BLOCK) {
                // what was written of the block still counts, the track is made again
                written += w.coerceAtLeast(0).toLong()
                closeTrack()
                continue
            }
            stuckInARow = 0
            written += BLOCK.toLong()
            writtenFrames += BLOCK.toLong()
        }
        closeTrack()
    }

    /**
     * Writes the block, waiting for room in the track (not blocking: a track that takes nothing must not hold the thread). Returns the
     * frames written: all of them, or fewer when the track refused a write or took nothing for [STUCK_MS]; then it is to be made again.
     */
    private fun writeBlock(t: AudioTrack, out: ShortArray): Int {
        val total = BLOCK * 2
        var offset = 0
        var progressMs = SystemClock.elapsedRealtime()
        while (offset < total && running) {
            val n = try {
                t.write(out, offset, total - offset, AudioTrack.WRITE_NON_BLOCKING)
            } catch (e: Exception) {
                -1
            }
            val nowMs = SystemClock.elapsedRealtime()
            if (n < 0) {
                writeErrors++
                AppLog.error(TAG, "the track refused a write ($n): it is made again")
                return offset / 2
            }
            if (n > 0) {
                offset += n
                progressMs = nowMs
                continue
            }
            if (nowMs - progressMs >= STUCK_MS) {
                stuckInARow++
                stucks++
                val waitMs = minOf(REOPEN_MAX_MS, REOPEN_FIRST_MS shl minOf(stuckInARow - 1, 5))
                reopenAtMs = nowMs + waitMs
                // logged the first times, then once in ten: an HDMI output that sleeps must not fill the log overnight
                if (stuckInARow <= 3 || stuckInARow % 10 == 0) AppLog.error(TAG, "the track took nothing for $STUCK_MS ms ($stuckInARow in a row): a new one in ${waitMs / 1000} s")
                return offset / 2
            }
            sleepQuietly(WAIT_MS)
        }
        return offset / 2
    }

    /** Looks at whether the room is to be replayed: the mute of the Spotify app, the capture, another player of the capture that plays. */
    private fun look() {
        val m = readMuted()
        muted = m
        val info = MediaWatch.lastInfo
        val other = if (info != null && info.playing && info.packageName != ReplayGate.SPOTIFY_TV) info.packageName else null
        val awake = try {
            context.getSystemService(PowerManager::class.java)?.isInteractive != false
        } catch (e: Exception) {
            true
        }
        val d = ReplayGate.decide(m, captureRunning(), other, awake)
        if (d.replay != decision.replay || d.silent != decision.silent) {
            if (d.replay) AppLog.add(TAG, "the living room is replayed on HDMI: ${d.why}") else AppLog.add(TAG, "no replay: ${d.why}")
        }
        decision = d
        why = d.why
    }

    /** Whether the Spotify app's own sound is muted (its PLAY_AUDIO ignored or refused); null when that cannot be read. */
    private fun readMuted(): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            if (spotifyUid < 0) spotifyUid = context.packageManager.getPackageUid(ReplayGate.SPOTIFY_TV, 0)
            val ops = context.getSystemService(AppOpsManager::class.java) ?: return null
            when (ops.unsafeCheckOpRawNoThrow(ReplayGate.OP_PLAY_AUDIO, spotifyUid, ReplayGate.SPOTIFY_TV)) {
                AppOpsManager.MODE_IGNORED, AppOpsManager.MODE_ERRORED -> true
                else -> false
            }
        } catch (e: PackageManager.NameNotFoundException) {
            // no Spotify app on the box: nothing of it is muted
            false
        } catch (e: Exception) {
            if (!readFailed) {
                readFailed = true
                AppLog.error(TAG, "the PLAY_AUDIO of the Spotify app cannot be read", e)
            }
            null
        }
    }

    /** Reads [frames] captured frames from [from] into [into]: fewer when the capture does not have them yet, negative when they are lost. */
    private fun read(from: Long, generation: Long, into: ShortArray, frames: Int, scratch: ShortArray): Int {
        var got = 0
        while (got < frames) {
            val n = feed.buffer.read(from + got, generation, scratch, frames - got, 0)
            if (n < 0) return n
            if (n == 0) break
            System.arraycopy(scratch, 0, into, got * 2, n * 2)
            got += n
        }
        return got
    }

    private fun delayNanos(): Long {
        val v = Settings.values
        return (v.livingReplayMs - v.livingReplayTvMs).toLong() * 1_000_000L
    }

    private fun openTrack(): AudioTrack? {
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
                AppLog.error(TAG, "the replay's AudioTrack did not initialise")
                return null
            }
            created.play()
            tracks++
            AppLog.add(TAG, "the replay's AudioTrack is open: $bytes bytes (min $minBytes), session ${created.audioSessionId}")
            created
        } catch (e: Exception) {
            AppLog.error(TAG, "cannot make the replay's AudioTrack", e)
            null
        }
    }

    private fun closeTrack() {
        val t = synchronized(lock) {
            val was = track
            track = null
            was
        } ?: return
        try {
            t.pause()
            t.flush()
        } catch (e: Exception) {
            // released anyway
        }
        try {
            t.release()
        } catch (e: Exception) {
            AppLog.error(TAG, "releasing the replay's AudioTrack failed", e)
        }
    }

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            // a stop: the loop looks at running
        }
    }

    fun statusJson(): JSONObject {
        val v = Settings.values
        val o = JSONObject()
        o.put("state", state)
        o.put("why", why)
        o.put("spotify_muted", muted ?: JSONObject.NULL)
        o.put("delay_ms", v.livingReplayMs)
        o.put("tv_ms", v.livingReplayTvMs)
        o.put("for_s", if (sinceMs == 0L) JSONObject.NULL else (SystemClock.elapsedRealtime() - sinceMs) / 1000)
        o.put("error_ms", round1(errorMs))
        o.put("ahead_ms", round1(aheadMs))
        o.put("spare_ms", round1(spareMs))
        o.put("stretched_blocks", stretched)
        o.put("jumps", jumps)
        o.put("missing_ms", missingFrames * 1000L / RATE)
        o.put("written_s", writtenFrames / RATE)
        o.put("tracks", tracks)
        o.put("write_errors", writeErrors)
        o.put("stuck_tracks", stucks)
        o.put("warning", warning() ?: JSONObject.NULL)
        o.put("mute", ReplayGate.MUTE_COMMAND)
        o.put("unmute", ReplayGate.UNMUTE_COMMAND)
        return o
    }

    private fun round1(x: Double): Any = if (x.isNaN()) JSONObject.NULL else Math.round(x * 10.0) / 10.0

    private fun fmt(x: Double): String = String.format(java.util.Locale.ROOT, "%.1f", x)

    companion object {
        private const val TAG = "replay"
        const val STATE_OFF = "off"
        const val STATE_STARTING = "starting"
        const val STATE_PLAYING = "playing"

        /** The capture's rate. */
        private const val RATE = CaptureFeed.RATE

        /** One block: 10 ms. */
        private const val BLOCK = RATE / 100

        /** A block takes at most this many captured frames more than it writes (see [ReplaySteer]). */
        private const val MAX_STRETCH = 4

        /** The track's buffer (ms), as the living room's. */
        private const val BUFFER_MS = 200

        private const val LOOK_EVERY_MS = 1_000L
        private const val IDLE_MS = 500L
        private const val RETRY_MS = 2_000L
        private const val JOIN_MS = 1_500L

        /** A track that takes nothing for this long is made again (the living room's took a sleeping HDMI sink's silence for ever). */
        private const val STUCK_MS = 2_000L
        private const val REOPEN_FIRST_MS = 2_000L
        private const val REOPEN_MAX_MS = 60_000L

        /** The wait for room in the track: a block is 10 ms, so a few ms keep it fed without spinning. */
        private const val WAIT_MS = 3L
    }
}
