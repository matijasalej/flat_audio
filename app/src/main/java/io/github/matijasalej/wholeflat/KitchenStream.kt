package io.github.matijasalej.wholeflat

import io.github.matijasalej.wholeflat.capture.CaptureFeed
import io.github.matijasalej.wholeflat.capture.CaptureFrames
import io.github.matijasalej.wholeflat.sync.CalibrationClicks
import io.github.matijasalej.wholeflat.sync.ClickSource
import io.github.matijasalej.wholeflat.sync.Flac
import io.github.matijasalej.wholeflat.sync.FrameSource
import io.github.matijasalej.wholeflat.sync.HoldController
import io.github.matijasalej.wholeflat.sync.KitchenMap
import io.github.matijasalej.wholeflat.sync.L16
import io.github.matijasalej.wholeflat.sync.SessionClock
import io.github.matijasalej.wholeflat.sync.SessionRecord
import io.github.matijasalej.wholeflat.sync.StreamBuilder
import io.github.matijasalej.wholeflat.sync.StreamFormat
import io.github.matijasalej.wholeflat.sync.TsFeed
import io.github.matijasalej.wholeflat.sync.TsHistory
import io.github.matijasalej.wholeflat.sync.Wav
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * One stream connection of the Sonos after Play: its map from stream frames to source frames and its trims.
 * Every connection of a session has its own builder; the one the controller steers is [KitchenSession.stream].
 */
class ActiveStream(val connectionId: Int, val map: KitchenMap, val builder: StreamBuilder, val startNanos: Long = System.nanoTime())

/**
 * One stream for the Sonos: the FLAC of the ring from [startRingFrame] on, behind a URL of its own.
 * A session belongs to one epoch of the ring; a new epoch, a re-join or a pause ends it. A session of the
 * capture mode ([capture] is set) streams the captured audio of another app instead of the ring, at the
 * capture's sample rate, from a frame of one capture attempt ([captureGeneration]).
 *
 * The Sonos asks for the URL in its own way: a GET that reads a frame or two and is closed by the Sonos
 * (the probe), a GET that is the stream proper, a HEAD, in whatever order and at whatever moment. Every
 * connection is served for itself from the start of the stream, until the Sonos closes it or the session
 * ends; none is ever closed because another one came. A connection that comes after [arm] is a candidate
 * for the stream proper ([attach]); the newest one that is still open is the one the controller steers.
 */
