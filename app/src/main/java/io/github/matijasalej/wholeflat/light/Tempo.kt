package io.github.matijasalej.wholeflat.light

import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.log2

/**
 * The tempo and the place of the beats, from the onset envelope (one value per hop, [hopsPerSecond] of them a second, oldest first).
 *
 * - **The tempo** is the lag at which the envelope of the last seconds is most like itself. The autocorrelation is weighted by a
 *   log-normal prior around [PRIOR_BPM] (Ellis 2007), and helped by its own multiples, so that the beat wins over its half and its double.
 * - **The period** is then refined on a comb: the period and phase whose beats land on the most onset strength. A comb of six seconds
 *   tells periods apart to a tenth of a hop.
 * - **The phase** is the comb's best placement of the beats. A known grid ([expectedOffset]) is preferred when it is about as good,
 *   so the beats do not jump between the beat and its off-beat.
 *
 * Plain Kotlin.
 */
class Tempo(val hopsPerSecond: Double = 100.0) {
    /** One reading: the tempo, the period in hops (fractional), how clear the beat is (0 to 1), and the beat nearest the end of the envelope. */
    class Estimate(
        val bpm: Double,
        val periodHops: Double,
        val clarity: Double,
        /** How far before the last value of the envelope the latest beat is (hops, 0 until a period). */
        val offsetHops: Double,
    )

    /**
     * The tempo of [env] (the last [count] values of it are used, oldest first), or null if there is too little or nothing that repeats.
     * [priorBpm] centres the prior (a tempo remembered for the song); [expectedOffset] is where the current grid puts the latest beat.
     */
    fun estimate(env: DoubleArray, count: Int = env.size, priorBpm: Double = PRIOR_BPM, expectedOffset: Double? = null, expectedPeriod: Double? = null): Estimate? {
        val n = minOf(count, env.size)
        if (n < MIN_SECONDS * hopsPerSecond) return null
        val from = env.size - n
        val s = smooth(env, from, n)
        var mean = 0.0
        for (v in s) mean += v
        mean /= n
        val x = DoubleArray(n) { s[it] - mean }
        var energy = 0.0
        for (v in x) energy += v * v
        if (energy / n < 1e-12) return null
        val var0 = energy / n
        val minLag = maxOf(2, floor(60.0 * hopsPerSecond / MAX_BPM).toInt())
        val maxLag = Math.ceil(60.0 * hopsPerSecond / MIN_BPM).toInt()
        val topLag = minOf(n / 2, 3 * maxLag)
        val r = DoubleArray(topLag + 2)
        for (lag in 1..topLag + 1) {
            if (lag >= n) break
            var acc = 0.0
            for (i in lag until n) acc += x[i] * x[i - lag]
            r[lag] = acc / ((n - lag) * var0)
        }
        val l0 = 60.0 * hopsPerSecond / priorBpm
        var best = -1
        var bestScore = Double.NEGATIVE_INFINITY
        for (lag in minLag..minOf(maxLag, topLag)) {
            var sum = r[lag]
            var weight = 1.0
            if (2 * lag <= topLag) {
                sum += 0.5 * r[2 * lag]
                weight += 0.5
            }
            if (3 * lag <= topLag) {
                sum += 0.25 * r[3 * lag]
                weight += 0.25
            }
            val prior = exp(-0.5 * Math.pow(log2(lag / l0) / PRIOR_OCTAVES, 2.0))
            val score = prior * sum / weight
            if (score > bestScore) {
                bestScore = score
                best = lag
            }
        }
        if (best < 0) return null
        val clarity = r[best].coerceIn(0.0, 1.0)
        // the peak between the lags, then the comb
        var lagF = best.toDouble()
        if (best - 1 >= 1 && best + 1 <= topLag + 1) {
            val a = r[best - 1]
            val b = r[best]
            val c = r[best + 1]
            val den = a - 2 * b + c
            if (den < 0) lagF = best + (0.5 * (a - c) / den).coerceIn(-0.5, 0.5)
        }
        // a period that the running grid already has is kept unless the comb finds a clearly better one
        val seed = if (expectedPeriod != null && Math.abs(expectedPeriod / lagF - 1.0) < 0.04) expectedPeriod else lagF
        val (period, offset) = comb(s, seed, expectedOffset)
        return Estimate(60.0 * hopsPerSecond / period, period, clarity, offset)
    }

