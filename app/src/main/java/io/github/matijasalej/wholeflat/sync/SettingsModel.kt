package io.github.matijasalej.wholeflat.sync

import org.json.JSONObject

/**
 * The settings of the app, as the screen and the `/settings` route see them: kitchen on or off, the
 * offset that calibrates the two rooms, the kitchen balance, the volume mode of the living room and
 * the TV options. [toJson] is exactly what `GET /settings` returns, [applyJson] what `POST /settings`
 * accepts (a partial object); out-of-range values are clamped, unknown keys ignored.
 */
class SettingsValues(
    val kitchenEnabled: Boolean,
    val kitchenOffsetMs: Int,
    val kitchenBalance: Int,
    val livingFixedVolume: Boolean,
    val tvScreenOff: Boolean,
    val tvWake: Boolean,
    val tvIp: String,
    val captureEnabled: Boolean,
    val captureLivingLatencyMs: Int,
    val captureLeadMs: Int,
    /** The lag the kitchen is brought to in capture mode (ms after the capture); -1: it is left where it locked. */
    val kitchenTargetMs: Int,
    /** The lowest lag the kitchen is brought to in capture mode (ms after the capture); an underrun can only raise it. */
    val kitchenFloorMs: Int,
    /** The volume of the Sonos in capture mode (0 to 100, set from the phone page); -1: the app leaves it alone. */
    val kitchenVolume: Int,
    /** Whether the Sonos follows the music volume of this box (the phone's Cast volume); off where Cast changes nothing. */
    val captureVolumeMirror: Boolean,
    /** The format of the stream to the Sonos: "auto", "flac" or "wav" (see [StreamFormat]). */
    val kitchenFormat: String,
    /** The block size of the FLAC stream: 4096, 1152 or 576 frames. */
    val kitchenFlacBlock: Int,
    /** "synced" (the living room through the TV's DLNA player, both rooms held to one delay) or "lyrics" (HDMI direct, the kitchen at its lowest clean lag). */
    val mode: String,
    /**
     * How long the TV needs from our write of a frame to its sound (ms): the one number of the timing model of the TV's stream, which is
     * calibrated on the device. -1: the usual one of the stream format in use (see [SyncedTiming]).
     */
    val tvLatencyMs: Int,
    /** The stream the TV is given in Synced mode: "ts" (a live lyrics video with the audio) or "l16" (audio only, the last resort). */
    val tvFormat: String,
    /**
     * How old the key picture is at least that a connection of the TV starts at (ms): the stream was started so in the device test that the
     * TV took well (a second behind live, paced to live); 0: at the next key picture made after the connection.
     */
    val tvBurstMs: Int,
    /**
     * What the TV adds to the age of the key picture it started at (the backlog) between our write and its sound (ms): its latency is the
     * backlog plus this, 631 ms in the click check against the doorway recording (the 490 ms of the first tests had the start of the recording
     * folded into it). The common delay of the two rooms is the hold of the stream plus that latency.
     */
    val tvExtraMs: Int,
    /** The least backlog the TV's latency is counted from (ms): a connection with less than this to start from plays as if it had this much (see [SyncedTiming.AUTO_TS_PREROLL_MS]). */
    val tvPrerollMs: Int,
    /** How far the PCR of the video stream runs behind the timestamps of what it carries (ms): the lead a player wants; ffmpeg writes 700. */
    val tvPcrLeadMs: Int,
    /** A re-anchor of the TV's stream (a restart with a warm-up) is asked for when the stream is this old (minutes) and a song ends; 0: never. */
    val tvReanchorMin: Int,
    /** How the lyrics picture is put on the video encoder: "auto" (hardware canvas, else software canvas, else OpenGL), "hw", "sw" or "gl". */
    val tvRender: String,
    /** What the lyrics video is made as, as text (see [TvVideoSpec]): the height, the pictures a second, constant or variable bitrate, kbit/s and the time between key pictures. */
    val tvVideo: String,
    /** Moves the lyrics earlier (positive) or later (negative) against the sound, for what the media session's clock does not tell (ms). */
    val lyricsOffsetMs: Int,
    /** What the TV shows in Synced mode: "listen" (screen off, lights off), "ambilight" (screen off, lights on) or "screen" (the lyrics video, lights on); see [Display]. */
    val display: String,
    /** The Ambilight style in Synced mode (JointSpace `styleName`) and its `menuSetting`. */
    val ambilightStyle: String,
    val ambilightMenu: String,
    /** The mood light (the Ambilight as a lamp, see [MoodLight]): "off", the key of a mood, or "custom" with [lightColor] ("#rrggbb"). */
    val light: String,
    val lightColor: String,
    /** How bright the mood light is, in percent (10 to 100). */
    val lightLevel: Int,
    /** The mood light was chosen after the display, so it has the TV also while the music plays (the last tap wins). */
    val lightFirst: Boolean,
)

