package io.github.matijasalej.wholeflat.light

import org.json.JSONObject

/**
 * The settings of the lights, kept apart from the app's own settings (in their own preferences, changed at `/lights/set`):
 * - `lights`: `tv` (the TV's own style, as before) or `mood` (the mood lights);
 * - `lights_strength`: how deep the pulses go (`subtle`, `adaptive`, `punchy`);
 * - `lights_offset_ms`: how far ahead of the sound the painted lights are drawn (-200 to 300; positive is earlier);
 * - `lights_band_pct`: the width of the glow band round the lyrics, in % of the picture's width (1 to 12);
 * - `lights_synced_style`: the TV's style while the picture carries the lights, "STYLE/MENU";
 * - `lights_native_mono`, `_duo`, `_multi`: what the TV shows per class in Lyrics mode (see [NativeStyles.forLook]).
 *
 * Plain Kotlin; [apply] checks every value and says what it refused.
 */
data class LightOptions(
    val mode: String = MOOD,
    val strength: String = LightPlanner.Strength.ADAPTIVE.key,
    val offsetMs: Int = 20,
    val bandPct: Int = 5,
    // Lumina (today's style, it takes its colours from the picture) until the TV test shows that FOLLOW_VIDEO follows with the screen off
    val syncedStyle: String = "FOLLOW_AUDIO/ENERGY_ADAPTIVE_BRIGHTNESS",
    val nativeMono: String = "COLOR",
    val nativeDuo: String = "COLORS:ENERGY_ADAPTIVE_BRIGHTNESS",
    val nativeMulti: String = "PARTY",
) {
    val mood: Boolean get() = mode == MOOD

    /** True if the TV's style in Synced mode makes its own pulses from the audio (Lumina): the painter then gives only the colours. */
    val tvPulses: Boolean get() = syncedStyle.startsWith("FOLLOW_AUDIO")

    fun toJson(): JSONObject = JSONObject()
        .put("lights", mode)
        .put("lights_strength", strength)
        .put("lights_offset_ms", offsetMs)
        .put("lights_band_pct", bandPct)
        .put("lights_synced_style", syncedStyle)
        .put("lights_native_mono", nativeMono)
        .put("lights_native_duo", nativeDuo)
        .put("lights_native_multi", nativeMulti)

    /** These options with the changes in [changes] (keys as in [toJson]); what could not be taken is in the second part. */
    fun apply(changes: Map<String, String>): Pair<LightOptions, List<String>> {
        var o = this
        val refused = ArrayList<String>()
        for ((k, raw) in changes) {
            val v = raw.trim()
            when (k) {
                "lights" -> if (v == MOOD || v == TV) o = o.copy(mode = v) else refused.add("$k=$v")
                "lights_strength" -> if (LightPlanner.Strength.entries.any { it != LightPlanner.Strength.NONE && it.key == v }) o = o.copy(strength = v) else refused.add("$k=$v")
                "lights_offset_ms" -> v.toIntOrNull()?.takeIf { it in -200..300 }?.let { o = o.copy(offsetMs = it) } ?: refused.add("$k=$v")
                "lights_band_pct" -> v.toIntOrNull()?.takeIf { it in 1..12 }?.let { o = o.copy(bandPct = it) } ?: refused.add("$k=$v")
                "lights_synced_style" -> if (STYLE.matches(v)) o = o.copy(syncedStyle = v) else refused.add("$k=$v")
                "lights_native_mono" -> if (NATIVE.matches(v)) o = o.copy(nativeMono = v) else refused.add("$k=$v")
                "lights_native_duo" -> if (NATIVE.matches(v)) o = o.copy(nativeDuo = v) else refused.add("$k=$v")
                "lights_native_multi" -> if (NATIVE.matches(v)) o = o.copy(nativeMulti = v) else refused.add("$k=$v")
                else -> Unit // not a setting of the lights
            }
        }
        return Pair(o, refused)
    }

    companion object {
        const val MOOD = "mood"
        const val TV = "tv"
        private val STYLE = Regex("""(FOLLOW_VIDEO|FOLLOW_AUDIO|FOLLOW_COLOR)(/[A-Z_]+)?""")
        private val NATIVE = Regex("""COLOR|COLORS:[A-Z_]+|[A-Z_]+""")

        fun of(json: String?): LightOptions {
            if (json.isNullOrBlank()) return LightOptions()
            return try {
                val j = JSONObject(json)
                val m = HashMap<String, String>()
                for (k in j.keys()) m[k] = j.get(k).toString()
                LightOptions().apply(m).first
            } catch (e: Exception) {
                LightOptions()
            }
        }
    }
}
