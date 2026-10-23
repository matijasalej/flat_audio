package io.github.matijasalej.wholeflat

import android.app.ActivityManager
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
        // the system takes a moment to give the memory back
        main.postDelayed({
            val after = memory()
            lastSummary = "asked ${victims.size} of ${installed.size} apps${if (failed > 0) " ($failed refused)" else ""}; available memory ${before.first} -> ${after.first} MB, swap free ${before.second} -> ${after.second} MB"
            AppLog.add(TAG, "cleanup ($why): $lastSummary")
        }, 2_000L)
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
        const val SCREENSAVER = "screensaver_enabled"
        const val WRITE_SECURE = "android.permission.WRITE_SECURE_SETTINGS"
    }
}
