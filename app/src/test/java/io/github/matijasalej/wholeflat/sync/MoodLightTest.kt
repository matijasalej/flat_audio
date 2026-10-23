package io.github.matijasalej.wholeflat.sync

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoodLightTest {
    @Test
    fun theMoodsHaveTheirOwnKeysAndColours() {
        val keys = MoodLight.MOODS.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        assertFalse(MoodLight.OFF in keys)
        assertFalse(MoodLight.CUSTOM in keys)
        for (m in MoodLight.MOODS) {
            assertEquals(m.key, m.color, MoodLight.parseColor(m.color))
            assertTrue(m.key, m.label.isNotEmpty())
        }
        assertEquals("Cozy", MoodLight.of(" COZY ")!!.label)
        assertNull(MoodLight.of("party"))
    }

    @Test
    fun theSettingIsOffCustomOrAMoodInAnyCase() {
        assertEquals("off", MoodLight.settingOf(" Off "))
        assertEquals("custom", MoodLight.settingOf("CUSTOM"))
        assertEquals("ocean", MoodLight.settingOf("Ocean"))
        assertNull(MoodLight.settingOf("disco"))
        assertNull(MoodLight.settingOf(""))
        assertNull(MoodLight.settingOf(null))
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
    fun theColourOfTheLightIsTheMoodsOrTheCustomOne() {
        assertEquals("#2f80ff", MoodLight.colorOf("ocean", "#123456"))
        assertEquals("#123456", MoodLight.colorOf("custom", "#123456"))
        assertNull(MoodLight.colorOf("off", "#123456"))
        assertEquals("Ocean", MoodLight.labelOf("ocean"))
        assertEquals("Custom", MoodLight.labelOf("custom"))
        assertEquals("Off", MoodLight.labelOf("off"))
    }

    @Test
    fun theHueIsTheAngleScaledTo255AsOnTheTv() {
        // seen on the TV: hue 0 red, 85 green, 170 blue, at saturation and brightness 255
        assertArrayEquals(intArrayOf(0, 255, 255), MoodLight.hsb("#ff0000", 100))
        assertArrayEquals(intArrayOf(85, 255, 255), MoodLight.hsb("#00ff00", 100))
        assertArrayEquals(intArrayOf(170, 255, 255), MoodLight.hsb("#0000ff", 100))
        // a warm orange: 30 degrees
        assertEquals(21, MoodLight.hsb("#ff8000", 100)[0])
        // a red a little towards blue wraps round below 255, not to a negative hue
        val pink = MoodLight.hsb("#ff0080", 100)[0]
        assertTrue("$pink", pink in 200..255)
    }

    @Test
    fun theLevelIsAPercentOfTheBrightnessAndWhiteHasNoSaturation() {
        assertEquals(255, MoodLight.hsb("#ff0000", 100)[2])
        assertEquals(128, MoodLight.hsb("#ff0000", 50)[2])
        assertEquals(26, MoodLight.hsb("#ff0000", 10)[2])
        // a darker colour is darker at the same level
        assertEquals(64, MoodLight.hsb("#800000", 50)[2])
        assertEquals(0, MoodLight.hsb("#ffffff", 100)[1])
        assertEquals(255, MoodLight.hsb("#ffffff", 100)[2])
        // not a colour: black
        assertArrayEquals(intArrayOf(0, 0, 0), MoodLight.hsb("nothing", 100))
    }

    @Test
    fun theBodyIsTheExpertFollowColourWithAStillColour() {
        val o = JSONObject(MoodLight.json("#0000ff", 50))
        assertEquals("FOLLOW_COLOR", o.getString("styleName"))
        assertEquals(true, o.getBoolean("isExpert"))
        assertEquals("MANUAL_HUE", o.getString("algorithm"))
        val settings = o.getJSONObject("colorSettings")
        val color = settings.getJSONObject("color")
        assertEquals(170, color.getInt("hue"))
        assertEquals(255, color.getInt("saturation"))
        assertEquals(128, color.getInt("brightness"))
        val delta = settings.getJSONObject("colorDelta")
        assertEquals(0, delta.getInt("hue"))
        assertEquals(0, delta.getInt("saturation"))
        assertEquals(0, delta.getInt("brightness"))
        assertEquals(0, settings.getInt("speed"))
        assertEquals("FOLLOW_COLOR", AmbilightControl.styleName(MoodLight.json("#ff9a40", 80)))
    }
}