class KitchenSession(
    val token: String,
    val epoch: Long,
    val kind: Int,
    startRingFrame: Long,
    val channels: StreamBuilder.ChannelMode,
    val capture: CaptureFeed? = null,
    val captureGeneration: Long = 0L,
    /** The format of the stream (the setting `kitchen_format` when the session was made), and the block of a FLAC one. */
    val format: StreamFormat = StreamFormat.FLAC,
    val flacBlock: Int = Flac.BLOCK,
) {
    val isCapture: Boolean get() = capture != null

    /**
     * The stream of a captured player was made while Synced mode held the kitchen at the common delay: it is set once and left alone
     * (see SyncedHold) instead of being steered by the controller of the Lyrics mode. Fixed when the session is made.
     */
    @Volatile
    var syncedHold = false

    /** The clock the lag of a stream that is set once is counted from (the TV's stream, see Kitchen.syncedClock); null: the capture's. */
    @Volatile
    var refClock: io.github.matijasalej.wholeflat.sync.FrameTimes? = null

    /** Frames per block of the stream: the FLAC block, or the small chunk of a WAV or L16 stream, which have no block structure. */
    val blockFrames: Int get() = if (format == StreamFormat.FLAC) flacBlock else StreamFormat.WAV_CHUNK_FRAMES

    /** The sample rate of the stream: the ring's 44.1 kHz, or the capture's. */
    val rate: Int get() = capture?.rate ?: RING_RATE

    /** First ring frame of the stream (a captured frame in capture mode); negative while it is not known yet (a hot-join picks it at Play time). */
    @Volatile
    var startRingFrame: Long = startRingFrame

    @Volatile
    var uri = ""

    /**
     * For a stream whose start is decided when the renderer connects (the TV's): the first source frame for a connection made at
     * the given moment (System.nanoTime). The TV opens its connection about 2 s after Play and again later, and every connection
     * starts at "now".
     */
    @Volatile
    var startAtConnect: ((Long) -> Long)? = null

    /**
     * The stream is sent in real time from the moment each connection is made, as a renderer that pulls a live stream wants it,
     * whether Play was sent before or not. The Sonos's streams are not (the captured one is sent as it is captured, the ring's
     * one ahead of the speaker's position).
     */
    @Volatile
    var realTime = false

    /** Digital silence (frames) sent at once at the start of a real-time connection: it adds to the renderer's delay. */
    @Volatile
    var leadInFrames = 0

    /**
     * The delay wanted between the capture of a frame and its write to the renderer (ns); the stream is held there with frame
     * trims, against the capture clock. Null: no hold.
     */
    @Volatile
    var holdTargetNanos: (() -> Long)? = null

    /** The send buffer to ask of the socket (bytes; 0: leave it as the system has it). */
    @Volatile
    var sendBufferBytes = 0

    /** For a stream of [StreamFormat.TS]: the live transport stream that the connections read from. */
    @Volatile
    var ts: TsFeed? = null

    /** Called at every GET of the renderer, with the time (System.nanoTime): the warm-up of the TV counts them. */
    @Volatile
    var onGet: ((Long) -> Unit)? = null

    /** The time of the last GET (System.nanoTime), 0 before the first. */
    @Volatile
    var lastGetNanos = 0L

    /** The pitch of the click track that replaces the music of a captured stream while the calibration clicks are on (Hz; 0: never). */
    @Volatile
    var clickHz = 0

    private val tsOpen = AtomicInteger()

    /** The backlog (ms: how old the key picture was that the connection started at, when it was made) of every open connection of a transport stream, by connection number. */
    private val tsBacklogs = java.util.concurrent.ConcurrentSkipListMap<Int, Double>()

    /** Called when the set of open connections of a transport stream changed (one was made or one ended): the TV's latency may be another. */
    @Volatile
    var onTsChange: (() -> Unit)? = null

    fun tsConnectionOpened() {
        tsOpen.incrementAndGet()
    }

    fun tsConnectionClosed() {
        tsOpen.decrementAndGet()
    }

    /** The connection [id] started at a key picture that was [backlogMs] old. */
    fun tsStarted(id: Int, backlogMs: Double) {
        tsBacklogs[id] = backlogMs
        notifyTsChange()
    }

    /** The connection [id] ended. */
    fun tsEnded(id: Int) {
        if (tsBacklogs.remove(id) != null) notifyTsChange()
    }

    private fun notifyTsChange() {
        try {
            onTsChange?.invoke()
        } catch (t: Throwable) {
            AppLog.error("stream", "the connection hook failed", t)
        }
    }

    /**
     * The backlog of the connection that the TV plays: the newest one that is open (the TV makes some short ones to look at the stream and
     * stays on the last); NaN if none is open. The TV's latency is this plus a constant.
     */
    fun liveBacklogMs(): Double = tsBacklogs.lastEntry()?.value ?: Double.NaN

    /** The statistics of a connection are put in the log every this many ms (0: not). */
    @Volatile
    var statsEveryMs = 0L

    @Volatile
    var armed = false
        private set

    /** When the Sonos plays which stream frame; set by [arm], refined by every reading. */
    @Volatile
    var clock: SessionClock? = null
        private set

    @Volatile
    var cancelled = false

    @Volatile
    var endReason: String? = null

    @Volatile
    var playSentNanos = 0L

    @Volatile
    var firstPlayingNanos = 0L

    /** Readings in which the Sonos reported that it plays. */
    @Volatile
    var playingReadings = 0

    /** The log of this session's connections and of what the speaker showed (see `/kitchen/last-sessions`). */
    @Volatile
    var record: SessionRecord? = null

    /** The session went on well for a while: a start that is over for the back-off. */
    @Volatile
    var healthy = false

    /** The end of this session was counted as a failed start already. */
    @Volatile
    var failureCounted = false

    private val streams = ArrayList<ActiveStream>()

    /** The connection the controller steers: the newest one after Play that is still open; null if there is none. */
    @Volatile
    var stream: ActiveStream? = null
        private set

    private val conns = HashSet<ClientConn>()

    fun addConn(conn: ClientConn) {
        synchronized(conns) { conns.add(conn) }
    }

    fun removeConn(conn: ClientConn) {
        synchronized(conns) { conns.remove(conn) }
    }

    /** Closes the connections of this session: a stream thread blocked in a write to a stalled Sonos ends. */
    fun closeSockets() {
        val open = synchronized(conns) { conns.toList() }
        for (conn in open) {
            try {
                conn.closeNow()
            } catch (e: Exception) {
                // closed already
            }
        }
    }

    val bytesSent = AtomicLong()
    val blocksSent = AtomicLong()
    private val connections = AtomicInteger()
    val createdNanos = System.nanoTime()

    fun nextConnectionId(): Int = connections.incrementAndGet()

    /** GET connections so far. */
    val connectionCount: Int get() = connections.get()

    /** Play is about to be sent: the Sonos should play stream frame 0 at [predictedStartNanos]. */
    fun arm(predictedStartNanos: Long) {
        clock = SessionClock(predictedStartNanos, rate)
        armed = true
    }

    /** A connection that came after Play begins to stream: it is the one the controller steers until it ends. */
    fun attach(active: ActiveStream) {
        synchronized(streams) {
            streams.add(active)
            stream = active
        }
    }

    /** A connection ended; the newest one that is still open (if any) is the one steered again. */
    fun detach(active: ActiveStream) {
        synchronized(streams) {
            streams.remove(active)
            stream = streams.lastOrNull()
        }
    }

    /** Open connections after Play (of a transport stream: all that are open). */
    val streamCount: Int get() = synchronized(streams) { streams.size } + tsOpen.get()

    companion object {
        /** The ring plays 44.1 kHz. */
        const val RING_RATE = 44_100
    }
}

