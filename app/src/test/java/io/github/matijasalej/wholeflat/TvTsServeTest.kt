package io.github.matijasalej.wholeflat

import io.github.matijasalej.wholeflat.sync.SessionHistory
import io.github.matijasalej.wholeflat.sync.StreamBuilder
import io.github.matijasalej.wholeflat.sync.StreamFormat
import io.github.matijasalej.wholeflat.sync.TsFeed
import io.github.matijasalej.wholeflat.sync.TsHistory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
 * The live video stream to the TV, with a fake TV: the headers of the device test, a start at the key place the policy says (the newest
 * one that is a lead old, or the first one after the connection), the burst and then the stream as it is made, every connection for
 * itself, and a connection that cannot keep up is closed rather than fed a stream with a hole in it.
 */
class TvTsServeTest {
    @get:Rule
    val timeout: Timeout = Timeout(30, TimeUnit.SECONDS)

    private val logs = CopyOnWriteArrayList<String>()
    private val ms = 1_000_000L

    @Before
    fun setUp() {
        KitchenStreams.log = { logs.add(it) }
    }

    @After
    fun tearDown() {
        KitchenStreams.log = {}
    }

    private class FakeConn : ClientConn {
        val writes = CopyOnWriteArrayList<ByteArray>()

        @Volatile
        var failWith: IOException? = null

        @Volatile
        var sendBuffer = 0

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
        }

        override fun prepareForStream() {}

        override fun limitSendBuffer(bytes: Int) {
            sendBuffer = bytes
        }

        override fun awaitPeerClose(): String {
            latch.await()
            return peerHow
        }

        override fun closeNow() {
            peerHow = "reset: Socket closed"
            failWith = IOException("Socket closed")
            latch.countDown()
        }

        fun peerCloses() {
            peerHow = "eof"
            failWith = IOException("Broken pipe")
            latch.countDown()
        }

