package io.github.matijasalej.wholeflat.capture

import io.github.matijasalej.wholeflat.KitchenStreams
import io.github.matijasalej.wholeflat.sync.FrameTimes
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
    private val rate = CaptureFeed.RATE
    private val t0 = 1_000_000_000_000L
    private val chunk = rate / 100

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
        val planFor: () -> CaptureStart.Plan,
        /** The stream ended because the speaker ran dry at the lag it was brought to (the floor was raised, the stream is to start again). */
        val rejoinUnderrun: Boolean = false,
        /** Times the simulated Sonos ran dry because the lag went below its limit. */
        val dryRuns: Int = 0,
        /** The setpoint of the steer (and the state it was in) at each reading: (seconds after Play, setpoint, state). */
        val steerTrace: List<Triple<Double, Double, LagSteer.State>> = emptyList(),
        /** The trims that were asked for: (seconds after Play, frames). */
        val trims: List<Pair<Double, Int>> = emptyList(),
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
        /** The lag the kitchen is brought to (NaN: held where it locked), and a later one from [changeTargetAtS] on. */
        targetMs: Double = Double.NaN,
        changeTargetAtS: Int = -1,
        newTargetMs: Double = Double.NaN,
        /** The simulated Sonos runs dry (stops for [limitStallMs] and fills its buffer again) whenever its lag is below this. */
        limitLagMs: Double = 0.0,
        limitStallMs: Double = 400.0,
        /** Whether the Kitchen tells the tracker that the speaker reports buffering when that happens (else only the readings show it). */
        tellTheTracker: Boolean = true,
        /** A tracker to continue with (a floor that was learned stays), else a new one. */
        trackerIn: CaptureTracker? = null,
        /** The lowest lag aimed for, from the settings (NaN: none). */
        floorMs: Double = Double.NaN,
        /** How long the capture has been running when Play goes out (a lead is limited by what the buffer holds). */
        playAfterS: Double = 1.23,
        /** Synced mode: the stream is set once at this lag and left alone (NaN: the Lyrics mode's controller); [changeTargetAtS] / [newTargetMs] move it. */
        syncedTargetMs: Double = Double.NaN,
        /** From [clockStepAtS] seconds after Play the timestamps of the capture say [clockStepMs] later (a step of the clock, nothing is heard of it). */
        clockStepAtS: Double = -1.0,
        clockStepMs: Double = 0.0,
        /** Synced mode: the clock the lag is counted from (null: the capture's own clock, as the Lyrics mode does). */
        syncedClock: FrameTimes? = null,
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
                val stepped = clockStepAtS >= 0 && tOf(written - 1) >= t0 + ((playAfterS + clockStepAtS) * 1e9).toLong()
                clock.add(written - 1, tOf(written - 1) + if (stepped) (clockStepMs * 1e6).toLong() else 0L)
            }
        }
        fun framesAt(t: Long): Long = Math.floorDiv((t - t0) * rate, 1_000_000_000L)

        // Play goes out 1.23 s into the capture (or when the test says)
        val tPlay = t0 + (playAfterS * 1e9).toLong()
        capture(framesAt(tPlay))
        var offset = offsetMs
        // what a stream planned at the moment of Play sees (a later plan, as after a restart, has its own numbers)
        val writtenAtPlay = written
        val validStartAtPlay = buffer.validStart
        fun planWith(): CaptureStart.Plan = CaptureStart.plan(
            nowFrame = framesAt(tPlay), writeIndex = writtenAtPlay, validStart = validStartAtPlay, rate = rate,
            leadMs = lead.effectiveMs(leadMs), lkMs = lkMs,
        )
        val plan = planWith()
        val map = KitchenMap(plan.startFrame)
        val builder = StreamBuilder(CaptureFrames(buffer, 1), map, StreamBuilder.ChannelMode.STEREO, trimSpacing = KitchenStreams.trimSpacing(rate, captured = true))
        val controller = SyncController(CaptureTracker.controllerParams(rate))
        val tracker = trackerIn ?: CaptureTracker(controller, CaptureLag(), lead, rate)
        tracker.startStream(plan.plannedLagMs)
        var target = targetMs
        var syncedNow = syncedTargetMs
        val trims = ArrayList<Pair<Double, Int>>()
        val allStalls = ArrayList(stalls)
        var dryRuns = 0
        val steerTrace = ArrayList<Triple<Double, Double, LagSteer.State>>()
        val start = tPlay + ((lkMs + extraStartMs) * 1e6).toLong()
        val speed = 1.0 - driftPpm * 1e-6

        fun stalledBefore(t: Long): Double {
            var total = 0.0
            for (st in allStalls) {
                val from = tPlay + (st.atS * 1e9).toLong()
                total += ((t - from) / 1e6).coerceIn(0.0, st.lengthMs)
            }
            return total
        }
        fun stalledAt(t: Long): Boolean = allStalls.any { t >= tPlay + (it.atS * 1e9).toLong() && (t - tPlay - (it.atS * 1e9).toLong()) / 1e6 < it.lengthMs }
        fun heardFrame(t: Long): Double =
            Math.max(0.0, ((t - start) / 1e9 - stalledBefore(t) / 1e3) * rate * speed)

        var rejoinAt: Double? = null
        var rejoinReason: String? = null
        var rejoinUnderrun = false
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
            if (changeTargetAtS >= 0 && t - tPlay >= changeTargetAtS * 1_000_000_000L) {
                if (syncedTargetMs.isNaN()) target = newTargetMs else syncedNow = newTargetMs
            }

            // the simulated Sonos runs dry when its lag is below its limit: it stops, fills its buffer again, and the lag is bigger by that
            if (limitLagMs > 0 && t > start && !stalledAt(t)) {
                val playing = map.ringOfStream(heardFrame(t))
                val capturedAt = playing?.let { clock.nanosOfFrame(Math.round(it)) }
                if (capturedAt != null && (t - capturedAt) / 1e6 < limitLagMs) {
                    allStalls.add(Stall((t - tPlay) / 1e9, limitStallMs))
                    dryRuns++
                    if (tellTheTracker) {
                        // the Kitchen starts the stream again when the speaker's own signal raised the floor
                        val e = tracker.onSpeakerTrouble(t, "PLAYBACK_STATE_BUFFERING")
                        if (e != null) {
                            rejoinAt = (t - tPlay) / 1e9
                            rejoinReason = "the speaker said so: " + e.why
                            rejoinUnderrun = true
                        }
                    }
                }
            }

            if (t >= nextReading) {
                nextReading += 200_000_000L
                if (!stalledAt(t)) {
                    val position = Math.round(heardFrame(t) / rate * 1000.0 + 156.0 + rnd.nextGaussian() * noiseMs)
                    val outcome = if (syncedNow.isNaN()) {
                        tracker.onReading(t, position, map, { builder.unappliedBudget() }, clock, offset, lkMs, target, floorMs)
                    } else {
                        tracker.onSyncedReading(t, position, map, { builder.unappliedBudget() }, syncedClock ?: clock, syncedNow)
                    }
                    when (outcome) {
                        is CaptureTracker.Outcome.Locked -> {
                            if (lockedAt == null) {
                                lockedAt = (t - tPlay) / 1e9
                                firstLock = outcome.lagMs
                            }
                        }
                        is CaptureTracker.Outcome.Trim -> {
                            builder.addTrim(outcome.frames)
                            trims.add((t - tPlay) / 1e9 to outcome.frames)
                        }
                        is CaptureTracker.Outcome.Rejoin -> {
                            if (rejoinAt == null) {
                                rejoinAt = (t - tPlay) / 1e9
                                rejoinReason = outcome.reason
                                rejoinUnderrun = outcome.underrun
                            }
                        }
                        else -> Unit
                    }
                    steerTrace.add(Triple((t - tPlay) / 1e9, tracker.steer.setpointMs, tracker.steer.state()))
                    // the real lag of the frame the Sonos plays now
                    val trueFrame = map.ringOfStream(heardFrame(t))!!
                    // (a clock that steps is not the truth: the frames were captured when they were)
                    val capturedAt = if (clockStepMs != 0.0) tOf(Math.round(trueFrame)) else clock.nanosOfFrame(Math.round(trueFrame))!!
                    lags.add(Pair((t - tPlay) / 1e9, (t - capturedAt) / 1e6))
                }
            }
            t += 20_000_000L
        }
        return Result(
            rejoinAt, rejoinReason, lockedAt, firstLock, tracker.lag.locked ?: Double.NaN, plan.plannedLagMs,
            tracker.relocks, lags, minMargin, tracker, { planWith() }, rejoinUnderrun, dryRuns, steerTrace, trims,
        )
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
        assertEquals(790.0, r.planFor().plannedLagMs, 6.0)
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
        // and the restart is planned with the lead alone: the offset is no part of the plan
        assertEquals(r.plannedMs, r.planFor().plannedLagMs, 0.5)
    }

    @Test
    fun theKitchenOffsetAtTheStartIsNoPartOfThePlan() {
        // the tracker still knows an offset (it moves the lag that was locked), but the plan of a stream is the lead and nothing else
        val r = simulate(seconds = 30, offsetMs = 100)
        assertEquals(440.0, r.plannedMs, 0.5)
        assertEquals(440.0, r.lockedMs, 4.0)
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

    // ---- the lag is brought to a target ----------------------------------------------------------------------------

    /** The most the lag fell in any [windowS] seconds of [lags] (ms; negative: it rose), over the readings from [fromS] on. */
    private fun steepestFall(lags: List<Pair<Double, Double>>, windowS: Double, fromS: Double = 0.0): Double {
        var worst = 0.0
        val points = lags.filter { it.first >= fromS }
        var j = 0
        for (i in points.indices) {
            while (points[i].first - points[j].first > windowS) j++
            worst = maxOf(worst, points[j].second - points[i].second)
        }
        return worst
    }

    @Test
    fun aTargetBelowTheLockedLagIsReachedGraduallyWithoutARestart() {
        // locked at 442 ms (lk 290 + lead 150), wanted 300 ms: 142 ms at 3 ms per second
        val r = simulate(seconds = 200, targetMs = 300.0, noiseMs = 3.4, seed = 3)
        assertNull("no restart: ${r.rejoinReason}", r.rejoinAt)
        assertEquals(0, r.relocks)
        assertEquals(442.0, r.lockedMs, 4.0)
        // it gets there (47 s of ramp and a little more for the controller), and stays within the tolerance of the controller
        for (lag in tail(r, 90.0)) assertEquals(300.0, lag, 7.0)
        // never faster than 4.5 ms per second (the trims) and not much slower than the ramp, in any 10 s
        assertTrue("fell ${steepestFall(r.lags, 10.0)} ms in 10 s", steepestFall(r.lags, 10.0) <= 45.0)
        assertTrue("it did fall at about 3 ms per second: ${steepestFall(r.lags, 10.0)}", steepestFall(r.lags, 10.0) >= 20.0)
        // the steer says what it does: descending on the way, holding at the end, no learned floor
        assertTrue(r.steerTrace.any { it.third == LagSteer.State.DESCENDING })
        assertEquals(LagSteer.State.HOLDING, r.steerTrace.last().third)
        assertTrue(r.tracker.steer.learnedFloorMs.isNaN())
        assertEquals(300.0, r.steerTrace.last().second, 0.001)
    }

    @Test
    fun aTargetAboveTheLockedLagIsReachedByAddingFrames() {
        val r = simulate(seconds = 200, targetMs = 520.0, noiseMs = 3.4, seed = 4)
        assertNull(r.rejoinAt)
        assertEquals(0, r.relocks)
        for (lag in tail(r, 90.0)) assertEquals(520.0, lag, 7.0)
        assertTrue(r.steerTrace.any { it.third == LagSteer.State.ASCENDING })
        // up at no more than 4.5 ms per second either
        val rose = steepestFall(r.lags.map { Pair(it.first, -it.second) }, 10.0)
        assertTrue("rose $rose ms in 10 s", rose <= 45.0)
    }

    @Test
    fun withoutATargetTheLagIsHeldWhereItLockedAsBefore() {
        val r = simulate(seconds = 100, noiseMs = 3.4, seed = 5)
        assertNull(r.rejoinAt)
        for (lag in tail(r, 20.0)) assertEquals(r.lockedMs, lag, 7.0)
        assertTrue(r.steerTrace.all { it.third == LagSteer.State.OFF && it.second.isNaN() })
    }

    @Test
    fun theConfiguredFloorIsWhereTheLagStopsWhateverTheTarget() {
        // a target of 300 under a floor of 316
        val r = simulate(seconds = 200, targetMs = 300.0, floorMs = 316.0, noiseMs = 3.4, seed = 3)
        assertNull(r.rejoinAt)
        for (lag in tail(r, 90.0)) assertEquals(316.0, lag, 7.0)
        assertEquals(316.0, r.steerTrace.last().second, 0.001)
        assertTrue(r.tracker.steer.learnedFloorMs.isNaN())
    }

    @Test
    fun aSpeakerThatRunsDryEndsTheStreamAndTheNextOneStopsAtTheNewFloor() {
        // the simulated Sonos runs dry below 330 ms: it stops for 400 ms and fills its buffer again, 400 ms later in the stream.
        // The controller would hide it (a steady lag), a raised setpoint would not cure it: the stream is started again, with a floor.
        val first = simulate(seconds = 300, targetMs = 250.0, floorMs = 200.0, limitLagMs = 330.0, noiseMs = 3.4, seed = 3)
        assertEquals("it ran dry once", 1, first.dryRuns)
        assertTrue("the stream was ended: ${first.rejoinReason}", first.rejoinAt != null)
        assertTrue("as an underrun, not a failed start", first.rejoinUnderrun)
        val steer = first.tracker.steer
        assertEquals(1, steer.floorEvents)
        // 15 ms above the lag where it happened: the limit, and the little by which the readings trail the real lag
        val floor = steer.learnedFloorMs
        assertTrue("floor $floor", floor in 340.0..360.0)
        // the speaker was on its way down from 442 at 3 ms per second when it hit 330
        assertTrue("after about 37 s of descent: ${first.rejoinAt}", first.rejoinAt!! in 30.0..50.0)
        assertEquals("a stall is not a lead the speaker needs", 0, first.tracker.lead.learnedMs)

        // the new stream: the same tracker (the floor stays), a new simulated Sonos with the same limit
        val second = simulate(seconds = 200, targetMs = 250.0, floorMs = 200.0, limitLagMs = 330.0, noiseMs = 3.4, seed = 7, trackerIn = first.tracker)
        assertEquals("the speaker did not run dry again", 0, second.dryRuns)
        assertNull("and the stream was not ended: ${second.rejoinReason}", second.rejoinAt)
        assertEquals(floor, second.tracker.steer.learnedFloorMs, 0.0)
        for (lag in tail(second, 100.0)) assertEquals(floor, lag, 7.0)
        assertEquals(floor, second.steerTrace.last().second, 0.001)
        assertEquals(LagSteer.State.HOLDING, second.steerTrace.last().third)
    }

    @Test
    fun aSpeakerThatSaysNothingIsFoundOutFromTheReadings() {
        val r = simulate(seconds = 300, targetMs = 250.0, floorMs = 200.0, limitLagMs = 330.0, noiseMs = 3.4, seed = 3, tellTheTracker = false)
        assertEquals(1, r.dryRuns)
        assertTrue("the stream was ended: ${r.rejoinReason}", r.rejoinAt != null)
        assertTrue(r.rejoinUnderrun)
        val steer = r.tracker.steer
        assertEquals(1, steer.floorEvents)
        assertTrue("floor ${steer.learnedFloorMs}", steer.learnedFloorMs in 340.0..365.0)
        // found out within about a second of the stall, from the lag that jumped or the position that fell behind
        assertTrue(r.rejoinReason!!.contains("jumped") || r.rejoinReason!!.contains("slower"))
    }

    @Test
    fun aSmallStallIsFoundFromThePositionAloneToo() {
        // 40 ms: the lag rises by 40, which is a jump too; the position falls behind real time by the same
        val r = simulate(seconds = 300, targetMs = 250.0, floorMs = 200.0, limitLagMs = 330.0, limitStallMs = 40.0, noiseMs = 3.4, seed = 5, tellTheTracker = false)
        assertTrue("the stream was ended: ${r.rejoinReason}", r.rejoinAt != null)
        assertTrue(r.rejoinUnderrun)
        assertTrue("floor ${r.tracker.steer.learnedFloorMs}", r.tracker.steer.learnedFloorMs in 340.0..365.0)
    }

    @Test
    fun noiseDoesNotMakeTheSpeakerLookLikeItRunsDry() {
        // seeds and noise well above what the readings have, on the way down and at the target: not one false alarm
        for (seed in 1L..6L) {
            val r = simulate(seconds = 200, targetMs = 300.0, floorMs = 200.0, noiseMs = 5.0, seed = seed)
            assertNull("seed $seed: ${r.rejoinReason}", r.rejoinAt)
            assertEquals("seed $seed", 0, r.tracker.steer.floorEvents)
        }
    }

    @Test
    fun aTargetThatIsChangedLaterIsFollowedAtTheSameSpeed() {
        val r = simulate(seconds = 220, targetMs = 400.0, changeTargetAtS = 50, newTargetMs = 330.0, noiseMs = 3.4, seed = 6)
        assertNull(r.rejoinAt)
        assertEquals(0, r.relocks)
        for (lag in tail(r, 40.0, 50.0)) assertEquals(400.0, lag, 7.0)
        for (lag in tail(r, 100.0)) assertEquals(330.0, lag, 7.0)
        assertTrue("fell ${steepestFall(r.lags, 10.0, 50.0)} ms in 10 s", steepestFall(r.lags, 10.0, 50.0) <= 45.0)
    }

    @Test
    fun theTargetCanBeSwitchedOffAndTheLagIsHeldWhereItIs() {
        // switched off on the way down, at 20 s: the lag is measured again where it is, and held there
        val r = simulate(seconds = 160, targetMs = 300.0, changeTargetAtS = 20, newTargetMs = Double.NaN, noiseMs = 3.4, seed = 8)
        assertNull(r.rejoinAt)
        assertEquals(0, r.relocks)
        val atSwitch = tail(r, 21.0, 24.0).average()
        assertTrue("it was on its way down: $atSwitch", atSwitch in 390.0..430.0)
        for (lag in tail(r, 40.0)) assertEquals(atSwitch, lag, 8.0)
        assertTrue(r.steerTrace.last().third == LagSteer.State.OFF)
        assertTrue(r.steerTrace.last().second.isNaN())
    }

    @Test
    fun theTargetCanBeSwitchedOnLater() {
        val r = simulate(seconds = 200, targetMs = Double.NaN, changeTargetAtS = 30, newTargetMs = 350.0, noiseMs = 3.4, seed = 9)
        assertNull(r.rejoinAt)
        assertEquals(0, r.relocks)
        for (lag in tail(r, 25.0, 30.0)) assertEquals(r.lockedMs, lag, 7.0)
        for (lag in tail(r, 130.0)) assertEquals(350.0, lag, 7.0)
    }

    // ---- Synced mode: the kitchen is held two and a half seconds behind the capture ---------------------------------

    @Test
    fun aLagOfTwoAndAHalfSecondsIsHeldLikeAnyOther() {
        // the Sonos gets the stream 2.3 s of audio ahead of what it plays (the lead of Synced mode), so the stream that is built
        // as it is captured is far ahead of the speaker: nothing about the control may depend on that
        val r = simulate(seconds = 240, leadMs = 2_300, driftPpm = 20.0, noiseMs = 3.4, seed = 11, targetMs = 2_590.0, playAfterS = 5.0)
        assertNull("no restart: ${r.rejoinReason}", r.rejoinAt)
        assertEquals(0, r.relocks)
        assertEquals(2_590.0, r.plannedMs, 0.5)
        assertEquals(2_590.0, r.lockedMs, 5.0)
        for (lag in tail(r, 40.0)) assertEquals(2_590.0, lag, 8.0)
        assertTrue(r.tracker.steer.learnedFloorMs.isNaN())
    }

    @Test
    fun aTargetThatMovesByTensOfMillisecondsInSyncedModeIsFollowedWithoutARestart() {
        // the TV reconnected and its latency moved: +50 ms, later -70 ms (3 ms per second, so about 17 s and 24 s)
        val up = simulate(seconds = 200, leadMs = 2_300, noiseMs = 3.4, seed = 12, targetMs = 2_590.0, changeTargetAtS = 60, newTargetMs = 2_640.0, playAfterS = 5.0)
        assertNull("no restart: ${up.rejoinReason}", up.rejoinAt)
        assertEquals(0, up.relocks)
        for (lag in tail(up, 40.0, 60.0)) assertEquals(2_590.0, lag, 8.0)
        for (lag in tail(up, 100.0)) assertEquals(2_640.0, lag, 8.0)
        val down = simulate(seconds = 200, leadMs = 2_300, noiseMs = 3.4, seed = 13, targetMs = 2_590.0, changeTargetAtS = 60, newTargetMs = 2_520.0, playAfterS = 5.0)
        assertNull("no restart: ${down.rejoinReason}", down.rejoinAt)
        assertEquals(0, down.relocks)
        for (lag in tail(down, 110.0)) assertEquals(2_520.0, lag, 8.0)
        assertTrue("it went at the pace of the ramp: ${steepestFall(down.lags, 10.0, 60.0)}", steepestFall(down.lags, 10.0, 60.0) <= 45.0)
    }

    // ---- Synced mode: the stream is set once and then left alone ---------------------------------

    @Test
    fun aSyncedStreamThatLandsWhereItWasPlannedIsLeftAloneForTenMinutes() {
        // a lead of 1850 ms and Play-to-sound 290: planned 2140 ms. The Sonos is 22 ppm slow and the readings are 3.4 ms off
        val r = simulate(seconds = 600, leadMs = 1850, playAfterS = 4.0, driftPpm = 22.0, noiseMs = 3.4, seed = 5, syncedTargetMs = 2140.0)
        assertNull(r.rejoinAt)
        assertEquals(2140.0, r.plannedMs, 0.5)
        assertEquals("nothing is trimmed: ${r.trims}", 0, r.trims.size)
        assertEquals(0, r.relocks)
        // the drift of 22 ppm moves the lag by 13 ms in ten minutes, which is inside the band and left alone
        for (lag in tail(r, 20.0)) assertEquals(2140.0, lag, 20.0)
    }

    @Test
    fun aSyncedStreamThatLandsLateIsTrimmedOnceToTheTargetAndThenLeftAlone() {
        // the speaker started 130 ms later than planned (342 ms for 217 on the device)
        val r = simulate(seconds = 300, leadMs = 1850, playAfterS = 4.0, extraStartMs = 130.0, noiseMs = 3.0, seed = 3, syncedTargetMs = 2140.0)
        assertNull(r.rejoinAt)
        assertEquals(2270.0, r.lockedMs, 6.0)
        assertEquals("one trim, to place it: ${r.trims}", 1, r.trims.size)
        assertEquals(130.0 * 44.1, r.trims[0].second.toDouble(), 400.0)
        assertTrue("right after the lock: ${r.trims[0].first}", r.trims[0].first < 10.0)
        // it is there after the trim has landed (0.45 % of the stream: half a minute), and stays
        for (lag in tail(r, 70.0)) assertEquals(2140.0, lag, 10.0)
    }

    @Test
    fun aSyncedStreamThatLandsEarlyIsStretchedOnceToTheTarget() {
        val r = simulate(seconds = 200, leadMs = 1850, playAfterS = 4.0, extraStartMs = -60.0, noiseMs = 2.0, seed = 4, syncedTargetMs = 2140.0)
        assertNull(r.rejoinAt)
        assertEquals(1, r.trims.size)
        assertTrue("frames are repeated: ${r.trims}", r.trims[0].second < 0)
        for (lag in tail(r, 70.0)) assertEquals(2140.0, lag, 10.0)
    }

    @Test
    fun aNewSyncedTargetIsPlacedAtOnce() {
        // the TV reconnected with another backlog (or the kitchen offset changed): the common delay is 30 ms more from 60 s on
        val r = simulate(seconds = 200, leadMs = 1850, playAfterS = 4.0, noiseMs = 2.0, seed = 6, syncedTargetMs = 2140.0, changeTargetAtS = 60, newTargetMs = 2170.0)
        assertNull(r.rejoinAt)
        val after = r.trims.filter { it.first >= 60.0 }
        assertEquals("one trim for the new target: ${r.trims}", 1, after.size)
        assertTrue("it comes at once: ${after[0].first}", after[0].first < 62.0)
        assertEquals(-30.0 * 44.1, after[0].second.toDouble(), 200.0)
        for (lag in tail(r, 120.0)) assertEquals(2170.0, lag, 10.0)
    }

    @Test
    fun aStepOfTheSyncedLagBeyondTheBandIsCorrectedOnceAfterThirtySeconds() {
        // the speaker stops for 120 ms at 100 s and fills its buffer again: the lag is 120 ms more for good
        val r = simulate(seconds = 300, leadMs = 1850, playAfterS = 4.0, noiseMs = 2.0, seed = 7, syncedTargetMs = 2140.0, stalls = listOf(Stall(100.0, 120.0)))
        assertNull(r.rejoinAt)
        assertEquals(1, r.trims.size)
        // 30 s beyond the band (the first reading after the stall, about 100.2 s, plus 30 s)
        assertTrue("the correction came at ${r.trims[0].first}", r.trims[0].first in 129.0..134.0)
        assertEquals(120.0 * 44.1, r.trims[0].second.toDouble(), 500.0)
        for (lag in tail(r, 100.0, 125.0)) assertEquals(2260.0, lag, 10.0)
        for (lag in tail(r, 180.0)) assertEquals(2140.0, lag, 10.0)
    }

    @Test
    fun aStepOfTheSyncedLagThatIsTooBigForATrimStartsTheStreamAgainAfterThirtySeconds() {
        val r = simulate(seconds = 300, leadMs = 1850, playAfterS = 4.0, noiseMs = 2.0, seed = 8, syncedTargetMs = 2140.0, stalls = listOf(Stall(100.0, 700.0)))
        assertTrue("re-joined", r.rejoinAt != null)
        assertTrue("after 30 s beyond the band, not at once: ${r.rejoinAt}", r.rejoinAt!! in 129.0..134.0)
        assertTrue(r.rejoinReason!!, r.rejoinReason.contains("more than a trim is for"))
        assertEquals(0, r.relocks)
    }

    @Test
    fun aSyncedStreamNeverLearnsAFloorOrSteers() {
        val r = simulate(seconds = 120, leadMs = 1850, playAfterS = 4.0, noiseMs = 12.0, seed = 11, syncedTargetMs = 2140.0)
        assertTrue(r.tracker.steer.learnedFloorMs.isNaN())
        assertEquals(0, r.tracker.steer.floorEvents)
        assertTrue(r.tracker.steer.setpointMs.isNaN())
    }

    @Test
    fun aStepOfTheCaptureClockMovesASyncedKitchenThatCountsFromItButNotOneThatCountsFromTheTvsClock() {
        // the timestamps of the capture step by 46.8 ms at 50 s (as on the device) while the Sonos plays steadily
        val tvClock = object : FrameTimes {
            override fun nanosOfFrame(frame: Long): Long? = tOf(frame)
        }
        val steady = simulate(
            seconds = 200, leadMs = 1850, playAfterS = 4.0, noiseMs = 2.0, seed = 21, syncedTargetMs = 2140.0,
            clockStepAtS = 50.0, clockStepMs = 46.8, syncedClock = tvClock,
        )
        assertNull(steady.rejoinAt)
        assertEquals("nothing is trimmed: ${steady.trims}", 0, steady.trims.size)
        for (lag in tail(steady, 20.0)) assertEquals(2140.0, lag, 10.0)

        // counted from the capture's own clock the same stream is moved by the step, 30 s after it, and the real kitchen with it
        val followed = simulate(
            seconds = 200, leadMs = 1850, playAfterS = 4.0, noiseMs = 2.0, seed = 21, syncedTargetMs = 2140.0,
            clockStepAtS = 50.0, clockStepMs = 46.8,
        )
        assertEquals("the step is followed: ${followed.trims}", 1, followed.trims.size)
        assertTrue("after the step and 30 s: ${followed.trims[0].first}", followed.trims[0].first in 80.0..86.0)
        assertEquals(-46.8 * 44.1, followed.trims[0].second.toDouble(), 250.0)
        for (lag in tail(followed, 130.0)) assertEquals(2140.0 + 46.8, lag, 10.0)
    }

    @Test
    fun aSyncedStreamThatStartsMidStreamAfterTheSpeakerWasSwitchedOffAndOnLandsAtTheSameDelayOfTheSameClock() {
        // the first stream (started 4 s into the capture) and the one after a power cycle of the speaker (40 s into it, the TV's stream running on)
        // are both counted from the TV's clock and both land at the common delay: nothing in the rejoin depends on how long the capture has run,
        // on the ring having wrapped, or on anything that happened to the speaker before
        val tvClock = object : FrameTimes {
            override fun nanosOfFrame(frame: Long): Long? = tOf(frame)
        }
        for (playAfter in listOf(4.0, 40.0, 95.0)) {
            val r = simulate(
                seconds = 90, leadMs = 2200, playAfterS = playAfter, noiseMs = 2.0, seed = 31, syncedTargetMs = 2490.0, syncedClock = tvClock,
                extraStartMs = 25.0,
            )
            assertNull("play after $playAfter s: ${r.rejoinReason}", r.rejoinAt)
            assertEquals("planned", 2490.0, r.plannedMs, 0.5)
            assertEquals("play after $playAfter s locked at ${r.lockedMs}", 2515.0, r.lockedMs, 6.0)
            // placed once (25 ms is more than the leave-alone band of the placing), then at the common delay for the rest of the time
            assertEquals("play after $playAfter s: ${r.trims}", 1, r.trims.size)
            for (lag in tail(r, 60.0)) assertEquals("play after $playAfter s", 2490.0, lag, 8.0)
        }
    }

    @Test
    fun aSyncedRejoinWhosePlayWasRefusedWith701AndTriedAgainStillLandsAtTheCommonDelayAndNeverAtTheLyricsLag() {
        // After a power cycle the speaker may answer the first Play with 701 (the transition is not available): the Play goes out 60 ms later,
        // twice at most, and when it does not take, the whole start is made again some seconds later. Whatever the delay of the sound against
        // the plan (a retry of 120 ms, a start made again that is a second and more late), the stream that is counted from the TV's clock is
        // brought to the common delay once and left there: it is not a Lyrics-mode stream that is steered down to the lag of 322 ms (that is
        // the lag the kitchen showed 1.85 s ahead of the TV, 2171 - 322)
        val tvClock = object : FrameTimes {
            override fun nanosOfFrame(frame: Long): Long? = tOf(frame)
        }
        for (late in listOf(60.0, 120.0, 240.0, 1850.0)) {
            val r = simulate(
                seconds = 120, leadMs = 1900, playAfterS = 40.0, noiseMs = 2.0, seed = 41, syncedTargetMs = 2171.0, syncedClock = tvClock,
                extraStartMs = late,
            )
            assertEquals("planned", 2171.0, r.plannedMs, 40.0)
            for (lag in tail(r, 70.0)) assertEquals("Play $late ms late", 2171.0, lag, 10.0)
            assertTrue("Play $late ms late: never near the Lyrics lag", r.lags.none { it.first > 5.0 && it.second < 1000.0 })
            assertFalse("Play $late ms late: the speaker was not steered like a Lyrics stream", r.tracker.steer.hasSetpoint)
        }
    }
}
