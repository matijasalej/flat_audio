package io.github.matijasalej.wholeflat

import io.github.matijasalej.wholeflat.sync.MoodLight
import io.github.matijasalej.wholeflat.sync.RemoteActions
import io.github.matijasalej.wholeflat.sync.ReplaySwitch
import io.github.matijasalej.wholeflat.sync.StuckTv
import org.json.JSONObject

/**
 * What the phone page (`/remote`) and the tuning buttons of the debug page ask the app: the state they show and the changes they make.
 * The phone page has the switch of Synced on HDMI, the four displays of the TV (with the colour of Chill), the volume of the kitchen and
 * its fine sync; the rest of the tuning (the TV's extra, the lyrics) is on the debug page. A change is a change of settings (see [RemoteActions]); the settings do the rest.
 */
object RemoteApi {
    /** The state for the page: the display, the mode, the kitchen volume, and a line each of what the TV and the kitchen are doing. */
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
        // the living room cannot hear the muted Spotify app; else the TV's player is hung (it is not used while the room is replayed on HDMI):
        // the page says what to do, whatever the mode
        val replay = AppState.livingReplay
        val warning = replay?.warning()
            ?: if (AppState.tvRenderer?.stuck == true && replay?.replaying != true) StuckTv.ADVICE else null
        o.put("warning", warning ?: JSONObject.NULL)
        o.put("kitchen_text", kitchenText())
        o.put("source", AppState.source)
        o.put("version", BuildInfo.versionName)
        o.put("lights", AppState.lights?.phoneJson() ?: JSONObject.NULL)
        o.put("replay", replayJson())
        o.put("kitchen_offset_ms", v.kitchenOffsetMs)
        o.put("light_color", v.lightColor)
        o.put("light_level", v.lightLevel)
        o.put("light_pulse", v.lightPulse)
        o.put("moods", MoodLight.moodsJson())
        return o
    }

    /** The switch of Synced on HDMI: on (the Spotify app is muted), whether the app can switch it, and the line under it. */
    private fun replayJson(): JSONObject {
        val replay = AppState.livingReplay
        val adb = AppState.adbSelf
        adb?.probeSoon()
        val muted = replay?.muted
        val link = adb?.link ?: ReplaySwitch.Link.UNKNOWN
        return JSONObject()
            .put("on", muted ?: JSONObject.NULL)
            .put("pending", adb?.pending ?: JSONObject.NULL)
            .put("link", link.key)
            .put("text", ReplaySwitch.text(link, muted, replay?.replaying == true, adb?.pending, adb?.error))
    }

    private fun tvText(): String {
        val v = Settings.values
        // the Spotify app stays on the screen, its sound is played by this app in step with the kitchen
        if (AppState.livingReplay?.replaying == true) return "TV: Spotify app, in step (HDMI)"
        if (v.mode == "lyrics") return "TV: Spotify app"
        val r = AppState.tvRenderer ?: return "TV: not running"
        return when (r.state) {
            TvRenderer.State.PLAYING -> if (r.live) "TV: playing" else "TV: warming up"
            TvRenderer.State.STARTING -> "TV: starting"
            TvRenderer.State.FAILED -> if (r.stuck) "TV: player stuck" else "TV: did not start"
            TvRenderer.State.LEFT -> "TV: left the player"
            TvRenderer.State.IDLE -> when {
                r.stuck -> "TV: player stuck"
                // a fresh start waits until the TV has been on and its player closed long enough
                r.switchWaitMs() > 0L -> "TV: switch in ${(r.switchWaitMs() + 999) / 1000} s"
                AppState.source == "capture" -> "TV: waiting"
                else -> "TV: idle"
            }
        }
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
        val current = RemoteActions.Current(v.display, v.kitchenEnabled, volume, v.kitchenOffsetMs, v.lyricsOffsetMs, v.tvExtraMs, v.lightPulse)
        val r = RemoteActions.parse(query, current)
        // the switch of Synced on HDMI is no setting: it is the Spotify app's mute, set through the box's own adb
        query["replay"]?.trim()?.lowercase()?.let { want ->
            val on = when (want) {
                "on" -> true
                "off" -> false
                "toggle" -> AppState.livingReplay?.muted != true
                else -> null
            }
            val adb = AppState.adbSelf
            if (on != null && adb != null) {
                AppLog.add("remote", "Synced on HDMI ${if (on) "on" else "off"} (asked for: $query)")
                adb.switch(on)
            }
        }
        if (r.updates.length() > 0) {
            AppLog.add("remote", "${r.updates} (asked for: $query)")
            Settings.applyJson(r.updates)
        }
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
