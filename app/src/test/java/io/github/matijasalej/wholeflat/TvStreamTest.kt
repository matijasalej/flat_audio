package io.github.matijasalej.wholeflat

import io.github.matijasalej.wholeflat.capture.CaptureFeed
import io.github.matijasalej.wholeflat.sync.L16
import io.github.matijasalej.wholeflat.sync.SessionHistory
import io.github.matijasalej.wholeflat.sync.StreamBuilder
import io.github.matijasalej.wholeflat.sync.StreamFormat
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

/**
 * The stream to the TV's DLNA renderer, with a fake TV: raw big-endian samples without a header, the headers that worked on the
 * device, sent in real time from the moment the TV connects, at the frame that the session picks for that moment, held against the
 * capture clock with trims.
 */
class TvStreamTest {
    @get:Rule
    val timeout: Timeout = Timeout(30, TimeUnit.SECONDS)

    private val logs = CopyOnWriteArrayList<String>()
    private val rate = 44_100
    private val block = StreamFormat.WAV_CHUNK_FRAMES
    private val blockBytes = block * 4

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

        val written: Int get() = writes.sumOf { it.size }
        val bytes: ByteArray get() = writes.fold(ByteArray(0)) { a, b -> a + b }
    }

    private class Host(val session: KitchenSession) : StreamHost {
        val lost = CopyOnWriteArrayList<String>()

        override fun sessionFor(token: String): KitchenSession? = if (session.token == token) session else null

        override fun streamLost(s: KitchenSession, reason: String) {
            lost.add(reason)
        }
    }

    /** A capture of [seconds] seconds whose frame f carries the value f (low 16 bits) on both channels. */
    private fun feedOf(seconds: Double): CaptureFeed {
        val feed = CaptureFeed()
        feed.newGeneration(1)
        val per = rate / 100
        var next = 0L
        val total = (seconds * rate).toLong()
        while (next < total) {
            val pcm = ShortArray(per * 2)
            for (i in 0 until per) {
                pcm[i * 2] = (next + i).toShort()
                pcm[i * 2 + 1] = (next + i).toShort()
            }
            feed.writeAt(pcm, per, 1_000L + next)
            next += per
        }
        return feed
    }

    private fun tvSession(feed: CaptureFeed, start: Long = 10_000L, realTime: Boolean = true): KitchenSession {
        val s = KitchenSession("tok1", -1L, 101, -1L, StreamBuilder.ChannelMode.STEREO, feed, feed.generation, format = StreamFormat.L16)
        s.realTime = realTime
        s.startAtConnect = { start }
        s.record = SessionHistory().begin("tok1", "tv", System.nanoTime(), System.currentTimeMillis())
        return s
    }

    private fun serveAsync(session: KitchenSession, method: String, conn: FakeConn): Thread {
        val host = Host(session)
        val t = Thread {
            try {
                KitchenStreams.serve(host, "tok1", method, "192.168.0.69:50000", "TV-UA", conn)
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

    // ---- the headers and the samples -----------------------------------------------------------------

    @Test
    fun theHeadersAreExactlyTheSetThatWorkedOnTheTvAndThereIsNoLengthAndNoHeaderOfTheAudio() {
        val head = KitchenStreams.RESPONSE_HEAD_L16
        assertEquals(
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: audio/L16;rate=44100;channels=2\r\n" +
                "transferMode.dlna.org: Streaming\r\n" +
                "contentFeatures.dlna.org: DLNA.ORG_PN=LPCM;DLNA.ORG_OP=00;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000\r\n" +
                "Connection: close\r\n\r\n",
            head,
        )
        assertFalse(head.contains("Content-Length"))
        assertFalse(head.contains("Transfer-Encoding"))
        assertEquals(head, KitchenStreams.responseHead(StreamFormat.L16))
        // a stream of this format is found by its extension
        assertEquals("abc123", KitchenStreams.tokenOf("/k/abc123.l16"))
        assertEquals(StreamFormat.L16, StreamFormat.ofPath("/k/abc123.l16"))
    }

    @Test
    fun aGetStartsWithTheHeadersThenRawBigEndianSamplesFromTheFrameTheSessionPicks() {
        val s = tvSession(feedOf(3.0), start = 10_000L)
        val conn = FakeConn()
        val t = serveAsync(s, "GET", conn)
        waitUntil { conn.writes.isNotEmpty() }
        val first = conn.writes[0]
        val head = KitchenStreams.RESPONSE_HEAD_L16.toByteArray(Charsets.ISO_8859_1)
        assertEquals(head.toList(), first.copyOfRange(0, head.size).toList())
        // no header of the audio: the next bytes are the first frame, big-endian, left and right
        val p = head.size
        assertEquals((10_000 shr 8) and 0xFF, first[p].toInt() and 0xFF)
        assertEquals(10_000 and 0xFF, first[p + 1].toInt() and 0xFF)
        assertEquals(first[p], first[p + 2])
        assertEquals(first[p + 1], first[p + 3])
        // the next frame is 10_001
        assertEquals(10_001 and 0xFF, first[p + 5].toInt() and 0xFF)
        // whole chunks of 10 ms
        assertEquals(0, (first.size - head.size) % blockBytes)
        conn.peerCloses()
        t.join(5_000)
    }

    @Test
    fun aHeadGetsTheSameHeadersAndNothingElse() {
        val s = tvSession(feedOf(1.0))
        val conn = FakeConn()
        serveAsync(s, "HEAD", conn).join(5_000)
        assertEquals(1, conn.writes.size)
        assertEquals(KitchenStreams.RESPONSE_HEAD_L16, String(conn.writes[0], Charsets.ISO_8859_1))
        assertEquals("a HEAD is no stream", 0, s.connectionCount)
    }

    // ---- the start and the pace ----------------------------------------------------------------------

    @Test
    fun theStartFrameIsDecidedAtTheMomentOfTheConnection() {
        val s = tvSession(feedOf(3.0))
        val asked = CopyOnWriteArrayList<Long>()
        s.startAtConnect = { now ->
            asked.add(now)
            20_000L
        }
        val before = System.nanoTime()
        val conn = FakeConn()
        val t = serveAsync(s, "GET", conn)
        waitUntil { conn.writes.isNotEmpty() }
        val after = System.nanoTime()
        assertEquals(1, asked.size)
        assertTrue(asked[0] in before..after)
        val p = KitchenStreams.RESPONSE_HEAD_L16.length
        assertEquals((20_000 shr 8) and 0xFF, conn.writes[0][p].toInt() and 0xFF)
        // a second connection (the TV opens several) picks its own start
        val conn2 = FakeConn()
        val t2 = serveAsync(s, "GET", conn2)
        waitUntil { conn2.writes.isNotEmpty() }
        assertEquals(2, asked.size)
        conn.peerCloses()
        conn2.peerCloses()
        t.join(5_000)
        t2.join(5_000)
    }

    @Test
    fun aRealTimeStreamIsSentInRealTimeNotAllAtOnce() {
        val s = tvSession(feedOf(5.0))
        val conn = FakeConn()
        val t = serveAsync(s, "GET", conn)
        waitUntil { conn.writes.isNotEmpty() }
        val head = KitchenStreams.RESPONSE_HEAD_L16.length
        val started = System.nanoTime()
        Thread.sleep(400)
        val seconds = (System.nanoTime() - started) / 1e9
        val chunks = (conn.written - head) / blockBytes
        // 100 chunks per second, plus the one or two of the first write
        val expected = seconds * 100 + 1
        assertTrue("$chunks chunks in $seconds s", chunks >= expected - 8 && chunks <= expected + 8)
        conn.peerCloses()
        t.join(5_000)
    }

    @Test
    fun theFirstWriteHoldsTheHeadersAndOnlyWhatIsDueAtOnce() {
        val s = tvSession(feedOf(3.0))
        val conn = FakeConn()
        val t = serveAsync(s, "GET", conn)
        waitUntil { conn.writes.isNotEmpty() }
        val head = KitchenStreams.RESPONSE_HEAD_L16.length
        val chunks = (conn.writes[0].size - head) / blockBytes
        assertTrue("$chunks chunks in the first write", chunks in 1..3)
        conn.peerCloses()
        t.join(5_000)
    }

    @Test
    fun theLeadInIsDigitalSilenceSentBeforeTheStream() {
        val s = tvSession(feedOf(3.0), start = 10_000L)
        s.leadInFrames = 4_410 // 100 ms
        val conn = FakeConn()
        val t = serveAsync(s, "GET", conn)
        waitUntil { conn.writes.isNotEmpty() }
        val first = conn.writes[0]
        val head = KitchenStreams.RESPONSE_HEAD_L16.length
        assertTrue("silence first", (head until head + 4_410 * 4).all { first[it].toInt() == 0 })
        val p = head + 4_410 * 4
        assertEquals((10_000 shr 8) and 0xFF, first[p].toInt() and 0xFF)
        assertEquals(0, (first.size - p) % blockBytes)
        conn.peerCloses()
        t.join(5_000)
    }

    @Test
    fun aRealTimeStreamIsLiveBeforeAnyPlayAndTheNewestConnectionIsTheOneThatCounts() {
        val s = tvSession(feedOf(3.0))
        assertFalse(s.armed)
        val conn = FakeConn()
        val t = serveAsync(s, "GET", conn)
        waitUntil { conn.writes.isNotEmpty() }
        val steered = s.stream
        assertNotNull("it is the stream, not a probe", steered)
        assertEquals(1, s.streamCount)
        conn.peerCloses()
        t.join(5_000)
        assertNull(s.stream)
        val c = s.record!!.toJson(System.nanoTime()).getJSONArray("connections").getJSONObject(0)
        assertEquals("peer", c.getString("closed_by"))
        assertTrue(c.getLong("writes") >= 1)
        assertTrue(c.has("write_ms_max"))
        assertTrue(c.has("slip_ms_max"))
    }

    @Test
    fun theSendBufferIsAskedForWhenTheSessionWantsASmallOne() {
        val s = tvSession(feedOf(2.0))
        s.sendBufferBytes = 16 * 1024
        val conn = FakeConn()
        val t = serveAsync(s, "GET", conn)
        waitUntil { conn.writes.isNotEmpty() }
        assertEquals(16 * 1024, conn.sendBuffer)
        conn.peerCloses()
        t.join(5_000)
        val none = tvSession(feedOf(2.0))
        val conn2 = FakeConn()
        val t2 = serveAsync(none, "GET", conn2)
        waitUntil { conn2.writes.isNotEmpty() }
        assertEquals(0, conn2.sendBuffer)
        conn2.peerCloses()
        t2.join(5_000)
    }

    // ---- the hold ------------------------------------------------------------------------------------

    @Test
    fun aStreamThatIsLateAgainstTheCaptureClockHasFramesDroppedUntilItIsBack() {
        val feed = feedOf(8.0)
        val s = tvSession(feed, start = 10_000L)
        val hold = 110_000_000L
        s.holdTargetNanos = { hold }
        // the capture clock says frame 10_000 was captured 40 ms later than a hold ago: the stream starts 40 ms late
        s.startAtConnect = { began ->
            feed.onTimestamp(10_000L, began - hold - 40_000_000L)
            10_000L
        }
        val conn = FakeConn()
        val t = serveAsync(s, "GET", conn)
        waitUntil { conn.writes.isNotEmpty() }
        val rec = s.record!!.connections().first { it.method == "GET" }
        waitUntil(6_000) { rec.trimmedFrames > 0 }
        assertEquals("40 ms late", 40.0, rec.holdErrorMs, 3.0)
        // half of 40 ms at 44.1 kHz = 882 frames asked for in the first look
        assertTrue("trimmed ${rec.trimmedFrames}", rec.trimmedFrames in 800..2_000)
        conn.peerCloses()
        t.join(5_000)
    }

    @Test
    fun aStreamThatIsOnTimeIsLeftAlone() {
        val feed = feedOf(8.0)
        val s = tvSession(feed, start = 10_000L)
        val hold = 110_000_000L
        s.holdTargetNanos = { hold }
        s.startAtConnect = { began ->
            feed.onTimestamp(10_000L, began - hold - 1_000_000L) // 1 ms late: inside the deadband
            10_000L
        }
        val conn = FakeConn()
        val t = serveAsync(s, "GET", conn)
        waitUntil { conn.writes.isNotEmpty() }
        val rec = s.record!!.connections().first { it.method == "GET" }
        waitUntil(6_000) { !rec.holdErrorMs.isNaN() }
        assertEquals(0L, rec.trimmedFrames)
        conn.peerCloses()
        t.join(5_000)
    }

    @Test
    fun theStatisticsOfAConnectionAreLoggedForTheCalibration() {
        val s = tvSession(feedOf(5.0))
        s.statsEveryMs = 250
        val conn = FakeConn()
        val t = serveAsync(s, "GET", conn)
        waitUntil(5_000) { logs.any { it.contains("x real time") } }
        val line = logs.first { it.contains("x real time") }
        assertTrue(line, line.contains("a write blocked"))
        assertTrue(line, line.contains("behind the schedule"))
        conn.peerCloses()
        t.join(5_000)
    }

    // ---- who made the stream -------------------------------------------------------------------------

    @Test
    fun theRouterFindsTheSessionOfATokenAmongTheHostsAndReportsALostStreamToItsOwner() {
        val s = tvSession(feedOf(1.0))
        val tv = Host(s)
        val other = object : StreamHost {
            val lost = CopyOnWriteArrayList<String>()
            override fun sessionFor(token: String): KitchenSession? = null
            override fun streamLost(s: KitchenSession, reason: String) {
                lost.add(reason)
            }
        }
        StreamRouter.add(other)
        StreamRouter.add(tv)
        try {
            assertFalse(StreamRouter.isEmpty)
            assertSame(s, StreamRouter.sessionFor("tok1"))
            assertNull(StreamRouter.sessionFor("nobody"))
            StreamRouter.streamLost(s, "gone")
            assertEquals(listOf("gone"), tv.lost.toList())
            assertTrue(other.lost.isEmpty())
        } finally {
            StreamRouter.remove(other)
            StreamRouter.remove(tv)
        }
    }

    @Test
    fun theL16EncoderIsBigEndianWhereWavIsLittleEndian() {
        val pcm = shortArrayOf(0x1234, 0x7FFF, -2, Short.MIN_VALUE)
        val b = L16.pcm(pcm, 2)
        assertEquals(listOf(0x12, 0x34, 0x7F, 0xFF, 0xFF, 0xFE, 0x80, 0x00), b.map { it.toInt() and 0xFF })
        val w = io.github.matijasalej.wholeflat.sync.Wav.pcm(pcm, 2)
        assertEquals(b[0], w[1])
        assertEquals(b[1], w[0])
        assertEquals(16, L16.silence(4).size)
        assertTrue(L16.silence(4).all { it.toInt() == 0 })
    }
}
