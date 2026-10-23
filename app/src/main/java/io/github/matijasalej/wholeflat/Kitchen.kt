package io.github.matijasalej.wholeflat

import android.content.Context
import android.content.SharedPreferences
import io.github.matijasalej.wholeflat.capture.CaptureFeed
import io.github.matijasalej.wholeflat.capture.CaptureLag
import io.github.matijasalej.wholeflat.capture.CaptureLead
import io.github.matijasalej.wholeflat.capture.CaptureStart
import io.github.matijasalej.wholeflat.capture.CaptureTarget
import io.github.matijasalej.wholeflat.capture.CaptureTracker
import io.github.matijasalej.wholeflat.capture.LagSteer
import io.github.matijasalej.wholeflat.capture.SourceArbiter
import io.github.matijasalej.wholeflat.sync.ClickTrack
import io.github.matijasalej.wholeflat.sync.DayFloor
import io.github.matijasalej.wholeflat.sync.SyncedHold
import io.github.matijasalej.wholeflat.sync.KitchenVolume
import io.github.matijasalej.wholeflat.sync.PlayDelays
import io.github.matijasalej.wholeflat.sync.SessionHistory
import io.github.matijasalej.wholeflat.sync.SettingsModel
import io.github.matijasalej.wholeflat.sync.SonosPolicy
import io.github.matijasalej.wholeflat.sync.SonosXml
import io.github.matijasalej.wholeflat.sync.StartBackoff
import io.github.matijasalej.wholeflat.sync.StartPlanner
import io.github.matijasalej.wholeflat.sync.StreamBuilder
import io.github.matijasalej.wholeflat.sync.StreamFormat
import io.github.matijasalej.wholeflat.sync.StreamView
import io.github.matijasalej.wholeflat.sync.SyncController
import io.github.matijasalej.wholeflat.sync.VolumeCurve
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The kitchen: a Sonos speaker that plays the same music in step with the living room.
 *
 * It owns the conversation with the speaker (UPnP to set the stream URL and play, mute and stop;
 * the websocket for the play position), finds the speaker and notices when it comes and goes, and
 * runs the feedback loop that keeps it in step: every ~0.2 s the Sonos position is turned into the
 * ring frame it plays, compared with when the living room plays that frame, and the difference
 * is removed by trimming single frames from the stream (see SyncController).
 *
 * Everything that changes state runs on one thread ([control]); other threads post work to it.
 * The audio thread talks to it through [StartCoordinator]: it never waits for the network.
 *
 * It also runs the capture mode: when the Cast receiver (or the Spotify TV app) plays on this box and
 * librespot does not, the kitchen follows a copy of that audio (see capture/CaptureSource.kt). The
 * [arbiter] decides which source feeds the speaker; librespot wins. A capture has no living room to be
 * in step with, so the lag of the kitchen behind the capture is measured and then held constant.
 */
class Kitchen(private val context: Context, private val living: LivingRoom) : StartCoordinator, SonosFinder.Listener, StreamHost {

    enum class State(val label: String) {
        OFF("off"),
        SEARCHING("searching"),
        OFFLINE("offline"),
        BUSY("busy"),
        READY("ready"),
        STARTING("starting"),
        PLAYING("playing"),
    }

    @Volatile
    var state = State.SEARCHING
        private set

    @Volatile
    var lastError: String? = null
        private set

    private val control = ScheduledThreadPoolExecutor(1, daemonFactory("wf-kitchen"))
    private val timer = ScheduledThreadPoolExecutor(1, daemonFactory("wf-kitchen-timer"))
    private val netPool = Executors.newSingleThreadExecutor(daemonFactory("wf-kitchen-net"))
    private val finder = SonosFinder()
    private val ws = SonosWs()
    private val ringController = SyncController()

    /**
     * The captured stream has the capture's sample rate, and its time stamps are looser than the living room's (the reads are 10 ms
     * apart), so the controller is a little more tolerant: it only has to hold the lag where it is told to.
     */
    private val captureController = SyncController(CaptureTracker.controllerParams(CaptureFeed.RATE))

    @Volatile
    private var controller = ringController
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences("wholeflat_kitchen", Context.MODE_PRIVATE)

    /** Held while the speaker is muted or unmuted, so that a late unmute cannot overtake a mute. */
    private val muteLock = Any()

    @Volatile
    private var device: SonosDevice? = null

    @Volatile
    private var session: KitchenSession? = null
    private val sessions = ConcurrentHashMap<String, KitchenSession>()
    private val advices = ConcurrentHashMap<Long, StartAdvice>()

    /** The speaker answered the last poll. */
    private var reachable = false

    /** The speaker plays something that is not ours. */
    private var busyElsewhere = false
    private var pollFailures = 0
    private var badPolls = 0
    private val polling = AtomicBoolean(false)
    private val searching = AtomicBoolean(false)
    private var lastSearchNanos = 0L
    private var lastWsConnectNanos = 0L
    private var lastRejoinNanos = 0L
    private var lastFailureNanos = 0L
    private var readingTask: ScheduledFuture<*>? = null
    private var unmuteTask: ScheduledFuture<*>? = null
    private var notPlayingReadings = 0
    private var lastSummaryReadings = 0
    private val volumeScheduled = AtomicBoolean(false)

    /** The speaker was left muted and stopped by us. */
    @Volatile
    private var sonosQuiet = false

    /**
     * The token of our stream that someone paused on the speaker itself during the session that just ended: the
     * kitchen leaves the speaker alone while that stream is still paused there. A paused stream of ours from an
     * earlier session or run is nobody's, and does not count.
     */
    @Volatile
    private var pausedOnSpeakerToken: String? = null

    /** The delay from Play to the first sound, by stream format. */
    private val delays = PlayDelays()

    @Volatile
    private var lastVolumeRaw = -1

    private var currentEpoch = -1L

    // ---- the capture mode ---------------------------------------------------------------------

    private val arbiter = SourceArbiter()
    private val lagCapture = CaptureLag()
    private val captureLead = CaptureLead()
    private val captureTracker = CaptureTracker(captureController, lagCapture, captureLead, CaptureFeed.RATE)

    /** The audio the capture hands over; set by the service. */
    @Volatile
    var captureFeed: CaptureFeed? = null

    /** The music volume of the box (the phone's Cast volume), as a Connect volume 0 to 65535, while capture mode is the source. */
    @Volatile
    private var captureVolumeRaw = -1

    @Volatile
    private var capturePlannedLagMs = Double.NaN

    /** How far before "now" the current captured stream began at Play (ms): the lead plus the kitchen offset, as far as the buffer allowed. */
    @Volatile
    private var captureAheadMs = Double.NaN

    /** How old the newest captured frame was when Play was sent for the current captured stream (ms): the capture's own part of the lag. */
    @Volatile
    private var captureAgeAtPlayMs = Double.NaN

