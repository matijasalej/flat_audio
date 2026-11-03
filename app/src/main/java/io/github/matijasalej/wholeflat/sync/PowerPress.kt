package io.github.matijasalej.wholeflat.sync

/**
 * The remote's power button, read from the box's log: whether a press switched the room on or off.
 *
 * The remote's Power is set to the Onkyo by infrared (Google TV's remote setup), so the box never gets it as a key (nothing in `getevent`,
 * 3.11.2026). Google's remote service (Axel) hears each press over its own Bluetooth link and logs `Axel.RemoteManager: Waking up device`:
 *  - with the box asleep it wakes it (`PowerManagerService: Waking up from Asleep (… details=axel:IR_power_button)`, 3 ms later), the
 *    box's wake turns the TV on over CEC, and the Onkyo comes on by infrared: everything is on;
 *  - with the box awake that wake does nothing: the Onkyo goes off and the TV stays on.
 *
 * So a press that did not wake the box is an off press: the box is to go to sleep, and its CEC Standby switches the TV off too.
 *
 * Lines are added as they come ([add]); a press is decided [DECIDE_AFTER_MS] after it was seen ([due]), when its wake has had time to
 * show. A wake belongs to one press only, so two presses close together are on and then off, as the Onkyo is. When the wake line is
 * missing, the box's own sleep and wake lines tell whether it was asleep. A wrong "off" would switch the TV off right after it came on,
 * so whatever looks like a wake makes it "on". A second press that comes after an off press but before the box slept turned the Onkyo
 * on again: the two cancel ([Verdict.UNDO]), and the box is woken again if it went to sleep meanwhile. Plain Kotlin.
 */
class PowerPress {
    enum class Kind {
        /** Axel heard the power button. */
        PRESS,

        /** The box woke from sleep (PowerManagerService). */
        WOKE,

        /** The box woke from its screensaver: the TV was on before. */
        WOKE_FROM_DREAM,

        /** The box went to sleep (PowerManagerService). */
        SLEPT,

        /** The app's own broadcast that the box's screen came on: a backup for a wake line that did not come. */
        SCREEN_ON,

        /** The app's own broadcast that the box's screen went off: a backup for a sleep line that did not come (it is about 1 s later). */
        SCREEN_OFF,
    }

    /** A line: [atMs] is when it was logged (when it was seen, for [Kind.SCREEN_ON]), on the box's wall clock. */
    class Line(val atMs: Long, val kind: Kind, val text: String = "")

    enum class Verdict(val key: String) {
        /** The press found the box asleep: Axel woke it, and everything comes on. */
        ON("on"),

        /** The press found the box awake: the Onkyo went off, and the box is to sleep too. */
        OFF("off"),

        /** The press came after an off press, before the box slept: the Onkyo is on again, and the box is to stay on (or wake again). */
        UNDO("undo"),
    }

    class Decision(val pressAtMs: Long, val verdict: Verdict, val why: String)

    private class Pending(val atMs: Long, val seenAtMs: Long)

    private val presses = ArrayList<Pending>()

    /** Wakes that a press may still take (each one press only). */
    private val wakes = ArrayList<Line>()

    private var lastPressMs: Long? = null
    private var lastWokeMs: Long? = null
    private var lastSleptMs: Long? = null

    /** The last press that was decided "off", while it may still be cancelled by the next. */
    private var lastOffMs: Long? = null

    /** Adds a line; [seenAtMs] is when it came (a press is decided counting from then). */
    fun add(line: Line, seenAtMs: Long = line.atMs) {
        when (line.kind) {
            Kind.PRESS -> {
                // one press logged twice
                val last = lastPressMs
                if (last != null && Math.abs(line.atMs - last) < SAME_PRESS_MS) return
                lastPressMs = line.atMs
                presses.add(Pending(line.atMs, seenAtMs))
                presses.sortBy { it.atMs }
            }
            Kind.SLEPT -> lastSleptMs = maxOf(lastSleptMs ?: line.atMs, line.atMs)
            Kind.SCREEN_OFF -> {
                // the broadcast of a sleep that the log told already would put that sleep a second late
                val slept = lastSleptMs
                if (slept == null || line.atMs - slept > SAME_SLEEP_MS) lastSleptMs = line.atMs
            }
            Kind.WOKE, Kind.WOKE_FROM_DREAM -> {
                lastWokeMs = maxOf(lastWokeMs ?: line.atMs, line.atMs)
                wakes.add(line)
            }
            Kind.SCREEN_ON -> wakes.add(line)
        }
    }

    /** What the box was at [atMs] when the log's watch began (its last sleep or wake is not in the log that is read): no press takes it. */
    fun boxWas(awake: Boolean, atMs: Long) {
        if (awake) lastWokeMs = maxOf(lastWokeMs ?: atMs, atMs) else lastSleptMs = maxOf(lastSleptMs ?: atMs, atMs)
    }

    /** The presses that are decided by [nowMs], oldest first; each is given once. */
    fun due(nowMs: Long): List<Decision> {
        val out = ArrayList<Decision>()
        while (presses.isNotEmpty() && nowMs - presses[0].seenAtMs >= DECIDE_AFTER_MS) {
            out.add(decide(presses.removeAt(0).atMs))
        }
        // nothing this old can be taken by a press that is still to come
        wakes.removeAll { it.atMs < nowMs - KEEP_MS }
        return out
    }

