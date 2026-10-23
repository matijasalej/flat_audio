package io.github.matijasalej.wholeflat.sync

/**
 * How late things were in a period (a thread that woke up after the time it asked for, a write that came after its due time): how many,
 * the average, the worst, and how many were later than [overMs]. [take] gives the period and starts the next; [worstMs] is the worst
 * since the start. Thread safe. Plain Kotlin.
 */
class Lateness(private val overMs: Double = 50.0) {
    class Snapshot(val count: Int, val avgMs: Double, val maxMs: Double, val over: Int)

    private var count = 0
    private var sum = 0.0
    private var max = 0.0
    private var over = 0

    /** The worst lateness since the start (ms). */
    @Volatile
    var worstMs = 0.0
        private set

    @Synchronized
    fun note(ms: Double) {
        val v = maxOf(0.0, ms)
        count++
        sum += v
        if (v > max) max = v
        if (v > overMs) over++
        if (v > worstMs) worstMs = v
    }

    @Synchronized
    fun take(): Snapshot {
        val s = Snapshot(count, if (count == 0) 0.0 else sum / count, max, over)
        count = 0
        sum = 0.0
        max = 0.0
        over = 0
        return s
    }
}
