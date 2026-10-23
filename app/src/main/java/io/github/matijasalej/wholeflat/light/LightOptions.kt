package io.github.matijasalej.wholeflat.light

import org.json.JSONObject

/**
 * The settings of the lights, kept apart from the app's own settings (in their own preferences, changed at `/lights/set`):
 * - `lights`: `mood` (the mood lights) or `tv` (the app's own Ambilight style, as before);
 * - `lights_strength`: how lively (`subtle`, `adaptive`, `punchy`): how fast the colours drift, and how often an energetic song changes
 *   mode (every 64, 32 or 16 beats);
 * - `lights_modes`: the TV modes an energetic song goes through, comma-separated (of [TvLooks.MODES]; all of them by default).
 *
 * Plain Kotlin; [apply] checks every value and says what it refused.
 */
data class LightOptions(
    val mode: String = MOOD,
    val strength: String = "adaptive",
    val modes: List<String> = TvLooks.MODES.map { it.name },
) {
    val mood: Boolean get() = mode == MOOD

    /** The modes allowed, as looks (in the order of [TvLooks.MODES]). */
    fun modeLooks(): List<TvLook> = TvLooks.MODES.filter { it.name in modes }

    fun toJson(): JSONObject = JSONObject()
        .put("lights", mode)
        .put("lights_strength", strength)
        .put("lights_modes", modes.joinToString(","))

    /** These options with the changes in [changes] (keys as in [toJson]); what could not be taken is in the second part. */
    fun apply(changes: Map<String, String>): Pair<LightOptions, List<String>> {
        var o = this
        val refused = ArrayList<String>()
        for ((k, raw) in changes) {
            val v = raw.trim()
            when (k) {
                "lights" -> if (v == MOOD || v == TV) o = o.copy(mode = v) else refused.add("$k=$v")
                "lights_strength" -> if (v in STRENGTHS) o = o.copy(strength = v) else refused.add("$k=$v")
                "lights_modes" -> {
                    val names = v.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    if (names.isNotEmpty() && names.all { n -> TvLooks.MODES.any { it.name == n } }) o = o.copy(modes = names) else refused.add("$k=$v")
                }
                else -> Unit // not a setting of the lights
            }
        }
        return Pair(o, refused)
    }

    companion object {
        const val MOOD = "mood"
        const val TV = "tv"
        val STRENGTHS = listOf("subtle", "adaptive", "punchy")

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
