package io.github.matijasalej.wholeflat.sync

/**
 * Which apps are asked to give up their background processes while the music runs (a Chromecast with 2 GB: the screensaver, a streaming
 * app that was left in the background and a handful of others took half of the four cores and all of the swap, and the lyrics video and
 * the capture are then late). The system keeps what it needs: only processes in the background are ever killed, and they are restarted
 * on demand. Nothing is asked of the app itself, of the two players whose audio is captured, of Google Play services, of the home
 * screen and the system UI, of the voice search and the remote service, of the providers, and of what is in front. Plain Kotlin.
 */
object BackgroundPolicy {
    /** Packages that are left alone (whole names). */
    private val KEEP = setOf(
        "android",
        "com.android.systemui",
        "com.spotify.tv.android",
        "com.google.android.apps.mediashell",
        "com.google.android.katniss",
        "com.google.android.tv.remote.service",
    )

    /** Packages that are left alone (beginnings of names): services of the system that others stand on, and the home screen and input methods. */
    private val KEEP_PREFIX = listOf(
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.google.android.katniss",
        "com.google.android.tvlauncher",
        "com.google.android.apps.tv.launcherx",
        "com.google.android.tv.remote",
        "com.google.android.tv.providers",
        "com.android.providers.",
        "com.android.inputmethod",
        "com.google.android.inputmethod",
        "com.android.bluetooth",
        "com.android.shell",
        "com.android.settings",
        "com.android.vending",
    )

    /** The packages of [installed] that are asked, in the order they were given. */
    fun victims(installed: Collection<String>, own: String, launchers: Set<String>, foreground: String?): List<String> =
        installed.filter { p ->
            p != own &&
                p != foreground &&
                p !in launchers &&
                p !in KEEP &&
                !p.contains("launcher", ignoreCase = true) &&
                !p.contains(".providers") &&
                KEEP_PREFIX.none { p.startsWith(it) }
        }
}

/** What `/proc/loadavg` and `/proc/meminfo` say, for the status of the box. */
object BoxLoad {
    /** The three load averages of the text of `/proc/loadavg`; null if it is not that. */
    fun parseLoadavg(text: String): List<Double>? {
        val parts = text.trim().split(Regex("\\s+"))
        if (parts.size < 3) return null
        val v = parts.take(3).map { it.toDoubleOrNull() ?: return null }
        return v
    }

    /** A value of `/proc/meminfo` in megabytes (kB / 1024), null if the text has no such line. */
    fun meminfoMb(text: String, key: String): Long? {
        for (line in text.lineSequence()) {
            if (!line.startsWith("$key:")) continue
            val kb = line.substring(key.length + 1).trim().split(Regex("\\s+")).firstOrNull()?.toLongOrNull() ?: return null
            return kb / 1024
        }
        return null
    }
}
