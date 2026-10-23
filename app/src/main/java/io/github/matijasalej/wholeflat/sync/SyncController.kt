package io.github.matijasalej.wholeflat.sync

/**
 * Keeps the kitchen in step with the living room.
 *
 * The living room is never touched. The Sonos reports its play position (the websocket's
 * `positionMillis`, about 0.156 s ahead of the sound); from it the controller works out which ring
 * frame the kitchen plays now and when the living room played (or will play) that same frame. The
 * difference, minus the offset the user calibrated, is the error: positive means the kitchen is late.
 *
 * Small errors are removed by **trims**: single frames dropped from, or repeated in, the stream the
 * Sonos is fed (inaudible, and at most one per 10 ms). Big errors, or a stream that has lost its
 * place, ask for a **re-join**: only the Sonos stream is restarted at the right frame.
 *
 * A trim shows in the readings only after the stream has travelled through the Sonos's buffer, so the
 * controller always looks at the error *minus what is still on its way*, and waits for fresh readings
 * after it has asked for a trim instead of asking twice.
 */
class SyncController(private val params: Params = Params()) {

    class Params(
        /** The kitchen is re-joined when the error is bigger than this ... */
        val rejoinThresholdMs: Double = 40.0,
        /** ... in this many readings in a row. */
        val rejoinReadings: Int = 3,
        /** Errors up to this are left alone: the readings are only good to a few ms. */
        val deadbandMs: Double = 4.0,
        /** A trim removes this part of the error; the rest is left for the next round, so that the
         *  noise of the readings is not amplified (0.5 gave the quietest result in simulation). */
        val gain: Double = 0.5,
        /** Readings the median is taken over (and the readings needed before the controller acts). */
        val window: Int = 11,
        /** Most frames that may be on their way at any time (50 ms). */
        val maxPendingFrames: Int = 2205,
        /** Seconds of history the drift is fitted over (the fit is good to a few ppm after 5 min). */
        val driftWindowSeconds: Double = 300.0,
        val rate: Int = TrackTimeline.RATE,
    )

    /** What the controller needs to know about the two rooms. */
    interface View {
        /** The ring frame at position `streamFrame` of the Sonos stream; null if that is not known. */
        fun ringOfStream(streamFrame: Double): Double?

        /** When the living room presents (or presented) ring frame `ringFrame`; null if unknown. */
        fun livingHalNanos(ringFrame: Double): Long?

        /** Net frames dropped (+) or inserted (-) in the stream up to this position. */
        fun netDropsAt(streamFrame: Double): Double

        /** Trims on their way: applied beyond `streamFrame`, plus those not applied yet. */
        fun pendingTrimFrames(streamFrame: Double): Long
    }

    sealed class Decision {
        object Hold : Decision()

        /** Drop this many frames from the stream (negative: repeat that many). */
        class Trim(val frames: Int) : Decision()

        class Rejoin(val reason: String) : Decision()
    }

    var offsetMs = 0.0
    var deltaMs = DEFAULT_DELTA_MS

    /** False while re-joining is not wanted (it just happened): big errors are then trimmed. */
    @Volatile
    var allowRejoin = true

    /** The last reading's error, after the pending trims (ms, positive: kitchen late). */
    @Volatile
    var rawErrorMs = Double.NaN
        private set

    /** The median over the window (ms). */
    @Volatile
    var errorMs = Double.NaN
        private set

    /** The drift of the kitchen against the living room, positive: the Sonos plays slower. */
    @Volatile
    var driftPpm = Double.NaN
        private set

    @Volatile
    var readings = 0
        private set

    @Volatile
    var trimmedFrames = 0L
        private set

    @Volatile
    var rejoins = 0
        private set

    private val adjusted = ArrayList<Double>()
    private var sinceAction = 0
    private var overThreshold = 0
    private val driftT = ArrayList<Double>()
    private val driftU = ArrayList<Double>()

    @Synchronized
    fun reset() {
        adjusted.clear()
        driftT.clear()
        driftU.clear()
        sinceAction = 0
        overThreshold = 0
        rawErrorMs = Double.NaN
        errorMs = Double.NaN
        driftPpm = Double.NaN
        readings = 0
    }

    /** One reading of the Sonos: at `tNanos` its position was `positionMs`. */
    @Synchronized
    fun onReading(tNanos: Long, positionMs: Long, view: View): Decision {
        val heard = (positionMs - deltaMs) / 1000.0 * params.rate
        val ring = view.ringOfStream(heard) ?: return Decision.Hold
        val living = view.livingHalNanos(ring) ?: return Decision.Hold
        val errorNow = (tNanos - living) / 1e6 - offsetMs
        readings++
        val pendingMs = view.pendingTrimFrames(heard) * 1000.0 / params.rate
        val remaining = errorNow - pendingMs
        rawErrorMs = remaining
        adjusted.add(remaining)
        while (adjusted.size > params.window) adjusted.removeAt(0)
        sinceAction++
        recordDrift(tNanos, errorNow + view.netDropsAt(heard) * 1000.0 / params.rate)

        // The error is far off, reading after reading: trims would take far too long (they move at most
        // 1 frame per 10 ms), the stream has lost its place.
        overThreshold = if (Math.abs(remaining) > params.rejoinThresholdMs) overThreshold + 1 else 0
        if (overThreshold >= params.rejoinReadings && allowRejoin) {
            return rejoin("error ${fmt(remaining)} ms")
        }
        if (sinceAction < params.window) return Decision.Hold

        val median = median(adjusted)
        errorMs = median
        if (Math.abs(median) <= params.deadbandMs) return Decision.Hold

        val pending = view.pendingTrimFrames(heard)
        var frames = Math.round(median * params.gain * params.rate / 1000.0).toInt()
        // never more than maxPendingFrames on their way, in either direction
        val room = params.maxPendingFrames
        frames = frames.coerceIn(-room - pending.toInt(), room - pending.toInt())
        if (frames == 0) return Decision.Hold
        trimmedFrames += Math.abs(frames)
        // fresh readings, taken after this trim was counted as pending, before the next decision
        adjusted.clear()
        sinceAction = 0
        return Decision.Trim(frames)
    }

    private fun rejoin(reason: String): Decision {
        rejoins++
        return Decision.Rejoin(reason)
    }

    private fun recordDrift(tNanos: Long, uncorrectedMs: Double) {
        val t = tNanos / 1e9
        driftT.add(t)
        driftU.add(uncorrectedMs)
        while (driftT.size > 1 && t - driftT[0] > params.driftWindowSeconds) {
            driftT.removeAt(0)
            driftU.removeAt(0)
        }
        if (driftT.size < MIN_DRIFT_READINGS || driftT[driftT.size - 1] - driftT[0] < MIN_DRIFT_SECONDS) return
        val n = driftT.size
        val meanT = driftT.sum() / n
        val meanU = driftU.sum() / n
        var num = 0.0
        var den = 0.0
        for (i in 0 until n) {
            num += (driftT[i] - meanT) * (driftU[i] - meanU)
            den += (driftT[i] - meanT) * (driftT[i] - meanT)
        }
        if (den > 0) driftPpm = num / den * 1000.0
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    }

    private fun fmt(v: Double): String = String.format(java.util.Locale.US, "%.1f", v)

    companion object {
        /** The Sonos's position runs this far ahead of the sound (measured: 0.156 s, sd 4 ms). */
        const val DEFAULT_DELTA_MS = 156.0
        private const val MIN_DRIFT_READINGS = 40
        private const val MIN_DRIFT_SECONDS = 10.0
    }
}
