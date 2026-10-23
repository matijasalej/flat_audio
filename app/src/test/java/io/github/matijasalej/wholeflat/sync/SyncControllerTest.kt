package io.github.matijasalej.wholeflat.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * The controller against a simulated kitchen: the living room plays ring frame f at f / 44100 s; the
 * Sonos is fed a stream that runs `lead` seconds ahead of what it plays, built with trims at most
 * every 441 frames, and answers with its position (156 ms ahead of the sound, with noise).
 */
class SyncControllerTest {
    private val rate = 44_100
    private val nanos = 1_000_000_000L

    private class Plant(
        e0Ms: Double,
        driftPpm: Double = 0.0,
        private val leadSeconds: Double = 1.0,
        private val noiseMs: Double = 0.0,
        seed: Long = 1,
        private val deltaMs: Double = 156.0,
    ) : SyncController.View {
        val rate = 44_100
        val firstRing = 10_000L
        val map = KitchenMap(firstRing)

        /** When the Sonos plays stream frame 0: the living room plays that frame at firstRing / rate. */
        val kitchenStartNanos = (firstRing.toDouble() / rate * 1e9 + e0Ms * 1e6).toLong()
        private val speed = 1.0 - driftPpm * 1e-6
        private val rnd = Random(seed)
        var budget = 0
        private var emitted = 0L
        private var cursor = firstRing
        private var sinceTrim = 441

        fun heardStream(tNanos: Long): Double = Math.max(0.0, (tNanos - kitchenStartNanos) / 1e9 * rate * speed)

        /** The stream builder, run up to what the Sonos needs at time `tNanos`. */
        fun step(tNanos: Long) {
            val target = heardStream(tNanos) + leadSeconds * rate
            while (emitted + 441 <= target) {
                if (sinceTrim >= 441 && budget != 0) {
                    if (budget > 0) {
                        cursor += 1
                        budget -= 1
                        map.addCheckpoint(emitted, cursor)
                    } else {
                        map.addCheckpoint(emitted, cursor - 1)
                        emitted += 1
                        budget += 1
                    }
                    sinceTrim = 0
                }
                emitted += 441
                cursor += 441
                sinceTrim += 441
                map.setStreamEnd(emitted)
            }
        }

        fun reading(tNanos: Long): Long =
            Math.round(heardStream(tNanos) / rate * 1000.0 + deltaMs + rnd.nextGaussian() * noiseMs)

        /** The true difference between the rooms for the frame the kitchen plays at `tNanos` (ms). */
        fun physicalErrorMs(tNanos: Long): Double {
            val ring = map.ringOfStream(heardStream(tNanos))!!
            return (tNanos - livingHalNanos(ring)!!) / 1e6
        }

        override fun ringOfStream(streamFrame: Double): Double? =
            if (streamFrame < 0 || streamFrame > emitted + 1) null else map.ringOfStream(streamFrame)

        override fun livingHalNanos(ringFrame: Double): Long? = (ringFrame / rate * 1e9).toLong()

        override fun netDropsAt(streamFrame: Double): Double = map.netDropsAt(streamFrame)

        override fun pendingTrimFrames(streamFrame: Double): Long =
            Math.round(map.netDropsAt(emitted.toDouble()) - map.netDropsAt(streamFrame)) + budget
    }

    private class Outcome(
        val trims: List<Pair<Double, Int>>,
        val rejoinAt: Double?,
        val errors: List<Pair<Double, Double>>,
    )

    /** Runs readings at 5 Hz for `seconds` after the Sonos started; the controller's trims are fed back. */
    private fun run(plant: Plant, controller: SyncController, seconds: Int): Outcome {
        val trims = ArrayList<Pair<Double, Int>>()
        val errors = ArrayList<Pair<Double, Double>>()
        var rejoinAt: Double? = null
        var t = plant.kitchenStartNanos
        val end = t + seconds * nanos
        while (t < end && rejoinAt == null) {
            plant.step(t)
            if (t > plant.kitchenStartNanos + 600_000_000L) {
                val at = (t - plant.kitchenStartNanos) / 1e9
                when (val d = controller.onReading(t, plant.reading(t), plant)) {
                    is SyncController.Decision.Trim -> {
                        plant.budget += d.frames
                        trims.add(Pair(at, d.frames))
                    }
                    is SyncController.Decision.Rejoin -> rejoinAt = at
                    SyncController.Decision.Hold -> Unit
                }
                errors.add(Pair(at, plant.physicalErrorMs(t) - controller.offsetMs))
            }
            t += 200_000_000L
        }
        return Outcome(trims, rejoinAt, errors)
    }

    private fun tail(outcome: Outcome, fromSeconds: Double) = outcome.errors.filter { it.first >= fromSeconds }.map { it.second }

    @Test
    fun aLateKitchenIsTrimmedBackWithoutARejoin() {
        val controller = SyncController()
        val outcome = run(Plant(e0Ms = 20.0), controller, 60)
        assertNull(outcome.rejoinAt)
        assertTrue("the first trim drops frames", outcome.trims.first().second > 0)
        assertTrue("it acts after the first window of readings", outcome.trims.first().first in 2.0..4.0)
        // each trim removes half of what is left, down to the deadband (4 ms): 441 + 221 + 110 frames
        for (e in tail(outcome, 40.0)) assertEquals(0.0, e, 4.5)
        assertTrue("${controller.trimmedFrames} frames", controller.trimmedFrames in 700L..850L)
    }

