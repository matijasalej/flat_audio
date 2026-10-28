package io.github.matijasalej.wholeflat.sync

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What happened at every switch of the TV to its player, kept on disk so that it outlives the log (2,000 lines in memory, once overwritten
 * in under a second) and a restart of the app: one line of JSON per event, the last [capacity] of them. A hung player can then be read next
 * to what came before it. The kinds the app writes:
 *  - `grab`: one start of the TV's player: why, the TV's power and how long it had been on, how long its player took to be ready, what its
 *    transport held before the item, whether it was stopped first, the tries of SetAVTransportURI and Play, the first GET, PLAYING, and
 *    how it ended (`outcome`);
 *  - `live`: the warm-up of a stream was over (`warmup_ms`);
 *  - `end`: a stream of the TV ended (why, and whether the TV was stopped before it); `after`: what the TV showed some seconds later;
 *  - `tv_power`: the TV's power as JointSpace tells it changed (On, Standby, unreachable);
 *  - `box`: the screen of the box went on or off (it sleeps at its timeout and broadcasts CEC Standby).
 *
 * The file is only appended to, and cut back to the last [capacity] lines when it holds twice as many. Plain Kotlin.
 */
class TvJournal(
    private val file: File?,
    private val capacity: Int = CAPACITY,
    private val wallMs: () -> Long = { System.currentTimeMillis() },
) {
    private val lines = ArrayDeque<String>()
    private var fileLines = 0

    /** The file ends in a line that a crash cut short: the next line starts on a line of its own. */
    private var cutShort = false

    /** Why the journal could not be read or written, if it could not (the app goes on without it). */
    @Volatile
    var lastError: String? = null
        private set

    init {
        load()
    }

    /** Adds an event of [kind] with [fields] (a copy is kept; the time and the kind are put first). */
    @Synchronized
    fun add(kind: String, fields: JSONObject = JSONObject()) {
        val o = JSONObject()
        o.put("t", stamp(wallMs()))
        o.put("kind", kind)
        val keys = fields.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k != "t" && k != "kind") o.put(k, fields.get(k))
        }
        val line = o.toString()
        lines.addLast(line)
        while (lines.size > capacity) lines.removeFirst()
        append(line)
    }

    /** The events, oldest first. */
    @Synchronized
    fun toJson(): JSONArray {
        val a = JSONArray()
        for (l in lines) a.put(JSONObject(l))
        return a
    }

    /** The last [n] events of [kind] (all kinds when null), oldest first. */
    @Synchronized
    fun last(n: Int, kind: String? = null): List<JSONObject> =
        lines.map { JSONObject(it) }.filter { kind == null || it.optString("kind") == kind }.takeLast(n)

    @Synchronized
    fun size(): Int = lines.size

    private fun load() {
        val f = file ?: return
        if (!f.exists()) return
        try {
            val text = f.readText()
            cutShort = text.isNotEmpty() && !text.endsWith("\n")
            val read = text.lines().filter { it.isNotBlank() }
            fileLines = read.size
            for (l in read.takeLast(capacity)) {
                try {
                    JSONObject(l)
                    lines.addLast(l)
                } catch (e: Exception) {
                    // a line cut by a crash is left out
                }
            }
        } catch (e: Exception) {
            lastError = "read: $e"
        }
    }

    private fun append(line: String) {
        val f = file ?: return
        try {
            if (fileLines + 1 > 2 * capacity) {
                // cut back to what is kept in memory: written to a new file that then takes the old one's place
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.writeText(lines.joinToString("\n", postfix = "\n"))
                if (!tmp.renameTo(f)) {
                    f.delete()
                    tmp.renameTo(f)
                }
                fileLines = lines.size
            } else {
                f.appendText((if (cutShort) "\n" else "") + line + "\n")
                cutShort = false
                fileLines++
            }
        } catch (e: Exception) {
            lastError = "write: $e"
        }
    }

    private fun stamp(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(ms))

    companion object {
        /** About a week of switches at a few a day, with the power changes and the box's sleeps around them. */
        const val CAPACITY = 300
    }
}
