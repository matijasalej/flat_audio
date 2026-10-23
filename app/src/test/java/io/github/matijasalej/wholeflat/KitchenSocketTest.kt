package io.github.matijasalej.wholeflat

import io.github.matijasalej.wholeflat.sync.FrameSource
import io.github.matijasalej.wholeflat.sync.SessionHistory
import io.github.matijasalej.wholeflat.sync.StreamBuilder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** The stream server on real sockets (loopback), with a client that behaves as the Sonos does. */
class KitchenSocketTest {
    /** A test that hangs fails instead of blocking the build. */
    @get:Rule
    val timeout: Timeout = Timeout(30, TimeUnit.SECONDS)

    private val logs = CopyOnWriteArrayList<String>()
    private lateinit var server: ServerSocket
    private lateinit var session: KitchenSession
    private val threads = CopyOnWriteArrayList<Thread>()
    private val clients = CopyOnWriteArrayList<Socket>()

    private object FullSource : FrameSource {
        override fun read(cursor: Long, out: ShortArray, maxFrames: Int, timeoutMs: Int): Int {
            for (i in 0 until maxFrames * 2) out[i] = (cursor + i / 2).toShort()
            return maxFrames
        }
    }

    private val host = object : StreamHost {
        override fun sessionFor(token: String): KitchenSession? = if (token == "tok1") session else null

        override fun streamLost(s: KitchenSession, reason: String) {}
    }

    @Before
    fun setUp() {
        KitchenStreams.log = { logs.add(it) }
        KitchenStreams.ringFrames = { FullSource }
        session = KitchenSession("tok1", 1L, 0, 1_000L, StreamBuilder.ChannelMode.STEREO)
        session.record = SessionHistory().begin("tok1", "ring", System.nanoTime(), System.currentTimeMillis())
        server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val acceptor = Thread {
            try {
                while (true) {
                    val sock = server.accept()
                    val t = Thread {
                        try {
                            val input = sock.getInputStream()
                            val request = readRequest(input)
                            val method = request.substringBefore(' ')
                            KitchenStreams.serve(
                                host, "tok1", method, sock.remoteSocketAddress.toString(), "test",
                                SocketConn(sock, BufferedOutputStream(sock.getOutputStream(), 16 * 1024)),
                            )
                        } catch (e: Exception) {
                            // the client went away
                        } finally {
                            try {
                                sock.close()
                            } catch (e: Exception) {
                                // closed
                            }
                        }
                    }
                    t.isDaemon = true
                    t.start()
                    threads.add(t)
                }
            } catch (e: Exception) {
                // the server socket was closed
            }
        }
        acceptor.isDaemon = true
        acceptor.start()
    }

    @After
    fun tearDown() {
        for (c in clients) {
            try {
                c.close()
            } catch (e: Exception) {
                // closed
            }
        }
        server.close()
        KitchenStreams.log = {}
        KitchenStreams.ringFrames = { epoch -> RingFrameSource(epoch) }
    }

    private fun readRequest(input: InputStream): String {
        val sb = StringBuilder()
        while (!sb.endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0) break
            sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun request(method: String): Socket {
        val s = Socket(InetAddress.getLoopbackAddress(), server.localPort)
        clients.add(s)
        s.getOutputStream().write("$method /k/tok1.flac HTTP/1.1\r\nHost: x\r\nUser-Agent: Sonos\r\n\r\n".toByteArray())
        s.getOutputStream().flush()
        return s
    }

    /**
     * Reads for [forMs] at most, whatever a connection that keeps on sending delivers meanwhile. The second value
     * is true if the server closed the connection (an end of stream or a reset) within that time.
     */
    private fun drain(s: Socket, forMs: Int = 400): Pair<Int, Boolean> {
        val end = System.nanoTime() + forMs * 1_000_000L
        val buf = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val left = (end - System.nanoTime()) / 1_000_000L
            if (left <= 0) return Pair(total, false)
            s.soTimeout = left.toInt().coerceAtLeast(1)
            try {
                val n = s.getInputStream().read(buf)
                if (n < 0) return Pair(total, true)
                total += n
            } catch (e: SocketTimeoutException) {
                return Pair(total, false)
            } catch (e: SocketException) {
                return Pair(total, true)
            }
        }
    }

