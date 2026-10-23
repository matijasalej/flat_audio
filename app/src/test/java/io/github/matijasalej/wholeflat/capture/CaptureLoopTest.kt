package io.github.matijasalej.wholeflat.capture

import io.github.matijasalej.wholeflat.sync.KitchenMap
import io.github.matijasalej.wholeflat.sync.StreamBuilder
import io.github.matijasalej.wholeflat.sync.SyncController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * The capture mode's loop with the real parts: the buffer and clock of the capture, the start planner, the
 * stream builder reading the buffer, and the tracker (lag lock, controller, the decision between measuring
 * again and restarting). Only the Sonos is simulated: it plays stream frame s some time after Play (as late
 * as the test says), a little slower when it drifts, may stall, and reports its position 156 ms ahead of the
 * sound, with noise. The capture runs in real time: a frame exists from the moment it is captured.
 */
class CaptureLoopTest {
    private val rate = 48_000
    private val t0 = 1_000_000_000_000L
    private val chunk = 960

    /** The Sonos stops playing for [lengthMs] at [atS] seconds after Play (it refills its buffer), then goes on. */
    private class Stall(val atS: Double, val lengthMs: Double)

    private class Result(
        val rejoinAt: Double?,
        val rejoinReason: String?,
        val lockedAt: Double?,
        val firstLockMs: Double,
        val lockedMs: Double,
        val plannedMs: Double,
        val relocks: Int,
        val lags: List<Pair<Double, Double>>,
        val minMarginMs: Double,
        val tracker: CaptureTracker,
        val planFor: (Int) -> CaptureStart.Plan,
    )

    private fun tOf(frame: Long): Long = t0 + frame * 1_000_000_000L / rate

    private fun simulate(
        seconds: Int,
        lkMs: Double = 290.0,
        extraStartMs: Double = 0.0,
        leadMs: Int = 150,
        offsetMs: Int = 0,
        driftPpm: Double = 0.0,
        noiseMs: Double = 0.0,
        seed: Long = 1,
        changeOffsetAtS: Int = -1,
        newOffsetMs: Int = 0,
        stalls: List<Stall> = emptyList(),
        lead: CaptureLead = CaptureLead(),
    ): Result {
        val buffer = CaptureBuffer(rate, rate * 8)
        buffer.newGeneration(1)
        val clock = CaptureClock(rate)
        val rnd = Random(seed)
        var written = 0L
        fun capture(until: Long) {
            val pcm = ShortArray(chunk * 2)
            while (written + chunk <= until) {
                for (i in 0 until chunk) {
                    pcm[i * 2] = (written + i).toShort()
                    pcm[i * 2 + 1] = (written + i).toShort()
                }
                buffer.write(pcm, chunk)
                written += chunk
                clock.add(written - 1, tOf(written - 1))
            }
        }
        fun framesAt(t: Long): Long = Math.floorDiv((t - t0) * rate, 1_000_000_000L)

        // Play goes out 1.23 s into the capture
        val tPlay = t0 + 1_230_000_000L
        capture(framesAt(tPlay))
        var offset = offsetMs
        // what a stream planned at the moment of Play sees (a later plan, as after a restart, has its own numbers)
        val writtenAtPlay = written
        val validStartAtPlay = buffer.validStart
        fun planWith(offsetNow: Int): CaptureStart.Plan = CaptureStart.plan(
            nowFrame = framesAt(tPlay), writeIndex = writtenAtPlay, validStart = validStartAtPlay, rate = rate,
            leadMs = lead.effectiveMs(leadMs), offsetMs = offsetNow, lkMs = lkMs,
        )
        val plan = planWith(offset)
        val map = KitchenMap(plan.startFrame)
        val builder = StreamBuilder(CaptureFrames(buffer, 1), map, StreamBuilder.ChannelMode.STEREO, trimSpacing = rate / 100)
        val controller = SyncController(
            SyncController.Params(rejoinThresholdMs = 60.0, rejoinReadings = 5, deadbandMs = 8.0, maxPendingFrames = 2_400, rate = rate),
        )
        val tracker = CaptureTracker(controller, CaptureLag(), lead, rate)
        tracker.startStream(plan.plannedLagMs)
        val start = tPlay + ((lkMs + extraStartMs) * 1e6).toLong()
        val speed = 1.0 - driftPpm * 1e-6

        fun stalledBefore(t: Long): Double {
            var total = 0.0
            for (st in stalls) {
                val from = tPlay + (st.atS * 1e9).toLong()
                total += ((t - from) / 1e6).coerceIn(0.0, st.lengthMs)
            }
            return total
        }
        fun stalledAt(t: Long): Boolean = stalls.any { t >= tPlay + (it.atS * 1e9).toLong() && (t - tPlay - (it.atS * 1e9).toLong()) / 1e6 < it.lengthMs }
        fun heardFrame(t: Long): Double =
            Math.max(0.0, ((t - start) / 1e9 - stalledBefore(t) / 1e3) * rate * speed)

        var rejoinAt: Double? = null
        var rejoinReason: String? = null
        var lockedAt: Double? = null
        var firstLock = Double.NaN
        var minMargin = Double.MAX_VALUE
        val lags = ArrayList<Pair<Double, Double>>()
        val builtAt = ArrayList<Long>()
        var checked = 0
        var t = tPlay
        val end = tPlay + seconds * 1_000_000_000L
        var nextReading = start + 600_000_000L
        while (t < end && rejoinAt == null) {
            capture(framesAt(t))
            while (builder.nextBlock(0) == 4096) builtAt.add(t)
            // the Sonos needs stream block b by start + b * 4096 / rate: the margin is how early it was there
            for (b in checked until builtAt.size) {
                val due = start + (b * 4096L * 1e9 / rate / speed).toLong()
                minMargin = Math.min(minMargin, (due - builtAt[b]) / 1e6)
            }
            checked = builtAt.size
            assertTrue("the Sonos ran ahead of the stream", heardFrame(t) / 4096 <= builtAt.size)

            if (changeOffsetAtS >= 0 && t - tPlay >= changeOffsetAtS * 1_000_000_000L && offset != newOffsetMs) offset = newOffsetMs

            if (t >= nextReading) {
                nextReading += 200_000_000L
                if (!stalledAt(t)) {
                    val position = Math.round(heardFrame(t) / rate * 1000.0 + 156.0 + rnd.nextGaussian() * noiseMs)
                    val outcome = tracker.onReading(t, position, map, { builder.unappliedBudget() }, clock, offset, lkMs)
                    when (outcome) {
                        is CaptureTracker.Outcome.Locked -> {
                            if (lockedAt == null) {
                                lockedAt = (t - tPlay) / 1e9
                                firstLock = outcome.lagMs
                            }
                        }
                        is CaptureTracker.Outcome.Trim -> builder.addTrim(outcome.frames)
                        is CaptureTracker.Outcome.Rejoin -> {
                            rejoinAt = (t - tPlay) / 1e9
                            rejoinReason = outcome.reason
                        }
                        else -> Unit
                    }
                    // the real lag of the frame the Sonos plays now
                    val trueFrame = map.ringOfStream(heardFrame(t))!!
                    lags.add(Pair((t - tPlay) / 1e9, (t - clock.nanosOfFrame(Math.round(trueFrame))!!) / 1e6))
                }
            }
            t += 20_000_000L
        }
        return Result(
            rejoinAt, rejoinReason, lockedAt, firstLock, tracker.lag.locked ?: Double.NaN, plan.plannedLagMs,
            tracker.relocks, lags, minMargin, tracker,
        ) { o -> planWith(o) }
    }

