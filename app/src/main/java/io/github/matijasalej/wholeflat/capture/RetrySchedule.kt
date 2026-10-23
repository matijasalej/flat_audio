package io.github.matijasalej.wholeflat.capture

/**
 * The waits between the attempts to get the screen capture permission and the capture going again after it
 * was lost: 2 s, 5 s, 15 s, 30 s and then a minute for as long as it takes. [reset] after a capture ran well.
 */
class RetrySchedule(private val delaysMs: LongArray = longArrayOf(2_000, 5_000, 15_000, 30_000, 60_000)) {
    private var attempt = 0

    /** The number of waits handed out since the last reset. */
    val attempts: Int get() = attempt

    @Synchronized
    fun next(): Long {
        val delay = delaysMs[minOf(attempt, delaysMs.size - 1)]
        attempt++
        return delay
    }

    @Synchronized
    fun reset() {
        attempt = 0
    }
}
