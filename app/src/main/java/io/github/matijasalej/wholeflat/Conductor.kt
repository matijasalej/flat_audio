package io.github.matijasalej.wholeflat

import io.github.matijasalej.wholeflat.sync.StartPlanner

/**
 * What the living room tells when an epoch starts or playback stops, passed on to the kitchen (which
 * prepares the Sonos and sends Play at the right moment) and to the TV (which wakes and blanks its
 * screen after a silence).
 */
class Conductor(private val kitchen: Kitchen, private val tv: TvControl) : StartCoordinator {
    override fun begin(epoch: Long, kind: Int, startRingFrame: Long) {
        tv.onStartEvent(kind)
        kitchen.begin(epoch, kind, startRingFrame)
    }

    override fun advice(epoch: Long): StartAdvice = kitchen.advice(epoch)

    override fun commit(epoch: Long, plan: StartPlanner.Plan) {
        kitchen.commit(epoch, plan)
    }

    override fun abandon(epoch: Long) {
        kitchen.abandon(epoch)
    }

    override fun playbackStopped() {
        tv.onStopped()
        kitchen.playbackStopped()
    }
}
