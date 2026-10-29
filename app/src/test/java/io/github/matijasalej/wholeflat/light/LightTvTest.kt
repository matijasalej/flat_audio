package io.github.matijasalej.wholeflat.light

import io.github.matijasalej.wholeflat.sync.AmbilightApi
import io.github.matijasalej.wholeflat.sync.AmbilightControl
import io.github.matijasalej.wholeflat.sync.AmbilightStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the lights ask of the TV: its styles per song and phrase, the settings of the lights, and the Ambilight control's part. */
class LightTvTest {
    private val rate = 44100
    private val blue = Colors.hsv(220.0, 0.8, 0.9)
    private val orange = Colors.hsv(30.0, 0.8, 0.9)

    private fun look(cls: LightClass) = SongLook("k", cls, blue, orange, intArrayOf(blue, orange, blue, orange), "test")

    private fun analysis(bpm: Double = 120.0, locked: Boolean = true, energy: Double = 0.8, sections: List<Analysis.Section> = emptyList()) =
        Analysis(0, 60L * rate, bpm, 60.0 / bpm * rate, 0.0, locked, 0.8, energy, energy, 0.5, 0.0, -12.0, false, sections)

    @Test
    fun theModesAreTheOnesThatLightUpWithTheScreenOff() {
        val names = TvLooks.MODES.map { it.name }
        assertEquals(listOf("party", "rhythm", "scanner", "vu", "scanner-cw", "spectrum", "colora"), names)
        for (m in TvLooks.MODES) {
            val o = JSONObject(m.json)
            assertEquals("FOLLOW_AUDIO", o.getString("styleName"))
            // never the strobe, never Lumina (dark with the screen off)
            assertFalse(m.json.contains("STROBE"))
            assertFalse(m.json.contains("ENERGY_ADAPTIVE_BRIGHTNESS"))
        }
        // Party works only in the expert form
        val party = JSONObject(TvLooks.mode("party")!!.json)
        assertTrue(party.getBoolean("isExpert"))
        assertEquals("PARTY", party.getString("algorithm"))
        assertEquals("RANDOM_PIXEL_FLASH", JSONObject(TvLooks.mode("rhythm")!!.json).getString("menuSetting"))
    }

    @Test
    fun calmHoldsTheSongsColourWithASlowSmallDrift() {
        val o = JSONObject(TvLooks.calm(blue, 0.2).json)
        assertEquals("FOLLOW_COLOR", o.getString("styleName"))
        assertEquals("MANUAL_HUE", o.getString("algorithm"))
        val cs = o.getJSONObject("colorSettings")
        assertEquals(156, cs.getJSONObject("color").getInt("hue")) // 220° on 0..255
        assertEquals(6, cs.getJSONObject("colorDelta").getInt("hue"))
        assertEquals(32, cs.getInt("speed"))
    }

    @Test
    fun livelyDriftsBetweenHomeAndAccentAndFasterForFasterSongs() {
        val slow = JSONObject(TvLooks.lively(blue, orange, 80.0, 0.5).json).getJSONObject("colorSettings")
        val fast = JSONObject(TvLooks.lively(blue, orange, 150.0, 0.5).json).getJSONObject("colorSettings")
        assertTrue(fast.getInt("speed") > slow.getInt("speed"))
        // halfway towards orange, 40° at most: 20° from 220 (the short way round, past 180)
        val hue = fast.getJSONObject("color").getInt("hue") * 360.0 / 255
        assertEquals(240.0, hue, 2.0)
        assertEquals(14, fast.getJSONObject("colorDelta").getInt("hue")) // 20° on 0..255
    }

    @Test
    fun theClassGivesTheLook() {
        assertEquals("calm", TvSchedule.lookAt(10L * rate, analysis(), look(LightClass.MONO), rate, "adaptive", TvLooks.MODES, 0).name)
        assertEquals("lively", TvSchedule.lookAt(10L * rate, analysis(), look(LightClass.DUO), rate, "adaptive", TvLooks.MODES, 0).name)
        assertTrue(TvSchedule.lookAt(10L * rate, analysis(), look(LightClass.MULTI), rate, "adaptive", TvLooks.MODES, 0).name in TvLooks.MODES.map { it.name })
    }

