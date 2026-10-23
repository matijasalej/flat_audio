package io.github.matijasalej.wholeflat.sync

/**
 * The common delay of the two rooms in Synced mode, and what follows from it.
 *
 * Every frame is to sound at capture(f) + D in both rooms. The Sonos is held there with its websocket feedback (the lag it is steered
 * to is D). The TV gives no feedback: frame f is written to it at capture(f) + D - X, where X is the TV's own delay from our write to
 * its sound (the setting `tv_latency_ms`, calibrated on the device). A frame cannot be written before it has been captured and has
 * come through the capture pipeline (P, about 80 ms from the capture point to this app), so the TV path alone needs P + X, and
 *
 *     D = max(X + P, Sonos target) + margin
 *
 * with the Sonos target the lag it is kept to in Lyrics mode (the target setting, not below the floor). With the defaults (X 300 ms,
 * P 80 ms, target 322 ms, margin 30 ms) D is 410 ms and the TV stream is held 110 ms behind the capture, 30 ms behind its arrival.
 *
 * Plain Kotlin.
 */
object SyncedTiming {
    /** The room that is slower decides, and a margin keeps the TV's frames from being due before they exist. */
    const val MARGIN_MS = 30.0

    /** The pipeline to count with when it has not been measured (ms). */
    const val DEFAULT_PIPELINE_MS = 80.0

    /**
     * The usual delay from our write to the sound of the TV (ms) for the stream format, when `tv_latency_ms` says "auto". The audio-only
     * L16 stream: the sound followed the data by 0.1 to 0.4 s. The video stream (MPEG-TS): in the device test with a stream that was
     * started a second behind live and paced to live, the Sonos had to be held 0.6 to 0.7 s behind to match the TV, which puts the TV about
     * 0.95 s behind the writes. To be calibrated with the click track (`/tv/clicks`).
     */
    const val AUTO_L16_LATENCY_MS = 300
    const val AUTO_TS_LATENCY_MS = 950

    /**
     * What the stream itself needs between the capture of a frame and its write (ms), on top of the pipeline: the audio is taken a block of
     * 23 ms at a time and has to go through the AAC encoder, the picture through the H.264 encoder. The TS stream counts it into the
     * common delay so that its frames are never due before they exist.
     */
    const val ENCODE_TS_MS = 100.0

    /** The part of the stream's own delay that the common delay has to make room for (ms), by format ("ts" or "l16"). */
    fun encodeMs(format: String): Double = if (format == "ts") ENCODE_TS_MS else 0.0

    /** X for [format] ("ts" or "l16") from the setting [settingMs] (-1: auto). */
    fun tvLatencyMs(settingMs: Int, format: String): Int =
        if (settingMs >= 0) settingMs else if (format == "ts") AUTO_TS_LATENCY_MS else AUTO_L16_LATENCY_MS

    /** D, the delay of both rooms after the capture (ms). [pipelineMs] NaN: the default is used. */
    fun delayMs(tvLatencyMs: Double, pipelineMs: Double, sonosTargetMs: Double, marginMs: Double = MARGIN_MS): Double {
        val pipeline = if (pipelineMs.isNaN() || pipelineMs < 0) DEFAULT_PIPELINE_MS else pipelineMs
        return maxOf(tvLatencyMs + pipeline, sonosTargetMs) + marginMs
    }

    /** How long after its capture a frame is written to the TV (ms): D less the TV's own delay. */
    fun tvHoldMs(delayMs: Double, tvLatencyMs: Double): Double = delayMs - tvLatencyMs

    /**
     * The first source frame of a TV stream whose connection is made at [nowNanos]: the frame captured one hold ago, so that it is
     * due now. [captureFrameAt] maps a time to the frame captured then (the capture clock), null if it cannot say; [fallbackFrame] is
     * the newest frame in the buffer and [rate] the sample rate, for when the clock has no anchor yet. The result is never before
     * [oldestFrame] nor after the newest frame.
     */
    fun startFrame(
        nowNanos: Long,
        holdNanos: Long,
        captureFrameAt: (Long) -> Long?,
        fallbackFrame: Long,
        pipelineMs: Double,
        rate: Int,
        oldestFrame: Long,
    ): Long {
        val byClock = captureFrameAt(nowNanos - holdNanos)
        val frame = byClock
            ?: (fallbackFrame - Math.round(maxOf(0.0, holdNanos / 1e6 - (if (pipelineMs.isNaN()) DEFAULT_PIPELINE_MS else pipelineMs)) * rate / 1000.0))
        return frame.coerceIn(oldestFrame, maxOf(oldestFrame, fallbackFrame))
    }
}
