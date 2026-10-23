package io.github.matijasalej.wholeflat.light

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * The slow side of a song, hop by hop:
 * - **Energy** (0 to 1): how loud, how many onsets a second and how bright, the mix Spotify's "energy" describes. It is reliable
 *   (R² about 0.58 against listeners), and it is the main signal of the lights.
 * - **Mood** (0 sad to 1 happy): major or minor from the pitch classes against the Krumhansl-Kessler key profiles, plus brightness and
 *   tempo. It is weak (R² about 0.28), so it only tints the colour.
 * - **Section events:** the music gets clearly louder (a chorus, a drop) or clearly quieter (a breakdown), each confirmed for a second.
 *
 * Plain Kotlin, one thread.
 */
class SongFeatures(private val hopsPerSecond: Int = 100) {
    /** A section event: at hop [hop] the music became louder ([up]) or quieter, by [db]. */
    class Section(val hop: Long, val up: Boolean, val db: Double)

    private val window = 8 * hopsPerSecond
    private val power = DoubleArray(window)
    private val centroid = DoubleArray(window)
    private val flux = DoubleArray(window)
    private val onset = BooleanArray(window)
    private var count = 0L
    private var lastOnset = Long.MIN_VALUE / 2

    private val energySamples = ArrayList<Double>()
    private val chroma = DoubleArray(12)
    private var chromaWeight = 0.0
    private val sections = ArrayList<Section>()
    private var candidate: Section? = null
    private var lastEventHop = Long.MIN_VALUE / 2

    /** Hops seen since the start of the song. */
    val hops: Long get() = count

    fun reset() {
        power.fill(0.0)
        centroid.fill(0.0)
        flux.fill(0.0)
        onset.fill(false)
        count = 0
        lastOnset = Long.MIN_VALUE / 2
        energySamples.clear()
        chroma.fill(0.0)
        chromaWeight = 0.0
        sections.clear()
        candidate = null
        lastEventHop = Long.MIN_VALUE / 2
    }

    fun add(h: Onsets.Hop) {
        val i = idx(count)
        power[i] = Math.pow(10.0, h.rmsDb / 10.0)
        centroid[i] = h.centroidHz
        flux[i] = h.flux
        onset[i] = false
        count++
        markOnset()
        h.chroma?.let { c ->
            // an average over about half a minute, so the key settles and can still change
            val a = 1.0 / minOf(chromaWeight + 1.0, CHROMA_MEMORY)
            for (k in 0 until 12) chroma[k] = chroma[k] * (1 - a) + c[k] * a
            chromaWeight += 1.0
        }
        if (count % (hopsPerSecond / 2) == 0L) {
            if (!silent() && count >= hopsPerSecond * 3) energySamples.add(energyNow())
            sectionCheck()
        }
    }

    /** The level (dBFS) of the last [seconds]. */
    fun loudDb(seconds: Double): Double {
        val n = minOf(count, (seconds * hopsPerSecond).toLong()).toInt()
        if (n <= 0) return Onsets.FLOOR_DB
        var sum = 0.0
        for (j in 1..n) sum += power[idx(count - j)]
        val mean = sum / n
        return if (mean <= 1e-12) Onsets.FLOOR_DB else maxOf(Onsets.FLOOR_DB, 10.0 * log10(mean))
    }

    /** True if the last second was below [SILENCE_DB]. */
    fun silent(): Boolean = count == 0L || loudDb(1.0) < SILENCE_DB

    /** Onsets a second over the last [seconds]. */
    fun onsetRate(seconds: Double = 6.0): Double {
        val n = minOf(count, (seconds * hopsPerSecond).toLong()).toInt()
        if (n <= hopsPerSecond / 2) return 0.0
        var c = 0
        for (j in 1..n) if (onset[idx(count - j)]) c++
        return c * hopsPerSecond.toDouble() / n
    }

    /** The spectral centroid of the last [seconds], weighted by the power of each hop (Hz). */
    fun brightnessHz(seconds: Double = 3.0): Double {
        val n = minOf(count, (seconds * hopsPerSecond).toLong()).toInt()
        var sw = 0.0
        var s = 0.0
        for (j in 1..n) {
            val k = idx(count - j)
            s += centroid[k] * power[k]
            sw += power[k]
        }
        return if (sw <= 1e-12) 0.0 else s / sw
    }

    /** The energy of the last few seconds, 0 to 1. */
    fun energyNow(): Double {
        val loud = ((loudDb(3.0) - QUIET_DB) / (LOUD_DB - QUIET_DB)).coerceIn(0.0, 1.0)
        val rate = (onsetRate() / BUSY_ONSETS).coerceIn(0.0, 1.0)
        val bright = ((brightnessHz() - DULL_HZ) / (BRIGHT_HZ - DULL_HZ)).coerceIn(0.0, 1.0)
        return (0.45 * loud + 0.30 * rate + 0.25 * bright).coerceIn(0.0, 1.0)
    }

    /** The energy of the song so far: the median of its half-second readings (silence left out); [energyNow] until there are some. */
    fun energySong(): Double {
        if (energySamples.isEmpty()) return energyNow()
        val s = energySamples.sorted()
        return s[s.size / 2]
    }