class SettingsModel {
    var kitchenEnabled = true
    var kitchenOffsetMs = 0
    var kitchenBalance = 0
    var livingFixedVolume = true
    var tvScreenOff = true
    var tvWake = true
    var tvIp = ""
    var captureEnabled = true
    var captureLivingLatencyMs = 0
    var captureLeadMs = DEFAULT_CAPTURE_LEAD_MS
    var kitchenTargetMs = DEFAULT_KITCHEN_TARGET_MS
    var kitchenFloorMs = DEFAULT_KITCHEN_FLOOR_MS
    var kitchenVolume = NO_VOLUME
    var captureVolumeMirror = false
    var kitchenFormat = StreamFormat.AUTO
    var kitchenFlacBlock = Flac.BLOCK
    var mode = DEFAULT_MODE
    var tvLatencyMs = AUTO_TV_LATENCY
    var tvFormat = TV_FORMAT_TS
    var tvBurstMs = DEFAULT_TV_BURST_MS
    var tvExtraMs = DEFAULT_TV_EXTRA_MS
    var tvPrerollMs = DEFAULT_TV_PREROLL_MS
    var tvPcrLeadMs = DEFAULT_TV_PCR_LEAD_MS
    var tvReanchorMin = 0
    var tvRender = TV_RENDER_AUTO
    var tvVideo = TvVideoSpec.DEFAULT.text()
    var lyricsOffsetMs = 0
    var display = Display.DEFAULT.key
    var ambilightStyle = DEFAULT_AMBILIGHT_STYLE
    var ambilightMenu = DEFAULT_AMBILIGHT_MENU
    var light = MoodLight.OFF
    var lightColor = MoodLight.DEFAULT_COLOR
    var lightLevel = MoodLight.DEFAULT_LEVEL
    var lightFirst = false

