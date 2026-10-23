package io.github.matijasalej.wholeflat.sync

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsModelTest {
    @Test
    fun theDefaultsAreTheDocumentedOnes() {
        val o = SettingsModel().toJson()
        assertEquals(true, o.getBoolean("kitchen_enabled"))
        assertEquals(0, o.getInt("kitchen_offset_ms"))
        assertEquals(0, o.getInt("kitchen_balance"))
        assertEquals(true, o.getBoolean("living_fixed_volume"))
        assertEquals(true, o.getBoolean("tv_screen_off"))
        assertEquals(true, o.getBoolean("tv_wake"))
        assertEquals("", o.getString("tv_ip"))
        assertEquals(true, o.getBoolean("capture_enabled"))
        assertEquals(0, o.getInt("capture_living_latency_ms"))
        assertEquals(150, o.getInt("capture_lead_ms"))
        assertEquals("flac", o.getString("kitchen_format"))
        assertEquals(4096, o.getInt("kitchen_flac_block"))
    }

    @Test
    fun theJsonHasTheSevenKeysTheMacToolReadsTheThreeOfTheCaptureModeAndTheTwoOfTheStreamFormat() {
        val keys = SettingsModel().toJson().keys().asSequence().toSet()
        assertEquals(
            setOf(
                "kitchen_enabled", "kitchen_offset_ms", "kitchen_balance", "living_fixed_volume",
                "tv_screen_off", "tv_wake", "tv_ip",
                "capture_enabled", "capture_living_latency_ms", "capture_lead_ms",
                "kitchen_format", "kitchen_flac_block",
            ),
            keys,
        )
    }

    @Test
    fun theStreamFormatTakesFlacOrWavInAnyCaseAndIgnoresTheRest() {
        val m = SettingsModel()
        assertEquals(setOf("kitchen_format"), m.applyJson(JSONObject("""{"kitchen_format": "wav"}""")))
        assertEquals("wav", m.kitchenFormat)
        assertEquals("wav", m.values().kitchenFormat)
        assertEquals(setOf("kitchen_format"), m.applyJson(JSONObject("""{"kitchen_format": " FLAC "}""")))
        assertEquals("flac", m.kitchenFormat)
        // not a format: nothing changes
        assertTrue(m.applyJson(JSONObject("""{"kitchen_format": "mp3"}""")).isEmpty())
        assertTrue(m.applyJson(JSONObject("""{"kitchen_format": ""}""")).isEmpty())
        assertTrue(m.applyJson(JSONObject("""{"kitchen_format": 3}""")).isEmpty())
        assertEquals("flac", m.kitchenFormat)
        // the same again is no change
        assertTrue(m.applyJson(JSONObject("""{"kitchen_format": "flac"}""")).isEmpty())
    }

    @Test
    fun theFlacBlockTakesTheThreeMeasuredSizesAsNumbersOrText() {
        val m = SettingsModel()
        assertEquals(setOf("kitchen_flac_block"), m.applyJson(JSONObject("""{"kitchen_flac_block": 1152}""")))
        assertEquals(1152, m.kitchenFlacBlock)
        assertEquals(setOf("kitchen_flac_block"), m.applyJson(JSONObject("""{"kitchen_flac_block": "576"}""")))
        assertEquals(576, m.values().kitchenFlacBlock)
        assertEquals(setOf("kitchen_flac_block"), m.applyJson(JSONObject("""{"kitchen_flac_block": 4096.0}""")))
        assertEquals(4096, m.kitchenFlacBlock)
        // another size is not taken, nor is a word
        assertTrue(m.applyJson(JSONObject("""{"kitchen_flac_block": 1000}""")).isEmpty())
        assertTrue(m.applyJson(JSONObject("""{"kitchen_flac_block": 0}""")).isEmpty())
        assertTrue(m.applyJson(JSONObject("""{"kitchen_flac_block": "big"}""")).isEmpty())
        assertEquals(4096, m.kitchenFlacBlock)
    }

    @Test
    fun theStreamFormatSettingsSurviveAStoreAndAReload() {
        val m = SettingsModel()
        m.applyJson(JSONObject("""{"kitchen_format": "wav", "kitchen_flac_block": 1152}"""))
        val reloaded = SettingsModel()
        reloaded.applyJson(m.toJson())
        assertEquals("wav", reloaded.kitchenFormat)
        assertEquals(1152, reloaded.kitchenFlacBlock)
    }

    @Test
    fun theCaptureSettingsAreClampedAndAcceptWords() {
        val m = SettingsModel()
        m.applyJson(JSONObject("""{"capture_living_latency_ms": 5000, "capture_enabled": "off"}"""))
        assertEquals(SettingsModel.MAX_CAPTURE_LATENCY_MS, m.captureLivingLatencyMs)
        assertFalse(m.captureEnabled)
        m.applyJson(JSONObject("""{"capture_living_latency_ms": -30}"""))
        assertEquals(-30, m.captureLivingLatencyMs)
        assertEquals(-30, m.values().captureLivingLatencyMs)
    }

    @Test
    fun theCaptureLeadIsClamped() {
        val m = SettingsModel()
        m.applyJson(JSONObject("""{"capture_lead_ms": 10}"""))
        assertEquals(SettingsModel.MIN_CAPTURE_LEAD_MS, m.captureLeadMs)
        m.applyJson(JSONObject("""{"capture_lead_ms": 99999}"""))
        assertEquals(SettingsModel.MAX_CAPTURE_LEAD_MS, m.captureLeadMs)
        val changed = m.applyJson(JSONObject("""{"capture_lead_ms": "400"}"""))
        assertEquals(setOf("capture_lead_ms"), changed)
        assertEquals(400, m.values().captureLeadMs)
    }

    @Test
    fun aPartialBodyChangesOnlyWhatItNames() {
        val m = SettingsModel()
        val changed = m.applyJson(JSONObject("""{"kitchen_offset_ms": 35, "tv_wake": false}"""))
        assertEquals(setOf("kitchen_offset_ms", "tv_wake"), changed)
        assertEquals(35, m.kitchenOffsetMs)
        assertFalse(m.tvWake)
        assertTrue(m.kitchenEnabled)
        assertTrue(m.livingFixedVolume)
    }

    @Test
    fun aValueThatDoesNotChangeIsNotReportedAsChanged() {
        val m = SettingsModel()
        assertTrue(m.applyJson(JSONObject("""{"kitchen_enabled": true, "kitchen_offset_ms": 0}""")).isEmpty())
    }

    @Test
    fun positiveOffsetsAreKept() {
        val m = SettingsModel()
        m.applyJson(JSONObject("""{"kitchen_offset_ms": 12.6}"""))
        assertEquals(13, m.kitchenOffsetMs)
        m.applyJson(JSONObject("""{"kitchen_offset_ms": -40}"""))
        assertEquals(-40, m.kitchenOffsetMs)
    }

    @Test
    fun valuesAreClamped() {
        val m = SettingsModel()
        m.applyJson(JSONObject("""{"kitchen_offset_ms": 99999, "kitchen_balance": -500}"""))
        assertEquals(SettingsModel.MAX_OFFSET_MS, m.kitchenOffsetMs)
        assertEquals(-SettingsModel.MAX_BALANCE, m.kitchenBalance)
    }

    @Test
    fun booleansAcceptNumbersAndWords() {
        val m = SettingsModel()
        m.applyJson(JSONObject("""{"kitchen_enabled": 0, "tv_screen_off": "off", "living_fixed_volume": "yes"}"""))
        assertFalse(m.kitchenEnabled)
        assertFalse(m.tvScreenOff)
        assertTrue(m.livingFixedVolume)
    }

    @Test
    fun garbageIsIgnored() {
        val m = SettingsModel()
        val changed = m.applyJson(
            JSONObject("""{"kitchen_enabled": "maybe", "kitchen_offset_ms": "abc", "tv_ip": "a b;c", "unknown": 1}"""),
        )
        assertTrue(changed.isEmpty())
        assertTrue(m.kitchenEnabled)
        assertEquals(0, m.kitchenOffsetMs)
        assertEquals("", m.tvIp)
    }

    @Test
    fun theTvAddressCanBeSetAndCleared() {
        val m = SettingsModel()
        m.applyJson(JSONObject("""{"tv_ip": " 192.168.0.69 "}"""))
        assertEquals("192.168.0.69", m.tvIp)
        m.applyJson(JSONObject("""{"tv_ip": ""}"""))
        assertEquals("", m.tvIp)
        m.applyJson(JSONObject("""{"tv_ip": "tv.local"}"""))
        assertEquals("tv.local", m.tvIp)
        m.applyJson(JSONObject("""{"tv_ip": null}"""))
        assertEquals("", m.tvIp)
    }

    @Test
    fun theValuesSnapshotMatches() {
        val m = SettingsModel()
        m.applyJson(JSONObject("""{"kitchen_balance": 7, "tv_ip": "10.0.0.5"}"""))
        val v = m.values()
        assertEquals(7, v.kitchenBalance)
        assertEquals("10.0.0.5", v.tvIp)
        assertTrue(v.kitchenEnabled)
    }
}
