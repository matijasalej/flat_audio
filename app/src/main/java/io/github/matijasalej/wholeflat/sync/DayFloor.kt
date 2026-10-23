package io.github.matijasalej.wholeflat.sync

/**
 * A value that is kept for the rest of the local day it was set on: the floor of the kitchen's lag that an underrun showed.
 * The Sonos does not change in the course of a day, so the floor holds across streams and restarts of the app; the next day it is
 * tried again from the configured floor. The values are kept as text in a [Store] (SharedPreferences in the app), the day as the
 * number of days since 1970 in the local time zone.
 */
class DayFloor(private val store: Store, private val today: () -> Long) {
    interface Store {
        fun read(key: String): String?

        /** Sets the keys to the texts, removes those with null. */
        fun write(values: Map<String, String?>)
    }

    /** The value saved today, or null (a value of an earlier day is dropped). */
    fun load(): Double? {
        val day = store.read(DAY_KEY)?.toLongOrNull()
        val value = store.read(VALUE_KEY)?.toDoubleOrNull()
        if (day == null || value == null || value.isNaN() || value.isInfinite()) return null
        if (day != today()) {
            clear()
            return null
        }
        return value
    }

    /** The day of the saved value (days since 1970), or null. */
    fun savedDay(): Long? = store.read(DAY_KEY)?.toLongOrNull()

    fun save(ms: Double) {
        store.write(mapOf(VALUE_KEY to ms.toString(), DAY_KEY to today().toString()))
    }

    fun clear() {
        store.write(mapOf(VALUE_KEY to null, DAY_KEY to null))
    }

    companion object {
        const val VALUE_KEY = "floor_ms"
        const val DAY_KEY = "floor_day"

        /** The local day of [epochMs] for a zone that is [offsetMs] ahead of UTC at that moment. */
        fun dayOf(epochMs: Long, offsetMs: Long): Long = Math.floorDiv(epochMs + offsetMs, 86_400_000L)
    }
}
