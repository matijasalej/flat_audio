package io.github.matijasalej.wholeflat.sync

/**
 * The clock of a live transport stream: it says which capture frame goes with a capture time, and the other way round. It runs on
 * the plain count of frames from an anchor (frame [f0] was captured at [cap0Nanos]) and is slowly pulled towards what the capture
 * clock says ([update]), at most [maxSlewPpm] parts per million, so that it follows the sample clock of the capture over hours (the
 * Sonos is steered against that clock) without a step in the PCR or the timestamps that a player could take for a discontinuity.
 * Thread safe. Plain Kotlin.
 */
class SlewClock(private val rate: Int, private val f0: Long, private val cap0Nanos: Long, private val maxSlewPpm: Double = 200.0) {
    private var adjust = 0.0 // frames
    private var lastUpdateNanos = cap0Nanos

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
        val maxStep = maxSlewPpm * 1e-6 * dt * rate
        adjust += (wanted - adjust).coerceIn(-maxStep, maxStep)
    }

    /** How far the clock is from the plain count now (ms); positive: the capture clock runs faster than the system clock. */
    @get:Synchronized
    val adjustMs: Double get() = adjust * 1000.0 / rate
}
