package io.github.matijasalej.wholeflat.sync

/**
 * The clock of a live transport stream: it says which capture frame goes with a capture time, and the other way round. It runs on
 * the plain count of frames from an anchor (frame [f0] was captured at [cap0Nanos]) and is slowly pulled towards what the capture
 * clock says ([update]), at most [maxSlewPpm] parts per million, so that it follows the sample clock of the capture over hours (the
 * Sonos is steered against that clock) without a step in the PCR or the timestamps that a player could take for a discontinuity.
 * Thread safe. Plain Kotlin.
 */
class SlewClock(
    private val rate: Int,
    private val f0: Long,
    private val cap0Nanos: Long,
    private val maxSlewPpm: Double = 200.0,
    /**
     * A capture clock that is further than this from the clock (ms) has stepped (the timestamps of the capture step by a period of the audio
     * path or two, 46.8 ms once, without anything being heard), and is not followed: the TV's stream and the kitchen, which are counted from this
     * clock, stay where they are. The default follows everything.
     */
    private val stepMs: Double = Double.MAX_VALUE,
) : FrameTimes {
    private var adjust = 0.0 // frames
    private var lastUpdateNanos = cap0Nanos

    /** Updates that were not followed because the capture clock was a step away. */
    @Volatile
    var stepsIgnored = 0L
        private set

    override fun nanosOfFrame(frame: Long): Long? = nanosOf(frame)

    private fun plain(captureNanos: Long): Double = f0 + (captureNanos - cap0Nanos).toDouble() * rate / 1e9

    /** The frame (fractional) that was captured at [captureNanos]. */
    @Synchronized
    fun frameAt(captureNanos: Long): Double = plain(captureNanos) + adjust

    /** When frame [frame] was captured (System.nanoTime). */
    @Synchronized
    fun nanosOf(frame: Long): Long = cap0Nanos + Math.round((frame - f0 - adjust) * 1e9 / rate)

    /** The capture clock says that at [captureNanos] frame [modelFrame] was captured; the clock is pulled towards that, slowly. */
    @Synchronized
    fun update(captureNanos: Long, modelFrame: Double?) {
        if (modelFrame == null) return
        val dt = (captureNanos - lastUpdateNanos) / 1e9
        lastUpdateNanos = captureNanos
        if (dt <= 0) return
        val wanted = modelFrame - plain(captureNanos)
        if (Math.abs(wanted - adjust) * 1000.0 / rate > stepMs) {
            stepsIgnored++
            return
        }
        val maxStep = maxSlewPpm * 1e-6 * dt * rate
        adjust += (wanted - adjust).coerceIn(-maxStep, maxStep)
    }

    /** How far the clock is from the plain count now (ms); positive: the capture clock runs faster than the system clock. */
    @get:Synchronized
    val adjustMs: Double get() = adjust * 1000.0 / rate
}
