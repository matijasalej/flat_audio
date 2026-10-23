package io.github.matijasalej.wholeflat.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class TsHistoryTest {
    private val s = 1_000_000_000L

    private fun unit(size: Int, fill: Int) = ByteArray(size) { fill.toByte() }

    /** One unit per 100 ms of 10 bytes, a key one every 1 s (10 units). */
    private fun filled(seconds: Int): TsHistory {
        val h = TsHistory()
        for (i in 0 until seconds * 10) h.append(unit(10, i % 100), i * 100_000_000L, i % 10 == 0)
        return h
    }

    @Test
    fun aConnectionStartsAtTheNewestKeyPlaceThatIsOldEnough() {
        val h = filled(5) // key places at 0, 1, 2, 3, 4 s, the newest unit at 4.9 s
        // now = 4.95 s with a lead of 1 s: the key place at 3 s is the newest one that is 1 s old (3.95 would allow 3.0 only)
        val pos = h.startFor((4.95 * s).toLong(), 1 * s)
        assertEquals(30L * 10, pos)
        // a lead of 0 starts at the newest key place
        assertEquals(40L * 10, h.startFor((4.95 * s).toLong(), 0))
        // a lead that is longer than the stream is long starts at the oldest key place
        assertEquals(0L, h.startFor((4.95 * s).toLong(), 30 * s))
    }

    @Test
    fun withoutAKeyPlaceThereIsNoStart() {
        val h = TsHistory()
        assertNull(h.startFor(0, 0))
        h.append(unit(10, 1), 0, false)
        assertNull(h.startFor(s, 0))
        assertNull(h.keyAfter(0, 5))
    }

    @Test
    fun readingGivesWholeUnitsFromThePositionAndTheNextPosition() {
        val h = filled(1)
        val r = h.read(0, 25, 10) as TsHistory.Read.Data
        // 10 + 10 fit in 25 bytes, the third would not
        assertEquals(20, r.bytes.size)
        assertEquals(20L, r.next)
        assertArrayEquals(unit(10, 0) + unit(10, 1), r.bytes)
        val rest = h.read(r.next, 1_000_000, 10) as TsHistory.Read.Data
        assertEquals(80, rest.bytes.size)
        assertEquals(100L, rest.next)
    }

    @Test
    fun aUnitBiggerThanTheLimitIsStillGivenWhole() {
        val h = TsHistory()
        h.append(unit(5_000, 7), 0, true)
        val r = h.read(0, 100, 10) as TsHistory.Read.Data
        assertEquals(5_000, r.bytes.size)
    }

    @Test
    fun aReaderAtTheEndWaitsAndGetsWhatComes() {
        val h = filled(1)
        assertTrue(h.read(100, 1000, 20) is TsHistory.Read.Timeout)
        val got = AtomicReference<TsHistory.Read>()
        val t = thread { got.set(h.read(100, 1000, 2_000)) }
        Thread.sleep(50)
        h.append(unit(10, 99), s, false)
        t.join(3_000)
        val r = got.get() as TsHistory.Read.Data
        assertArrayEquals(unit(10, 99), r.bytes)
        assertEquals(110L, r.next)
    }

    @Test
    fun aReaderThatFellBehindIsToldSo() {
        val h = TsHistory(maxBytes = 100, maxAgeNanos = 100 * s)
        for (i in 0 until 50) h.append(unit(10, i), i * 100_000_000L, i % 10 == 0)
        assertTrue(h.heldBytes <= 100 + 100) // trimmed at about the size asked for (never past the newest key place)
        assertTrue(h.read(0, 100, 10) is TsHistory.Read.Lapped)
        assertTrue(h.read(h.oldestPos, 100, 10) is TsHistory.Read.Data)
    }

    @Test
    fun theNewestKeyPlaceAndWhatFollowsItAreNeverTrimmed() {
        val h = TsHistory(maxBytes = 10, maxAgeNanos = 1)
        h.append(unit(10, 1), 0, true)
        h.append(unit(10, 2), 5 * s, false)
        h.append(unit(10, 3), 10 * s, false)
        assertEquals(0L, h.oldestPos)
        assertEquals(0L, h.startFor(10 * s, 0))
        h.append(unit(10, 4), 11 * s, true) // a newer key place: what came before it may go now
        assertEquals(30L, h.oldestPos)
        assertEquals(30L, h.startFor(11 * s, 0))
    }

    @Test
    fun theAgeLimitAlsoTrims() {
        val h = TsHistory(maxBytes = 1_000_000, maxAgeNanos = 3 * s)
        for (i in 0 until 100) h.append(unit(10, i), i * 100_000_000L, i % 10 == 0)
        // the newest unit is at 9.9 s; what is older than 3 s goes, down to the newest key place at 9 s at least
        assertTrue(h.oldestPos >= 60L * 10)
        assertEquals(90L * 10, h.newestKey())
    }

    @Test
    fun aFreshStartWaitsForTheNextKeyPlace() {
        val h = filled(1) // the only key place is at 0
        assertNull(h.keyAfter(s, 20)) // none after 1 s yet
        val got = AtomicReference<Long?>()
        val t = thread { got.set(h.keyAfter(s, 2_000)) }
        Thread.sleep(50)
        h.append(unit(10, 5), 2 * s, true)
        t.join(3_000)
        assertEquals(100L, got.get())
    }

    @Test
    fun closingEndsTheReadersAfterTheLastByte() {
        val h = filled(1)
        h.close()
        val r = h.read(90, 1000, 10) as TsHistory.Read.Data
        assertEquals(10, r.bytes.size)
        assertTrue(h.read(100, 1000, 10) is TsHistory.Read.Closed)
        assertNull(h.keyAfter(10 * s, 1_000))
    }

    @Test
    fun aPositionInTheMiddleOfAUnitIsServedFromThere() {
        val h = TsHistory()
        h.append(byteArrayOf(1, 2, 3, 4, 5), 0, true)
        val r = h.read(2, 100, 10) as TsHistory.Read.Data
        assertArrayEquals(byteArrayOf(3, 4, 5), r.bytes)
        assertEquals(5L, r.next)
    }
}
