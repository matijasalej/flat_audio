package io.github.matijasalej.wholeflat.sync

/**
 * Reads the TV's position (UPnP `GetPositionInfo`: RelTime, AbsTime, RelCount, AbsCount) now and then and says whether its player keeps
 * time: if the position of the item has advanced less than the wall clock did between two readings, the player stood still (paused,
 * rebuffered) for the difference, and its sound is that much later than the other room's. The write-block detector cannot see a pause where
 * the TV keeps reading; this can, to the resolution of the counter (whole seconds if the TV only gives those, and then only a pause of
 * more than about 2.5 s counts). Plain Kotlin.
 */
class PositionProbe(private val minPauseMs: () -> Double) {
    /** What one reading said. */
    class Reading(
        val wallMs: Long,
        val relMs: Long?,
        val absMs: Long?,
        val relCount: Long?,
        val absCount: Long?,
        /** Wall time and position time since the reading before it (ms), null for the first or after a restart of the position. */
        val dWallMs: Long?,
        val dRelMs: Long?,
        /** What the wall clock advanced more than the position: positive means the player stood still for that long. */
        val driftMs: Long?,
        /** Whether the position advanced at all since the reading before (null when that cannot be said). */
        val advancing: Boolean?,
        /** The standstill that is counted as a pause (ms), 0 if none. */
        val pauseMs: Long,
    )

    private var prevWall = 0L
    private var prevRel: Long? = null
    private var prevCount: Long? = null
    private var wholeSeconds = true
    private var readings = 0

    /** True while every position seen so far was a whole number of seconds. */
    val coarse: Boolean get() = wholeSeconds

    @Synchronized
    fun reset() {
        prevRel = null
        prevCount = null
        wholeSeconds = true
        readings = 0
    }

    /** One reading, taken at [wallMs] (any steady clock, ms), with the texts the TV gave. */
    @Synchronized
    fun add(wallMs: Long, relTime: String?, absTime: String?, relCount: String?, absCount: String?): Reading {
        val rel = parseTime(relTime)
        val abs = parseTime(absTime)
        val rc = relCount?.trim()?.toLongOrNull()
        val ac = absCount?.trim()?.toLongOrNull()
        if (rel != null && rel % 1000L != 0L) wholeSeconds = false
        readings++
        var dWall: Long? = null
        var dRel: Long? = null
        var drift: Long? = null
        var advancing: Boolean? = null
        val pr = prevRel
        if (rel != null && pr != null && rel >= pr) {
            dWall = wallMs - prevWall
            dRel = rel - pr
            drift = dWall - dRel
            advancing = dRel > 0
        } else if (rel == null && rc != null && prevCount != null) {
            advancing = rc != prevCount
        }
        prevWall = wallMs
        prevRel = rel
        prevCount = rc
        val threshold = maxOf(minPauseMs(), if (wholeSeconds) 2_500.0 else 100.0)
        val pause = if (drift != null && dWall != null && drift >= threshold) drift else 0L
        return Reading(wallMs, rel, abs, rc, ac, dWall, dRel, drift, advancing, pause)
    }

    companion object {
        /** "H:MM:SS" or "H:MM:SS.fff" in ms; null for "NOT_IMPLEMENTED", empty or anything else. */
        fun parseTime(text: String?): Long? {
            val t = text?.trim() ?: return null
            val m = Regex("^([+-]?)(\\d+):(\\d{2}):(\\d{2})(?:\\.(\\d{1,3}))?").find(t) ?: return null
            val h = m.groupValues[2].toLong()
            val min = m.groupValues[3].toLong()
            val sec = m.groupValues[4].toLong()
            val frac = m.groupValues[5].padEnd(3, '0').take(3).toLong()
            val ms = ((h * 60 + min) * 60 + sec) * 1000 + frac
            return if (m.groupValues[1] == "-") -ms else ms
        }
    }
}
