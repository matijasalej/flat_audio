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
        assertEquals(80, o.getInt("capture_lead_ms"))
        assertEquals(322, o.getInt("kitchen_target_ms"))
        assertEquals(316, o.getInt("kitchen_floor_ms"))
        assertEquals(-1, o.getInt("kitchen_volume"))
        assertEquals(false, o.getBoolean("capture_volume_mirror"))
        assertEquals("auto", o.getString("kitchen_format"))
        assertEquals(4096, o.getInt("kitchen_flac_block"))
        assertEquals("synced", o.getString("mode"))
        assertEquals(300, o.getInt("tv_dlna_latency_ms"))
        assertEquals(0, o.getInt("tv_dlna_leadin_ms"))
        assertEquals(16, o.getInt("tv_dlna_sndbuf_kb"))
    }

    @Test
    fun theJsonHasTheSevenKeysTheMacToolReadsTheFourOfTheCaptureModeAndTheTwoOfTheStreamFormat() {
        val keys = SettingsModel().toJson().keys().asSequence().toSet()
        assertEquals(
            setOf(
                "kitchen_enabled", "kitchen_offset_ms", "kitchen_balance", "living_fixed_volume",
                "tv_screen_off", "tv_wake", "tv_ip",
                "capture_enabled", "capture_living_latency_ms", "capture_lead_ms", "kitchen_target_ms", "kitchen_floor_ms",
                "kitchen_volume", "capture_volume_mirror", "kitchen_format", "kitchen_flac_block",
                "mode", "tv_dlna_latency_ms", "tv_dlna_leadin_ms", "tv_dlna_sndbuf_kb",
            ),
            keys,
        )
    }

    @Test
    fun theStreamFormatTakesAutoFlacOrWavInAnyCaseAndIgnoresTheRest() {
        val m = SettingsModel()
        assertEquals("auto", m.kitchenFormat)
        assertEquals(setOf("kitchen_format"), m.applyJson(JSONObject("""{"kitchen_format": "wav"}""")))
        assertEquals("wav", m.kitchenFormat)
        assertEquals("wav", m.values().kitchenFormat)
        assertEquals(setOf("kitchen_format"), m.applyJson(JSONObject("""{"kitchen_format": " FLAC "}""")))
        assertEquals("flac", m.kitchenFormat)
        assertEquals(setOf("kitchen_format"), m.applyJson(JSONObject("""{"kitchen_format": "Auto"}""")))
        assertEquals("auto", m.kitchenFormat)
        // not a format: nothing changes
        assertTrue(m.applyJson(JSONObject("""{"kitchen_format": "mp3"}""")).isEmpty())
        assertTrue(m.applyJson(JSONObject("""{"kitchen_format": ""}""")).isEmpty())
        assertTrue(m.applyJson(JSONObject("""{"kitchen_format": 3}""")).isEmpty())
        assertEquals("auto", m.kitchenFormat)
        // the same again is no change
        assertTrue(m.applyJson(JSONObject("""{"kitchen_format": "auto"}""")).isEmpty())
    }

    @Test
    fun autoMeansWavForTheCaptureAndFlacForSpotify() {
        assertEquals(StreamFormat.WAV, StreamFormat.resolve("auto", capture = true))
        assertEquals(StreamFormat.FLAC, StreamFormat.resolve("auto", capture = false))
        assertEquals(StreamFormat.FLAC, StreamFormat.resolve("flac", capture = true))
        assertEquals(StreamFormat.WAV, StreamFormat.resolve("wav", capture = false))
        // a value that is none of them falls back to the choice of auto
        assertEquals(StreamFormat.WAV, StreamFormat.resolve("mp3", capture = true))
        assertEquals(StreamFormat.FLAC, StreamFormat.resolve(null, capture = false))
    }

    @Test
    fun theTargetLagIs322ByDefaultAndTakesNumbersFromZeroUp() {
        val m = SettingsModel()
        assertEquals(322, m.kitchenTargetMs)
        assertEquals(setOf("kitchen_target_ms"), m.applyJson(JSONObject("""{"kitchen_target_ms": 240}""")))
        assertEquals(240, m.values().kitchenTargetMs)
        assertEquals(setOf("kitchen_target_ms"), m.applyJson(JSONObject("""{"kitchen_target_ms": "250.4"}""")))
        assertEquals(250, m.kitchenTargetMs)
        assertEquals(setOf("kitchen_target_ms"), m.applyJson(JSONObject("""{"kitchen_target_ms": 0}""")))
        assertEquals(0, m.kitchenTargetMs)
        m.applyJson(JSONObject("""{"kitchen_target_ms": 99999}"""))
        assertEquals(SettingsModel.MAX_KITCHEN_TARGET_MS, m.kitchenTargetMs)
        // below zero, off, none, an empty text or null switch it off again
        for (off in listOf("-5", "-1", "\"off\"", "\"none\"", "\"\"", "null")) {
            m.applyJson(JSONObject("""{"kitchen_target_ms": 300}"""))
            assertEquals(setOf("kitchen_target_ms"), m.applyJson(JSONObject("""{"kitchen_target_ms": $off}""")))
            assertEquals("for $off", -1, m.kitchenTargetMs)
        }
        // words that mean nothing change nothing
        m.applyJson(JSONObject("""{"kitchen_target_ms": 300}"""))
        assertTrue(m.applyJson(JSONObject("""{"kitchen_target_ms": "soon"}""")).isEmpty())
        assertEquals(300, m.kitchenTargetMs)
    }

    @Test
    fun theModeIsSyncedOrLyricsAndNothingElse() {
        val m = SettingsModel()
        assertEquals("synced", m.mode)
        assertEquals(setOf("mode"), m.applyJson(JSONObject("""{"mode": "lyrics"}""")))
        assertEquals("lyrics", m.values().mode)
        assertEquals(setOf("mode"), m.applyJson(JSONObject("""{"mode": " SYNCED "}""")))
        assertEquals("synced", m.mode)
        assertTrue(m.applyJson(JSONObject("""{"mode": "both"}""")).isEmpty())
        assertTrue(m.applyJson(JSONObject("""{"mode": 3}""")).isEmpty())
        assertTrue(m.applyJson(JSONObject("""{"mode": ""}""")).isEmpty())
        assertEquals("synced", m.mode)
    }

    @Test
    fun theTvTimingModelIsClamped() {
        val m = SettingsModel()
        assertEquals(setOf("tv_dlna_latency_ms", "tv_dlna_leadin_ms", "tv_dlna_sndbuf_kb"), m.applyJson(JSONObject("""{"tv_dlna_latency_ms": 280, "tv_dlna_leadin_ms": 150, "tv_dlna_sndbuf_kb": 0}""")))
        assertEquals(280, m.values().tvDlnaLatencyMs)
        assertEquals(150, m.values().tvDlnaLeadinMs)
        assertEquals(0, m.values().tvDlnaSndbufKb)
        m.applyJson(JSONObject("""{"tv_dlna_latency_ms": -50, "tv_dlna_leadin_ms": 99999, "tv_dlna_sndbuf_kb": 9999}"""))
        assertEquals(0, m.tvDlnaLatencyMs)
        assertEquals(SettingsModel.MAX_TV_DLNA_MS, m.tvDlnaLeadinMs)
        assertEquals(SettingsModel.MAX_TV_DLNA_SNDBUF_KB, m.tvDlnaSndbufKb)
        assertTrue(m.applyJson(JSONObject("""{"tv_dlna_latency_ms": "slow"}""")).isEmpty())
    }

    @Test
    fun theFloorIsAtLeastTheLeastLagAndAtMostTheTargetRange() {
        val m = SettingsModel()
        assertEquals(316, m.values().kitchenFloorMs)
        assertEquals(setOf("kitchen_floor_ms"), m.applyJson(JSONObject("""{"kitchen_floor_ms": 330}""")))
        assertEquals(330, m.kitchenFloorMs)
        m.applyJson(JSONObject("""{"kitchen_floor_ms": 20}"""))
        assertEquals(SettingsModel.MIN_KITCHEN_FLOOR_MS, m.kitchenFloorMs)
        m.applyJson(JSONObject("""{"kitchen_floor_ms": 99999}"""))
        assertEquals(SettingsModel.MAX_KITCHEN_TARGET_MS, m.kitchenFloorMs)
        assertTrue(m.applyJson(JSONObject("""{"kitchen_floor_ms": "low"}""")).isEmpty())
    }

    @Test
    fun theKitchenVolumeIsAPercentOrOff() {
        val m = SettingsModel()
        assertEquals(-1, m.kitchenVolume)
        assertEquals(setOf("kitchen_volume"), m.applyJson(JSONObject("""{"kitchen_volume": 35}""")))
        assertEquals(35, m.values().kitchenVolume)
        m.applyJson(JSONObject("""{"kitchen_volume": 250}"""))
        assertEquals(100, m.kitchenVolume)
        m.applyJson(JSONObject("""{"kitchen_volume": "0"}"""))
        assertEquals(0, m.kitchenVolume)
        for (off in listOf("-5", "\"off\"", "\"\"", "null")) {
            m.applyJson(JSONObject("""{"kitchen_volume": 40}"""))
            assertEquals(setOf("kitchen_volume"), m.applyJson(JSONObject("""{"kitchen_volume": $off}""")))
            assertEquals("for $off", -1, m.kitchenVolume)
        }
        m.applyJson(JSONObject("""{"kitchen_volume": 40}"""))
        assertTrue(m.applyJson(JSONObject("""{"kitchen_volume": "loud"}""")).isEmpty())
        assertEquals(40, m.kitchenVolume)
    }

    @Test
    fun theMirrorOfTheBoxsMusicVolumeIsOffByDefault() {
        val m = SettingsModel()
        assertEquals(false, m.captureVolumeMirror)
        assertEquals(setOf("capture_volume_mirror"), m.applyJson(JSONObject("""{"capture_volume_mirror": "on"}""")))
        assertTrue(m.values().captureVolumeMirror)
    }

    @Test
    fun aFlacStoredByAnOlderVersionWasTheOldDefaultAndBecomesAuto() {
        val stored = JSONObject("""{"kitchen_format": "flac", "kitchen_offset_ms": 20}""")
        SettingsModel.migrate(stored, version = 1)
        val m = SettingsModel()
        m.applyJson(stored)
        assertEquals("auto", m.kitchenFormat)
        assertEquals(20, m.kitchenOffsetMs)
        // a wav was a choice, and so is a flac of the current version
        val wav = JSONObject("""{"kitchen_format": "wav"}""")
        SettingsModel.migrate(wav, version = 1)
        assertEquals("wav", wav.getString("kitchen_format"))
        val current = JSONObject("""{"kitchen_format": "flac"}""")
        SettingsModel.migrate(current, SettingsModel.VERSION)
        assertEquals("flac", current.getString("kitchen_format"))
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
        m.applyJson(JSONObject("""{"kitchen_format": "wav", "kitchen_flac_block": 1152, "kitchen_target_ms": 240, "kitchen_floor_ms": 300, "kitchen_volume": 33}"""))
        val reloaded = SettingsModel()
        reloaded.applyJson(m.toJson())
        assertEquals("wav", reloaded.kitchenFormat)
        assertEquals(1152, reloaded.kitchenFlacBlock)
        assertEquals(240, reloaded.kitchenTargetMs)
        assertEquals(300, reloaded.kitchenFloorMs)
        assertEquals(33, reloaded.kitchenVolume)
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
        assertEquals(10, SettingsModel.MIN_CAPTURE_LEAD_MS)
        m.applyJson(JSONObject("""{"capture_lead_ms": 3}"""))
        assertEquals(SettingsModel.MIN_CAPTURE_LEAD_MS, m.captureLeadMs)
        m.applyJson(JSONObject("""{"capture_lead_ms": 10}"""))
        assertEquals(10, m.values().captureLeadMs)
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
