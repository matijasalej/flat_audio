package io.github.matijasalej.wholeflat.capture

/**
 * The lag that the kitchen is to be brought to in capture mode, from the settings. `kitchen_target_ms` says it directly; without it
 * the latency of the living room (`capture_living_latency_ms`, how long after the capture the Chromecast's sound reaches the room)
 * is the target, so that the kitchen lands in step with the living room. The kitchen offset is added to either (positive: the
 * kitchen later). Without both the lag is left where it locked.
 */
object CaptureTarget {
    /** The target [ms] after the capture, from the setting [source]. */
    class Target(val ms: Double, val source: String)

    const val FROM_TARGET = "kitchen_target_ms"
    const val FROM_LATENCY = "capture_living_latency_ms"

    fun of(kitchenTargetMs: Int, livingLatencyMs: Int, offsetMs: Int): Target? = when {
        kitchenTargetMs >= 0 -> Target((kitchenTargetMs + offsetMs).toDouble(), FROM_TARGET)
        livingLatencyMs > 0 -> Target((livingLatencyMs + offsetMs).toDouble(), FROM_LATENCY)
        else -> null
    }
}