/** The ring as the kitchen's source of frames (read by absolute index, the living room is not affected). */
class RingFrameSource(private val epoch: Long) : FrameSource {
    private val meta = LongArray(12)

    override fun read(cursor: Long, out: ShortArray, maxFrames: Int, timeoutMs: Int): Int {
        val n = NativeBridge.kRead(cursor, epoch, out, maxFrames, timeoutMs, meta)
        return when (n) {
            NativeBridge.CLOSED -> FrameSource.CLOSED
            NativeBridge.LAPPED -> FrameSource.LAPPED
            NativeBridge.EPOCH_CHANGED -> FrameSource.EPOCH
            else -> n
        }
    }
}

/**
 * For the probe before Play: real frames when the ring has them at once, silence when it does not, so
 * that the Sonos is never kept waiting for the first bytes of a stream it only wants to look at.
 */
class ProbeSource(private val inner: FrameSource) : FrameSource {
    override fun read(cursor: Long, out: ShortArray, maxFrames: Int, timeoutMs: Int): Int {
        val n = inner.read(cursor, out, maxFrames, minOf(timeoutMs, 30))
        if (n != 0) return n
        java.util.Arrays.fill(out, 0, maxFrames * 2, 0.toShort())
        return maxFrames
    }
}

/** Silence, for the probe of a session whose first frame is not known yet. */
object SilenceSource : FrameSource {
    override fun read(cursor: Long, out: ShortArray, maxFrames: Int, timeoutMs: Int): Int {
        java.util.Arrays.fill(out, 0, maxFrames * 2, 0.toShort())
        return maxFrames
    }
}

/** What the HTTP side needs from the kitchen. */
interface StreamHost {
    fun sessionFor(token: String): KitchenSession?

    /** The frames of the stream the controller steers are gone. */
    fun streamLost(s: KitchenSession, reason: String)
}

/** One HTTP connection of the Sonos, as the stream server sees it (a socket in the app, a fake in the tests). */
interface ClientConn {
    val out: OutputStream

    /** Before a stream is sent: no Nagle, no read timeout. */
    fun prepareForStream()

    /** Asks for a send buffer of about [bytes], small, so that a renderer that reads slower than real time makes writes block early. */
    fun limitSendBuffer(bytes: Int) {}

    /** Blocks until the peer has closed its side or the connection failed; says which ("eof", "reset: ..."). */
    fun awaitPeerClose(): String

    /** Closes the connection from our side (the session ended); also ends [awaitPeerClose]. */
    fun closeNow()
}

class SocketConn(private val socket: Socket, override val out: OutputStream) : ClientConn {
    override fun prepareForStream() {
        try {
            socket.soTimeout = 0
            socket.tcpNoDelay = true
        } catch (e: Exception) {
            // socket options are a nicety
        }
    }

    override fun limitSendBuffer(bytes: Int) {
        try {
            socket.sendBufferSize = bytes
        } catch (e: Exception) {
            // a nicety
        }
    }

    override fun awaitPeerClose(): String {
        return try {
            val input = socket.getInputStream()
            val buffer = ByteArray(256)
            // The Sonos sends nothing after its request; what comes is ignored. The end of the stream is the news.
            while (input.read(buffer) >= 0) {
                // nothing to do
            }
            "eof"
        } catch (e: IOException) {
            "reset: " + e.message
        }
    }

    override fun closeNow() {
        socket.close()
    }
}

/**
 * The HTTP side of the kitchen stream (`GET /k/<token>.flac` or `.wav`), made after the stream server of the Sonos
 * spikes, which the Sonos plays from: headers as they found it likes them (FLAC without a length, WAV with the
 * length its header claims, no ranges, connection close), then for FLAC the STREAMINFO header and one verbatim
 * frame per block (4096, 1152 or 576 frames), for WAV the RIFF header and the samples in chunks of 10 ms.
 *
 * - **Every connection is for itself.** Each GET is served from the first frame of the stream until the
 *   Sonos closes it or the session ends; a connection is never closed because a newer one came, and a probe
 *   is not closed when Play is sent: the Sonos closes what it opened.
 * - **The first write is the whole lead.** The headers, the STREAMINFO header and every frame that is ready
 *   (or due) go out in one write; after that one write per frame, or per burst of frames.
 * - **A HEAD gets the headers of a GET** and touches nothing.
 * - **Pacing:** a stream after Play is sent a lead of [LEAD_FRAMES] ahead of the position the Sonos is predicted
 *   to play, a captured one as it is captured (it exists no sooner), a probe like the spikes' server: [PROBE_PREBUFFER_NANOS]
 *   of audio at once, then in real time.
 */
object KitchenStreams {
    private const val TAG = "stream"

    /** How far ahead of the Sonos's play position the stream is sent: 1.5 s. */
    const val LEAD_FRAMES = 66_150L

    /** The audio a connection that is not the stream proper gets at once, then real time (the spikes' server used 1 s). */
    const val PROBE_PREBUFFER_NANOS = 1_000_000_000L

