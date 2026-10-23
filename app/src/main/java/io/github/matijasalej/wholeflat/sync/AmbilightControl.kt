package io.github.matijasalej.wholeflat.sync

import org.json.JSONObject

/** What the TV's Ambilight can be asked (JointSpace v6 `/6/ambilight/...`); the TV control implements it, the tests fake it. */
interface AmbilightApi {
    /** `power`: "On" or "Off"; null if the TV does not answer. */
    fun power(): String?

    /** The raw JSON of `currentconfiguration` (`{"styleName":"OFF","isExpert":false}`); null if the TV does not answer. */
    fun config(): String?

    fun setPower(on: Boolean): Boolean

    fun setConfig(json: String): Boolean
}

/** Where the remembered state is kept so that it survives a crash of the app (SharedPreferences in the app). */
interface AmbilightStore {
    fun read(): AmbilightControl.Saved?

    fun write(saved: AmbilightControl.Saved?)
}

/**
 * The Ambilight of the TV while this app has it: for the music (the lights follow the audio, the TV being the karaoke screen of the flat)
 * or for the mood light (one colour, see [MoodLight]). The first [hold] remembers the power and the configuration, switches the power on and
 * **then** sets the configuration (the other way round the style is overridden by the style that the power-on restores: seen on the TV);
 * later holds only send a configuration that changed, so going from the music to the mood light keeps what was remembered before either.
 * [leave] puts back what was remembered, and if that was "off" (the power was Off or the style was "OFF") it just switches the power off.
 * The remembered state is stored before anything is changed, so that an app that crashed while it had the lights puts them back at its
 * next start ([recover]) instead of leaving them on.
 *
 * Plain Kotlin behind two small interfaces.
 */
class AmbilightControl(
    private val api: AmbilightApi,
    private val store: AmbilightStore,
) {
    /** The state the lights were in before this app took them: the power ("On" / "Off") and the raw configuration JSON. */
    class Saved(val power: String, val config: String)

    /** One call of the restore, in order. */
    sealed class Step {
        class Power(val on: Boolean) : Step()

        class Config(val json: String) : Step()
    }

    @Volatile
    var entered = false
        private set

    /** The configuration the TV took at the last hold; null before it took one (or after it refused one, which is then sent again). */
    @Volatile
    var held: String? = null
        private set

    /** What was remembered at the first hold (still set while the lights are held, or after a failed restore), for the status. */
    @Volatile
    var remembered: Saved? = store.read()
        private set

    @Volatile
    var lastAction = ""
        private set

    @Volatile
    var lastActionOk = true
        private set

    /**
     * The lights are wanted as [json]: the first time, remember and power on, then the configuration; after that the configuration only if it
     * changed, or anyway with [force] (the power too then, and after a configuration the TV refused). False if the TV did not answer at the
     * first hold (nothing was changed or remembered).
     */
    @Synchronized
    fun hold(json: String, force: Boolean = false): Boolean {
        if (entered && json == held && !force) return true
        var was = ""
        if (!entered) {
            val power = api.power()
            val config = api.config()
            if (power == null || config == null) {
                note("the TV's Ambilight does not answer, left alone", false)
                return false
            }
            val saved = remembered ?: Saved(power, config) // a state that an earlier crash left behind is the one to put back
            remembered = saved
            store.write(saved)
            entered = true
            was = " (was ${saved.power}, ${styleName(saved.config)})"
        }
        // the power before the configuration, whenever the TV may not have taken it: at the first hold, after a refusal, when forced
        val powerToo = held == null || force
        val on = if (powerToo) api.setPower(true) else true
        val set = api.setConfig(json)
        held = if (on && set) json else null
        val power = if (powerToo) "power ${ok(on)}, " else ""
        note("Ambilight ${describe(json)}$was: $power${ok(set)}", on && set)
        return true
    }

    /** The lights are not wanted any more (or the app stops): the remembered state is put back. */
    @Synchronized
    fun leave() {
        val saved = remembered
        if (!entered && saved == null) return
        entered = false
        held = null
        if (saved == null) return
        restore(saved, "now that they are not wanted")
    }

    /** At the start of the app: lights that a crash left on are put back. */
    @Synchronized
    fun recover() {
        val saved = remembered ?: return
        if (entered) return
        restore(saved, "after a crash")
    }

    private fun restore(saved: Saved, why: String) {
        var allOk = true
        for (step in plan(saved)) {
            allOk = when (step) {
                is Step.Power -> api.setPower(step.on)
                is Step.Config -> api.setConfig(step.json)
            } && allOk
        }
        if (allOk) {
            store.write(null)
            remembered = null
        }
        note("Ambilight put back $why (power ${saved.power}, ${styleName(saved.config)}): ${ok(allOk)}", allOk)
    }

    private fun note(text: String, ok: Boolean) {
        lastAction = text
        lastActionOk = ok
    }

    private fun ok(v: Boolean): String = if (v) "ok" else "refused"

    companion object {
        /**
         * The calls that put [saved] back: if the lights were off (the power Off, or the style "OFF"), the power Off and nothing else;
         * else the power On and then the configuration as it was read (the style comes after the power, or the power-on overrides it).
         */
        fun plan(saved: Saved): List<Step> {
            val off = saved.power.equals("Off", ignoreCase = true) || styleName(saved.config).equals("OFF", ignoreCase = true)
            return if (off) listOf(Step.Power(false)) else listOf(Step.Power(true), Step.Config(saved.config))
        }

        /** The configuration of one of the TV's own styles (the music's: `FOLLOW_AUDIO` with its `menuSetting`). */
        fun styleJson(style: String, menuSetting: String): String {
            val o = JSONObject()
            o.put("styleName", style)
            o.put("isExpert", false)
            o.put("menuSetting", menuSetting)
            return o.toString()
        }

        /** The `styleName` of a configuration JSON, "" if there is none. */
        fun styleName(config: String): String = try {
            JSONObject(config).optString("styleName", "")
        } catch (e: Exception) {
            ""
        }

        /** A configuration in a few words for the log: the style and its setting, or the colour of the mood light. */
        fun describe(config: String): String = try {
            val o = JSONObject(config)
            val color = o.optJSONObject("colorSettings")?.optJSONObject("color")
            if (color != null) {
                "colour h${color.optInt("hue")} s${color.optInt("saturation")} b${color.optInt("brightness")}"
            } else {
                listOf(o.optString("styleName", ""), o.optString("menuSetting", "")).filter { it.isNotEmpty() }.joinToString(" ")
            }
        } catch (e: Exception) {
            "?"
        }
    }
}
