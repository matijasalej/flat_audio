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

    /** An immutable copy, for the threads that only read. */
    fun values(): SettingsValues = SettingsValues(
        kitchenEnabled, kitchenOffsetMs, kitchenBalance, livingFixedVolume, tvScreenOff, tvWake, tvIp,
        captureEnabled, captureLivingLatencyMs, captureLeadMs, kitchenTargetMs, kitchenFloorMs, kitchenVolume,
        captureVolumeMirror, kitchenFormat, kitchenFlacBlock,
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

        /** The offset is limited to what a Mac calibration could plausibly ask for. */
        const val MAX_OFFSET_MS = 2_000
        const val MAX_BALANCE = 20

        /** How much the living room can hear a captured player later than it is captured (ms). */
        const val MAX_CAPTURE_LATENCY_MS = 1_000

        /** Audio sent to the Sonos ahead when a captured stream starts (ms): less is a smaller lag, more is steadier. */
        const val DEFAULT_CAPTURE_LEAD_MS = 80
        const val MIN_CAPTURE_LEAD_MS = 10
        const val MAX_CAPTURE_LEAD_MS = 3_000

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

        /** `kitchen_volume` when the app leaves the volume of the Sonos alone. */
        const val NO_VOLUME = -1
        const val MAX_KITCHEN_VOLUME = 100

        /**
         * The version of the stored settings. 2: the default of `kitchen_format` became "auto", but every change of
         * any setting had stored all of them, so a stored "flac" is the old default and not a choice (see [migrate]).
         */
        const val VERSION = 2

        /** Brings settings [stored] by an older [version] up to date (in place). */
        fun migrate(stored: JSONObject, version: Int) {
            if (version < 2 && stored.optString(KITCHEN_FORMAT) == "flac") stored.remove(KITCHEN_FORMAT)
        }

        /** Empty, or a host name or address. */
        private val HOST = Regex("^([0-9A-Za-z.\\-]{1,100})?$")
    }
}
