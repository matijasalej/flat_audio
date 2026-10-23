package io.github.matijasalej.wholeflat.sync

import java.util.PriorityQueue

/** Things that wait for the moment they are due and come out in the order of that moment (equal moments in the order they came). Thread safe. */
class DueQueue<T> {
    private class Entry<T>(val due: Long, val seq: Long, val item: T)

    private val queue = PriorityQueue<Entry<T>>(compareBy<Entry<T>>({ it.due }, { it.seq }))
    private var seq = 0L

    @Synchronized
    fun add(due: Long, item: T) {
        queue.add(Entry(due, seq++, item))
    }

    /** The next item if it is due at [nowNanos], with its due time; null if none is. */
    @Synchronized
    fun popDue(nowNanos: Long): Pair<Long, T>? {
        val e = queue.peek() ?: return null
        if (e.due > nowNanos) return null
        queue.poll()
        return Pair(e.due, e.item)
    }

    /** When the next item is due, null if the queue is empty. */
    @Synchronized
    fun nextDue(): Long? = queue.peek()?.due

    @get:Synchronized
    val size: Int get() = queue.size

    @Synchronized
    fun clear() = queue.clear()
}

/**
 * The last step before the network: audio frames and video access units are queued with the moment each is to be written (the capture
 * time of its picture or sound plus the hold of the stream), and written in that order, through the muxer, into the ring that the
 * connections of the TV read from. What is due but late is written at once; how late is counted. Between the items the muxer is asked
 * for what is due without data (the PCR packets and the tables), so that the clock of the stream never stands still. Thread safe as far as
 * [submitAudio] and [submitVideo] may be called while another thread calls [step]. Plain Kotlin.
 */
class TsWriter(private val mux: TsMuxer, private val history: TsHistory) {
    private sealed class Item {
        class Video(val au: ByteArray, val pts90: Long, val key: Boolean) : Item()
        class Audio(val adts: ByteArray, val pts90: Long) : Item()
    }

    private val queue = DueQueue<Item>()

    /** How late the items were when they were written (ms): the latest, and the sums for averages (read them with [takeStats]). */
    class Stats(
        val audioLateAvgMs: Double,
        val audioLateMaxMs: Double,
        val videoLateAvgMs: Double,
        val videoLateMaxMs: Double,
        val audioCount: Int,
        val videoCount: Int,
        val queued: Int,
    )

    private var audioLateSum = 0.0
    private var audioLateMax = 0.0
    private var audioCount = 0
    private var videoLateSum = 0.0
    private var videoLateMax = 0.0
    private var videoCount = 0

    @Volatile
    var keyUnits = 0L
        private set

    @Volatile
    var bytes = 0L
        private set

    fun submitVideo(dueNanos: Long, au: ByteArray, pts90: Long, key: Boolean) = queue.add(dueNanos, Item.Video(au, pts90, key))

    fun submitAudio(dueNanos: Long, adts: ByteArray, pts90: Long) = queue.add(dueNanos, Item.Audio(adts, pts90))

    val queued: Int get() = queue.size

    /** Writes everything that is due at [nowNanos]; returns the time to come back at (the next item's, but never later than [maxWaitNanos] from now). */
    fun step(nowNanos: Long, maxWaitNanos: Long = 5_000_000L): Long {
        while (true) {
            val (due, item) = queue.popDue(nowNanos) ?: break
            val lateMs = maxOf(0L, nowNanos - due) / 1e6
            val out = when (item) {
                is Item.Video -> mux.video(item.au, item.pts90, item.key, nowNanos).also { noteLate(false, lateMs) }
                is Item.Audio -> mux.audio(item.adts, item.pts90, nowNanos).also { noteLate(true, lateMs) }
            }
            put(out, nowNanos)
        }
        mux.idle(nowNanos)?.let { put(it, nowNanos) }
        val next = queue.nextDue()
        return if (next == null) nowNanos + maxWaitNanos else minOf(next, nowNanos + maxWaitNanos)
    }

    private fun put(out: TsMuxer.Out, nowNanos: Long) {
        history.append(out.bytes, nowNanos, out.keyStart)
        bytes += out.bytes.size
        if (out.keyStart) keyUnits++
    }

    @Synchronized
    private fun noteLate(audio: Boolean, lateMs: Double) {
        if (audio) {
            audioLateSum += lateMs
            audioCount++
            if (lateMs > audioLateMax) audioLateMax = lateMs
        } else {
            videoLateSum += lateMs
            videoCount++
            if (lateMs > videoLateMax) videoLateMax = lateMs
        }
    }

    /** The lateness statistics since the last call (and a fresh start of them). */
    @Synchronized
    fun takeStats(): Stats {
        val s = Stats(
            if (audioCount == 0) 0.0 else audioLateSum / audioCount, audioLateMax,
            if (videoCount == 0) 0.0 else videoLateSum / videoCount, videoLateMax,
            audioCount, videoCount, queue.size,
        )
        audioLateSum = 0.0
        audioLateMax = 0.0
        audioCount = 0
        videoLateSum = 0.0
        videoLateMax = 0.0
        videoCount = 0
        return s
    }
}