    @Test
    fun anEarlyKitchenGetsFramesRepeated() {
        val controller = SyncController()
        val outcome = run(Plant(e0Ms = -15.0), controller, 60)
        assertNull(outcome.rejoinAt)
        assertTrue("the first trim repeats frames", outcome.trims.first().second < 0)
        for (e in tail(outcome, 40.0)) assertEquals(0.0, e, 4.5)
    }

    @Test
    fun anErrorWithinTheDeadbandIsLeftAlone() {
        val controller = SyncController()
        val outcome = run(Plant(e0Ms = 2.0), controller, 30)
        assertTrue("no trims for 2 ms", outcome.trims.isEmpty())
        assertNull(outcome.rejoinAt)
    }

    @Test
    fun aBigErrorReJoinsAtOnce() {
        val controller = SyncController()
        val outcome = run(Plant(e0Ms = 80.0), controller, 60)
        assertTrue("re-joined within 2 s of the first reading", outcome.rejoinAt != null && outcome.rejoinAt!! < 2.0)
        assertTrue(outcome.trims.isEmpty())
        assertEquals(1, controller.rejoins)
    }

    @Test
    fun anEarlyKitchenThatIsFarOffReJoinsToo() {
        val controller = SyncController()
        val outcome = run(Plant(e0Ms = -300.0), controller, 60)
        assertTrue(outcome.rejoinAt != null && outcome.rejoinAt!! < 2.0)
    }

    @Test
    fun whenReJoiningIsNotWantedABigErrorIsTrimmedInstead() {
        val controller = SyncController()
        controller.allowRejoin = false
        val outcome = run(Plant(e0Ms = 80.0), controller, 90)
        assertNull(outcome.rejoinAt)
        assertTrue(outcome.trims.isNotEmpty())
        assertTrue("never more than 50 ms on their way", outcome.trims.all { it.second <= 2205 })
        for (e in tail(outcome, 80.0)) assertEquals(0.0, e, 4.5)
    }

    @Test
    fun noiseAndDriftDoNotMakeItChaseTheReadings() {
        for (seed in 1L..3L) {
            val controller = SyncController()
            val outcome = run(Plant(e0Ms = 0.0, driftPpm = 22.0, noiseMs = 3.4, seed = seed), controller, 600)
            assertNull("seed $seed", outcome.rejoinAt)
            val settled = tail(outcome, 60.0)
            assertTrue("seed $seed: max error ${settled.maxOf { Math.abs(it) }}", settled.all { Math.abs(it) < 6.0 })
            // the drift needs about 580 frames of correction in ten minutes; chasing the noise would take thousands
            assertTrue("seed $seed: ${controller.trimmedFrames} frames trimmed", controller.trimmedFrames < 3_000)
            assertEquals("seed $seed: drift", 22.0, controller.driftPpm, 10.0)
        }
    }

    @Test
    fun theOffsetIsTheTargetNotZero() {
        val controller = SyncController()
        controller.offsetMs = 30.0
        val outcome = run(Plant(e0Ms = 5.0), controller, 90)
        assertNull(outcome.rejoinAt)
        // errors are reported relative to the target: the kitchen ends up 30 ms behind the living room
        for (e in tail(outcome, 60.0)) assertEquals(0.0, e, 4.5)
    }

    @Test
    fun aTrimIsNotAskedForTwiceWhileItsEffectIsOnTheWay() {
        val controller = SyncController()
        val outcome = run(Plant(e0Ms = 35.0), controller, 60)
        assertNull(outcome.rejoinAt)
        val times = outcome.trims.map { it.first }
        for (i in 1 until times.size) {
            assertTrue("trims at ${times[i - 1]} and ${times[i]}", times[i] - times[i - 1] >= 2.0)
        }
        // the sum of what it asked for is about the error, not several times that
        val total = outcome.trims.sumOf { it.second }
        assertTrue("asked for $total frames for a 35 ms error", total in 1_400..1_700)
    }

    @Test
    fun readingsWithoutAMappingAreIgnored() {
        val controller = SyncController()
        val nothing = object : SyncController.View {
            override fun ringOfStream(streamFrame: Double): Double? = null
            override fun livingHalNanos(ringFrame: Double): Long? = 0L
            override fun netDropsAt(streamFrame: Double): Double = 0.0
            override fun pendingTrimFrames(streamFrame: Double): Long = 0L
        }
        for (i in 0 until 50) {
            assertTrue(controller.onReading(i * 200_000_000L, 5_000L, nothing) === SyncController.Decision.Hold)
        }
        assertEquals(0, controller.readings)
        val noLiving = object : SyncController.View {
            override fun ringOfStream(streamFrame: Double): Double? = 1.0
            override fun livingHalNanos(ringFrame: Double): Long? = null
            override fun netDropsAt(streamFrame: Double): Double = 0.0
            override fun pendingTrimFrames(streamFrame: Double): Long = 0L
        }
        assertTrue(controller.onReading(0, 5_000L, noLiving) === SyncController.Decision.Hold)
        assertEquals(0, controller.readings)
    }

    @Test
    fun resetForgetsEverythingAboutTheSession() {
        val controller = SyncController()
        run(Plant(e0Ms = 20.0), controller, 20)
        assertTrue(controller.readings > 0)
        controller.reset()
        assertEquals(0, controller.readings)
        assertTrue(controller.errorMs.isNaN())
        assertTrue(controller.driftPpm.isNaN())
    }
}
