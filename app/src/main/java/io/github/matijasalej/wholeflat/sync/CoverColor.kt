package io.github.matijasalej.wholeflat.sync

/**
 * The colour behind the lyrics on the TV: the dominant colour of the cover, darkened until white text reads well on it. A cover with
 * no colour in it (grey) gives its average, darkened the same. Without a cover the colour of the mock is used. Plain Kotlin.
 */
object CoverColor {
    /** The colour when there is no cover (the one of the design mock). */
    val DEFAULT: Int = rgb(64, 92, 140)

    /** The relative luminance above which white text stops being comfortable. */
    private const val MAX_LUMINANCE = 0.16
    private const val MIN_LUMINANCE = 0.025
    private const val BUCKETS = 12

    fun rgb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    fun red(c: Int): Int = (c shr 16) and 0xFF
    fun green(c: Int): Int = (c shr 8) and 0xFF
    fun blue(c: Int): Int = c and 0xFF

    /** The dominant colour of the pixels (ARGB), a few hundred are enough. */
    fun of(argb: IntArray, count: Int = argb.size): Int {
        val weight = DoubleArray(BUCKETS)
        val sr = DoubleArray(BUCKETS)
        val sg = DoubleArray(BUCKETS)
        val sb = DoubleArray(BUCKETS)
        var allR = 0.0
        var allG = 0.0
        var allB = 0.0
        var n = 0
        for (i in 0 until count) {
            val p = argb[i]
            if ((p ushr 24) < 128) continue
            val r = red(p)
            val g = green(p)
            val b = blue(p)
            allR += r
            allG += g
            allB += b
            n++
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val delta = max - min
            if (delta < 12 || max == 0) continue // grey: it has no hue to vote with
            val s = delta.toDouble() / max
            val v = max / 255.0
            val hue = when (max) {
                r -> ((g - b).toDouble() / delta + 6.0) % 6.0
                g -> (b - r).toDouble() / delta + 2.0
                else -> (r - g).toDouble() / delta + 4.0
            } * 60.0
            val bucket = (hue / (360.0 / BUCKETS)).toInt().coerceIn(0, BUCKETS - 1)
            val w = s * v + 0.001
            weight[bucket] += w
            sr[bucket] += r * w
            sg[bucket] += g * w
            sb[bucket] += b * w
        }
        if (n == 0) return DEFAULT
        var best = -1
        for (k in 0 until BUCKETS) if (weight[k] > 0 && (best < 0 || weight[k] > weight[best])) best = k
        // too little colour in the cover to speak of: its plain average
        val (r, g, b) = if (best < 0 || weight[best] < 0.01 * n) {
            Triple(allR / n, allG / n, allB / n)
        } else {
            Triple(sr[best] / weight[best], sg[best] / weight[best], sb[best] / weight[best])
        }
        return readable(r, g, b)
    }

    /** The relative luminance of an sRGB colour (0 to 1). */
    fun luminance(r: Double, g: Double, b: Double): Double {
        fun lin(c: Double): Double {
            val v = c / 255.0
            return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)
    }

    /** The colour darkened until its luminance is at most [MAX_LUMINANCE], and lifted a little if it is nearly black. */
    private fun readable(r0: Double, g0: Double, b0: Double): Int {
        var r = r0
        var g = g0
        var b = b0
        var guard = 0
        while (luminance(r, g, b) > MAX_LUMINANCE && guard++ < 60) {
            r *= 0.94
            g *= 0.94
            b *= 0.94
        }
        guard = 0
        while (luminance(r, g, b) < MIN_LUMINANCE && guard++ < 60) {
            r = r * 1.1 + 2
            g = g * 1.1 + 2
            b = b * 1.1 + 2
        }
        return rgb(Math.round(r).toInt(), Math.round(g).toInt(), Math.round(b).toInt())
    }

    /** [color] with [c] mixed in by [a] (0 to 1): the colour a text or a line is drawn in over the background. */
    fun mix(color: Int, c: Int, a: Double): Int = rgb(
        Math.round(red(color) * (1 - a) + red(c) * a).toInt(),
        Math.round(green(color) * (1 - a) + green(c) * a).toInt(),
        Math.round(blue(color) * (1 - a) + blue(c) * a).toInt(),
    )
}
