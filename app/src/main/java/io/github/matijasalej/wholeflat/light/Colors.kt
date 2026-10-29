package io.github.matijasalej.wholeflat.light

import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.floor
import kotlin.math.sqrt

/** Colours as opaque ARGB ints, and the few things the lights do with them: HSV, mixing, dimming, hue distances, ΔE. Plain Kotlin. */
object Colors {
    fun rgb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    fun red(c: Int): Int = (c shr 16) and 0xFF

    fun green(c: Int): Int = (c shr 8) and 0xFF

    fun blue(c: Int): Int = c and 0xFF

    /** The colour of hue [h] (degrees), saturation [s] and value [v] (0 to 1). */
    fun hsv(h: Double, s: Double, v: Double): Int {
        val hh = ((h % 360.0) + 360.0) % 360.0 / 60.0
        val ss = s.coerceIn(0.0, 1.0)
        val vv = v.coerceIn(0.0, 1.0)
        val i = floor(hh).toInt()
        val f = hh - i
        val p = vv * (1 - ss)
        val q = vv * (1 - ss * f)
        val t = vv * (1 - ss * (1 - f))
        val (r, g, b) = when (i % 6) {
            0 -> Triple(vv, t, p)
            1 -> Triple(q, vv, p)
            2 -> Triple(p, vv, t)
            3 -> Triple(p, q, vv)
            4 -> Triple(t, p, vv)
            else -> Triple(vv, p, q)
        }
        return rgb(Math.round(r * 255).toInt(), Math.round(g * 255).toInt(), Math.round(b * 255).toInt())
    }

    /** Hue (degrees), saturation and value of [c]. */
    fun toHsv(c: Int): DoubleArray {
        val r = red(c) / 255.0
        val g = green(c) / 255.0
        val b = blue(c) / 255.0
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val d = max - min
        val h = when {
            d < 1e-9 -> 0.0
            max == r -> 60.0 * (((g - b) / d) % 6.0)
            max == g -> 60.0 * ((b - r) / d + 2.0)
            else -> 60.0 * ((r - g) / d + 4.0)
        }
        return doubleArrayOf((h + 360.0) % 360.0, if (max <= 0) 0.0 else d / max, max)
    }

    /** [c] dimmed (or brightened) by [f]. */
    fun scale(c: Int, f: Double): Int = rgb(Math.round(red(c) * f).toInt(), Math.round(green(c) * f).toInt(), Math.round(blue(c) * f).toInt())

    /** [a] towards [b] by [t] (0: a, 1: b). */
    fun mix(a: Int, b: Int, t: Double): Int {
        val u = t.coerceIn(0.0, 1.0)
        return rgb(
            Math.round(red(a) + (red(b) - red(a)) * u).toInt(),
            Math.round(green(a) + (green(b) - green(a)) * u).toInt(),
            Math.round(blue(a) + (blue(b) - blue(a)) * u).toInt(),
        )
    }

    /**
     * [a] towards [b] by [t] round the hue circle (the short way), with saturation and value in between: a blue to orange blend passes
     * through magenta and red instead of the grey of a plain RGB mix. Greys blend as RGB.
     */
    fun mixHue(a: Int, b: Int, t: Double): Int {
        val ha = toHsv(a)
        val hb = toHsv(b)
        if (ha[1] < 0.15 || hb[1] < 0.15) return mix(a, b, t)
        val u = t.coerceIn(0.0, 1.0)
        return hsv(ha[0] + shortest(ha[0], hb[0]) * u, ha[1] + (hb[1] - ha[1]) * u, ha[2] + (hb[2] - ha[2]) * u)
    }

    /** The signed turn from hue [from] to hue [to] the short way (degrees, -180 to 180). */
    fun shortest(from: Double, to: Double): Double = ((to - from) % 360.0 + 540.0) % 360.0 - 180.0

    /** The distance of two hues (degrees, 0 to 180). */
    fun hueDistance(a: Double, b: Double): Double {
        val d = abs(((a - b) % 360.0 + 360.0) % 360.0)
        return if (d > 180) 360 - d else d
    }

    /** [h] turned towards [target] by at most [maxDeg] degrees (the short way round, never past it). */
    fun turnToward(h: Double, target: Double, maxDeg: Double): Double {
        var d = ((target - h) % 360.0 + 540.0) % 360.0 - 180.0
        if (abs(d) > maxDeg) d = Math.signum(d) * maxDeg
        return ((h + d) % 360.0 + 360.0) % 360.0
    }

    /** The share of red in [c] (0 to 1; 0 for black). */
    fun redShare(c: Int): Double {
        val sum = red(c) + green(c) + blue(c)
        return if (sum == 0) 0.0 else red(c).toDouble() / sum
    }

    /** The largest channel of [c] (0 to 255). */
    fun peak(c: Int): Int = maxOf(red(c), green(c), blue(c))

    /** The CIE76 ΔE of two sRGB colours (about 2.3 is just noticeable; the spike accepts below 10). */
    fun deltaE(a: Int, b: Int): Double {
        val la = lab(a)
        val lb = lab(b)
        val dl = la[0] - lb[0]
        val da = la[1] - lb[1]
        val db = la[2] - lb[2]
        return sqrt(dl * dl + da * da + db * db)
    }

    private fun lab(c: Int): DoubleArray {
        fun lin(v: Int): Double {
            val x = v / 255.0
            return if (x <= 0.04045) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4)
        }
        val r = lin(red(c))
        val g = lin(green(c))
        val b = lin(blue(c))
        val x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047
        val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
        val z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883
        fun f(t: Double): Double = if (t > 216.0 / 24389.0) cbrt(t) else (24389.0 / 27.0 * t + 16.0) / 116.0
        val fx = f(x)
        val fy = f(y)
        val fz = f(z)
        return doubleArrayOf(116.0 * fy - 16.0, 500.0 * (fx - fy), 200.0 * (fy - fz))
    }
}
