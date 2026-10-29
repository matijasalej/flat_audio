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
    fun theDisplayIsOneOfFourInAnyCaseAndChoosingItPutsTheAppBackInSyncedMode() {
        val r = parse("display" to "Karaoke")
        assertEquals("karaoke", r.updates.getString("display"))
        assertEquals("synced", r.updates.getString("mode"))
        assertEquals("party", parse("display" to " party ").updates.getString("display"))
        assertEquals("chill", parse("display" to "CHILL").updates.getString("display"))
        assertEquals("listen", parse("display" to "LISTEN").updates.getString("display"))
        // the older keys are the new displays
        assertEquals("party", parse("display" to "ambilight").updates.getString("display"))
        assertEquals("karaoke", parse("display" to "screen").updates.getString("display"))
        val bad = parse("display" to "disco")
        assertFalse(bad.updates.has("display"))
        assertFalse(bad.updates.has("mode"))
        assertEquals(listOf("display: disco"), bad.errors)
    }

    @Test
    fun nextCyclesThroughTheFourLikeTheButtonOfTheTvApp() {
        fun from(d: String) = RemoteActions.Current(d, true, 40, 15, 0, 490)
        assertEquals("chill", parse("display" to "next").updates.getString("display"))
        assertEquals("party", parse("display" to "next", current = from("chill")).updates.getString("display"))
        assertEquals("karaoke", parse("display" to "next", current = from("party")).updates.getString("display"))
        assertEquals("listen", parse("display" to "next", current = from("karaoke")).updates.getString("display"))
    }

    @Test
    fun aColourIsTheChillLightAndTheLevelItsBrightness() {
        val r = parse("color" to "#FF9A40")
        assertEquals("#ff9a40", r.updates.getString("light_color"))
        assertEquals("chill", r.updates.getString("display"))
        assertEquals("synced", r.updates.getString("mode"))
        assertEquals(listOf("color: red"), parse("color" to "red").errors)
        assertEquals(60, parse("level" to "60").updates.getInt("light_level"))
        assertEquals(MoodLight.MIN_LEVEL, parse("level" to "2").updates.getInt("light_level"))
        // the brightness alone does not take the TV
        assertFalse(parse("level" to "60").updates.has("display"))
        assertEquals(listOf("level: lots"), parse("level" to "lots").errors)
    }

    @Test
    fun theFineSyncCanBeSetOutrightByTheSlider() {
        assertEquals(-120, parse("fine_ms" to "-120").updates.getInt("kitchen_offset_ms"))
        assertEquals(75, parse("fine_ms" to "+75").updates.getInt("kitchen_offset_ms"))
        assertEquals(0, parse("fine_ms" to "0").updates.getInt("kitchen_offset_ms"))
        assertEquals(listOf("fine_ms: soon"), parse("fine_ms" to "soon").errors)
        // "fine" with a sign is a step from where it is (15)
        assertEquals(-105, parse("fine" to "-120").updates.getInt("kitchen_offset_ms"))
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
        val r = parse("display" to "karaoke", "fine" to "+", "t" to "123456", "volume" to "+", "mode" to "lyrics", "replay" to "on")
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
