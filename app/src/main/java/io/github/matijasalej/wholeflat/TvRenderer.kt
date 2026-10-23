package io.github.matijasalej.wholeflat

import android.content.Context
import android.os.SystemClock
import io.github.matijasalej.wholeflat.capture.CaptureFeed
import io.github.matijasalej.wholeflat.sync.DmrDescription
import io.github.matijasalej.wholeflat.sync.SessionHistory
import io.github.matijasalej.wholeflat.sync.SonosXml
import io.github.matijasalej.wholeflat.sync.StartBackoff
import io.github.matijasalej.wholeflat.sync.StreamBuilder
import io.github.matijasalej.wholeflat.sync.StreamFormat
import io.github.matijasalej.wholeflat.sync.SyncedTiming
import io.github.matijasalej.wholeflat.sync.TvDidl
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

/**
 * The Philips TV as a second renderer of the captured music (Synced mode): the TV's own DLNA player is given a live stream of the same
 * capture that the Sonos gets, written to it at capture(f) + D - X (see [SyncedTiming]), so that both rooms sound together. The TV
 * gives no position to follow; its timing is a model with one setting (`tv_latency_ms`) that is calibrated on the device, and the
 * stream is held against the capture clock alone (see the hold of [KitchenStreams]).
 *
 * What this class does:
 *  - **grab**: wakes the TV if it sleeps, reads its description (`dmr.xml`), makes a session, and has the TV play it:
 *    Stop, SetAVTransportURI with a DIDL item (title, artist, cover), Play; then it waits for the TV's GET (about 2 s for L16) and
 *    for PLAYING. A start that fails is retried with the back-off of the Sonos ([StartBackoff]); after three failures in a row it
 *    gives up and says so ([onGaveUp]), and the mode manager falls back to Lyrics.
 *  - **keep**: every 2 s it asks the TV's transport how it is. The TV leaving its player (the transport STOPPED, the stream closed
 *    by the TV, the TV not answering) is reported as [onLeft] with what happened; who is in front on this box and what the user
 *    meant is for the mode manager to say.
 *  - **release**: Stop, and the TV returns to its HDMI input by itself; the box then checks that it did.
 *
 * All the work is on one thread of its own; the status is read from any thread.
 */
class TvRenderer(private val context: Context, private val tv: TvControl) : StreamHost {

    enum class State(val label: String) {
        /** Nothing wanted, or released. */
        IDLE("idle"),

        /** A grab is under way: waiting for the TV to ask for the stream and to play it. */
        STARTING("starting"),
        PLAYING("playing"),

        /** The TV left its player (see [leftKind]) or the stream broke; nothing is running. */
        LEFT("left"),

        /** The last start failed; the next waits for the back-off. */
        FAILED("failed"),
    }

    /** How the TV left its player. */
    enum class LeftKind(val label: String) {
        /** Its transport is STOPPED (or nothing is loaded, or paused on the TV itself): an input key, Home, a Cast session, the user. */
        STOPPED("stopped"),

        /** The TV closed the stream and asked for no other. */
        CLOSED("closed"),

        /** The TV does not answer any more (standby, off, network). */
        UNREACHABLE("unreachable"),
    }

    /** What the TV's player shows of the track. */
    class Track(val title: String, val artist: String?, val album: String?, val artUri: String?)

    // ---- wiring (set by the service) -------------------------------------------------------------

    /** The audio the capture hands over. */
    @Volatile
    var captureFeed: CaptureFeed? = null

    /** Whether the capture is running (a grab needs it, and a stream that ends with it is not the TV leaving). */
    @Volatile
    var captureAlive: () -> Boolean = { false }

    /** The track for the DIDL item (title, artist, album, cover); the title of the app when nothing is known. */
    @Volatile
    var track: () -> Track = { Track(TITLE, null, null, null) }

    /** The lag the Sonos is kept to in Lyrics mode (ms after the capture): the other half of the common delay. */
    @Volatile
    var sonosTargetMs: () -> Double = { 322.0 }

