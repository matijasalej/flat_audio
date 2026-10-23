package io.github.matijasalej.wholeflat

import android.content.Context
import android.content.SharedPreferences
import io.github.matijasalej.wholeflat.capture.CaptureFeed
import io.github.matijasalej.wholeflat.capture.CaptureLag
import io.github.matijasalej.wholeflat.capture.CaptureLead
import io.github.matijasalej.wholeflat.capture.CaptureStart
import io.github.matijasalej.wholeflat.capture.CaptureTracker
import io.github.matijasalej.wholeflat.capture.SourceArbiter
import io.github.matijasalej.wholeflat.sync.SonosPolicy
import io.github.matijasalej.wholeflat.sync.SonosXml
import io.github.matijasalej.wholeflat.sync.StartPlanner
import io.github.matijasalej.wholeflat.sync.StreamBuilder
import io.github.matijasalej.wholeflat.sync.StreamView
import io.github.matijasalej.wholeflat.sync.SyncController
import io.github.matijasalej.wholeflat.sync.VolumeCurve
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
class Kitchen(private val context: Context, private val living: LivingRoom) : StartCoordinator, SonosFinder.Listener {

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
     * The captured stream is at 48 kHz, and its time stamps are looser than the living room's (the reads are 20 ms
     * apart), so the controller is a little more tolerant: it only has to hold the lag constant.
     */
    private val captureController = SyncController(
        SyncController.Params(
            rejoinThresholdMs = 60.0,
            rejoinReadings = 5,
            deadbandMs = 8.0,
            maxPendingFrames = 2_400,
            rate = CaptureFeed.RATE,
        ),
    )

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

    @Volatile
    private var lkNanos = DEFAULT_LK_NANOS

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
        finder.listener = this
        finder.startNotifyListener()
        Settings.addListener(settingsListener)
        controller.offsetMs = Settings.values.kitchenOffsetMs.toDouble()
        state = baseState()
        control.scheduleWithFixedDelay(Runnable { post("tick") { tick() } }, 500, POLL_PERIOD_MS, TimeUnit.MILLISECONDS)
        AppLog.add(TAG, "kitchen started, ${if (Settings.values.kitchenEnabled) "enabled" else "switched off"}")
    }

    fun stop() {
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
        if (!v.kitchenEnabled || dev == null || !reachable || busyElsewhere) {
            advices[epoch] = StartAdvice.Alone
            return
        }
        val s = KitchenSession(newToken(), epoch, kind, startFrame, channelsFor(kind))
        val host = Net.localAddressFor(dev.ip)
        if (host == null) {
            advices[epoch] = StartAdvice.Alone
            return
        }
        s.uri = "http://$host:${DebugServer.PORT}/k/${s.token}.flac"
        sessions[s.token] = s
        session = s
        state = State.STARTING
        if (!prepare(s, soapFor(dev))) {
            advices[epoch] = StartAdvice.Alone
            return
        }
        if (s.cancelled || session !== s || currentEpoch != epoch) {
            advices[epoch] = StartAdvice.Alone
            return
        }
        advices[epoch] = StartAdvice.Ready(lkNanos, v.kitchenOffsetMs * 1_000_000L)
    }

    private fun doCommit(epoch: Long, plan: StartPlanner.Plan) {
        val s = session
        if (s == null || s.epoch != epoch || s.cancelled) return
        startsTogether++
        val delayMs = (plan.tCmdNanos - System.nanoTime()) / 1_000_000L
        AppLog.add(TAG, "start $epoch: Play in $delayMs ms${if (plan.delayed) " (held back for the living room)" else ""}")
        timer.schedule(Runnable { sendPlay(s, plan.tCmdNanos) }, maxOf(0L, delayMs - 3L), TimeUnit.MILLISECONDS)
    }

    /** Sets the speaker up for a stream: muted, stopped, at the right volume, URL loaded. False if that failed. */
    private fun prepare(s: KitchenSession, api: SonosSoap): Boolean {
        unmuteTask?.cancel(false)
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
        val volume = sonosVolume()
        if (volume >= 0) api.setVolume(volume)
        val didl = SonosXml.didlTrack(TITLE, s.uri)
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
        s.arm(tSent + lkNanos)
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
        val unmuteAt = tSent + lkNanos - UNMUTE_LEAD_NANOS
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
        endSession(s, "Play failed", true)
    }

    private fun afterPlay(s: KitchenSession) {
        if (session !== s || s.cancelled) return
        state = State.PLAYING
        controller = if (s.isCapture) captureController else ringController
        controller.reset()
        controller.offsetMs = effectiveOffsetMs()
        if (s.isCapture) captureTracker.startStream(capturePlannedLagMs)
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
        val meta = RingMeta()
        if (NativeBridge.ringState(meta.a) != 0 || !meta.playing || meta.startPending) return false
        val host = Net.localAddressFor(dev.ip) ?: return false
        val s = KitchenSession(newToken(), meta.epoch, meta.kind, -1L, channelsFor(meta.kind))
        s.uri = "http://$host:${DebugServer.PORT}/k/${s.token}.flac"
        sessions[s.token] = s
        session = s
        currentEpoch = meta.epoch
        state = State.STARTING
        AppLog.add(TAG, "joining the kitchen ($why), session ${s.token}")
        if (!prepare(s, soapFor(dev))) return false
        if (s.cancelled || session !== s) return false

        // the frame the living room plays when the kitchen's first sound arrives
        val offsetNanos = v.kitchenOffsetMs * 1_000_000L
        val tCmd = System.nanoTime() + HOT_JOIN_LEAD_NANOS
        val frame = StartPlanner.hotJoinFrame(living.timeline, tCmd, lkNanos, offsetNanos)
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
        val stream = s.stream ?: return // the Sonos has not asked for the stream yet
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
        if (s.isCapture) {
            handleCaptureReading(s, stream, clock.startNanos, tMid, p.positionMs)
            return
        }
        if (s.playingReadings == LEARN_LK_AT) learnLk(s, clock.startNanos)
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

    /** The measured delay from Play to the first sound, as it came out of the readings, smoothed over the starts. */
    private fun learnLk(s: KitchenSession, observedStartNanos: Long) {
        val observed = observedStartNanos - s.playSentNanos
        if (observed < MIN_LK_NANOS || observed > MAX_LK_NANOS) {
            AppLog.add(TAG, "Play to sound looks like ${observed / 1_000_000L} ms, not used")
            return
        }
        val before = lkNanos
        lkNanos = (before * 0.6 + observed * 0.4).toLong()
        AppLog.add(TAG, "Play to sound: ${observed / 1_000_000L} ms (was ${before / 1_000_000L}, now ${lkNanos / 1_000_000L})")
    }

    /** Only the kitchen's own stream is restarted; the living room never notices. */
    private fun rejoin(reason: String) {
        val now = System.nanoTime()
        if (now - lastRejoinNanos < REJOIN_COOLDOWN_NANOS) {
            rejoinsSuppressed++
            return
        }
        lastRejoinNanos = now
        rejoins++
        lastRejoinReason = reason
        AppLog.add(TAG, "re-join #$rejoins: $reason")
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
    fun streamLost(s: KitchenSession, reason: String) {
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
        val dev = device ?: return false
        val v = Settings.values
        if (!v.kitchenEnabled || !reachable || busyElsewhere) return false
        if (arbiter.source == SourceArbiter.Source.LIBRESPOT) return false
        var s = session
        if (s != null) {
            if (!s.isCapture) return false
        } else {
            val host = Net.localAddressFor(dev.ip) ?: return false
            s = KitchenSession(
                newToken(), CAPTURE_EPOCH, KIND_CAPTURE, -1L, StreamBuilder.ChannelMode.STEREO, feed, feed.generation,
            )
            s.uri = "http://$host:${DebugServer.PORT}/k/${s.token}.flac"
            sessions[s.token] = s
            session = s
            state = State.STARTING
            AppLog.add(TAG, "capture: getting the kitchen ready ($why), session ${s.token}")
            if (!prepare(s, soapFor(dev))) return false
            if (s.cancelled || session !== s) return false
        }
        if (play) playCapture(s)
        return true
    }

    /** Picks the frame the stream begins at and sends Play shortly. The speaker has been prepared. */
    private fun playCapture(s: KitchenSession) {
        val feed = s.capture ?: return
        if (s.cancelled || s.armed || s.startRingFrame >= 0) return // Play is on its way already
        if (s.captureGeneration != feed.generation) {
            endSession(s, "the capture started again", true)
            return
        }
        val v = Settings.values
        val tCmd = System.nanoTime() + HOT_JOIN_LEAD_NANOS
        val plan = CaptureStart.plan(
            nowFrame = feed.frameAtReadClock(tCmd),
            writeIndex = feed.buffer.writeIndex,
            validStart = feed.buffer.validStart,
            rate = feed.rate,
            leadMs = captureLead.effectiveMs(v.captureLeadMs),
            offsetMs = v.kitchenOffsetMs,
            lkMs = lkNanos / 1e6,
        )
        s.startRingFrame = plan.startFrame
        capturePlannedLagMs = plan.plannedLagMs
        captureStarts++
        AppLog.add(
            TAG,
            "capture: Play in ${(tCmd - System.nanoTime()) / 1_000_000L} ms, the stream begins " +
                "${plan.aheadFrames * 1000L / feed.rate} ms before now (frame ${plan.startFrame}" +
                "${if (plan.limited) ", limited by the buffer" else ""}), planned lag ${fmt(plan.plannedLagMs)} ms",
        )
        timer.schedule(
            Runnable { sendPlay(s, tCmd) },
            maxOf(0L, (tCmd - System.nanoTime()) / 1_000_000L - 3L),
            TimeUnit.MILLISECONDS,
        )
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
        val offset = Settings.values.kitchenOffsetMs
        val outcome = captureTracker.onReading(
            tMid, positionMs, stream.map, { stream.builder.unappliedBudget() }, feed.clock, offset, lkNanos / 1e6,
        )
        when (outcome) {
            is CaptureTracker.Outcome.Locked -> {
                AppLog.add(
                    TAG,
                    "capture: the kitchen sounds ${fmt(outcome.lagMs)} ms after the capture (expected ${fmt(outcome.expectedMs)} ms" +
                        ", Play to sound ${(startNanos - s.playSentNanos) / 1_000_000L} ms), holding that" +
                        if (outcome.leadRaised) "; the speaker needs more than asked, the next stream starts ${captureLead.learnedMs} ms ahead" else "",
                )
                lastSummaryReadings = 0
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
            is CaptureTracker.Outcome.Rejoin -> rejoin(outcome.reason)
            is CaptureTracker.Outcome.Hold, is CaptureTracker.Outcome.Waiting -> Unit
        }
        val n = controller.readings
        if (lagCapture.isLocked && n > 0 && n % SUMMARY_EVERY_READINGS == 0 && n != lastSummaryReadings) {
            lastSummaryReadings = n
            AppLog.add(
                TAG,
                "sync (capture): lag ${fmt(lagCapture.recentMs())} ms (held at ${fmt(lagCapture.targetMs(offset))}), " +
                    "error ${fmt(controller.errorMs)} ms, drift ${fmt(controller.driftPpm)} ppm, " +
                    "trimmed ${controller.trimmedFrames} frames, re-joins $rejoins, round trip ${fmt(lastRttMs)} ms",
            )
        }
    }

    // ---- sessions ------------------------------------------------------------------------------

    fun sessionFor(token: String): KitchenSession? = sessions[token]

    /** Ends a session. With [commands], the speaker is muted and stopped (it may still hold seconds of our audio). */
    private fun endSession(s: KitchenSession, why: String, commands: Boolean) {
        if (!s.cancelled) {
            s.cancelled = true
            s.endReason = why
            sessions.remove(s.token)
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

    private class PollResult(val ok: Boolean, val transport: String, val uri: String, val error: String?)

    private fun triggerPoll(dev: SonosDevice) {
        if (!polling.compareAndSet(false, true)) return
        netPool.execute {
            val result = try {
                val api = SonosSoap(dev.ip)
                val t = api.transportInfo()
                if (!t.ok) {
                    PollResult(false, "", "", t.describe())
                } else {
                    val p = api.positionInfo()
                    PollResult(
                        true,
                        SonosXml.tag(t.body, "CurrentTransportState") ?: "",
                        if (p.ok) SonosXml.tag(p.body, "TrackURI") ?: "" else "",
                        null,
                    )
                }
            } catch (t: Throwable) {
                PollResult(false, "", "", t.toString())
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
        // Something else uses the speaker, or someone paused our stream on the speaker itself (then the
        // kitchen stays quiet until the speaker is stopped or free).
        busyElsewhere = when (kind) {
            SonosPolicy.Availability.BUSY -> true
            SonosPolicy.Availability.OURS -> r.transport == "PAUSED_PLAYBACK"
            SonosPolicy.Availability.FREE -> false
        }
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
    }

    /**
     * The offset the controller works with. For the ring it is the setting. For a captured stream the offset
     * only moves the lag that was measured at the start (see CaptureLag).
     */
    private fun effectiveOffsetMs(): Double {
        val offset = Settings.values.kitchenOffsetMs
        return if (session?.isCapture == true) lagCapture.controllerOffsetMs(offset) else offset.toDouble()
    }

    /** The Connect volume changed (0 to 65535). */
    fun onVolume(raw: Int) {
        lastVolumeRaw = raw
        schedulePush()
    }

    /** The music volume of the box changed (what the phone's Cast volume sets), as a Connect volume 0 to 65535. */
    fun onCaptureVolume(raw: Int) {
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

    /** The volume the Sonos should have: that of the Cast volume for a capture session, else the Spotify one. */
    private fun sonosVolume(): Int {
        val raw = if (captureIsActive()) captureVolumeRaw else lastVolumeRaw
        return if (raw < 0) -1 else VolumeCurve.sonosVolume(raw, Settings.values.kitchenBalance)
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

    // ---- debug actions -------------------------------------------------------------------------

    fun rejoinNow() {
        post("manual re-join") {
            lastRejoinNanos = 0L
            rejoin("asked for on the debug page")
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
        o.put("lk_ms", lkNanos / 1e6)
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
        val cs = session?.takeIf { it.isCapture }
        val offset = Settings.values.kitchenOffsetMs
        val lag = if (cs != null) lagCapture.recentMs() else Double.NaN
        // how far behind the captured moment the kitchen sounds; it includes the kitchen offset, which moves it
        o.put("lag_ms", jsonNumber(round1(lag)))
        o.put("lag_target_ms", jsonNumber(round1(if (cs != null) lagCapture.targetMs(offset) else Double.NaN)))
        o.put("lag_locked", cs != null && lagCapture.isLocked)
        // and behind the living room's sound: the same, less the time the living room needs from capture to sound
        o.put("room_offset_ms", jsonNumber(round1(lag - Settings.values.captureLivingLatencyMs)))
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
            o.put("session", j)
        } else {
            o.put("session", JSONObject.NULL)
        }
        o.put("last_error", lastError ?: JSONObject.NULL)
        return o
    }

    /** "  lag 430 ms" while a captured stream is measured, else nothing (for the screen). */
    fun lagText(): String {
        if (session?.isCapture != true) return ""
        val lag = lagCapture.recentMs()
        return if (lag.isNaN()) "" else "  lag " + Math.round(lag) + " ms"
    }

    private fun jsonNumber(v: Double): Any = if (v.isNaN() || v.isInfinite()) JSONObject.NULL else v

    private fun round1(v: Double): Double = if (v.isNaN() || v.isInfinite()) v else Math.round(v * 10.0) / 10.0

    companion object {
        private const val TAG = "kitchen"
        private const val TITLE = "Whole Flat"
        private const val KIND_CALIBRATION = 3

        /** The kind and the epoch of a session of the capture mode (there is no ring epoch behind it). */
        private const val KIND_CAPTURE = 100
        private const val CAPTURE_EPOCH = -1L
        private const val PREF_IP = "sonos_ip"
        private val KITCHEN_WORDS = listOf("kitchen", "küche", "kuche", "kuhinja", "køkken", "kjøkken", "cuisine", "cocina", "keuken")

        private const val POLL_PERIOD_MS = 3_000L
        private const val READING_PERIOD_MS = 200L
        private const val SUMMARY_EVERY_READINGS = 150
        private const val SEARCH_EVERY_NANOS = 10_000_000_000L
        private const val SEARCH_OFFLINE_EVERY_NANOS = 30_000_000_000L
        private const val WS_RECONNECT_NANOS = 3_000_000_000L

        /** Typical Play-to-sound delay, measured 0.288 s +- 14 ms; learned per run after that. */
        private const val DEFAULT_LK_NANOS = 290_000_000L
        private const val MIN_LK_NANOS = 100_000_000L
        private const val MAX_LK_NANOS = 800_000_000L
        private const val LEARN_LK_AT = 4
        private const val UNMUTE_LEAD_NANOS = 30_000_000L
        private const val HOT_JOIN_LEAD_NANOS = 30_000_000L
        private const val JOIN_MARGIN_FRAMES = 2_205L

        private const val MAX_RTT_NANOS = 60_000_000L
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