    /** The floor that an underrun showed, kept in the prefs for the rest of the local day. */
    private val dayFloor = DayFloor(
        object : DayFloor.Store {
            override fun read(key: String): String? = prefs.getString(key, null)

            override fun write(values: Map<String, String?>) {
                val e = prefs.edit()
                for ((k, v) in values) if (v == null) e.remove(k) else e.putString(k, v)
                e.apply()
            }
        },
    ) { DayFloor.dayOf(System.currentTimeMillis(), java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()).toLong()) }

    /**
     * In Synced mode, while the TV plays the same capture: the lag the Sonos is brought to, the delay D that both rooms are held to
     * (ms after the capture). NaN: not in Synced mode, the target of the settings counts.
     */
    var syncedTargetMs: Double
        get() = syncedTarget
        set(value) {
            val was = !syncedTarget.isNaN()
            syncedTarget = value
            // a change of the mode: what the speaker showed in the other one says nothing about this one
            if (was != !value.isNaN()) post("mode change") { onModeChange(!value.isNaN()) }
        }

    @Volatile
    private var syncedTarget = Double.NaN

    /**
     * How much later than planned the speaker landed at its last starts in Synced mode (ms; the first one counts fully). A stream that
     * starts 2 s behind the capture takes the speaker longer to begin than the shallow streams the Play-to-sound delay is learned from.
     */
    @Volatile
    private var syncedPlanBiasMs = Double.NaN

    /** The time of the last fresh start that the Synced rule asked for (System.nanoTime), 0 if none. */
    private var lastSyncedRestartNanos = 0L

    /** Readings of the stream that is set by Synced mode, for the summary. */
    private var syncedReadings = 0

    /**
     * In Synced mode, while the TV plays: the lead a captured stream is started with so that the Sonos lands near the common delay at once
     * (ms ahead of the newest frame), instead of climbing there at 3 ms per second. NaN: the lead setting counts.
     */
    @Volatile
    var syncedLeadMs = Double.NaN

    /**
     * While not null the kitchen does not start a captured stream: Synced mode waits for the TV to be through its warm-up (silence and a
     * splash) so that the two rooms begin together. The text says why, for the log. A stream that is playing is not touched by it.
     */
    @Volatile
    var holdStart: String? = null

    /** A start that Synced mode scheduled for the moment the TV's music begins is still to come (the kitchen waits for it). */
    @Volatile
    private var syncedStartPending = false
    private var lastHoldLogNanos = 0L

    /** The state of the lag steering that was last put in the log, to note only its changes. */
    private var lastSteerState = LagSteer.State.OFF

    /** The last `playbackError` of the speaker that was taken for trouble, so that one error counts once. */
    private var seenPlaybackErrorNanos = 0L

    @Volatile
    private var captureStarts = 0

    @Volatile
    private var captureRelocks = 0

    /** Called on the control thread whenever the source that feeds the speaker changes. */
    @Volatile
    var onSource: ((SourceArbiter.Source) -> Unit)? = null

    // ---- status ------------------------------------------------------------------------------

    @Volatile
    var startsTogether = 0
        private set

    @Volatile
    var hotJoins = 0
        private set

    @Volatile
    var rejoins = 0
        private set

    @Volatile
    var lastRejoinReason = ""
        private set

    @Volatile
    private var rejoinsSuppressed = 0

    @Volatile
    private var readingsDiscarded = 0

    @Volatile
    private var prepFailures = 0

    @Volatile
    private var playFailures = 0

    @Volatile
    private var lastTransport = ""

    /** `CurrentTransportStatus` of the last poll ("OK", or an error the speaker names). */
    @Volatile
    private var lastTransportStatus = ""

    /** The last `playbackState` and error fields of an answer of the websocket. */
    @Volatile
    private var lastWsError = ""

    /** The last ten sessions with the connections the speaker made to their streams (`/kitchen/last-sessions`). */
    private val history = SessionHistory(10)

    /** The last speaker state that was put in the log of a session, to note only changes of it. */
    private var lastNotedWs = ""

    /** How soon a start may be tried again after failed ones (see [StartBackoff]). */
    private val backoff = StartBackoff()
    private var lastBackoffLogNanos = 0L

    @Volatile
    private var startFailuresTotal = 0

    @Volatile
    private var lastReadingNanos = 0L

    @Volatile
    private var lastPositionMs = -1L

    @Volatile
    private var lastWsState = ""

    @Volatile
    private var lastRttMs = 0.0

    /** Called when the kitchen joined while the living room was playing ("Kitchen connected"). */
    @Volatile
    var onJoined: (() -> Unit)? = null

    private val settingsListener: (Set<String>) -> Unit = { changed -> post("settings") { onSettings(changed) } }

    // ---- life cycle ----------------------------------------------------------------------------

    fun start() {
        StreamRouter.add(this)
        captureTracker.onFloor = { e ->
            dayFloor.save(e.floorMs)
            AppLog.add(
                TAG,
                "capture: the speaker ran out of audio at a lag of ${fmt(e.lagMs)} ms (${e.why}); the lag will not be brought below " +
                    "${fmt(e.floorMs)} ms for the rest of today (floor event ${captureTracker.steer.floorEvents}), the stream starts again",
            )
        }
        dayFloor.load()?.let {
            captureTracker.steer.restoreLearnedFloor(it)
            AppLog.add(TAG, "capture: the floor of the lag from earlier today is ${fmt(it)} ms")
        }
        finder.listener = this
        finder.startNotifyListener()
        Settings.addListener(settingsListener)
        controller.offsetMs = Settings.values.kitchenOffsetMs.toDouble()
        state = baseState()
        control.scheduleWithFixedDelay(Runnable { post("tick") { tick() } }, 500, POLL_PERIOD_MS, TimeUnit.MILLISECONDS)
        AppLog.add(TAG, "kitchen started, ${if (Settings.values.kitchenEnabled) "enabled" else "switched off"}")
    }

    fun stop() {
        StreamRouter.remove(this)
        Settings.removeListener(settingsListener)
        finder.stop()
        post("stop") {
            readingTask?.cancel(false)
            session?.let { endSession(it, "app stopping", true) }
        }
        ws.close()
        control.shutdown()
        timer.shutdown()
        netPool.shutdown()
    }

    /** The engine was restarted: the ring is a new one, the streams of the old one are void. */
    fun engineReset() {
        advices.clear()
        post("engine reset") {
            // a restarted engine plays nothing: a stale "librespot plays" must not keep the capture out
            val change = arbiter.librespotPlaying(false)
            session?.takeIf { !it.isCapture }?.let { endSession(it, "engine restarted", true) }
            noteSource(change)
            if (arbiter.source == SourceArbiter.Source.CAPTURE) startCaptureSession("the engine restarted", true)
        }
    }

    private fun post(name: String, block: () -> Unit) {
        try {
            control.execute {
                try {
                    block()
                } catch (t: Throwable) {
                    AppLog.error(TAG, "$name failed", t)
                    lastError = "$name: $t"
                }
            }
        } catch (e: Exception) {
            // shut down
        }
    }

    private fun later(ms: Long, block: () -> Unit): ScheduledFuture<*> =
        control.schedule(Runnable { post("later") { block() } }, ms, TimeUnit.MILLISECONDS)

    // ---- StartCoordinator (called by the living room's audio thread) ----------------------------

    override fun begin(epoch: Long, kind: Int, startRingFrame: Long) {
        val stale = advices.entries.iterator()
        while (stale.hasNext()) {
            if (stale.next().key < epoch) stale.remove()
        }
        advices[epoch] = StartAdvice.Pending
        post("begin") { doBegin(epoch, kind, startRingFrame) }
    }

    override fun advice(epoch: Long): StartAdvice = advices[epoch] ?: StartAdvice.Alone

    override fun commit(epoch: Long, plan: StartPlanner.Plan) {
        advices.remove(epoch)
        post("commit") { doCommit(epoch, plan) }
    }

    override fun abandon(epoch: Long) {
        advices.remove(epoch)
        post("abandon") {
            val s = session
            if (s != null && s.epoch == epoch && !s.armed) endSession(s, "the start went ahead without the kitchen", true)
        }
    }

    override fun playbackStopped() {
        post("stopped") {
            val change = arbiter.librespotPlaying(false)
            session?.takeIf { !it.isCapture }?.let { endSession(it, "playback stopped", true) }
            noteSource(change)
            // librespot is quiet and the captured player still plays: the capture takes over
            if (arbiter.source == SourceArbiter.Source.CAPTURE) startCaptureSession("librespot stopped", true)
        }
    }

    // ---- start events --------------------------------------------------------------------------

    private fun doBegin(epoch: Long, kind: Int, startFrame: Long) {
        currentEpoch = epoch
        // librespot wins: a capture session in progress ends here, like any session at a new start event
        noteSource(arbiter.librespotPlaying(true))
        val old = session
        if (old != null) endSession(old, "new start event", true)
        val dev = device
        val v = Settings.values
        if (!v.kitchenEnabled || dev == null || !reachable || busyElsewhere || !startAllowed("start $epoch")) {
            advices[epoch] = StartAdvice.Alone
            return
        }
        val s = KitchenSession(newToken(), epoch, kind, startFrame, channelsFor(kind), format = streamFormat(capture = false), flacBlock = v.kitchenFlacBlock)
        val host = Net.localAddressFor(dev.ip)
        if (host == null) {
            advices[epoch] = StartAdvice.Alone
            return
        }
        s.uri = streamUri(host, s)
        registerSession(s)
        state = State.STARTING
        if (!prepare(s, soapFor(dev))) {
            advices[epoch] = StartAdvice.Alone
            return
        }
        if (s.cancelled || session !== s || currentEpoch != epoch) {
            advices[epoch] = StartAdvice.Alone
            return
        }
        advices[epoch] = StartAdvice.Ready(delays.nanos(s.format), v.kitchenOffsetMs * 1_000_000L)
    }

    private fun doCommit(epoch: Long, plan: StartPlanner.Plan) {
        val s = session
        if (s == null || s.epoch != epoch || s.cancelled) return
        startsTogether++
        val delayMs = (plan.tCmdNanos - System.nanoTime()) / 1_000_000L
        AppLog.add(TAG, "start $epoch: Play in $delayMs ms${if (plan.delayed) " (held back for the living room)" else ""}")
        timer.schedule(Runnable { sendPlay(s, plan.tCmdNanos) }, maxOf(0L, delayMs - 3L), TimeUnit.MILLISECONDS)
    }

    /**
     * What the speaker holds right now, asked afresh (the last poll can be seconds old): [Availability.BUSY] for
     * something that plays, and whether a paused foreign session has to be taken over.
     */
    private class Holder(val kind: SonosPolicy.Availability, val takeover: Boolean, val uri: String)

    private fun whoHolds(api: SonosSoap): Holder {
        val t = api.transportInfo()
        if (!t.ok) return Holder(SonosPolicy.Availability.FREE, false, "") // the steps that follow fail on their own if it is gone
        val state = SonosXml.tag(t.body, "CurrentTransportState") ?: ""
        if (state == "STOPPED" || state == "NO_MEDIA_PRESENT") return Holder(SonosPolicy.Availability.FREE, false, "")
        val p = api.positionInfo()
        val uri = if (p.ok) SonosXml.tag(p.body, "TrackURI") ?: "" else ""
        return Holder(SonosPolicy.classify(state, uri), SonosPolicy.needsTakeover(state, uri), uri)
    }

    /**
     * A paused session of someone else (a Spotify Connect one, `x-sonos-vli:...`) holds the transport and ignores a
     * UPnP Stop. Switching the speaker to its own queue lets go of it; the stream is set after that.
     */
    private fun takeOver(api: SonosSoap, heldUri: String) {
        val queue = SonosPolicy.queueUri(device?.udn ?: "")
        if (queue == null) {
            AppLog.add(TAG, "a paused session holds the speaker ($heldUri) but its player id is unknown, setting our stream over it")
            return
        }
        val a = api.setUri(queue, "")
        val b = api.stop()
        AppLog.add(TAG, "took over a paused session ($heldUri): own queue ${a.describe()}, stop ${b.describe()}")
    }

    /** Sets the speaker up for a stream: muted, stopped, at the right volume, URL loaded. False if that failed. */
    private fun prepare(s: KitchenSession, api: SonosSoap): Boolean {
        unmuteTask?.cancel(false)
        val holder = whoHolds(api)
        if (holder.kind == SonosPolicy.Availability.BUSY) {
            // it started to play something else since the last poll: not ours to take, not a failed start
            AppLog.add(TAG, "the speaker plays something else now (${holder.uri}), leaving it alone")
            busyElsewhere = true
            endSession(s, "the speaker plays something else", false)
            state = baseState()
            return false
        }
        synchronized(muteLock) {
            if (!sonosQuiet) {
                val m = api.setMute(true)
                if (!m.ok) {
                    prepFailed(s, "SetMute", m)
                    return false
                }
                api.stop() // it may be stopped already: the answer does not matter
                sonosQuiet = true
            }
        }
        if (holder.takeover) takeOver(api, holder.uri)
        val volume = sonosVolume()
        if (volume >= 0) api.setVolume(volume)
        val didl = SonosXml.didlTrack(TITLE, s.uri, s.format.mime)
        var set = api.setUri(s.uri, didl)
        var tries = 1
        while (!set.ok && set.upnpError == 701 && tries < 3) {
            // "transition not available": the Stop from a moment ago is still being worked off
            sleepQuietly(80)
            set = api.setUri(s.uri, didl)
            tries++
        }
        if (!set.ok) {
            prepFailed(s, "SetAVTransportURI", set)
            return false
        }
        return true
    }

    private fun prepFailed(s: KitchenSession, what: String, r: SonosSoap.Result) {
        prepFailures++
        lastError = "$what: ${r.describe()}"
        lastFailureNanos = System.nanoTime()
        AppLog.error(TAG, "preparing the speaker failed at $what: ${r.describe()}")
        startFailed(s, "$what: ${r.describe()}")
        endSession(s, "$what failed", false)
    }

    /** On the timer thread: sends Play at [tCmd] and unmutes just before the first sound. */
    private fun sendPlay(s: KitchenSession, tCmd: Long) {
        while (System.nanoTime() < tCmd && !s.cancelled) Thread.yield()
        if (s.cancelled) return
        val dev = device ?: return
        val api = soapFor(dev)
        val tSent = System.nanoTime()
        s.playSentNanos = tSent
        // a captured stream is placed now, at the moment of Play: its lead is counted back from the newest frame of this very instant
        if (s.isCapture && !replanCapture(s, tSent)) return
        s.record?.playSent(tSent)
        val lk = delays.nanos(s.format)
        s.arm(tSent + lk)
        var result = api.play()
        var attempts = 1
        while (!result.ok && result.upnpError == 701 && attempts < 3 && !s.cancelled) {
            // "transition not available": the previous Stop is still being worked off
            sleepQuietly(60)
            result = api.play()
            attempts++
        }
        if (!result.ok) {
            post("play failed") { playFailed(s, result.describe()) }
            return
        }
        post("after play") { afterPlay(s) }
        // the first sound is lk after Play; unmute 30 ms before it
        val unmuteAt = tSent + lk - UNMUTE_LEAD_NANOS
        while (System.nanoTime() < unmuteAt && !s.cancelled) sleepQuietly(5)
        synchronized(muteLock) {
            if (!s.cancelled) {
                val m = api.setMute(false)
                if (m.ok) sonosQuiet = false
            }
        }
    }

    private fun playFailed(s: KitchenSession, why: String) {
        playFailures++
        lastError = "Play: $why"
        lastFailureNanos = System.nanoTime()
        AppLog.error(TAG, "Play failed: $why")
        startFailed(s, "Play: $why")
        endSession(s, "Play failed", true)
    }

    private fun afterPlay(s: KitchenSession) {
        if (session !== s || s.cancelled) return
        state = State.PLAYING
        controller = if (s.isCapture) captureController else ringController
        controller.reset()
        controller.offsetMs = effectiveOffsetMs()
        if (s.isCapture) {
            captureTracker.startStream(capturePlannedLagMs)
            syncedReadings = 0
            // an error of the speaker from before this stream is not trouble of it
            seenPlaybackErrorNanos = ws.lastPlaybackErrorNanos
            lastSteerState = LagSteer.State.OFF
        }
        notPlayingReadings = 0
        lastSummaryReadings = 0
        badPolls = 0
        readingTask?.cancel(false)
        readingTask = control.scheduleWithFixedDelay(
            Runnable { post("reading") { requestReading(s) } },
            300,
            READING_PERIOD_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    /** Hot-join: the kitchen starts while the living room plays. */
    private fun doHotJoin(why: String): Boolean {
        val dev = device ?: return false
        val v = Settings.values
        if (!v.kitchenEnabled || session != null || !reachable || busyElsewhere) return false
        if (!living.dataFlowing) return false
        if (!startAllowed("joining the kitchen")) return false
        val meta = RingMeta()
        if (NativeBridge.ringState(meta.a) != 0 || !meta.playing || meta.startPending) return false
        val host = Net.localAddressFor(dev.ip) ?: return false
        val s = KitchenSession(newToken(), meta.epoch, meta.kind, -1L, channelsFor(meta.kind), format = streamFormat(capture = false), flacBlock = v.kitchenFlacBlock)
        s.uri = streamUri(host, s)
        registerSession(s)
        currentEpoch = meta.epoch
        state = State.STARTING
        AppLog.add(TAG, "joining the kitchen ($why), session ${s.token}")
        if (!prepare(s, soapFor(dev))) return false
        if (s.cancelled || session !== s) return false

        // the frame the living room plays when the kitchen's first sound arrives
        val offsetNanos = v.kitchenOffsetMs * 1_000_000L
        val tCmd = System.nanoTime() + HOT_JOIN_LEAD_NANOS
        val frame = StartPlanner.hotJoinFrame(living.timeline, tCmd, delays.nanos(s.format), offsetNanos)
        val fresh = RingMeta()
        val ringOk = NativeBridge.ringState(fresh.a) == 0 && fresh.epoch == meta.epoch && !fresh.startPending
        if (frame == null || !ringOk || frame < fresh.validStart + JOIN_MARGIN_FRAMES || frame > fresh.writeIdx) {
            prepFailures++
            lastError = "hot-join: no usable ring frame ($frame)"
            lastFailureNanos = System.nanoTime()
            AppLog.add(TAG, "hot-join: no usable ring frame ($frame, ring ${fresh.validStart}..${fresh.writeIdx}), trying again later")
            endSession(s, "no ring frame to join at", true)
            return false
        }
        s.startRingFrame = frame
        hotJoins++
        timer.schedule(
            Runnable { sendPlay(s, tCmd) },
            maxOf(0L, (tCmd - System.nanoTime()) / 1_000_000L - 3L),
            TimeUnit.MILLISECONDS,
        )
        return true
    }

    private fun maybeHotJoin(why: String) {
        if (System.nanoTime() - lastFailureNanos < FAILURE_COOLDOWN_NANOS) return
        if (state != State.READY) return
        if (doHotJoin(why)) onJoinedPending = true
    }

    /** Set when a hot-join started; the toast comes with the first reading that shows sound. */
    private var onJoinedPending = false

    // ---- feedback ------------------------------------------------------------------------------

    private fun requestReading(s: KitchenSession) {
        if (session !== s || s.cancelled) {
            readingTask?.cancel(false)
            return
        }
        if (!ws.isOpen) {
            connectWsIfNeeded()
            return
        }
        ws.requestPosition(
            { p -> post("position") { handleReading(s, p) } },
            { reason -> lastError = "websocket: $reason" },
        )
    }

    private fun handleReading(s: KitchenSession, p: SonosWs.Position) {
        if (session !== s || s.cancelled || !s.armed) return
        val rttNanos = p.receivedNanos - p.sentNanos
        lastRttMs = rttNanos / 1e6
        lastReadingNanos = System.nanoTime()
        lastPositionMs = p.positionMs
        lastWsState = p.state
        lastWsError = p.error
        if (p.state + "|" + p.error != lastNotedWs) {
            lastNotedWs = p.state + "|" + p.error
            noteSpeaker(lastTransport, lastTransportStatus, s.uri, p.state, p.error)
        }
        if (s.isCapture) noteCaptureTrouble(p)
        if (p.state != "PLAYBACK_STATE_PLAYING" || p.positionMs < 0) {
            notPlayingReadings++
            val sincePlay = System.nanoTime() - s.playSentNanos
            if (notPlayingReadings >= NOT_PLAYING_LIMIT && sincePlay > NOT_PLAYING_GRACE_NANOS) {
                notPlayingReadings = 0
                rejoin("the speaker reports ${p.state}")
            }
            return
        }
        notPlayingReadings = 0
        val stream = s.stream
        if (stream == null) {
            // It plays, but no connection came after Play: it plays from a probe, or from nothing. Give it a moment.
            if (System.nanoTime() - s.playSentNanos > NO_STREAM_GRACE_NANOS) {
                AppLog.add(TAG, "the speaker plays but has not asked for the stream after Play (${s.connectionCount} GET so far)")
                rejoin("the speaker plays but never asked for the stream after Play")
            }
            return
        }
        if (rttNanos > MAX_RTT_NANOS) {
            // the mid-point of a slow round trip is not worth much
            readingsDiscarded++
            return
        }
        val clock = s.clock ?: return
        val tMid = (p.sentNanos + p.receivedNanos) / 2
        val heard = (p.positionMs - controller.deltaMs) / 1000.0 * s.rate
        clock.refine(tMid, heard)
        s.playingReadings++
        if (!s.healthy && s.playingReadings >= HEALTHY_READINGS && System.nanoTime() - s.playSentNanos >= HEALTHY_AFTER_NANOS) {
            // it went on well for a while: the failed starts before it are forgotten
            s.healthy = true
            backoff.onHealthy()
        }
        // the Play-to-sound delay is learned from the shallow streams; a stream that starts 2 s behind the capture is another case (syncedPlanBiasMs)
        if (s.playingReadings == LEARN_LK_AT && !s.syncedHold) learnLk(s, clock.startNanos)
        if (s.isCapture) {
            if (s.syncedHold) handleSyncedReading(s, stream, clock.startNanos, tMid, p.positionMs) else handleCaptureReading(s, stream, clock.startNanos, tMid, p.positionMs)
            return
        }
        if (onJoinedPending && s.playingReadings >= 2) {
            onJoinedPending = false
            AppLog.add(TAG, "kitchen connected")
            onJoined?.invoke()
        }
        val view = StreamView(stream.map, { stream.builder.unappliedBudget() }, { f -> living.timeline.halNanosOfRing(f) })
        val decision = controller.onReading(tMid, p.positionMs, view)
        when (decision) {
            is SyncController.Decision.Trim -> {
                stream.builder.addTrim(decision.frames)
                AppLog.add(TAG, "trim ${decision.frames} frames (median error ${fmt(controller.errorMs)} ms)")
            }
            is SyncController.Decision.Rejoin -> rejoin(decision.reason)
            is SyncController.Decision.Hold -> Unit
        }
        val n = controller.readings
        if (n > 0 && n % SUMMARY_EVERY_READINGS == 0 && n != lastSummaryReadings) {
            lastSummaryReadings = n
            AppLog.add(
                TAG,
                "sync: error ${fmt(controller.errorMs)} ms (last ${fmt(controller.rawErrorMs)}), drift ${fmt(controller.driftPpm)} ppm, " +
                    "trimmed ${controller.trimmedFrames} frames, re-joins $rejoins, round trip ${fmt(lastRttMs)} ms",
            )
        }
    }

    private fun fmt(v: Double): String = if (v.isNaN()) "-" else String.format(Locale.US, "%.1f", v)

    /** The measured delay from Play to the first sound of this stream's format, as it came out of the readings, smoothed over the starts. */
    private fun learnLk(s: KitchenSession, observedStartNanos: Long) {
        val observed = observedStartNanos - s.playSentNanos
        val before = delays.nanos(s.format)
        val now = delays.learn(s.format, observed)
        if (now == null) {
            AppLog.add(TAG, "Play to sound looks like ${observed / 1_000_000L} ms, not used")
            return
        }
        AppLog.add(TAG, "Play to sound (${s.format.key}): ${observed / 1_000_000L} ms (was ${before / 1_000_000L}, now ${now / 1_000_000L})")
    }

    /**
     * Only the kitchen's own stream is restarted; the living room never notices. Unless [manual] (asked for on the
     * debug page, a changed setting) it counts as a failed start of the session it ends, if that never went well.
     */
    private fun rejoin(reason: String, manual: Boolean = false) {
        val now = System.nanoTime()
        if (now - lastRejoinNanos < REJOIN_COOLDOWN_NANOS) {
            rejoinsSuppressed++
            return
        }
        lastRejoinNanos = now
        rejoins++
        lastRejoinReason = reason
        AppLog.add(TAG, "re-join #$rejoins: $reason")
        if (!manual) session?.let { startFailed(it, reason) }
        val wasCapture = session?.isCapture == true
        session?.let { endSession(it, "re-join: $reason", true) }
        controller.allowRejoin = false
        val c = controller
        later(REJOIN_QUIET_MS) { c.allowRejoin = true }
        val again = if (wasCapture) startCaptureSession("re-join", true) else doHotJoin("re-join")
        if (!again) {
            AppLog.add(TAG, "re-join: could not start again now, the poll will try")
        }
    }

    /** The stream thread found that its frames were gone. */
    override fun streamLost(s: KitchenSession, reason: String) {
        post("stream lost") {
            if (session === s && !s.cancelled) rejoin("stream: $reason")
        }
    }

    // ---- capture mode ---------------------------------------------------------------------------

    /** Sound began in the captured player (or its media session says it will play): get the speaker ready. */
    fun captureCandidate() = post("capture candidate") { onCaptureCandidate() }

    /** It was a blip. */
    fun captureCandidateCancelled() = post("capture candidate cancelled") { onCaptureCancelled() }

    /** The captured player plays (it started, or went on after a pause). */
    fun captureStarted() = post("capture started") { onCaptureStarted() }

    /** The captured player has been silent for the whole hold. */
    fun captureIdle() = post("capture idle") { onCaptureIdle() }

    /** The capture is gone (the projection was lost, or the capture was switched off). */
    fun captureLost(reason: String) = post("capture lost") { onCaptureLost(reason) }

    private fun noteSource(change: SourceArbiter.Change?) {
        if (change == null) return
        AppState.source = change.to.label
        AppLog.add(TAG, "source: ${change.from.label} -> ${change.to.label}")
        try {
            onSource?.invoke(change.to)
        } catch (t: Throwable) {
            AppLog.error(TAG, "the source hook failed", t)
        }
    }

    private fun onCaptureCandidate() {
        if (arbiter.source == SourceArbiter.Source.LIBRESPOT || session != null) return
        startCaptureSession("sound began", false)
    }

    private fun onCaptureCancelled() {
        val s = session ?: return
        if (s.isCapture && !s.armed && s.startRingFrame < 0 && arbiter.source != SourceArbiter.Source.CAPTURE) {
            endSession(s, "it was a blip", true)
        }
    }

    private fun onCaptureStarted() {
        noteSource(arbiter.captureActive(true))
        if (arbiter.source != SourceArbiter.Source.CAPTURE) return // librespot wins
        val s = session
        if (s == null) {
            startCaptureSession("the player plays", true)
        } else if (s.isCapture && !s.armed && s.startRingFrame < 0) {
            playCapture(s)
        }
    }

    private fun onCaptureIdle() {
        val change = arbiter.captureActive(false)
        session?.takeIf { it.isCapture }?.let { endSession(it, "silent for the whole hold", true) }
        noteSource(change)
    }

    private fun onCaptureLost(reason: String) {
        val change = arbiter.captureLost()
        session?.takeIf { it.isCapture }?.let { endSession(it, "the capture was lost ($reason)", true) }
        noteSource(change)
    }

    /** The speaker is free, the captured player plays and nothing streams: join (a failed start is tried again by the poll). */
    private fun maybeCaptureJoin(why: String) {
        if (System.nanoTime() - lastFailureNanos < FAILURE_COOLDOWN_NANOS) return
        if (state != State.READY || session != null) return
        if (arbiter.source != SourceArbiter.Source.CAPTURE) return
        startCaptureSession(why, true)
    }

    /**
     * Gets the speaker ready for the captured stream (mute, stop, volume, URL) and, with [play], starts it.
     * False if the kitchen cannot take it now.
     */
    private fun startCaptureSession(why: String, play: Boolean): Boolean {
        val feed = captureFeed ?: return false
        val waiting = holdStart ?: if (syncedStartPending) "the start that is timed for the TV's music is to come" else null
        if (waiting != null && session == null) {
            val nanos = System.nanoTime()
            if (nanos - lastHoldLogNanos > 5_000_000_000L) {
                lastHoldLogNanos = nanos
                AppLog.add(TAG, "capture: the start is held back ($why): $waiting")
            }
            return false
        }
        val dev = device ?: return false
        val v = Settings.values
        if (!v.kitchenEnabled || !reachable || busyElsewhere) return false
        if (arbiter.source == SourceArbiter.Source.LIBRESPOT) return false
        if (session == null && !startAllowed("capture start")) return false
        var s = session
        if (s != null) {
            if (!s.isCapture) return false
        } else {
            val host = Net.localAddressFor(dev.ip) ?: return false
            s = KitchenSession(
                newToken(), CAPTURE_EPOCH, KIND_CAPTURE, -1L, StreamBuilder.ChannelMode.STEREO, feed, feed.generation,
                format = streamFormat(capture = true), flacBlock = v.kitchenFlacBlock,
            )
            s.uri = streamUri(host, s)
            s.clickHz = ClickTrack.KITCHEN_HZ
            s.syncedHold = !syncedTargetMs.isNaN()
            registerSession(s)
            state = State.STARTING
            AppLog.add(TAG, "capture: getting the kitchen ready ($why), session ${s.token}${if (s.syncedHold) ", set once at the common delay" else ""}")
            if (!prepare(s, soapFor(dev))) return false
            if (s.cancelled || session !== s) return false
        }
        if (play) playCapture(s)
        return true
    }

    /**
     * The part of the lag of a captured stream that the lead does not make: the Play-to-sound delay of the speaker for streams of
     * [format], and the capture pipeline (how old a frame is when it has been read, from the timestamps of AudioRecord; 0 without them).
     */
    private fun fixedLagMs(feed: CaptureFeed, format: StreamFormat): Double {
        val pipeline = feed.pipelineMs()
        return delays.ms(format) + (if (pipeline.isNaN()) 0.0 else pipeline)
    }

    /** The lead a captured stream is started with: the setting (or what was learned), or in Synced mode what puts the lag at the common delay. */
    private fun effectiveLeadMs(settingMs: Int): Int {
        val base = captureLead.effectiveMs(settingMs)
        val synced = syncedLeadMs
        if (synced.isNaN()) return base
        val bias = if (syncedPlanBiasMs.isNaN()) 0.0 else syncedPlanBiasMs
        return maxOf(base, Math.round(synced - bias).toInt().coerceAtMost(SettingsModel.MAX_CAPTURE_LEAD_MS))
    }

    /** The part of the lag of a captured WAV stream that the lead does not make (ms): the Play-to-sound delay and the capture pipeline. */
    fun fixedLagMs(): Double {
        val feed = captureFeed ?: return delays.ms(StreamFormat.WAV)
        return fixedLagMs(feed, StreamFormat.WAV)
    }

    /** The lag the kitchen is to have now (ms after the capture): the common delay of Synced mode, else the target of the settings; NaN if none. */
    fun currentTargetMs(): Double = currentTarget()?.ms ?: Double.NaN

    @Volatile
    private var volumeReadNanos = 0L

    @Volatile
    private var volumeRead = -1

    /**
     * The volume of the Sonos for the slider of the phone page (0 to 100): the kitchen volume setting if one is set, else what the speaker
     * says (read over the network, kept for 3 s); null if it cannot be read. Blocks for the length of the read.
     */
    fun sonosVolumeNow(): Int? {
        val set = Settings.values.kitchenVolume
        if (set >= 0) return set
        val nanos = System.nanoTime()
        if (volumeReadNanos != 0L && nanos - volumeReadNanos < 3_000_000_000L) return volumeRead.takeIf { it >= 0 }
        val dev = device
        if (dev == null || !reachable) return null
        val v = try {
            soapFor(dev).getVolume()
        } catch (t: Throwable) {
            null
        }
        volumeReadNanos = System.nanoTime()
        volumeRead = v ?: -1
        return v
    }

    /** The delay of the speaker from Play to its sound for a WAV stream (ms): what Synced mode counts with when it times a start. */
    fun playToSoundMs(): Double = delays.ms(StreamFormat.WAV)

    /**
     * Synced mode: the TV's music begins in [delayMs] (the TV is through its warm-up); the kitchen's stream is started, or started again
     * if it is playing, then, with the lead that puts it at the common delay (set in [syncedLeadMs]), so that the two rooms begin together.
     */
    fun syncedStartAfter(delayMs: Long, why: String) {
        syncedStartPending = true
        later(maxOf(0L, delayMs)) {
            syncedStartPending = false
            holdStart = null
            lastRejoinNanos = 0L
            val s = session
            if (s?.isCapture == true) {
                rejoin(why, manual = true)
            } else if (s == null && arbiter.source == SourceArbiter.Source.CAPTURE) {
                startCaptureSession(why, true)
            }
        }
    }

    /** The lag of the kitchen now (ms after the capture), NaN if no captured stream is locked. */
    fun lagNowMs(): Double = if (session?.isCapture == true && lagCapture.isLocked) lagCapture.recentMs() else Double.NaN

    /** Starts the kitchen's stream again, as a manual re-join does (no failed start): its lag is to be set from the start. */
    fun restartStream(reason: String) {
        post("restart") {
            lastRejoinNanos = 0L
            if (session?.isCapture == true) rejoin(reason, manual = true)
        }
    }

    /** The plan for a captured stream that Play is sent for at [tNanos]: where it begins and how far behind the capture it is expected to sound. */
    private fun capturePlan(feed: CaptureFeed, tNanos: Long, format: StreamFormat): CaptureStart.Plan {
        val v = Settings.values
        return CaptureStart.plan(
            nowFrame = feed.frameAtReadClock(tNanos),
            writeIndex = feed.buffer.writeIndex,
            validStart = feed.buffer.validStart,
            rate = feed.rate,
            leadMs = effectiveLeadMs(v.captureLeadMs),
            lkMs = fixedLagMs(feed, format),
        )
    }

    /** Picks the frame the stream begins at and sends Play shortly. The speaker has been prepared. */
    private fun playCapture(s: KitchenSession) {
        val feed = s.capture ?: return
        if (s.cancelled || s.armed || s.startRingFrame >= 0) return // Play is on its way already
        if (s.captureGeneration != feed.generation) {
            endSession(s, "the capture started again", true)
            return
        }
        val tCmd = System.nanoTime() + CAPTURE_PLAY_LEAD_NANOS
        // the plan is made again at the moment Play is sent (see [replanCapture]); this one marks the session as started
        val plan = capturePlan(feed, tCmd, s.format)
        s.startRingFrame = plan.startFrame
        capturePlannedLagMs = plan.plannedLagMs
        captureAheadMs = plan.aheadFrames * 1000.0 / feed.rate
        captureStarts++
        AppLog.add(TAG, "capture: Play in ${(tCmd - System.nanoTime()) / 1_000_000L} ms, planned lag ${fmt(plan.plannedLagMs)} ms")
        timer.schedule(
            Runnable { sendPlay(s, tCmd) },
            maxOf(0L, (tCmd - System.nanoTime()) / 1_000_000L - 3L),
            TimeUnit.MILLISECONDS,
        )
    }

    /**
     * On the timer thread, as Play goes out: places the captured stream again with the clock of this instant, so that the
     * lead is exactly the lead (counted back from the newest frame now, not from a guess made a few ms earlier), and notes
     * how old that newest frame is (the capture's own delay). False if the capture was restarted meanwhile: no Play then.
     */
    private fun replanCapture(s: KitchenSession, tSent: Long): Boolean {
        val feed = s.capture ?: return false
        if (feed.generation != s.captureGeneration) {
            post("capture changed") { if (session === s) endSession(s, "the capture started again", true) }
            return false
        }
        val plan = capturePlan(feed, tSent, s.format)
        s.startRingFrame = plan.startFrame
        capturePlannedLagMs = plan.plannedLagMs
        captureAheadMs = plan.aheadFrames * 1000.0 / feed.rate
        val age = feed.markPlay(tSent)
        captureAgeAtPlayMs = age
        AppLog.add(
            TAG,
            "capture: Play sent, the stream begins ${plan.aheadFrames * 1000L / feed.rate} ms before the newest frame " +
                "(frame ${plan.startFrame}${if (plan.limited) ", limited" else ""}), planned lag ${fmt(plan.plannedLagMs)} ms, " +
                "the newest frame is ${fmt(age)} ms old (${feed.clockSource} clock)",
        )
        return true
    }

    /**
     * A reading of the Sonos for a captured stream that Synced mode set at the common delay: the first readings measure the lag, then
     * the stream is placed at the target once and left alone (see [SyncedHold]); only an error that stays beyond 40 ms for 30 s is
     * corrected, at most once a minute. Nothing here steers, learns a floor or runs the controller of the Lyrics mode.
     */
    private fun handleSyncedReading(s: KitchenSession, stream: ActiveStream, startNanos: Long, tMid: Long, positionMs: Long) {
        val feed = s.capture ?: return
        if (feed.generation != s.captureGeneration) return // the session is about to end
        val target = syncedTargetMs
        // the TV has been given back: the next tick of Synced mode starts the stream again for the Lyrics mode
        if (target.isNaN()) return
        val hold = captureTracker.hold
        hold.restartAllowed = lastSyncedRestartNanos == 0L || System.nanoTime() - lastSyncedRestartNanos > SYNCED_RESTART_PAUSE_NANOS
        val outcome = captureTracker.onSyncedReading(tMid, positionMs, stream.map, { stream.builder.unappliedBudget() }, feed.clock, target)
        recordReading(tMid, positionMs, captureTracker.lastLagMs, lagCapture.recentMs(CaptureTracker.SYNCED_READINGS), hold.errorMs, target)
        when (outcome) {
            is CaptureTracker.Outcome.Locked -> {
                val planned = capturePlannedLagMs
                if (!planned.isNaN() && Math.abs(outcome.lagMs - planned) < SYNCED_BIAS_MAX_MS) {
                    val observed = outcome.lagMs - planned
                    syncedPlanBiasMs = if (syncedPlanBiasMs.isNaN()) observed else syncedPlanBiasMs * 0.4 + observed * 0.6
                }
                AppLog.add(
                    TAG,
                    "synced: the kitchen sounds ${fmt(outcome.lagMs)} ms after the capture (planned ${fmt(planned)} ms, Play to sound " +
                        "${(startNanos - s.playSentNanos) / 1_000_000L} ms); the common delay with the kitchen offset is ${fmt(target)} ms; " +
                        "it is placed there once and then left alone (next starts are planned ${fmt(if (syncedPlanBiasMs.isNaN()) 0.0 else syncedPlanBiasMs)} ms earlier)",
                )
                lastSummaryReadings = 0
            }
            is CaptureTracker.Outcome.Trim -> {
                stream.builder.addTrim(outcome.frames)
                AppLog.add(
                    TAG,
                    "synced: ${outcome.why}: the kitchen is ${fmt(hold.errorMs)} ms from ${fmt(target)} ms (lag ${fmt(lagCapture.recentMs(CaptureTracker.SYNCED_READINGS))} ms), " +
                        "trimming ${outcome.frames} frames (${fmt(outcome.frames * 1000.0 / s.rate)} ms), correction ${hold.corrections} of this stream",
                )
            }
            is CaptureTracker.Outcome.Rejoin -> {
                lastSyncedRestartNanos = System.nanoTime()
                rejoin(outcome.reason, manual = true)
            }
            is CaptureTracker.Outcome.Hold, is CaptureTracker.Outcome.Waiting, is CaptureTracker.Outcome.Relock -> Unit
        }
        // one summary a while, counted in the readings of this stream
        syncedReadings++
        if (lagCapture.isLocked && syncedReadings % SUMMARY_EVERY_READINGS == 0) {
            AppLog.add(
                TAG,
                "sync (synced): lag ${fmt(lagCapture.recentMs(CaptureTracker.SYNCED_READINGS))} ms, target ${fmt(target)} ms, error ${fmt(hold.errorMs)} ms" +
                    (if (hold.farForMs > 0) " (beyond ${SyncedHold.FAR_MS.toInt()} ms for ${hold.farForMs / 1000} s)" else "") +
                    ", corrections ${hold.corrections}, trim budget ${stream.builder.unappliedBudget()} frames, re-joins $rejoins, round trip ${fmt(lastRttMs)} ms",
            )
        }
    }

    /** The mode changed between Synced and Lyrics: the floor the speaker showed in the one says nothing of the other. */
    private fun onModeChange(synced: Boolean) {
        syncedReadings = 0
        dayFloor.clear()
        if (captureTracker.steer.clearFloor()) {
            AppLog.add(TAG, "capture: the mode is ${if (synced) "Synced" else "Lyrics"} now: the learned floor of the lag is forgotten")
        }
    }

    /** Whether the stream that plays was set by Synced mode and is left alone by it (the far restart of Synced mode does not apply). */
    fun syncedHoldActive(): Boolean = session?.takeIf { it.isCapture }?.syncedHold == true && !syncedTarget.isNaN()

    // ---- the readings, for a look afterwards ----------------------------------------------------------

    private class Reading(val ageMs: Long, val positionMs: Long, val rawLagMs: Double, val lagMs: Double, val errorMs: Double, val targetMs: Double, val clockMs: Double)

    /** The offset of the capture clock at the first recorded reading (ns): the clock column of the readings is counted from it. */
    private var clockBaselineNanos = Long.MIN_VALUE

    private val readings = java.util.ArrayDeque<Reading>()

    /** Keeps the last readings of the stream (about 10 minutes) for [readingsCsv]. */
    private fun recordReading(tNanos: Long, positionMs: Long, rawLagMs: Double, lagMs: Double, errorMs: Double, targetMs: Double) {
        val offset = captureFeed?.clock?.offsetNanos()
        synchronized(readings) {
            if (offset != null && clockBaselineNanos == Long.MIN_VALUE) clockBaselineNanos = offset
            val clockMs = if (offset == null || clockBaselineNanos == Long.MIN_VALUE) Double.NaN else (offset - clockBaselineNanos) / 1e6
            readings.addLast(Reading(tNanos / 1_000_000L, positionMs, rawLagMs, lagMs, errorMs, targetMs, clockMs))
            while (readings.size > MAX_RECORDED_READINGS) readings.removeFirst()
        }
    }

    /**
     * The recorded readings as CSV: when (ms of System.nanoTime), the position the Sonos reported, the lag of this reading, the median of
     * the last eleven, the error that the rule or the controller works with, the target or setpoint, and how far the offset of the capture
     * clock against System.nanoTime has moved since the first reading (a step or a drift of the clock shows here, a step of the speaker does not).
     */
    fun readingsCsv(): String {
        val sb = StringBuilder("t_ms,position_ms,lag_raw_ms,lag_median_ms,error_ms,target_ms,capture_clock_ms\n")
        synchronized(readings) {
            for (r in readings) {
                sb.append(r.ageMs).append(',').append(r.positionMs).append(',').append(fmt(r.rawLagMs)).append(',').append(fmt(r.lagMs)).append(',')
                    .append(fmt(r.errorMs)).append(',').append(fmt(r.targetMs)).append(',').append(String.format(java.util.Locale.US, "%.2f", r.clockMs)).append('\n')
            }
        }
        return sb.toString()
    }

    /**
     * A reading of the Sonos for a captured stream. There is no second room to compare with: the first
     * readings measure how long after the capture the kitchen sounds (the speaker's own buffer decides),
     * and then the controller holds that lag against the drift of the speaker's clock. A step of the lag
     * is measured again, not restarted; see [CaptureTracker].
     */
    private fun handleCaptureReading(
        s: KitchenSession,
        stream: ActiveStream,
        startNanos: Long,
        tMid: Long,
        positionMs: Long,
    ) {
        val feed = s.capture ?: return
        if (feed.generation != s.captureGeneration) return // the session is about to end
        val v = Settings.values
        // in capture mode only the target and the lead settings count: the kitchen offset moves neither the start nor the lag
        val offset = 0
        val target = currentTarget()
        val steer = captureTracker.steer
        expireFloor()
        val outcome = captureTracker.onReading(
            tMid, positionMs, stream.map, { stream.builder.unappliedBudget() }, feed.clock, offset, fixedLagMs(feed, s.format),
            target?.ms ?: Double.NaN, v.kitchenFloorMs.toDouble(),
        )
        recordReading(tMid, positionMs, captureTracker.lastLagMs, lagCapture.recentMs(CaptureTracker.SYNCED_READINGS), controller.rawErrorMs, if (steer.hasSetpoint) steer.setpointMs else Double.NaN)
        when (outcome) {
            is CaptureTracker.Outcome.Locked -> {
                AppLog.add(
                    TAG,
                    "capture: the kitchen sounds ${fmt(outcome.lagMs)} ms after the capture (expected ${fmt(outcome.expectedMs)} ms" +
                        ", Play to sound ${(startNanos - s.playSentNanos) / 1_000_000L} ms), " +
                        (if (steer.active) "bringing it to ${fmt(steer.goalMs)} ms (target ${fmt(target?.ms ?: Double.NaN)} ms from ${target?.source}, floor ${fmt(steer.floorMs)} ms)" else "holding that") +
                        if (outcome.leadRaised) "; the speaker needs more than asked, the next stream starts ${captureLead.learnedMs} ms ahead" else "",
                )
                lastSummaryReadings = 0
                lastSteerState = LagSteer.State.OFF
            }
            is CaptureTracker.Outcome.Trim -> {
                stream.builder.addTrim(outcome.frames)
                AppLog.add(
                    TAG,
                    "trim ${outcome.frames} frames (median error ${fmt(controller.errorMs)} ms, lag ${fmt(lagCapture.recentMs())} ms)",
                )
            }
            is CaptureTracker.Outcome.Relock -> {
                captureRelocks++
                AppLog.add(
                    TAG,
                    "capture: the lag moved from ${fmt(outcome.fromMs)} to about ${fmt(outcome.toMs)} ms (the speaker stalled or skipped), " +
                        "measuring it again instead of restarting the stream",
                )
            }
            // a speaker that ran dry is no failed start: it is started again at once, with the new floor
            is CaptureTracker.Outcome.Rejoin -> rejoin(outcome.reason, manual = outcome.underrun)
            is CaptureTracker.Outcome.Hold, is CaptureTracker.Outcome.Waiting -> Unit
        }
        val state = steer.state()
        if (state != lastSteerState) {
            if (state == LagSteer.State.DESCENDING || state == LagSteer.State.ASCENDING || lastSteerState != LagSteer.State.OFF) {
                AppLog.add(
                    TAG,
                    "capture: the lag is ${state.label}: ${fmt(lagCapture.recentMs())} ms, setpoint ${fmt(steer.setpointMs)} ms, goal ${fmt(steer.goalMs)} ms",
                )
            }
            lastSteerState = state
        }
        val n = controller.readings
        if (lagCapture.isLocked && n > 0 && n % SUMMARY_EVERY_READINGS == 0 && n != lastSummaryReadings) {
            lastSummaryReadings = n
            val held = if (steer.active && steer.hasSetpoint) steer.setpointMs else lagCapture.targetMs(offset)
            AppLog.add(
                TAG,
                "sync (capture): lag ${fmt(lagCapture.recentMs())} ms (held at ${fmt(held)}" +
                    (if (steer.active) ", ${state.label} to ${fmt(steer.goalMs)}, floor ${fmt(steer.floorMs)}" else "") + "), " +
                    "error ${fmt(controller.errorMs)} ms, drift ${fmt(controller.driftPpm)} ppm, position behind real time ${fmt(steer.rateDeficitMs)} ms/s, " +
                    "trimmed ${controller.trimmedFrames} frames, re-joins $rejoins, round trip ${fmt(lastRttMs)} ms",
            )
        }
    }

    /**
     * What the speaker says about a captured stream whose lag is brought down, that means it ran out of audio: it reports buffering,
     * or a playback error. The steer raises the floor of the lag if it was the one that brought the lag down (see [LagSteer]).
     */
    private fun noteCaptureTrouble(p: SonosWs.Position) {
        val errorAt = ws.lastPlaybackErrorNanos
        val why = when {
            SonosPolicy.isStall(p.state) -> "the speaker reports ${p.state}"
            errorAt != seenPlaybackErrorNanos -> "playbackError ${ws.lastPlaybackError}"
            else -> null
        }
        seenPlaybackErrorNanos = errorAt
        if (why != null) speakerTrouble(why)
    }

    /**
     * The speaker said it ran out of audio. If the lag was brought down to that, the floor is raised (and kept for the rest of the
     * day) and the stream is started again: raising the setpoint does not cure a speaker that was driven that low, a fresh start does.
     */
    private fun speakerTrouble(why: String) {
        val e = captureTracker.onSpeakerTrouble(System.nanoTime(), why) ?: return
        rejoin(
            "the speaker runs dry at a lag of ${Math.round(e.lagMs)} ms ($why); the floor is ${Math.round(e.floorMs)} ms from now on",
            manual = true,
        )
    }

    /** The floor that was learned on an earlier day is dropped (the stored one is checked against the date). */
    private fun expireFloor() {
        if (captureTracker.steer.learnedFloorMs.isNaN()) return
        if (dayFloor.load() == null && captureTracker.steer.clearFloor()) {
            AppLog.add(TAG, "capture: a new day, the learned floor of the lag is forgotten")
        }
    }

    // ---- failed starts -------------------------------------------------------------------------

    private fun nowMs(): Long = System.nanoTime() / 1_000_000L

    /** Whether a start may begin now; if not (failed starts are being waited out) it says so, but not more than every 5 s. */
    private fun startAllowed(what: String): Boolean {
        val now = nowMs()
        if (backoff.allowed(now)) return true
        val nanos = System.nanoTime()
        if (nanos - lastBackoffLogNanos > 5_000_000_000L) {
            lastBackoffLogNanos = nanos
            AppLog.add(TAG, "$what held back: ${backoff.failures} starts failed in a row, the next may begin in ${backoff.remainingMs(now) / 1000} s")
        }
        return false
    }

    /**
     * Why the Sonos did not take the stream, as far as it says: its transport state and status (asked afresh), and what its
     * websocket last announced (state, `playbackError`, error fields of the last answer).
     */
    private fun sonosReason(): String {
        val sb = StringBuilder("Sonos says: ")
        val dev = device
        if (dev != null && reachable) {
            val t = soapFor(dev).transportInfo()
            if (t.ok) {
                sb.append("transport ").append(SonosXml.tag(t.body, "CurrentTransportState") ?: "?")
                    .append(", status ").append(SonosXml.tag(t.body, "CurrentTransportStatus") ?: "?")
            } else {
                sb.append("transport not readable (").append(t.describe()).append("), last ").append(lastTransport).append(" / ").append(lastTransportStatus)
            }
        } else {
            sb.append("transport not asked (last ").append(lastTransport).append(" / ").append(lastTransportStatus).append(")")
        }
        sb.append("; ").append(ws.describe())
        if (lastWsState.isNotEmpty()) sb.append("; last reading ").append(lastWsState)
        if (lastWsError.isNotEmpty()) sb.append(" ").append(lastWsError)
        return sb.toString()
    }

    /**
     * A start did not work out (once per session, and not for one that went well before): the failures in a row are
     * counted, the next start waits as long as [StartBackoff] says, and the speaker's own words are logged.
     */
    private fun startFailed(s: KitchenSession, why: String) {
        if (s.healthy || s.failureCounted) return
        s.failureCounted = true
        val wait = backoff.onFailure(nowMs())
        startFailuresTotal++
        val sonos = sonosReason()
        s.record?.failed(why, sonos)
        AppLog.add(
            TAG,
            "start failed (${backoff.failures} in a row): $why. $sonos. " +
                if (wait > 0) "The next start waits ${wait / 1000} s." else "Trying again soon.",
        )
    }

    // ---- sessions ------------------------------------------------------------------------------

    override fun sessionFor(token: String): KitchenSession? = sessions[token]

    /** A session was made: the speaker finds it by its token, it is the current one, and its log begins. */
    private fun registerSession(s: KitchenSession) {
        sessions[s.token] = s
        session = s
        s.record = history.begin(s.token, if (s.isCapture) "capture" else "ring", System.nanoTime(), System.currentTimeMillis())
    }

    /** The lag the kitchen is brought to now: the common delay of Synced mode while it holds, else the target of the settings; null: none. */
    private fun currentTarget(): CaptureTarget.Target? {
        val synced = syncedTargetMs
        if (!synced.isNaN()) return CaptureTarget.Target(synced, "synced")
        val v = Settings.values
        return CaptureTarget.of(v.kitchenTargetMs, v.captureLivingLatencyMs)
    }

    /** The floor in force (ms): the setting, or what an underrun showed today if that is higher. */
    fun floorNowMs(): Double {
        val configured = Settings.values.kitchenFloorMs.toDouble()
        val learned = captureTracker.steer.learnedFloorMs
        return if (learned.isNaN()) configured else maxOf(configured, learned)
    }

    /**
     * The lag the Sonos is kept to in Lyrics mode (ms after the capture): the target of the settings, not below the floor. Without a
     * target the lag it locked at last, else the 350 ms it usually has. This is what the common delay of Synced mode is built on.
     */
    fun lyricsTargetMs(): Double {
        val v = Settings.values
        val target = CaptureTarget.of(v.kitchenTargetMs, v.captureLivingLatencyMs)?.ms
            ?: lagCapture.locked
            ?: NATURAL_LAG_MS
        return maxOf(target, floorNowMs())
    }

    /** The log of the last sessions, newest first: what the speaker did with each stream and what it showed. */
    fun lastSessionsJson(): JSONArray = history.toJson(System.nanoTime())

    /** "ours", "foreign:<scheme>" or "" for the track URI of a poll, for the log of a session. */
    private fun uriKind(uri: String): String = when {
        uri.isEmpty() -> ""
        SonosPolicy.isOurUri(uri) -> "ours"
        else -> "foreign:" + uri.substringBefore(':').take(24)
    }

    /** Puts the speaker's state in the log of the newest session, while it runs and for a while after. */
    private fun noteSpeaker(transport: String, status: String, uri: String, ws: String, wsError: String) {
        val rec = history.latest() ?: return
        val now = System.nanoTime()
        if (rec.wantsNotes(now)) rec.noteTransport(now, transport, status, uriKind(uri), ws, wsError)
    }

    /** Ends a session. With [commands], the speaker is muted and stopped (it may still hold seconds of our audio). */
    private fun endSession(s: KitchenSession, why: String, commands: Boolean) {
        if (!s.cancelled) {
            s.cancelled = true
            s.endReason = why
            sessions.remove(s.token)
            s.record?.ended(System.nanoTime(), why)
            AppLog.add(TAG, "session ${s.token} ended: $why")
        }
        if (session === s) {
            session = null
            readingTask?.cancel(false)
            readingTask = null
            onJoinedPending = false
            state = baseState()
        }
        if (commands) quietSpeaker()
        // after the mute: this only ends a stream thread that is stuck writing to a speaker that stopped reading
        s.closeSockets()
    }

    private fun quietSpeaker() {
        val dev = device ?: return
        if (!reachable) return
        val api = soapFor(dev)
        synchronized(muteLock) {
            val m = api.setMute(true)
            api.stop()
            sonosQuiet = m.ok
        }
        // A stopped Sonos plays out its buffer for about 3 s, muted. After that the speaker is given
        // back unmuted, so that Bluetooth or the Sonos app can be used on it.
        unmuteTask?.cancel(false)
        unmuteTask = later(IDLE_UNMUTE_MS) { unmuteIdle() }
    }

    private fun unmuteIdle() {
        if (session != null) return
        val dev = device ?: return
        if (!reachable) return
        synchronized(muteLock) {
            if (!sonosQuiet) return
            val m = soapFor(dev).setMute(false)
            if (m.ok) sonosQuiet = false
        }
    }

    private fun baseState(): State = when {
        !Settings.values.kitchenEnabled -> State.OFF
        device == null -> State.SEARCHING
        !reachable -> State.OFFLINE
        busyElsewhere -> State.BUSY
        else -> State.READY
    }

    // ---- the speaker: finding it, polling it -----------------------------------------------------

    private fun tick() {
        val v = Settings.values
        if (!v.kitchenEnabled) {
            state = State.OFF
            return
        }
        val dev = device
        if (dev == null) {
            searchIfDue(SEARCH_EVERY_NANOS)
            return
        }
        if (state == State.STARTING) return // a start is not disturbed
        if (!reachable) searchIfDue(SEARCH_OFFLINE_EVERY_NANOS)
        triggerPoll(dev)
    }

    private fun searchIfDue(everyNanos: Long) {
        val now = System.nanoTime()
        if (now - lastSearchNanos < everyNanos) return
        if (!searching.compareAndSet(false, true)) return
        lastSearchNanos = now
        netPool.execute {
            val found = try {
                finder.search()
            } catch (t: Throwable) {
                AppLog.error(TAG, "SSDP search failed", t)
                emptyList<SonosDevice>()
            } finally {
                searching.set(false)
            }
            post("search result") { onSearchResult(found) }
        }
    }

    private class PollResult(val ok: Boolean, val transport: String, val status: String, val uri: String, val error: String?)

    private fun triggerPoll(dev: SonosDevice) {
        if (!polling.compareAndSet(false, true)) return
        netPool.execute {
            val result = try {
                val api = SonosSoap(dev.ip)
                val t = api.transportInfo()
                if (!t.ok) {
                    PollResult(false, "", "", "", t.describe())
                } else {
                    val p = api.positionInfo()
                    PollResult(
                        true,
                        SonosXml.tag(t.body, "CurrentTransportState") ?: "",
                        SonosXml.tag(t.body, "CurrentTransportStatus") ?: "",
                        if (p.ok) SonosXml.tag(p.body, "TrackURI") ?: "" else "",
                        null,
                    )
                }
            } catch (t: Throwable) {
                PollResult(false, "", "", "", t.toString())
            } finally {
                polling.set(false)
            }
            post("poll result") { onPoll(dev, result) }
        }
    }

    private fun onSearchResult(found: List<SonosDevice>) {
        if (found.isEmpty()) return
        val current = device
        if (current != null) {
            // the same speaker under a new address?
            val moved = found.firstOrNull { it.udn.isNotEmpty() && it.udn == current.udn && it.ip != current.ip }
            if (moved != null) {
                AppLog.add(TAG, "the speaker moved from ${current.ip} to ${moved.ip}")
                setDevice(moved)
            }
            return
        }
        val preferred = prefs.getString(PREF_IP, null)
        val chosen = found.firstOrNull { it.ip == preferred }
            ?: (if (found.size == 1) found[0] else null)
            ?: found.firstOrNull { d -> KITCHEN_WORDS.any { d.room.contains(it, ignoreCase = true) } }
            ?: found[0]
        AppLog.add(TAG, "speakers: ${found.joinToString { it.toString() }}; using $chosen")
        setDevice(chosen)
    }

    private fun setDevice(d: SonosDevice) {
        if (device?.udn != d.udn) {
            // the floor is a property of the speaker
            dayFloor.clear()
            captureTracker.steer.clearFloor()
        }
        device = d
        reachable = false
        busyElsewhere = false
        pollFailures = 0
        prefs.edit().putString(PREF_IP, d.ip).apply()
        ws.close()
        state = baseState()
        triggerPoll(d)
    }

    private fun onPoll(dev: SonosDevice, r: PollResult) {
        if (device?.ip != dev.ip) return
        if (!Settings.values.kitchenEnabled) {
            state = State.OFF
            return
        }
        if (!r.ok) {
            pollFailures++
            lastError = "speaker: ${r.error}"
            if (pollFailures >= 2 && (reachable || session != null)) goOffline(r.error ?: "no answer")
            return
        }
        pollFailures = 0
        lastTransport = r.transport
        lastTransportStatus = r.status
        noteSpeaker(r.transport, r.status, r.uri, lastWsState, lastWsError)
        if (!reachable) {
            reachable = true
            AppLog.add(TAG, "the speaker answers (${r.transport})")
        }
        connectWsIfNeeded()
        val kind = SonosPolicy.classify(r.transport, r.uri)
        val s = session
        if (s != null) {
            checkSession(s, r, kind)
            return
        }
        // Only something else that plays keeps the kitchen out, or a stream of ours that someone paused on the
        // speaker during the session that just ended (then the kitchen stays quiet until the speaker is stopped or
        // plays). A foreign session that is paused or stopped is free (it is taken over when a stream is set),
        // and so is a paused stream of ours from an earlier session or run.
        val pausedHere = pausedOnSpeakerToken
        busyElsewhere = when (kind) {
            SonosPolicy.Availability.BUSY -> true
            SonosPolicy.Availability.OURS ->
                r.transport == "PAUSED_PLAYBACK" && pausedHere != null && SonosPolicy.tokenOf(r.uri) == pausedHere
            SonosPolicy.Availability.FREE -> false
        }
        if (!busyElsewhere) pausedOnSpeakerToken = null
        if (kind == SonosPolicy.Availability.OURS && (r.transport == "PLAYING" || r.transport == "TRANSITIONING")) {
            // a stream of ours from before this run: its server is gone
            AppLog.add(TAG, "stopping a leftover stream of ours")
            quietSpeaker()
        }
        state = baseState()
        if (state == State.READY) {
            maybeHotJoin("the speaker is free")
            maybeCaptureJoin("the speaker is free")
        }
    }

    private fun checkSession(s: KitchenSession, r: PollResult, kind: SonosPolicy.Availability) {
        if (!s.armed || System.nanoTime() - s.playSentNanos < SESSION_GRACE_NANOS) return
        val ours = r.uri.contains(s.token)
        if (ours && r.transport == "PLAYING") {
            badPolls = 0
            return
        }
        if (ours && s.isCapture && r.transport == "TRANSITIONING") {
            speakerTrouble("the transport is TRANSITIONING")
        }
        if (!ours && kind == SonosPolicy.Availability.BUSY) {
            AppLog.add(TAG, "the speaker plays something else now (${r.transport}), leaving it alone")
            busyElsewhere = true
            endSession(s, "the speaker was taken over", false)
            state = baseState()
            return
        }
        if (ours && r.transport == "PAUSED_PLAYBACK") {
            AppLog.add(TAG, "the kitchen was paused on the speaker itself, leaving it alone")
            busyElsewhere = true
            pausedOnSpeakerToken = s.token
            endSession(s, "paused on the speaker", false)
            state = baseState()
            return
        }
        badPolls++
        if (badPolls >= 2) {
            badPolls = 0
            rejoin("the speaker is ${r.transport}")
        }
    }

    private fun goOffline(why: String) {
        AppLog.add(TAG, "the speaker is offline ($why)")
        reachable = false
        busyElsewhere = false
        sonosQuiet = false
        session?.let { endSession(it, "offline: $why", false) }
        ws.close()
        state = baseState()
    }

    private fun connectWsIfNeeded() {
        val dev = device ?: return
        if (ws.isOpen || ws.status == "connecting") return
        val now = System.nanoTime()
        if (now - lastWsConnectNanos < WS_RECONNECT_NANOS) return
        lastWsConnectNanos = now
        ws.connect(dev.ip)
    }

    override fun onAlive(ip: String) {
        post("ssdp alive") {
            val dev = device
            if (dev == null) {
                netPool.execute {
                    val d = finder.describe(ip)
                    post("described") { if (device == null) onSearchResult(listOf(d)) }
                }
            } else if (dev.ip == ip && !reachable) {
                triggerPoll(dev)
            }
        }
    }

    override fun onByebye(ip: String) {
        post("ssdp byebye") {
            if (device?.ip == ip && reachable) goOffline("it said goodbye")
        }
    }

    // ---- settings and volume ---------------------------------------------------------------------

    private fun onSettings(changed: Set<String>) {
        val v = Settings.values
        controller.offsetMs = effectiveOffsetMs()
        if (changed.contains("capture_lead_ms")) captureLead.reset()
        if (changed.contains("kitchen_floor_ms") || changed.contains("kitchen_format")) {
            // the learned floor is what this speaker allowed with the format of the time, and the floor setting is a decision about it
            dayFloor.clear()
            if (captureTracker.steer.clearFloor()) AppLog.add(TAG, "capture: the floor of the lag is forgotten (the floor setting or the format changed)")
        }
        val sessionNow = session
        if (sessionNow != null && !sessionNow.cancelled) {
            // A stream keeps the format it was made with; a changed setting restarts the kitchen's stream (for the latency measurements).
            val formatChanged = changed.contains("kitchen_format") && sessionNow.format != StreamFormat.resolve(v.kitchenFormat, sessionNow.isCapture)
            val blockChanged = changed.contains("kitchen_flac_block") && sessionNow.format == StreamFormat.FLAC && sessionNow.flacBlock != v.kitchenFlacBlock
            if (formatChanged || blockChanged) {
                lastRejoinNanos = 0L
                rejoin("the stream format was changed to ${v.kitchenFormat}, FLAC block ${v.kitchenFlacBlock}", manual = true)
            }
        }
        if (changed.contains("kitchen_enabled")) {
            if (!v.kitchenEnabled) {
                session?.let { endSession(it, "the kitchen was switched off", true) }
                ws.close()
                state = State.OFF
            } else {
                state = baseState()
                val dev = device
                if (dev != null) triggerPoll(dev) else searchIfDue(0L)
            }
        }
        if (changed.contains("kitchen_balance")) pushVolume()
        if (changed.contains("kitchen_volume")) pushKitchenVolume()
    }

    /**
     * The offset the controller works with. For the ring it is the setting. For a captured stream the offset
     * only moves the lag that was measured at the start (see CaptureLag).
     */
    private fun effectiveOffsetMs(): Double {
        val offset = Settings.values.kitchenOffsetMs
        return if (session?.isCapture == true) captureTracker.controllerOffsetMs(0) else offset.toDouble()
    }

    /** The Connect volume changed (0 to 65535). */
    fun onVolume(raw: Int) {
        lastVolumeRaw = raw
        schedulePush()
    }

    /**
     * The music volume of the box changed (what the phone's Cast volume sets), as a Connect volume 0 to 65535. Only where the mirror
     * is switched on (`capture_volume_mirror`): on this box the Cast volume changes nothing, and the kitchen volume is a setting.
     */
    fun onCaptureVolume(raw: Int) {
        if (!Settings.values.captureVolumeMirror) return
        captureVolumeRaw = raw
        schedulePush()
    }

    private fun schedulePush() {
        if (volumeScheduled.compareAndSet(false, true)) {
            later(60L) {
                volumeScheduled.set(false)
                pushVolume()
            }
        }
    }

    /** The volume the Sonos should have: the kitchen volume setting for a capture session, else the Spotify one (see [KitchenVolume]). */
    private fun sonosVolume(): Int {
        val v = Settings.values
        return KitchenVolume.sonos(captureIsActive(), v.kitchenVolume, v.captureVolumeMirror, captureVolumeRaw, lastVolumeRaw, v.kitchenBalance)
    }

    private fun captureIsActive(): Boolean {
        val s = session
        return if (s != null) s.isCapture else arbiter.source == SourceArbiter.Source.CAPTURE
    }

    private fun pushVolume() {
        val s = session ?: return
        val dev = device ?: return
        if (s.cancelled) return
        val volume = sonosVolume()
        if (volume >= 0) soapFor(dev).setVolume(volume)
    }

    /**
     * The kitchen volume was set (the slider of the phone page): the Sonos gets it now, also when nothing streams, unless something
     * else plays on it. The volume is applied at the start of every captured stream too.
     */
    private fun pushKitchenVolume() {
        val volume = Settings.values.kitchenVolume
        if (volume < 0) return
        val dev = device ?: return
        if (!reachable || (session == null && busyElsewhere)) return
        val r = soapFor(dev).setVolume(volume)
        AppLog.add(TAG, "kitchen volume $volume: ${r.describe()}")
    }

    // ---- debug actions -------------------------------------------------------------------------

    fun rejoinNow() {
        post("manual re-join") {
            lastRejoinNanos = 0L
            rejoin("asked for on the debug page", manual = true)
        }
    }

    fun searchNow() {
        post("manual search") { searchIfDue(0L) }
    }

    fun select(ip: String) {
        post("select") {
            prefs.edit().putString(PREF_IP, ip).apply()
            netPool.execute {
                val d = finder.describe(ip)
                post("selected") { setDevice(d) }
            }
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    private fun soapFor(dev: SonosDevice): SonosSoap = SonosSoap(dev.ip)

    private fun newToken(): String = UUID.randomUUID().toString().replace("-", "").substring(0, 12)

    /**
     * The format of the streams that are made now (the setting `kitchen_format`: with "auto" a captured stream is WAV and
     * Spotify's is FLAC; a session keeps the one it was made with).
     */
    private fun streamFormat(capture: Boolean): StreamFormat = StreamFormat.resolve(Settings.values.kitchenFormat, capture)

    /** Where the speaker finds a session's stream: the extension tells the format. */
    private fun streamUri(host: String, s: KitchenSession): String = "http://$host:${DebugServer.PORT}/k/${s.token}.${s.format.ext}"

    private fun channelsFor(kind: Int): StreamBuilder.ChannelMode =
        if (kind == KIND_CALIBRATION) StreamBuilder.ChannelMode.RIGHT_ONLY else StreamBuilder.ChannelMode.STEREO

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            // the callers check their flags
        }
    }

    fun statusJson(): JSONObject {
        val o = JSONObject()
        o.put("state", state.label)
        o.put("enabled", Settings.values.kitchenEnabled)
        val dev = device
        if (dev != null) {
            val d = JSONObject()
            d.put("ip", dev.ip)
            d.put("room", dev.room)
            d.put("model", dev.model)
            d.put("udn", dev.udn)
            o.put("speaker", d)
        } else {
            o.put("speaker", JSONObject.NULL)
        }
        o.put("reachable", reachable)
        o.put("busy_elsewhere", busyElsewhere)
        o.put("transport", lastTransport)
        o.put("ssdp_notify", finder.notifyStatus)
        val w = JSONObject()
        w.put("status", ws.status)
        w.put("open", ws.isOpen)
        w.put("group", ws.groupId)
        w.put("last_error", ws.lastError ?: JSONObject.NULL)
        o.put("websocket", w)
        val sessionFormat = session?.format ?: streamFormat(arbiter.source == SourceArbiter.Source.CAPTURE)
        o.put("lk_ms", delays.ms(sessionFormat))
        o.put("lk_flac_ms", delays.ms(StreamFormat.FLAC))
        o.put("lk_wav_ms", delays.ms(StreamFormat.WAV))
        o.put("offset_ms", controller.offsetMs)
        o.put("error_ms", jsonNumber(controller.errorMs))
        o.put("raw_error_ms", jsonNumber(controller.rawErrorMs))
        o.put("drift_ppm", jsonNumber(controller.driftPpm))
        o.put("readings", controller.readings)
        o.put("trimmed_frames", controller.trimmedFrames)
        o.put("controller_rejoins", controller.rejoins)
        o.put("starts_together", startsTogether)
        o.put("hot_joins", hotJoins)
        o.put("rejoins", rejoins)
        o.put("rejoins_suppressed", rejoinsSuppressed)
        o.put("last_rejoin_reason", lastRejoinReason)
        o.put("readings_discarded_slow", readingsDiscarded)
        o.put("prepare_failures", prepFailures)
        o.put("play_failures", playFailures)
        o.put("source", arbiter.source.label)
        o.put("format", Settings.values.kitchenFormat)
        o.put("format_capture", streamFormat(capture = true).key)
        o.put("format_ring", streamFormat(capture = false).key)
        o.put("flac_block", Settings.values.kitchenFlacBlock)
        o.put("kitchen_volume", Settings.values.kitchenVolume)
        o.put("failed_starts", backoff.failures)
        o.put("start_failures_total", startFailuresTotal)
        o.put("backoff_s", backoff.remainingMs(nowMs()) / 1000)
        o.put("transport_status", lastTransportStatus)
        o.put("ws_error", lastWsError.ifEmpty { JSONObject.NULL })
        o.put("ws_description", ws.describe())
        val cs = session?.takeIf { it.isCapture }
        val v = Settings.values
        val lag = if (cs != null) lagCapture.recentMs() else Double.NaN
        val target = currentTarget()
        val steer = captureTracker.steer
        // how far behind the captured moment the kitchen sounds
        o.put("lag_ms", jsonNumber(round1(lag)))
        // how far ahead of the capture the stream was sent when it began (null for a session that is not a captured one)
        o.put("lead_ms", jsonNumber(round1(if (cs != null) captureAheadMs else Double.NaN)))
        // the lag the controller holds now: the setpoint on its way to the target, or where the lag locked
        val heldSynced = cs?.syncedHold == true
        val held = if (heldSynced) captureTracker.hold.targetMs else if (steer.active && steer.hasSetpoint) steer.setpointMs else lagCapture.targetMs(0)
        o.put("lag_target_ms", jsonNumber(round1(if (cs != null) held else Double.NaN)))
        o.put("lag_locked", cs != null && lagCapture.isLocked)
        // the lag that is wanted (kitchen_target_ms, else capture_living_latency_ms); null: the lag is left where it locked
        o.put("target_ms", jsonNumber(round1(target?.ms ?: Double.NaN)))
        o.put("target_from", target?.source ?: "off")
        // the floor: the lowest lag aimed for. The setting (kitchen_floor_ms) or, if higher, what an underrun showed (the lag where it
        // happened plus 15 ms), which is kept for the rest of the local day and across streams and restarts of the app
        val learned = steer.learnedFloorMs
        o.put("floor_ms", jsonNumber(round1(if (learned.isNaN()) v.kitchenFloorMs.toDouble() else maxOf(learned, v.kitchenFloorMs.toDouble()))))
        o.put("floor_configured_ms", v.kitchenFloorMs)
        o.put("floor_learned_ms", jsonNumber(round1(learned)))
        o.put("floor_day", dayFloor.savedDay()?.let { java.time.LocalDate.ofEpochDay(it).toString() } ?: JSONObject.NULL)
        o.put("floor_events", steer.floorEvents)
        o.put("position_behind_ms_per_s", jsonNumber(round1(steer.rateDeficitMs)))
        // where the controller is bringing the lag now, and whether that is still down
        o.put("setpoint_ms", jsonNumber(round1(if (cs != null && !heldSynced) steer.setpointMs else Double.NaN)))
        o.put("steering", if (cs == null) "off" else if (heldSynced) "synced-hold" else steer.state().label)
        o.put("descending", cs != null && !heldSynced && steer.descending())
        // Synced mode sets the stream once and leaves it alone: where the rule stands
        val hold = captureTracker.hold
        val holdJson = JSONObject()
        holdJson.put("active", heldSynced)
        holdJson.put("placed", heldSynced && hold.placed)
        holdJson.put("target_ms", jsonNumber(round1(if (heldSynced) hold.targetMs else Double.NaN)))
        holdJson.put("error_ms", jsonNumber(round1(if (heldSynced) hold.errorMs else Double.NaN)))
        holdJson.put("beyond_band_for_s", if (heldSynced) hold.farForMs / 1000 else 0L)
        holdJson.put("corrections", if (heldSynced) hold.corrections else 0)
        holdJson.put("plan_bias_ms", jsonNumber(round1(syncedPlanBiasMs)))
        o.put("synced_hold", holdJson)
        // how far the kitchen is from the target (positive: later); without a target from the living room's latency (0 if none), as before
        o.put("room_offset_ms", jsonNumber(round1(lag - (target?.ms ?: v.captureLivingLatencyMs.toDouble()))))
        o.put("capture_age_at_play_ms", jsonNumber(round1(captureAgeAtPlayMs)))
        o.put("capture_starts", captureStarts)
        o.put("capture_planned_lag_ms", jsonNumber(round1(capturePlannedLagMs)))
        o.put("capture_lead_ms", captureLead.effectiveMs(Settings.values.captureLeadMs))
        o.put("capture_lead_learned_ms", captureLead.learnedMs)
        o.put("capture_relocks", captureRelocks)
        val read = JSONObject()
        read.put("age_ms", if (lastReadingNanos == 0L) -1L else (System.nanoTime() - lastReadingNanos) / 1_000_000L)
        read.put("position_ms", lastPositionMs)
        read.put("ws_state", lastWsState)
        read.put("rtt_ms", lastRttMs)
        o.put("last_reading", read)
        val s = session
        if (s != null) {
            val j = JSONObject()
            j.put("token", s.token)
            j.put("epoch", s.epoch)
            j.put("kind", s.kind)
            j.put("armed", s.armed)
            j.put("start_ring_frame", s.startRingFrame)
            j.put("age_ms", (System.nanoTime() - s.createdNanos) / 1_000_000L)
            j.put("connections", s.connectionCount)
            j.put("blocks_sent", s.blocksSent.get())
            j.put("bytes_sent", s.bytesSent.get())
            j.put("playing_readings", s.playingReadings)
            j.put("trim_budget", s.stream?.builder?.unappliedBudget() ?: 0)
            j.put("capture", s.isCapture)
            j.put("rate", s.rate)
            j.put("format", s.format.key)
            j.put("block_frames", s.blockFrames)
            o.put("session", j)
        } else {
            o.put("session", JSONObject.NULL)
        }
        o.put("last_error", lastError ?: JSONObject.NULL)
        return o
    }

    /** "  lag 430 ms" while a captured stream is measured, with "to 240" while it is brought to a target, else nothing (for the screen). */
    fun lagText(): String {
        if (session?.isCapture != true) return ""
        val lag = lagCapture.recentMs()
        if (lag.isNaN()) return ""
        val steer = captureTracker.steer
        val moving = session?.syncedHold != true && steer.active && steer.hasSetpoint && steer.state().let { it == LagSteer.State.DESCENDING || it == LagSteer.State.ASCENDING }
        return "  lag " + Math.round(lag) + " ms" + (if (moving) " to " + Math.round(steer.goalMs) else "")
    }

    private fun jsonNumber(v: Double): Any = if (v.isNaN() || v.isInfinite()) JSONObject.NULL else v

    private fun round1(v: Double): Double = if (v.isNaN() || v.isInfinite()) v else Math.round(v * 10.0) / 10.0

    companion object {
        private const val TAG = "kitchen"
        private const val TITLE = "Whole Flat"
        private const val KIND_CALIBRATION = 3

        /** The lag a captured stream usually settles at (ms), for a first guess when nothing was measured. */
        private const val NATURAL_LAG_MS = 350.0

        /** The kind and the epoch of a session of the capture mode (there is no ring epoch behind it). */
        private const val KIND_CAPTURE = 100
        private const val CAPTURE_EPOCH = -1L
        private const val PREF_IP = "sonos_ip"
        private val KITCHEN_WORDS = listOf("kitchen", "küche", "kuche", "kuhinja", "køkken", "kjøkken", "cuisine", "cocina", "keuken")

        private const val POLL_PERIOD_MS = 3_000L
        private const val READING_PERIOD_MS = 200L
        private const val SUMMARY_EVERY_READINGS = 150

        /** Readings that are kept for a look afterwards (200 ms apart: about 10 minutes). */
        private const val MAX_RECORDED_READINGS = 3_000

        /** After a fresh start asked for by the Synced rule the next error is trimmed, not answered with another start, for this long. */
        private const val SYNCED_RESTART_PAUSE_NANOS = 90_000_000_000L

        /** A lock that is further than this from the plan is not taken as the speaker's start delay (ms). */
        private const val SYNCED_BIAS_MAX_MS = 600.0
        private const val SEARCH_EVERY_NANOS = 10_000_000_000L
        private const val SEARCH_OFFLINE_EVERY_NANOS = 30_000_000_000L
        private const val WS_RECONNECT_NANOS = 3_000_000_000L

        private const val LEARN_LK_AT = 4
        private const val UNMUTE_LEAD_NANOS = 30_000_000L
        private const val HOT_JOIN_LEAD_NANOS = 30_000_000L

        /** A captured stream is placed at the moment Play goes out, so the time ahead only has to cover the timer. */
        private const val CAPTURE_PLAY_LEAD_NANOS = 10_000_000L
        private const val JOIN_MARGIN_FRAMES = 2_205L

        private const val MAX_RTT_NANOS = 60_000_000L
        /** A session that plays this long, in this many readings, went well: the failed starts before it are forgotten. */
        private const val HEALTHY_AFTER_NANOS = 15_000_000_000L
        private const val HEALTHY_READINGS = 25

        /** The speaker plays but no connection came after Play for this long: it does not play our stream. */
        private const val NO_STREAM_GRACE_NANOS = 5_000_000_000L
        private const val NOT_PLAYING_LIMIT = 30
        private const val NOT_PLAYING_GRACE_NANOS = 6_000_000_000L
        private const val SESSION_GRACE_NANOS = 6_000_000_000L
        private const val REJOIN_COOLDOWN_NANOS = 6_000_000_000L
        private const val REJOIN_QUIET_MS = 8_000L
        private const val FAILURE_COOLDOWN_NANOS = 5_000_000_000L

        /** A stopped speaker is unmuted again after this, when its buffer has run dry. */
        private const val IDLE_UNMUTE_MS = 4_500L

        private fun daemonFactory(name: String): ThreadFactory = ThreadFactory { r ->
            val t = Thread(r, name)
            t.isDaemon = true
            t
        }
    }
}
