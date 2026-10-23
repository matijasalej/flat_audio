package io.github.matijasalej.wholeflat.light

/**
 * The hues of a cover: up to four clusters of hue with their share of the cover's colour, strongest first, and whether the cover has
 * colour at all. A pixel votes for its hue with its saturation times its value (as in the lyrics background), greys do not vote.
 * [colourful] is three or more strong hues (each at least [STRONG_SHARE] of the colour) at least 60° apart: such a cover asks for
 * more than two colours. Plain Kotlin.
 */
class CoverHues(val clusters: List<Cluster>, val grey: Boolean) {
    class Cluster(val hue: Double, val share: Double, val saturation: Double, val value: Double)

    val dominant: Cluster? get() = clusters.firstOrNull()

    val colourful: Boolean
        get() {
            val strong = clusters.filter { it.share >= STRONG_SHARE }
            var count = 0
            val picked = ArrayList<Cluster>()
            for (c in strong) {
                if (picked.all { Colors.hueDistance(it.hue, c.hue) >= 60.0 }) {
                    picked.add(c)
                    count++
                }
            }
            return count >= 3
        }

    /** The strongest hue other than the dominant one that is at least [minDistance] away and at least [STRONG_SHARE] of the colour. */
    fun second(minDistance: Double = 60.0): Cluster? {
        val d = dominant ?: return null
        return clusters.drop(1).firstOrNull { it.share >= STRONG_SHARE && Colors.hueDistance(it.hue, d.hue) >= minDistance }
    }

    companion object {
        const val BUCKETS = 36
        const val STRONG_SHARE = 0.12
        val NONE = CoverHues(emptyList(), true)

        /** The hues of [count] pixels of [argb] (a few hundred are enough). */
        fun of(argb: IntArray, count: Int = argb.size): CoverHues {
            val weight = DoubleArray(BUCKETS)
            val sumS = DoubleArray(BUCKETS)
            val sumV = DoubleArray(BUCKETS)
            val sumSin = DoubleArray(BUCKETS)
            val sumCos = DoubleArray(BUCKETS)
            var pixels = 0
            for (i in 0 until count) {
                val p = argb[i]
                if ((p ushr 24) < 128) continue
                pixels++
                val r = Colors.red(p)
                val g = Colors.green(p)
                val b = Colors.blue(p)
                val max = maxOf(r, g, b)
                if (max - minOf(r, g, b) < 12 || max == 0) continue
                val hsv = Colors.toHsv(p)
                val w = hsv[1] * hsv[2]
                val k = ((hsv[0] / (360.0 / BUCKETS)).toInt()).coerceIn(0, BUCKETS - 1)
                weight[k] += w
                sumS[k] += hsv[1] * w
                sumV[k] += hsv[2] * w
                sumSin[k] += Math.sin(Math.toRadians(hsv[0])) * w
                sumCos[k] += Math.cos(Math.toRadians(hsv[0])) * w
            }
            val total = weight.sum()
            if (pixels == 0 || total < GREY_SHARE * pixels) return NONE
            val smooth = DoubleArray(BUCKETS) { 0.25 * weight[(it + BUCKETS - 1) % BUCKETS] + 0.5 * weight[it] + 0.25 * weight[(it + 1) % BUCKETS] }
            val peaks = (0 until BUCKETS)
                .filter { smooth[it] > 0.02 * total && smooth[it] >= smooth[(it + BUCKETS - 1) % BUCKETS] && smooth[it] >= smooth[(it + 1) % BUCKETS] }
                .sortedByDescending { smooth[it] }
            val clusters = ArrayList<Cluster>()
            val taken = BooleanArray(BUCKETS)
            for (pk in peaks) {
                var w = 0.0
                var s = 0.0
                var v = 0.0
                var sn = 0.0
                var cs = 0.0
                for (d in -2..2) {
                    val k = (pk + d + BUCKETS) % BUCKETS
                    if (taken[k]) continue
                    taken[k] = true
                    w += weight[k]
                    s += sumS[k]
                    v += sumV[k]
                    sn += sumSin[k]
                    cs += sumCos[k]
                }
                if (w <= 0) continue
                val hue = (Math.toDegrees(Math.atan2(sn, cs)) + 360.0) % 360.0
                if (clusters.any { Colors.hueDistance(it.hue, hue) < 30.0 }) continue
                clusters.add(Cluster(hue, w / total, s / w, v / w))
                if (clusters.size == 4) break
            }
            return CoverHues(clusters.sortedByDescending { it.share }, false)
        }

        /** Below this share of colour weight per pixel a cover counts as grey. */
        const val GREY_SHARE = 0.02
    }
}
