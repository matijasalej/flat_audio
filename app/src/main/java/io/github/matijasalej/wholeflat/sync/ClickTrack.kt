package io.github.matijasalej.wholeflat.sync

/**
 * The click track for the calibration of the two rooms in Synced mode, put in place of the music in both streams from the capture
 * frame numbers (so a click is at the same capture frame in both rooms): click k begins at frame k * rate, one a second, 5 ms of a
 * sine with a Hann window at 0.5 full scale; every tenth click (k % 10 == 0) at twice the pitch, so that the index can be told from a
 * recording. The TV's stream gets 2 kHz (4 kHz), the kitchen's 3 kHz (6 kHz), as the click track of `/calibrate`, so that the two
 * rooms can be told apart in one recording and their delay measured. Both channels alike. Plain Kotlin.
 */
object ClickTrack {
    const val TV_HZ = 2_000
    const val KITCHEN_HZ = 3_000
    const val MARK_EVERY = 10
    private const val CLICK_MS = 5
    private const val AMPLITUDE = 0.5

    private val templates = HashMap<Long, ShortArray>()

    /** The length of a click in frames at [rate]. */
    fun clickFrames(rate: Int): Int = rate * CLICK_MS / 1000

    @Synchronized
    private fun template(rate: Int, hz: Int): ShortArray = templates.getOrPut(rate.toLong() * 1_000_000L + hz) {
        val n = clickFrames(rate)
        ShortArray(n) { m ->
            val window = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * (m + 0.5) / n)
            val v = AMPLITUDE * window * Math.sin(2.0 * Math.PI * hz * m / rate)
            Math.round(v * 32767.0).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    /** The click track at [hz] for the frames [first, first + frames) as stereo (interleaved) into [out]. */
    fun fill(out: ShortArray, frames: Int, first: Long, rate: Int, hz: Int) {
        val normal = template(rate, hz)
        val mark = template(rate, hz * 2)
        val len = normal.size
        for (i in 0 until frames) {
            val f = first + i
            val k = Math.floorDiv(f, rate.toLong())
            val m = (f - k * rate).toInt()
            val s: Short = if (m < len) (if (k % MARK_EVERY == 0L) mark[m] else normal[m]) else 0
            out[i * 2] = s
            out[i * 2 + 1] = s
        }
    }
}

/**
 * The frames of [inner], and while [active] says so the click track in their place (the same frames are read, so a stream is held
 * up exactly as before; only what they say is replaced).
 */
class ClickSource(private val inner: FrameSource, private val rate: Int, private val hz: Int, private val active: () -> Boolean) : FrameSource {
    override fun read(cursor: Long, out: ShortArray, maxFrames: Int, timeoutMs: Int): Int {
        val n = inner.read(cursor, out, maxFrames, timeoutMs)
        if (n > 0 && active()) ClickTrack.fill(out, n, cursor, rate, hz)
        return n
    }
}

/** Whether the click track is on, for the whole app (the streams ask at every read). */
object CalibrationClicks {
    @Volatile
    private var untilNanos = 0L

    @Volatile
    var startedNanos = 0L
        private set

    /** On for [seconds] from now. */
    fun start(seconds: Int, nowNanos: Long = System.nanoTime()) {
        startedNanos = nowNanos
        untilNanos = nowNanos + seconds * 1_000_000_000L
    }

    fun stop() {
        untilNanos = 0L
    }

    fun active(nowNanos: Long = System.nanoTime()): Boolean = nowNanos < untilNanos

    /** Seconds left (0 when off). */
    fun secondsLeft(nowNanos: Long = System.nanoTime()): Long = maxOf(0L, (untilNanos - nowNanos) / 1_000_000_000L)
}
