package io.github.matijasalej.wholeflat.capture

import io.github.matijasalej.wholeflat.sync.KitchenMap
import io.github.matijasalej.wholeflat.sync.StreamBuilder
import io.github.matijasalej.wholeflat.sync.StreamView
import io.github.matijasalej.wholeflat.sync.SyncController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * The capture mode's loop with the real parts: the buffer and clock of the capture, the start planner, the
 * stream builder reading the buffer, the lag lock and the controller. Only the Sonos is simulated: it plays
 * stream frame s some time after Play (as late as the test says), a little slower when it drifts, and
 * reports its position 156 ms ahead of the sound, with noise. The capture runs in real time: a frame
 * exists from the moment it is captured, not before.
 */
class CaptureLoopTest {
    private val rate = 48_000
    private val t0 = 1_000_000_000_000L
    private val chunk = 960

    private class Result(
        val rejoinAt: Double?,
        val lockedAt: Double?,
        val lockedMs: Double,
        val plannedMs: Double,
        /** (time since the start of the sound, true lag in ms) */
        val lags: List<Pair<Double, Double>>,
        val minMarginMs: Double,
        val lag: CaptureLag,
        val controller: SyncController,
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

        // one second of silence-or-music before the player is noticed; Play goes out 230 ms later
        val tPlay = t0 + 1_230_000_000L
        capture(framesAt(tPlay))
        val tCmd = tPlay
        val lag = CaptureLag()
        var offset = offsetMs
        val plan = CaptureStart.plan(
            nowFrame = framesAt(tCmd), writeIndex = written, validStart = buffer.validStart, rate = rate,
            leadMs = leadMs, offsetMs = offset, lkMs = lkMs,
        )
        val map = KitchenMap(plan.startFrame)
        val builder = StreamBuilder(CaptureFrames(buffer, 1), map, StreamBuilder.ChannelMode.STEREO, trimSpacing = rate / 100)
        val controller = SyncController(
            SyncController.Params(rejoinThresholdMs = 60.0, rejoinReadings = 5, deadbandMs = 8.0, maxPendingFrames = 2_400, rate = rate),
        )
        val start = tCmd + ((lkMs + extraStartMs) * 1e6).toLong()
        val speed = 1.0 - driftPpm * 1e-6
        fun heardFrame(t: Long): Double = Math.max(0.0, (t - start) / 1e9 * rate * speed)
        fun builtBlocks(): Long = builder.streamFrames / 4096

        var rejoinAt: Double? = null
        var lockedAt: Double? = null
        var minMargin = Double.MAX_VALUE
        val lags = ArrayList<Pair<Double, Double>>()
        var blocksSeen = 0L
        // the time at which each block was complete, to see whether the Sonos had it in time
        val builtAt = ArrayList<Long>()
        var t = tPlay
        val end = tPlay + seconds * 1_000_000_000L
        var nextReading = start + 600_000_000L
        while (t < end && rejoinAt == null) {
            capture(framesAt(t))
            while (builder.nextBlock(0) == 4096) builtAt.add(t)
            // the Sonos needs block k at start + k * 4096 / rate (stream time); margin is how early it was there
            val playing = heardFrame(t)
            val k = (playing / 4096).toInt()
            for (b in blocksSeen.toInt() until builtAt.size) {
                val due = start + (b * 4096L * 1e9 / rate / speed).toLong()
                minMargin = Math.min(minMargin, (due - builtAt[b]) / 1e6)
            }
            blocksSeen = builtAt.size.toLong()
            assertTrue("the Sonos ran ahead of the stream at block $k of ${builtAt.size}", k <= builtAt.size)

            if (changeOffsetAtS >= 0 && t - tPlay >= changeOffsetAtS * 1_000_000_000L && offset != newOffsetMs) offset = newOffsetMs

            if (t >= nextReading) {
                nextReading += 200_000_000L
                val position = Math.round(heardFrame(t) / rate * 1000.0 + 156.0 + rnd.nextGaussian() * noiseMs)
                val heard = (position - controller.deltaMs) / 1000.0 * rate
                val frame = map.ringOfStream(heard)!!
                val captured = clock.nanosOfFrame(Math.round(frame))!!
                val lagMs = (t - captured) / 1e6
                if (!lag.isLocked) {
                    if (lag.onReading(lagMs, offset)) {
                        lockedAt = (t - tPlay) / 1e9
                        controller.reset()
                        controller.offsetMs = lag.controllerOffsetMs(offset)
                    }
                } else {
                    lag.onReading(lagMs, offset)
                    controller.offsetMs = lag.controllerOffsetMs(offset)
                    val base = lag.baseNanos()
                    val view = StreamView(map, { builder.unappliedBudget() }, { f -> clock.nanosOfFrame(f)?.plus(base) })
                    when (val d = controller.onReading(t, position, view)) {
                        is SyncController.Decision.Trim -> builder.addTrim(d.frames)
                        is SyncController.Decision.Rejoin -> rejoinAt = (t - tPlay) / 1e9
                        is SyncController.Decision.Hold -> Unit
                    }
                }
                // the real lag of the frame the Sonos plays now
                val trueFrame = map.ringOfStream(heardFrame(t))!!
                lags.add(Pair((t - tPlay) / 1e9, (t - clock.nanosOfFrame(Math.round(trueFrame))!!) / 1e6))
            }
            t += 20_000_000L
        }
        return Result(rejoinAt, lockedAt, lag.locked ?: Double.NaN, plan.plannedLagMs, lags, minMargin, lag, controller)
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
    }