    /** One write never carries more than this (a burst of frames is cut here). */
    private const val MAX_WRITE_BYTES = 512 * 1024

    /** The hold of a real-time stream is looked at first after this long, and then this often. */
    private const val HOLD_FIRST_NANOS = 1_000_000_000L
    private const val HOLD_EVERY_NANOS = 200_000_000L

    /** The headers of a FLAC stream: no Content-Length, no chunking, the stream ends by closing the connection. */
    const val RESPONSE_HEAD = "HTTP/1.1 200 OK\r\n" +
        "Content-Type: audio/flac\r\n" +
        "Accept-Ranges: none\r\n" +
        "Cache-Control: no-cache, no-store\r\n" +
        "Connection: close\r\n" +
        "transferMode.dlna.org: Streaming\r\n" +
        "Server: WholeFlat\r\n\r\n"

    /** The headers of a WAV stream: as the spikes' server sent them, with the length that the RIFF header claims. */
    const val RESPONSE_HEAD_WAV = "HTTP/1.1 200 OK\r\n" +
        "Content-Type: audio/wav\r\n" +
        "Content-Length: ${Wav.CONTENT_LENGTH}\r\n" +
        "Accept-Ranges: none\r\n" +
        "Cache-Control: no-cache, no-store\r\n" +
        "Connection: close\r\n" +
        "transferMode.dlna.org: Streaming\r\n" +
        "Server: WholeFlat\r\n\r\n"

    /**
     * The headers of an L16 stream for the TV's renderer, exactly the set that worked in the manual test: the content type with
     * the rate, a live stream, the DLNA features of LPCM, no length (the stream is endless), the connection closed at its end.
     */
    const val RESPONSE_HEAD_L16 = "HTTP/1.1 200 OK\r\n" +
        "Content-Type: ${L16.CONTENT_TYPE}\r\n" +
        "transferMode.dlna.org: Streaming\r\n" +
        "contentFeatures.dlna.org: ${L16.CONTENT_FEATURES}\r\n" +
        "Connection: close\r\n\r\n"

    /**
     * The headers of a transport stream for the TV's renderer, as the device test that the TV played sent them: a live stream of video
     * (`video/mpeg`), no seeking and no connection stalling (the DLNA flags), no length, the connection closed at its end.
     */
    const val RESPONSE_HEAD_TS = "HTTP/1.1 200 OK\r\n" +
        "Content-Type: video/mpeg\r\n" +
        "Accept-Ranges: none\r\n" +
        "Connection: close\r\n" +
        "transferMode.dlna.org: Streaming\r\n" +
        "contentFeatures.dlna.org: DLNA.ORG_OP=00;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000\r\n\r\n"

    /** How long a connection that comes before the stream has a key picture waits for the first one (ms). */
    private const val TS_START_WAIT_MS = 4_000L

    /** One write of a transport stream carries at most this much (the burst at the start goes out in pieces of it). */
    private const val TS_MAX_WRITE_BYTES = 256 * 1024

    /**
     * The frames between two trims of a stream (a trim is one frame dropped or repeated): 10 ms for Spotify's, which is up to
     * 2.3 ms of correction per second; 5 ms for a captured one, up to 4.5 ms per second, which is a speed change of the kitchen
     * of 0.45 %, the most that the lag steering of the capture mode (`kitchen_target_ms`) lets itself use.
     */
    fun trimSpacing(rate: Int, captured: Boolean): Int = if (captured) rate / 200 else rate / 100

    /** The headers of a stream of [format]; the same for a GET and a HEAD. */
    fun responseHead(format: StreamFormat): String = when (format) {
        StreamFormat.WAV -> RESPONSE_HEAD_WAV
        StreamFormat.L16 -> RESPONSE_HEAD_L16
        StreamFormat.TS -> RESPONSE_HEAD_TS
        else -> RESPONSE_HEAD
    }

    /** Where the log lines go (the tests collect them). */
    @Volatile
    var log: (String) -> Unit = { AppLog.add(TAG, it) }

    /** The ring as a source of frames for a session of [epoch] (the tests put a fake here). */
    @Volatile
    var ringFrames: (Long) -> FrameSource = { epoch -> RingFrameSource(epoch) }

    /** The token of a `/k/<token>.flac` (or `.wav`) path, or null. */
    fun tokenOf(path: String): String? {
        if (!path.startsWith("/k/")) return null
        val format = StreamFormat.ofPath(path) ?: return null
        val token = path.substring(3, path.length - 1 - format.ext.length)
        return if (token.isNotEmpty() && token.all { it.isLetterOrDigit() }) token else null
    }