    @Test
    fun anEnergeticSongChangesModeEveryThirtyTwoBeatsOnTheGrid() {
        val a = analysis(bpm = 120.0) // 32 beats = 16 s
        val l = look(LightClass.MULTI)
        val names = (0 until 64).map { s -> TvSchedule.lookAt(s * rate / 2L, a, l, rate, "adaptive", TvLooks.MODES, 5).name }
        // the same mode for 16 s, then another
        val changes = names.zipWithNext().withIndex().filter { (_, p) -> p.first != p.second }.map { it.index + 1 }
        assertEquals(listOf(32), changes) // the half-seconds 0..63: one change, at 16 s
        assertEquals(16.0 * rate, TvSchedule.nextSwitch(rate.toLong(), a, l, rate, "adaptive").toDouble(), 1.0)
        assertEquals(8.0 * rate, TvSchedule.nextSwitch(rate.toLong(), a, l, rate, "punchy").toDouble(), 1.0)
    }

    @Test
    fun aChorusStartsWithPartyAndABreakdownWithSomethingCalmer() {
        val l = look(LightClass.MULTI)
        val up = analysis(sections = listOf(Analysis.Section(20L * rate, true)))
        assertEquals("party", TvSchedule.lookAt(21L * rate, up, l, rate, "adaptive", TvLooks.MODES, 3).name)
        val down = analysis(sections = listOf(Analysis.Section(20L * rate, false)))
        // a breakdown lowers Multi to Duo for 32 beats: the song's colours, lively
        assertEquals("lively", TvSchedule.lookAt(21L * rate, down, l, rate, "adaptive", TvLooks.MODES, 3).name)
        // a Duo song's chorus is lifted to Multi, and starts with Party
        assertEquals("party", TvSchedule.lookAt(21L * rate, up, look(LightClass.DUO), rate, "adaptive", TvLooks.MODES, 3).name)
    }

    @Test
    fun beforeTheTempoLocksAnEnergeticSongKeepsOneModePickedBySong() {
        val a = analysis(locked = false)
        val l = look(LightClass.MULTI)
        val first = TvSchedule.lookAt(1L * rate, a, l, rate, "adaptive", TvLooks.MODES, 42).name
        assertEquals(first, TvSchedule.lookAt(30L * rate, a, l, rate, "adaptive", TvLooks.MODES, 42).name)
        assertNotEquals(first, TvSchedule.lookAt(1L * rate, a, l, rate, "adaptive", TvLooks.MODES, 43).name)
    }

    @Test
    fun onlyTheModesHeAllowsAreUsed() {
        val (o, refused) = LightOptions().apply(mapOf("lights_modes" to "party,rhythm"))
        assertTrue(refused.isEmpty())
        val a = analysis()
        val l = look(LightClass.MULTI)
        val used = (0 until 200).map { s -> TvSchedule.lookAt(s * rate.toLong(), a, l, rate, "punchy", o.modeLooks(), 1).name }.toSet()
        assertEquals(setOf("party", "rhythm"), used)
    }

    @Test
    fun theSettingsOfTheLightsCheckEveryValue() {
        val (o, refused) = LightOptions().apply(mapOf("lights" to "tv", "lights_strength" to "punchy", "lights_modes" to "party,strobe", "lights_offset_ms" to "40"))
        assertEquals("tv", o.mode)
        assertEquals("punchy", o.strength)
        assertEquals(TvLooks.MODES.map { it.name }, o.modes)
        assertEquals(listOf("lights_modes=party,strobe"), refused)
        assertEquals(o, LightOptions.of(o.toJson().toString()))
        // the settings of the painted lights of an earlier build are let go
        assertEquals(LightOptions(), LightOptions.of("""{"lights":"mood","lights_band_pct":5,"lights_synced_style":"FOLLOW_VIDEO/GAME"}"""))
    }

    private class FakeTv : AmbilightApi {
        val configs = ArrayList<String>()

        override fun power(): String = "On"

        override fun config(): String = """{"styleName":"FOLLOW_COLOR","isExpert":false,"menuSetting":"HOT_LAVA"}"""

        override fun setPower(on: Boolean): Boolean = true

        override fun setConfig(json: String): Boolean {
            configs.add(json)
            return true
        }
    }

    private class NoStore : AmbilightStore {
        override fun read(): AmbilightControl.Saved? = null

        override fun write(saved: AmbilightControl.Saved?) = Unit
    }

    @Test
    fun theAmbilightControlPutsOnTheLightsConfigurationWhenThereIsOne() {
        val tv = FakeTv()
        var lights: String? = TvLooks.mode("party")!!.json
        val c = AmbilightControl(tv, NoStore(), { "FOLLOW_AUDIO" }, { "ENERGY_ADAPTIVE_BRIGHTNESS" }, { lights })
        assertTrue(c.enter())
        assertEquals("PARTY", JSONObject(tv.configs.last()).getString("algorithm"))
        lights = null
        c.restyle()
        assertEquals("ENERGY_ADAPTIVE_BRIGHTNESS", JSONObject(tv.configs.last()).getString("menuSetting"))
    }
}
