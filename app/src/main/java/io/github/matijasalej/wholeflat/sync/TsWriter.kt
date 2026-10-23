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
 * time of its picture or sound plus the hold of the stream); when that moment comes it goes into the muxer, which sends it at the rate of
 * the stream, and the slots that have come by ([slotOf] says how many) are emitted into the ring that the connections of the TV read from.
 * The stream is constant in rate: what is not picture or sound is padding. What is due but late is queued at once; how late is counted.
 * **Nothing here waits for video**: the slots go out on the clock whether a picture has come or not, audio goes before video in the
 * slots, and a picture that comes so late that it could not reach the TV before its time is dropped by the [gate] (with what depends on
 * it, up to the next key picture), not sent late. Thread safe as far as [submitAudio] and [submitVideo] may be called while another thread
 * calls [step]. Plain Kotlin.
 */
class TsWriter(private val mux: TsMuxer, private val history: TsHistory, private val slotOf: (Long) -> Long) {
    private sealed class Item {
        class Video(val au: ByteArray, val pts90: Long, val key: Boolean) : Item()
        class Audio(val adts: ByteArray, val pts90: Long) : Item()
    }

    private val queue = DueQueue<Item>()

    /** How late the items were when they were queued for sending (ms): averages and the latest, since the last [takeStats]. */
    class Stats(
        val audioLateAvgMs: Double,
        val audioLateMaxMs: Double,
        val videoLateAvgMs: Double,
        val videoLateMaxMs: Double,
        val audioCount: Int,
        val videoCount: Int,
        val queued: Int,
        /** Pictures the gate did not let into the stream in the period. */
        val videoDropped: Int = 0,
        /** The most slots one step emitted in the period (a step that comes late emits what passed meanwhile, 6 per ms at 9 Mbit/s). */
        val maxStepSlots: Long = 0,
    )

    private var audioLateSum = 0.0
    private var audioLateMax = 0.0
    private var audioCount = 0
    private var videoLateSum = 0.0
    private var videoLateMax = 0.0
    private var videoCount = 0
    private var videoDroppedPeriod = 0
    private var maxStepSlotsPeriod = 0L

    /** Lets a late picture through or not (see [LateVideoGate]); null: every picture goes into the stream. */
    @Volatile
    var gate: LateVideoGate? = null

    @Volatile
    var keyUnits = 0L
        private set

    /** Bytes emitted into the ring (all of the stream, padding included). */
    @Volatile
    var bytes = 0L
        private set

    private var lastVideoDue = Long.MIN_VALUE

    /**
     * A picture is never due before the one it follows (the encoder gives them in order, and the next refers to the last): a due time that
     * would put it ahead is the last one's, and the queue keeps equal times in the order they came.
     */
    @Synchronized
    fun submitVideo(dueNanos: Long, au: ByteArray, pts90: Long, key: Boolean) {
        val d = if (dueNanos < lastVideoDue) lastVideoDue else dueNanos
        lastVideoDue = d
        queue.add(d, Item.Video(au, pts90, key))
    }

    /** A picture the gate did not let into the stream. */
    class Drop(val key: Boolean, val verdict: LateVideoGate.Verdict, val lateMs: Double, val bytes: Int, val pts90: Long)

    private val drops = java.util.ArrayDeque<Drop>()

    /** The pictures dropped since the last call (at most the last 64), for the log. */
    fun takeDrops(): List<Drop> = synchronized(drops) {
        val l = drops.toList()
        drops.clear()
        l
    }

    fun submitAudio(dueNanos: Long, adts: ByteArray, pts90: Long) = queue.add(dueNanos, Item.Audio(adts, pts90))

    val queued: Int get() = queue.size

    /** Packets that wait in the muxer for their slot, and how many of them are video. */
    val packetsWaiting: Int get() = synchronized(mux) { mux.queuedPackets }
    val videoPacketsWaiting: Int get() = synchronized(mux) { mux.queuedVideoPackets }

    /** Queues what is due at [nowNanos] and emits the slots that have come by; returns the time to come back at (never later than [maxWaitNanos] from now). */
    fun step(nowNanos: Long, maxWaitNanos: Long = 5_000_000L): Long {
        synchronized(mux) {
            while (true) {
                val (due, item) = queue.popDue(nowNanos) ?: break
                val lateMs = maxOf(0L, nowNanos - due) / 1e6
                when (item) {
                    is Item.Video -> {
                        val g = gate
                        val verdict = g?.judge(item.key, nowNanos - due, item.au.size, mux.queuedVideoPackets * TsMuxer.PACKET.toLong())
                        if (verdict == null || verdict == LateVideoGate.Verdict.SEND) {
                            mux.queueVideo(item.au, item.pts90, item.key)
                            noteLate(false, lateMs)
                        } else {
                            noteDropped()
                            synchronized(drops) {
                                drops.addLast(Drop(item.key, verdict, lateMs, item.au.size, item.pts90))
                                while (drops.size > 64) drops.removeFirst()
                            }
                        }
                    }
                    is Item.Audio -> {
                        mux.queueAudio(item.adts, item.pts90)
                        noteLate(true, lateMs)
                    }
                }
            }
            val slot = slotOf(nowNanos)
            val firstSlot = mux.nextSlotToEmit
            while (mux.nextSlotToEmit <= slot) {
                val upTo = minOf(slot, mux.nextSlotToEmit + CHUNK_SLOTS - 1)
                put(mux.emit(upTo), nowNanos)
            }
            noteStep(mux.nextSlotToEmit - firstSlot)
        }
        val next = queue.nextDue()
        return if (next == null) nowNanos + maxWaitNanos else minOf(next, nowNanos + maxWaitNanos)
    }

    /** The emitted packets go into the ring in pieces that begin where a place to start begins. */
    private fun put(e: TsMuxer.Emitted, nowNanos: Long) {
        if (e.bytes.isEmpty()) return
        var from = 0
        for (k in e.keyOffsets) {
            if (k > from) history.append(e.bytes.copyOfRange(from, k), nowNanos, false)
            from = k
            keyUnits++
            // a place to start: from here to the next one (or the end)
            val next = e.keyOffsets.firstOrNull { it > k } ?: e.bytes.size
            history.append(e.bytes.copyOfRange(k, next), nowNanos, true)
            from = next
        }
        if (from < e.bytes.size) history.append(if (from == 0) e.bytes else e.bytes.copyOfRange(from, e.bytes.size), nowNanos, false)
        bytes += e.bytes.size
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

    @Synchronized
    private fun noteDropped() {
        videoDroppedPeriod++
    }

    @Synchronized
    private fun noteStep(slots: Long) {
        if (slots > maxStepSlotsPeriod) maxStepSlotsPeriod = slots
    }

    /** The lateness statistics since the last call (and a fresh start of them). */
    @Synchronized
    fun takeStats(): Stats {
        val s = Stats(
            if (audioCount == 0) 0.0 else audioLateSum / audioCount, audioLateMax,
            if (videoCount == 0) 0.0 else videoLateSum / videoCount, videoLateMax,
            audioCount, videoCount, queue.size, videoDroppedPeriod, maxStepSlotsPeriod,
        )
        videoDroppedPeriod = 0
        maxStepSlotsPeriod = 0L
        audioLateSum = 0.0
        audioLateMax = 0.0
        audioCount = 0
        videoLateSum = 0.0
        videoLateMax = 0.0
        videoCount = 0
        return s
    }

    private companion object {
        /** One call of the muxer gives at most this many slots (about 1 s at 9 Mbit/s). */
        const val CHUNK_SLOTS = 6_000L
    }
}