    private fun waitUntil(ms: Long = 5_000, condition: () -> Boolean) {
        val end = System.nanoTime() + ms * 1_000_000L
        while (!condition()) {
            if (System.nanoTime() > end) throw AssertionError("not true within $ms ms")
            Thread.sleep(5)
        }
    }

    @Test
    fun aHeadOnARealSocketGetsTheHeadersAndIsClosedByTheApp() {
        val s = request("HEAD")
        val bytes = ByteArrayOutputStream()
        s.soTimeout = 3_000
        val buf = ByteArray(1024)
        while (true) {
            val n = s.getInputStream().read(buf)
            if (n < 0) break
            bytes.write(buf, 0, n)
        }
        assertEquals(KitchenStreams.RESPONSE_HEAD, bytes.toString(Charsets.ISO_8859_1.name()))
        assertEquals(0, session.connectionCount)
    }

    @Test
    fun aGetOnARealSocketStartsWithTheHeadersAndThePeerClosingEndsIt() {
        val s = request("GET")
        s.soTimeout = 3_000
        val head = ByteArray(KitchenStreams.RESPONSE_HEAD.length)
        var read = 0
        while (read < head.size) {
            val n = s.getInputStream().read(head, read, head.size - read)
            assertTrue("closed before the headers", n >= 0)
            read += n
        }
        assertEquals(KitchenStreams.RESPONSE_HEAD, String(head, Charsets.ISO_8859_1))
        s.close()
        waitUntil { session.record!!.toJson(System.nanoTime()).getJSONArray("connections").getJSONObject(0).optString("closed_by", "") == "peer" }
        val c = session.record!!.toJson(System.nanoTime()).getJSONArray("connections").getJSONObject(0)
        assertEquals("peer", c.getString("closed_by"))
        waitUntil { session.streamCount == 0 }
    }

    @Test
    fun anOlderConnectionKeepsRunningWhenANewerOneComesAndWhenPlayIsSent() {
        val probe = request("GET")
        val (first, closed1) = drain(probe)
        assertTrue("the probe gets its bytes at once: $first", first > 100_000)
        assertFalse(closed1)
        // Play is sent, the Sonos opens the stream proper and asks for the headers again
        session.arm(System.nanoTime() + 290_000_000L)
        val stream = request("GET")
        val head = request("HEAD")
        val (second, closed2) = drain(stream)
        assertTrue("the stream gets its lead: $second", second > 100_000)
        assertFalse(closed2)
        drain(head, 200)
        // nobody closed the probe: it is quiet or still sending, but never at its end
        val (_, probeClosed) = drain(probe, 600)
        assertFalse("the probe was closed by the server", probeClosed)
        val (_, streamClosed) = drain(stream, 300)
        assertFalse("the stream was closed by the server", streamClosed)
        assertEquals(1, session.streamCount)
        // the Sonos closes the probe: that, and only that, ends it
        probe.close()
        waitUntil { session.record!!.toJson(System.nanoTime()).getJSONArray("connections").getJSONObject(0).optString("closed_by", "") == "peer" }
        assertEquals(1, session.streamCount)
        assertTrue(session.stream != null)
    }

    @Test
    fun whenTheSessionEndsTheSonosSeesTheConnectionClosed() {
        session.arm(System.nanoTime() + 290_000_000L)
        val s = request("GET")
        drain(s, 200)
        session.cancelled = true
        session.endReason = "test"
        session.closeSockets()
        val (_, closed) = drain(s, 2_000)
        assertTrue(closed)
        waitUntil { session.record!!.toJson(System.nanoTime()).getJSONArray("connections").getJSONObject(0).optString("closed_by", "") == "server" }
    }
}