    /** The TV left its player. */
    @Volatile
    var onLeft: ((LeftKind, String) -> Unit)? = null

    /** The state changed. */
    @Volatile
    var onState: ((State) -> Unit)? = null

    /** The common delay D changed (a new stream, a changed setting): the Sonos is to be held at it. */
    @Volatile
    var onDelay: ((Double) -> Unit)? = null

    /** Starts failed [GIVE_UP_AFTER] times in a row. */
    @Volatile
    var onGaveUp: ((String) -> Unit)? = null

    // ---- state -----------------------------------------------------------------------------------

    @Volatile
    var state = State.IDLE
        private set

    @Volatile
    var leftKind: LeftKind? = null
        private set

    @Volatile
    var leftWhy = ""
        private set

    /** Times the TV left its player in this run (each departure is handled once). */
    @Volatile
    var leftCount = 0
        private set

    /** The stream format in use ("ts" or "l16"): the setting, or L16 when the video stream failed. */
    @Volatile
    var activeFormat = "l16"
        private set

    /** The common delay D of the rooms (ms after the capture) of the stream in force; NaN when there is none. */
    @Volatile
    var delayMs = Double.NaN
        private set

    /** How long after its capture a frame is written to the TV (ms): D less the TV's own delay. */
    @Volatile
    var holdMs = Double.NaN
        private set

    @Volatile
    var lastError: String? = null
        private set

    private val backoff = StartBackoff()
    private val history = SessionHistory(10)
    private val sessions = ConcurrentHashMap<String, KitchenSession>()
    private val exec = ScheduledThreadPoolExecutor(1, ThreadFactory { r -> Thread(r, "wf-tvdlna").also { it.isDaemon = true } })

    @Volatile
    private var session: KitchenSession? = null

    @Volatile
    private var soap: SonosSoap? = null

    @Volatile
    private var description: DmrDescription? = null
    private var describedIp = ""

    @Volatile
    private var sinkProtocols = ""

    @Volatile
    private var cancelRequested = false

    @Volatile
    private var lastTransport = ""

    @Volatile
    private var lastTransportStatus = ""

    @Volatile
    private var lastContext = ""

    @Volatile
    private var lastAction = ""

    /** A start may not begin before this (SystemClock.elapsedRealtime): the TV was not on, or its address is not known. */
    @Volatile
    private var notBeforeMs = 0L

    private var unreachablePolls = 0
    private var stoppedPolls = 0
    private var closedPolls = 0
    private var playingSinceMs = 0L
    private var lastBackoffLogMs = 0L

    @Volatile
    private var grabs = 0

    @Volatile
    private var startedAtWallMs = 0L

    // ---- life cycle ------------------------------------------------------------------------------

    fun start() {
        StreamRouter.add(this)
        exec.scheduleWithFixedDelay({ post("poll") { poll() } }, POLL_PERIOD_MS, POLL_PERIOD_MS, TimeUnit.MILLISECONDS)
        AppLog.add(TAG, "the TV renderer started")
    }

    /** The app stops: the TV is given back (Stop), as far as it answers within a moment. */
    fun stop() {
        StreamRouter.remove(this)
        try {
            val s = session
            val api = soap
            if (s != null && api != null && (state == State.PLAYING || state == State.STARTING)) {
                cancelRequested = true
                endSession(s, "the app stopped")
                api.stop()
            }
        } catch (t: Throwable) {
            AppLog.error(TAG, "giving the TV back failed", t)
        }
        exec.shutdownNow()
    }

