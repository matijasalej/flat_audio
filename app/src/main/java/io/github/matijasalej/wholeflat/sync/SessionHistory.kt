package io.github.matijasalej.wholeflat.sync

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What the Sonos did with the kitchen's streams, kept for the last few sessions so that a device test can be read
 * afterwards (`/kitchen/last-sessions`): for each session every connection of the speaker to the stream URL
 * (GET or HEAD) with when it began and ended, the bytes and blocks sent, who closed it and why, and the transport
 * state and status that the speaker showed during and after the session.
 *
 * All times are milliseconds since the session was created. Plain Kotlin; the caller passes `System.nanoTime()`.
 */
class ConnRecord(
    val id: Int,
    val method: String,
    val remote: String,
    val userAgent: String,
    /** Play had been sent when the connection came (the stream proper, as opposed to a probe before it). */
    val armed: Boolean,
    val startMs: Long,
) {
    @Volatile var firstByteMs = -1L

    /** The Range header of the request, if it had one (the Sonos has not asked for a range so far). */
    @Volatile var range = ""
    @Volatile var endMs = -1L
    @Volatile var bytes = 0L
    @Volatile var blocks = 0L

    /** Writes to the renderer, the time they blocked (ms, total and longest) and, for a stream paced in real time, how late the latest write was against its schedule (ms). */
    @Volatile var writes = 0L
    @Volatile var writeMsTotal = 0.0
    @Volatile var writeMsMax = 0.0
    @Volatile var slipMsMax = 0.0

    /** For a stream that is held to a delay after the capture: the last error of the hold (ms, positive: late) and the frames trimmed to remove it. */
    @Volatile var holdErrorMs = Double.NaN
    @Volatile var trimmedFrames = 0L

    /**
     * For a transport stream: how old the key place was that the connection started at (ms; the part of the stream that was sent at once is
     * the burst), the bytes of that burst, and how old the newest data was when it was written (ms, the most after the burst).
     */
    @Volatile var startAgeMs = Double.NaN
    @Volatile var burstBytes = -1L
    @Volatile var behindMsMax = Double.NaN

    /** "peer" or "server" once it is closed. */
    @Volatile var closedBy = ""
    @Volatile var reason = ""

    val isOpen: Boolean get() = endMs < 0

    fun toJson(nowMs: Long): JSONObject {
        val o = JSONObject()
        o.put("conn", id)
        o.put("method", method)
        o.put("remote", remote)
        o.put("user_agent", userAgent)
        o.put("armed", armed)
        if (range.isNotEmpty()) o.put("range", range)
        o.put("start_ms", startMs)
        o.put("first_byte_ms", if (firstByteMs < 0) JSONObject.NULL else firstByteMs)
        if (endMs < 0) {
            o.put("open", true)
            o.put("end_ms", JSONObject.NULL)
            o.put("duration_ms", nowMs - startMs)
        } else {
            o.put("open", false)
            o.put("end_ms", endMs)
            o.put("duration_ms", endMs - startMs)
        }
        o.put("bytes", bytes)
        o.put("blocks", blocks)
        if (writes > 0) {
            o.put("writes", writes)
            o.put("write_ms_avg", Math.round(writeMsTotal / writes * 10.0) / 10.0)
            o.put("write_ms_max", Math.round(writeMsMax * 10.0) / 10.0)
            o.put("slip_ms_max", Math.round(slipMsMax * 10.0) / 10.0)
        }
        if (!holdErrorMs.isNaN()) {
            o.put("hold_error_ms", Math.round(holdErrorMs * 10.0) / 10.0)
            o.put("trimmed_frames", trimmedFrames)
        }
        if (!startAgeMs.isNaN()) o.put("start_age_ms", Math.round(startAgeMs * 10.0) / 10.0)
        if (burstBytes >= 0) o.put("burst_bytes", burstBytes)
        if (!behindMsMax.isNaN()) o.put("behind_ms_max", Math.round(behindMsMax * 10.0) / 10.0)
        o.put("closed_by", if (closedBy.isEmpty()) JSONObject.NULL else closedBy)
        o.put("reason", if (reason.isEmpty()) JSONObject.NULL else reason)
        return o
    }
}

/** What the speaker showed at some moment: its transport state and status, and what its websocket said. */
class TransportNote(
    val tMs: Long,
    val state: String,
    val status: String,
    /** Whose stream the transport has: "ours", "foreign" or "" when unknown. */
    val uri: String,
    val ws: String,
    val error: String,
) {
    fun sameAs(o: TransportNote): Boolean = state == o.state && status == o.status && uri == o.uri && ws == o.ws && error == o.error

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("t_ms", tMs)
        o.put("state", state)
        o.put("status", status)
        o.put("uri", uri)
        o.put("ws", ws)
        o.put("error", if (error.isEmpty()) JSONObject.NULL else error)
        return o
    }
}

