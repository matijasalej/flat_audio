package io.github.matijasalej.wholeflat.sync

/**
 * The common delay of the two rooms in Synced mode, and what follows from it.
 *
 * Every frame is to sound at capture(f) + D in both rooms. The Sonos is held there with its websocket feedback (the lag it is steered
 * to is D). The TV gives no feedback: frame f is written to it at capture(f) + hold, and X, the TV's own delay from our write to its sound,
 * is known from how the TV starts (see [tvLatencyMs]: the backlog of its connection plus about 0.49 s); so D = hold + X. A frame cannot be
 * written before it has been captured, has come through the capture pipeline (P, about 80 ms from the capture point to this app) and
 * through the encoders, so the hold is at least P + the encoders, and
 *
 *     D = max(X + P, Sonos target) + margin
 *
 * with the Sonos target the lag it is kept to in Lyrics mode (the target setting, not below the floor). With the defaults of the audio-only
 * stream (X 300 ms, P 80 ms, target 322 ms, margin 30 ms) D is 410 ms and the TV stream is held 110 ms behind the capture; with the video
 * stream X is two seconds and D about 2.3 s.
 *
 * Plain Kotlin.
 */
object SyncedTiming {
    /** The room that is slower decides, and a margin keeps the TV's frames from being due before they exist. */
    const val MARGIN_MS = 30.0

    /** The pipeline to count with when it has not been measured (ms). */
    const val DEFAULT_PIPELINE_MS = 80.0

    /**
     * The audio-only L16 stream: the usual delay from our write to the sound of the TV (ms) when `tv_latency_ms` says "auto"; the sound
     * followed the data by 0.1 to 0.4 s.
     */
    const val AUTO_L16_LATENCY_MS = 300

    /**
     * The video stream (MPEG-TS, constant rate): the TV plays what it is given at the pace it is given it, starting at the key picture
     * its connection began at, so its latency from our write to its sound is **the age of that key picture when the connection was made
     * (the backlog) plus about 0.49 s**. In the device tests, three runs gave the same extra, to 10 ms: a backlog of 1.60 s gave 2.10 s,
     * one of 2.17 s gave 2.66 s. The backlog of the connection that stays open is what counts (see `KitchenSession.liveBacklogMs`).
     */
    const val AUTO_TS_EXTRA_MS = 490

    /** What is counted as the backlog before the TV has made a connection: the lead of its start and the spacing of the key pictures of the warm-up. */
    const val WARMUP_KEY_SPACING_MS = 100

    /**
     * What the stream itself needs between the capture of a frame and its write (ms), on top of the pipeline: the audio is taken a block of
     * 23 ms at a time and has to go through the AAC encoder, the picture through the H.264 encoder. The TS stream counts it into the
     * common delay so that its frames are never due before they exist.
     */
    const val ENCODE_TS_MS = 100.0

    /**
     * X for [format] ("ts" or "l16") from the setting [settingMs] (-1: auto): the video stream's is the [backlogMs] of the connection the
     * TV plays (NaN before it has one: the [burstMs] of the start and a little more) plus [extraMs]; a setting that is not negative
     * is the latency itself.
     */
    fun tvLatencyMs(
        settingMs: Int,
        format: String,
        backlogMs: Double = Double.NaN,
        burstMs: Int = 1_500,
        extraMs: Int = AUTO_TS_EXTRA_MS,
    ): Int {
        if (settingMs >= 0) return settingMs
        if (format != "ts") return AUTO_L16_LATENCY_MS
        val backlog = if (backlogMs.isNaN()) (burstMs + WARMUP_KEY_SPACING_MS).toDouble() else backlogMs
        return Math.round(backlog + extraMs).toInt()
    }

    /** D, the delay of both rooms after the capture (ms). [pipelineMs] NaN: the default is used. */
    fun delayMs(tvLatencyMs: Double, pipelineMs: Double, sonosTargetMs: Double, marginMs: Double = MARGIN_MS): Double {
        val pipeline = if (pipelineMs.isNaN() || pipelineMs < 0) DEFAULT_PIPELINE_MS else pipelineMs
        return maxOf(tvLatencyMs + pipeline, sonosTargetMs) + marginMs
    }

    /** How long after its capture a frame is written to the TV (ms): D less the TV's own delay. */
    fun tvHoldMs(delayMs: Double, tvLatencyMs: Double): Double = delayMs - tvLatencyMs

    /** The part of the stream's own delay that the common delay has to make room for (ms), by format ("ts" or "l16"). */
    fun encodeMs(format: String): Double = if (format == "ts") ENCODE_TS_MS else 0.0

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