    /**
     * The period near [seed] (within 3 %) and the offset of the latest beat from the end of [s] whose beats land on the most onset
     * strength over the last [COMB_SECONDS]; a placement near [expectedOffset] gets a bonus.
     */
    fun comb(s: DoubleArray, seed: Double, expectedOffset: Double?): Pair<Double, Double> {
        var bestPeriod = seed
        var bestOffset = 0.0
        var bestScore = Double.NEGATIVE_INFINITY
        val steps = 24
        for (i in -steps..steps) {
            val p = seed * (1.0 + 0.03 * i / steps)
            val (off, score) = phase(s, p, expectedOffset)
            if (score > bestScore) {
                bestScore = score
                bestPeriod = p
                bestOffset = off
            }
        }
        return Pair(bestPeriod, bestOffset)
    }

    /** The offset of the latest beat for [period] and its comb score (see [comb]). */
    fun phase(s: DoubleArray, period: Double, expectedOffset: Double?): Pair<Double, Double> {
        val last = s.size - 1
        val beats = maxOf(2, minOf(MAX_COMB_BEATS, floor(COMB_SECONDS * hopsPerSecond / period).toInt()))
        var bestOffset = 0.0
        var bestScore = Double.NEGATIVE_INFINITY
        var off = 0.0
        while (off < period) {
            var score = 0.0
            var w = 1.0
            for (k in 0 until beats) {
                val t = last - off - k * period
                if (t < 0) break
                score += w * at(s, t)
                w *= COMB_DECAY
            }
            if (expectedOffset != null) {
                val d = circular(off - expectedOffset, period) / (0.1 * period)
                score *= 1.0 + CONTINUITY_BONUS * exp(-d * d)
            }
            if (score > bestScore) {
                bestScore = score
                bestOffset = off
            }
            off += PHASE_STEP
        }
        return Pair(bestOffset, bestScore)
    }

    private fun smooth(env: DoubleArray, from: Int, n: Int): DoubleArray {
        // a short Gaussian (sigma one hop): the peaks are a few hops wide, the lags between them are interpolated
        val k = doubleArrayOf(0.054, 0.244, 0.403, 0.244, 0.054)
        return DoubleArray(n) { i ->
            var acc = 0.0
            for (j in -2..2) {
                val idx = (i + j).coerceIn(0, n - 1)
                acc += k[j + 2] * env[from + idx]
            }
            acc
        }
    }

    companion object {
        const val PRIOR_BPM = 120.0
        const val PRIOR_OCTAVES = 1.0
        const val MIN_BPM = 50.0
        const val MAX_BPM = 220.0
        const val MIN_SECONDS = 3.0
        const val COMB_SECONDS = 6.0
        const val MAX_COMB_BEATS = 16
        const val COMB_DECAY = 0.93
        const val CONTINUITY_BONUS = 0.25
        const val PHASE_STEP = 0.25

        /** [s] at the fractional index [t] (linear between the hops). */
        fun at(s: DoubleArray, t: Double): Double {
            val i = floor(t).toInt()
            if (i < 0) return s[0]
            if (i >= s.size - 1) return s[s.size - 1]
            val f = t - i
            return s[i] * (1 - f) + s[i + 1] * f
        }

        /** The signed distance [d] taken round a circle of [period], between -period/2 and period/2. */
        fun circular(d: Double, period: Double): Double {
            var x = d % period
            if (x > period / 2) x -= period
            if (x < -period / 2) x += period
            return x
        }
    }
}
