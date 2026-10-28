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
        assertEquals(SettingsModel.DEFAULT_MODE, o.getString("mode"))
        assertEquals(-1, o.getInt("tv_latency_ms"))
        assertEquals("ts", o.getString("tv_format"))
        assertEquals(1500, o.getInt("tv_burst_ms"))
        assertEquals(631, o.getInt("tv_extra_ms"))
        assertEquals(1050, o.getInt("tv_preroll_ms"))
        assertEquals(700, o.getInt("tv_pcr_lead_ms"))
        assertEquals(0, o.getInt("tv_reanchor_min"))
        assertEquals("auto", o.getString("tv_render"))
        assertEquals("720p30 cbr 2500 gop1", o.getString("tv_video"))
        assertEquals(0, o.getInt("lyrics_offset_ms"))
        assertEquals("listen", o.getString("display"))
        assertEquals(true, o.getBoolean("background_cleanup"))
        assertEquals("FOLLOW_AUDIO", o.getString("ambilight_style"))
        assertEquals("ENERGY_ADAPTIVE_BRIGHTNESS", o.getString("ambilight_menu"))
        assertEquals(100, o.getInt("tv_pause_ms"))
        assertEquals(0, o.getInt("tv_hold_silence_min"))
        assertEquals(180, o.getInt("tv_boot_wait_s"))
        assertEquals(300, o.getInt("tv_close_wait_s"))
        assertEquals(5000, o.getInt("tv_ready_ms"))
        assertEquals(true, o.getBoolean("tv_clear_item"))
        assertEquals(false, o.getBoolean("tv_quiet"))
        assertEquals(false, o.getBoolean("tv_audio_only"))
        assertEquals("C0:D2:F3:87:BF:F4", o.getString("tv_mac"))
        assertEquals(true, o.getBoolean("tv_position_probe"))
        assertEquals("FOLLOW_AUDIO", o.getString("ambilight_dark_style"))
        assertEquals("PARTY", o.getString("ambilight_dark_algorithm"))
        assertEquals("", o.getString("ambilight_dark_menu"))
        assertEquals(false, o.getBoolean("tv_auto_reanchor"))
        assertEquals(false, o.getBoolean("tv_reanchor_running"))
        assertEquals("smooth", o.getString("tv_scroll"))
    }

    @Test
    fun theScrollOfTheLyricsIsSmoothOrInstant() {
        val m = SettingsModel()
        assertEquals(setOf("tv_scroll"), m.applyJson(JSONObject("""{"tv_scroll": " Instant "}""")))
        assertEquals("instant", m.values().tvScroll)
        assertEquals(emptySet<String>(), m.applyJson(JSONObject("""{"tv_scroll": "fast"}""")))
        assertEquals("instant", m.tvScroll)
    }

    @Test
    fun theRisksOfAReanchorAreOptInAndOff() {
        val m = SettingsModel()
        assertEquals(false, m.values().tvAutoReanchor)
        assertEquals(false, m.values().tvReanchorRunning)
        assertEquals(setOf("tv_auto_reanchor", "tv_reanchor_running"), m.applyJson(JSONObject("""{"tv_auto_reanchor": true, "tv_reanchor_running": "yes"}""")))
        assertEquals(true, m.values().tvAutoReanchor)
        assertEquals(true, m.values().tvReanchorRunning)
    }

    @Test
    fun theLightsOfTheScreenOffDisplayTakeAStyleAnAlgorithmAndAMenuSetting() {
        val m = SettingsModel()
        assertEquals(setOf("ambilight_dark_style", "ambilight_dark_algorithm"), m.applyJson(JSONObject("""{"ambilight_dark_style": "follow_color", "ambilight_dark_algorithm": "hot_lava"}""")))
        assertEquals("FOLLOW_COLOR", m.values().ambilightDarkStyle)
        assertEquals("HOT_LAVA", m.values().ambilightDarkAlgorithm)
        // an empty algorithm is the plain form, with a menu setting
        assertEquals(setOf("ambilight_dark_algorithm", "ambilight_dark_menu"), m.applyJson(JSONObject("""{"ambilight_dark_algorithm": "", "ambilight_dark_menu": "party"}""")))
        assertEquals("", m.ambilightDarkAlgorithm)
        assertEquals("PARTY", m.ambilightDarkMenu)
        // nonsense is ignored
        assertEquals(emptySet<String>(), m.applyJson(JSONObject("""{"ambilight_dark_style": "not a style", "ambilight_dark_algorithm": "x y"}""")))
    }

    @Test
    fun theStallSettingsAreClampedAndTheMacIsChecked() {
        val m = SettingsModel()
        assertEquals(setOf("tv_pause_ms"), m.applyJson(JSONObject("""{"tv_pause_ms": 99999}""")))
        assertEquals(2000, m.tvPauseMs)
        assertEquals(setOf("tv_pause_ms"), m.applyJson(JSONObject("""{"tv_pause_ms": 0}""")))
        assertEquals(0, m.values().tvPauseMs)
        assertEquals(setOf("tv_hold_silence_min"), m.applyJson(JSONObject("""{"tv_hold_silence_min": 600}""")))
        assertEquals(30, m.values().tvHoldSilenceMin)
        assertEquals(setOf("tv_hold_silence_min"), m.applyJson(JSONObject("""{"tv_hold_silence_min": -5}""")))
        assertEquals(0, m.values().tvHoldSilenceMin)
        assertEquals(setOf("tv_boot_wait_s", "tv_close_wait_s"), m.applyJson(JSONObject("""{"tv_boot_wait_s": 99999, "tv_close_wait_s": -1}""")))
        assertEquals(1800, m.values().tvBootWaitS)
        assertEquals(0, m.values().tvCloseWaitS)
        assertEquals(setOf("tv_ready_ms"), m.applyJson(JSONObject("""{"tv_ready_ms": 99999}""")))
        assertEquals(30000, m.values().tvReadyMs)
        assertEquals(setOf("tv_ready_ms"), m.applyJson(JSONObject("""{"tv_ready_ms": -5}""")))
        assertEquals(0, m.values().tvReadyMs)
        assertEquals(setOf("tv_clear_item"), m.applyJson(JSONObject("""{"tv_clear_item": "off"}""")))
        assertEquals(false, m.values().tvClearItem)
        assertEquals(setOf("tv_quiet", "tv_audio_only", "tv_position_probe"), m.applyJson(JSONObject("""{"tv_quiet": true, "tv_audio_only": "on", "tv_position_probe": 0}""")))
        assertEquals(true, m.values().tvQuiet)
        assertEquals(true, m.values().tvAudioOnly)
        assertEquals(false, m.values().tvPositionProbe)
        assertEquals(setOf("tv_mac"), m.applyJson(JSONObject("""{"tv_mac": "aa-bb-cc-00-11-22"}""")))
        assertEquals("AA:BB:CC:00:11:22", m.tvMac)
        assertEquals(emptySet<String>(), m.applyJson(JSONObject("""{"tv_mac": "not a mac"}""")))
        assertEquals("AA:BB:CC:00:11:22", m.tvMac)
        assertEquals(setOf("tv_mac"), m.applyJson(JSONObject("""{"tv_mac": ""}""")))
        assertEquals("", m.values().tvMac)
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
                "mode", "tv_latency_ms", "tv_format", "tv_burst_ms", "tv_extra_ms", "tv_preroll_ms", "tv_pcr_lead_ms", "tv_reanchor_min", "tv_render", "tv_video", "lyrics_offset_ms", "display", "background_cleanup",
                "ambilight_style", "ambilight_menu", "tv_pause_ms", "tv_quiet", "tv_audio_only", "tv_mac", "tv_position_probe",
                "ambilight_dark_style", "ambilight_dark_algorithm", "ambilight_dark_menu", "tv_auto_reanchor", "tv_reanchor_running", "tv_scroll",
                "tv_hold_silence_min", "tv_ready_ms", "tv_clear_item", "tv_boot_wait_s", "tv_close_wait_s",
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
        assertEquals(SettingsModel.DEFAULT_MODE, m.mode)
        m.applyJson(JSONObject("""{"mode": "synced"}"""))
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
    fun theAmbilightSettingsTakeStyleNamesAndSwitches() {
        val m = SettingsModel()
        assertEquals(setOf("ambilight_style", "ambilight_menu"), m.applyJson(JSONObject("""{"ambilight_style": " follow_video ", "ambilight_menu": "standard"}""")))
        assertEquals("FOLLOW_VIDEO", m.values().ambilightStyle)
        assertEquals("STANDARD", m.values().ambilightMenu)
        // not a style name: ignored
        assertTrue(m.applyJson(JSONObject("""{"ambilight_style": "rain bow", "ambilight_menu": ""}""")).isEmpty())
        assertEquals("FOLLOW_VIDEO", m.ambilightStyle)
    }

    @Test
    fun theTvLatencyIsOneNumberOrAuto() {
        val m = SettingsModel()
        assertEquals(setOf("tv_latency_ms"), m.applyJson(JSONObject("""{"tv_latency_ms": 1350}""")))
        assertEquals(1350, m.values().tvLatencyMs)
        m.applyJson(JSONObject("""{"tv_latency_ms": 99999}"""))
        assertEquals(SettingsModel.MAX_TV_LATENCY_MS, m.tvLatencyMs)
        assertEquals(setOf("tv_latency_ms"), m.applyJson(JSONObject("""{"tv_latency_ms": "auto"}""")))
        assertEquals(-1, m.tvLatencyMs)
        m.applyJson(JSONObject("""{"tv_latency_ms": 200}"""))
        m.applyJson(JSONObject("""{"tv_latency_ms": -30}"""))
        assertEquals(-1, m.tvLatencyMs)
        assertTrue(m.applyJson(JSONObject("""{"tv_latency_ms": "slow"}""")).isEmpty())
    }

    @Test
    fun theTvStreamIsTsOrL16AndKaraokeIsASwitch() {
        val m = SettingsModel()
        assertEquals(setOf("tv_format"), m.applyJson(JSONObject("""{"tv_format": " L16 "}""")))
        assertEquals("l16", m.values().tvFormat)
        assertEquals(setOf("tv_format"), m.applyJson(JSONObject("""{"tv_format": "TS"}""")))
        assertEquals("ts", m.tvFormat)
        assertTrue(m.applyJson(JSONObject("""{"tv_format": "mkv"}""")).isEmpty())
        assertTrue(m.applyJson(JSONObject("""{"tv_format": 3}""")).isEmpty())
        assertEquals("ts", m.tvFormat)
    }

    @Test
    fun theDisplayIsOneOfThreeAndListeningIsTheDefault() {
        val m = SettingsModel()
        assertEquals("listen", m.values().display)
        assertEquals(setOf("display"), m.applyJson(JSONObject("""{"display": " Screen "}""")))
        assertEquals("screen", m.values().display)
        assertEquals(setOf("display"), m.applyJson(JSONObject("""{"display": "ambilight"}""")))
        assertTrue(m.applyJson(JSONObject("""{"display": "party"}""")).isEmpty())
        assertTrue(m.applyJson(JSONObject("""{"display": 3}""")).isEmpty())
        assertEquals("ambilight", m.display)
        // the settings that the display replaced are not keys any more
        assertTrue(m.applyJson(JSONObject("""{"karaoke": "on", "ambilight_synced": "off"}""")).isEmpty())
    }

    @Test
    fun theTimingOfTheVideoStreamHasLimitsAndTheRenderWayIsOneOfFour() {
        val m = SettingsModel()
        assertEquals(setOf("tv_burst_ms"), m.applyJson(JSONObject("""{"tv_burst_ms": 0}""")))
        assertEquals(0, m.values().tvBurstMs)
        m.applyJson(JSONObject("""{"tv_burst_ms": 99999}"""))
        assertEquals(SettingsModel.MAX_TV_BURST_MS, m.tvBurstMs)
        m.applyJson(JSONObject("""{"tv_burst_ms": -5}"""))
        assertEquals(0, m.tvBurstMs)
        assertEquals(setOf("tv_extra_ms"), m.applyJson(JSONObject("""{"tv_extra_ms": 510}""")))
        assertEquals(510, m.values().tvExtraMs)
        m.applyJson(JSONObject("""{"tv_extra_ms": -99999}"""))
        assertEquals(SettingsModel.MIN_TV_EXTRA_MS, m.tvExtraMs)
        m.applyJson(JSONObject("""{"tv_extra_ms": 99999}"""))
        assertEquals(SettingsModel.MAX_TV_EXTRA_MS, m.tvExtraMs)
        assertEquals(setOf("tv_pcr_lead_ms"), m.applyJson(JSONObject("""{"tv_pcr_lead_ms": 400}""")))
        assertEquals(400, m.values().tvPcrLeadMs)
        m.applyJson(JSONObject("""{"tv_pcr_lead_ms": 5}"""))
        assertEquals(SettingsModel.MIN_TV_PCR_LEAD_MS, m.tvPcrLeadMs)
        m.applyJson(JSONObject("""{"tv_pcr_lead_ms": 99999}"""))
        assertEquals(SettingsModel.MAX_TV_PCR_LEAD_MS, m.tvPcrLeadMs)
        assertEquals(setOf("tv_reanchor_min"), m.applyJson(JSONObject("""{"tv_reanchor_min": 30}""")))
        assertEquals(30, m.values().tvReanchorMin)
        assertEquals(setOf("tv_reanchor_min"), m.applyJson(JSONObject("""{"tv_reanchor_min": "off"}""")))
        assertEquals(0, m.tvReanchorMin)
        m.applyJson(JSONObject("""{"tv_reanchor_min": 999999}"""))
        assertEquals(SettingsModel.MAX_TV_REANCHOR_MIN, m.tvReanchorMin)
        assertEquals(setOf("tv_render"), m.applyJson(JSONObject("""{"tv_render": " GL "}""")))
        assertEquals("gl", m.values().tvRender)
        assertTrue(m.applyJson(JSONObject("""{"tv_render": "vulkan"}""")).isEmpty())
        assertEquals("gl", m.tvRender)
    }

    @Test
    fun theLyricsOffsetIsLimitedToThreeSeconds() {
        val m = SettingsModel()
        assertEquals(setOf("lyrics_offset_ms"), m.applyJson(JSONObject("""{"lyrics_offset_ms": -250}""")))
        assertEquals(-250, m.values().lyricsOffsetMs)
        m.applyJson(JSONObject("""{"lyrics_offset_ms": 99999}"""))
        assertEquals(SettingsModel.MAX_LYRICS_OFFSET_MS, m.lyricsOffsetMs)
        m.applyJson(JSONObject("""{"lyrics_offset_ms": -99999}"""))
        assertEquals(-SettingsModel.MAX_LYRICS_OFFSET_MS, m.lyricsOffsetMs)
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
    fun aLyricsModeStoredByAnOlderVersionWasTheOldDefaultAndBecomesSynced() {
        val stored = JSONObject("""{"mode": "lyrics", "karaoke": true, "ambilight_synced": false, "kitchen_offset_ms": 20}""")
        SettingsModel.migrate(stored, version = 2)
        val m = SettingsModel()
        m.applyJson(stored)
        assertEquals("synced", m.mode)
        assertEquals(20, m.kitchenOffsetMs)
        assertEquals("listen", m.display)
        // a mode of the current version is what the app or the user set
        val current = JSONObject("""{"mode": "lyrics"}""")
        SettingsModel.migrate(current, SettingsModel.VERSION)
        assertEquals("lyrics", current.getString("mode"))
        // and a synced mode stays
        val synced = JSONObject("""{"mode": "synced"}""")
        SettingsModel.migrate(synced, version = 2)
        assertEquals("synced", synced.getString("mode"))
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

    @Test
    fun theVideoSettingTakesWhatTheSpecUnderstandsAtAConstantBitrateInItsCanonicalFormAndIgnoresTheRest() {
        val m = SettingsModel()
        assertEquals(setOf("tv_video"), m.applyJson(JSONObject().put("tv_video", "720p30 CBR 3000 gop2")))
        assertEquals("720p30 cbr 3000 gop2", m.tvVideo)
        assertEquals("720p30 cbr 3000 gop2", m.toJson().getString("tv_video"))
        // the same again, and nonsense: nothing changes
        assertTrue(m.applyJson(JSONObject().put("tv_video", "cbr 3000 gop2 720p30")).isEmpty())
        assertTrue(m.applyJson(JSONObject().put("tv_video", "480p30")).isEmpty())
        assertTrue(m.applyJson(JSONObject().put("tv_video", "")).isEmpty())
        // a variable bitrate moved the TV's latency by +2.08 s: it is not taken
        assertTrue(m.applyJson(JSONObject().put("tv_video", "720p30 vbr 6000 gop2")).isEmpty())
        assertTrue(m.applyJson(JSONObject().put("tv_video", "720p30 cq 6000")).isEmpty())
        assertEquals("720p30 cbr 3000 gop2", m.tvVideo)
        assertEquals("720p30 cbr 3000 gop2", m.values().tvVideo)
    }
}
