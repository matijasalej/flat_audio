package io.github.matijasalej.wholeflat.light

import org.json.JSONArray
import org.json.JSONObject

/** Where the song memory is kept (SharedPreferences in the app, a string in the tests). */
interface SongStore {
    fun read(): String?

    fun write(json: String)
}

/**
 * What the lights remember of each song, by its key: the tempo, energy, beat clarity and mood measured the last time it played, the class
 * it got, and **his override** (more or less colour on the phone page), which wins from then on. A song that comes back starts with its
 * tempo as the centre of the prior and its class from the first second. It is also the history the review reads (`/lights/history`).
 * At most [max] songs are kept, the ones heard longest ago are dropped first. Plain Kotlin; thread safe.
 */
class SongMemory(private val store: SongStore, private val max: Int = 500) {
    class Entry(
        val key: String,
        val title: String?,
        val artist: String?,
        val bpm: Double,
        val energy: Double,
        val clarity: Double,
        val mood: Double,
        val cls: LightClass?,
        val override: LightClass?,
        val plays: Int,
        val lastMs: Long,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("key", key)
            put("title", title ?: JSONObject.NULL)
            put("artist", artist ?: JSONObject.NULL)
            put("bpm", if (bpm.isNaN()) JSONObject.NULL else round(bpm, 1))
            put("energy", round(energy, 3))
            put("clarity", round(clarity, 3))
            put("mood", round(mood, 3))
            put("class", cls?.key ?: JSONObject.NULL)
            put("override", override?.key ?: JSONObject.NULL)
            put("plays", plays)
            put("last_ms", lastMs)
        }

        companion object {
            fun of(o: JSONObject): Entry = Entry(
                key = o.getString("key"),
                title = o.optString("title").takeIf { !o.isNull("title") && it.isNotEmpty() },
                artist = o.optString("artist").takeIf { !o.isNull("artist") && it.isNotEmpty() },
                bpm = if (o.isNull("bpm")) Double.NaN else o.optDouble("bpm", Double.NaN),
                energy = o.optDouble("energy", 0.0),
                clarity = o.optDouble("clarity", 0.0),
                mood = o.optDouble("mood", 0.5),
                cls = LightClass.of(o.optString("class")),
                override = LightClass.of(o.optString("override")),
                plays = o.optInt("plays", 0),
                lastMs = o.optLong("last_ms", 0L),
            )

            private fun round(v: Double, digits: Int): Double {
                val f = Math.pow(10.0, digits.toDouble())
                return Math.round(v * f) / f
            }
        }
    }

    private val lock = Any()
    private val songs = LinkedHashMap<String, Entry>()

    init {
        try {
            val text = store.read()
            if (!text.isNullOrBlank()) {
                val arr = JSONArray(text)
                for (i in 0 until arr.length()) {
                    val e = Entry.of(arr.getJSONObject(i))
                    songs[e.key] = e
                }
            }
        } catch (e: Exception) {
            songs.clear() // a memory that cannot be read is started again
        }
    }

    fun get(key: String): Entry? = synchronized(lock) { songs[key] }

    /** Notes what was measured of [key] this time ([newPlay]: the first note of this play counts a play). */
    fun remember(key: String, title: String?, artist: String?, bpm: Double, energy: Double, clarity: Double, mood: Double, cls: LightClass, nowMs: Long, newPlay: Boolean) {
        synchronized(lock) {
            val old = songs.remove(key)
            songs[key] = Entry(key, title ?: old?.title, artist ?: old?.artist, bpm, energy, clarity, mood, cls, old?.override, (old?.plays ?: 0) + if (newPlay) 1 else 0, nowMs)
            trim()
            save()
        }
    }

    /** His choice for [key] (null clears it): the class it gets from now on. */
    fun override(key: String, cls: LightClass?, nowMs: Long) {
        synchronized(lock) {
            val old = songs.remove(key)
            songs[key] = if (old != null) {
                Entry(old.key, old.title, old.artist, old.bpm, old.energy, old.clarity, old.mood, old.cls, cls, old.plays, nowMs)
            } else {
                Entry(key, null, null, Double.NaN, 0.0, 0.0, 0.5, null, cls, 0, nowMs)
            }
            trim()
            save()
        }
    }

    /** Every song, the one heard last first. */
    fun all(): List<Entry> = synchronized(lock) { songs.values.toList().asReversed() } // not reversed(): Java 21's would bind and crash on Android 14

    private fun trim() {
        while (songs.size > max) songs.remove(songs.keys.first())
    }

    private fun save() {
        val arr = JSONArray()
        for (e in songs.values) arr.put(e.toJson())
        try {
            store.write(arr.toString())
        } catch (e: Exception) {
            // the memory is a convenience: a song that cannot be written is measured again next time
        }
    }
}
