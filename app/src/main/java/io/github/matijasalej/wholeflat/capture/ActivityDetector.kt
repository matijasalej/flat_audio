package io.github.matijasalej.wholeflat.capture

/**
 * Tells from the level of the captured audio whether something is playing.
 *
 * Sound for [startNanos] in a row is a start; silence for [pauseNanos] after it is a pause; and
 * silence for [idleNanos] since the last sound is the end of it (the kitchen then stops, a skip or a short
 * pause does not). A pause shorter than a gap of [gapNanos] inside the sound does not break it.
 *
 * A start is announced early as a [Event.CANDIDATE] (at the first sound), so that the speaker can be
 * prepared while the 200 ms are being waited for; if the sound stops again the candidate is cancelled.
 * The MediaSession of the player can say the same sooner ([hint]), but the audio decides.
 *
 * Plain Kotlin; the time comes from the chunk timestamps, not from counting chunks.
 */
class ActivityDetector(
    private val silenceDb: Double = DEFAULT_SILENCE_DB,
    private val startNanos: Long = 200_000_000L,
    private val gapNanos: Long = 100_000_000L,
    private val pauseNanos: Long = 2_000_000_000L,
    private val idleNanos: Long = 60_000_000_000L,
    private val hintTimeoutNanos: Long = 3_000_000_000L,
) {
    enum class State { IDLE, ACTIVE, PAUSED }

    enum class Event {
        /** Sound began (or the player says it will): get the speaker ready. */
        CANDIDATE,

        /** It was a blip, or the player did not follow up. */
        CANDIDATE_CANCELLED,

        /** Music is playing. */
        START,

        /** Silence for 2 s: paused or between tracks. The kitchen keeps streaming silence. */
        PAUSE,

        /** Sound again after a pause (within the hold). */
        RESUME,

        /** Silent for the whole hold: the kitchen stops. */
        IDLE,
    }

    @Volatile
    var state = State.IDLE
        private set

    /** Start of the current run of sound that has not reached [startNanos] yet, -1 if none. */
    private var runStart = -1L
    private var lastSound = -1L
    private var silentSince = -1L
    private var hintedAt = -1L

    /** Seconds since the last sound, for the status (-1 before the first). */
    fun silentForNanos(nowNanos: Long): Long = if (lastSound < 0) -1L else nowNanos - lastSound

    @Synchronized
    fun reset() {
        state = State.IDLE
        runStart = -1L
        lastSound = -1L
        silentSince = -1L
        hintedAt = -1L
    }

    /** One chunk of captured audio: when it was read and its peak level in dBFS. */
    @Synchronized
    fun onChunk(tNanos: Long, peakDb: Double): List<Event> {
        val out = ArrayList<Event>(2)
        if (peakDb > silenceDb) {
            lastSound = tNanos
            silentSince = -1L
            hintedAt = -1L
            if (state != State.ACTIVE) {
                if (runStart < 0) {
                    runStart = tNanos
                    if (state == State.IDLE) out.add(Event.CANDIDATE)
                }
                if (tNanos - runStart >= startNanos) {
                    out.add(if (state == State.IDLE) Event.START else Event.RESUME)
                    state = State.ACTIVE
                    runStart = -1L
                }
            }
            return out
        }

        if (silentSince < 0) silentSince = tNanos
        when (state) {
            State.ACTIVE -> {
                if (tNanos - lastSound >= pauseNanos) {
                    state = State.PAUSED
                    out.add(Event.PAUSE)
                }
            }
            State.IDLE, State.PAUSED -> {
                if (runStart >= 0 && tNanos - silentSince > gapNanos) {
                    runStart = -1L
                    if (state == State.IDLE) out.add(Event.CANDIDATE_CANCELLED)
                }
                if (state == State.IDLE && hintedAt >= 0 && runStart < 0 && tNanos - hintedAt >= hintTimeoutNanos) {
                    hintedAt = -1L
                    out.add(Event.CANDIDATE_CANCELLED)
                }
            }
        }
        if (state == State.PAUSED && lastSound >= 0 && tNanos - lastSound >= idleNanos) {
            state = State.IDLE
            runStart = -1L
            out.add(Event.IDLE)
        }
        return out
    }

    /**
     * What the player's MediaSession says: playing or not. A "playing" while idle prepares the speaker at
     * once; a "not playing" while active is a pause without waiting for the 2 s of silence.
     */
    @Synchronized
    fun hint(playing: Boolean, tNanos: Long): List<Event> {
        val out = ArrayList<Event>(1)
        if (playing) {
            if (state == State.IDLE && runStart < 0 && hintedAt < 0) {
                hintedAt = tNanos
                out.add(Event.CANDIDATE)
            }
        } else if (state == State.ACTIVE) {
            state = State.PAUSED
            runStart = -1L
            out.add(Event.PAUSE)
        }
        return out
    }

    companion object {
        /** A chunk whose peak is at or below this is silence (one LSB of 16 bit audio is -90 dBFS). */
        const val DEFAULT_SILENCE_DB = -70.0
    }
}
