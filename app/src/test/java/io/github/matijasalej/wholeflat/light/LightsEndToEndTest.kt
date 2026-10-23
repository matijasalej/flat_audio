package io.github.matijasalej.wholeflat.light

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The audio, the analysis and the lights together, at the pace of the TV picture: what the room sees against what it hears. */
class LightsEndToEndTest {
    private val rate = Signals.RATE

    /**
     * Plays [pcm] through the analyzer and paints a picture 30 times a second. The painter of capture time t has heard the audio up to
     * t + [aheadMs] (the picture is drawn about 110 ms after its capture time and the capture arrives about 80 ms late) and asks the
     * planner for t + [offsetMs] (the setting `lights_offset_ms`). Returns the frame times and the colours of zone 0.
     */
    private fun paint(pcm: ShortArray, look: (Analysis) -> SongLook, aheadMs: Int = 30, offsetMs: Int = 20): List<Pair<Long, Int>> {
        val a = Analyzer(rate).also { it.reset(0) }
        val p = LightPlanner(rate)
        val frames = pcm.size / 2
        var pushed = 0
        val chunk = 441
        val buf = ShortArray(chunk * 2)
        val out = ArrayList<Pair<Long, Int>>()
        val z = IntArray(Zones.COUNT)
        var i = 0
        while (true) {
            val t = Math.round(i / 30.0 * rate)
            val heard = t + aheadMs * rate / 1000
            if (heard >= frames) break
            while (pushed + chunk <= heard) {
                System.arraycopy(pcm, pushed * 2, buf, 0, chunk * 2)
                a.push(buf, chunk)
                pushed += chunk
            }
            val s = a.snapshot
            p.frame(t + offsetMs * rate / 1000, s, look(s), LightPlanner.Strength.ADAPTIVE, LightPlanner.Variant.FULL, z)
            out.add(Pair(t, z[0]))
            i++
        }
        return out
    }

    private fun duo(key: String) = { _: Analysis -> SongLook(key, LightClass.DUO, Colors.hsv(220.0, 0.8, 0.9), Colors.hsv(30.0, 0.8, 0.9), IntArray(4) { Colors.hsv(220.0, 0.8, 0.9) }, "test") }

    @Test
    fun theLightsFlashOnTheKicksSlightlyBeforeTheSound() {
        for (bpm in doubleArrayOf(96.0, 120.0, 128.0)) {
            val seconds = 20.0
            val beats = Signals.beats(bpm, seconds)
            val frames = paint(Signals.band(bpm, seconds), duo("band"))
            // the flashes after the lock: frames brighter than the frame before by a clear step
            val starts = ArrayList<Long>()
            for (k in 1 until frames.size) {
                if (frames[k].first < 8 * rate) continue
                val b0 = Colors.peak(frames[k - 1].second)
                val b1 = Colors.peak(frames[k].second)
                if (b1 - b0 > 20) starts.add(frames[k].first)
            }
            assertTrue("flashes at $bpm BPM: ${starts.size}", starts.size >= (seconds - 9) * bpm / 60 * 0.8)
            // how far each flash is from the nearest beat (negative: the light comes first)
            val errs = starts.map { s -> (s - beats.minByOrNull { Math.abs(it - s) }!!) * 1000.0 / rate }
            assertTrue("$bpm BPM errors $errs", errs.all { it in -45.0..25.0 })
            assertEquals("$bpm BPM mean", -10.0, errs.average(), 20.0)
        }
    }

    @Test
    fun aQuietIntroDoesNotFlashBeforeTheBeatIsLocked() {
        val frames = paint(Signals.band(120.0, 6.0), duo("intro"))
        val early = frames.filter { it.first < 2 * rate }
        val jumps = early.zipWithNext { a, b -> Colors.peak(b.second) - Colors.peak(a.second) }
        assertTrue("jumps before the lock: ${jumps.filter { it > 20 }}", jumps.none { it > 20 })
    }
}
