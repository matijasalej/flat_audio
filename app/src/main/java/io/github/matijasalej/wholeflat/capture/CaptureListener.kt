package io.github.matijasalej.wholeflat.capture

import io.github.matijasalej.wholeflat.source.SourceListener

/**
 * What the capture source tells the app, on top of what every source tells ([SourceListener]: start,
 * pause, resume, the track). All calls come from the capture thread or the main thread and must not block.
 */
interface CaptureListener : SourceListener {
    /** Sound began, or the player's media session says it will play: get the speaker ready. */
    fun onCandidate()

    /** It was a blip, or the player did not follow up. */
    fun onCandidateCancelled()

    /** Silent for the whole hold (a minute): the player is done, the speaker may stop. */
    fun onIdle()

    /** The capture is running; the frames of this attempt are numbered [generation]. */
    fun onCaptureRunning(generation: Long)

    /** The capture is gone (the projection was lost, or it was switched off). A new attempt follows by itself. */
    fun onCaptureLost(reason: String)
}
