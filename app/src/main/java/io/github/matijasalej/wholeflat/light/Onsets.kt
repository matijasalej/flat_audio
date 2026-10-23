package io.github.matijasalej.wholeflat.light

import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * What the music does every hundredth of a second, from the captured PCM (interleaved 16 bit stereo):
 * - how much new sound began: the onset strength, a spectral flux on log magnitudes, also for the bass and the highs alone;
 * - how loud it is;
 * - how bright (the spectral centroid) and how much of it is bass;
 * - every tenth hop, which pitch classes sound: a chroma of the last ~186 ms, for the key.
 *
 * One hop is [hop] frames (441 at 44.1 kHz). The spectrum is that of the last [FFT_SIZE] frames, so an onset shows in the hop whose
 * window it enters; [ONSET_LAG_FRAMES] says how far after the onset the flux peaks, for the beat times. Plain Kotlin, one thread.
 */
class Onsets(val rate: Int = 44100) {
    /** One hop: [index] counts hops from the start of the analysis; the hop ends at frame (index + 1) * hop. */
    class Hop(
        val index: Long,
        val flux: Double,
        val bassFlux: Double,
        val highFlux: Double,
        val rmsDb: Double,
        val centroidHz: Double,
        val bassShare: Double,
        /** The pitch classes from C (twelve, summing to 1), every [CHROMA_EVERY]th hop; null in between or in silence. */
        val chroma: DoubleArray?,
    )

    val hop = rate / 100

    private val fft = Fft(FFT_SIZE)
    private val window = Fft.hann(FFT_SIZE)
    private val ring = DoubleArray(FFT_SIZE)
    private var ringPos = 0
    private var fill = 0
    private var hopIndex = 0L
    private val re = DoubleArray(FFT_SIZE)
    private val im = DoubleArray(FFT_SIZE)
    private val logMag = DoubleArray(FFT_SIZE / 2)
    private val prevLogMag = DoubleArray(FFT_SIZE / 2)
    private var havePrev = false
    private var hopSquares = 0.0

    private val binHz = rate.toDouble() / FFT_SIZE
    private val topBin = minOf(FFT_SIZE / 2 - 1, (16_000.0 / binHz).toInt())
    private val bassTop = maxOf(1, (200.0 / binHz).toInt())
    private val highFrom = minOf(topBin, (4_000.0 / binHz).toInt())

    // the chroma: the audio low-passed and taken down by four, and a longer spectrum of it
    private val decimate = 4
    private val lowRate = rate.toDouble() / decimate
    // 3.5 kHz: the chroma uses up to 2.5 kHz, and what folds back into that band from above 5.5 kHz is well into the stop band
    private val fir = lowPass(FIR_TAPS, 3_500.0 / rate)
    private val firHist = DoubleArray(FIR_TAPS)
    private var firPos = 0
    private var firPhase = 0
    private val chromaFft = Fft(CHROMA_SIZE)
    private val chromaWindow = Fft.hann(CHROMA_SIZE)
    private val lowRing = DoubleArray(CHROMA_SIZE)
    private var lowPos = 0
    private val cre = DoubleArray(CHROMA_SIZE)
    private val cim = DoubleArray(CHROMA_SIZE)
    private val pitchClass = IntArray(CHROMA_SIZE / 2) { -1 }
    private val pitchWeight = DoubleArray(CHROMA_SIZE / 2)

    init {
        for (k in 1 until CHROMA_SIZE / 2) {
            val f = k * lowRate / CHROMA_SIZE
            if (f < CHROMA_LOW_HZ || f > CHROMA_HIGH_HZ) continue
            val midi = 69.0 + 12.0 * log2(f / 440.0)
            val nearest = midi.roundToInt()
            pitchClass[k] = ((nearest % 12) + 12) % 12
            pitchWeight[k] = 1.0 - 2.0 * Math.abs(midi - nearest) // 1 on the note, 0 halfway to the next
        }
    }

    /** Analyses [frames] frames of [pcm] (interleaved stereo) and hands every finished hop to [sink]. */
    fun push(pcm: ShortArray, frames: Int, sink: (Hop) -> Unit) {
        for (i in 0 until frames) {
            val x = (pcm[2 * i] + pcm[2 * i + 1]) / 65536.0
            ring[ringPos] = x
            ringPos = (ringPos + 1) % FFT_SIZE
            hopSquares += x * x
            feedLow(x)
            fill++
            if (fill == hop) {
                fill = 0
                sink(analyseHop())
            }
        }
    }

    /** Forgets everything (a new capture, a jump in the frames): the next hop counts from 0. */
    fun reset() {
        ring.fill(0.0)
        ringPos = 0
        fill = 0
        hopIndex = 0
        havePrev = false
        hopSquares = 0.0
        firHist.fill(0.0)
        firPos = 0
        firPhase = 0
        lowRing.fill(0.0)
        lowPos = 0
    }

