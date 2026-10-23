package io.github.matijasalej.wholeflat

import io.github.matijasalej.wholeflat.sync.SwapWatch
import org.json.JSONObject
import java.io.File

/**
 * Reads the swap counters of `/proc/vmstat` every 5 s and puts a WARNING in the log when the box swaps more than 300 pages a second (zram,
 * on the Chromecast): everything on the box stalls then (the writer wakes up late, the encoder needs 400 ms for a picture), and the log line
 * is the time to put beside the other warnings. See [SwapWatch].
 */
class SwapMonitor {
    private val watch = SwapWatch(WARN_PAGES_PER_S)
    private var lastMs = 0L
    private var readable = true

    @Volatile
    private var lastLine = ""

    /** Called every second or so; reads when 5 s have passed since the last reading. */
    fun tick(nowMs: Long) {
        if (!readable || nowMs - lastMs < EVERY_MS) return
        lastMs = nowMs
        val counters = try {
            SwapWatch.parseVmstat(File("/proc/vmstat").readText())
        } catch (t: Throwable) {
            readable = false
            AppLog.add(TAG, "/proc/vmstat cannot be read ($t): swap bursts are not logged")
            return
        }
        if (counters == null) {
            readable = false
            AppLog.add(TAG, "/proc/vmstat has no pswpin/pswpout: swap bursts are not logged")
            return
        }
        val r = watch.add(nowMs, counters.first, counters.second) ?: return
        lastLine = String.format(java.util.Locale.US, "%.0f pages/s out, %.0f in", r.outPerS, r.inPerS)
        if (r.burst) AppLog.add(TAG, "WARNING swap burst: $lastLine (${BoxStats.brief()}); burst ${watch.bursts} so far")
    }

    fun statusJson(): JSONObject {
        val o = JSONObject()
        o.put("readable", readable)
        o.put("bursts", watch.bursts)
        o.put("worst_pages_per_s", Math.round(watch.worstPerS))
        o.put("last", lastLine.ifEmpty { JSONObject.NULL })
        o.put("last_burst_s_ago", if (watch.lastBurstAtMs == 0L) JSONObject.NULL else (android.os.SystemClock.elapsedRealtime() - watch.lastBurstAtMs) / 1000)
        return o
    }

    private companion object {
        const val TAG = "swap"
        const val EVERY_MS = 5_000L
        const val WARN_PAGES_PER_S = 300.0
    }
}
