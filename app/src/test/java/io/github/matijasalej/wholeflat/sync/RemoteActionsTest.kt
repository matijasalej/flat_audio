package io.github.matijasalej.wholeflat.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteActionsTest {
    private val now = RemoteActions.Current(
        display = "listen", kitchenEnabled = true, kitchenVolume = 40, kitchenOffsetMs = 15, lyricsOffsetMs = 0, tvExtraMs = 490,
    )

    private fun parse(vararg pairs: Pair<String, String>, current: RemoteActions.Current = now) = RemoteActions.parse(mapOf(*pairs), current)

    @Test
    fun theDisplayIsOneOfThreeInAnyCaseAndChoosingItPutsTheAppBackInSyncedMode() {
        val r = parse("display" to "Screen")
        assertEquals("screen", r.updates.getString("display"))
        assertEquals("synced", r.updates.getString("mode"))
        assertEquals("ambilight", parse("display" to " ambilight ").updates.getString("display"))
        assertEquals("listen", parse("display" to "LISTEN").updates.getString("display"))
        val bad = parse("display" to "party")
        assertFalse(bad.updates.has("display"))
        assertFalse(bad.updates.has("mode"))
        assertEquals(listOf("display: party"), bad.errors)
    }

    @Test
    fun nextCyclesThroughTheThreeLikeTheButtonOfTheTvApp() {
        assertEquals("ambilight", parse("display" to "next").updates.getString("display"))
        val fromAmbilight = now.let { RemoteActions.Current("ambilight", it.kitchenEnabled, it.kitchenVolume, it.kitchenOffsetMs, it.lyricsOffsetMs, it.tvExtraMs) }
        assertEquals("screen", parse("display" to "next", current = fromAmbilight).updates.getString("display"))
        val fromScreen = RemoteActions.Current("screen", true, 40, 15, 0, 490)
        assertEquals("listen", parse("display" to "next", current = fromScreen).updates.getString("display"))
    }

    @Test
    fun theKitchenCanBeSwitchedOnAndOff() {
        assertEquals(false, parse("kitchen" to "off").updates.getBoolean("kitchen_enabled"))
        assertEquals(false, parse("kitchen" to "toggle").updates.getBoolean("kitchen_enabled"))
        assertEquals(true, parse("kitchen" to "1").updates.getBoolean("kitchen_enabled"))
        assertEquals(listOf("kitchen: maybe"), parse("kitchen" to "maybe").errors)
    }

    @Test
    fun theVolumeIsSteppedFromWhereItIsOrSetAndKeptInRange() {
        assertEquals(43, parse("volume" to "+").updates.getInt("kitchen_volume"))
        assertEquals(37, parse("volume" to "down").updates.getInt("kitchen_volume"))
        assertEquals(45, parse("volume" to "+5").updates.getInt("kitchen_volume"))
        assertEquals(35, parse("volume" to "-5").updates.getInt("kitchen_volume"))
        assertEquals(70, parse("volume" to "70").updates.getInt("kitchen_volume"))
        assertEquals(100, parse("volume" to "+500").updates.getInt("kitchen_volume"))
        assertEquals(0, parse("volume" to "-500").updates.getInt("kitchen_volume"))
        assertEquals(100, parse("volume" to "250").updates.getInt("kitchen_volume"))
        assertEquals(listOf("volume: loud"), parse("volume" to "loud").errors)
    }

    @Test
    fun aVolumeThatIsNotKnownIsSteppedFromThirty() {
        val unknown = RemoteActions.parse(mapOf("volume" to "+"), RemoteActions.Current("listen", true, -1, 0, 0, 490))
        assertEquals(33, unknown.updates.getInt("kitchen_volume"))
    }

    @Test
    fun theFineSyncMovesTheKitchenByTenMillisecondsAKeyPress() {
        assertEquals(25, parse("fine" to "+").updates.getInt("kitchen_offset_ms"))
        assertEquals(5, parse("fine" to "-").updates.getInt("kitchen_offset_ms"))
        assertEquals(35, parse("fine" to "+20").updates.getInt("kitchen_offset_ms"))
        assertEquals(-25, parse("fine" to "-40").updates.getInt("kitchen_offset_ms"))
        assertEquals(0, parse("fine" to "0").updates.getInt("kitchen_offset_ms"))
        assertEquals(listOf("fine: x"), parse("fine" to "x").errors)
    }

    @Test
    fun theLyricsAndTheTvExtraMoveInTheirOwnSteps() {
        assertEquals(100, parse("lyrics" to "+").updates.getInt("lyrics_offset_ms"))
        assertEquals(-100, parse("lyrics" to "-").updates.getInt("lyrics_offset_ms"))
        assertEquals(0, parse("lyrics" to "0").updates.getInt("lyrics_offset_ms"))
        assertEquals(500, parse("lyrics" to "+500").updates.getInt("lyrics_offset_ms"))
        assertEquals(500, parse("tv_extra" to "+").updates.getInt("tv_extra_ms"))
        assertEquals(480, parse("tv_extra" to "-").updates.getInt("tv_extra_ms"))
    }

    @Test
    fun severalPressesInOneRequestAreAllTakenAndUnknownParametersAreIgnored() {
        val r = parse("display" to "screen", "fine" to "+", "t" to "123456", "volume" to "+", "mode" to "lyrics", "karaoke" to "on")
        // the page's own parameters, and keys the page does not have any more (the mode: it is Synced, whatever the display), are not taken
        assertEquals(setOf("display", "mode", "kitchen_offset_ms", "kitchen_volume"), r.updates.keys().asSequence().toSet())
        assertEquals("synced", r.updates.getString("mode"))
        assertTrue(r.errors.isEmpty())
    }

    @Test
    fun anEmptyRequestChangesNothing() {
        val r = parse()
        assertEquals(0, r.updates.length())
        assertTrue(r.errors.isEmpty())
    }
}
