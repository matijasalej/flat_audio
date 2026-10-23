package io.github.matijasalej.wholeflat

import io.github.matijasalej.wholeflat.capture.CaptureFeed
import io.github.matijasalej.wholeflat.sync.Flac
import io.github.matijasalej.wholeflat.sync.FrameSource
import io.github.matijasalej.wholeflat.sync.SessionHistory
import io.github.matijasalej.wholeflat.sync.StreamBuilder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The HTTP side of the kitchen stream, with a fake Sonos: the connections it opens, how long they live and
 * who closes them. The device test showed a speaker that started and was cut off again and again; what the
 * stream server must do about the connections is pinned down here.
 */
class KitchenHttpTest {
    /** A test that hangs fails instead of blocking the build. */
    @get:Rule
    val timeout: Timeout = Timeout(30, TimeUnit.SECONDS)

    private val logs = CopyOnWriteArrayList<String>()
    private val rate = 44_100
    private val headBytes = KitchenStreams.RESPONSE_HEAD.toByteArray(Charsets.ISO_8859_1)

    @Before
    fun setUp() {
        KitchenStreams.log = { logs.add(it) }
        KitchenStreams.ringFrames = { FullSource }
    }

    @After
    fun tearDown() {
        KitchenStreams.log = {}
        KitchenStreams.ringFrames = { epoch -> RingFrameSource(epoch) }
    }

    /** A ring that has every frame. */
    private object FullSource : FrameSource {
        override fun read(cursor: Long, out: ShortArray, maxFrames: Int, timeoutMs: Int): Int {
            for (i in 0 until maxFrames) {
                out[i * 2] = (cursor + i).toShort()
                out[i * 2 + 1] = (cursor + i).toShort()
            }
            return maxFrames
        }
    }

    /** Frames that exist until the test says they are lapped. */
    private class LappingSource(private val frames: Int) : FrameSource {
        private var used = 0

        override fun read(cursor: Long, out: ShortArray, maxFrames: Int, timeoutMs: Int): Int {
            if (used >= frames) return FrameSource.LAPPED
            val n = minOf(maxFrames, frames - used)
            used += n
            return n
        }
    }

    /** One connection of the fake Sonos: what we write to it, and its closing, from its side or ours. */
    private class FakeConn : ClientConn {
        val writes = CopyOnWriteArrayList<ByteArray>()
        val flushes = AtomicInteger()

        @Volatile
        var failWith: IOException? = null

        @Volatile
        var closedByUs = false

        @Volatile
        var peerHow = ""
        private val latch = CountDownLatch(1)

        override val out: OutputStream = object : OutputStream() {
            override fun write(b: Int) {
                write(byteArrayOf(b.toByte()), 0, 1)
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                failWith?.let { throw it }
                writes.add(b.copyOfRange(off, off + len))
            }

            override fun flush() {
                flushes.incrementAndGet()
            }
        }

        override fun prepareForStream() {}

        override fun awaitPeerClose(): String {
            latch.await()
            return peerHow
        }

        override fun closeNow() {
            closedByUs = true
            if (peerHow.isEmpty()) peerHow = "reset: Socket closed"
            failWith = IOException("Socket closed")
            latch.countDown()
        }

        /** The Sonos closes: FIN (eof) or RST (reset). Later writes fail like on a socket whose peer is gone. */
        fun peerCloses(how: String = "eof") {
            peerHow = how
            failWith = IOException(if (how == "eof") "Broken pipe" else "Connection reset by peer")
            latch.countDown()
        }

        val written: Int get() = writes.sumOf { it.size }
    }

    private class Host(val session: KitchenSession?) : StreamHost {
        val lost = CopyOnWriteArrayList<String>()

        override fun sessionFor(token: String): KitchenSession? = if (session != null && session.token == token) session else null

        override fun streamLost(s: KitchenSession, reason: String) {
            lost.add(reason)
        }
    }

    private fun ringSession(armed: Boolean = false, startFrame: Long = 5_000L): KitchenSession {
        val s = KitchenSession("tok1", 1L, 0, startFrame, StreamBuilder.ChannelMode.STEREO)
        s.record = SessionHistory().begin("tok1", "ring", System.nanoTime(), System.currentTimeMillis())
        if (armed) s.arm(System.nanoTime() + 290_000_000L)
        return s
    }

