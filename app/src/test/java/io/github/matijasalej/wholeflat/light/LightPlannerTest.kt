package io.github.matijasalej.wholeflat.light

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LightPlannerTest {
    private val rate = 44100
    private val blue = Colors.hsv(220.0, 0.8, 0.9)
    private val orange = Colors.hsv(30.0, 0.8, 0.9)
    private val green = Colors.hsv(130.0, 0.8, 0.9)
    private val violet = Colors.hsv(280.0, 0.8, 0.9)

    private fun look(cls: LightClass, home: Int = blue, key: String = "song") = SongLook(key, cls, home, orange, intArrayOf(home, orange, green, violet), "test")

    private fun analysis(
        bpm: Double = 120.0,
        locked: Boolean = true,
        clarity: Double = 0.8,
        energy: Double = 0.6,
        loudDb: Double = -12.0,
        silent: Boolean = false,
        beatFrame: Double = 0.0,
        sections: List<Analysis.Section> = emptyList(),
    ) = Analysis(0, 0, bpm, 60.0 / bpm * rate, beatFrame, locked, clarity, energy, energy, 0.5, 0.0, loudDb, silent, sections)

    /** The frames of [seconds] at 30 a second (the picture's rate) from [fromS]. */
    private fun run(p: LightPlanner, look: SongLook, seconds: Double, fromS: Double = 0.0, strength: LightPlanner.Strength = LightPlanner.Strength.ADAPTIVE, variant: LightPlanner.Variant = LightPlanner.Variant.FULL, a: (Long) -> Analysis): List<Pair<Long, IntArray>> {
        val out = ArrayList<Pair<Long, IntArray>>()
        var i = 0
        while (i < seconds * 30) {
            val t = Math.round((fromS + i / 30.0) * rate)
            val z = IntArray(Zones.COUNT)
            p.frame(t, a(t), look, strength, variant, z)
            out.add(Pair(t, z))
            i++
        }
        return out
    }

    private fun brightness(c: Int): Double = Colors.peak(c) / 255.0

    /** The starts of flashes at [zone]: a frame that rose by more than [rise] when the frame before did not rise. */
    private fun onsets(frames: List<Pair<Long, IntArray>>, zone: Int = 0, rise: Double = 0.08): List<Long> {
        val out = ArrayList<Long>()
        var rising = false
        for (i in 1 until frames.size) {
            val a = brightness(frames[i - 1].second[zone])
            val b = brightness(frames[i].second[zone])
            if (b - a > rise && !rising) out.add(frames[i].first)
            rising = b > a
        }
        return out
    }

    @Test
    fun aMonoSongShowsOneHueOnEveryZone() {
        val p = LightPlanner(rate)
        val frames = run(p, look(LightClass.MONO), 4.0) { analysis() }
        for ((_, z) in frames.drop(60)) {
            val hues = z.map { Colors.toHsv(it)[0] }
            assertTrue(hues.all { Colors.hueDistance(it, 220.0) < 3.0 })
        }
    }

    @Test
    fun aPulsePeaksOnTheBeatAndDecaysBeforeTheNext() {
        val p = LightPlanner(rate)
        val a = analysis(bpm = 120.0)
        val l = look(LightClass.DUO)
        val z = IntArray(Zones.COUNT)
        for (i in 0 until 60) p.frame(Math.round(i / 30.0 * rate), a, l, LightPlanner.Strength.ADAPTIVE, LightPlanner.Variant.FULL, z)
        p.frame(4L * rate / 2 + rate / 100, a, l, LightPlanner.Strength.ADAPTIVE, LightPlanner.Variant.FULL, z) // 10 ms after a beat
        val onBeat = brightness(z[0])
        p.frame(4L * rate / 2 + rate * 4 / 10, a, l, LightPlanner.Strength.ADAPTIVE, LightPlanner.Variant.FULL, z) // 400 ms after it
        val late = brightness(z[0])
        assertTrue("on the beat $onBeat, late $late", onBeat > late * 1.25)
    }

    @Test
    fun aboveOneHundredFiftyBpmEverySecondBeatPulses() {
        val p = LightPlanner(rate)
        val frames = run(p, look(LightClass.DUO), 12.0) { analysis(bpm = 170.0) }
        val gaps = onsets(frames).zipWithNext { a, b -> (b - a) * 1000.0 / rate }
        assertTrue(gaps.isNotEmpty())
        // every second beat of 170 BPM is 706 ms
        assertEquals(706.0, gaps.drop(1).average(), 40.0)
    }

    @Test
    fun noTwoFlashesStartCloserThan360MillisecondsWhateverTheGrid() {
        for (bpm in doubleArrayOf(60.0, 100.0, 140.0, 150.0, 160.0, 200.0)) {
            val p = LightPlanner(rate)
            // the grid jumps by a third of a beat every 1.1 s, as a tracker that keeps correcting itself might
            val period = 60.0 / bpm * rate
            val frames = run(p, look(LightClass.MULTI), 20.0, strength = LightPlanner.Strength.PUNCHY) { t ->
                analysis(bpm = bpm, energy = 1.0, beatFrame = (t / (1.1 * rate)).toInt() * period / 3)
            }
            val starts = onsets(frames, rise = 0.04)
            val tooClose = starts.zipWithNext { a, b -> (b - a) * 1000.0 / rate }.filter { it < 360.0 - 34.0 }
            assertTrue("$bpm BPM: flash gaps $tooClose", tooClose.isEmpty())
            assertTrue(p.pulsesAllowed > 0)
        }
    }

    @Test
    fun aSaturatedRedPulsesAtMostTenPercent() {
        val p = LightPlanner(rate)
        val red = Colors.rgb(230, 20, 10)
        val frames = run(p, look(LightClass.MONO, home = red), 8.0, strength = LightPlanner.Strength.PUNCHY) { analysis(energy = 1.0) }
        val b = frames.drop(150).map { brightness(it.second[7]) }
        assertTrue((b.maxOrNull()!! - b.minOrNull()!!) / b.maxOrNull()!! <= 0.11)
    }

    @Test
    fun theGlowBandPulsesAtMostThirtyPercent() {
        val p = LightPlanner(rate)
        // four times the same colour, so that only the pulses change the brightness
        val oneColour = SongLook("song", LightClass.MULTI, blue, blue, intArrayOf(blue, blue, blue, blue), "test")
        val frames = run(p, oneColour, 8.0, strength = LightPlanner.Strength.PUNCHY, variant = LightPlanner.Variant.BAND) { analysis(energy = 1.0) }
        val b = frames.drop(150).map { brightness(it.second[0]) }
        assertTrue((b.maxOrNull()!! - b.minOrNull()!!) / b.maxOrNull()!! <= 0.31)
    }

    @Test
    fun withoutALockedBeatTheLightsFollowTheLoudnessWithoutPulsing() {
        val p = LightPlanner(rate)
        val frames = run(p, look(LightClass.DUO), 10.0) { t -> analysis(locked = false, loudDb = if (t < 5 * rate) -35.0 else -12.0) }
        assertFalse(p.pulsing)
        val quiet = brightness(frames[140].second[0])
        val loud = brightness(frames.last().second[0])
        assertTrue("quiet $quiet loud $loud", loud > quiet)
        assertTrue(onsets(frames, rise = 0.08).isEmpty())
    }

    @Test
    fun aChorusLiftsDuoToMultiForThirtyTwoBeatsAndABreakdownLowersIt() {
        val p = LightPlanner(rate)
        val up = Analysis.Section(10L * rate, true)
        val a = analysis(sections = listOf(up))
        val l = look(LightClass.DUO)
        val z = IntArray(Zones.COUNT)
        p.frame(9L * rate, a, l, LightPlanner.Strength.ADAPTIVE, LightPlanner.Variant.FULL, z)
        assertEquals(LightClass.DUO, p.classNow)
        p.frame(11L * rate, a, l, LightPlanner.Strength.ADAPTIVE, LightPlanner.Variant.FULL, z)
        assertEquals(LightClass.MULTI, p.classNow)
        p.frame(10L * rate + 33 * rate / 2, a, l, LightPlanner.Strength.ADAPTIVE, LightPlanner.Variant.FULL, z) // 33 beats later
        assertEquals(LightClass.DUO, p.classNow)
        val down = analysis(sections = listOf(Analysis.Section(30L * rate, false)))
        p.frame(31L * rate, down, l, LightPlanner.Strength.ADAPTIVE, LightPlanner.Variant.FULL, z)
        assertEquals(LightClass.MONO, p.classNow)
    }

    @Test
    fun duoSwapsItsSidesAtASectionEvent() {
        val p = LightPlanner(rate)
        val l = look(LightClass.DUO)
        val before = run(p, l, 3.0) { analysis(bpm = 70.0, clarity = 0.1) }.last().second
        // one event, long enough ago that its lift (32 beats, 27 s at 70 BPM) is over: the class is Duo again, its sides swapped
        val after = run(p, l, 3.0, fromS = 40.0) { analysis(bpm = 70.0, clarity = 0.1, sections = listOf(Analysis.Section(1L * rate, true))) }.last().second
        assertTrue(Colors.hueDistance(Colors.toHsv(before[0])[0], 220.0) < 5)
        assertTrue(Colors.hueDistance(Colors.toHsv(after[0])[0], 30.0) < 5)
    }

    @Test
    fun silenceDimsTheLights() {
        val p = LightPlanner(rate)
        val l = look(LightClass.MONO)
        val playing = run(p, l, 4.0) { analysis() }.last().second
        val silent = run(p, l, 4.0, fromS = 4.0) { analysis(silent = true, loudDb = -90.0) }.last().second
        assertTrue(brightness(silent[0]) < 0.5 * brightness(playing[0]))
    }

    @Test
    fun theFlashLimiterKeepsThreeHundredSixtyMillisecondsBetweenStarts() {
        val f = FlashLimiter.forRate(rate)
        assertTrue(f.allow(0))
        assertFalse(f.allow((0.35 * rate).toLong()))
        assertTrue(f.allow((0.36 * rate).toLong()))
        assertEquals(2L, f.allowed)
        assertEquals(1L, f.held)
    }
}