    /** An immutable copy, for the threads that only read. */
    fun values(): SettingsValues = SettingsValues(
        kitchenEnabled, kitchenOffsetMs, kitchenBalance, livingFixedVolume, tvScreenOff, tvWake, tvIp,
        captureEnabled, captureLivingLatencyMs, captureLeadMs, kitchenTargetMs, kitchenFloorMs, kitchenVolume,
        captureVolumeMirror, kitchenFormat, kitchenFlacBlock, mode, tvLatencyMs, tvFormat, tvBurstMs, tvExtraMs, tvPrerollMs, tvPcrLeadMs, tvReanchorMin, tvRender, tvVideo, lyricsOffsetMs, display,
        ambilightStyle, ambilightMenu, light, lightColor, lightLevel, lightFirst,
    )

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put(KITCHEN_ENABLED, kitchenEnabled)
        o.put(KITCHEN_OFFSET_MS, kitchenOffsetMs)
        o.put(KITCHEN_BALANCE, kitchenBalance)
        o.put(LIVING_FIXED_VOLUME, livingFixedVolume)
        o.put(TV_SCREEN_OFF, tvScreenOff)
        o.put(TV_WAKE, tvWake)
        o.put(TV_IP, tvIp)
        o.put(CAPTURE_ENABLED, captureEnabled)
        o.put(CAPTURE_LIVING_LATENCY_MS, captureLivingLatencyMs)
        o.put(CAPTURE_LEAD_MS, captureLeadMs)
        o.put(KITCHEN_TARGET_MS, kitchenTargetMs)
        o.put(KITCHEN_FLOOR_MS, kitchenFloorMs)
        o.put(KITCHEN_VOLUME, kitchenVolume)
        o.put(CAPTURE_VOLUME_MIRROR, captureVolumeMirror)
        o.put(KITCHEN_FORMAT, kitchenFormat)
        o.put(KITCHEN_FLAC_BLOCK, kitchenFlacBlock)
        o.put(MODE, mode)
        o.put(TV_LATENCY_MS, tvLatencyMs)
        o.put(TV_FORMAT, tvFormat)
        o.put(TV_BURST_MS, tvBurstMs)
        o.put(TV_EXTRA_MS, tvExtraMs)
        o.put(TV_PREROLL_MS, tvPrerollMs)
        o.put(TV_PCR_LEAD_MS, tvPcrLeadMs)
        o.put(TV_REANCHOR_MIN, tvReanchorMin)
        o.put(TV_RENDER, tvRender)
        o.put(TV_VIDEO, tvVideo)
        o.put(LYRICS_OFFSET_MS, lyricsOffsetMs)
        o.put(DISPLAY, display)
        o.put(AMBILIGHT_STYLE, ambilightStyle)
        o.put(AMBILIGHT_MENU, ambilightMenu)
        o.put(LIGHT, light)
        o.put(LIGHT_COLOR, lightColor)
        o.put(LIGHT_LEVEL, lightLevel)
        o.put(LIGHT_FIRST, lightFirst)
        return o
    }

    /** Applies the keys present in [json]; returns the names of the settings that changed. */
    fun applyJson(json: JSONObject): Set<String> {
        val changed = LinkedHashSet<String>()
        if (json.has(KITCHEN_ENABLED)) {
            val v = bool(json.get(KITCHEN_ENABLED))
            if (v != null && v != kitchenEnabled) { kitchenEnabled = v; changed.add(KITCHEN_ENABLED) }
        }
        if (json.has(KITCHEN_OFFSET_MS)) {
            val v = number(json.get(KITCHEN_OFFSET_MS))?.coerceIn(-MAX_OFFSET_MS, MAX_OFFSET_MS)
            if (v != null && v != kitchenOffsetMs) { kitchenOffsetMs = v; changed.add(KITCHEN_OFFSET_MS) }
        }
        if (json.has(KITCHEN_BALANCE)) {
            val v = number(json.get(KITCHEN_BALANCE))?.coerceIn(-MAX_BALANCE, MAX_BALANCE)
            if (v != null && v != kitchenBalance) { kitchenBalance = v; changed.add(KITCHEN_BALANCE) }
        }
        if (json.has(LIVING_FIXED_VOLUME)) {
            val v = bool(json.get(LIVING_FIXED_VOLUME))
            if (v != null && v != livingFixedVolume) { livingFixedVolume = v; changed.add(LIVING_FIXED_VOLUME) }
        }
        if (json.has(TV_SCREEN_OFF)) {
            val v = bool(json.get(TV_SCREEN_OFF))
            if (v != null && v != tvScreenOff) { tvScreenOff = v; changed.add(TV_SCREEN_OFF) }
        }
        if (json.has(TV_WAKE)) {
            val v = bool(json.get(TV_WAKE))
            if (v != null && v != tvWake) { tvWake = v; changed.add(TV_WAKE) }
        }
        if (json.has(TV_IP)) {
            val raw = json.get(TV_IP)
            val v = if (raw == JSONObject.NULL) "" else raw.toString().trim()
            if (HOST.matches(v) && v != tvIp) { tvIp = v; changed.add(TV_IP) }
        }
        if (json.has(CAPTURE_ENABLED)) {
            val v = bool(json.get(CAPTURE_ENABLED))
            if (v != null && v != captureEnabled) { captureEnabled = v; changed.add(CAPTURE_ENABLED) }
        }
        if (json.has(CAPTURE_LIVING_LATENCY_MS)) {
            val v = number(json.get(CAPTURE_LIVING_LATENCY_MS))?.coerceIn(-MAX_CAPTURE_LATENCY_MS, MAX_CAPTURE_LATENCY_MS)
            if (v != null && v != captureLivingLatencyMs) { captureLivingLatencyMs = v; changed.add(CAPTURE_LIVING_LATENCY_MS) }
        }
        if (json.has(CAPTURE_LEAD_MS)) {
            val v = number(json.get(CAPTURE_LEAD_MS))?.coerceIn(MIN_CAPTURE_LEAD_MS, MAX_CAPTURE_LEAD_MS)
            if (v != null && v != captureLeadMs) { captureLeadMs = v; changed.add(CAPTURE_LEAD_MS) }
        }
        if (json.has(KITCHEN_TARGET_MS)) {
            // a number from 0; anything below that, or "off", or null, is no target
            val raw = json.get(KITCHEN_TARGET_MS)
            val v = if (raw == JSONObject.NULL || off(raw)) NO_TARGET else number(raw)?.let { if (it < 0) NO_TARGET else it.coerceAtMost(MAX_KITCHEN_TARGET_MS) }
            if (v != null && v != kitchenTargetMs) { kitchenTargetMs = v; changed.add(KITCHEN_TARGET_MS) }
        }
        if (json.has(KITCHEN_FLOOR_MS)) {
            val v = number(json.get(KITCHEN_FLOOR_MS))?.coerceIn(MIN_KITCHEN_FLOOR_MS, MAX_KITCHEN_TARGET_MS)
            if (v != null && v != kitchenFloorMs) { kitchenFloorMs = v; changed.add(KITCHEN_FLOOR_MS) }
        }
        if (json.has(KITCHEN_VOLUME)) {
            // 0 to 100; below 0, "off", "none", an empty text or null: the app leaves the Sonos volume alone
            val raw = json.get(KITCHEN_VOLUME)
            val v = if (raw == JSONObject.NULL || off(raw)) NO_VOLUME else number(raw)?.let { if (it < 0) NO_VOLUME else it.coerceAtMost(MAX_KITCHEN_VOLUME) }
            if (v != null && v != kitchenVolume) { kitchenVolume = v; changed.add(KITCHEN_VOLUME) }
        }
        if (json.has(CAPTURE_VOLUME_MIRROR)) {
            val v = bool(json.get(CAPTURE_VOLUME_MIRROR))
            if (v != null && v != captureVolumeMirror) { captureVolumeMirror = v; changed.add(CAPTURE_VOLUME_MIRROR) }
        }
        if (json.has(KITCHEN_FORMAT)) {
            // anything but "auto", "flac" or "wav" is ignored
            val v = StreamFormat.settingOf(json.get(KITCHEN_FORMAT).toString())
            if (v != null && v != kitchenFormat) { kitchenFormat = v; changed.add(KITCHEN_FORMAT) }
        }
        if (json.has(KITCHEN_FLAC_BLOCK)) {
            // only the block sizes that were measured are taken: 4096, 1152 or 576
            val v = number(json.get(KITCHEN_FLAC_BLOCK))
            if (v != null && v in StreamFormat.FLAC_BLOCKS && v != kitchenFlacBlock) { kitchenFlacBlock = v; changed.add(KITCHEN_FLAC_BLOCK) }
        }
        if (json.has(MODE)) {
            // anything but "synced" or "lyrics" is ignored
            val v = Mode.of(json.get(MODE).toString())?.key
            if (v != null && v != mode) { mode = v; changed.add(MODE) }
        }
        if (json.has(TV_LATENCY_MS)) {
            // -1 (or "auto") is the usual latency of the stream format in use
            val raw = json.get(TV_LATENCY_MS)
            val v = if (raw is String && raw.trim().equals("auto", ignoreCase = true)) AUTO_TV_LATENCY else number(raw)?.let { if (it < 0) AUTO_TV_LATENCY else it.coerceAtMost(MAX_TV_LATENCY_MS) }
            if (v != null && v != tvLatencyMs) { tvLatencyMs = v; changed.add(TV_LATENCY_MS) }
        }
        if (json.has(TV_FORMAT)) {
            val t = json.get(TV_FORMAT).toString().trim().lowercase()
            if ((t == TV_FORMAT_TS || t == TV_FORMAT_L16) && t != tvFormat) { tvFormat = t; changed.add(TV_FORMAT) }
        }
        if (json.has(TV_BURST_MS)) {
            val v = number(json.get(TV_BURST_MS))?.coerceIn(0, MAX_TV_BURST_MS)
            if (v != null && v != tvBurstMs) { tvBurstMs = v; changed.add(TV_BURST_MS) }
        }
        if (json.has(TV_EXTRA_MS)) {
            val v = number(json.get(TV_EXTRA_MS))?.coerceIn(MIN_TV_EXTRA_MS, MAX_TV_EXTRA_MS)
            if (v != null && v != tvExtraMs) { tvExtraMs = v; changed.add(TV_EXTRA_MS) }
        }
        if (json.has(TV_PREROLL_MS)) {
            val v = number(json.get(TV_PREROLL_MS))?.coerceIn(0, MAX_TV_PREROLL_MS)
            if (v != null && v != tvPrerollMs) { tvPrerollMs = v; changed.add(TV_PREROLL_MS) }
        }
        if (json.has(TV_PCR_LEAD_MS)) {
            val v = number(json.get(TV_PCR_LEAD_MS))?.coerceIn(MIN_TV_PCR_LEAD_MS, MAX_TV_PCR_LEAD_MS)
            if (v != null && v != tvPcrLeadMs) { tvPcrLeadMs = v; changed.add(TV_PCR_LEAD_MS) }
        }
        if (json.has(TV_REANCHOR_MIN)) {
            val raw = json.get(TV_REANCHOR_MIN)
            val v = if (raw == JSONObject.NULL || off(raw)) 0 else number(raw)?.coerceIn(0, MAX_TV_REANCHOR_MIN)
            if (v != null && v != tvReanchorMin) { tvReanchorMin = v; changed.add(TV_REANCHOR_MIN) }
        }
        if (json.has(TV_RENDER)) {
            val t = json.get(TV_RENDER).toString().trim().lowercase()
            if (t in TV_RENDERS && t != tvRender) { tvRender = t; changed.add(TV_RENDER) }
        }
        if (json.has(TV_VIDEO)) {
            // anything that is not understood (see TvVideoSpec) is ignored; what is understood is stored in its canonical form
            val v = TvVideoSpec.parse(json.get(TV_VIDEO).toString())?.text()
            if (v != null && v != tvVideo) { tvVideo = v; changed.add(TV_VIDEO) }
        }
        if (json.has(LYRICS_OFFSET_MS)) {
            val v = number(json.get(LYRICS_OFFSET_MS))?.coerceIn(-MAX_LYRICS_OFFSET_MS, MAX_LYRICS_OFFSET_MS)
            if (v != null && v != lyricsOffsetMs) { lyricsOffsetMs = v; changed.add(LYRICS_OFFSET_MS) }
        }
        if (json.has(DISPLAY)) {
            // anything but "listen", "ambilight" or "screen" is ignored
            val v = Display.of(json.get(DISPLAY).toString())?.key
            if (v != null && v != display) { display = v; changed.add(DISPLAY) }
        }
        if (json.has(AMBILIGHT_STYLE)) {
            // a JointSpace style name: capital letters, digits and underscores
            val v = json.get(AMBILIGHT_STYLE).toString().trim().uppercase()
            if (STYLE_NAME.matches(v) && v != ambilightStyle) { ambilightStyle = v; changed.add(AMBILIGHT_STYLE) }
        }
        if (json.has(AMBILIGHT_MENU)) {
            val v = json.get(AMBILIGHT_MENU).toString().trim().uppercase()
            if (STYLE_NAME.matches(v) && v != ambilightMenu) { ambilightMenu = v; changed.add(AMBILIGHT_MENU) }
        }
        // the mood light: "off", "custom" or a mood; anything else is ignored
        val newLight = if (json.has(LIGHT)) MoodLight.settingOf(json.get(LIGHT).toString()) else null
        if (newLight != null && newLight != light) { light = newLight; changed.add(LIGHT) }
        val newColor = if (json.has(LIGHT_COLOR)) MoodLight.parseColor(json.get(LIGHT_COLOR).toString()) else null
        if (newColor != null && newColor != lightColor) { lightColor = newColor; changed.add(LIGHT_COLOR) }
        if (json.has(LIGHT_LEVEL)) {
            val v = number(json.get(LIGHT_LEVEL))?.coerceIn(MoodLight.MIN_LEVEL, MoodLight.MAX_LEVEL)
            if (v != null && v != lightLevel) { lightLevel = v; changed.add(LIGHT_LEVEL) }
        }
        // the last tap wins: a mood (or a colour) takes the TV from the music, a display gives it back; said outright, it is what is said
        val first = when {
            json.has(LIGHT_FIRST) -> bool(json.get(LIGHT_FIRST))
            (newLight != null && newLight != MoodLight.OFF) || newColor != null -> true
            json.has(DISPLAY) && Display.of(json.get(DISPLAY).toString()) != null -> false
            else -> null
        }
        if (first != null && first != lightFirst) { lightFirst = first; changed.add(LIGHT_FIRST) }
        return changed
    }

    private fun bool(v: Any?): Boolean? = when (v) {
        is Boolean -> v
        is Number -> v.toInt() != 0
        is String -> when (v.trim().lowercase()) {
            "true", "1", "on", "yes" -> true
            "false", "0", "off", "no" -> false
            else -> null
        }
        else -> null
    }

    private fun off(v: Any?): Boolean = v is String && v.trim().lowercase().let { it.isEmpty() || it == "off" || it == "none" || it == "no" }

    private fun number(v: Any?): Int? = when (v) {
        is Number -> Math.round(v.toDouble()).toInt()
        is String -> v.trim().toDoubleOrNull()?.let { Math.round(it).toInt() }
        else -> null
    }

    companion object {
        const val KITCHEN_ENABLED = "kitchen_enabled"
        const val KITCHEN_OFFSET_MS = "kitchen_offset_ms"
        const val KITCHEN_BALANCE = "kitchen_balance"
        const val LIVING_FIXED_VOLUME = "living_fixed_volume"
        const val TV_SCREEN_OFF = "tv_screen_off"
        const val TV_WAKE = "tv_wake"
        const val TV_IP = "tv_ip"
        const val CAPTURE_ENABLED = "capture_enabled"
        const val CAPTURE_LIVING_LATENCY_MS = "capture_living_latency_ms"
        const val CAPTURE_LEAD_MS = "capture_lead_ms"
        const val KITCHEN_TARGET_MS = "kitchen_target_ms"
        const val KITCHEN_FLOOR_MS = "kitchen_floor_ms"
        const val KITCHEN_VOLUME = "kitchen_volume"
        const val CAPTURE_VOLUME_MIRROR = "capture_volume_mirror"
        const val KITCHEN_FORMAT = "kitchen_format"
        const val KITCHEN_FLAC_BLOCK = "kitchen_flac_block"
        const val MODE = "mode"
        const val TV_LATENCY_MS = "tv_latency_ms"
        const val TV_FORMAT = "tv_format"
        const val TV_BURST_MS = "tv_burst_ms"
        const val TV_EXTRA_MS = "tv_extra_ms"
        const val TV_PREROLL_MS = "tv_preroll_ms"
        const val TV_PCR_LEAD_MS = "tv_pcr_lead_ms"
        const val TV_REANCHOR_MIN = "tv_reanchor_min"
        const val TV_RENDER = "tv_render"
        const val TV_VIDEO = "tv_video"
        const val LYRICS_OFFSET_MS = "lyrics_offset_ms"
        const val DISPLAY = "display"
        const val AMBILIGHT_STYLE = "ambilight_style"
        const val AMBILIGHT_MENU = "ambilight_menu"
        const val LIGHT = "light"
        const val LIGHT_COLOR = "light_color"
        const val LIGHT_LEVEL = "light_level"
        const val LIGHT_FIRST = "light_first"

        /** The offset is limited to what a Mac calibration could plausibly ask for. */
        const val MAX_OFFSET_MS = 2_000
        const val MAX_BALANCE = 20

        /** How much the living room can hear a captured player later than it is captured (ms). */
        const val MAX_CAPTURE_LATENCY_MS = 1_000

        /** Audio sent to the Sonos ahead when a captured stream starts (ms): less is a smaller lag, more is steadier. Synced mode uses the seconds. */
        const val DEFAULT_CAPTURE_LEAD_MS = 80
        const val MIN_CAPTURE_LEAD_MS = 10
        const val MAX_CAPTURE_LEAD_MS = 5_000

        /** `kitchen_target_ms` when the kitchen is not brought to a lag (it stays where it locked). */
        const val NO_TARGET = -1
        const val MAX_KITCHEN_TARGET_MS = 3_000

        /**
         * Measured on the device (23 Oct, WAV): the Sonos is clean down to a lag of 316 ms after the capture and stutters at 306,
         * and a fresh start followed by one descent to 322 is clean. The floor is the lowest lag that is ever aimed for.
         */
        const val DEFAULT_KITCHEN_TARGET_MS = 322
        const val DEFAULT_KITCHEN_FLOOR_MS = 316
        const val MIN_KITCHEN_FLOOR_MS = 100

        /** The mode the app starts in: Synced (the TV plays the lyrics video, the kitchen follows); Lyrics is entered when the TV's own remote leaves the player. */
        val DEFAULT_MODE = Mode.SYNCED.key

        /** `tv_latency_ms` when the usual latency of the stream format is to be used. */
        const val AUTO_TV_LATENCY = -1
        const val MAX_TV_LATENCY_MS = 4_000

        /** The streams the TV can be given. */
        const val TV_FORMAT_TS = "ts"
        const val TV_FORMAT_L16 = "l16"

        /** A connection of the TV starts at a key picture at least this old (ms): 1.5 s in the device tests (the backlog came out 1.5 to 2.5 s). */
        const val DEFAULT_TV_BURST_MS = 1_500
        const val MAX_TV_BURST_MS = 5_000

        /** The TV's latency is the backlog of its connection plus this (ms): 631 against the doorway recording (the first tests said 490, with the start of the recording folded in). */
        const val DEFAULT_TV_EXTRA_MS = 631

        /** A connection with a backlog below this plays as if it had this much (ms); see [SyncedTiming.AUTO_TS_PREROLL_MS]. */
        const val DEFAULT_TV_PREROLL_MS = 1_050
        const val MAX_TV_PREROLL_MS = 3_000
        const val MIN_TV_EXTRA_MS = -1_000
        const val MAX_TV_EXTRA_MS = 3_000

        /** ffmpeg's mux delay, which the TV played. */
        const val DEFAULT_TV_PCR_LEAD_MS = 700
        const val MIN_TV_PCR_LEAD_MS = 100
        const val MAX_TV_PCR_LEAD_MS = 3_000
        const val MAX_TV_REANCHOR_MIN = 24 * 60
        const val MAX_LYRICS_OFFSET_MS = 3_000

        /** The ways the lyrics picture can be put on the encoder (see `LyricsRenderer`). */
        const val TV_RENDER_AUTO = "auto"
        val TV_RENDERS = setOf(TV_RENDER_AUTO, "hw", "sw", "gl")

        /** The Ambilight in Synced mode follows the audio (verified on the TV: the style "Lumina" in its menu). */
        const val DEFAULT_AMBILIGHT_STYLE = "FOLLOW_AUDIO"
        const val DEFAULT_AMBILIGHT_MENU = "ENERGY_ADAPTIVE_BRIGHTNESS"
        private val STYLE_NAME = Regex("^[A-Z][A-Z0-9_]{1,40}$")

        /** `kitchen_volume` when the app leaves the volume of the Sonos alone. */
        const val NO_VOLUME = -1
        const val MAX_KITCHEN_VOLUME = 100

        /**
         * The version of the stored settings. 2: the default of `kitchen_format` became "auto", but every change of
         * any setting had stored all of them, so a stored "flac" is the old default and not a choice (see [migrate]).
         * 3: the default of `mode` became "synced" (it was "lyrics" until the lyrics video existed, and was stored with every
         * other change of a setting), and `display` replaced `karaoke` and `ambilight_synced`.
         */
        const val VERSION = 3

        /** Brings settings [stored] by an older [version] up to date (in place). */
        fun migrate(stored: JSONObject, version: Int) {
            if (version < 2 && stored.optString(KITCHEN_FORMAT) == "flac") stored.remove(KITCHEN_FORMAT)
            if (version < 3) {
                if (stored.optString(MODE) == Mode.LYRICS.key) stored.remove(MODE)
                stored.remove("karaoke")
                stored.remove("ambilight_synced")
            }
        }

        /** Empty, or a host name or address. */
        private val HOST = Regex("^([0-9A-Za-z.\\-]{1,100})?$")
    }
}
