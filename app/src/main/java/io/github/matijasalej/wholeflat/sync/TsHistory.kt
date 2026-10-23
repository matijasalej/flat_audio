package io.github.matijasalej.wholeflat.sync

import java.util.ArrayDeque

/**
 * The last seconds of the live transport stream, as it was written: one writer (the muxer thread) appends units of whole packets, any
 * number of readers (the connections of the TV) read from a position of their own and are never held up by one another. A unit that
 * [Unit.key] begins with the PAT and PMT of an IDR picture: a place where a player can start.
 *
 * A new connection starts at such a place: the newest one that is at least `lead` old ([startFor], which is how the stream was
 * started in the device test that the TV took well), or the first one made after the connection ([keyAfter], for a start at "now").
 * A reader that falls behind by more than the ring holds is told so ([Read.Lapped]) and is not given a stream with a hole in it.
 *
 * Positions are byte counts since the start of the stream. Thread safe. Plain Kotlin.
 */
class TsHistory(private val maxBytes: Long = 16L shl 20, private val maxAgeNanos: Long = 12_000_000_000L) {
    private class Entry(val pos: Long, val bytes: ByteArray, val nanos: Long, val key: Boolean)

    /** What a read gives. */
    sealed class Read {
        /** Bytes from the position asked for (whole units), the position after them, and when the newest unit among them was appended (System.nanoTime). */
        class Data(val bytes: ByteArray, val next: Long, val lastNanos: Long) : Read()

        /** Nothing new within the time asked for. */
        object Timeout : Read()

        /** The position is older than what the ring holds. */
        object Lapped : Read()

        /** The stream has ended and everything was read. */
        object Closed : Read()
    }

    private val lock = Object()
    private val entries = ArrayDeque<Entry>()
    private var held = 0L
    private var end = 0L
    private var closed = false
    private var newestKeyPos = -1L

    /** The position after the newest byte. */
    val endPos: Long get() = synchronized(lock) { end }

    /** The position of the oldest byte that is held. */
    val oldestPos: Long get() = synchronized(lock) { entries.peekFirst()?.pos ?: end }

    /** Bytes held now, and the key places among the units. */
    val heldBytes: Long get() = synchronized(lock) { held }
    val keyUnits: Int get() = synchronized(lock) { entries.count { it.key } }

    /** Appends a unit made at [nanos] (System.nanoTime); [key]: it begins with the PAT and PMT of an IDR picture. */
    fun append(bytes: ByteArray, nanos: Long, key: Boolean) {
        if (bytes.isEmpty()) return
        synchronized(lock) {
            if (key) newestKeyPos = end
            entries.addLast(Entry(end, bytes, nanos, key))
            end += bytes.size
            held += bytes.size
            trim(nanos)
            lock.notifyAll()
        }
    }

    /** What is older than the ring keeps goes, but never the newest key place and what follows it. */
    private fun trim(nowNanos: Long) {
        val newestKey = newestKeyPos
        while (entries.size > 1) {
            val first = entries.peekFirst()!!
            if (newestKey >= 0 && first.pos >= newestKey) return
            if (held <= maxBytes && nowNanos - first.nanos <= maxAgeNanos) return
            entries.removeFirst()
            held -= first.bytes.size
        }
    }

    /** The stream ends: readers get what is left and then [Read.Closed]. */
    fun close() {
        synchronized(lock) {
            closed = true
            lock.notifyAll()
        }
    }

    /**
     * Where a connection made at [nowNanos] starts: the newest key place that is at least [leadNanos] old, else the oldest key place
     * that is held (the stream is younger than the lead); null while there is no key place.
     */
    fun startFor(nowNanos: Long, leadNanos: Long): Long? {
        synchronized(lock) {
            var oldestKey: Long? = null
            var chosen: Long? = null
            for (e in entries) {
                if (!e.key) continue
                if (oldestKey == null) oldestKey = e.pos
                if (e.nanos <= nowNanos - leadNanos) chosen = e.pos
            }
            return chosen ?: oldestKey
        }
    }

    /** The first key place made at or after [nanos], waiting up to [timeoutMs] for it; null if none came. */
    fun keyAfter(nanos: Long, timeoutMs: Long): Long? {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        synchronized(lock) {
            while (true) {
                for (e in entries) if (e.key && e.nanos >= nanos) return e.pos
                if (closed) return null
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return null
                lock.wait(maxOf(1L, remaining / 1_000_000L))
            }
        }
    }

    /** How old the oldest key place that is held is at [nowNanos] (ns), null while there is none. */
    fun oldestKeyAgeNanos(nowNanos: Long): Long? {
        synchronized(lock) {
            for (e in entries) if (e.key) return nowNanos - e.nanos
            return null
        }
    }

    /** The newest key place, null if none is held. */
    fun newestKey(): Long? {
        synchronized(lock) {
            var pos: Long? = null
            for (e in entries) if (e.key) pos = e.pos
            return pos
        }
    }

    /** The time at which the unit that begins at [pos] was appended, null if it is not held. */
    fun nanosAt(pos: Long): Long? {
        synchronized(lock) {
            for (e in entries) if (e.pos == pos) return e.nanos
            return null
        }
    }

    /**
     * Bytes from [pos] on: whole units up to about [maxBytes] (one unit is always given whole), waiting up to [timeoutMs] for the first
     * one to be appended.
     */
    fun read(pos: Long, maxBytes: Int, timeoutMs: Long): Read {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        synchronized(lock) {
            while (true) {
                val oldest = entries.peekFirst()
                if (oldest != null && pos < oldest.pos) return Read.Lapped
                if (pos < end) return gather(pos, maxBytes)
                if (closed) return Read.Closed
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return Read.Timeout
                lock.wait(maxOf(1L, remaining / 1_000_000L))
            }
        }
    }

    private fun gather(pos: Long, maxBytes: Int): Read {
        var total = 0
        val parts = ArrayList<ByteArray>()
        var next = pos
        var last = 0L
        for (e in entries) {
            val entryEnd = e.pos + e.bytes.size
            if (entryEnd <= pos) continue
            val skip = if (e.pos < pos) (pos - e.pos).toInt() else 0
            val take = e.bytes.size - skip
            if (parts.isNotEmpty() && total + take > maxBytes) break
            parts.add(if (skip == 0) e.bytes else e.bytes.copyOfRange(skip, e.bytes.size))
            total += take
            next = entryEnd
            last = e.nanos
            if (total >= maxBytes) break
        }
        val out = if (parts.size == 1) parts[0] else ByteArray(total).also {
            var at = 0
            for (p in parts) {
                System.arraycopy(p, 0, it, at, p.size)
                at += p.size
            }
        }
        return Read.Data(out, next, last)
    }
}
