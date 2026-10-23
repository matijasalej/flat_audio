package io.github.matijasalej.wholeflat.capture

/**
 * How long the captured audio has been silent, counted from the level of the chunks themselves (the same threshold as the activity
 * detector) and from nothing else: any chunk louder than [silenceDb] starts the count again from zero, whatever state the activity
 * detector is in. It **outlives the capture**: the capture is made again now and then (the box's audio route changes when the TV goes to
 * standby), the detector is reset with it and then knows of no sound, and a count that went with it said "never heard anything", which the
 * rules about long silence took for hours: 30 minutes of silence and ten minutes without music were announced seconds after music had
 * played, and the TV was given back under a running song. Before the first sound the count is from [startNanos] (when the app began).
 * Thread safe. Plain Kotlin.
 */
class SilenceClock(private val silenceDb: Double, startNanos: Long) {
    @Volatile
    private var lastAudibleNanos = -1L

    @Volatile
    private var base = startNanos

    /** One chunk of captured audio read at [tNanos], with its peak level in dBFS. */
    fun onChunk(tNanos: Long, peakDb: Double) {
        if (peakDb > silenceDb && tNanos > lastAudibleNanos) lastAudibleNanos = tNanos
    }

    /** The time since the last audible chunk, or since the start if there was none (ns); never negative. */
    fun silentForNanos(nowNanos: Long): Long = maxOf(0L, nowNanos - (if (lastAudibleNanos >= 0) lastAudibleNanos else base))

    /** Whether any chunk has been audible since the start. */
    val heardSound: Boolean get() = lastAudibleNanos >= 0
}
