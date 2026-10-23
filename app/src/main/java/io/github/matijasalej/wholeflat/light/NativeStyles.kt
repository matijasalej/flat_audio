package io.github.matijasalej.wholeflat.light

import org.json.JSONObject

/**
 * The TV's own Ambilight styles that the lights use where they cannot paint the picture (Lyrics mode, with the Spotify app over HDMI),
 * one JointSpace `currentconfiguration` per song or section:
 * - **Mono:** `FOLLOW_COLOR` with the home colour (expert, `MANUAL_HUE`).
 * - **Duo:** `FOLLOW_AUDIO` with our two colours (expert `audioSettings`), if the TV takes them. Otherwise its own algorithm.
 * - **Multi:** a `FOLLOW_AUDIO` algorithm of the TV.
 *
 * The algorithms per class are settings (`lights_native_*`), so that what the TV test shows can be taken over without a new build.
 * And the style for Synced mode, where the picture carries the lights, is `FOLLOW_VIDEO` with a menu setting or Lumina (the setting
 * `lights_synced_style`, "STYLE/MENU"). Plain Kotlin.
 */
object NativeStyles {
    /** The plain (not expert) configuration "STYLE/MENU", e.g. `FOLLOW_VIDEO/GAME`. */
    fun plain(styleAndMenu: String): String {
        val style = styleAndMenu.substringBefore('/')
        val menu = styleAndMenu.substringAfter('/', "")
        val o = JSONObject().put("styleName", style).put("isExpert", false)
        if (menu.isNotEmpty()) o.put("menuSetting", menu)
        return o.toString()
    }

    /** One colour held by the TV (expert `FOLLOW_COLOR`, `MANUAL_HUE`). */
    fun manualHue(c: Int): String = JSONObject()
        .put("styleName", "FOLLOW_COLOR")
        .put("isExpert", true)
        .put("algorithm", "MANUAL_HUE")
        .put("colorSettings", JSONObject().put("color", hsb(c)).put("colorDelta", JSONObject().put("hue", 0).put("saturation", 0).put("brightness", 0)).put("speed", 255))
        .toString()

    /** The TV's audio algorithm [algorithm] in our colours (expert `FOLLOW_AUDIO`; [second] goes in `colorDelta`). */
    fun audioWithColours(algorithm: String, first: Int, second: Int): String = JSONObject()
        .put("styleName", "FOLLOW_AUDIO")
        .put("isExpert", true)
        .put("audioSettings", JSONObject().put("algorithm", algorithm).put("color", hsb(first)).put("colorDelta", hsb(second)))
        .put("tuning", 0)
        .toString()

    /**
     * The configuration for a song of [look] shown as [cls] in Lyrics mode. [mono], [duo] and [multi] are the settings: "COLOR" (our
     * home colour, held), "COLORS:<algorithm>" (the algorithm in our colours) or a plain algorithm of `FOLLOW_AUDIO`.
     */
    fun forLook(look: SongLook, cls: LightClass, mono: String, duo: String, multi: String): String {
        val choice = when (cls) {
            LightClass.MONO -> mono
            LightClass.DUO -> duo
            LightClass.MULTI -> multi
        }
        return when {
            choice == "COLOR" -> manualHue(look.home)
            choice.startsWith("COLORS:") -> audioWithColours(choice.substringAfter(':'), look.home, look.accent)
            else -> plain("FOLLOW_AUDIO/$choice")
        }
    }

    /** Hue, saturation and brightness of [c] on the TV's scale of 0 to 255. */
    fun hsb(c: Int): JSONObject {
        val hsv = Colors.toHsv(c)
        return JSONObject()
            .put("hue", Math.round(hsv[0] / 360.0 * 255).toInt().coerceIn(0, 255))
            .put("saturation", Math.round(hsv[1] * 255).toInt().coerceIn(0, 255))
            .put("brightness", Math.round(hsv[2] * 255).toInt().coerceIn(0, 255))
    }
}
