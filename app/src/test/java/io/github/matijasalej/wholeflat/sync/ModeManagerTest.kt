package io.github.matijasalej.wholeflat.sync

import io.github.matijasalej.wholeflat.sync.ModeManager.Action
import io.github.matijasalej.wholeflat.sync.ModeManager.Tv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModeManagerTest {
    private val minute = 60_000L
    private val never = Long.MAX_VALUE / 4

    private fun inputs(
        now: Long,
        mode: Mode = Mode.SYNCED,
        source: String = "capture",
        alive: Boolean = true,
        silent: Long = 0L,
        known: Boolean = true,
        tv: Tv = Tv.IDLE,
        leftCount: Int = 0,
        leftKind: ModeManager.LeftKind? = null,
        foreground: Foreground = Foreground.UNKNOWN,
        backoff: Long = 0L,
        failed: Int = 0,
    ) = ModeManager.Inputs(now, mode, source, alive, silent, known, tv, leftCount, leftKind, foreground, backoff, failed)

    private fun List<Action>.grabs() = count { it is Action.Grab }
    private fun List<Action>.releases() = filterIsInstance<Action.Release>()
    private fun List<Action>.modes() = filterIsInstance<Action.SetMode>()

    /** A manager that has seen the mode once, long ago, so that "just asked" is over. */
    private fun settled(): ModeManager {
        val m = ModeManager()
        m.decide(inputs(0L, silent = never))
        return m
    }

    // ---- taking the TV ---------------------------------------------------------------------------

    @Test
    fun theTvIsGrabbedWhenMusicStartsInSyncedMode() {
        val m = settled()
        val a = m.decide(inputs(60_000L, silent = 0L))
        assertEquals(1, a.grabs())
        assertTrue(a.releases().isEmpty())
    }

    @Test
    fun nothingIsGrabbedWhileItIsQuiet() {
        val m = settled()
        assertTrue(m.decide(inputs(60_000L, silent = 10 * minute)).isEmpty())
        // a second of silence is still the music
        assertEquals(1, m.decide(inputs(61_000L, silent = 1_000L)).grabs())
    }

    @Test
    fun theFirstLookAtAppStartDoesNotGrabTheTvForNothing() {
        val m = ModeManager()
        assertTrue(m.decide(inputs(1_000L, silent = never)).isEmpty())
        assertTrue(m.decide(inputs(2_000L, silent = never)).isEmpty())
    }

    @Test
    fun whenTheUserAsksForSyncedTheTvIsGrabbedEvenIfNothingPlays() {
        val m = settled()
        m.decide(inputs(10_000L, mode = Mode.LYRICS, silent = 2 * minute))
        val a = m.decide(inputs(20_000L, mode = Mode.SYNCED, silent = 2 * minute))
        assertEquals(1, a.grabs())
        // but not for ever: 20 s later with nothing playing it is not asked again
        assertTrue(m.decide(inputs(40_000L, mode = Mode.SYNCED, silent = 2 * minute + 20_000L, tv = Tv.IDLE)).grabs() == 0)
    }

    @Test
    fun noGrabInLyricsModeWithoutAnAddressOrWithWholeFlatPlayingOrWithoutCapture() {
        val m = settled()
        assertTrue(m.decide(inputs(60_000L, mode = Mode.LYRICS)).isEmpty())
        assertTrue(m.decide(inputs(61_000L, known = false)).isEmpty())
        assertTrue(m.decide(inputs(62_000L, source = "librespot")).isEmpty())
        assertTrue(m.decide(inputs(63_000L, alive = false)).isEmpty())
    }

    @Test
    fun aBackOffHoldsTheGrab() {
        val m = settled()
        assertTrue(m.decide(inputs(60_000L, backoff = 12_000L, tv = Tv.FAILED)).isEmpty())
        assertEquals(1, m.decide(inputs(72_000L, backoff = 0L, tv = Tv.FAILED)).grabs())
    }

    // ---- keeping it and giving it back ---------------------------------------------------------------

    @Test
    fun theTvKeepsPlayingThroughPausesAndSkipsAndGoesBackAfterThirtyMinutesOfSilence() {
        val m = settled()
        // the capture says idle after a minute of silence, but the TV stays
        assertTrue(m.decide(inputs(5 * minute, source = "idle", silent = 4 * minute, tv = Tv.PLAYING)).isEmpty())
        assertTrue(m.decide(inputs(25 * minute, source = "idle", silent = 24 * minute, tv = Tv.PLAYING)).isEmpty())
        val a = m.decide(inputs(32 * minute, source = "idle", silent = 31 * minute, tv = Tv.PLAYING))
        assertEquals(1, a.releases().size)
        assertTrue(a.releases()[0].why, a.releases()[0].why.contains("30 minutes"))
        // and it is not taken again by the silence
        assertTrue(m.decide(inputs(33 * minute, source = "idle", silent = 32 * minute, tv = Tv.IDLE)).isEmpty())
    }

    @Test
    fun wholeFlatTheLostCaptureAndLyricsModeGiveTheTvBack() {
        for ((name, i) in listOf(
            "librespot" to inputs(1L, source = "librespot", tv = Tv.PLAYING),
            "no capture" to inputs(1L, alive = false, tv = Tv.PLAYING),
            "lyrics" to inputs(1L, mode = Mode.LYRICS, tv = Tv.PLAYING),
            "starting" to inputs(1L, source = "librespot", tv = Tv.STARTING),
        )) {
            val a = settled().decide(i)
            assertEquals(name, 1, a.releases().size)
            assertEquals(name, 0, a.grabs())
        }
    }

    // ---- the TV leaves its player --------------------------------------------------------------------

    @Test
    fun leavingWithTheLauncherSpotifyOrThisAppInFrontIsTheUserLeavingAndTheModeBecomesLyrics() {
        for (fg in listOf(Foreground.LAUNCHER, Foreground.SPOTIFY, Foreground.OWN_APP)) {
            val m = settled()
            val a = m.decide(inputs(60_000L, tv = Tv.LEFT, leftCount = 1, leftKind = ModeManager.LeftKind.STOPPED, foreground = fg))
            assertEquals(fg.name, 1, a.modes().size)
            assertEquals(Mode.LYRICS, a.modes()[0].mode)
            assertTrue(a.modes()[0].why, a.modes()[0].why.contains(fg.name.lowercase()))
            assertEquals(0, a.grabs())
        }
    }

    @Test
    fun aDepartureIsHandledOnce() {
        val m = settled()
        val first = m.decide(inputs(60_000L, tv = Tv.LEFT, leftCount = 1, leftKind = ModeManager.LeftKind.STOPPED, foreground = Foreground.LAUNCHER))
        assertEquals(1, first.modes().size)
        // the setting is Lyrics from now on; the renderer still says LEFT
        val again = m.decide(inputs(61_000L, mode = Mode.LYRICS, tv = Tv.LEFT, leftCount = 1, leftKind = ModeManager.LeftKind.STOPPED, foreground = Foreground.LAUNCHER))
        assertTrue(again.isEmpty())
    }

    @Test
    fun aCastSessionIsGrabbedAgainOnceItHasSettled() {
        val m = settled()
        val left = m.decide(inputs(60_000L, tv = Tv.LEFT, leftCount = 1, leftKind = ModeManager.LeftKind.CLOSED, foreground = Foreground.MEDIASHELL))
        assertTrue(left.isEmpty())
        assertTrue(m.decide(inputs(62_000L, tv = Tv.LEFT, leftCount = 1, foreground = Foreground.MEDIASHELL)).isEmpty())
        val again = m.decide(inputs(63_100L, tv = Tv.LEFT, leftCount = 1, foreground = Foreground.MEDIASHELL))
        assertEquals(1, again.grabs())
        assertTrue(again.modes().isEmpty())
    }

    @Test
    fun aSecondDepartureWithinAMinuteOfAReGrabIsTheUserAndTheModeBecomesLyrics() {
        val m = settled()
        m.decide(inputs(60_000L, tv = Tv.LEFT, leftCount = 1, foreground = Foreground.MEDIASHELL))
        assertEquals(1, m.decide(inputs(64_000L, tv = Tv.LEFT, leftCount = 1, foreground = Foreground.MEDIASHELL)).grabs()) // the re-grab
        val a = m.decide(inputs(80_000L, tv = Tv.LEFT, leftCount = 2, leftKind = ModeManager.LeftKind.STOPPED, foreground = Foreground.MEDIASHELL))
        assertEquals(1, a.modes().size)
        assertEquals(Mode.LYRICS, a.modes()[0].mode)
        assertEquals(0, a.grabs())
    }

    @Test
    fun aDepartureLongAfterAReGrabIsAnotherCastSession() {
        val m = settled()
        m.decide(inputs(60_000L, tv = Tv.LEFT, leftCount = 1, foreground = Foreground.MEDIASHELL))
        m.decide(inputs(64_000L, tv = Tv.LEFT, leftCount = 1, foreground = Foreground.MEDIASHELL))
        val later = m.decide(inputs(10 * minute, tv = Tv.LEFT, leftCount = 2, foreground = Foreground.MEDIASHELL))
        assertTrue(later.modes().isEmpty())
        assertEquals(1, m.decide(inputs(10 * minute + 3_500L, tv = Tv.LEFT, leftCount = 2, foreground = Foreground.MEDIASHELL)).grabs())
    }

    @Test
    fun anUnknownForegroundIsTreatedAsACastSessionOnce() {
        val m = settled()
        assertTrue(m.decide(inputs(60_000L, tv = Tv.LEFT, leftCount = 1, foreground = Foreground.UNKNOWN)).isEmpty())
        assertEquals(1, m.decide(inputs(64_000L, tv = Tv.LEFT, leftCount = 1, foreground = Foreground.UNKNOWN)).grabs())
    }

    @Test
    fun aVideoAppInFrontKeepsTheTvOutUntilItIsGone() {
        val m = settled()
        val left = m.decide(inputs(60_000L, tv = Tv.LEFT, leftCount = 1, leftKind = ModeManager.LeftKind.STOPPED, foreground = Foreground.VIDEO_APP))
        assertEquals(1, left.releases().size)
        assertTrue(left.modes().isEmpty())
        assertTrue("it asks what is in front while the app is there", m.wantsForeground(Tv.IDLE, 1))
        // sound, the app still in front: no grab
        assertEquals(0, m.decide(inputs(70_000L, tv = Tv.IDLE, leftCount = 1, foreground = Foreground.VIDEO_APP, silent = 0L)).grabs())
        // the app is gone
        assertEquals(1, m.decide(inputs(80_000L, tv = Tv.IDLE, leftCount = 1, foreground = Foreground.LAUNCHER, silent = 0L)).grabs())
        assertFalse(m.wantsForeground(Tv.IDLE, 1))
    }

    @Test
    fun aTvThatDoesNotAnswerIsReleasedAndTheModeStaysSynced() {
        val m = settled()
        val a = m.decide(inputs(60_000L, tv = Tv.LEFT, leftCount = 1, leftKind = ModeManager.LeftKind.UNREACHABLE, foreground = Foreground.LAUNCHER))
        assertEquals(1, a.releases().size)
        assertTrue(a.modes().isEmpty())
    }

    // ---- the mode ------------------------------------------------------------------------------------

    @Test
    fun threeFailedGrabsMakeTheModeLyrics() {
        val m = settled()
        assertTrue(m.decide(inputs(60_000L, tv = Tv.FAILED, failed = 2, backoff = 5_000L)).modes().isEmpty())
        val a = m.decide(inputs(61_000L, tv = Tv.FAILED, failed = 3, backoff = 30_000L))
        assertEquals(1, a.modes().size)
        assertEquals(Mode.LYRICS, a.modes()[0].mode)
        assertTrue(a.modes()[0].why.contains("3 times"))
    }

    @Test
    fun lyricsGoesBackToSyncedAfterMoreThanTenMinutesOfSilence() {
        val m = settled()
        assertTrue(m.decide(inputs(20 * minute, mode = Mode.LYRICS, silent = 9 * minute)).isEmpty())
        val a = m.decide(inputs(22 * minute, mode = Mode.LYRICS, silent = 11 * minute))
        assertEquals(1, a.modes().size)
        assertEquals(Mode.SYNCED, a.modes()[0].mode)
        // synced again by the reset: the TV is not taken for the silence, only when music begins
        assertEquals(0, m.decide(inputs(22 * minute + 1_000L, mode = Mode.SYNCED, silent = 11 * minute + 1_000L)).grabs())
        assertEquals(1, m.decide(inputs(23 * minute, mode = Mode.SYNCED, silent = 0L)).grabs())
    }

    @Test
    fun theUserWhoSwitchedToLyricsWhileMusicPlaysStaysInLyrics() {
        val m = settled()
        for (t in 1..30) assertTrue(m.decide(inputs(t * 10_000L, mode = Mode.LYRICS, silent = 0L)).isEmpty())
    }

    @Test
    fun whatIsInFrontIsAskedOnlyWhenItMatters() {
        val m = settled()
        assertFalse(m.wantsForeground(Tv.PLAYING, 0))
        assertTrue(m.wantsForeground(Tv.LEFT, 1))
        m.decide(inputs(1L, tv = Tv.LEFT, leftCount = 1, foreground = Foreground.LAUNCHER))
        assertFalse("handled", m.wantsForeground(Tv.LEFT, 1))
        assertNotNull(m.lastWhy)
    }

    // ---- what is in front ----------------------------------------------------------------------------

    @Test
    fun thePackageInFrontIsClassified() {
        val own = "io.github.matijasalej.wholeflat"
        val launchers = setOf("com.google.android.apps.tv.launcherx", "com.vendor.home")
        assertEquals(Foreground.UNKNOWN, ForegroundPolicy.classify(null, own, launchers))
        assertEquals(Foreground.OWN_APP, ForegroundPolicy.classify(own, own, launchers))
        assertEquals(Foreground.SPOTIFY, ForegroundPolicy.classify("com.spotify.tv.android", own, launchers))
        assertEquals(Foreground.MEDIASHELL, ForegroundPolicy.classify("com.google.android.apps.mediashell", own, launchers))
        assertEquals(Foreground.LAUNCHER, ForegroundPolicy.classify("com.google.android.apps.tv.launcherx", own, launchers))
        assertEquals(Foreground.LAUNCHER, ForegroundPolicy.classify("com.vendor.home", own, launchers))
        assertEquals(Foreground.LAUNCHER, ForegroundPolicy.classify("com.android.systemui", own, launchers))
        assertEquals(Foreground.LAUNCHER, ForegroundPolicy.classify("com.google.android.apps.tv.dreamx", own, launchers))
        assertEquals(Foreground.VIDEO_APP, ForegroundPolicy.classify("com.netflix.ninja", own, launchers))
    }
}