    private fun tail(r: Result, from: Double, to: Double = Double.MAX_VALUE) = r.lags.filter { it.first in from..to }.map { it.second }

    @Test
    fun theLagIsMeasuredAfterTheFirstReadingsAndLandsWhereItWasPlanned() {
        val r = simulate(seconds = 30)
        assertTrue("locked", r.lockedAt != null)
        assertTrue("locked within about 3 s of the Sonos starting: ${r.lockedAt}", r.lockedAt!! < 5.0)
        // lk 290 ms + the lead of 150 ms
        assertEquals(440.0, r.plannedMs, 0.5)
        assertEquals(r.plannedMs, r.lockedMs, 3.0)
        assertNull(r.rejoinAt)
        assertEquals("nothing taught: the speaker did as planned", 0, r.tracker.lead.learnedMs)
    }

    @Test
    fun theSonosHasTheAudioWellBeforeItPlaysIt() {
        val r = simulate(seconds = 20)
        assertTrue("the least margin was ${r.minMarginMs} ms", r.minMarginMs > 200.0)
    }

    @Test
    fun aSonosThatStartsLateIsMeasuredNotFoughtAndTheNextStreamIsPlannedForIt() {
        val lead = CaptureLead()
        val r = simulate(seconds = 40, extraStartMs = 350.0, lead = lead)
        assertNull("no re-join", r.rejoinAt)
        assertEquals(r.plannedMs + 350.0, r.lockedMs, 4.0)
        for (lag in tail(r, 10.0)) assertEquals(r.lockedMs, lag, 6.0)
        // 440 + 350 = 790 ms after the capture; minus Play-to-sound 290 ms leaves 500 ms of lead
        assertEquals(500.0, lead.learnedMs.toDouble(), 6.0)
        // the next stream starts that far ahead
        assertEquals(790.0, r.planFor(0).plannedLagMs, 6.0)
    }

