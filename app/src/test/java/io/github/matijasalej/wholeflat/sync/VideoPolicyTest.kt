package io.github.matijasalej.wholeflat.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPolicyTest {
    private fun ev(type: Int, pkg: String, t: Long) = VideoPolicy.Event(type, pkg, t)

    @Test
    fun theLastResumedAppIsInFront() {
        val events = listOf(
            ev(VideoPolicy.RESUMED, "com.google.android.apps.tv.launcherx", 1),
            ev(VideoPolicy.PAUSED, "com.google.android.apps.tv.launcherx", 5),
            ev(VideoPolicy.RESUMED, "com.netflix.ninja", 5),
        )
        assertEquals("com.netflix.ninja", VideoPolicy.foreground(events))
    }

    @Test
    fun anAppThatWentToTheBackgroundIsNotInFront() {
        val events = listOf(
            ev(VideoPolicy.RESUMED, "com.netflix.ninja", 1),
            ev(VideoPolicy.PAUSED, "com.netflix.ninja", 9),
        )
        assertNull(VideoPolicy.foreground(events))
    }

    @Test
    fun aLateStopOfTheOldAppDoesNotHideTheNewOne() {
        val events = listOf(
            ev(VideoPolicy.RESUMED, "com.netflix.ninja", 1),
            ev(VideoPolicy.RESUMED, "com.google.android.youtube.tv", 5),
            ev(VideoPolicy.PAUSED, "com.netflix.ninja", 6),
        )
        assertEquals("com.google.android.youtube.tv", VideoPolicy.foreground(events))
    }

    @Test
    fun eventsAreTakenInTimeOrderWhateverTheListOrder() {
        val events = listOf(
            ev(VideoPolicy.PAUSED, "a", 9),
            ev(VideoPolicy.RESUMED, "a", 1),
            ev(VideoPolicy.RESUMED, "b", 10),
        )
        assertEquals("b", VideoPolicy.foreground(events))
    }

    @Test
    fun noEventsNoForeground() {
        assertNull(VideoPolicy.foreground(emptyList()))
    }

    @Test
    fun launchersOurAppAndScreenSaversAreNotVideo() {
        val own = "io.github.matijasalej.wholeflat"
        assertFalse(VideoPolicy.isVideo(null, own))
        assertFalse(VideoPolicy.isVideo(own, own))
        assertFalse(VideoPolicy.isVideo("com.google.android.apps.tv.launcherx", own))
        assertFalse(VideoPolicy.isVideo("com.google.android.apps.tv.dreamx", own))
        assertFalse(VideoPolicy.isVideo("com.android.tv.settings", own))
        assertFalse(VideoPolicy.isVideo("com.vendor.launcher", own, setOf("com.vendor.launcher")))
    }

    @Test
    fun everythingElseIsVideo() {
        val own = "io.github.matijasalej.wholeflat"
        assertTrue(VideoPolicy.isVideo("com.netflix.ninja", own))
        assertTrue(VideoPolicy.isVideo("com.google.android.youtube.tv", own))
        assertTrue(VideoPolicy.isVideo("org.videolan.vlc", own))
    }
}