    private fun post(name: String, block: () -> Unit) {
        try {
            exec.execute {
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

    private fun nowMs(): Long = SystemClock.elapsedRealtime()

    private fun setState(s: State) {
        if (state == s) return
        state = s
        try {
            onState?.invoke(s)
        } catch (t: Throwable) {
            AppLog.error(TAG, "the state hook failed", t)
        }
    }

    // ---- what the mode manager asks for ----------------------------------------------------------

    /** Has the TV play the capture. Returns at once; [state] tells how it goes. */
    fun grab(reason: String) {
        cancelRequested = false
        post("grab") { doGrab(reason) }
    }

    /** Gives the TV back (Stop). */
    fun release(reason: String) {
        cancelRequested = true
        post("release") { doRelease(reason) }
    }

    /** The time the next start may begin after failed ones or a TV that was not on (ms), 0 if it may begin. */
    fun backoffRemainingMs(): Long = maxOf(backoff.remainingMs(nowMs()), notBeforeMs - nowMs(), 0L)

    /** Failed starts in a row. */
    fun failedGrabs(): Int = backoff.failures

    /** The settings that the timing is built from changed: the common delay is worked out again. */
    fun settingsChanged() = post("settings") { refreshTiming(force = true) }

    // ---- the grab --------------------------------------------------------------------------------

    private fun doGrab(reason: String) {
        if (state == State.STARTING || state == State.PLAYING) return
        val now = nowMs()
        if (now < notBeforeMs) return
        if (!backoff.allowed(now)) {
            if (now - lastBackoffLogMs > 5_000) {
                lastBackoffLogMs = now
                AppLog.add(TAG, "grab held back: ${backoff.failures} starts failed in a row, the next may begin in ${backoff.remainingMs(now) / 1000} s")
            }
            return
        }
        val feed = captureFeed
        if (feed == null || !captureAlive()) {
            lastError = "no capture to give the TV"
            return
        }
        val ip = tv.currentIp()
        if (ip == null) {
            notBeforeMs = nowMs() + NOT_ON_WAIT_MS
            lastError = "the address of the TV is not known"
            return
        }
        grabs++
        setState(State.STARTING)
        leftKind = null
        note("grab: $reason")
        if (!tv.ensureOn()) {
            // a TV that is off or asleep is no failure of the stream: wait a little before asking again, and do not count it
            notBeforeMs = nowMs() + NOT_ON_WAIT_MS
            lastError = "the TV is not on"
            AppLog.add(TAG, "the TV is not on, trying again in ${NOT_ON_WAIT_MS / 1000} s at the earliest")
            setState(State.IDLE)
            return
        }
        if (cancelRequested) return cancelled()
        val api = connect(ip)
        refreshTiming(force = true)
        val d = delayMs
        val hold = holdMs
        val host = Net.localAddressFor(ip)
        if (host == null) {
            fail("this box has no address that reaches the TV")
            return
        }

        val s = KitchenSession(
            UUID.randomUUID().toString().replace("-", "").substring(0, 12), TV_EPOCH, KIND_TV, -1L, StreamBuilder.ChannelMode.STEREO,
            feed, feed.generation, format = StreamFormat.L16,
        )
        s.realTime = true
        s.startAtConnect = { began -> startFrameAt(feed, began) }
        s.holdTargetNanos = { (holdMs * 1_000_000.0).toLong() }
        s.sendBufferBytes = SEND_BUFFER_BYTES
        s.statsEveryMs = STATS_EVERY_MS
        s.uri = "http://$host:${DebugServer.PORT}/k/${s.token}.${s.format.ext}"
        s.record = history.begin(s.token, "tv", System.nanoTime(), System.currentTimeMillis())
        s.arm(System.nanoTime() + 2_000_000_000L)
        sessions[s.token] = s
        session = s
        startedAtWallMs = System.currentTimeMillis()
        val t = track()
        val didl = TvDidl.track(t.title, t.artist, t.album, t.artUri, s.uri)
        AppLog.add(
            TAG,
            "TV stream ${s.token}: ${s.uri}; common delay ${fmt(d)} ms, the TV's frames are written ${fmt(hold)} ms after their capture " +
                "(TV latency ${tvLatencyMs()} ms, pipeline ${fmt(feed.pipelineMs())} ms)",
        )

        // the TV's player: Stop (it may hold an old stream of ours), the item, Play
        api.stop()
        var set = api.setUri(s.uri, didl)
        var tries = 1
        while (!set.ok && set.upnpError == 701 && tries < 3) {
            sleepQuietly(150)
            set = api.setUri(s.uri, didl)
            tries++
        }
        if (!set.ok) {
            fail("SetAVTransportURI: ${set.describe()}")
            return
        }
        s.record?.playSent(System.nanoTime())
        var play = api.play()
        tries = 1
        while (!play.ok && play.upnpError == 701 && tries < 3) {
            sleepQuietly(150)
            play = api.play()
            tries++
        }
        if (!play.ok) {
            fail("Play: ${play.describe()}")
            return
        }
        val playSent = nowMs()
        if (!waitFor(GET_WAIT_MS) { s.connectionCount > 0 }) {
            if (cancelRequested) return cancelled()
            fail("the TV did not ask for the stream within ${GET_WAIT_MS / 1000} s (transport ${transportNow(api)})")
            return
        }
        val getAfter = nowMs() - playSent
        if (!waitFor(PLAYING_WAIT_MS) { transportNow(api) == "PLAYING" }) {
            if (cancelRequested) return cancelled()
            fail("the TV asked for the stream ${getAfter} ms after Play but did not play (transport $lastTransport)")
            return
        }
        playingSinceMs = nowMs()
        unreachablePolls = 0
        stoppedPolls = 0
        closedPolls = 0
        AppLog.add(TAG, "the TV plays: its first GET came ${getAfter} ms after Play, PLAYING ${nowMs() - playSent} ms after Play (context ${tv.context() ?: "?"})")
        setState(State.PLAYING)
    }

    private fun cancelled() {
        // a release came while the start was under way
        session?.let { endSession(it, "released during the start") }
        session = null
        setState(State.IDLE)
    }

    private fun fail(why: String) {
        lastError = why
        session?.let { endSession(it, "failed: $why") }
        session = null
        val wait = backoff.onFailure(nowMs())
        AppLog.error(TAG, "the TV did not take the stream (${backoff.failures} in a row): $why" + if (wait > 0) "; the next start waits ${wait / 1000} s" else "")
        setState(State.FAILED)
        if (backoff.failures >= GIVE_UP_AFTER) {
            try {
                onGaveUp?.invoke(why)
            } catch (t: Throwable) {
                AppLog.error(TAG, "the give-up hook failed", t)
            }
        }
    }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val end = nowMs() + timeoutMs
        while (nowMs() < end) {
            if (cancelRequested || exec.isShutdown) return false
            if (condition()) return true
            sleepQuietly(100)
        }
        return condition()
    }

    /** The TV's transport state now ("" if it does not answer), also kept for the status. */
    private fun transportNow(api: SonosSoap): String {
        val t = api.transportInfo()
        if (!t.ok) return ""
        val st = SonosXml.tag(t.body, "CurrentTransportState") ?: ""
        lastTransport = st
        lastTransportStatus = SonosXml.tag(t.body, "CurrentTransportStatus") ?: ""
        return st
    }

    // ---- the renderer ----------------------------------------------------------------------------

    /** The client of the TV's DMR; its description is read once per address (and the sink protocols logged). */
    private fun connect(ip: String): SonosSoap {
        var d = description
        if (d == null || describedIp != ip) {
            d = fetchDescription(ip) ?: DmrDescription.philipsFallback(ip).also { AppLog.add(TAG, "the description of the TV cannot be read, the paths of the manual test are used") }
            description = d
            describedIp = ip
            soap = null
        }
        var api = soap
        if (api == null) {
            val av = d.control("AVTransport")
            val rc = d.control("RenderingControl")
            val cm = d.control("ConnectionManager")
            api = SonosSoap(
                ip, av?.port ?: DMR_PORT, av?.path ?: "/control/AVTransport", rc?.path ?: "/control/RenderingControl",
                cm?.path ?: "/control/ConnectionManager", av?.serviceType ?: SonosXml.AVTRANSPORT,
                rc?.serviceType ?: SonosXml.RENDERING, cm?.serviceType ?: SonosXml.CONNECTIONMANAGER,
            )
            soap = api
            AppLog.add(TAG, "the TV is ${d.friendlyName} (${d.manufacturer} ${d.modelName}): AVTransport on port ${api.port} ${av?.path}")
            val sinks = api.protocolInfo()
            sinkProtocols = if (sinks.ok) (SonosXml.tag(sinks.body, "Sink") ?: "").take(1500) else "not readable (${sinks.describe()})"
            AppLog.add(TAG, "the TV accepts: ${sinkProtocols.take(600)}")
        }
        return api
    }

    private fun fetchDescription(ip: String): DmrDescription? {
        val location = "http://$ip:$DMR_PORT/dmr.xml"
        return try {
            val conn = URL(location).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 2_000
                conn.readTimeout = 3_000
                if (conn.responseCode !in 200..299) return null
                val xml = conn.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
                DmrDescription.parse(xml, location)
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            lastError = "dmr.xml: $e"
            null
        }
    }

    // ---- the timing ------------------------------------------------------------------------------

    /** X, the TV's own delay from our write to its sound (ms), for the format in use. */
    private fun tvLatencyMs(): Int = SyncedTiming.tvLatencyMs(Settings.values.tvLatencyMs, activeFormat)

    /**
     * The common delay D of the rooms and the hold of the TV's stream from the settings and the pipeline of the capture. Only a change
     * of 15 ms or more (or [force]: a new stream, a changed setting) moves it, so that the pipeline's small variations do not make
     * the Sonos chase them.
     */
    private fun refreshTiming(force: Boolean) {
        val feed = captureFeed ?: return
        val x = tvLatencyMs().toDouble()
        val d = SyncedTiming.delayMs(x, feed.pipelineMs(), sonosTargetMs())
        if (!force && !delayMs.isNaN() && Math.abs(d - delayMs) < DELAY_HYSTERESIS_MS) return
        delayMs = d
        holdMs = SyncedTiming.tvHoldMs(d, x)
        try {
            onDelay?.invoke(d)
        } catch (t: Throwable) {
            AppLog.error(TAG, "the delay hook failed", t)
        }
    }

    /** The first source frame of a connection made at [nowNanos]: the frame captured one hold ago. */
    private fun startFrameAt(feed: CaptureFeed, nowNanos: Long): Long = SyncedTiming.startFrame(
        nowNanos = nowNanos,
        holdNanos = (holdMs * 1_000_000.0).toLong(),
        captureFrameAt = { n -> feed.clock.frameAtNanos(n) },
        fallbackFrame = feed.buffer.writeIndex,
        pipelineMs = feed.pipelineMs(),
        rate = feed.rate,
        oldestFrame = feed.buffer.validStart + feed.rate / 4,
    )

    // ---- keeping it ------------------------------------------------------------------------------

    private fun poll() {
        val s = session ?: return
        if (state != State.PLAYING) return
        val api = soap ?: return
        val feed = captureFeed
        if (feed == null || !captureAlive() || feed.generation != s.captureGeneration) {
            // the capture ended or started again: that is not the TV leaving; the stream is made again with the capture
            restart("the capture changed")
            return
        }
        refreshTiming(force = false)
        val t = api.transportInfo()
        if (!t.ok) {
            unreachablePolls++
            if (unreachablePolls >= UNREACHABLE_POLLS) left(LeftKind.UNREACHABLE, "the TV does not answer (${t.describe()})")
            return
        }
        unreachablePolls = 0
        val st = SonosXml.tag(t.body, "CurrentTransportState") ?: ""
        val status = SonosXml.tag(t.body, "CurrentTransportStatus") ?: ""
        if (st != lastTransport || status != lastTransportStatus) AppLog.add(TAG, "the TV's transport is $st ($status)")
        lastTransport = st
        lastTransportStatus = status
        val ctx = tv.context() ?: ""
        if (ctx != lastContext) {
            AppLog.add(TAG, "the TV shows: ${ctx.ifEmpty { "?" }} (was ${lastContext.ifEmpty { "?" }})")
            lastContext = ctx
        }
        if (st == "STOPPED" || st == "NO_MEDIA_PRESENT" || st == "PAUSED_PLAYBACK") {
            stoppedPolls++
            if (stoppedPolls >= STOPPED_POLLS) left(LeftKind.STOPPED, "the TV's transport is $st")
            return
        }
        stoppedPolls = 0
        // the TV closed its stream and asked for none other
        val sincePlaying = nowMs() - playingSinceMs
        if (s.streamCount == 0 && sincePlaying > CLOSED_AFTER_MS) {
            closedPolls++
            if (closedPolls >= CLOSED_POLLS) left(LeftKind.CLOSED, "the TV closed the stream and asked for no other")
            return
        }
        closedPolls = 0
        if (sincePlaying > HEALTHY_AFTER_MS) backoff.onHealthy()
    }

    private fun left(kind: LeftKind, why: String) {
        val s = session
        leftKind = kind
        leftWhy = why
        leftCount++
        AppLog.add(TAG, "the TV left its player (${kind.label}): $why")
        s?.let { endSession(it, "the TV left: $why") }
        session = null
        setState(State.LEFT)
        try {
            onLeft?.invoke(kind, why)
        } catch (t: Throwable) {
            AppLog.error(TAG, "the left hook failed", t)
        }
    }

    private fun restart(why: String) {
        AppLog.add(TAG, "the TV stream is made again: $why")
        doRelease(why)
        cancelRequested = false
        doGrab(why)
    }

    private fun doRelease(reason: String) {
        val s = session
        val api = soap
        if (s != null) endSession(s, reason)
        session = null
        val wasOn = state == State.PLAYING || state == State.STARTING
        if (api != null && wasOn) {
            val r = api.stop()
            note("release: $reason; Stop ${r.describe()}")
            // the TV returns to its HDMI input by itself after Stop; if it does not, the box asks for it
            val end = nowMs() + RETURN_WAIT_MS
            var back = false
            while (nowMs() < end) {
                if (tv.context() == "WatchExtension") {
                    back = true
                    break
                }
                sleepQuietly(300)
            }
            AppLog.add(TAG, "the TV is ${if (back) "back on HDMI" else "still showing ${tv.lastContext.ifEmpty { "?" }} ${RETURN_WAIT_MS / 1000} s after Stop"}")
        }
        setState(State.IDLE)
    }

    private fun endSession(s: KitchenSession, why: String) {
        if (!s.cancelled) {
            s.cancelled = true
            s.endReason = why
            sessions.remove(s.token)
            s.record?.ended(System.nanoTime(), why)
            AppLog.add(TAG, "TV stream ${s.token} ended: $why")
        }
        s.closeSockets()
    }

    private fun note(text: String) {
        lastAction = text
        AppLog.add(TAG, text)
    }

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            // the callers check their flags
        }
    }

