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
        idle: Long = silent,
        known: Boolean = true,
        tv: Tv = Tv.IDLE,
        leftCount: Int = 0,
        leftKind: ModeManager.LeftKind? = null,
        foreground: Foreground = Foreground.UNKNOWN,
        backoff: Long = 0L,
        failed: Int = 0,
        retrying: Boolean = false,
        heard: Boolean = true,
        stuck: Boolean = false,
    ) = ModeManager.Inputs(now, mode, source, alive, silent, known, tv, leftCount, leftKind, foreground, backoff, failed, idle, retrying, heard, stuck)

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
    fun aTvThatLostItsStreamInARestartIsTriedAgainEvenInAPauseOfTheMusic() {
        val m = settled()
        // the music paused 20 s ago: nothing is grabbed for the quiet ...
        assertEquals(0, m.decide(inputs(60_000L, silent = 20_000L, tv = Tv.FAILED, failed = 1)).grabs())
        // ... except while the stream is being made again, or its start failed a moment ago
        assertEquals(1, m.decide(inputs(61_000L, silent = 21_000L, tv = Tv.FAILED, failed = 1, retrying = true)).grabs())
        // never against the back-off
        assertEquals(0, m.decide(inputs(62_000L, silent = 22_000L, tv = Tv.FAILED, failed = 2, backoff = 8_000L, retrying = true)).grabs())
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
    fun theFirstSecondsAfterAStartAreNotSoundUntilSomethingIsHeard() {
        // the capture's silence counts from the app's start until the first sound: 2 s after a start is not music (seen on 28.10.)
        val m = ModeManager()
        assertEquals(0, m.decide(inputs(1_000L, silent = 1_000L, idle = 1_000L, heard = false)).grabs())
        assertEquals(0, m.decide(inputs(2_000L, silent = 2_000L, idle = 2_000L, heard = false)).grabs())
        // the first real sound: the TV is taken as before
        assertEquals(1, m.decide(inputs(30_000L, silent = 0L, idle = 0L, heard = true)).grabs())
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
    fun theTvKeepsPlayingThroughPausesAndSkipsAndGoesBackAfterEightMinutesOfSilence() {
        val m = settled()
        // the capture says idle after a minute of silence, but the TV stays
        assertTrue(m.decide(inputs(5 * minute, source = "idle", silent = 4 * minute, tv = Tv.PLAYING)).isEmpty())
        assertTrue(m.decide(inputs(8 * minute, source = "idle", silent = 7 * minute, tv = Tv.PLAYING)).isEmpty())
        // given back before the box sleeps (about 11 minutes after the music)
        val a = m.decide(inputs(9 * minute, source = "idle", silent = 8 * minute, tv = Tv.PLAYING))
        assertEquals(1, a.releases().size)
        assertTrue(a.releases()[0].why, a.releases()[0].why.contains("8 minutes"))
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
    fun aStartWithoutMusicIsNotTenMinutesOfSilence() {
        // the app was just started in Lyrics mode (the user chose it): no sound yet is "never" for the capture, but the app is seconds old
        val m = ModeManager()
        assertTrue(m.decide(inputs(2_000L, mode = Mode.LYRICS, silent = never, idle = 2_000L)).isEmpty())
        assertTrue(m.decide(inputs(9 * minute, mode = Mode.LYRICS, silent = never, idle = 9 * minute)).isEmpty())
        // after ten minutes of the app's life without music the rule applies
        val a = m.decide(inputs(11 * minute, mode = Mode.LYRICS, silent = never, idle = 11 * minute))
        assertEquals(Mode.SYNCED, a.modes()[0].mode)
    }

    @Test
    fun theUserWhoSwitchedToLyricsWhileMusicPlaysStaysInLyrics() {
        val m = settled()
        for (t in 1..30) assertTrue(m.decide(inputs(t * 10_000L, mode = Mode.LYRICS, silent = 0L)).isEmpty())
    }

    @Test
    fun aGivenUpTvKeepsLyricsThroughALongSilenceInsteadOfTurningTheModeBackAndForth() {
        val m = settled()
        assertEquals(Mode.LYRICS, m.decide(inputs(60_000L, tv = Tv.FAILED, failed = 3, backoff = 30_000L)).modes().single().mode)
        // as the glue does it: every mode set is a settings change and the next step at once (on the box: 868 changes in 2.4 s)
        var mode = Mode.LYRICS
        var changes = 0
        for (t in 1..50) {
            val a = m.decide(inputs(20 * minute + t * 2L, mode = mode, silent = 11 * minute, tv = Tv.FAILED, failed = 3))
            a.modes().lastOrNull()?.let {
                mode = it.mode
                changes++
            }
        }
        assertEquals(0, changes)
        assertEquals(Mode.LYRICS, mode)
    }

    @Test
    fun theSyncedThatTheUserAsksForIsNotTurnedBackByTheFailuresFromBeforeItAndTheTvIsTriedAgain() {
        val m = settled()
        m.decide(inputs(60_000L, tv = Tv.FAILED, failed = 3, backoff = 30_000L))
        m.decide(inputs(5 * minute, mode = Mode.LYRICS, silent = 2 * minute, tv = Tv.FAILED, failed = 3))
        // a display on the phone page: Synced, and the renderer has not forgotten the three failures yet
        assertTrue(m.decide(inputs(5 * minute + 1_000L, mode = Mode.SYNCED, silent = 2 * minute, tv = Tv.FAILED, failed = 3, backoff = 20_000L)).modes().isEmpty())
        // forgotten a moment later: the TV is taken, with nothing playing (the user has just asked)
        val a = m.decide(inputs(5 * minute + 1_200L, mode = Mode.SYNCED, silent = 2 * minute, tv = Tv.IDLE, failed = 0))
        assertEquals(1, a.grabs())
        assertTrue(a.modes().isEmpty())
    }

    @Test
    fun failuresThatAreNotForgottenStillMakeTheModeLyricsOnceTheMomentOfAskingIsOver() {
        val m = settled()
        m.decide(inputs(60_000L, tv = Tv.FAILED, failed = 3, backoff = 30_000L))
        m.decide(inputs(5 * minute, mode = Mode.LYRICS, silent = 2 * minute, tv = Tv.FAILED, failed = 3))
        assertTrue(m.decide(inputs(5 * minute + 1_000L, mode = Mode.SYNCED, silent = 2 * minute, tv = Tv.FAILED, failed = 3, backoff = 20_000L)).modes().isEmpty())
        val a = m.decide(inputs(5 * minute + 12_000L, mode = Mode.SYNCED, silent = 2 * minute, tv = Tv.FAILED, failed = 3, backoff = 8_000L))
        assertEquals(Mode.LYRICS, a.modes().single().mode)
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

    // ---- a TV whose player is hung -----------------------------------------------------------------------

    @Test
    fun aHungPlayerIsNotAskedForAStreamAndSyncedFallsBackToLyricsWithTheAdvice() {
        val m = settled()
        val a = m.decide(inputs(60_000L, silent = 0L, stuck = true))
        assertEquals(0, a.grabs())
        assertEquals(Mode.LYRICS, a.modes().single().mode)
        assertTrue(a.modes()[0].why, a.modes()[0].why.contains("unplug the TV for a minute"))
        // asked again while it stays hung, with sound: still no grab
        assertEquals(0, m.decide(inputs(62_000L, mode = Mode.LYRICS, silent = 0L, stuck = true)).grabs())
    }

    @Test
    fun aHungPlayerKeepsLyricsThroughALongSilence() {
        val m = settled()
        m.decide(inputs(60_000L, silent = 0L, stuck = true))
        for (k in 0 until 5) {
            val a = m.decide(inputs(60 * minute + k, mode = Mode.LYRICS, silent = 30 * minute, stuck = true))
            assertTrue(a.toString(), a.modes().isEmpty())
        }
    }

    @Test
    fun theUsersSyncedIsNotBouncedWhileTheHungFlagIsBeingClearedAndThenTheTvIsTaken() {
        val m = settled()
        m.decide(inputs(60_000L, silent = 0L, stuck = true))
        m.decide(inputs(61_000L, mode = Mode.LYRICS, silent = 0L, stuck = true))
        // the user picks a setting: the mode is Synced, the renderer has not cleared the flag yet
        val a = m.decide(inputs(70_000L, mode = Mode.SYNCED, silent = never, stuck = true))
        assertTrue(a.toString(), a.modes().isEmpty())
        assertEquals(0, a.grabs())
        // cleared a moment later: the TV is taken, with no sound (the user asked)
        assertEquals(1, m.decide(inputs(71_000L, mode = Mode.SYNCED, silent = never, stuck = false)).grabs())
    }

    // ---- a tap after a long silence (28.10.: the grab and the silence release undid each other ~100 times in 10 s) ----

    @Test
    fun aTapAfterALongSilenceTakesTheTvOnceAndItIsNotGivenBackWhileItStartsOrSoonAfter() {
        val m = ModeManager(silenceKeepMs = { 60_000L })
        // Lyrics with music (a long silence here would have the manager set Synced itself, which is not the user asking)
        m.decide(inputs(0L, mode = Mode.LYRICS, silent = 0L))
        // the user picks a setting after half an hour of silence
        val a = m.decide(inputs(30 * minute, mode = Mode.SYNCED, silent = 30 * minute))
        assertEquals(1, a.grabs())
        // the start is under way: no release for the silence, and no second grab
        for (k in 1..20) {
            val b = m.decide(inputs(30 * minute + k * 100L, mode = Mode.SYNCED, silent = 30 * minute, tv = Tv.STARTING))
            assertTrue(b.toString(), b.releases().isEmpty() && b.grabs() == 0)
        }
        // it plays: the silence counts from the tap, so it is kept for the hold
        assertTrue(m.decide(inputs(30 * minute + 30_000L, mode = Mode.SYNCED, silent = 30 * minute, tv = Tv.PLAYING)).releases().isEmpty())
        assertEquals("1 minutes of silence", m.decide(inputs(30 * minute + 61_000L, mode = Mode.SYNCED, silent = 31 * minute, tv = Tv.PLAYING)).releases().single().why)
    }

    @Test
    fun grabsAreAtLeastThreeSecondsApart() {
        val m = settled()
        assertEquals(1, m.decide(inputs(60_000L, silent = 0L)).grabs())
        // the start ended at once (cancelled, failed): with sound the TV is wanted again, but not before 3 s
        assertEquals(0, m.decide(inputs(61_000L, silent = 0L, tv = Tv.IDLE)).grabs())
        assertEquals(0, m.decide(inputs(62_999L, silent = 0L, tv = Tv.IDLE)).grabs())
        assertEquals(1, m.decide(inputs(63_000L, silent = 0L, tv = Tv.IDLE)).grabs())
    }

    // ---- giving the TV back after a silence ------------------------------------------------------------

    @Test
    fun aPlayingTvIsGivenBackAfterEightMinutesOfSilenceBeforeTheBoxSleeps() {
        val m = settled()
        assertTrue(m.decide(inputs(60_000L, tv = Tv.PLAYING, silent = 8 * 60_000L - 1)).releases().isEmpty())
        val r = m.decide(inputs(62_000L, tv = Tv.PLAYING, silent = 8 * 60_000L)).releases()
        assertEquals(1, r.size)
        assertEquals("8 minutes of silence", r[0].why)
    }

    @Test
    fun theHoldComesFromTheSetting() {
        var minutes = 20L
        val m = ModeManager(silenceKeepMs = { minutes * 60_000L })
        m.decide(inputs(0L, silent = never))
        assertTrue(m.decide(inputs(60_000L, tv = Tv.PLAYING, silent = 8 * 60_000L)).releases().isEmpty())
        assertEquals("20 minutes of silence", m.decide(inputs(62_000L, tv = Tv.PLAYING, silent = 20 * 60_000L)).releases().single().why)
        // a changed setting counts at the next decision
        minutes = 5L
        assertEquals("5 minutes of silence", m.decide(inputs(64_000L, tv = Tv.PLAYING, silent = 5 * 60_000L)).releases().single().why)
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
