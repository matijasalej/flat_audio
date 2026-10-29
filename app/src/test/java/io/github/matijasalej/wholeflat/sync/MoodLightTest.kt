package io.github.matijasalej.wholeflat.sync

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoodLightTest {
    @Test
    fun theMoodsHaveTheirOwnKeysAndColours() {
        val keys = MoodLight.MOODS.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        for (m in MoodLight.MOODS) {
            assertEquals(m.key, m.color, MoodLight.parseColor(m.color))
            assertTrue(m.key, m.label.isNotEmpty())
        }
        assertEquals("Cozy", MoodLight.moodOf("#FF9A40")!!.label)
        assertNull(MoodLight.moodOf("#123456"))
        assertEquals("cozy", MoodLight.moodOf(MoodLight.DEFAULT_COLOR)!!.key)
        assertEquals(MoodLight.MOODS.size, MoodLight.moodsJson().length())
    }

    @Test
    fun aColourIsSixHexDigitsWithOrWithoutTheHash() {
        assertEquals("#ff8800", MoodLight.parseColor("#FF8800"))
        assertEquals("#ff8800", MoodLight.parseColor(" ff8800 "))
        assertNull(MoodLight.parseColor("#f80"))
        assertNull(MoodLight.parseColor("#gg8800"))
        assertNull(MoodLight.parseColor(""))
        assertNull(MoodLight.parseColor(null))
    }

    @Test
    fun theHueIsTheAngleScaledTo255AsOnTheTv() {
        // seen on the TV: hue 0 red, 85 green, 170 blue, at saturation and brightness 255
        assertArrayEquals(intArrayOf(0, 255, 255), MoodLight.hsb("#ff0000", 100))
        assertArrayEquals(intArrayOf(85, 255, 255), MoodLight.hsb("#00ff00", 100))
        assertArrayEquals(intArrayOf(170, 255, 255), MoodLight.hsb("#0000ff", 100))
        assertEquals(21, MoodLight.hsb("#ff8000", 100)[0])
        val pink = MoodLight.hsb("#ff0080", 100)[0]
        assertTrue("$pink", pink in 200..255)
    }

    @Test
    fun theLevelIsAPercentOfTheBrightnessAndWhiteHasNoSaturation() {
        assertEquals(255, MoodLight.hsb("#ff0000", 100)[2])
        assertEquals(128, MoodLight.hsb("#ff0000", 50)[2])
        assertEquals(26, MoodLight.hsb("#ff0000", 10)[2])
        assertEquals(64, MoodLight.hsb("#800000", 50)[2])
        assertEquals(0, MoodLight.hsb("#ffffff", 100)[1])
        assertArrayEquals(intArrayOf(0, 0, 0), MoodLight.hsb("nothing", 100))
    }

    @Test
    fun theBodyIsTheExpertFollowColourStillOrPulsing() {
        val o = JSONObject(MoodLight.json("#0000ff", 50))
        assertEquals("FOLLOW_COLOR", o.getString("styleName"))
        assertEquals(true, o.getBoolean("isExpert"))
        assertEquals("MANUAL_HUE", o.getString("algorithm"))
        val settings = o.getJSONObject("colorSettings")
        val color = settings.getJSONObject("color")
        assertEquals(170, color.getInt("hue"))
        assertEquals(128, color.getInt("brightness"))
        val delta = settings.getJSONObject("colorDelta")
        assertEquals(0, delta.getInt("hue"))
        // still by default
        assertEquals(0, delta.getInt("brightness"))
        assertEquals(0, settings.getInt("speed"))
        val pulsing = JSONObject(MoodLight.json("#0000ff", 100, pulse = true)).getJSONObject("colorSettings")
        assertEquals(MoodLight.PULSE_BRIGHTNESS, pulsing.getJSONObject("colorDelta").getInt("brightness"))
        assertEquals(MoodLight.PULSE_SPEED, pulsing.getInt("speed"))
        assertEquals(255, pulsing.getJSONObject("color").getInt("brightness"))
        // a dim light does not pulse below black
        assertEquals(26, JSONObject(MoodLight.json("#ff0000", 10, pulse = true)).getJSONObject("colorSettings").getJSONObject("colorDelta").getInt("brightness"))
        assertEquals("FOLLOW_COLOR/MANUAL_HUE", AmbilightControl.styleName(MoodLight.json("#ff9a40", 80)))
    }
}
