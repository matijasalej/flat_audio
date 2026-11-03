package io.github.matijasalej.wholeflat.sync

import io.github.matijasalej.wholeflat.sync.PowerPress.Kind
import io.github.matijasalej.wholeflat.sync.PowerPress.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerPressTest {
    private fun line(atMs: Long, kind: Kind) = PowerPress.Line(atMs, kind)

    private fun verdicts(p: PowerPress, nowMs: Long) = p.due(nowMs).map { it.verdict }

    @Test
    fun aPressThatWokeTheBoxIsOn() {
        val p = PowerPress()
        p.add(line(10_000, Kind.PRESS), seenAtMs = 10_050)
        p.add(line(10_003, Kind.WOKE))
        // decided only once its wake had time to show
        assertEquals(emptyList<Verdict>(), verdicts(p, 10_549))
        assertEquals(1, p.waiting)
        assertEquals(listOf(Verdict.ON), verdicts(p, 10_550))
        assertEquals(0, p.waiting)
        assertEquals(emptyList<Verdict>(), verdicts(p, 20_000))
    }

    @Test
    fun aPressThatFoundTheBoxAwakeIsOff() {
        val p = PowerPress()
        p.add(line(10_000, Kind.PRESS))
        assertEquals(listOf(Verdict.OFF), verdicts(p, 10_500))
    }

    @Test
    fun theWakeMayBeReadBeforeThePress() {
        // two log buffers: the wake line can come in before the press line
        val p = PowerPress()
        p.add(line(9_997, Kind.WOKE))
        p.add(line(10_000, Kind.PRESS))
        assertEquals(listOf(Verdict.ON), verdicts(p, 10_500))
    }

    @Test
    fun aWakeFromTheScreensaverIsOff() {
        // the screensaver was on the TV: the press found the TV on
        val p = PowerPress()
        p.add(line(10_000, Kind.PRESS))
        p.add(line(10_004, Kind.WOKE_FROM_DREAM))
        assertEquals(listOf(Verdict.OFF), verdicts(p, 10_500))
    }

    @Test
    fun theScreensBroadcastStandsInForAMissingWakeLine() {
        val p = PowerPress()
        p.add(line(10_000, Kind.PRESS))
        p.add(line(10_240, Kind.SCREEN_ON))
        assertEquals(listOf(Verdict.ON), verdicts(p, 10_500))
    }

    @Test
    fun aWakeBelongsToOnePressOnly() {
        // on (the box woke), then off 0.9 s later: the same wake does not make the second one "on"
        val p = PowerPress()
        p.add(line(10_000, Kind.PRESS))
        p.add(line(10_003, Kind.WOKE))
        p.add(line(10_210, Kind.SCREEN_ON))
        p.add(line(10_900, Kind.PRESS))
        assertEquals(listOf(Verdict.ON, Verdict.OFF), verdicts(p, 11_400))
    }

    @Test
    fun oneLineLoggedTwiceIsOnePress() {
        val p = PowerPress()
        p.add(line(10_000, Kind.PRESS))
        p.add(line(10_040, Kind.PRESS))
        assertEquals(listOf(Verdict.OFF), verdicts(p, 11_000))
    }

    @Test
    fun aSecondPressBeforeTheBoxSleptCancelsTheFirst() {
        // off; the box's sleep (ours) is logged after the second press: that press found the box awake and turned the Onkyo on again
        val p = PowerPress()
        p.add(line(10_000, Kind.PRESS))
        assertEquals(listOf(Verdict.OFF), verdicts(p, 10_500))
        p.add(line(10_600, Kind.PRESS))
        p.add(line(10_800, Kind.SLEPT))
        assertEquals(listOf(Verdict.UNDO), verdicts(p, 11_100))
    }

    @Test
    fun aQuickDoublePressIsTwoPresses() {
        val p = PowerPress()
        p.add(line(10_000, Kind.PRESS))
        p.add(line(10_250, Kind.PRESS))
        assertEquals(listOf(Verdict.OFF, Verdict.UNDO), verdicts(p, 10_750))
    }

    @Test
    fun threeQuickPressesEndOff() {
        val p = PowerPress()
        p.add(line(10_000, Kind.PRESS))
        p.add(line(10_300, Kind.PRESS))
        p.add(line(10_600, Kind.PRESS))
        assertEquals(listOf(Verdict.OFF, Verdict.UNDO, Verdict.OFF), verdicts(p, 11_100))
    }

    @Test
    fun aPressAfterTheBoxSleptIsOn() {
        val p = PowerPress()
        p.add(line(10_000, Kind.PRESS))
        assertEquals(listOf(Verdict.OFF), verdicts(p, 10_500))
        p.add(line(10_800, Kind.SLEPT))
        p.add(line(12_000, Kind.PRESS))
        p.add(line(12_003, Kind.WOKE))
        assertEquals(listOf(Verdict.ON), verdicts(p, 12_500))
    }

    @Test
    fun theLogSaysTheBoxWasAsleepWhenItsWakeLineIsMissing() {
        val p = PowerPress()
        p.add(line(5_000, Kind.SLEPT))
        p.add(line(60_000, Kind.PRESS))
        val d = p.due(60_500).single()
        assertEquals(Verdict.ON, d.verdict)
        assertTrue(d.why, d.why.contains("asleep"))
    }

    @Test
    fun aBoxThatWokeSinceItSleptIsAwake() {
        // it slept, then a key on the remote woke it: a power press a minute later found it awake
        val p = PowerPress()
        p.add(line(5_000, Kind.SLEPT))
        p.add(line(7_000, Kind.WOKE))
        p.add(line(60_000, Kind.PRESS))
        assertEquals(listOf(Verdict.OFF), verdicts(p, 60_500))
    }

    @Test
    fun whereTheWatchBeganTheBoxsStateIsGiven() {
        val awake = PowerPress()
        awake.boxWas(true, 1_000)
        awake.add(line(60_000, Kind.PRESS))
        assertEquals(listOf(Verdict.OFF), verdicts(awake, 60_500))

        val asleep = PowerPress()
        asleep.boxWas(false, 1_000)
        asleep.add(line(60_000, Kind.PRESS))
        assertEquals(listOf(Verdict.ON), verdicts(asleep, 60_500))
    }

    @Test
    fun theGivenStateIsNoWakeForAPress() {
        // the watch began 0.5 s before an off press: the box being awake then is not a wake at the press
        val p = PowerPress()
        p.boxWas(true, 10_000)
        p.add(line(10_500, Kind.PRESS))
        assertEquals(listOf(Verdict.OFF), verdicts(p, 11_000))
    }

    @Test
    fun theScreenOffBroadcastDoesNotMoveTheLoggedSleep() {
        // the log's sleep at 10.0 s, its broadcast at 10.9 s: a press at 10.5 s found the box asleep
        val p = PowerPress()
        p.add(line(10_000, Kind.SLEPT))
        p.add(line(10_900, Kind.SCREEN_OFF))
        p.add(line(10_500, Kind.PRESS))
        assertEquals(listOf(Verdict.ON), verdicts(p, 11_000))
    }

    @Test
    fun theScreenOffBroadcastStandsInForAMissedSleep() {
        val p = PowerPress()
        p.add(line(1_000, Kind.WOKE))
        p.add(line(30_000, Kind.SCREEN_OFF))
        p.add(line(60_000, Kind.PRESS))
        assertEquals(listOf(Verdict.ON), verdicts(p, 60_500))
    }

    @Test
    fun offPressesFarApartAreBothOff() {
        val p = PowerPress()
        p.add(line(10_000, Kind.PRESS))
        p.add(line(20_000, Kind.PRESS))
        assertEquals(listOf(Verdict.OFF, Verdict.OFF), verdicts(p, 20_500))
    }

    @Test
    fun theBoxsLinesAreRead() {
        val press = PowerPress.parse("         1793663369.169  1723  1723 I Axel.RemoteManager: Waking up device")!!
        assertEquals(Kind.PRESS, press.kind)
        assertEquals(1_793_663_369_169L, press.atMs)

        val woke = PowerPress.parse(
            "         1793663369.172   639 10025 I PowerManagerService: Waking up from Asleep (uid=10051, " +
                "reason=WAKE_REASON_APPLICATION, details=axel:IR_power_button)...",
        )!!
        assertEquals(Kind.WOKE, woke.kind)
        assertEquals(1_793_663_369_172L, woke.atMs)

        val slept = PowerPress.parse(
            "         1793662463.115   639   752 I PowerManagerService: Going to sleep due to timeout (uid 1000, " +
                "screenOffTimeout=600000, activityTimeoutWM=-1, maxDimRatio=0.20000005, maxDimDur=7000)...",
        )!!
        assertEquals(Kind.SLEPT, slept.kind)

        assertEquals(
            Kind.WOKE_FROM_DREAM,
            PowerPress.parse("1793663369.172 639 639 I PowerManagerService: Waking up from Dreaming (uid=1000, reason=WAKE_REASON_WAKE_KEY)...")!!.kind,
        )
    }

    @Test
    fun otherLinesAreNotPresses() {
        assertNull(PowerPress.parse("         1793662464.271   639   752 I PowerManagerService: Sleeping (uid 1000)..."))
        assertNull(PowerPress.parse("         1793664054.338   639   752 I PowerManagerService: Nap time (uid 1000)..."))
        assertNull(PowerPress.parse("         1793663400.658  1723  1723 I Axel.BleConnection: Notification [0, 0, -92]"))
        assertNull(PowerPress.parse("--------- beginning of main"))
        assertNull(PowerPress.parse(""))
    }

    @Test
    fun theCommandsAreTheBoxsOwn() {
        assertEquals("logcat -v epoch -T 1 -b main,system -s Axel.RemoteManager:I PowerManagerService:I", PowerPress.LOG_COMMAND)
        assertEquals("input keyevent KEYCODE_SLEEP", PowerPress.SLEEP_COMMAND)
        assertEquals("input keyevent KEYCODE_WAKEUP", PowerPress.WAKE_COMMAND)
    }
}