    private fun serveAsync(host: Host, method: String, conn: FakeConn): Thread {
        val t = Thread {
            try {
                KitchenStreams.serve(host, "tok1", method, "192.168.0.212:40000", "Sonos-UA", conn)
            } catch (e: IOException) {
                // a HEAD to a peer that has gone
            }
        }
        t.isDaemon = true
        t.start()
        return t
    }

    private fun waitUntil(ms: Long = 5_000, condition: () -> Boolean) {
        val end = System.nanoTime() + ms * 1_000_000L
        while (!condition()) {
            if (System.nanoTime() > end) throw AssertionError("not true within $ms ms")
            Thread.sleep(2)
        }
    }

    private fun frameSize(rate: Int = this.rate): Int = Flac.frame(0, ShortArray(4096 * 2), rate).size

    private fun streamInfoSize(rate: Int = this.rate): Int = Flac.streamHeader(rate).size

    // ---- the headers ---------------------------------------------------------------------------

    @Test
    fun aHeadGetsExactlyTheHeadersOfAGetAndTouchesNothing() {
        val s = ringSession(armed = true)
        val conn = FakeConn()
        serveAsync(Host(s), "HEAD", conn).join(5_000)
        assertEquals(1, conn.writes.size)
        assertEquals(KitchenStreams.RESPONSE_HEAD, String(conn.writes[0], Charsets.ISO_8859_1))
        assertEquals("a HEAD is no stream", 0, s.connectionCount)
        assertEquals(0, s.streamCount)
        assertNull(s.stream)
        assertTrue(s.armed)
        assertFalse(s.cancelled)
        // it is in the log of the session, closed by the app
        val c = s.record!!.toJson(System.nanoTime()).getJSONArray("connections").getJSONObject(0)
        assertEquals("HEAD", c.getString("method"))
        assertEquals("server", c.getString("closed_by"))
    }

    @Test
    fun aGetStartsWithTheHeadersAndNothingComesBeforeThem() {
        val s = ringSession()
        val conn = FakeConn()
        val t = serveAsync(Host(s), "GET", conn)
        waitUntil { conn.writes.isNotEmpty() }
        val first = conn.writes[0]
        assertTrue(String(first, Charsets.ISO_8859_1).startsWith("HTTP/1.1 200 OK\r\n"))
        assertEquals(KitchenStreams.RESPONSE_HEAD, String(first, 0, headBytes.size, Charsets.ISO_8859_1))
        val text = String(first, 0, headBytes.size, Charsets.ISO_8859_1)
        assertFalse(text.contains("Content-Length"))
        assertFalse(text.contains("Transfer-Encoding"))
        // the FLAC stream starts right after the headers
        assertEquals("fLaC", String(first, headBytes.size, 4, Charsets.US_ASCII))
        conn.peerCloses()
        t.join(5_000)
    }

    // ---- one write for the lead -------------------------------------------------------------------

    @Test
    fun theFirstWriteIsTheHeadersTheStreamInfoAndTheWholeLeadOfAProbe() {
        val s = ringSession()
        val conn = FakeConn()
        val t = serveAsync(Host(s), "GET", conn)
        waitUntil { conn.writes.isNotEmpty() }
        val first = conn.writes[0]
        val frames = (first.size - headBytes.size - streamInfoSize()) / frameSize()
        assertEquals("exactly headers, STREAMINFO and whole frames", headBytes.size + streamInfoSize() + frames * frameSize(), first.size)
        // a second of audio at once, as the spikes' server sends to a probe
        assertTrue("$frames frames in the first write", frames in 11..12)
        assertEquals("one write call for all of it", 1, conn.writes.size.coerceAtMost(1))
        conn.peerCloses()
        t.join(5_000)
    }

    @Test
    fun aStreamAfterPlayGetsItsLeadInOneWrite() {
        val s = ringSession(armed = true)
        val conn = FakeConn()
        val t = serveAsync(Host(s), "GET", conn)
        waitUntil { conn.writes.isNotEmpty() }
        val first = conn.writes[0]
        val frames = (first.size - headBytes.size - streamInfoSize()) / frameSize()
        // 1.5 s ahead of where the Sonos is predicted to play, which is 0.29 s from now: about 13 frames
        assertTrue("$frames frames in the first write", frames in 12..14)
        assertEquals(headBytes.size + streamInfoSize() + frames * frameSize(), first.size)
        conn.peerCloses()
        t.join(5_000)
    }

