package io.github.matijasalej.wholeflat

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import org.json.JSONObject
import java.io.File

/**
 * How full the Google TV's storage is. The box is small, and the Spotify app's own cache has filled it
 * to 97% once, which made the installation of this app fail. This app keeps its own use minimal (no audio
 * cache, the log lives in memory); here it reports the free space of /data and what it uses itself, and
 * warns below [LOW_MB].
 */
object Storage {
    /** The warning level: free space below this is called low. */
    const val LOW_MB = 300L
    private const val TAG = "storage"
    private const val REFRESH_MS = 10_000L

    class Info(val freeBytes: Long, val totalBytes: Long, val ownBytes: Long) {
        val freeMb: Long get() = freeBytes / MB
        val totalMb: Long get() = totalBytes / MB
        val low: Boolean get() = freeBytes in 0 until LOW_MB * MB
    }

    private const val MB = 1024L * 1024L

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var cached: Info? = null

    @Volatile
    private var cachedAt = 0L

    @Volatile
    private var wasLow = false

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** The free and total space of /data and the size of this app's files and cache. Cached for 10 s. */
    fun info(): Info {
        val now = SystemClock.elapsedRealtime()
        val c = cached
        if (c != null && now - cachedAt < REFRESH_MS) return c
        val fresh = read()
        cached = fresh
        cachedAt = now
        if (fresh.low && !wasLow) {
            AppLog.add(
                TAG,
                "only ${fresh.freeMb} MB free on /data (warning below $LOW_MB MB): clear the cache of other apps, " +
                    "Spotify's fills this box",
            )
        } else if (!fresh.low && wasLow) {
            AppLog.add(TAG, "${fresh.freeMb} MB free on /data again")
        }
        wasLow = fresh.low
        return fresh
    }

    private fun read(): Info {
        var free = -1L
        var total = -1L
        try {
            val stat = StatFs(Environment.getDataDirectory().path)
            free = stat.availableBytes
            total = stat.totalBytes
        } catch (e: Exception) {
            // the numbers stay unknown
        }
        var own = 0L
        val context = appContext
        if (context != null) {
            own += sizeOf(context.filesDir)
            own += sizeOf(context.cacheDir)
        }
        return Info(free, total, own)
    }

    /** Bytes of a directory tree (symbolic links are not followed). */
    fun sizeOf(file: File?): Long {
        if (file == null || !file.exists()) return 0L
        if (!file.isDirectory) return file.length()
        var sum = 0L
        val children = file.listFiles() ?: return 0L
        for (child in children) sum += sizeOf(child)
        return sum
    }

    fun toJson(): JSONObject {
        val i = info()
        val o = JSONObject()
        o.put("data_free_mb", if (i.freeBytes < 0) JSONObject.NULL else i.freeMb)
        o.put("data_total_mb", if (i.totalBytes < 0) JSONObject.NULL else i.totalMb)
        o.put("low", i.low)
        o.put("warn_below_mb", LOW_MB)
        o.put("own_kb", i.ownBytes / 1024)
        return o
    }

    /** One line for the status screen, with the warning when it is low. */
    fun text(): String {
        val i = info()
        if (i.freeBytes < 0) return "unknown"
        val size = "${format(i.freeBytes)} free of ${format(i.totalBytes)}"
        return if (i.low) {
            "LOW, $size. Clear the cache of other apps (Spotify's fills this box) or the install of updates fails."
        } else {
            size
        }
    }

    private fun format(bytes: Long): String =
        if (bytes >= 1024L * MB) String.format(java.util.Locale.US, "%.1f GB", bytes / (1024.0 * MB)) else "${bytes / MB} MB"
}
