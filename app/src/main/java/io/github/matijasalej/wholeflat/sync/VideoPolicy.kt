package io.github.matijasalej.wholeflat.sync

/**
 * Decides whether a video app is in front of the TV, from the usage events Android keeps: when it is,
 * the screen must not be switched off. Anything that is not our app, the launcher, the screen saver or
 * a settings screen counts as video.
 */
object VideoPolicy {
    /** Values of `UsageEvents.Event`: ACTIVITY_RESUMED (= MOVE_TO_FOREGROUND), ACTIVITY_PAUSED (= MOVE_TO_BACKGROUND), ACTIVITY_STOPPED. */
    const val RESUMED = 1
    const val PAUSED = 2
    const val STOPPED = 23

    class Event(val type: Int, val pkg: String, val time: Long)

    /** Packages that are not video: launchers, screen savers, system UI and settings of Android TV and Google TV. */
    val QUIET_PACKAGES: Set<String> = setOf(
        "com.google.android.apps.tv.launcherx",
        "com.google.android.tvlauncher",
        "com.google.android.leanbacklauncher",
        "com.google.android.apps.tv.dreamx",
        "com.google.android.backdrop",
        "com.android.systemui",
        "com.android.tv.settings",
        "com.google.android.tvrecommendations",
        "com.google.android.katniss",
        "com.google.android.apps.tv.setup",
        "com.android.settings",
    )

    /** The package in the foreground after the last of [events], or null if none is. */
    fun foreground(events: List<Event>): String? {
        var current: String? = null
        for (e in events.sortedBy { it.time }) {
            when (e.type) {
                RESUMED -> current = e.pkg
                PAUSED, STOPPED -> if (current == e.pkg) current = null
            }
        }
        return current
    }

    fun isVideo(foreground: String?, ownPackage: String, extraQuiet: Set<String> = emptySet()): Boolean {
        if (foreground == null) return false
        if (foreground == ownPackage) return false
        return !QUIET_PACKAGES.contains(foreground) && !extraQuiet.contains(foreground)
    }
}