        val bytes: ByteArray get() = writes.fold(ByteArray(0)) { a, b -> a + b }
    }

    private class Feed(var lead: Long) : TsFeed {
        override val history = TsHistory()
        val syncRequests = AtomicInteger()

        @Volatile
        var onSync: (() -> Unit)? = null

        override fun leadNanos(): Long = lead

        override fun requestSync() {
            syncRequests.incrementAndGet()
            onSync?.invoke()
        }
    }

    private class Host(val session: KitchenSession) : StreamHost {
        override fun sessionFor(token: String): KitchenSession? = if (session.token == token) session else null

        override fun streamLost(s: KitchenSession, reason: String) {}
    }

    private fun tsSession(feed: TsFeed): KitchenSession {
        val s = KitchenSession("tok1", -2L, 101, -1L, StreamBuilder.ChannelMode.STEREO, null, 0L, format = StreamFormat.TS)
        s.realTime = true
        s.ts = feed
        s.record = SessionHistory().begin("tok1", "tv", System.nanoTime(), System.currentTimeMillis())
        return s
    }

    private fun serveAsync(session: KitchenSession, method: String, conn: FakeConn): Thread {
        val host = Host(session)
        val t = Thread {
            try {
                KitchenStreams.serve(host, "tok1", method, "192.168.0.69:50000", "TV-UA", conn)
            } catch (e: IOException) {
                // a peer that has gone
            }
        }
        t.isDaemon = true
        t.start()
        return t
    }

    private fun waitUntil(waitMs: Long = 5_000, condition: () -> Boolean) {
        val end = System.nanoTime() + waitMs * 1_000_000L
        while (!condition()) {
            if (System.nanoTime() > end) throw AssertionError("not true within $waitMs ms")
            Thread.sleep(2)
        }
    }

    private fun unit(fill: Int, size: Int = 188) = ByteArray(size) { fill.toByte() }

    /** Key places 3 s, 2 s, 1.5 s and 0.5 s ago with a non-key unit after each, newest data now. */
    private fun filled(feed: Feed, now: Long) {
        feed.history.append(unit(1), now - 3_000 * ms, true)
        feed.history.append(unit(2), now - 2_900 * ms, false)
        feed.history.append(unit(3), now - 2_000 * ms, true)
        feed.history.append(unit(4), now - 1_900 * ms, false)
        feed.history.append(unit(5), now - 1_500 * ms, true)
        feed.history.append(unit(6), now - 1_400 * ms, false)
        feed.history.append(unit(7), now - 500 * ms, true)
        feed.history.append(unit(8), now - 400 * ms, false)
    }

    private val head = KitchenStreams.RESPONSE_HEAD_TS.toByteArray(Charsets.ISO_8859_1)

    // ---- headers ------------------------------------------------------------------------------------

    @Test
    fun theHeadersAreTheSetOfTheDeviceTestWithNoLength() {
        assertEquals(
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: video/mpeg\r\n" +
                "Accept-Ranges: none\r\n" +
                "Connection: close\r\n" +
                "transferMode.dlna.org: Streaming\r\n" +
                "contentFeatures.dlna.org: DLNA.ORG_OP=00;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000\r\n\r\n",
            KitchenStreams.RESPONSE_HEAD_TS,
        )
        assertFalse(KitchenStreams.RESPONSE_HEAD_TS.contains("Content-Length"))
        assertEquals(KitchenStreams.RESPONSE_HEAD_TS, KitchenStreams.responseHead(StreamFormat.TS))
        assertEquals("abc123", KitchenStreams.tokenOf("/k/abc123.ts"))
        assertEquals(StreamFormat.TS, StreamFormat.ofPath("/k/abc123.ts"))
    }

    @Test
    fun aHeadGetsTheHeadersOfAGetAndTouchesNoStream() {
        val feed = Feed(1_000 * ms)
        val s = tsSession(feed)
        val conn = FakeConn()
        serveAsync(s, "HEAD", conn).join(5_000)
        assertEquals(head.toList(), conn.bytes.toList())
        assertEquals(0, s.streamCount)
        assertEquals(0L, s.lastGetNanos)
    }

    // ---- where a connection starts ------------------------------------------------------------------

    @Test
    fun aGetStartsAtTheNewestKeyPlaceThatIsAtLeastTheLeadOldAndGetsEverythingFromThereAtOnce() {
        val feed = Feed(1_000 * ms)
        filled(feed, System.nanoTime())
        val s = tsSession(feed)
        val conn = FakeConn()
        val t = serveAsync(s, "GET", conn)
        // the key places are 3, 2, 1.5 and 0.5 s old: the newest that is 1 s old is the one of 1.5 s, unit 5
        waitUntil { conn.bytes.size >= head.size + 188 * 4 }
        val all = conn.bytes
        assertEquals(head.toList(), all.copyOfRange(0, head.size).toList())
        val body = all.copyOfRange(head.size, all.size)
        assertEquals(listOf(5, 6, 7, 8), (0 until 4).map { body[it * 188].toInt() })
        conn.peerCloses()
        t.join(5_000)
        val rec = s.record!!.connections()[0]
        assertTrue("start age ${rec.startAgeMs}", rec.startAgeMs in 1_500.0..1_700.0)
        assertEquals(188L * 4, rec.burstBytes)
        assertTrue(logs.any { it.contains("the live video stream from the key place that is") })
    }

    @Test
    fun aStreamYoungerThanTheLeadStartsAtItsOldestKeyPlace() {
        val feed = Feed(10_000 * ms)
        filled(feed, System.nanoTime())
        val conn = FakeConn()
        val t = serveAsync(tsSession(feed), "GET", conn)
        waitUntil { conn.bytes.size >= head.size + 188 * 8 }
        assertEquals(1, conn.bytes[head.size].toInt())
        conn.peerCloses()
        t.join(5_000)
    }

    @Test
    fun withALeadOfZeroTheEncoderIsAskedAndTheConnectionStartsAtTheKeyPlaceThatComes() {
        val feed = Feed(0)
        filled(feed, System.nanoTime())
        // the encoder makes the key picture when it is asked
        feed.onSync = { feed.history.append(unit(9), System.nanoTime(), true) }
        val conn = FakeConn()
        val t = serveAsync(tsSession(feed), "GET", conn)
        waitUntil { conn.bytes.size >= head.size + 188 }
        assertEquals(1, feed.syncRequests.get())
        // nothing of the older units: it begins with the new one
        assertEquals(9, conn.bytes[head.size].toInt())
        conn.peerCloses()
        t.join(5_000)
    }

    @Test
    fun aConnectionBeforeTheStreamHasAKeyPlaceWaitsForTheFirstOne() {
        val feed = Feed(1_000 * ms)
        val conn = FakeConn()
        val t = serveAsync(tsSession(feed), "GET", conn)
        waitUntil { conn.writes.isNotEmpty() } // the headers go at once
        assertEquals(head.size, conn.bytes.size)
        feed.history.append(unit(1), System.nanoTime(), true)
        waitUntil { conn.bytes.size >= head.size + 188 }
        assertEquals(1, conn.bytes[head.size].toInt())
        conn.peerCloses()
        t.join(5_000)
    }

    // ---- live ----------------------------------------------------------------------------------------

    @Test
    fun whatIsMadeAfterTheBurstIsSentAsItComes() {
        val feed = Feed(1_000 * ms)
        filled(feed, System.nanoTime())
        val conn = FakeConn()
        val t = serveAsync(tsSession(feed), "GET", conn)
        waitUntil { conn.bytes.size >= head.size + 188 * 4 }
        feed.history.append(unit(10), System.nanoTime(), false)
        feed.history.append(unit(11), System.nanoTime(), false)
        waitUntil { conn.bytes.size >= head.size + 188 * 6 }
        val body = conn.bytes.copyOfRange(head.size, conn.bytes.size)
        assertEquals(listOf(5, 6, 7, 8, 10, 11), (0 until 6).map { body[it * 188].toInt() })
        conn.peerCloses()
        t.join(5_000)
    }

    @Test
    fun everyConnectionIsForItselfAndOneThatTheTvClosesDoesNotEndTheOther() {
        val feed = Feed(1_000 * ms)
        filled(feed, System.nanoTime())
        val s = tsSession(feed)
        val gets = CopyOnWriteArrayList<Long>()
        s.onGet = { gets.add(it) }
        val a = FakeConn()
        val b = FakeConn()
        val ta = serveAsync(s, "GET", a)
        val tb = serveAsync(s, "GET", b)
        waitUntil { a.bytes.size >= head.size + 188 * 4 && b.bytes.size >= head.size + 188 * 4 }
        assertEquals(2, s.streamCount)
        assertEquals(2, gets.size)
        a.peerCloses()
        ta.join(5_000)
        assertEquals(1, s.streamCount)
        feed.history.append(unit(12), System.nanoTime(), false)
        waitUntil { b.bytes.size >= head.size + 188 * 5 }
        b.peerCloses()
        tb.join(5_000)
        assertEquals(0, s.streamCount)
        val recs = s.record!!.connections()
        assertEquals(2, recs.size)
        assertEquals("peer", recs[0].closedBy)
    }

    @Test
    fun theHookOfTheWarmUpIsToldOfEveryGetAndTheTimeIsKept() {
        val feed = Feed(1_000 * ms)
        filled(feed, System.nanoTime())
        val s = tsSession(feed)
        val times = CopyOnWriteArrayList<Long>()
        s.onGet = { times.add(it) }
        val conn = FakeConn()
        val before = System.nanoTime()
        val t = serveAsync(s, "GET", conn)
        waitUntil { times.isNotEmpty() }
        assertTrue(times[0] >= before)
        assertEquals(times[0], s.lastGetNanos)
        conn.peerCloses()
        t.join(5_000)
    }

    // ---- ending --------------------------------------------------------------------------------------

    @Test
    fun aConnectionThatCannotKeepUpIsClosedWithTheReasonInsteadOfBeingGivenAHole() {
        val feed = Feed(0)
        // a ring that holds very little
        val small = object : TsFeed {
            override val history = TsHistory(maxBytes = 400, maxAgeNanos = 1_000_000_000_000L)
            override fun leadNanos() = 1_000 * ms
            override fun requestSync() {}
        }
        small.history.append(unit(1), System.nanoTime() - 2_000 * ms, true)
        val conn = FakeConn()
        val gate = CountDownLatch(1)
        // a TV that does not read for a while: the first write blocks until the test lets it go
        val slow = object : ClientConn by conn {
            override val out: OutputStream = object : OutputStream() {
                override fun write(b: Int) {}

                override fun write(b: ByteArray, off: Int, len: Int) {
                    if (conn.writes.size >= 2) gate.await()
                    conn.out.write(b, off, len)
                }
            }
        }
        val s = tsSession(small)
        val t = Thread {
            try {
                KitchenStreams.serve(Host(s), "tok1", "GET", "192.168.0.69:50000", "TV-UA", slow)
            } catch (e: IOException) {
            }
        }
        t.isDaemon = true
        t.start()
        waitUntil { conn.writes.size >= 2 }
        // while it is stuck the ring moves on by far more than it holds
        for (i in 2..40) small.history.append(unit(i), System.nanoTime(), i % 5 == 0)
        gate.countDown()
        t.join(5_000)
        assertFalse(t.isAlive)
        val rec = s.record!!.connections()[0]
        assertEquals("server", rec.closedBy)
        assertTrue(rec.reason, rec.reason.contains("lapped"))
        assertEquals(0, s.streamCount)
        feed.history.close()
    }

    @Test
    fun theEndOfTheSessionClosesTheConnectionsAndSaysWhy() {
        val feed = Feed(1_000 * ms)
        filled(feed, System.nanoTime())
        val s = tsSession(feed)
        val conn = FakeConn()
        val t = serveAsync(s, "GET", conn)
        waitUntil { conn.bytes.size >= head.size + 188 * 4 }
        s.cancelled = true
        s.endReason = "the TV left"
        t.join(5_000)
        assertFalse(t.isAlive)
        val rec = s.record!!.connections()[0]
        assertEquals("server", rec.closedBy)
        assertTrue(rec.reason.contains("the TV left"))
    }

    @Test
    fun theEndOfTheStreamEndsTheConnectionsToo() {
        val feed = Feed(1_000 * ms)
        filled(feed, System.nanoTime())
        val s = tsSession(feed)
        val conn = FakeConn()
        val t = serveAsync(s, "GET", conn)
        waitUntil { conn.bytes.size >= head.size + 188 * 4 }
        feed.history.close()
        t.join(5_000)
        assertFalse(t.isAlive)
        assertEquals("the stream ended", s.record!!.connections()[0].reason)
    }

    @Test
    fun theSendBufferOfTheSessionIsAskedOfTheSocket() {
        val feed = Feed(1_000 * ms)
        filled(feed, System.nanoTime())
        val s = tsSession(feed)
        s.sendBufferBytes = 64 * 1024
        val conn = FakeConn()
        val t = serveAsync(s, "GET", conn)
        waitUntil { conn.bytes.size >= head.size + 188 }
        assertEquals(64 * 1024, conn.sendBuffer)
        conn.peerCloses()
        t.join(5_000)
    }

    @Test
    fun aSessionWithoutAStreamIsClosedAtOnce() {
        val s = KitchenSession("tok1", -2L, 101, -1L, StreamBuilder.ChannelMode.STEREO, null, 0L, format = StreamFormat.TS)
        s.record = SessionHistory().begin("tok1", "tv", System.nanoTime(), System.currentTimeMillis())
        val conn = FakeConn()
        serveAsync(s, "GET", conn).join(5_000)
        assertNotNull(s.record!!.connections()[0].reason)
        assertEquals(0, s.streamCount)
    }
}
