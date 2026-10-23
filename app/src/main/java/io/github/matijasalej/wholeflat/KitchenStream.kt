package io.github.matijasalej.wholeflat

import io.github.matijasalej.wholeflat.capture.CaptureFeed
import io.github.matijasalej.wholeflat.capture.CaptureFrames
import io.github.matijasalej.wholeflat.sync.Flac
import io.github.matijasalej.wholeflat.sync.FrameSource
import io.github.matijasalej.wholeflat.sync.HoldController
import io.github.matijasalej.wholeflat.sync.KitchenMap
import io.github.matijasalej.wholeflat.sync.L16
import io.github.matijasalej.wholeflat.sync.SessionClock
import io.github.matijasalej.wholeflat.sync.SessionRecord
import io.github.matijasalej.wholeflat.sync.StreamBuilder
import io.github.matijasalej.wholeflat.sync.StreamFormat
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

    /** Open connections after Play. */
    val streamCount: Int get() = synchronized(streams) { streams.size }

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
     * The frames between two trims of a stream (a trim is one frame dropped or repeated): 10 ms for Spotify's, which is up to
     * 2.3 ms of correction per second; 5 ms for a captured one, up to 4.5 ms per second, which is a speed change of the kitchen
     * of 0.45 %, the most that the lag steering of the capture mode (`kitchen_target_ms`) lets itself use.
     */
    fun trimSpacing(rate: Int, captured: Boolean): Int = if (captured) rate / 200 else rate / 100

    /** The headers of a stream of [format]; the same for a GET and a HEAD. */
    fun responseHead(format: StreamFormat): String = when (format) {
        StreamFormat.WAV -> RESPONSE_HEAD_WAV
        StreamFormat.L16 -> RESPONSE_HEAD_L16
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
            stream(host, session, remote, userAgent, conn, range)
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
            capture != null -> CaptureFrames(capture.buffer, session.captureGeneration)
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

    private fun sleepUntil(targetNanos: Long, shouldStop: () -> Boolean) {
        while (true) {
            val remaining = targetNanos - System.nanoTime()
            if (remaining <= 0 || shouldStop()) return
            val ms = minOf(remaining / 1_000_000L, 20L)
            if (ms <= 0) Thread.yield() else Thread.sleep(ms)
        }
    }
}