class SessionRecord(
    val token: String,
    /** "ring" (the Spotify engine) or "capture". */
    val kind: String,
    private val createdNanos: Long,
    private val createdWallMs: Long,
) {
    private val lock = Any()
    private val conns = ArrayList<ConnRecord>()
    private val notes = ArrayList<TransportNote>()

    @Volatile var playSentMs = -1L
    @Volatile var endMs = -1L
    @Volatile var endReason = ""

    /** Set when the start counted as failed: why, and what the speaker said at that moment. */
    @Volatile var failure = ""
    @Volatile var failureSonos = ""

    fun ms(nowNanos: Long): Long = (nowNanos - createdNanos) / 1_000_000L

    fun playSent(nowNanos: Long) {
        playSentMs = ms(nowNanos)
    }

    fun ended(nowNanos: Long, why: String) {
        if (endMs >= 0) return
        endMs = ms(nowNanos)
        endReason = why
    }

    fun failed(why: String, sonos: String) {
        if (failure.isEmpty()) {
            failure = why
            failureSonos = sonos
        }
    }

    fun openConnection(method: String, remote: String, userAgent: String, armed: Boolean, nowNanos: Long): ConnRecord {
        synchronized(lock) {
            val c = ConnRecord(conns.size + 1, method, remote, userAgent, armed, ms(nowNanos))
            conns.add(c)
            return c
        }
    }

    /** The connections so far, oldest first. */
    fun connections(): List<ConnRecord> = synchronized(lock) { ArrayList(conns) }

    fun closeConnection(c: ConnRecord, nowNanos: Long, by: String, why: String) {
        synchronized(lock) {
            if (c.endMs >= 0) return
            c.endMs = ms(nowNanos)
            c.closedBy = by
            c.reason = why
        }
    }

    /** True while the session runs and for [windowMs] after it ended: the speaker's state is still of interest. */
    fun wantsNotes(nowNanos: Long, windowMs: Long = AFTER_WINDOW_MS): Boolean {
        val end = endMs
        return end < 0 || ms(nowNanos) - end <= windowMs
    }

    /** A note of the speaker's state; the same state again within a few seconds is not noted twice. */
    fun noteTransport(nowNanos: Long, state: String, status: String, uri: String, ws: String, error: String) {
        val note = TransportNote(ms(nowNanos), state, status, uri, ws, error)
        synchronized(lock) {
            val last = notes.lastOrNull()
            if (last != null && last.sameAs(note) && note.tMs - last.tMs < SAME_NOTE_MS) return
            notes.add(note)
            while (notes.size > MAX_NOTES) notes.removeAt(0)
        }
    }

    fun toJson(nowNanos: Long): JSONObject {
        val now = ms(nowNanos)
        val o = JSONObject()
        o.put("token", token)
        o.put("kind", kind)
        o.put("started", SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(createdWallMs)))
        o.put("age_s", now / 1000)
        o.put("play_sent_ms", if (playSentMs < 0) JSONObject.NULL else playSentMs)
        o.put("ended_ms", if (endMs < 0) JSONObject.NULL else endMs)
        o.put("end_reason", if (endReason.isEmpty()) JSONObject.NULL else endReason)
        if (failure.isNotEmpty()) {
            val f = JSONObject()
            f.put("why", failure)
            f.put("sonos", failureSonos)
            o.put("failed", f)
        } else {
            o.put("failed", JSONObject.NULL)
        }
        val c = JSONArray()
        val n = JSONArray()
        synchronized(lock) {
            for (conn in conns) c.put(conn.toJson(now))
            for (note in notes) n.put(note.toJson())
        }
        o.put("connections", c)
        o.put("transport", n)
        return o
    }

    companion object {
        /** The speaker's state is noted for this long after a session ended. */
        const val AFTER_WINDOW_MS = 30_000L
        private const val SAME_NOTE_MS = 5_000L
        private const val MAX_NOTES = 60
    }
}

/** The last [max] sessions, newest first. */
class SessionHistory(private val max: Int = 10) {
    private val records = ArrayList<SessionRecord>()

    @Synchronized
    fun begin(token: String, kind: String, nowNanos: Long, wallMs: Long): SessionRecord {
        val r = SessionRecord(token, kind, nowNanos, wallMs)
        records.add(0, r)
        while (records.size > max) records.removeAt(records.size - 1)
        return r
    }

    /** The newest session, if any. */
    @Synchronized
    fun latest(): SessionRecord? = records.firstOrNull()

    @Synchronized
    fun all(): List<SessionRecord> = ArrayList(records)

    fun toJson(nowNanos: Long): JSONArray {
        val a = JSONArray()
        for (r in all()) a.put(r.toJson(nowNanos))
        return a
    }
}