    private fun fmt(v: Double): String = if (v.isNaN()) "-" else String.format(java.util.Locale.US, "%.1f", v)

    // ---- StreamHost ------------------------------------------------------------------------------

    override fun sessionFor(token: String): KitchenSession? = sessions[token]

    override fun streamLost(s: KitchenSession, reason: String) {
        post("stream lost") {
            if (session === s && !s.cancelled) restart("the frames are gone ($reason)")
        }
    }

    // ---- status ----------------------------------------------------------------------------------

    fun lastSessionsJson(): JSONArray = history.toJson(System.nanoTime())

    fun statusJson(): JSONObject {
        val o = JSONObject()
        o.put("state", state.label)
        val v = Settings.values
        o.put("enabled", v.mode == "synced")
        o.put("left", leftKind?.label ?: JSONObject.NULL)
        o.put("left_why", leftWhy.ifEmpty { JSONObject.NULL })
        o.put("left_count", leftCount)
        o.put("grabs", grabs)
        o.put("failed_grabs", backoff.failures)
        o.put("backoff_s", backoff.remainingMs(nowMs()) / 1000)
        o.put("delay_ms", jsonNumber(delayMs))
        o.put("hold_ms", jsonNumber(holdMs))
        o.put("tv_latency_ms", tvLatencyMs())
        o.put("tv_latency_setting", v.tvLatencyMs)
        o.put("format", activeFormat)
        o.put("sndbuf_kb", SEND_BUFFER_BYTES / 1024)
        o.put("pipeline_ms", jsonNumber(captureFeed?.pipelineMs() ?: Double.NaN))
        o.put("transport", lastTransport.ifEmpty { JSONObject.NULL })
        o.put("transport_status", lastTransportStatus.ifEmpty { JSONObject.NULL })
        o.put("context", tv.lastContext.ifEmpty { JSONObject.NULL })
        val d = description
        if (d != null) {
            val dj = JSONObject()
            dj.put("location", d.location)
            dj.put("name", d.friendlyName)
            dj.put("manufacturer", d.manufacturer)
            dj.put("model", d.modelName)
            val api = soap
            if (api != null) dj.put("port", api.port)
            o.put("renderer", dj)
        } else {
            o.put("renderer", JSONObject.NULL)
        }
        o.put("sink_protocols", sinkProtocols.ifEmpty { JSONObject.NULL })
        val s = session
        if (s != null) {
            val j = JSONObject()
            j.put("token", s.token)
            j.put("url", s.uri)
            j.put("format", s.format.key)
            j.put("connections", s.connectionCount)
            j.put("open_connections", s.streamCount)
            j.put("bytes_sent", s.bytesSent.get())
            j.put("age_s", (System.nanoTime() - s.createdNanos) / 1_000_000_000L)
            o.put("session", j)
        } else {
            o.put("session", JSONObject.NULL)
        }
        o.put("last_action", lastAction.ifEmpty { JSONObject.NULL })
        o.put("last_error", lastError ?: JSONObject.NULL)
        return o
    }

