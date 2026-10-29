package io.github.matijasalej.wholeflat.light

import java.util.Random

/** Made-up music for the tests: interleaved 16 bit stereo at [RATE], the same in both channels. */
object Signals {
    const val RATE = 44100

    /** Beat times (frames) of [bpm] over [seconds], starting at [startS]; [swing] delays every second beat by that share of a beat. */
    fun beats(bpm: Double, seconds: Double, startS: Double = 0.1, swing: Double = 0.0): List<Long> {
        val period = 60.0 / bpm
        val out = ArrayList<Long>()
        var k = 0
        while (true) {
            var t = startS + k * period
            if (k % 2 == 1) t += swing * period
            if (t >= seconds) break
            out.add(Math.round(t * RATE))
            k++
        }
        return out
    }

    /** A click track: a 5 ms burst (1 kHz and 3 kHz, decaying) at each of [beats], at [amp] of full scale. */
    fun clicks(beats: List<Long>, seconds: Double, amp: Double = 0.5): ShortArray {
        val n = (seconds * RATE).toInt()
        val mono = DoubleArray(n)
        for (b in beats) addClick(mono, b.toInt(), amp)
        return stereo(mono)
    }

    /**
     * A beat with a band: a kick on every beat, a snare on every second, hats on the eighths (the off-beat ones late by [swing] of a
     * half beat), and a held triad (major or minor, on [root] Hz) under it, with a little noise. [level] scales all of it.
     */
    fun band(bpm: Double, seconds: Double, major: Boolean = true, root: Double = 261.63, level: Double = 1.0, swing: Double = 0.0, seed: Long = 1): ShortArray {
        val n = (seconds * RATE).toInt()
        val mono = DoubleArray(n)
        val rnd = Random(seed)
        val period = 60.0 / bpm
        val beatTimes = beats(bpm, seconds)
        for ((k, b) in beatTimes.withIndex()) {
            addKick(mono, b.toInt(), 0.45 * level)
            if (k % 2 == 1) addNoiseBurst(mono, b.toInt(), 0.25 * level, 0.08, rnd)
            val half = (b + period * RATE * (0.5 + swing / 2)).toInt()
            addNoiseBurst(mono, b.toInt(), 0.04 * level, 0.02, rnd, high = true)
            if (half < n) addNoiseBurst(mono, half, 0.04 * level, 0.02, rnd, high = true)
        }
        addChord(mono, root, major, 0.10 * level)
        for (i in 0 until n) mono[i] += 0.004 * level * rnd.nextGaussian()
        return stereo(mono)
    }

    /** White noise at [db] dBFS RMS that ramps to [toDb] (same if null), with no beat. */
    fun noise(seconds: Double, db: Double = -20.0, toDb: Double? = null, seed: Long = 7): ShortArray {
        val n = (seconds * RATE).toInt()
        val rnd = Random(seed)
        val end = toDb ?: db
        val mono = DoubleArray(n) {
            val g = Math.pow(10.0, (db + (end - db) * it / n) / 20.0)
            g * rnd.nextGaussian()
        }
        return stereo(mono)
    }

    /** A held triad on [root] Hz (major or minor), with a few harmonics. */
    fun chord(seconds: Double, root: Double, major: Boolean, amp: Double = 0.2): ShortArray {
        val mono = DoubleArray((seconds * RATE).toInt())
        addChord(mono, root, major, amp)
        return stereo(mono)
    }

    /** Silence. */
    fun silence(seconds: Double): ShortArray = ShortArray((seconds * RATE).toInt() * 2)

    /** [a] then [b]. */
    fun concat(vararg parts: ShortArray): ShortArray {
        val out = ShortArray(parts.sumOf { it.size })
        var pos = 0
        for (p in parts) {
            System.arraycopy(p, 0, out, pos, p.size)
            pos += p.size
        }
        return out
    }

    /** Feeds [pcm] to [push] in captures of 10 ms (as the app reads it). */
    fun feed(pcm: ShortArray, chunkFrames: Int = 441, push: (ShortArray, Int) -> Unit) {
        val frames = pcm.size / 2
        var f = 0
        val buf = ShortArray(chunkFrames * 2)
        while (f < frames) {
            val n = minOf(chunkFrames, frames - f)
            System.arraycopy(pcm, f * 2, buf, 0, n * 2)
            push(buf, n)
            f += n
        }
    }

    private fun addClick(mono: DoubleArray, at: Int, amp: Double) {
        val len = (0.005 * RATE).toInt()
        for (i in 0 until len) {
            val idx = at + i
            if (idx !in mono.indices) break
            val t = i.toDouble() / RATE
            val env = Math.exp(-i / (len / 3.0))
            mono[idx] += amp * env * (0.6 * Math.sin(2 * Math.PI * 1000 * t) + 0.4 * Math.sin(2 * Math.PI * 3000 * t))
        }
    }

    private fun addKick(mono: DoubleArray, at: Int, amp: Double) {
        val len = (0.15 * RATE).toInt()
        var phase = 0.0
        for (i in 0 until len) {
            val idx = at + i
            if (idx !in mono.indices) break
            val t = i.toDouble() / RATE
            val f = 50.0 + 90.0 * Math.exp(-t / 0.03)
            phase += 2 * Math.PI * f / RATE
            mono[idx] += amp * Math.exp(-t / 0.06) * Math.sin(phase) + (if (i < 40) amp * 0.3 * Math.exp(-i / 10.0) else 0.0)
        }
    }

    private fun addNoiseBurst(mono: DoubleArray, at: Int, amp: Double, decayS: Double, rnd: Random, high: Boolean = false) {
        val len = (decayS * 4 * RATE).toInt()
        var prev = 0.0
        for (i in 0 until len) {
            val idx = at + i
            if (idx !in mono.indices) break
            val w = rnd.nextGaussian()
            val v = if (high) w - prev else w // a first difference leans the noise to the highs
            prev = w
            mono[idx] += amp * Math.exp(-i.toDouble() / (decayS * RATE)) * v
        }
    }

    private fun addChord(mono: DoubleArray, root: Double, major: Boolean, amp: Double) {
        val third = if (major) 4 else 3
        for (semi in intArrayOf(0, third, 7, 12)) {
            val f = root * Math.pow(2.0, semi / 12.0)
            for (h in 1..3) {
                val a = amp / (h * 1.5)
                for (i in mono.indices) mono[i] += a * Math.sin(2 * Math.PI * f * h * i / RATE)
            }
        }
    }

    private fun stereo(mono: DoubleArray): ShortArray {
        val out = ShortArray(mono.size * 2)
        for (i in mono.indices) {
            val v = (mono[i] * 32767).toInt().coerceIn(-32768, 32767).toShort()
            out[2 * i] = v
            out[2 * i + 1] = v
        }
        return out
    }
}
