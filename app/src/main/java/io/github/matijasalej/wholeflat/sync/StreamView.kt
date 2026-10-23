package io.github.matijasalej.wholeflat.sync

/**
 * What the controller sees of one Sonos stream: the map from stream frames to ring frames, the trims
 * the stream builder has not applied yet, and when the living room plays a ring frame.
 */
class StreamView(
    private val map: KitchenMap,
    private val unappliedBudget: () -> Int,
    private val livingHal: (Long) -> Long?,
) : SyncController.View {
    override fun ringOfStream(streamFrame: Double): Double? = map.ringOfStream(streamFrame)

    override fun livingHalNanos(ringFrame: Double): Long? = livingHal(Math.round(ringFrame))

    override fun netDropsAt(streamFrame: Double): Double = map.netDropsAt(streamFrame)

    /** Trims applied beyond the position being heard (they are in the stream but not heard yet), plus those still to be applied. */
    override fun pendingTrimFrames(streamFrame: Double): Long {
        val applied = map.netDropsAt(map.streamEnd.toDouble()) - map.netDropsAt(streamFrame)
        return Math.round(applied) + unappliedBudget()
    }
}
