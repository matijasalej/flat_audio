package io.github.matijasalej.wholeflat.sync

/**
 * When each frame of the captured audio is taken to have happened: what the lag of the kitchen is counted from. In the Lyrics mode it is
 * the clock of the capture (the timestamps of the audio capture, which say when the living room's path took the frame); in Synced mode it
 * is the clock of the TV's stream (a function of the number of the frame alone, see [SlewClock]), because the TV sounds a frame at that
 * time plus a fixed delay and the kitchen has to sound it at the same time, whatever the timestamps of the capture do.
 */
interface FrameTimes {
    /** The time of frame [frame] (System.nanoTime); null while it is not known. */
    fun nanosOfFrame(frame: Long): Long?
}
