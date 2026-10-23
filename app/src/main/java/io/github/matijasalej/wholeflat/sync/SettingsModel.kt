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
    /** The format of the stream to the Sonos: "flac" or "wav" (see [StreamFormat]). */
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
    var kitchenFormat = StreamFormat.FLAC.key
    var kitchenFlacBlock = Flac.BLOCK

    /** An immutable copy, for the threads that only read. */
    fun values(): SettingsValues = SettingsValues(
        kitchenEnabled, kitchenOffsetMs, kitchenBalance, livingFixedVolume, tvScreenOff, tvWake, tvIp,
        captureEnabled, captureLivingLatencyMs, captureLeadMs, kitchenFormat, kitchenFlacBlock,
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
        if (json.has(KITCHEN_FORMAT)) {
            // anything but "flac" or "wav" is ignored
            val v = StreamFormat.of(json.get(KITCHEN_FORMAT).toString())?.key
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
        const val KITCHEN_FORMAT = "kitchen_format"
        const val KITCHEN_FLAC_BLOCK = "kitchen_flac_block"

        /** The offset is limited to what a Mac calibration could plausibly ask for. */
        const val MAX_OFFSET_MS = 2_000
        const val MAX_BALANCE = 20

        /** How much the living room can hear a captured player later than it is captured (ms). */
        const val MAX_CAPTURE_LATENCY_MS = 1_000

        /** Audio sent to the Sonos ahead when a captured stream starts (ms): less is a smaller lag, more is steadier. */
        const val DEFAULT_CAPTURE_LEAD_MS = 150
        const val MIN_CAPTURE_LEAD_MS = 40
        const val MAX_CAPTURE_LEAD_MS = 3_000

        /** Empty, or a host name or address. */
        private val HOST = Regex("^([0-9A-Za-z.\\-]{1,100})?$")
    }
}
