package io.github.matijasalej.wholeflat

import io.github.matijasalej.wholeflat.sync.BoxLoad
import java.io.File

/** What the box is doing, in a line, for the log lines that tell about a stall: the load, the memory, the garbage collector. */
object BoxStats {
    /** The count and the time (ms) of the garbage collections that held the app up, since it started; null if the runtime does not say. */
    fun blockingGc(): Pair<Long, Long>? = try {
        val count = android.os.Debug.getRuntimeStat("art.gc.blocking-gc-count").trim().toLong()
        val time = android.os.Debug.getRuntimeStat("art.gc.blocking-gc-time").trim().toLong()
        Pair(count, time)
    } catch (t: Throwable) {
        null
    }

    /** "load 2.1/1.5/1.2, 345 MB available, 120 MB swap free" (whatever can be read). */
    fun brief(): String {
        val parts = ArrayList<String>()
        try {
            BoxLoad.parseLoadavg(File("/proc/loadavg").readText())?.let { l ->
                parts.add("load " + l.joinToString("/") { String.format(java.util.Locale.US, "%.1f", it) })
            }
        } catch (t: Throwable) {
            // not readable
        }
        try {
            val m = File("/proc/meminfo").readText()
            BoxLoad.meminfoMb(m, "MemAvailable")?.let { parts.add("$it MB available") }
            BoxLoad.meminfoMb(m, "SwapFree")?.let { parts.add("$it MB swap free") }
        } catch (t: Throwable) {
            // not readable
        }
        return if (parts.isEmpty()) "no load figures" else parts.joinToString(", ")
    }
}
