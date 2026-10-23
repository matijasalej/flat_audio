package io.github.matijasalej.wholeflat.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * The whole feedback loop with the real parts: the [StreamBuilder] building blocks from a ring that
 * always has frames, the [KitchenMap] it fills, the [StreamView] and the [SyncController]. Only the
 * Sonos is simulated: it plays stream frame s at `start + s / rate` (a little slower when it drifts),
 * the stream is sent block by block `lead` ahead of that, and its position is reported 156 ms ahead
 * of the sound, with noise.
 */
class KitchenLoopTest {
    private val rate = 44_100
    private val firstRing = 100_000L

    /** A ring that has every frame; the content is the frame number, so a repeated or dropped frame shows. */
    private class FullRing : FrameSource {
        override fun read(cursor: Long, out: ShortArray, maxFrames: Int, timeoutMs: Int): Int {
            for (i in 0 until maxFrames) {
                out[i * 2] = (cursor + i).toShort()
                out[i * 2 + 1] = (cursor + i).toShort()
            }
            return maxFrames
        }
    }

    private class Run(
        val errors: List<Pair<Double, Double>>,
        val rejoinAt: Double?,
        val controller: SyncController,
        val map: KitchenMap,
    )

    private fun simulate(
        e0Ms: Double,
        seconds: Int,
        driftPpm: Double = 0.0,
        noiseMs: Double = 0.0,
        offsetMs: Double = 0.0,
        seed: Long = 1,
        leadSeconds: Double = 1.5,
    ): Run {
        val map = KitchenMap(firstRing)
        val builder = StreamBuilder(FullRing(), map)
        val controller = SyncController()
        controller.offsetMs = offsetMs
        val living: (Long) -> Long? = { f -> (f.toDouble() / rate * 1e9).toLong() }
        val view = StreamView(map, { builder.unappliedBudget() }, living)
        val rnd = Random(seed)
        // the Sonos plays stream frame 0 e0 (+ offset) after the living room plays ring frame firstRing
        val start = (firstRing.toDouble() / rate * 1e9 + (e0Ms + offsetMs) * 1e6).toLong()
        val speed = 1.0 - driftPpm * 1e-6
        val leadFrames = (leadSeconds * rate).toLong()

        fun heardFrame(t: Long): Double = Math.max(0.0, (t - start) / 1e9 * rate * speed)

        // like the HTTP route: a block is built, then sent when the Sonos is within `lead` of its end
        var sent = 0L
        builder.nextBlock(0)
        fun due(block: Long): Long = start + (((block + 1) * Flac.BLOCK - leadFrames) / rate.toDouble() * 1e9 / speed).toLong()

        val errors = ArrayList<Pair<Double, Double>>()
        var rejoinAt: Double? = null
        var t = start - 500_000_000L
        val end = start + seconds * 1_000_000_000L
        while (t < end && rejoinAt == null) {
            while (due(sent) <= t) {
                sent++
                builder.nextBlock(0)
            }
            if (t > start + 600_000_000L) {
                val position = Math.round(heardFrame(t) / rate * 1000.0 + 156.0 + rnd.nextGaussian() * noiseMs)
                val at = (t - start) / 1e9
                when (val d = controller.onReading(t, position, view)) {
                    is SyncController.Decision.Trim -> builder.addTrim(d.frames)
                    is SyncController.Decision.Rejoin -> rejoinAt = at
                    SyncController.Decision.Hold -> Unit
                }
                // the real difference between the rooms for the frame the Sonos plays now
                val ring = map.ringOfStream(heardFrame(t))!!
                errors.add(Pair(at, (t - living(Math.round(ring))!!) / 1e6 - offsetMs))
            }
            t += 200_000_000L
        }
        return Run(errors, rejoinAt, controller, map)
    }

    private fun tail(run: Run, from: Double) = run.errors.filter { it.first >= from }.map { it.second }

    @Test
    fun aLateKitchenIsPulledInByTheRealBuilder() {
        val run = simulate(e0Ms = 22.0, seconds = 80)
        assertNull(run.rejoinAt)
        for (e in tail(run, 50.0)) assertEquals(0.0, e, 4.5)
        assertTrue("it made trims: ${run.map.checkpoints()}", run.map.checkpoints() > 1)
    }

    @Test
    fun anEarlyKitchenIsPulledInToo() {
        val run = simulate(e0Ms = -18.0, seconds = 80)
        assertNull(run.rejoinAt)
        for (e in tail(run, 50.0)) assertEquals(0.0, e, 4.5)
    }

    @Test
    fun theOffsetMovesTheKitchenAndNotTheLivingRoom() {
        // the kitchen should be 30 ms late; it starts 8 ms off that
        val run = simulate(e0Ms = 8.0, seconds = 80, offsetMs = 30.0)
        assertNull(run.rejoinAt)
        for (e in tail(run, 50.0)) assertEquals(0.0, e, 4.5)
    }

    @Test
    fun driftAndNoiseStayWithinTenMilliseconds() {
        // the Sonos is 22 ppm slow, the readings are 3.4 ms off (as measured), for ten minutes
        val run = simulate(e0Ms = 0.0, seconds = 600, driftPpm = 22.0, noiseMs = 3.4, seed = 5)
        assertNull(run.rejoinAt)
        val settled = tail(run, 120.0)
        val worst = settled.maxOf { Math.abs(it) }
        assertTrue("worst error $worst ms", worst < 10.0)
        val drift = run.controller.driftPpm
        assertEquals(22.0, drift, 10.0)
    }

    @Test
    fun aHugeErrorIsARejoinNotATrim() {
        val run = simulate(e0Ms = 180.0, seconds = 30)
        assertTrue(run.rejoinAt != null)
        assertTrue("it decided within a few seconds: ${run.rejoinAt}", run.rejoinAt!! < 5.0)
    }
}
