package io.github.matijasalej.wholeflat.light

/** An in-place radix-2 FFT of one size (a power of two), its tables made once. Forward transform, no scaling. Plain Kotlin. */
class Fft(val size: Int) {
    private val levels: Int
    private val cos: DoubleArray
    private val sin: DoubleArray
    private val rev: IntArray

    init {
        require(size >= 2 && size and (size - 1) == 0) { "the size of an FFT is a power of two" }
        levels = Integer.numberOfTrailingZeros(size)
        cos = DoubleArray(size / 2) { Math.cos(2.0 * Math.PI * it / size) }
        sin = DoubleArray(size / 2) { Math.sin(2.0 * Math.PI * it / size) }
        rev = IntArray(size) { Integer.reverse(it) ushr (32 - levels) }
    }

    /** Transforms [re] and [im] (both of [size]) in place: X[k] = sum of x[n] e^(-2 pi i k n / size). */
    fun transform(re: DoubleArray, im: DoubleArray) {
        for (i in 0 until size) {
            val j = rev[i]
            if (j > i) {
                val tr = re[i]
                re[i] = re[j]
                re[j] = tr
                val ti = im[i]
                im[i] = im[j]
                im[j] = ti
            }
        }
        var half = 1
        while (half < size) {
            val step = size / (half * 2)
            var start = 0
            while (start < size) {
                var k = 0
                for (j in start until start + half) {
                    val l = j + half
                    val tr = re[l] * cos[k] + im[l] * sin[k]
                    val ti = im[l] * cos[k] - re[l] * sin[k]
                    re[l] = re[j] - tr
                    im[l] = im[j] - ti
                    re[j] += tr
                    im[j] += ti
                    k += step
                }
                start += half * 2
            }
            half *= 2
        }
    }

    companion object {
        /** The Hann window of [n] points. */
        fun hann(n: Int): DoubleArray = DoubleArray(n) { 0.5 - 0.5 * Math.cos(2.0 * Math.PI * it / n) }
    }
}