    @Test
    fun driftAndNoiseDoNotMoveTheLagByMoreThanTenMilliseconds() {
        // the Sonos is 22 ppm slow, the readings are 3.4 ms off, for ten minutes
        val r = simulate(seconds = 600, driftPpm = 22.0, noiseMs = 3.4, seed = 5)
        assertNull(r.rejoinAt)
        assertEquals("no step was mistaken for a change of the lag", 0, r.relocks)
        val settled = tail(r, 60.0)
        val worst = settled.maxOf { Math.abs(it - r.lockedMs) }
        assertTrue("worst deviation $worst ms from ${r.lockedMs}", worst < 10.0)
        assertEquals(22.0, r.tracker.controller.driftPpm, 10.0)
    }

    @Test
    fun aLaterKitchenOffsetMovesTheLagByThatMuch() {
        val r = simulate(seconds = 160, changeOffsetAtS = 20, newOffsetMs = 30)
        assertNull(r.rejoinAt)
        assertEquals(0, r.relocks)
        for (lag in tail(r, 100.0)) assertEquals(r.lockedMs + 30.0, lag, 10.0)
    }

    @Test
    fun anEarlierKitchenOffsetShortensTheLag() {
        val r = simulate(seconds = 160, changeOffsetAtS = 20, newOffsetMs = -30)
        assertNull(r.rejoinAt)
        assertEquals(0, r.relocks)
        for (lag in tail(r, 100.0)) assertEquals(r.lockedMs - 30.0, lag, 10.0)
    }

    @Test
    fun aBigJumpOfTheOffsetRestartsTheStreamAtTheNewLag() {
        // trims move 2 ms per second: 80 ms in one go cannot be trimmed, and the lag did not move, so it is a re-join
        val r = simulate(seconds = 60, changeOffsetAtS = 20, newOffsetMs = 80)
        assertTrue("re-joined", r.rejoinAt != null)
        assertTrue("within a few seconds of the change: ${r.rejoinAt}", r.rejoinAt!! in 20.0..26.0)
        assertEquals(0, r.relocks)
        // and the restart is planned with the new offset
        assertEquals(r.plannedMs + 80.0, r.planFor(80).plannedLagMs, 0.5)
    }

    @Test
    fun theKitchenOffsetAtTheStartIsPartOfThePlan() {
        val r = simulate(seconds = 30, offsetMs = 100)
        assertEquals(540.0, r.plannedMs, 0.5)
        assertEquals(540.0, r.lockedMs, 4.0)
        assertNull(r.rejoinAt)
    }

    @Test
    fun aStallOfTheSonosIsMeasuredAgainNotRestarted() {
        // the Sonos stops for 700 ms at 40 s (it refills its buffer): the lag is 700 ms bigger afterwards
        val lead = CaptureLead()
        val r = simulate(seconds = 120, stalls = listOf(Stall(40.0, 700.0)), lead = lead)
        assertNull("no restart: ${r.rejoinReason}", r.rejoinAt)
        assertEquals(1, r.relocks)
        assertEquals(r.firstLockMs + 700.0, r.lockedMs, 8.0)
        for (lag in tail(r, 70.0)) assertEquals(r.lockedMs, lag, 8.0)
        // and the next stream is planned for the lag the speaker has shown it needs: 1140 - 290 = 850 ms of lead
        assertEquals(850.0, lead.learnedMs.toDouble(), 10.0)
    }

    @Test
    fun aSpeakerThatKeepsStallingIsRestartedAfterTheSixthStep() {
        val stalls = (1..8).map { Stall(30.0 + it * 15.0, 300.0) }
        val r = simulate(seconds = 300, stalls = stalls)
        assertTrue("re-joined", r.rejoinAt != null)
        assertEquals(CaptureTracker.MAX_RELOCKS, r.relocks)
        assertTrue("after the sixth step, not before: ${r.rejoinAt}", r.rejoinAt!! > 30.0 + 7 * 15.0 - 5.0)
    }

    @Test
    fun aLagThatHasGrownTooFarIsRestarted() {
        val r = simulate(seconds = 120, stalls = listOf(Stall(40.0, 6_000.0)))
        assertTrue("re-joined", r.rejoinAt != null)
        assertEquals("not measured again: too far behind", 0, r.relocks)
        assertTrue(r.rejoinReason!!.isNotEmpty())
    }

    @Test
    fun theLeadIsNotRaisedByNoise() {
        val lead = CaptureLead()
        simulate(seconds = 60, noiseMs = 6.0, seed = 9, lead = lead)
        assertEquals(0, lead.learnedMs)
        assertFalse(lead.onLocked(540.0, 440.0, 290.0, 0))
    }
}
