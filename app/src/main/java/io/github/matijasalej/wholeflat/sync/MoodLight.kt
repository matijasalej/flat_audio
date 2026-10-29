package io.github.matijasalej.wholeflat.sync

import org.json.JSONObject

/**
 * The Chill light (display "chill"): the Ambilight of the TV as a lamp of one colour, chosen on the phone page (`light_color`, a mood or a
 * colour of his own; `light_level` dims it), still or, with `light_pulse`, slowly pulsing. The moods are colours as the page shows them ("#rrggbb"); the TV is given
 * a colour as hue, saturation and brightness, each 0 to 255, in its style `FOLLOW_COLOR` with the algorithm `MANUAL_HUE`.
 *
 * Seen on the TV (23 Oct): hue 85 is green and 170 blue (the hue is the angle scaled to 255); the TV drives each LED with the square of
 * its channel, so brightness 80 gives about a tenth of the light and saturation 40 a pale tint, which is how a lamp dims to the eye (a
 * percent of `light_level` is a percent of the brightness). The TV takes the configuration at once (15 ms), but when asked what it shows it
 * names its last style from the menu, not the colour: what the lights show is known only from what was sent. From the branch mood-light.
 *
 * Plain Kotlin.
 */
object MoodLight {
    class Mood(val key: String, val label: String, val color: String)

    /** The moods of the phone page, in its order. The colours are a first guess, to be tuned by eye on the wall. */
    val MOODS: List<Mood> = listOf(
        Mood("cozy", "Cozy", "#ff9a40"),
        Mood("candle", "Candle", "#ff6a10"),
        Mood("sunset", "Sunset", "#ff5a4a"),
        Mood("rose", "Rose", "#ff4f8b"),
        Mood("lavender", "Lavender", "#a77bff"),
        Mood("ocean", "Ocean", "#2f80ff"),
        Mood("forest", "Forest", "#3ee07a"),
        Mood("white", "White", "#fff2e0"),
    )

    /** The colour until one is chosen: Cozy. */
    const val DEFAULT_COLOR = "#ff9a40"

    /** `light_level` in percent of the brightness: below a tenth the LEDs hardly glow. */
    const val DEFAULT_LEVEL = 80
    const val MIN_LEVEL = 10
    const val MAX_LEVEL = 100

    /**
     * The slow pulse: how far the brightness swings (of 255, at most down to black) and how fast (the TV's speed, of 255; the mood lights'
     * calm drift runs at 20 to 80). Slow and deep enough to be seen: a lamp breathing, not a show.
     */
    const val PULSE_BRIGHTNESS = 110
    const val PULSE_SPEED = 15

    /** "#rrggbb" (any case, the "#" may be left out) as "#rrggbb" in small letters; null if it is not a colour. */
    fun parseColor(text: String?): String? {
        val t = text?.trim()?.removePrefix("#")?.lowercase() ?: return null
        return if (HEX.matches(t)) "#$t" else null
    }

    /** The mood whose colour [color] is; null for a colour of his own. */
    fun moodOf(color: String): Mood? = MOODS.firstOrNull { it.color == parseColor(color) }

    /** The TV's hue, saturation and brightness (0 to 255) of [color] at [level] percent; black for what is not a colour. */
    fun hsb(color: String, level: Int): IntArray {
        val c = parseColor(color) ?: return intArrayOf(0, 0, 0)
        val r = c.substring(1, 3).toInt(16) / 255.0
        val g = c.substring(3, 5).toInt(16) / 255.0
        val b = c.substring(5, 7).toInt(16) / 255.0
        val max = maxOf(r, g, b)
        val d = max - minOf(r, g, b)
        val degrees = when {
            d == 0.0 -> 0.0
            max == r -> 60.0 * (((g - b) / d + 6.0) % 6.0)
            max == g -> 60.0 * ((b - r) / d + 2.0)
            else -> 60.0 * ((r - g) / d + 4.0)
        }
        val saturation = if (max == 0.0) 0.0 else d / max
        val l = level.coerceIn(0, MAX_LEVEL) / 100.0
        return intArrayOf(
            Math.round(degrees / 360.0 * 255.0).toInt().coerceIn(0, 255),
            Math.round(saturation * 255.0).toInt().coerceIn(0, 255),
            Math.round(max * l * 255.0).toInt().coerceIn(0, 255),
        )
    }

    /** The body of `POST /6/ambilight/currentconfiguration` that makes the lights [color] at [level] percent, still or slowly pulsing ([pulse]). */
    fun json(color: String, level: Int, pulse: Boolean = false): String {
        val (h, s, b) = hsb(color, level).let { Triple(it[0], it[1], it[2]) }
        val settings = JSONObject()
        settings.put("color", JSONObject().put("hue", h).put("saturation", s).put("brightness", b))
        settings.put("colorDelta", JSONObject().put("hue", 0).put("saturation", 0).put("brightness", if (pulse) minOf(PULSE_BRIGHTNESS, b) else 0))
        settings.put("speed", if (pulse) PULSE_SPEED else 0)
        val o = JSONObject()
        o.put("styleName", "FOLLOW_COLOR")
        o.put("isExpert", true)
        o.put("algorithm", "MANUAL_HUE")
        o.put("colorSettings", settings)
        return o.toString()
    }

    /** The moods for the phone page: key, label and colour. */
    fun moodsJson(): org.json.JSONArray = org.json.JSONArray().also { arr ->
        MOODS.forEach { m -> arr.put(JSONObject().put("key", m.key).put("label", m.label).put("color", m.color)) }
    }

    private val HEX = Regex("^[0-9a-f]{6}$")
}
