package io.github.matijasalej.wholeflat.sync

/**
 * A stream of ours that someone paused on the speaker itself (its power button pauses first, a button on the speaker, the Sonos app): the
 * kitchen leaves the speaker alone while that stream is still paused there, until the speaker is stopped or plays. A paused stream of ours
 * from an earlier session or run is nobody's, and does not count. **A power cycle ends the pause**: if the speaker goes offline and comes
 * back (it said goodbye, or it stopped answering) it was switched off and on, and "on again" means the kitchen is wanted; the stream it
 * still shows paused is a leftover. Plain Kotlin.
 */
class PausedHere {
    private var token: String? = null

    /** The token of the stream that is held by a pause, null if none. */
    val held: String? get() = token

    /** Our stream [token] was paused on the speaker during the session that just ended. */
    fun paused(token: String) {
        this.token = token
    }

    /** The speaker went offline: when it comes back it is a switched-on speaker, and the pause is forgotten. True if there was one. */
    fun offline(): Boolean {
        val had = token != null
        token = null
        return had
    }

    /**
     * Whether the speaker is busy for the kitchen: something else plays on it, or the stream of ours that is held by a pause is still
     * paused there. Anything else forgets the pause.
     */
    fun busy(kind: SonosPolicy.Availability, transport: String, uri: String): Boolean {
        val busy = when (kind) {
            SonosPolicy.Availability.BUSY -> true
            SonosPolicy.Availability.OURS -> transport == "PAUSED_PLAYBACK" && token != null && SonosPolicy.tokenOf(uri) == token
            SonosPolicy.Availability.FREE -> false
        }
        if (!busy) token = null
        return busy
    }
}