    private fun feedLow(x: Double) {
        firHist[firPos] = x
        firPos = (firPos + 1) % FIR_TAPS
        firPhase = (firPhase + 1) % decimate
        if (firPhase != 0) return
        var y = 0.0
        var idx = firPos
        for (t in 0 until FIR_TAPS) {
            y += fir[t] * firHist[idx]
            idx = (idx + 1) % FIR_TAPS
        }
        lowRing[lowPos] = y
        lowPos = (lowPos + 1) % CHROMA_SIZE
    }

    private fun analyseHop(): Hop {
        val rms = sqrt(hopSquares / hop)
        hopSquares = 0.0
        val rmsDb = if (rms <= 1e-9) FLOOR_DB else maxOf(FLOOR_DB, 20.0 * log10(rms))
        for (n in 0 until FFT_SIZE) {
            re[n] = ring[(ringPos + n) % FFT_SIZE] * window[n]
            im[n] = 0.0
        }
        fft.transform(re, im)
        var flux = 0.0
        var bass = 0.0
        var high = 0.0
        var magSum = 0.0
        var weighted = 0.0
        var power = 0.0
        var bassPower = 0.0
        for (k in 1..topBin) {
            val mag = sqrt(re[k] * re[k] + im[k] * im[k]) / (FFT_SIZE / 4.0)
            val lm = ln(1.0 + COMPRESSION * mag)
            logMag[k] = lm
            magSum += mag
            weighted += mag * k * binHz
            val p = mag * mag
            power += p
            if (k <= bassTop) bassPower += p
            if (havePrev) {
                val d = lm - prevLogMag[k]
                if (d > 0) {
                    flux += d
                    if (k <= bassTop) bass += d
                    if (k >= highFrom) high += d
                }
            }
        }
        System.arraycopy(logMag, 0, prevLogMag, 0, logMag.size)
        havePrev = true
        val index = hopIndex++
        val chroma = if (index % CHROMA_EVERY == CHROMA_EVERY - 1L && rmsDb > SILENCE_DB) chroma() else null
        return Hop(
            index = index,
            flux = flux / topBin,
            bassFlux = bass / bassTop,
            highFlux = high / (topBin - highFrom + 1),
            rmsDb = rmsDb,
            centroidHz = if (magSum > 1e-12) weighted / magSum else 0.0,
            bassShare = if (power > 1e-18) bassPower / power else 0.0,
            chroma = chroma,
        )
    }

    private fun chroma(): DoubleArray? {
        for (n in 0 until CHROMA_SIZE) {
            cre[n] = lowRing[(lowPos + n) % CHROMA_SIZE] * chromaWindow[n]
            cim[n] = 0.0
        }
        chromaFft.transform(cre, cim)
        val c = DoubleArray(12)
        var sum = 0.0
        for (k in 1 until CHROMA_SIZE / 2) {
            val pc = pitchClass[k]
            if (pc < 0) continue
            val v = (cre[k] * cre[k] + cim[k] * cim[k]) * pitchWeight[k]
            c[pc] += v
            sum += v
        }
        if (sum <= 1e-18) return null
        for (i in 0 until 12) c[i] /= sum
        return c
    }

    companion object {
        const val FFT_SIZE = 1024
        const val CHROMA_SIZE = 2048
        const val CHROMA_EVERY = 10L
        const val CHROMA_LOW_HZ = 80.0
        const val CHROMA_HIGH_HZ = 2_500.0
        const val FIR_TAPS = 33
        const val COMPRESSION = 1000.0
        const val FLOOR_DB = -120.0

        /** Below this a hop is silence (dBFS): no chroma is taken from it. */
        const val SILENCE_DB = -60.0

        /**
         * How many frames after an onset the flux of its hop peaks, counted from the end of that hop: the onset has to come into the
         * window, which is weighted towards its middle. Measured on click tracks (see OnsetsTest); beat times are moved back by it.
         */
        const val ONSET_LAG_FRAMES = 512

        /** A windowed-sinc low-pass of [taps] taps at [cutoff] (a fraction of the input rate), with unit gain at DC. */
        fun lowPass(taps: Int, cutoff: Double): DoubleArray {
            val h = DoubleArray(taps)
            val m = (taps - 1) / 2.0
            var sum = 0.0
            for (n in 0 until taps) {
                val x = n - m
                val sinc = if (x == 0.0) 2 * cutoff else Math.sin(2 * Math.PI * cutoff * x) / (Math.PI * x)
                val w = 0.54 - 0.46 * Math.cos(2 * Math.PI * n / (taps - 1))
                h[n] = sinc * w
                sum += h[n]
            }
            for (n in 0 until taps) h[n] /= sum
            return h
        }
    }
}