    fun serve(host: StreamHost, token: String, method: String, remote: String, userAgent: String, conn: ClientConn, range: String = "") {
        val out = conn.out
        val session = host.sessionFor(token)
        if (session == null) {
            val body = "no such stream\n".toByteArray(Charsets.UTF_8)
            out.write(
                ("HTTP/1.1 404 Not Found\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1),
            )
            out.write(body)
            out.flush()
            log("$remote $method /k/$token -> 404 (no such session)")
            return
        }
        if (method == "HEAD") {
            // the headers of a GET and nothing else; no session or stream state is touched
            val rec = session.record?.openConnection("HEAD", remote, userAgent, session.armed, System.nanoTime())
            rec?.range = range
            try {
                out.write(responseHead(session.format).toByteArray(Charsets.ISO_8859_1))
                out.flush()
                rec?.let { session.record?.closeConnection(it, System.nanoTime(), "server", "HEAD answered") }
            } catch (e: IOException) {
                rec?.let { session.record?.closeConnection(it, System.nanoTime(), "peer", "peer: " + e.message) }
                throw e
            }
            log("$remote HEAD session ${session.token}${if (range.isNotEmpty()) " Range: $range" else ""} [$userAgent]")
            return
        }
        conn.prepareForStream()
        session.addConn(conn)
        try {
            if (session.format == StreamFormat.TS) streamTs(session, remote, userAgent, conn, range) else stream(host, session, remote, userAgent, conn, range)
        } finally {
            session.removeConn(conn)
        }
    }

    /** Watches a connection for its closing by the Sonos, so that the stream notices at once, also when it sleeps or waits for frames. */
    private class PeerWatch(conn: ClientConn) {
        @Volatile
        var closed = false

        @Volatile
        var how = ""

        init {
            val t = Thread({
                val result = conn.awaitPeerClose()
                how = result
                closed = true
            }, "wf-stream-peer")
            t.isDaemon = true
            t.start()
        }
    }

    private fun stream(host: StreamHost, session: KitchenSession, remote: String, userAgent: String, conn: ClientConn, range: String) {
        val out = conn.out
        val format = session.format
        val blockFrames = session.blockFrames
        val armed = session.armed
        val capture = session.capture
        val rate = session.rate
        val began = System.nanoTime()
        // a real-time stream (the TV's) is live whenever it is asked for; the Sonos's is a probe until Play was sent
        val live = armed || session.realTime
        val startFrame = session.startAtConnect?.invoke(began) ?: session.startRingFrame
        val count = session.nextConnectionId()
        val rec = session.record?.openConnection("GET", remote, userAgent, armed, began)
        rec?.range = range
        val connectionId = rec?.id ?: count
        val frames: FrameSource? = when {
            startFrame < 0 -> null
            capture != null -> {
                val captured = CaptureFrames(capture.buffer, session.captureGeneration)
                // for the calibration of the rooms the music is replaced by the click track of the stream's own pitch
                if (session.clickHz > 0) ClickSource(captured, rate, session.clickHz) { CalibrationClicks.active() } else captured
            }
            else -> ringFrames(session.epoch)
        }
        val source: FrameSource = when {
            frames == null -> SilenceSource
            live -> frames
            else -> ProbeSource(frames)
        }
        val map = KitchenMap(maxOf(0L, startFrame))
        val builder = StreamBuilder(source, map, session.channels, blockFrames = blockFrames, trimSpacing = trimSpacing(rate, capture != null))
        val active = ActiveStream(connectionId, map, builder, began)
        // A captured stream after Play is not paced: its frames exist only as they are captured, so the reads wait for them.
        // A real-time stream is: block k is due k block lengths after the connection was made, whatever time its frames were captured at.
        val lead = LEAD_FRAMES * rate / KitchenSession.RING_RATE
        fun dueNanos(block: Long): Long = when {
            session.realTime -> began + block * blockFrames * 1_000_000_000L / rate
            armed && capture != null -> 0L
            armed -> session.clock?.timeOfFrame(((block + 1) * blockFrames - lead).toDouble()) ?: 0L
            else -> began + maxOf(0L, block * blockFrames * 1_000_000_000L / rate - PROBE_PREBUFFER_NANOS)
        }
        if (live) session.attach(active)
        val peer = PeerWatch(conn)
        log(
            "$remote GET session ${session.token} conn $connectionId from ${if (capture != null) "captured" else "ring"} frame " +
                "$startFrame, " + (if (session.realTime) "a live stream" else if (armed) "the stream" else "a probe before Play") +
                ", ${format.key}" + (if (format == StreamFormat.FLAC) " blocks of $blockFrames" else " chunks of $blockFrames") +
                " at $rate Hz" + (if (range.isNotEmpty()) ", Range: $range" else "") + " [$userAgent]",
        )

        var reason = "peer closed"
        var closedBy = "peer"
        var lost = false
        var blockNo = 0L
        var bytes = 0L
        var dataBytes = 0L
        val pending = ByteArrayOutputStream(64 * 1024)
        var pendingBlocks = 0
        var lastDue = 0L

        // what the TV's renderer does with the stream, for the calibration of its timing model: how long writes block, how late they are
        val statsEveryNanos = session.statsEveryMs * 1_000_000L
        var intervalStart = began
        var intervalBytes = 0L
        var intervalWrites = 0
        var intervalBlockedMs = 0.0
        var intervalBlockedMax = 0.0
        var intervalSlipMax = 0.0

        // the hold: frame f is to be written one hold after it was captured (the TV's timing model); the difference is trimmed away
        val holdTarget = session.holdTargetNanos
        val holdControl = if (holdTarget != null && capture != null) HoldController(rate) else null
        var nextHoldNanos = began + HOLD_FIRST_NANOS
        var holdErrorMs = Double.NaN
        var holdTrimmed = 0L
        fun holdStep(due: Long) {
            val control = holdControl ?: return
            val now = System.nanoTime()
            if (now < nextHoldNanos) return
            nextHoldNanos = now + HOLD_EVERY_NANOS
            val src = map.ringOfStream((blockNo * blockFrames).toDouble()) ?: return
            val capturedAt = capture?.clock?.nanosOfFrame(Math.round(src)) ?: return
            val errorMs = (due - capturedAt - (holdTarget?.invoke() ?: 0L)) / 1e6
            holdErrorMs = errorMs
            val trim = control.trim(errorMs, builder.unappliedBudget())
            if (trim != 0) {
                builder.addTrim(trim)
                holdTrimmed += Math.abs(trim)
            }
            rec?.holdErrorMs = errorMs
            rec?.trimmedFrames = holdTrimmed
        }

        try {
            if (session.sendBufferBytes > 0) conn.limitSendBuffer(session.sendBufferBytes)
            pending.write(responseHead(format).toByteArray(Charsets.ISO_8859_1))
            when (format) {
                StreamFormat.WAV -> pending.write(Wav.header(rate))
                StreamFormat.FLAC -> pending.write(Flac.streamHeader(rate, blockFrames))
                StreamFormat.L16 -> if (session.leadInFrames > 0) pending.write(L16.silence(session.leadInFrames)) // no header; a lead-in of silence, if asked for
                StreamFormat.TS -> Unit // served by streamTs
            }

            // One write for what is ready: the headers, and every frame that is due and exists at this moment.
            fun flush() {
                val size = pending.size()
                if (size == 0) return
                val t0 = System.nanoTime()
                pending.writeTo(out)
                out.flush()
                val t1 = System.nanoTime()
                pending.reset()
                bytes += size
                session.bytesSent.addAndGet(size.toLong())
                session.blocksSent.addAndGet(pendingBlocks.toLong())
                val blockedMs = (t1 - t0) / 1e6
                val slipMs = if (lastDue > 0L) (t0 - lastDue) / 1e6 else 0.0
                if (rec != null) {
                    if (rec.firstByteMs < 0) rec.firstByteMs = session.record?.ms(t1) ?: 0L
                    rec.bytes = bytes
                    rec.blocks = blockNo
                    rec.writes++
                    rec.writeMsTotal += blockedMs
                    if (blockedMs > rec.writeMsMax) rec.writeMsMax = blockedMs
                    if (session.realTime && slipMs > rec.slipMsMax) rec.slipMsMax = slipMs
                }
                pendingBlocks = 0
                if (statsEveryNanos > 0) {
                    intervalBytes += size
                    intervalWrites++
                    intervalBlockedMs += blockedMs
                    if (blockedMs > intervalBlockedMax) intervalBlockedMax = blockedMs
                    if (session.realTime && slipMs > intervalSlipMax) intervalSlipMax = slipMs
                    if (t1 - intervalStart >= statsEveryNanos) {
                        val seconds = (t1 - intervalStart) / 1e9
                        log(
                            "conn $connectionId, last ${"%.1f".format(java.util.Locale.US, seconds)} s: $intervalWrites writes, $intervalBytes bytes " +
                                "(${"%.3f".format(java.util.Locale.US, intervalBytes / seconds / (rate * 4.0))} x real time), a write blocked " +
                                "${"%.1f".format(java.util.Locale.US, intervalBlockedMs / maxOf(1, intervalWrites))} ms on average and " +
                                "${"%.1f".format(java.util.Locale.US, intervalBlockedMax)} ms at most, up to ${"%.1f".format(java.util.Locale.US, intervalSlipMax)} ms behind " +
                                "the schedule, hold error ${if (holdErrorMs.isNaN()) "-" else "%.1f".format(java.util.Locale.US, holdErrorMs)} ms, trimmed $holdTrimmed frames",
                        )
                        intervalStart = t1
                        intervalBytes = 0
                        intervalWrites = 0
                        intervalBlockedMs = 0.0
                        intervalBlockedMax = 0.0
                        intervalSlipMax = 0.0
                    }
                }
            }

            var haveBlock = false
            while (true) {
                if (session.cancelled) {
                    reason = "session ended" + (session.endReason?.let { ": $it" } ?: "")
                    closedBy = "server"
                    break
                }
                if (peer.closed) {
                    reason = "peer " + peer.how
                    closedBy = "peer"
                    break
                }
                if (!haveBlock) {
                    // with something to send, only what exists at once is built; then it is sent and the wait for more begins
                    val n = builder.nextBlock(if (pending.size() > 0) 0 else 150)
                    when {
                        n == FrameSource.CLOSED -> {
                            flush()
                            reason = "source closed"
                            closedBy = "server"
                            break
                        }
                        n == FrameSource.LAPPED -> {
                            flush()
                            reason = "the frames are gone (lapped)"
                            closedBy = "server"
                            lost = true
                            break
                        }
                        n == FrameSource.EPOCH -> {
                            flush()
                            reason = "epoch changed"
                            closedBy = "server"
                            break
                        }
                        n <= 0 -> {
                            flush()
                            continue
                        }
                        else -> haveBlock = true
                    }
                }
                val due = dueNanos(blockNo)
                holdStep(due)
                if (due > System.nanoTime()) {
                    flush()
                    sleepUntil(due) { session.cancelled || peer.closed }
                    continue
                }
                val chunk = when (format) {
                    StreamFormat.WAV -> Wav.pcm(builder.block, blockFrames)
                    StreamFormat.L16 -> L16.pcm(builder.block, blockFrames)
                    StreamFormat.FLAC -> Flac.frame(blockNo, builder.block, rate, blockFrames)
                    StreamFormat.TS -> ByteArray(0)
                }
                if (format == StreamFormat.WAV) {
                    if (!Wav.fits(dataBytes, chunk.size)) {
                        // the length that the header claimed (about 3.4 h) is used up: the stream ends as it said it would
                        flush()
                        reason = "the length that the WAV header claims is reached"
                        closedBy = "server"
                        break
                    }
                    dataBytes += chunk.size
                }
                pending.write(chunk)
                lastDue = due
                pendingBlocks++
                blockNo++
                haveBlock = false
                if (pending.size() >= MAX_WRITE_BYTES) flush()
            }
        } catch (e: IOException) {
            reason = "peer: " + e.message
            closedBy = "peer"
        } catch (e: InterruptedException) {
            reason = "interrupted"
            closedBy = "server"
        } finally {
            val wasCurrent = session.stream === active
            if (live) session.detach(active)
            val ended = System.nanoTime()
            if (rec != null) {
                rec.bytes = bytes
                rec.blocks = blockNo
                session.record?.closeConnection(rec, ended, closedBy, reason)
            }
            log(
                "session ${session.token} conn $connectionId closed after ${(ended - began) / 1_000_000L} ms, " +
                    "$blockNo blocks, $bytes bytes, by the ${if (closedBy == "peer") (if (session.realTime) "TV" else "Sonos") else "app"} ($reason)",
            )
            if (live && lost && wasCurrent) host.streamLost(session, reason)
        }
    }

    /**
     * A connection of the TV to the live transport stream: the headers, then the stream from a key place of the ring on, as fast as the TV
     * reads (the part that exists already, the burst) and then as it is made. The place it starts at is the newest key picture that is at
     * least `lead` old (the way the stream was started in the device test), or with a lead of 0 the first one made after the connection
     * (the encoder is asked for one). Every connection is for itself and none is closed because another came; one that cannot keep up with
     * the ring is closed ("lapped") rather than given a stream with a hole in it. What the TV does is in the log: where each connection
     * started, how much it was given at once, how fast it read and how far behind the newest data it was.
     */
    private fun streamTs(session: KitchenSession, remote: String, userAgent: String, conn: ClientConn, range: String) {
        val out = conn.out
        val began = System.nanoTime()
        val count = session.nextConnectionId()
        val rec = session.record?.openConnection("GET", remote, userAgent, session.armed, began)
        rec?.range = range
        val connectionId = rec?.id ?: count
        session.lastGetNanos = began
        session.tsConnectionOpened()
        try {
            session.onGet?.invoke(began)
        } catch (t: Throwable) {
            AppLog.error(TAG, "the GET hook failed", t)
        }
        val peer = PeerWatch(conn)
        var reason = "peer closed"
        var closedBy = "peer"
        var bytes = 0L
        var writes = 0L
        var blockedTotal = 0.0
        var blockedMax = 0.0
        var behindMax = 0.0
        val statsEveryNanos = session.statsEveryMs * 1_000_000L
        var intervalStart = began
        var intervalBytes = 0L
        var intervalWrites = 0
        var intervalBlockedMs = 0.0
        var intervalBlockedMax = 0.0
        var intervalBehindMax = 0.0
        try {
            if (session.sendBufferBytes > 0) conn.limitSendBuffer(session.sendBufferBytes)
            val feedSource = session.ts
            if (feedSource == null) {
                reason = "no live stream"
                closedBy = "server"
                return
            }
            val history: TsHistory = feedSource.history
            val lead = feedSource.leadNanos()
            out.write(RESPONSE_HEAD_TS.toByteArray(Charsets.ISO_8859_1))
            out.flush()
            var pos: Long?
            if (lead > 0) {
                pos = history.startFor(began, lead) ?: history.keyAfter(began, TS_START_WAIT_MS)
            } else {
                feedSource.requestSync()
                pos = history.keyAfter(began, TS_START_WAIT_MS) ?: history.startFor(began, 0)
            }
            if (pos == null) {
                reason = "the stream has no key picture to start at"
                closedBy = "server"
                return
            }
            val startNanos = history.nanosAt(pos)
            // the age of the place the TV starts at, as it is when its first byte is written: the TV's latency is this and a constant
            val startAgeMs = if (startNanos == null) Double.NaN else (System.nanoTime() - startNanos) / 1e6
            val burst = history.endPos - pos
            rec?.startAgeMs = startAgeMs
            rec?.burstBytes = burst
            if (!startAgeMs.isNaN()) session.tsStarted(connectionId, startAgeMs)
            log(
                "$remote GET session ${session.token} conn $connectionId, the live video stream from the key place that is " +
                    "${if (startAgeMs.isNaN()) "?" else "%.0f".format(java.util.Locale.US, startAgeMs)} ms old ($burst bytes at once, lead ${lead / 1_000_000L} ms)" +
                    (if (range.isNotEmpty()) ", Range: $range" else "") + " [$userAgent]",
            )
            while (true) {
                if (session.cancelled) {
                    reason = "session ended" + (session.endReason?.let { ": $it" } ?: "")
                    closedBy = "server"
                    break
                }
                if (peer.closed) {
                    reason = "peer " + peer.how
                    closedBy = "peer"
                    break
                }
                when (val r = history.read(pos!!, TS_MAX_WRITE_BYTES, 100)) {
                    is TsHistory.Read.Timeout -> continue
                    is TsHistory.Read.Lapped -> {
                        reason = "the TV reads too slowly: the ring has moved on (lapped)"
                        closedBy = "server"
                        break
                    }
                    is TsHistory.Read.Closed -> {
                        reason = "the stream ended"
                        closedBy = "server"
                        break
                    }
                    is TsHistory.Read.Data -> {
                        val t0 = System.nanoTime()
                        out.write(r.bytes)
                        out.flush()
                        val t1 = System.nanoTime()
                        pos = r.next
                        val blockedMs = (t1 - t0) / 1e6
                        val behindMs = (t1 - r.lastNanos) / 1e6
                        bytes += r.bytes.size
                        writes++
                        session.bytesSent.addAndGet(r.bytes.size.toLong())
                        blockedTotal += blockedMs
                        if (blockedMs > blockedMax) blockedMax = blockedMs
                        // how far behind the newest data the connection was is counted after the burst (the first 3 s are the catching up)
                        if (t1 - began > 3_000_000_000L && behindMs > behindMax) behindMax = behindMs
                        if (rec != null) {
                            if (rec.firstByteMs < 0) rec.firstByteMs = session.record?.ms(t1) ?: 0L
                            rec.bytes = bytes
                            rec.writes = writes
                            rec.writeMsTotal = blockedTotal
                            rec.writeMsMax = blockedMax
                            rec.behindMsMax = behindMax
                        }
                        if (statsEveryNanos > 0) {
                            intervalBytes += r.bytes.size
                            intervalWrites++
                            intervalBlockedMs += blockedMs
                            if (blockedMs > intervalBlockedMax) intervalBlockedMax = blockedMs
                            if (behindMs > intervalBehindMax) intervalBehindMax = behindMs
                            if (t1 - intervalStart >= statsEveryNanos) {
                                val seconds = (t1 - intervalStart) / 1e9
                                log(
                                    "conn $connectionId, last ${"%.1f".format(java.util.Locale.US, seconds)} s: $intervalWrites writes, $intervalBytes bytes " +
                                        "(${"%.0f".format(java.util.Locale.US, intervalBytes * 8 / seconds / 1000.0)} kbit/s), a write blocked " +
                                        "${"%.1f".format(java.util.Locale.US, intervalBlockedMs / maxOf(1, intervalWrites))} ms on average and " +
                                        "${"%.1f".format(java.util.Locale.US, intervalBlockedMax)} ms at most, the data written was up to " +
                                        "${"%.0f".format(java.util.Locale.US, intervalBehindMax)} ms old",
                                )
                                intervalStart = t1
                                intervalBytes = 0
                                intervalWrites = 0
                                intervalBlockedMs = 0.0
                                intervalBlockedMax = 0.0
                                intervalBehindMax = 0.0
                            }
                        }
                    }
                }
            }
        } catch (e: IOException) {
            reason = "peer: " + e.message
            closedBy = "peer"
        } catch (e: InterruptedException) {
            reason = "interrupted"
            closedBy = "server"
        } finally {
            session.tsConnectionClosed()
            session.tsEnded(connectionId)
            val ended = System.nanoTime()
            if (rec != null) {
                rec.bytes = bytes
                session.record?.closeConnection(rec, ended, closedBy, reason)
            }
            log(
                "session ${session.token} conn $connectionId closed after ${(ended - began) / 1_000_000L} ms, $bytes bytes, " +
                    "by the ${if (closedBy == "peer") "TV" else "app"} ($reason)",
            )
        }
    }

    private fun sleepUntil(targetNanos: Long, shouldStop: () -> Boolean) {
        while (true) {
            val remaining = targetNanos - System.nanoTime()
            if (remaining <= 0 || shouldStop()) return
            val ms = minOf(remaining / 1_000_000L, 20L)
            if (ms <= 0) Thread.yield() else Thread.sleep(ms)
        }
    }
}
