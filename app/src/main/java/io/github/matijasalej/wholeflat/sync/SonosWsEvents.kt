package io.github.matijasalej.wholeflat.sync

import org.json.JSONObject

/**
 * What the speaker's websocket says besides the answers to our questions: the playback events it sends once
 * we have subscribed (`playbackStatus`, `playbackError`) and the error fields that an answer or an event
 * may carry. Only used to say why a start failed, so it is lenient: it finds error fields by name, at any
 * level of the message, and ignores what it does not know.
 */
object SonosWsEvents {
    class Event(val type: String, val state: String, val error: String)

    /**
     * The error fields of a message body as text: every key with "error" in its name (`errorCode`, `playbackError`, ...)
     * and the `reason` or `message` that stands beside one, as "name=value" separated by spaces. Empty if there are none.
     */
    fun errorText(body: JSONObject): String {
        val parts = ArrayList<String>()
        collect(body, 0, parts)
        return parts.joinToString(" ")
    }

    private fun collect(o: JSONObject, depth: Int, out: MutableList<String>) {
        val names = ArrayList<String>()
        val it = o.keys()
        while (it.hasNext()) names.add(it.next())
        names.sort() // the order of the keys of a JSONObject is arbitrary; the text should not depend on it
        val hasError = names.any { it.contains("error", ignoreCase = true) }
        for (k in names) {
            val v = o.opt(k)
            val isError = k.contains("error", ignoreCase = true)
            if (isError || (hasError && (k == "reason" || k == "message"))) {
                when {
                    v == null || v == JSONObject.NULL -> Unit
                    v is JSONObject -> out.add(k + "=" + v.toString().take(MAX_VALUE))
                    v.toString().isNotEmpty() -> out.add(k + "=" + v.toString().take(MAX_VALUE))
                }
            } else if (v is JSONObject && depth < MAX_DEPTH) {
                collect(v, depth + 1, out)
            }
        }
    }

    /**
     * An event of the speaker (a message without a `cmdId`, with its name as `type` in the header): `playbackStatus`
     * with the playback state, `playbackError` with the error. Null for anything else.
     */
    fun parse(header: JSONObject, body: JSONObject): Event? {
        return when (val type = header.optString("type", "")) {
            "playbackError" -> Event(type, "", errorText(body).ifEmpty { body.toString().take(MAX_VALUE) })
            "playbackStatus" -> Event(type, body.optString("playbackState", ""), errorText(body))
            else -> null
        }
    }

    private const val MAX_DEPTH = 3
    private const val MAX_VALUE = 200
}