    @Test
    fun theSonosHasTheAudioWellBeforeItPlaysIt() {
        val r = simulate(seconds = 20)
        assertTrue("the least margin was ${r.minMarginMs} ms", r.minMarginMs > 200.0)
    }

    @Test
    fun aSonosThatStartsLateIsMeasuredNotFought() {
        val r = simulate(seconds = 40, extraStartMs = 350.0)
        assertNull("no re-join", r.rejoinAt)
        assertEquals(r.plannedMs + 350.0, r.lockedMs, 4.0)
        for (lag in tail(r, 10.0)) assertEquals(r.lockedMs, lag, 6.0)
    }

    @Test
    fun driftAndNoiseDoNotMoveTheLagByMoreThanTenMilliseconds() {
        // the Sonos is 22 ppm slow, the readings are 3.4 ms off, for ten minutes
        val r = simulate(seconds = 600, driftPpm = 22.0, noiseMs = 3.4, seed = 5)
        assertNull(r.rejoinAt)
        val settled = tail(r, 60.0)
        val worst = settled.maxOf { Math.abs(it - r.lockedMs) }
        assertTrue("worst deviation $worst ms from ${r.lockedMs}", worst < 10.0)
        assertEquals(22.0, r.controller.driftPpm, 10.0)
    }

    @Test
    fun aLaterKitchenOffsetMovesTheLagByThatMuch() {
        val r = simulate(seconds = 160, changeOffsetAtS = 20, newOffsetMs = 30)
        assertNull(r.rejoinAt)
        for (lag in tail(r, 100.0)) assertEquals(r.lockedMs + 30.0, lag, 10.0)
    }

    @Test
    fun anEarlierKitchenOffsetShortensTheLag() {
        val r = simulate(seconds = 160, changeOffsetAtS = 20, newOffsetMs = -30)
        assertNull(r.rejoinAt)
        for (lag in tail(r, 100.0)) assertEquals(r.lockedMs - 30.0, lag, 10.0)
    }

    @Test
    fun aBigJumpOfTheOffsetRestartsTheStreamAtTheNewLag() {
        // trims move 2 ms per second: 80 ms in one go is a re-join, as in the ring mode
        val r = simulate(seconds = 60, changeOffsetAtS = 20, newOffsetMs = 80)
        assertTrue("re-joined", r.rejoinAt != null)
        assertTrue("within a few seconds of the change: ${r.rejoinAt}", r.rejoinAt!! in 20.0..26.0)
    }

    @Test
    fun theKitchenOffsetAtTheStartIsPartOfThePlan() {
        val r = simulate(seconds = 30, offsetMs = 100)
        assertEquals(540.0, r.plannedMs, 0.5)
        assertEquals(540.0, r.lockedMs, 4.0)
        assertNull(r.rejoinAt)
    }
}
