package io.github.matijasalej.wholeflat

import android.app.ActivityManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings as AndroidSettings
import io.github.matijasalej.wholeflat.sync.BackgroundPolicy
import io.github.matijasalej.wholeflat.sync.BoxLoad
import org.json.JSONObject
import java.io.File

/**
 * Frees the box of work it does not need while the music runs: every installed app except the ones the box stands on (see
 * [BackgroundPolicy]) is asked to give up its background processes (`ActivityManager.killBackgroundProcesses`, the normal permission
 * `KILL_BACKGROUND_PROCESSES`; the system kills only what is in the background and starts it again when it is wanted), at each grab of the
 * TV and every ten minutes while the music plays, and the screensaver is switched off where the grant `WRITE_SECURE_SETTINGS` allows it
 * (`adb shell pm grant io.github.matijasalej.wholeflat android.permission.WRITE_SECURE_SETTINGS`, once). What was freed is in the log.
 */
class BackgroundCleaner(private val context: Context, private val tv: TvControl) {
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    var lastRunMs = 0L
        private set

    @Volatile
    private var lastSummary = ""

    @Volatile
    private var runs = 0

    @Volatile
    private var lastAsked = 0

    // the apps that were asked at the last run, and when (wall clock), for the watch for those that come back
    private var asked: Set<String> = emptySet()
    private var askedAtWallMs = 0L
    private var lastWatchMs = 0L
    private val comebacks = HashMap<String, Int>()

    @Volatile
    private var lastComeback = ""

    /** Asks the apps in the background to give up their processes; [why] is for the log. */
    fun run(why: String) {
        if (!Settings.values.backgroundCleanup) return
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val installed = try {
            context.packageManager.getInstalledApplications(0).map { it.packageName }
        } catch (t: Throwable) {
            AppLog.add(TAG, "cleanup ($why): the apps cannot be listed ($t)")
            return
        }
        ensureScreensaverOff()
        val victims = BackgroundPolicy.victims(installed, context.packageName, tv.launcherPackages(), tv.foregroundPackage())
        val before = memory()
        var failed = 0
        for (p in victims) {
            try {
                am.killBackgroundProcesses(p)
            } catch (t: Throwable) {
                failed++
            }
        }
        runs++
        lastRunMs = android.os.SystemClock.elapsedRealtime()
        lastAsked = victims.size
        asked = victims.toHashSet()
        askedAtWallMs = System.currentTimeMillis()
        // the system takes a moment to give the memory back
        main.postDelayed({
            val after = memory()
            lastSummary = "asked ${victims.size} of ${installed.size} apps${if (failed > 0) " ($failed refused)" else ""}; available memory ${before.first} -> ${after.first} MB, swap free ${before.second} -> ${after.second} MB"
            AppLog.add(TAG, "cleanup ($why): $lastSummary")
        }, 2_000L)
    }