    private fun jsonNumber(v: Double): Any = if (v.isNaN() || v.isInfinite()) JSONObject.NULL else Math.round(v * 10.0) / 10.0

    companion object {
        private const val TAG = "tvdlna"
        private const val TITLE = "Whole Flat"

        /** The send buffer asked of the TV's socket (bytes): small, so that a TV that reads slower than real time makes writes block early. */
        private const val SEND_BUFFER_BYTES = 16 * 1024
        private const val DMR_PORT = 2870
        const val KIND_TV = 101
        private const val TV_EPOCH = -2L

        private const val POLL_PERIOD_MS = 2_000L

        /** The TV asks for the stream about 2 s after Play (L16); a video stream takes longer. */
        private const val GET_WAIT_MS = 25_000L
        private const val PLAYING_WAIT_MS = 20_000L

        private const val UNREACHABLE_POLLS = 3
        private const val STOPPED_POLLS = 2
        private const val CLOSED_POLLS = 3
        private const val CLOSED_AFTER_MS = 8_000L
        private const val HEALTHY_AFTER_MS = 15_000L
        private const val RETURN_WAIT_MS = 6_000L

        /** A TV that was not on is asked again after this long at the earliest. */
        private const val NOT_ON_WAIT_MS = 15_000L

        /** Failed starts in a row after which the mode manager is told to give up. */
        const val GIVE_UP_AFTER = 3

        /** The statistics of a connection go to the log this often. */
        private const val STATS_EVERY_MS = 5_000L

        /** A new common delay within this much of the one in force is not taken. */
        private const val DELAY_HYSTERESIS_MS = 15.0
    }
}
