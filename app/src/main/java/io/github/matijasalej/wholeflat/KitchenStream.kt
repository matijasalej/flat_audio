package io.github.matijasalej.wholeflat

import io.github.matijasalej.wholeflat.sync.Flac
import io.github.matijasalej.wholeflat.sync.FrameSource
import io.github.matijasalej.wholeflat.sync.KitchenMap
import io.github.matijasalej.wholeflat.sync.SessionClock
import io.github.matijasalej.wholeflat.sync.StreamBuilder
import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** The stream being served to the Sonos right now: its map from stream frames to ring frames and its trims. */
class ActiveStream(val connectionId: Int, val map: KitchenMap, val builder: StreamBuilder)

/**
 * One stream for the Sonos: the FLAC of the ring from [startRingFrame] on, behind a URL of its own.
 * A session belongs to one epoch of the ring; a new epoch, a re-join or a pause ends it.
 *
 * The Sonos asks for the URL before it is told to play (a HEAD and a short probe GET, which are
 * answered with real or silent frames and then closed), and again when Play arrives; only the stream
 * that is requested after [arm] is the one it plays.
 */
class KitchenSession(
    val token: String,
    val epoch: Long,
    val kind: Int,
    startRingFrame: Long,
    val channels: StreamBuilder.ChannelMode,
) {
    /** First ring frame of the stream; negative while it is not known yet (a hot-join picks it at Play time). */
    @Volatile
    var startRingFrame: Long = startRingFrame

    @Volatile
    var uri = ""

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

    @Volatile
    var stream: ActiveStream? = null
        private set

    private val sockets = HashSet<Socket>()

    fun addSocket(socket: Socket) {
        synchronized(sockets) { sockets.add(socket) }
    }

    fun removeSocket(socket: Socket) {
        synchronized(sockets) { sockets.remove(socket) }
    }

    /** Closes the connections of this session: a stream thread blocked in a write to a stalled Sonos ends. */
    fun closeSockets() {
        val open = synchronized(sockets) { sockets.toList() }
        for (socket in open) {
            try {
                socket.close()
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

    val connectionCount: Int get() = connections.get()

    /** Play is about to be sent: the Sonos should play stream frame 0 at [predictedStartNanos]. */
    fun arm(predictedStartNanos: Long) {
        clock = SessionClock(predictedStartNanos)
        armed = true
    }

    fun attach(active: ActiveStream) {
        stream = active
    }

    fun isCurrent(connectionId: Int): Boolean = stream?.connectionId == connectionId
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

/**
 * The HTTP side of the kitchen stream (`GET /k/<token>.flac`): headers as the spikes found the Sonos
 * likes them (FLAC without a length, no ranges, connection close), then the STREAMINFO header and one
 * verbatim FLAC frame per 4096 ring frames, paced to real time with a lead of [LEAD_FRAMES] ahead of
 * the position the Sonos is predicted to play.
 */
object KitchenStreams {
    private const val TAG = "stream"

    /** How far ahead of the Sonos's play position the stream is sent: 1.5 s. */
    const val LEAD_FRAMES = 66_150L
    private const val PROBE_START_NANOS = 300_000_000L

    const val RESPONSE_HEAD = "HTTP/1.1 200 OK\r\n" +
        "Content-Type: audio/flac\r\n" +
        "Accept-Ranges: none\r\n" +
        "Cache-Control: no-cache, no-store\r\n" +
        "Connection: close\r\n" +
        "transferMode.dlna.org: Streaming\r\n" +
        "Server: WholeFlat\r\n\r\n"

    /** The token of a `/k/<token>.flac` path, or null. */
    fun tokenOf(path: String): String? {
        if (!path.startsWith("/k/") || !path.endsWith(".flac")) return null
        val token = path.substring(3, path.length - 5)
        return if (token.isNotEmpty() && token.all { it.isLetterOrDigit() }) token else null
    }

    fun serve(kitchen: Kitchen, token: String, method: String, remote: String, userAgent: String, client: Socket, out: OutputStream) {
        val session = kitchen.sessionFor(token)
        if (session == null) {
            val body = "no such stream\n".toByteArray(Charsets.UTF_8)
            out.write(
                ("HTTP/1.1 404 Not Found\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1),
            )
            out.write(body)
            out.flush()
            AppLog.add(TAG, "$remote $method /k/$token -> 404 (no such session)")
            return
        }
        out.write(RESPONSE_HEAD.toByteArray(Charsets.ISO_8859_1))
        if (method == "HEAD") {
            out.flush()
            AppLog.add(TAG, "$remote HEAD session ${session.token} [$userAgent]")
            return
        }
        try {
            client.soTimeout = 0
            client.tcpNoDelay = true
        } catch (e: Exception) {
            // socket options are a nicety
        }
        session.addSocket(client)
        try {
            stream(kitchen, session, remote, userAgent, out)
        } finally {
            session.removeSocket(client)
        }
    }

    private fun stream(kitchen: Kitchen, session: KitchenSession, remote: String, userAgent: String, out: OutputStream) {
        val connectionId = session.nextConnectionId()
        val armed = session.armed
        val startFrame = session.startRingFrame
        val source: FrameSource = when {
            startFrame < 0 -> SilenceSource
            armed -> RingFrameSource(session.epoch)
            else -> ProbeSource(RingFrameSource(session.epoch))
        }
        val map = KitchenMap(maxOf(0L, startFrame))
        val builder = StreamBuilder(source, map, session.channels)
        val probeClock = if (armed) null else SessionClock(System.nanoTime() + PROBE_START_NANOS)
        if (armed) session.attach(ActiveStream(connectionId, map, builder))
        AppLog.add(
            TAG,
            "$remote GET session ${session.token} conn $connectionId from ring frame $startFrame, " +
                (if (armed) "the stream" else "a probe before Play") + " [$userAgent]",
        )
        var reason = "peer closed"
        var lost = false
        var blockNo = 0L
        val began = System.nanoTime()
        fun stopped(): Boolean = session.cancelled || (armed && !session.isCurrent(connectionId)) || (!armed && session.armed)
        try {
            out.write(Flac.streamHeader())
            out.flush()
            while (true) {
                if (session.cancelled) {
                    reason = "session ended"
                    break
                }
                if (!armed && session.armed) {
                    reason = "probe done, Play was sent"
                    break
                }
                if (armed && !session.isCurrent(connectionId)) {
                    reason = "replaced by a newer connection"
                    break
                }
                val n = builder.nextBlock(150)
                if (n == FrameSource.CLOSED) {
                    reason = "ring closed"
                    break
                }
                if (n == FrameSource.LAPPED) {
                    reason = "the ring moved on (lapped)"
                    lost = true
                    break
                }
                if (n == FrameSource.EPOCH) {
                    reason = "epoch changed"
                    break
                }
                if (n <= 0) continue
                val clock = if (armed) session.clock else probeClock
                if (clock != null) {
                    val due = clock.timeOfFrame(((blockNo + 1) * Flac.BLOCK - LEAD_FRAMES).toDouble())
                    sleepUntil(due) { stopped() }
                    if (stopped()) continue
                }
                val frame = Flac.frame(blockNo, builder.block)
                out.write(frame)
                out.flush()
                blockNo++
                session.bytesSent.addAndGet(frame.size.toLong())
                session.blocksSent.incrementAndGet()
            }
        } catch (e: IOException) {
            reason = "peer: " + e.message
        } catch (e: InterruptedException) {
            reason = "interrupted"
        } finally {
            AppLog.add(
                TAG,
                "session ${session.token} conn $connectionId closed after ${(System.nanoTime() - began) / 1_000_000L} ms, " +
                    "$blockNo blocks ($reason)",
            )
        }
        if (armed && lost && session.isCurrent(connectionId)) kitchen.streamLost(session, reason)
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
