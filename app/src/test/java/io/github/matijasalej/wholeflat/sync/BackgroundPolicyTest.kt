package io.github.matijasalej.wholeflat.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundPolicyTest {
    private val own = "io.github.matijasalej.wholeflat"
    private val installed = listOf(
        own,
        "com.spotify.tv.android",
        "com.google.android.apps.mediashell",
        "com.google.android.gms",
        "com.google.android.gms.supervision",
        "com.google.android.apps.tv.launcherx",
        "com.google.android.tvlauncher",
        "com.android.systemui",
        "com.google.android.katniss",
        "com.google.android.tv.remote.service",
        "com.android.providers.media",
        "com.google.android.tv.providers.something",
        "android",
        "com.google.android.apps.tv.dreamx",
        "tv.twitch.android.app",
        "dk.dr.tvplayer",
        "com.apple.atve.androidtv.appletv",
        "com.wbd.stream",
        "com.netflix.ninja",
    )

    @Test
    fun theAppsOfTheBackgroundAreAskedAndWhatTheBoxStandsOnIsNot() {
        val v = BackgroundPolicy.victims(installed, own, setOf("com.google.android.apps.tv.launcherx"), foreground = "com.netflix.ninja")
        assertEquals(
            listOf("com.google.android.apps.tv.dreamx", "tv.twitch.android.app", "dk.dr.tvplayer", "com.apple.atve.androidtv.appletv", "com.wbd.stream"),
            v,
        )
    }

    @Test
    fun whatIsInFrontIsAskedWhenItIsNotInFrontAnyMore() {
        val v = BackgroundPolicy.victims(installed, own, emptySet(), foreground = null)
        assertTrue(v.contains("com.netflix.ninja"))
        assertTrue(!v.contains(own))
    }

    @Test
    fun theHomeScreenIsKeptWhateverItIsCalled() {
        val v = BackgroundPolicy.victims(listOf("com.some.oem.homescreen", "com.vendor.launcher3"), own, setOf("com.some.oem.homescreen"), null)
        assertEquals(emptyList<String>(), v)
    }

    @Test
    fun theLoadAveragesAndTheMemoryAreReadFromTheProcFiles() {
        assertEquals(listOf(2.5, 2.1, 1.9), BoxLoad.parseLoadavg("2.50 2.10 1.90 3/1042 28111\n"))
        assertNull(BoxLoad.parseLoadavg(""))
        assertNull(BoxLoad.parseLoadavg("a b c"))
        val mem = "MemTotal:        1887236 kB\nMemFree:           52000 kB\nMemAvailable:     555456 kB\nSwapTotal:        524284 kB\nSwapFree:          53248 kB\n"
        assertEquals(542L, BoxLoad.meminfoMb(mem, "MemAvailable"))
        assertEquals(52L, BoxLoad.meminfoMb(mem, "SwapFree"))
        assertEquals(1843L, BoxLoad.meminfoMb(mem, "MemTotal"))
        assertNull(BoxLoad.meminfoMb(mem, "Nothing"))
    }
}