    /**
     * Looks (at most every 30 s) for apps that were asked to stop and have done something since (an activity resumed, a foreground service
     * started: usage events), and asks them again at once; the log says which came back, how long after it was stopped, and the count. A
     * foreground service is not a background process, so `killBackgroundProcesses` does not end it: the log then says what does
     * (`adb shell cmd appops set <package> RUN_ANY_IN_BACKGROUND ignore`, or `am force-stop`).
     */
    fun watch() {
        if (!Settings.values.backgroundCleanup || asked.isEmpty()) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastWatchMs < WATCH_EVERY_MS) return
        lastWatchMs = now
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return
        val wall = System.currentTimeMillis()
        val events = try {
            usm.queryEvents(askedAtWallMs, wall)
        } catch (t: Throwable) {
            null
        } ?: return
        val e = UsageEvents.Event()
        val back = LinkedHashMap<String, Int>()
        val times = HashMap<String, Long>()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val pkg = e.packageName ?: continue
            if (pkg !in asked) continue
            if (e.eventType == EVENT_ACTIVITY_RESUMED || e.eventType == EVENT_FOREGROUND_SERVICE_START) {
                back[pkg] = e.eventType
                times[pkg] = e.timeStamp
            }
        }
        if (back.isEmpty()) return
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        for ((pkg, type) in back) {
            val n = (comebacks[pkg] ?: 0) + 1
            comebacks[pkg] = n
            try {
                am.killBackgroundProcesses(pkg)
            } catch (t: Throwable) {
                // refused
            }
            val after = ((times[pkg] ?: wall) - askedAtWallMs) / 1000
            lastComeback = "$pkg came back ${after} s after it was stopped (${if (type == EVENT_FOREGROUND_SERVICE_START) "a foreground service started" else "an activity resumed"}, #$n)"
            AppLog.add(
                TAG,
                "cleanup: $lastComeback: asked to stop again" +
                    if (type == EVENT_FOREGROUND_SERVICE_START) "; a foreground service is not a background process, so this may not end it: adb shell cmd appops set $pkg RUN_ANY_IN_BACKGROUND ignore (or am force-stop $pkg)" else "",
            )
        }
        // what came back has been dealt with; the next look is from now on
        askedAtWallMs = wall
    }

    /** Switches the screensaver off if it is on and the grant is there; returns what happened. */
    fun ensureScreensaverOff(): String {
        val resolver = context.contentResolver
        val enabled = try {
            AndroidSettings.Secure.getInt(resolver, SCREENSAVER, 1)
        } catch (t: Throwable) {
            return "unknown ($t)"
        }
        if (enabled == 0) return "off"
        if (context.checkSelfPermission(WRITE_SECURE) != PackageManager.PERMISSION_GRANTED) {
            if (!warnedGrant) {
                warnedGrant = true
                AppLog.add(TAG, "the screensaver is on and cannot be switched off: adb shell pm grant ${context.packageName} $WRITE_SECURE")
            }
            return "on (WRITE_SECURE_SETTINGS is not granted: adb shell pm grant ${context.packageName} $WRITE_SECURE)"
        }
        return try {
            AndroidSettings.Secure.putInt(resolver, SCREENSAVER, 0)
            AppLog.add(TAG, "the screensaver is switched off")
            "switched off by the app"
        } catch (t: Throwable) {
            "on (could not be switched off: $t)"
        }
    }

    private var warnedGrant = false

    /** Available memory and free swap now (MB). */
    private fun memory(): Pair<Long, Long> {
        val text = try {
            File("/proc/meminfo").readText()
        } catch (t: Throwable) {
            ""
        }
        return Pair(BoxLoad.meminfoMb(text, "MemAvailable") ?: -1L, BoxLoad.meminfoMb(text, "SwapFree") ?: -1L)
    }

    fun statusJson(): JSONObject {
        val o = JSONObject()
        o.put("enabled", Settings.values.backgroundCleanup)
        o.put("runs", runs)
        o.put("last_run_s_ago", if (lastRunMs == 0L) JSONObject.NULL else (android.os.SystemClock.elapsedRealtime() - lastRunMs) / 1000)
        o.put("last_asked", lastAsked)
        o.put("last_summary", lastSummary.ifEmpty { JSONObject.NULL })
        o.put("comebacks", JSONObject(comebacks as Map<*, *>))
        o.put("last_comeback", lastComeback.ifEmpty { JSONObject.NULL })
        o.put("write_secure_settings", context.checkSelfPermission(WRITE_SECURE) == PackageManager.PERMISSION_GRANTED)
        o.put("screensaver", ensureScreensaverNote())
        return o
    }

    /** The state of the screensaver without changing it. */
    private fun ensureScreensaverNote(): Any = try {
        if (AndroidSettings.Secure.getInt(context.contentResolver, SCREENSAVER, 1) == 0) "off" else "on"
    } catch (t: Throwable) {
        JSONObject.NULL
    }

    private companion object {
        const val TAG = "cleanup"
        const val WATCH_EVERY_MS = 30_000L

        /** UsageEvents.Event types: ACTIVITY_RESUMED (1) and FOREGROUND_SERVICE_START (19, Android 10). */
        const val EVENT_ACTIVITY_RESUMED = 1
        const val EVENT_FOREGROUND_SERVICE_START = 19
        const val SCREENSAVER = "screensaver_enabled"
        const val WRITE_SECURE = "android.permission.WRITE_SECURE_SETTINGS"
    }
}
