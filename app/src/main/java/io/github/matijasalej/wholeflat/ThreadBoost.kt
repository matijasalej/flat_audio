package io.github.matijasalej.wholeflat

import android.os.Process

/**
 * Puts the calling thread ahead of the other work on the box in the CPU queue. The threads that carry the TV's stream (the writer that
 * makes the transport stream on its clock, the audio feeder, the output threads of the two encoders, the connection that writes to the TV)
 * wake up a hundred times a second and have to run within a few milliseconds of their time: the TV holds the stream for a second or so and
 * takes a gap of more than that as a break in the stream, which moves its sound against the other room for good. At the default priority
 * they were seen waiting for the CPU for hundreds of milliseconds behind the apps that were playing in front. Android allows an app to
 * give its own threads the priority of an audio thread (nice -19); these threads sleep nearly all the time, so they take nothing from anyone.
 * Never throws: a box that refuses leaves the thread as it is (the first thread of each name says in the log what it got).
 */
object ThreadBoost {
    private val told = HashSet<String>()

    /** The priority of an audio thread (-19): the writer, the audio feeder, the encoders' output threads, the connection to the TV. */
    fun urgent() = apply(Process.THREAD_PRIORITY_URGENT_AUDIO)

    /** A little ahead of the normal (-4): the thread that draws the pictures. */
    fun display() = apply(Process.THREAD_PRIORITY_DISPLAY)

    private fun apply(priority: Int) {
        try {
            val name = Thread.currentThread().name
            val before = Process.getThreadPriority(Process.myTid())
            Process.setThreadPriority(priority)
            val after = Process.getThreadPriority(Process.myTid())
            val tell = synchronized(told) { told.add(name) }
            if (tell) AppLog.add("tvvideo", "thread $name: priority $before -> $after" + if (after != priority) " (asked for $priority)" else "")
        } catch (t: Throwable) {
            // not on a box, or not allowed: the thread keeps its priority
        }
    }
}
