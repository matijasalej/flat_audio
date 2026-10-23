package io.github.matijasalej.wholeflat.sync

/**
 * Watches the swap of the box (zram on the Chromecast) from two counters of `/proc/vmstat`, `pswpin` and `pswpout` (pages swapped in and out
 * since boot): the rate between two readings, and a burst when it is above [warnPagesPerS]. A burst is when everything on the box stalls
 * (the writer woke up late and the encoder needed 413 ms for a picture at the same moment, with zram 95 to 99 % full), so the log names it
 * with the time to put it beside the other warnings. Plain Kotlin.
 */
class SwapWatch(private val warnPagesPerS: Double = 300.0) {
    class Result(val inPerS: Double, val outPerS: Double, val burst: Boolean)

    private var prevMs = -1L
    private var prevIn = 0L
    private var prevOut = 0L

    @Volatile
    var bursts = 0
        private set

    @Volatile
    var worstPerS = 0.0
        private set

    @Volatile
    var lastBurstAtMs = 0L
        private set

    @Volatile
    var last: Result? = null
        private set

    /** One reading at [tMs] (any steady clock) of the counters; null for the first, or if the counters went back (a reboot). */
    @Synchronized
    fun add(tMs: Long, pagesIn: Long, pagesOut: Long): Result? {
        val pm = prevMs
        val pi = prevIn
        val po = prevOut
        prevMs = tMs
        prevIn = pagesIn
        prevOut = pagesOut
        if (pm < 0 || tMs <= pm || pagesIn < pi || pagesOut < po) return null
        val dt = (tMs - pm) / 1000.0
        val inRate = (pagesIn - pi) / dt
        val outRate = (pagesOut - po) / dt
        val burst = inRate + outRate > warnPagesPerS
        if (burst) {
            bursts++
            lastBurstAtMs = tMs
        }
        if (inRate + outRate > worstPerS) worstPerS = inRate + outRate
        val r = Result(inRate, outRate, burst)
        last = r
        return r
    }

    companion object {
        /** `pswpin` and `pswpout` of the text of `/proc/vmstat`; null if either is missing. */
        fun parseVmstat(text: String): Pair<Long, Long>? {
            var pin: Long? = null
            var pout: Long? = null
            for (line in text.lineSequence()) {
                if (line.startsWith("pswpin ")) pin = line.substring(7).trim().toLongOrNull()
                else if (line.startsWith("pswpout ")) pout = line.substring(8).trim().toLongOrNull()
            }
            return if (pin != null && pout != null) Pair(pin, pout) else null
        }
    }
}
