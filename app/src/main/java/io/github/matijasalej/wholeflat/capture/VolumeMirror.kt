package io.github.matijasalej.wholeflat.capture

/**
 * Turns changes of the Chromecast's music volume (what the Cast volume of the phone sets) into the Sonos
 * volume, and with "living room fixed volume" puts the music volume back to its maximum.
 *
 * The capture is taken before the volume is applied, so the Sonos always gets the full signal and the
 * volume has to be copied to the speaker by hand. With a fixed living room volume (the loudness is set on
 * the amplifier) the box's own volume is put back to maximum after each change, which makes the living room
 * quieter for a moment: the brief blip. The restore itself is a change of the volume: it is recognised (the
 * index it sets, within a short time) and ignored, and a volume that is merely read again is not a change,
 * so there is no loop.
 *
 * Plain Kotlin: the caller passes the indexes and the time, and does what the returned [Action] says.
 */
class VolumeMirror(
    private val debounceMs: Long = 250L,
    private val ignoreRestoreMs: Long = 2_000L,
) {
    /** What to do: set the Sonos volume (the raw value 0 to 65535 of the Connect scale), restore the box's volume. */
    class Action(val sonosRaw: Int, val restoreMax: Boolean)

    private var lastIndex = -1
    private var lastMax = 0
    private var pendingIndex = -1
    private var pendingMax = 0
    private var pendingAt = 0L
    private var ignoreUntil = 0L
    private var ignoreIndex = -1

    /** The volume as it is when watching begins: not a change, nothing to do about it. */
    @Synchronized
    fun seed(index: Int, max: Int) {
        lastIndex = index
        lastMax = max
        pendingIndex = -1
    }

    /** The volume index of the box was read at [nowMs]; only a different index than the last one is a change. */
    @Synchronized
    fun onIndex(index: Int, max: Int, nowMs: Long) {
        if (max <= 0) return
        if (index == lastIndex && max == lastMax) return
        lastIndex = index
        lastMax = max
        if (index == ignoreIndex && nowMs < ignoreUntil) return // the echo of our own restore
        pendingIndex = index
        pendingMax = max
        pendingAt = nowMs
    }

    /** Call regularly; returns the action once the volume has stayed put for the debounce time. */
    @Synchronized
    fun poll(nowMs: Long, fixedVolume: Boolean): Action? {
        if (pendingIndex < 0 || nowMs - pendingAt < debounceMs) return null
        val index = pendingIndex
        val max = pendingMax
        pendingIndex = -1
        val restore = fixedVolume && index < max
        if (restore) {
            ignoreIndex = max
            ignoreUntil = nowMs + ignoreRestoreMs
        }
        return Action(raw(index, max), restore)
    }

    /** Forgets a change that was not acted on yet (capture mode ended). */
    @Synchronized
    fun clear() {
        pendingIndex = -1
        lastIndex = -1
    }

    companion object {
        /**
         * The Sonos volume to set when capture mode begins, or null to leave the speaker as it is. With the fixed
         * living room volume the box is parked at its maximum on purpose, so its index says nothing about how loud
         * the kitchen should be (it would blast at full volume at the start of every stream); only a change that
         * the user makes sets the volume. Without it, the box's volume is what the user hears in the living room
         * and the Sonos follows it from the first note.
         */
        fun initialRaw(index: Int, max: Int, fixedVolume: Boolean): Int? =
            if (fixedVolume || max <= 0) null else raw(index, max)

        /** The index 0..max as the Connect scale 0..65535 (what the Sonos volume mapping starts from). */
        fun raw(index: Int, max: Int): Int =
            if (max <= 0) 0 else (index.coerceIn(0, max) * 65_535L / max).toInt()
    }
}
