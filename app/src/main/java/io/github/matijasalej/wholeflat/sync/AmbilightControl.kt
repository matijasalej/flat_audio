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
 * The Ambilight of the TV during Synced mode (the lights follow the audio, the TV being the karaoke screen of the flat). Entering
 * Synced remembers the power and the configuration, switches the power on and **then** sets the style (the other way round the style
 * is overridden by the style that the power-on restores: seen on the TV); leaving puts back what was remembered, and if that was
 * "off" (the power was Off or the style was "OFF") it just switches the power off. The remembered state is stored before anything is
 * changed, so that an app that crashed in Synced mode puts the lights back at its next start ([recover]) instead of leaving them on.
 * The mood lights may give a whole configuration of their own ([config]); without one, the style is [style] with [menuSetting].
 *
 * Plain Kotlin behind two small interfaces.
 */
class AmbilightControl(
    private val api: AmbilightApi,
    private val store: AmbilightStore,
    private val style: () -> String,
    private val menuSetting: () -> String,
    private val config: () -> String? = { null },
) {
    /** The state the lights were in before Synced mode: the power ("On" / "Off") and the raw configuration JSON. */
    class Saved(val power: String, val config: String)

    /** One call of the restore, in order. */
    sealed class Step {
        class Power(val on: Boolean) : Step()

        class Config(val json: String) : Step()
    }

    @Volatile
    var entered = false
        private set

    /** What was remembered at the last entry (still set while in Synced mode, or after a failed restore), for the status. */
    @Volatile
    var remembered: Saved? = store.read()
        private set

    @Volatile
    var lastAction = ""
        private set

    @Volatile
    var lastActionOk = true
        private set

    /** Entering Synced mode: remember, power on, then the style. False if the TV did not answer (nothing was changed or remembered). */
    @Synchronized
    fun enter(): Boolean {
        if (entered) return true
        val power = api.power()
        val config = api.config()
        if (power == null || config == null) {
            note("the TV's Ambilight does not answer, left alone", false)
            return false
        }
        val saved = remembered ?: Saved(power, config) // a state that an earlier crash left behind is the one to put back
        remembered = saved
        store.write(saved)
        val on = api.setPower(true)
        val json = styleJson()
        val set = api.setConfig(json)
        entered = true
        note("Ambilight on (was ${saved.power}, ${styleName(saved.config)}), power ${ok(on)}, style ${styleName(json)} ${ok(set)}", on && set)
        return true
    }

    /** Leaving Synced mode (or the app stopping): the remembered state is put back. */
    @Synchronized
    fun leave() {
        val saved = remembered
        if (!entered && saved == null) return
        entered = false
        if (saved == null) return
        restore(saved, "leaving Synced mode")
    }

    /** At the start of the app: lights that a crash left on are put back. */
    @Synchronized
    fun recover() {
        val saved = remembered ?: return
        if (entered) return
        restore(saved, "after a crash")
    }

    /** The style setting changed while in Synced mode. */
    @Synchronized
    fun restyle() {
        if (!entered) return
        val json = styleJson()
        val set = api.setConfig(json)
        note("Ambilight style ${styleName(json)} ${ok(set)}", set)
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

    private fun styleJson(): String {
        config()?.let { return it }
        val o = JSONObject()
        o.put("styleName", style())
        o.put("isExpert", false)
        o.put("menuSetting", menuSetting())
        return o.toString()
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

        /** The `styleName` of a configuration JSON, "" if there is none. */
        fun styleName(config: String): String = try {
            JSONObject(config).optString("styleName", "")
        } catch (e: Exception) {
            ""
        }
    }
}
