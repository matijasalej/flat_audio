package io.github.matijasalej.wholeflat

import io.github.matijasalej.wholeflat.sync.MoodLight
import io.github.matijasalej.wholeflat.sync.RemoteActions
import io.github.matijasalej.wholeflat.sync.TvPresentation
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the phone page (`/remote`) and the tuning buttons of the debug page ask the app: the state they show and the changes they make.
 * The phone page has the three displays of the TV, the volume of the kitchen and the mood light, and nothing else; the fine tuning (the TV's
 * extra, the fine sync of the kitchen, the lyrics) is on the debug page. A change is a change of settings (see [RemoteActions]); the settings
 * do the rest.
 */
object RemoteApi {
    /** The state for the page: the display, the mode, the kitchen volume, the mood light and its moods, and a line each of what the TV, the kitchen and the lights are doing. */
    fun stateJson(): JSONObject {
        val v = Settings.values
        val o = JSONObject()
        o.put("display", v.display)
        o.put("mode", v.mode)
        val volume = try {
            AppState.kitchen?.sonosVolumeNow()
        } catch (t: Throwable) {
            null
        }
        o.put("volume", volume ?: JSONObject.NULL)
        o.put("tv_text", tvText())
        o.put("kitchen_text", kitchenText())
        o.put("light", v.light)
        o.put("light_color", v.lightColor)
        o.put("light_level", v.lightLevel)
        o.put("light_first", v.lightFirst)
        o.put("light_text", lightText())
        val palette = JSONArray()
        for (m in MoodLight.MOODS) palette.put(JSONObject().put("key", m.key).put("label", m.label).put("color", m.color))
        o.put("palette", palette)
        o.put("source", AppState.source)
        o.put("version", BuildInfo.versionName)
        return o
    }

    private fun tvText(): String {
        val v = Settings.values
        if (v.mode == "lyrics") return "TV: Spotify app"
        val r = AppState.tvRenderer ?: return "TV: not running"
        return when (r.state) {
            TvRenderer.State.PLAYING -> if (r.live) "TV: playing" else "TV: warming up"
            TvRenderer.State.STARTING -> "TV: starting"
            TvRenderer.State.FAILED -> "TV: did not start"
            TvRenderer.State.LEFT -> "TV: left the player"
            TvRenderer.State.IDLE -> if (AppState.source == "capture") "TV: waiting" else "TV: idle"
        }
    }

    private fun lightText(): String {
        val v = Settings.values
        val now = AppState.synced?.lightsNow()
        if (v.light == MoodLight.OFF) return if (now == TvPresentation.Lights.MUSIC) "Lights: with the music" else "Light: off"
        val name = MoodLight.labelOf(v.light)
        return if (now == TvPresentation.Lights.MUSIC) "Light: $name after the music" else "Light: $name"
    }

    private fun kitchenText(): String {
        val k = AppState.kitchen ?: return "Kitchen: not running"
        return "Kitchen: " + k.state.label
    }

    /** Applies the presses in [query] to the settings; the answer is the new state, with what was refused. */
    fun action(query: Map<String, String>): JSONObject {
        val v = Settings.values
        val volume = try {
            AppState.kitchen?.sonosVolumeNow() ?: -1
        } catch (t: Throwable) {
            -1
        }
        val current = RemoteActions.Current(v.display, v.kitchenEnabled, volume, v.kitchenOffsetMs, v.lyricsOffsetMs, v.tvExtraMs, v.lightLevel)
        val r = RemoteActions.parse(query, current)
        if (r.updates.length() > 0) {
            AppLog.add("remote", "${r.updates} (asked for: $query)")
            Settings.applyJson(r.updates)
        }
        // a tap on a mood (also the one that is on) takes the picture and is sent whole, which also mends lights that the TV lost
        if (query.containsKey("light") && r.updates.has("light")) AppState.synced?.lightTapped()
        val o = stateJson()
        if (r.errors.isNotEmpty()) o.put("errors", org.json.JSONArray(r.errors))
        return o
    }

    /** The values the tuning buttons of the debug page show. */
    fun tuningJson(): JSONObject {
        val v = Settings.values
        val o = JSONObject()
        o.put("display", v.display)
        o.put("tv_extra_ms", v.tvExtraMs)
        o.put("kitchen_offset_ms", v.kitchenOffsetMs)
        o.put("lyrics_offset_ms", v.lyricsOffsetMs)
        val r = AppState.tvRenderer
        o.put("tv_latency_ms", r?.tvLatencyMs() ?: JSONObject.NULL)
        o.put("tv_delay_ms", if (r == null || r.delayMs.isNaN()) JSONObject.NULL else Math.round(r.delayMs))
        return o
    }
}