    @Test
    fun aCapturedStreamIsSentAsItIsCapturedAfterItsFirstWrite() {
        val feed = CaptureFeed()
        feed.newGeneration(1)
        val framesPerChunk = feed.rate / 50
        val chunk = ShortArray(framesPerChunk * 2)
        // 0.5 s exist when the Sonos asks
        repeat(25) { feed.writeAt(chunk, framesPerChunk, System.nanoTime()) }
        val s = KitchenSession("tok1", -1L, 100, 0L, StreamBuilder.ChannelMode.STEREO, feed, 1L)
        s.record = SessionHistory().begin("tok1", "capture", System.nanoTime(), System.currentTimeMillis())
        s.arm(System.nanoTime())
        val conn = FakeConn()
        val t = serveAsync(Host(s), "GET", conn)
        waitUntil { conn.writes.isNotEmpty() }
        val written = 25 * framesPerChunk
        val blocks = written / 4096
        val size = frameSize(feed.rate)
        assertEquals(
            "the headers, the STREAMINFO and the $blocks frames that exist, in one write",
            headBytes.size + streamInfoSize(feed.rate) + blocks * size,
            conn.writes[0].size,
        )
        assertEquals(1, conn.writes.size)
        // and then each frame as it comes into existence, not before
        Thread.sleep(80)
        assertEquals("nothing is made up", 1, conn.writes.size)
        // capture on until exactly one more frame exists
        while (feed.buffer.writeIndex < (blocks + 1) * 4096L) feed.writeAt(chunk, framesPerChunk, System.nanoTime())
        waitUntil { conn.writes.size == 2 }
        assertEquals(size, conn.writes[1].size)
        conn.peerCloses()
        t.join(5_000)
    }

    // ---- connections are independent -------------------------------------------------------------

    @Test
    fun aProbeIsNotClosedWhenPlayIsSentNorWhenAnotherConnectionComes() {
        val s = ringSession()
        val host = Host(s)
        val probe = FakeConn()
        val tProbe = serveAsync(host, "GET", probe)
        waitUntil { probe.writes.isNotEmpty() }
        // Play is sent: the old rule closed the probe here
        s.arm(System.nanoTime() + 290_000_000L)
        Thread.sleep(150)
        assertTrue("the probe lives", tProbe.isAlive)
        assertFalse(probe.closedByUs)
        // the stream proper comes, then a third connection
        val stream = FakeConn()
        val tStream = serveAsync(host, "GET", stream)
        waitUntil { stream.writes.isNotEmpty() }
        val third = FakeConn()
        val tThird = serveAsync(host, "GET", third)
        waitUntil { third.writes.isNotEmpty() }
        Thread.sleep(150)
        assertTrue(tProbe.isAlive && tStream.isAlive && tThird.isAlive)
        assertFalse(probe.closedByUs || stream.closedByUs || third.closedByUs)
        // the Sonos closes what it opened
        probe.peerCloses("eof")
        tProbe.join(5_000)
        assertFalse(tProbe.isAlive)
        assertTrue(tStream.isAlive && tThird.isAlive)
        stream.peerCloses("eof")
        third.peerCloses("reset: Connection reset by peer")
        tStream.join(5_000)
        tThird.join(5_000)
        val c = s.record!!.toJson(System.nanoTime()).getJSONArray("connections")
        assertEquals(3, c.length())
        for (i in 0 until 3) assertEquals("peer", c.getJSONObject(i).getString("closed_by"))
        assertTrue(c.getJSONObject(0).getString("reason").contains("eof"))
        assertTrue(c.getJSONObject(2).getString("reason").contains("reset"))
        assertFalse(c.getJSONObject(0).getBoolean("armed"))
        assertTrue(c.getJSONObject(1).getBoolean("armed"))
    }