    /** How major the music is (-1 minor to 1 major) and how sure (the best correlation with a key profile), from the pitch classes so far. */
    fun mode(): Pair<Double, Double> {
        if (chromaWeight < MIN_CHROMA) return Pair(0.0, 0.0)
        var bestMajor = -1.0
        var bestMinor = -1.0
        for (key in 0 until 12) {
            bestMajor = maxOf(bestMajor, correlate(chroma, MAJOR, key))
            bestMinor = maxOf(bestMinor, correlate(chroma, MINOR, key))
        }
        val score = ((bestMajor - bestMinor) / MODE_SPREAD).coerceIn(-1.0, 1.0)
        return Pair(score, maxOf(bestMajor, bestMinor))
    }

    /** The mood, 0 (sad, dark) to 1 (happy, light): mostly the mode, a little brightness and tempo ([bpm], NaN if unknown). */
    fun mood(bpm: Double): Double {
        val (mode, sure) = mode()
        val modeTerm = if (sure > 0.3) mode else 0.0
        val bright = ((brightnessHz() - DULL_HZ) / (BRIGHT_HZ - DULL_HZ)).coerceIn(0.0, 1.0)
        val tempoTerm = if (bpm.isNaN()) 0.0 else ((bpm - 100.0) / 60.0).coerceIn(-1.0, 1.0)
        return (0.5 + 0.3 * modeTerm + 0.1 * (2 * bright - 1) + 0.1 * tempoTerm).coerceIn(0.0, 1.0)
    }

    /** The section events so far (oldest first). */
    fun sections(): List<Section> = sections.toList()

    private fun markOnset() {
        // a peak of the flux a hop ago, clearly above the last second, at least 50 ms after the one before
        if (count < 3) return
        val c = count - 1
        val prev = flux[idx(c - 1)]
        val cur = flux[idx(c)]
        val before = flux[idx(c - 2)]
        if (!(prev > before && prev >= cur)) return
        val n = minOf(count, hopsPerSecond.toLong()).toInt()
        var mean = 0.0
        for (j in 1..n) mean += flux[idx(count - j)]
        mean /= n
        var v = 0.0
        for (j in 1..n) {
            val d = flux[idx(count - j)] - mean
            v += d * d
        }
        val sd = sqrt(v / n)
        var max = 0.0
        for (j in 1..minOf(count, 2L * hopsPerSecond).toInt()) max = maxOf(max, flux[idx(count - j)])
        // relative to the last second and the last two, and above what a held chord does (its flux stays below about 0.012)
        if (prev > mean + ONSET_SD * sd && prev > ONSET_FLOOR && prev > 0.15 * max && c - 1 - lastOnset >= hopsPerSecond / 20) {
            onset[idx(c - 1)] = true
            lastOnset = c - 1
        }
    }

    private fun sectionCheck() {
        if (count < hopsPerSecond * 6) return
        val short = loudDb(1.0)
        val longPart = levelBetween(5.0, 1.0)
        val d = short - longPart
        val now = count
        val cand = candidate
        if (cand != null) {
            // confirmed if it still holds half a second later
            val holds = if (cand.up) d >= UP_DB * 0.8 else d <= -DOWN_DB * 0.8
            if (holds && now - lastEventHop >= MIN_GAP_S * hopsPerSecond) {
                sections.add(cand)
                lastEventHop = cand.hop
                if (sections.size > MAX_SECTIONS) sections.removeAt(0)
            }
            candidate = null
            return
        }
        if (short < SILENCE_DB) return
        if (d >= UP_DB) candidate = Section(now - hopsPerSecond, true, d)
        else if (d <= -DOWN_DB) candidate = Section(now - hopsPerSecond, false, d)
    }

    /** The level (dBFS) between [fromS] and [toS] seconds ago. */
    private fun levelBetween(fromS: Double, toS: Double): Double {
        val a = (fromS * hopsPerSecond).toInt()
        val b = (toS * hopsPerSecond).toInt()
        var sum = 0.0
        var n = 0
        for (j in b + 1..a) {
            if (j > count) break
            sum += power[idx(count - j)]
            n++
        }
        if (n == 0 || sum / n <= 1e-12) return Onsets.FLOOR_DB
        return 10.0 * log10(sum / n)
    }

    private fun idx(hop: Long): Int = (((hop % window) + window) % window).toInt()

    companion object {
        const val SILENCE_DB = -50.0
        const val QUIET_DB = -32.0
        const val LOUD_DB = -9.0
        const val BUSY_ONSETS = 6.0
        const val DULL_HZ = 600.0
        const val BRIGHT_HZ = 3_500.0
        const val ONSET_SD = 1.5
        const val ONSET_FLOOR = 0.03
        const val UP_DB = 3.0
        const val DOWN_DB = 6.0
        const val MIN_GAP_S = 8
        const val MAX_SECTIONS = 16
        const val CHROMA_MEMORY = 300.0
        const val MIN_CHROMA = 30.0
        const val MODE_SPREAD = 0.15

        /** The Krumhansl-Kessler profiles, from the tonic. */
        val MAJOR = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
        val MINOR = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)

        /** The Pearson correlation of [chroma] with [profile] rotated to [key]. */
        fun correlate(chroma: DoubleArray, profile: DoubleArray, key: Int): Double {
            var mc = 0.0
            var mp = 0.0
            for (i in 0 until 12) {
                mc += chroma[i]
                mp += profile[i]
            }
            mc /= 12
            mp /= 12
            var num = 0.0
            var dc = 0.0
            var dp = 0.0
            for (i in 0 until 12) {
                val c = chroma[(i + key) % 12] - mc
                val p = profile[i] - mp
                num += c * p
                dc += c * c
                dp += p * p
            }
            return if (dc <= 1e-18 || dp <= 1e-18) 0.0 else num / sqrt(dc * dp)
        }
    }
}
