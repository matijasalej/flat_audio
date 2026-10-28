package io.github.matijasalej.wholeflat.sync

/**
 * How long a fresh start of the TV's player has to wait. On 28.10. three starts hung the player: 35 s after the TV had its power back, and
 * 5 s and 70 s after its player had been closed; the starts that played came long after the TV came on, or as a restart with the player
 * still open. So a fresh start waits until the TV has been on for [bootWaitMs] and its player has been closed for [closeWaitMs]; a restart
 * (Stop and the new item at once) does not ask. 0 switches a wait off. Plain Kotlin.
 */
object SwitchGates {
    class Wait(val ms: Long, val why: String?, val bootLeftMs: Long, val closeLeftMs: Long)

    /**
     * [nowMs] and [onSinceMs] on the monotonic clock (0: not known, no boot wait), [nowWallMs] and [lastCloseWallMs] on the wall clock (the
     * close is kept across restarts of the app; 0: none).
     */
    fun wait(nowMs: Long, onSinceMs: Long, bootWaitMs: Long, nowWallMs: Long, lastCloseWallMs: Long, closeWaitMs: Long): Wait {
        val boot = if (bootWaitMs <= 0L || onSinceMs <= 0L) 0L else (bootWaitMs - (nowMs - onSinceMs)).coerceAtLeast(0L)
        val close = if (closeWaitMs <= 0L || lastCloseWallMs <= 0L) 0L else (closeWaitMs - (nowWallMs - lastCloseWallMs)).coerceIn(0L, closeWaitMs)
        val ms = maxOf(boot, close)
        val why = when {
            ms == 0L -> null
            boot >= close -> "the TV came on ${(nowMs - onSinceMs) / 1000} s ago, its player is given ${bootWaitMs / 1000} s"
            else -> "its player was closed ${(nowWallMs - lastCloseWallMs) / 1000} s ago, it is given ${closeWaitMs / 1000} s"
        }
        return Wait(ms, why, boot, close)
    }
}
