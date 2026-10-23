package io.github.matijasalej.wholeflat.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncedHoldTest {
    private val rate = 44_100
    private val t0 = 5_000_000_000_000L
    private val ms = 1_000_000L
    private val s = 1_000_000_000L

    private fun at(seconds: Double): Long = t0 + (seconds * 1e9).toLong()

    /** Feeds a constant error every 200 ms from [from] to [to] seconds; returns the decisions that were not Hold with the time they came. */
    private fun feed(hold: SyncedHold, error: Double, from: Double, to: Double, target: Double = 2_171.0): List<Pair<Double, SyncedHold.Decision>> {
        val out = ArrayList<Pair<Double, SyncedHold.Decision>>()
        var t = from
        while (t <= to + 1e-9) {
            val d = hold.onReading(at(t), error, target)
            if (d !is SyncedHold.Decision.Hold) out.add(t to d)
            t += 0.2
        }
        return out
    }

    @Test
    fun theStreamIsPlacedOnceAtItsTargetAndThenLeftAlone() {
        val hold = SyncedHold(rate)
        // the speaker landed 132 ms late (as on the device: 2303 ms for 2171): one trim of that much
        val first = hold.onReading(at(0.0), 132.0, 2_171.0)
        assertTrue(first is SyncedHold.Decision.Trim)
        assertEquals(Math.round(132.0 * 44.1).toInt(), (first as SyncedHold.Decision.Trim).frames)
        assertTrue(hold.placed)
        assertEquals(1, hold.corrections)
        // the trim lands over half a minute, the error (less what is on its way) is about nothing, with the noise of the readings, for ten minutes
        val decisions = ArrayList<SyncedHold.Decision>()
        var t = 0.2
        var i = 0
        while (t < 600.0) {
            val noise = ((i * 7919) % 25 - 12).toDouble() // +-12 ms
            val d = hold.onReading(at(t), noise, 2_171.0)
            if (d !is SyncedHold.Decision.Hold) decisions.add(d)
            t += 0.2
            i++
        }
        assertEquals("nothing is trimmed any more: $decisions", 0, decisions.size)
        assertEquals(1, hold.corrections)
    }

    @Test
    fun aSmallErrorAtTheStartIsLeftAloneAndItIsStillPlaced() {
        val hold = SyncedHold(rate)
        assertTrue(hold.onReading(at(0.0), 12.0, 2_171.0) is SyncedHold.Decision.Hold)
        assertTrue(hold.placed)
        assertEquals(0, hold.corrections)
        // 30 ms is inside the band: nothing is done about it, however long it lasts
        assertTrue(feed(hold, 30.0, 0.2, 300.0).isEmpty())
    }

    @Test
    fun anErrorInsideThePlusMinusFortyBandIsNeverTrimmed() {
        val hold = SyncedHold(rate)
        hold.onReading(at(0.0), 0.0, 2_171.0)
        assertTrue(feed(hold, 39.0, 0.2, 600.0).isEmpty())
        assertTrue(feed(hold, -39.0, 600.2, 1_200.0).isEmpty())
        assertEquals(0, hold.corrections)
    }

    @Test
    fun anErrorBeyondTheBandIsCorrectedOnlyAfterThirtySecondsWithoutABreak() {
        val hold = SyncedHold(rate)
        hold.onReading(at(0.0), 0.0, 2_171.0)
        // 29.8 s beyond the band: nothing
        assertTrue(feed(hold, 50.0, 10.0, 39.8).isEmpty())
        assertTrue(hold.farForMs in 29_000L..30_000L)
        // and at 30 s the correction comes, of the error, once
        val out = feed(hold, 50.0, 40.0, 40.0)
        assertEquals(1, out.size)
        val trim = out[0].second as SyncedHold.Decision.Trim
        assertEquals(Math.round(50.0 * 44.1).toInt(), trim.frames)
        assertTrue(trim.why, trim.why.contains("50"))
        assertEquals(1, hold.corrections)
    }

    @Test
    fun aBreakInsideTheBandStartsTheThirtySecondsAgain() {
        val hold = SyncedHold(rate)
        hold.onReading(at(0.0), 0.0, 2_171.0)
        assertTrue(feed(hold, 60.0, 10.0, 35.0).isEmpty()) // 25 s
        assertTrue(hold.onReading(at(35.2), 10.0, 2_171.0) is SyncedHold.Decision.Hold) // back inside for one reading
        assertEquals(0L, hold.farForMs)
        assertTrue(feed(hold, 60.0, 35.4, 65.0).isEmpty()) // 29.6 s again
        assertEquals(1, feed(hold, 60.0, 65.2, 66.0).size)
    }

    @Test
    fun correctionsAreAtMostOneAMinuteAndTheErrorMustLastThirtySecondsAgain() {
        val hold = SyncedHold(rate)
        hold.onReading(at(0.0), 0.0, 2_171.0)
        val out = feed(hold, 60.0, 10.0, 200.0)
        // the first at 40 s; the error stays (as if the trim had not landed): the next needs 30 s more beyond the band and 60 s since the last
        assertEquals(3, out.size)
        val expected = listOf(40.0, 100.0, 160.0)
        for (i in expected.indices) assertEquals(expected[i], out[i].first, 0.5)
    }

    @Test
    fun aNewTargetSetsTheKitchenOnceMoreAtOnce() {
        val hold = SyncedHold(rate)
        hold.onReading(at(0.0), 0.0, 2_171.0)
        assertTrue(feed(hold, 0.0, 0.2, 100.0).isEmpty())
        // a reconnect of the TV: its backlog is another and the common delay moves by 85 ms: the kitchen moves with it, later, whatever the
        // reading of the lag says at that moment (it is only good to 10 ms or so)
        val d = hold.onReading(at(100.2), 7.0, 2_256.0)
        assertTrue(d is SyncedHold.Decision.Trim)
        assertEquals(Math.round(-85.0 * 44.1).toInt(), (d as SyncedHold.Decision.Trim).frames)
        assertTrue(d.why, d.why.contains("2171") && d.why.contains("2256"))
        assertEquals(1, hold.corrections)
        // a kitchen offset of 10 ms is a new target too, and a small one is no reason to wait for a reading that is better than that
        val e = hold.onReading(at(130.0), 3.0, 2_246.0)
        assertTrue(e is SyncedHold.Decision.Trim)
        assertEquals(Math.round(10.0 * 44.1).toInt(), (e as SyncedHold.Decision.Trim).frames)
        assertEquals(2, hold.corrections)
    }

    @Test
    fun aTargetThatMovesBeforeTheStreamWasPlacedIsJustTheTarget() {
        val hold = SyncedHold(rate)
        // nothing was read yet: the first reading is placed against the target it brings
        val d = hold.onReading(at(0.0), 60.0, 2_300.0)
        assertTrue(d is SyncedHold.Decision.Trim)
        assertEquals(Math.round(60.0 * 44.1).toInt(), (d as SyncedHold.Decision.Trim).frames)
    }

    @Test
    fun aMoveOfTheTargetBeyondWhatATrimIsForIsAFreshStart() {
        val hold = SyncedHold(rate)
        hold.onReading(at(0.0), 0.0, 2_171.0)
        assertTrue(hold.onReading(at(10.0), 0.0, 2_500.0) is SyncedHold.Decision.Restart)
    }

    @Test
    fun aTargetThatMovesByAFewTenthsOfAMillisecondIsTheSameTarget() {
        val hold = SyncedHold(rate)
        hold.onReading(at(0.0), 0.0, 2_171.0)
        assertTrue(hold.onReading(at(1.0), 20.0, 2_172.0) is SyncedHold.Decision.Hold)
        assertTrue(hold.onReading(at(2.0), 20.0, 2_170.5) is SyncedHold.Decision.Hold)
        assertEquals(0, hold.corrections)
    }

    @Test
    fun anErrorTooBigForATrimIsAFreshStartUnlessTheLastOneWasJustNow() {
        val hold = SyncedHold(rate)
        val d = hold.onReading(at(0.0), 300.0, 2_171.0)
        assertTrue(d is SyncedHold.Decision.Restart)
        // the new stream: if it lands as far off, it is trimmed this time (the caller says no to another start)
        hold.reset()
        hold.restartAllowed = false
        val e = hold.onReading(at(5.0), 300.0, 2_171.0)
        assertTrue(e is SyncedHold.Decision.Trim)
        assertEquals(Math.round(300.0 * 44.1).toInt(), (e as SyncedHold.Decision.Trim).frames)
        // and never more than 600 ms in one go
        hold.reset()
        val f = hold.onReading(at(10.0), 900.0, 2_171.0)
        assertEquals(Math.round(600.0 * 44.1).toInt(), (f as SyncedHold.Decision.Trim).frames)
    }

    @Test
    fun aLateKitchenIsTrimmedForwardAndAnEarlyOneBackward() {
        val late = SyncedHold(rate)
        assertTrue((late.onReading(at(0.0), 40.0, 2_171.0) as SyncedHold.Decision.Trim).frames > 0)
        val early = SyncedHold(rate)
        assertTrue((early.onReading(at(0.0), -40.0, 2_171.0) as SyncedHold.Decision.Trim).frames < 0)
    }

    @Test
    fun aNewStreamStartsAgainAndNothingIsKnownBeforeTheFirstReading() {
        val hold = SyncedHold(rate)
        assertTrue(hold.targetMs.isNaN())
        assertTrue(hold.onReading(at(0.0), Double.NaN, 2_171.0) is SyncedHold.Decision.Hold)
        assertTrue(hold.onReading(at(0.0), 10.0, Double.NaN) is SyncedHold.Decision.Hold)
        assertFalse(hold.placed)
        hold.onReading(at(1.0), 100.0, 2_171.0)
        assertTrue(hold.placed)
        hold.reset()
        assertFalse(hold.placed)
        assertEquals(0, hold.corrections)
        assertTrue(hold.errorMs.isNaN())
    }
}
