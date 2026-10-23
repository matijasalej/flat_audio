package io.github.matijasalej.wholeflat.sync

import org.json.JSONObject

/**
 * What a press on the phone page (or on the tuning buttons of the debug page) asks for, as changes to the settings:
 * `/remote/action?display=screen&volume=%2B5`, `/remote/action?light=cozy&level=-`, `/tune?fine=-&tv_extra=%2B`. Several may come in one
 * request. Nothing here touches the app; the result is a partial settings object (what `Settings.applyJson` takes). Choosing a display also
 * puts the app back in Synced mode (it may have been in Lyrics mode, which the user got into by leaving the TV's player with the TV's own
 * remote). The last tap wins between the display and the mood light: a mood or a colour takes the TV, also from the music, and a display
 * gives it back, also the display that was already chosen (`light_first`). Plain Kotlin.
 */
object RemoteActions {
    class Result(val updates: JSONObject, val notes: List<String>, val errors: List<String>)

    /** The state the actions are worked out from. */
    class Current(
        val display: String,
        val kitchenEnabled: Boolean,
        /** The volume of the Sonos now (0 to 100): the setting, or what the speaker says when the setting is unset; -1 if not known. */
        val kitchenVolume: Int,
        val kitchenOffsetMs: Int,
        val lyricsOffsetMs: Int,
        val tvExtraMs: Int,
        /** How bright the mood light is now (percent). */
        val lightLevel: Int = MoodLight.DEFAULT_LEVEL,
    )

    /** How far one press of the fine sync moves the kitchen against the TV (ms). */
    const val FINE_STEP_MS = 10

    /** How far one press of the volume buttons moves the kitchen volume. */
    const val VOLUME_STEP = 3

    /** How far one press of the lyrics buttons moves them (ms). */
    const val LYRICS_STEP_MS = 100

    /** How far one press of the brightness buttons moves the mood light (percent). */
    const val LEVEL_STEP = 10

    fun parse(query: Map<String, String>, now: Current): Result {
        val updates = JSONObject()
        val notes = ArrayList<String>()
        val errors = ArrayList<String>()
        for ((key, raw) in query) {
            val value = raw.trim()
            when (key) {
                "display" -> {
                    val current = Display.of(now.display) ?: Display.DEFAULT
                    val d = if (value.equals("next", ignoreCase = true)) current.next() else Display.of(value)
                    if (d == null) {
                        errors.add("display: $value")
                    } else {
                        updates.put("display", d.key)
                        updates.put("mode", Mode.SYNCED.key)
                        updates.put("light_first", false)
                        notes.add("display ${d.label}")
                    }
                }
                "light" -> {
                    val l = MoodLight.settingOf(value)
                    if (l == null) errors.add("light: $value") else {
                        updates.put("light", l)
                        if (l != MoodLight.OFF) updates.put("light_first", true)
                        notes.add("light ${MoodLight.labelOf(l)}")
                    }
                }
                "color" -> {
                    val c = MoodLight.parseColor(value)
                    if (c == null) errors.add("color: $value") else {
                        updates.put("light", MoodLight.CUSTOM)
                        updates.put("light_color", c)
                        updates.put("light_first", true)
                        notes.add("light $c")
                    }
                }
                "level" -> {
                    val v = steps(value, now.lightLevel, LEVEL_STEP)
                    if (v == null) errors.add("level: $value") else updates.put("light_level", v.coerceIn(MoodLight.MIN_LEVEL, MoodLight.MAX_LEVEL))
                }
                "kitchen" -> flag(value, now.kitchenEnabled)?.let { updates.put("kitchen_enabled", it) } ?: errors.add("kitchen: $value")
                "volume" -> {
                    val v = volume(value, now.kitchenVolume)
                    if (v == null) errors.add("volume: $value") else {
                        updates.put("kitchen_volume", v)
                        notes.add("kitchen volume $v")
                    }
                }
                "fine" -> {
                    val v = steps(value, now.kitchenOffsetMs, FINE_STEP_MS)
                    if (v == null) errors.add("fine: $value") else updates.put("kitchen_offset_ms", v)
                }
                "lyrics" -> {
                    val v = steps(value, now.lyricsOffsetMs, LYRICS_STEP_MS)
                    if (v == null) errors.add("lyrics: $value") else updates.put("lyrics_offset_ms", v)
                }
                "tv_extra" -> {
                    val v = steps(value, now.tvExtraMs, FINE_STEP_MS)
                    if (v == null) errors.add("tv_extra: $value") else updates.put("tv_extra_ms", v)
                }
                else -> Unit // the page's own parameters (a cache buster, a time stamp)
            }
        }
        return Result(updates, notes, errors)
    }

    /** "on", "off" or "toggle" (also true/false, 1/0); null for anything else. */
    private fun flag(value: String, current: Boolean): Boolean? = when (value.lowercase()) {
        "on", "true", "1", "yes" -> true
        "off", "false", "0", "no" -> false
        "toggle" -> !current
        else -> null
    }

    /** "+3" and "-3" (or "up" and "down") step the volume, a plain number sets it; always 0 to 100. A step from an unknown volume starts at 30. */
    private fun volume(value: String, current: Int): Int? {
        val base = if (current >= 0) current else DEFAULT_START_VOLUME
        val v = when (value.lowercase()) {
            "up", "+" -> base + VOLUME_STEP
            "down", "-" -> base - VOLUME_STEP
            else -> {
                val n = value.toIntOrNull() ?: return null
                if (value.startsWith("+") || value.startsWith("-")) base + n else n
            }
        }
        return v.coerceIn(0, 100)
    }

    /** "+" and "-" step by [step], "+20" and "-20" by that many ms, a plain number sets the value; null if it is none of them. */
    private fun steps(value: String, current: Int, step: Int): Int? = when (value) {
        "+", "up" -> current + step
        "-", "down" -> current - step
        "0" -> 0
        else -> {
            val n = value.toIntOrNull()
            if (n == null) null else if (value.startsWith("+") || value.startsWith("-")) current + n else n
        }
    }

    private const val DEFAULT_START_VOLUME = 30
}
