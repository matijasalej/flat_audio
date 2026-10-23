package io.github.matijasalej.wholeflat.capture

import io.github.matijasalej.wholeflat.KitchenStreams
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
            if (changeTargetAtS >= 0 && t - tPlay >= changeTargetAtS * 1_000_000_000L) target = newTargetMs

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
                    val outcome = tracker.onReading(t, position, map, { builder.unappliedBudget() }, clock, offset, lkMs, target, floorMs)
                    when (outcome) {
                        is CaptureTracker.Outcome.Locked -> {
                            if (lockedAt == null) {
                                lockedAt = (t - tPlay) / 1e9
                                firstLock = outcome.lagMs
                            }
                        }
                        is CaptureTracker.Outcome.Trim -> builder.addTrim(outcome.frames)
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
                    lags.add(Pair((t - tPlay) / 1e9, (t - clock.nanosOfFrame(Math.round(trueFrame))!!) / 1e6))
                }
            }
            t += 20_000_000L
        }
        return Result(
            rejoinAt, rejoinReason, lockedAt, firstLock, tracker.lag.locked ?: Double.NaN, plan.plannedLagMs,
            tracker.relocks, lags, minMargin, tracker, { planWith() }, rejoinUnderrun, dryRuns, steerTrace,
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
}