    @Test
    fun theNewestStreamIsSteeredAndTheOlderOneTakesOverAgainWhenItEnds() {
        val s = ringSession(armed = true)
        val host = Host(s)
        val first = FakeConn()
        val tFirst = serveAsync(host, "GET", first)
        waitUntil { s.streamCount == 1 }
        val firstActive = s.stream
        assertNotNull(firstActive)
        val second = FakeConn()
        val tSecond = serveAsync(host, "GET", second)
        waitUntil { s.streamCount == 2 }
        val secondActive = s.stream
        assertTrue("the newest is steered", secondActive!!.connectionId != firstActive!!.connectionId)
        // the Sonos resets the second one after a few blocks: the first is the stream again
        second.peerCloses("reset: Connection reset by peer")
        tSecond.join(5_000)
        waitUntil { s.streamCount == 1 }
        assertSame(firstActive, s.stream)
        assertTrue(tFirst.isAlive)
        first.peerCloses()
        tFirst.join(5_000)
        assertNull(s.stream)
        assertEquals(0, s.streamCount)
    }

    @Test
    fun aGetThatTheSonosResetsIsRecordedAsClosedByThePeerWithTheError() {
        val s = ringSession(armed = true)
        val conn = FakeConn()
        val t = serveAsync(Host(s), "GET", conn)
        waitUntil { conn.writes.isNotEmpty() }
        conn.failWith = IOException("Connection reset by peer")
        t.join(5_000)
        assertFalse(t.isAlive)
        val c = s.record!!.toJson(System.nanoTime()).getJSONArray("connections").getJSONObject(0)
        assertEquals("peer", c.getString("closed_by"))
        assertTrue(c.getString("reason"), c.getString("reason").contains("Connection reset by peer"))
        assertTrue(c.getLong("bytes") > 0)
        assertTrue(c.getLong("blocks") > 0)
        assertTrue(logs.any { it.contains("by the Sonos") })
    }

    @Test
    fun whenTheSessionEndsEveryConnectionEndsAndIsClosedByTheApp() {
        val s = ringSession(armed = true)
        val host = Host(s)
        val a = FakeConn()
        val b = FakeConn()
        val ta = serveAsync(host, "GET", a)
        val tb = serveAsync(host, "GET", b)
        waitUntil { s.streamCount == 2 }
        s.cancelled = true
        s.endReason = "re-join: the speaker is IDLE"
        s.closeSockets()
        ta.join(5_000)
        tb.join(5_000)
        assertTrue(a.closedByUs && b.closedByUs)
        val conns = s.record!!.toJson(System.nanoTime()).getJSONArray("connections")
        for (i in 0 until 2) {
            assertEquals("server", conns.getJSONObject(i).getString("closed_by"))
            assertTrue(conns.getJSONObject(i).getString("reason").contains("session ended"))
        }
    }

    @Test
    fun anUnknownStreamIsA404() {
        val conn = FakeConn()
        KitchenStreams.serve(Host(null), "nope", "GET", "x", "ua", conn)
        val text = String(conn.writes.reduce { a, b -> a + b }, Charsets.ISO_8859_1)
        assertTrue(text.startsWith("HTTP/1.1 404 Not Found\r\n"))
    }

    // ---- when the frames are gone ----------------------------------------------------------------

    @Test
    fun lostFramesRestartTheStreamOnlyWhenItIsTheOneSteered() {
        KitchenStreams.ringFrames = { LappingSource(4096 * 3) }
        val s = ringSession(armed = true)
        val host = Host(s)
        val conn = FakeConn()
        serveAsync(host, "GET", conn).join(5_000)
        assertEquals(1, host.lost.size)
        assertTrue(host.lost[0], host.lost[0].contains("lapped"))

        // a connection that is not the newest one is not worth a restart
        val s2 = ringSession(armed = true)
        val host2 = Host(s2)
        KitchenStreams.ringFrames = { FullSource }
        val old = FakeConn()
        val tOld = serveAsync(host2, "GET", old)
        waitUntil { s2.streamCount == 1 }
        KitchenStreams.ringFrames = { LappingSource(4096 * 3) }
        val newest = FakeConn()
        val tNewest = serveAsync(host2, "GET", newest)
        tNewest.join(5_000)
        // the newest was the steered one while it lived: that one asks for a restart
        assertEquals(1, host2.lost.size)
        old.peerCloses()
        tOld.join(5_000)
    }
}
