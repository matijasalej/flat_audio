package io.github.matijasalej.wholeflat.capture

/**
 * The lag of the kitchen behind the captured player: kitchen sound time minus capture time, one value per
 * reading of the Sonos position. The first [samples] readings of a session are only measured (the median is
 * the lag this session settled at, and the target the controller then holds constant against the Sonos's
 * drift); [recent] is the median of the last readings, for the status.
 */
class LagTracker(private val samples: Int = 11) {
    private val first = ArrayList<Double>()
    private val last = ArrayDeque<Double>()

    /** The lag the session settled at (ms), null while it is still being measured. */
    var locked: Double? = null
        private set

    @Synchronized
    fun reset() {
        first.clear()
        last.clear()
        locked = null
    }

    /** Adds a reading. Returns the locked lag once, when the [samples]-th reading came in. */
    @Synchronized
    fun add(lagMs: Double): Double? {
        last.addLast(lagMs)
        while (last.size > samples) last.removeFirst()
        if (locked != null) return null
        first.add(lagMs)
        if (first.size < samples) return null
        val m = median(first)
        locked = m
        return m
    }

    /** The median of the last readings, NaN before the first. */
    @Synchronized
    fun recent(): Double = if (last.isEmpty()) Double.NaN else median(last.toList())

    val count: Int
        @Synchronized get() = first.size

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    }
}
