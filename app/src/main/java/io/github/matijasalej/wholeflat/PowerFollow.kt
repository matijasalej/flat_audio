package io.github.matijasalej.wholeflat

import io.github.matijasalej.wholeflat.sync.PowerPress
import io.github.matijasalej.wholeflat.sync.ReplaySwitch
import io.github.matijasalej.wholeflat.sync.SettingsModel
import io.github.matijasalej.wholeflat.sync.TvJournal
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The remote's power button switches the TV off with the Onkyo ([PowerPress], setting `power_follow`). The remote's Power is set to the
 * Onkyo by infrared. A press with the box asleep already wakes it (Google's remote service does), and the box's wake turns the TV on. A
 * press with the box awake did nothing to the box: this puts it to sleep, and the CEC Standby of its sleep switches the TV off.
 *
 * The press is only in the box's log, which an app cannot read since Android 13, so the log is read through the box's own adb
 * ([AdbSelf]): this works while that is armed (`adb tcpip 5555` from the Mac after the box restarts). Every press goes to the journal
 * (`power_press`) and the status to `/power`.
 */
class PowerFollow(
    private val adb: AdbSelf,
    private val journal: TvJournal?,
    /** Whether the box is awake now (PowerManager.isInteractive): where the log starts, its last sleep is not known yet. */
    private val boxAwake: () -> Boolean,
) {
    private val press = PowerPress()
    private val decide = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "wf-power").also { it.isDaemon = true } }

    @Volatile
    private var running = false
    private var reader: Thread? = null

    /** off (the setting), connecting, watching, not_armed, retrying, stopped */
    @Volatile
    var state = "starting"
        private set

    @Volatile
    private var watchingSinceMs = 0L

    @Volatile
    private var last: JSONObject? = null

    @Volatile
    private var ons = 0

    @Volatile
    private var offs = 0

    @Volatile
    private var undos = 0

    private val settingsListener: (Set<String>) -> Unit = { changed ->
        if (SettingsModel.POWER_FOLLOW in changed) {
            // off: the watch ends now; on: the reader stops waiting
            if (Settings.values.powerFollow) reader?.interrupt() else adb.stopStream()
        }
    }

    fun start() {
        running = true
        Settings.addListener(settingsListener)
        reader = thread(name = "wf-power-log", isDaemon = true) { readLoop() }
    }

    fun stop() {
        running = false
        state = "stopped"
        Settings.removeListener(settingsListener)
        adb.stopStream()
        reader?.interrupt()
        decide.shutdownNow()
    }

    /** The box's screen came on (the app's own broadcast): a backup for a wake that the log does not show. */
    fun screenOn() {
        val now = System.currentTimeMillis()
        post { press.add(PowerPress.Line(now, PowerPress.Kind.SCREEN_ON)) }
    }

    /** The box's screen went off: the box sleeps (the log says so too, about a second earlier). */
    fun screenOff() {
        val now = System.currentTimeMillis()
        post { press.add(PowerPress.Line(now, PowerPress.Kind.SCREEN_OFF)) }
    }

    private fun readLoop() {
        while (running) {
            if (!Settings.values.powerFollow) {
                state = "off"
                pause(OFF_CHECK_MS)
                continue
            }
            state = "connecting"
            val startedAt = System.currentTimeMillis()
            val ran = adb.stream(
                PowerPress.LOG_COMMAND,
                onOpen = {
                    state = "watching"
                    watchingSinceMs = System.currentTimeMillis()
                    val awake = boxAwake()
                    post { press.boxWas(awake, startedAt - 1) }
                    AppLog.add(TAG, "watching the remote's power button in the box's log")
                },
            ) { text -> onLine(text, startedAt) }
            if (!running) break
            if (!Settings.values.powerFollow) continue
            state = if (adb.link == ReplaySwitch.Link.NOT_ARMED) "not_armed" else "retrying"
            if (ran) AppLog.add(TAG, "the watch of the box's log ended; again in ${RETRY_SOON_MS / 1000} s")
            pause(if (ran) RETRY_SOON_MS else RETRY_MS)
        }
    }

    private fun onLine(text: String, startedAt: Long) {
        val line = PowerPress.parse(text) ?: return
        // `-T 1` starts with the last line logged before the watch: a press from then is not one to act on now
        if (line.kind == PowerPress.Kind.PRESS && line.atMs < startedAt - OLD_PRESS_MS) return
        val seen = System.currentTimeMillis()
        post {
            press.add(line, seen)
            if (line.kind == PowerPress.Kind.PRESS) {
                decide.schedule(Runnable { guarded { decideDue() } }, PowerPress.DECIDE_AFTER_MS + 20, TimeUnit.MILLISECONDS)
            }
        }
    }

    private fun decideDue() {
        for (d in press.due(System.currentTimeMillis())) act(d)
    }

    private fun act(d: PowerPress.Decision) {
        when (d.verdict) {
            PowerPress.Verdict.ON -> ons++
            PowerPress.Verdict.OFF -> offs++
            PowerPress.Verdict.UNDO -> undos++
        }
        val action = when {
            d.verdict == PowerPress.Verdict.ON -> "nothing: the box woke, and the TV comes on with it"
            !Settings.values.powerFollow -> "nothing: power_follow is off"
            d.verdict == PowerPress.Verdict.OFF -> command(PowerPress.SLEEP_COMMAND, "the box was put to sleep: the TV goes off with it")
            else -> command(PowerPress.WAKE_COMMAND, "the box was woken again: the TV comes back on")
        }
        val afterMs = System.currentTimeMillis() - d.pressAtMs
        val o = JSONObject()
            .put("verdict", d.verdict.key)
            .put("why", d.why)
            .put("action", action)
            .put("done_after_ms", afterMs)
        last = JSONObject(o.toString()).put("at_ms", d.pressAtMs)
        journal?.add("power_press", o)
        AppLog.add(TAG, "power button: ${d.verdict.key} (${d.why}); $action, ${afterMs} ms after the press")
    }

    private fun command(command: String, done: String): String {
        val out = adb.runCommand(command)
        return if (out != null) done else "could not: ${adb.error ?: adb.link.key}"
    }

    private fun post(block: () -> Unit) {
        try {
            decide.execute { guarded(block) }
        } catch (e: Exception) {
            // shut down
        }
    }

    private fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            AppLog.error(TAG, "the power button step failed", t)
        }
    }

    private fun pause(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            // a stop, or the setting came on
        }
    }

    fun statusJson(): JSONObject = JSONObject()
        .put("enabled", Settings.values.powerFollow)
        .put("state", state)
        .put("watching_for_s", if (state == "watching") (System.currentTimeMillis() - watchingSinceMs) / 1000 else JSONObject.NULL)
        .put("presses", JSONObject().put("on", ons).put("off", offs).put("undo", undos))
        .put("last", last ?: JSONObject.NULL)
        .put("adb", adb.link.key)
        .put("adb_error", adb.error ?: JSONObject.NULL)
        .put("arm", ReplaySwitch.ARM_COMMAND)
        .put("log", PowerPress.LOG_COMMAND)

    private companion object {
        const val TAG = "power"

        /** A press logged this long before the watch began still counts (it may not have been seen by the watch before). */
        const val OLD_PRESS_MS = 2_000L
        const val OFF_CHECK_MS = 10_000L
        const val RETRY_SOON_MS = 5_000L
        const val RETRY_MS = 60_000L
    }
}
