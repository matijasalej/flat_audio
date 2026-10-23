package io.github.matijasalej.wholeflat

import android.content.Context
import android.content.SharedPreferences
import io.github.matijasalej.wholeflat.sync.SettingsModel
import io.github.matijasalej.wholeflat.sync.SettingsValues
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The settings of the app: kept in SharedPreferences, changed from the screen or over HTTP
 * (`/settings`), read by the other parts through [values] (an immutable snapshot).
 *
 * Listeners are called on the thread that made the change, with the names of the settings that
 * changed; they must not block.
 */
object Settings {
    private const val PREFS = "wholeflat_settings"
    private const val TAG = "settings"

    private val model = SettingsModel()
    private var prefs: SharedPreferences? = null
    private val listeners = CopyOnWriteArrayList<(Set<String>) -> Unit>()

    @Volatile
    var values: SettingsValues = model.values()
        private set

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        val stored = JSONObject()
        try {
            for ((key, value) in p.all) {
                if (value != null) stored.put(key, value)
            }
        } catch (e: Exception) {
            AppLog.error(TAG, "cannot read the stored settings", e)
        }
        model.applyJson(stored)
        values = model.values()
        AppLog.add(TAG, "loaded: " + model.toJson())
    }

    fun addListener(listener: (Set<String>) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (Set<String>) -> Unit) {
        listeners.remove(listener)
    }

    @Synchronized
    fun toJson(): JSONObject = model.toJson()

    /** Applies a (partial) JSON object, stores the result and tells the listeners. Returns what changed. */
    fun applyJson(json: JSONObject): Set<String> {
        val changed: Set<String>
        synchronized(this) {
            changed = model.applyJson(json)
            if (changed.isNotEmpty()) {
                values = model.values()
                persist()
            }
        }
        if (changed.isNotEmpty()) {
            AppLog.add(TAG, "changed: $changed -> " + toJson())
            for (listener in listeners) {
                try {
                    listener(changed)
                } catch (e: Exception) {
                    AppLog.error(TAG, "a settings listener failed", e)
                }
            }
        }
        return changed
    }

    /** One setting by name, for the screen. */
    fun set(key: String, value: Any) {
        val o = JSONObject()
        o.put(key, value)
        applyJson(o)
    }

    private fun persist() {
        val p = prefs ?: return
        val json = model.toJson()
        val editor = p.edit()
        for (key in json.keys()) {
            when (val v = json.get(key)) {
                is Boolean -> editor.putBoolean(key, v)
                is Int -> editor.putInt(key, v)
                is String -> editor.putString(key, v)
                else -> editor.putString(key, v.toString())
            }
        }
        editor.apply()
    }
}
