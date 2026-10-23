package io.github.matijasalej.wholeflat.light

/**
 * The Ambilight of the 55PUS6753 (JointSpace `/6/ambilight/topology`): 4 zones on the left, 8 on top, 4 on the right, none at the bottom.
 * The arrays of the lights hold them in the TV's order: left 0 to 3, top 0 to 7, right 0 to 3. JointSpace numbers each side clockwise,
 * so left 0 is at the bottom, top 0 at the left and right 0 at the top, and the array runs once round the frame clockwise (the spike
 * checks this on the TV).
 *
 * [image] turns the 16 colours into a small picture in which each edge carries the colour of its zones, blended smoothly towards the
 * middle, for the painter to scale up: the TV measures its zones at the edges of what it shows. Plain Kotlin.
 */
object Zones {
    const val LEFT = 4
    const val TOP = 8
    const val RIGHT = 4
    const val COUNT = LEFT + TOP + RIGHT

    /** The JointSpace side and pixel of array index [i] ("left" 0..3, "top" 0..7, "right" 0..3). */
    fun side(i: Int): Pair<String, Int> = when {
        i < LEFT -> Pair("left", i)
        i < LEFT + TOP -> Pair("top", i - LEFT)
        else -> Pair("right", i - LEFT - TOP)
    }

    /** The array index of JointSpace [side] and [pixel], -1 for none. */
    fun index(side: String, pixel: Int): Int = when (side) {
        "left" -> if (pixel in 0 until LEFT) pixel else -1
        "top" -> if (pixel in 0 until TOP) LEFT + pixel else -1
        "right" -> if (pixel in 0 until RIGHT) LEFT + TOP + pixel else -1
        else -> -1
    }

    /**
     * Draws [zones] into [out], a [w] x [h] picture (ARGB, rows from the top). Each pixel mixes the colour of the left edge at its height,
     * the top edge at its column and the right edge at its height, by how close it is to each edge ([sharpness]: the higher, the more
     * each edge keeps to itself).
     */
    fun image(zones: IntArray, w: Int, h: Int, out: IntArray, sharpness: Double = 6.0) {
        val wts = weights(w, h, sharpness)
        val left = IntArray(h)
        val right = IntArray(h)
        val top = IntArray(w)
        for (y in 0 until h) {
            val fy = (y + 0.5) / h
            left[y] = along(zones, 0, LEFT, 1.0 - fy) // left 0 at the bottom
            right[y] = along(zones, LEFT + TOP, RIGHT, fy) // right 0 at the top
        }
        for (x in 0 until w) top[x] = along(zones, LEFT, TOP, (x + 0.5) / w)
        var i = 0
        for (y in 0 until h) {
            val l = left[y]
            val r = right[y]
            for (x in 0 until w) {
                val t = top[x]
                val wl = wts[3 * i]
                val wt = wts[3 * i + 1]
                val wr = wts[3 * i + 2]
                val red = Colors.red(l) * wl + Colors.red(t) * wt + Colors.red(r) * wr
                val green = Colors.green(l) * wl + Colors.green(t) * wt + Colors.green(r) * wr
                val blue = Colors.blue(l) * wl + Colors.blue(t) * wt + Colors.blue(r) * wr
                out[i] = Colors.rgb((red + 0.5).toInt(), (green + 0.5).toInt(), (blue + 0.5).toInt())
                i++
            }
        }
    }

    @Volatile
    private var cached: Triple<Int, Int, Double>? = null

    @Volatile
    private var cachedWeights = DoubleArray(0)

    /** The share of the left, top and right edge in every pixel (they sum to 1); worked out once per size. */
    private fun weights(w: Int, h: Int, sharpness: Double): DoubleArray {
        val key = Triple(w, h, sharpness)
        if (cached == key) return cachedWeights
        val out = DoubleArray(3 * w * h)
        var i = 0
        for (y in 0 until h) {
            val wt = Math.pow(1.0 - (y + 0.5) / h, sharpness)
            for (x in 0 until w) {
                val fx = (x + 0.5) / w
                val wl = Math.pow(1.0 - fx, sharpness)
                val wr = Math.pow(fx, sharpness)
                val sum = wl + wt + wr
                out[3 * i] = wl / sum
                out[3 * i + 1] = wt / sum
                out[3 * i + 2] = wr / sum
                i++
            }
        }
        cachedWeights = out
        cached = key
        return out
    }

    /** The colour at [pos] (0 to 1 along the side, in zone order) of the [n] zones from [from]: linear between the zone centres. */
    private fun along(zones: IntArray, from: Int, n: Int, pos: Double): Int {
        val x = pos * n - 0.5
        if (x <= 0) return zones[from]
        if (x >= n - 1) return zones[from + n - 1]
        val i = x.toInt()
        return Colors.mix(zones[from + i], zones[from + i + 1], x - i)
    }
}