    /** How many presses wait for their decision. */
    val waiting: Int get() = presses.size

    private fun decide(p: Long): Decision {
        val near = wakes.filter { it.atMs - p in -BEFORE_MS..AFTER_MS }
        // what woke at this press belongs to it: a later press cannot take it
        wakes.removeAll(near.toSet())
        fun nearest(kind: Kind) = near.filter { it.kind == kind }.minByOrNull { Math.abs(it.atMs - p) }
        val decision = nearest(Kind.WOKE_FROM_DREAM)?.let { off(p, "the box woke from its screensaver: the TV was on") }
            ?: nearest(Kind.WOKE)?.let { Decision(p, Verdict.ON, "the box woke ${it.atMs - p} ms after it") }
            ?: nearest(Kind.SCREEN_ON)?.let { Decision(p, Verdict.ON, "the box's screen came on ${it.atMs - p} ms after it") }
            ?: asleepAt(p)?.let { slept -> Decision(p, Verdict.ON, "the box was asleep (for ${(p - slept) / 1000} s), its wake was not seen") }
            ?: off(p, "the box was awake: the press did not wake it")
        if (decision.verdict != Verdict.OFF) lastOffMs = null
        return decision
    }

    /** When the box went to sleep, if the log says it was asleep at [p]; null if it was awake or nothing is known. */
    private fun asleepAt(p: Long): Long? {
        val slept = lastSleptMs ?: return null
        val woke = lastWokeMs
        return if (slept < p && (woke == null || woke < slept || woke > p)) slept else null
    }

    /**
     * An off press, unless it follows an off press that the box had not slept on yet when it came: then the two cancel. (The sleep of the
     * first may be logged after the second press, before the second is decided: it does not count.)
     */
    private fun off(p: Long, why: String): Decision {
        val before = lastOffMs
        val slept = lastSleptMs
        if (before != null && p - before < UNDO_MS && (slept == null || slept <= before || slept >= p)) {
            lastOffMs = null
            return Decision(p, Verdict.UNDO, "${p - before} ms after an off press, before the box slept: the two cancel")
        }
        lastOffMs = p
        return Decision(p, Verdict.OFF, why)
    }

    companion object {
        /**
         * How long after a press was seen it is decided: its wake is logged 3 ms after it (the screen's broadcast comes about 0.2 s
         * later). Short, because the TV goes off only after it: a slow "off" invites a second press.
         */
        const val DECIDE_AFTER_MS = 500L

        /** A wake counts for a press from this long before it (the two lines come from two log buffers, either may come first)... */
        const val BEFORE_MS = 300L

        /** ...to this long after it. */
        const val AFTER_MS = 2_000L

        /** Two press lines closer than this are one press logged twice (a quick double press is still two). */
        const val SAME_PRESS_MS = 100L

        /** An off press is cancelled by a second press within this long, if the box has not slept in between. */
        const val UNDO_MS = 2_500L

        /** A screen-off broadcast this soon after a sleep line is that same sleep. */
        const val SAME_SLEEP_MS = 5_000L

        private const val KEEP_MS = 10_000L

        const val AXEL_TAG = "Axel.RemoteManager"
        const val AXEL_PRESS = "Waking up device"
        const val POWER_TAG = "PowerManagerService"

        /** The lines, as the box logs them from now on (`-T 1` starts with the last line logged before; the reader drops it by its time). */
        const val LOG_COMMAND = "logcat -v epoch -T 1 -b main,system -s $AXEL_TAG:I $POWER_TAG:I"

        /** What puts the box to sleep; its sleep sends the TV a CEC Standby. */
        const val SLEEP_COMMAND = "input keyevent KEYCODE_SLEEP"

        /** What wakes the box again ([Verdict.UNDO]); its wake turns the TV on over CEC (One Touch Play). */
        const val WAKE_COMMAND = "input keyevent KEYCODE_WAKEUP"

        private val LINE = Regex("""^\s*(\d+)\.(\d{3})\d*\s+\d+\s+\d+\s+[VDIWEFS]\s+(.+?)\s*:\s(.*)$""")
        private val WAKE = Regex("""^Waking up from (\w+)""")

        /** A line of `logcat -v epoch`: a press, a wake or a sleep of the box, or null for anything else. */
        fun parse(text: String): Line? {
            val m = LINE.find(text) ?: return null
            val atMs = m.groupValues[1].toLong() * 1000 + m.groupValues[2].toLong()
            val tag = m.groupValues[3]
            val message = m.groupValues[4]
            return when (tag) {
                AXEL_TAG -> if (message.startsWith(AXEL_PRESS)) Line(atMs, Kind.PRESS, message) else null
                POWER_TAG -> {
                    val from = WAKE.find(message)?.groupValues?.get(1)
                    when {
                        from == "Dreaming" -> Line(atMs, Kind.WOKE_FROM_DREAM, message)
                        from != null -> Line(atMs, Kind.WOKE, message)
                        message.startsWith("Going to sleep") -> Line(atMs, Kind.SLEPT, message)
                        else -> null
                    }
                }
                else -> null
            }
        }
    }
}
