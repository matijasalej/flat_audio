package io.github.matijasalej.wholeflat.light

import io.github.matijasalej.wholeflat.sync.AmbilightApi
import io.github.matijasalej.wholeflat.sync.AmbilightControl
import io.github.matijasalej.wholeflat.sync.AmbilightStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the lights ask of the TV: its styles, the settings of the lights, and the test pictures. */
class LightTvTest {
    private val blue = Colors.hsv(220.0, 0.8, 0.9)
    private val orange = Colors.hsv(30.0, 0.8, 0.9)
    private val look = SongLook("k", LightClass.DUO, blue, orange, intArrayOf(blue, orange, blue, orange), "test")

    @Test
    fun aPlainStyleCarriesItsMenuSetting() {
        val o = JSONObject(NativeStyles.plain("FOLLOW_VIDEO/GAME"))
        assertEquals("FOLLOW_VIDEO", o.getString("styleName"))
        assertFalse(o.getBoolean("isExpert"))
        assertEquals("GAME", o.getString("menuSetting"))
        assertFalse(JSONObject(NativeStyles.plain("FOLLOW_VIDEO")).has("menuSetting"))
    }

    @Test
    fun monoHoldsTheHomeColourOnTheTvsScale() {
        val o = JSONObject(NativeStyles.forLook(look, LightClass.MONO, "COLOR", "COLORS:VU_METER", "PARTY"))
        assertEquals("FOLLOW_COLOR", o.getString("styleName"))
        assertTrue(o.getBoolean("isExpert"))
        assertEquals("MANUAL_HUE", o.getString("algorithm"))
        val c = o.getJSONObject("colorSettings").getJSONObject("color")
        assertEquals(156, c.getInt("hue")) // 220° of 360 on 0..255
        assertEquals(204, c.getInt("saturation"))
        assertEquals(230, c.getInt("brightness"))
    }

    @Test
    fun duoAndMultiUseTheTvsAudioAlgorithms() {
        val duo = JSONObject(NativeStyles.forLook(look, LightClass.DUO, "COLOR", "COLORS:VU_METER", "PARTY"))
        assertEquals("FOLLOW_AUDIO", duo.getString("styleName"))
        assertTrue(duo.getBoolean("isExpert"))
        assertEquals("VU_METER", duo.getJSONObject("audioSettings").getString("algorithm"))
        val multi = JSONObject(NativeStyles.forLook(look, LightClass.MULTI, "COLOR", "COLORS:VU_METER", "PARTY"))
        assertEquals("PARTY", multi.getString("menuSetting"))
        assertFalse(multi.getBoolean("isExpert"))
    }

    @Test
    fun theSettingsOfTheLightsCheckEveryValue() {
        val (o, refused) = LightOptions().apply(mapOf("lights" to "tv", "lights_strength" to "punchy", "lights_offset_ms" to "40", "lights_band_pct" to "30", "lights_synced_style" to "FOLLOW_VIDEO/GAME", "lights_native_duo" to "rm -rf"))
        assertEquals("tv", o.mode)
        assertEquals("punchy", o.strength)
        assertEquals(40, o.offsetMs)
        assertEquals(5, o.bandPct)
        assertEquals("FOLLOW_VIDEO/GAME", o.syncedStyle)
        assertEquals(listOf("lights_band_pct=30", "lights_native_duo=rm -rf"), refused)
        assertEquals(o, LightOptions.of(o.toJson().toString()))
        assertTrue(LightOptions().apply(mapOf("lights_strength" to "none")).second.isNotEmpty())
    }

    @Test
    fun lightsOnLuminaLeaveThePulsesToTheTv() {
        assertTrue(LightOptions(syncedStyle = "FOLLOW_AUDIO/ENERGY_ADAPTIVE_BRIGHTNESS").tvPulses)
        assertFalse(LightOptions(syncedStyle = "FOLLOW_VIDEO/GAME").tvPulses)
    }

    @Test
    fun theTestPicturesAreWhatTheySay() {
        val split = LightPatterns("split").at(0)
        assertEquals(Colors.rgb(255, 0, 0), split[0])
        assertEquals(Colors.rgb(0, 255, 0), split[Zones.index("top", 3)])
        assertEquals(Colors.rgb(0, 0, 255), split[Zones.index("right", 3)])
        val zones = LightPatterns("zones").at(0)
        assertEquals(Zones.COUNT, zones.toSet().size)
        val chase = LightPatterns("chase", stepMs = 100)
        assertEquals(Colors.rgb(255, 255, 255), chase.at(250)[2])
        assertEquals(Colors.rgb(0, 0, 0), chase.at(250)[3])
        val pulse = LightPatterns("pulse", Colors.rgb(200, 100, 0), bpm = 120.0)
        assertTrue(Colors.peak(pulse.at(500)[0]) > Colors.peak(pulse.at(900)[0]))
        val steps = LightPatterns("steps", Colors.rgb(255, 255, 255), stepMs = 200)
        assertEquals(Colors.rgb(0, 0, 0), steps.at(100)[0])
        assertEquals(Colors.rgb(255, 255, 255), steps.at(300)[0])
        assertEquals(Colors.rgb(255, 136, 0), LightPatterns.parseColor("#ff8800"))
        assertNull(LightPatterns.parseColor("orange"))
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
        var lights: String? = NativeStyles.plain("FOLLOW_VIDEO/GAME")
        val c = AmbilightControl(tv, NoStore(), { "FOLLOW_AUDIO" }, { "ENERGY_ADAPTIVE_BRIGHTNESS" }, { lights })
        assertTrue(c.enter())
        assertEquals("FOLLOW_VIDEO", JSONObject(tv.configs.last()).getString("styleName"))
        lights = null
        c.restyle()
        assertEquals("ENERGY_ADAPTIVE_BRIGHTNESS", JSONObject(tv.configs.last()).getString("menuSetting"))
    }
}
