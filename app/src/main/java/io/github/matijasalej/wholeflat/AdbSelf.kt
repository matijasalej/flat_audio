package io.github.matijasalej.wholeflat

import android.content.Context
import dadb.AdbKeyPair
import dadb.Dadb
import io.github.matijasalej.wholeflat.sync.ReplaySwitch
import io.github.matijasalej.wholeflat.sync.ReplaySwitch.Link
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

/**
 * The box's own adb, as this app's client: what lets the phone page switch Synced on HDMI ([ReplaySwitch]). The app connects to the adb
 * daemon on 127.0.0.1:[ReplaySwitch.PORT] with a key of its own (made once, in its files), and runs a shell command there. The daemon
 * listens on that port only after `adb tcpip 5555` from the Mac, until the box restarts; the first connection with the key asks on the
 * TV whether to allow it. One command at a time, on a thread of its own: a connection may wait for that tap.
 */
class AdbSelf(context: Context) {
    private val dir = File(context.filesDir, "adb")
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "adb-self").also { it.isDaemon = true } }

    @Volatile
    var link = Link.UNKNOWN
        private set

    @Volatile
    var error: String? = null
        private set

    /** The state of the switch that was asked for and is being run (or waits for Allow); null when nothing is. */
    @Volatile
    var pending: Boolean? = null
        private set

    @Volatile
    private var lastProbeMs = 0L

    /** Mutes the Spotify app ([on]) or gives it its sound back, in the background. */
    fun switch(on: Boolean) {
        pending = on
        exec.execute {
            try {
                val out = run(ReplaySwitch.command(on))
                if (out != null) AppLog.add(TAG, "the Spotify app is ${if (on) "muted: Synced on HDMI is on" else "heard again: Synced on HDMI is off"}")
            } finally {
                pending = null
            }
        }
    }

    /** Looks now and then (at most every 20 s, asked by the phone page) whether the daemon still listens, without connecting to it. */
    fun probeSoon() {
        val now = System.currentTimeMillis()
        if (now - lastProbeMs < PROBE_EVERY_MS || pending != null) return
        lastProbeMs = now
        exec.execute {
            val open = listens()
            if (!open) link = Link.NOT_ARMED else if (link == Link.NOT_ARMED) link = Link.UNKNOWN
        }
    }

    private fun listens(): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(HOST, ReplaySwitch.PORT), CONNECT_TIMEOUT_MS) }
        true
    } catch (e: Exception) {
        false
    }

    /** Runs [command] on the box's adb; its output, or null (see [link] and [error]). */
    private fun run(command: String): String? {
        if (!listens()) {
            link = Link.NOT_ARMED
            AppLog.add(TAG, "the box's adb does not listen on ${ReplaySwitch.PORT}: arm it from the Mac with ${ReplaySwitch.ARM_COMMAND}")
            return null
        }
        // a key the daemon has not seen yet waits for the tap on the TV: that is what the switch says meanwhile
        if (link != Link.READY) link = Link.WAITING_ALLOW
        return try {
            Dadb.create(HOST, ReplaySwitch.PORT, keyPair(), CONNECT_TIMEOUT_MS, ALLOW_TIMEOUT_MS).use { adb ->
                val r = adb.shell(command)
                if (r.exitCode != 0) throw IllegalStateException("exit ${r.exitCode}: ${r.allOutput.trim().take(200)}")
                link = Link.READY
                error = null
                r.output
            }
        } catch (t: Throwable) {
            link = Link.FAILED
            error = t.message ?: t.javaClass.simpleName
            AppLog.error(TAG, "the box's adb failed for \"$command\"", t)
            null
        }
    }

    private fun keyPair(): AdbKeyPair {
        val private = File(dir, "adbkey")
        val public = File(dir, "adbkey.pub")
        if (!private.exists() || !public.exists()) {
            dir.mkdirs()
            AdbKeyPair.generate(private, public)
            AppLog.add(TAG, "a key for the box's adb was made")
        }
        return AdbKeyPair.read(private, public)
    }

    fun statusJson(): org.json.JSONObject = org.json.JSONObject()
        .put("link", link.key)
        .put("error", error ?: org.json.JSONObject.NULL)
        .put("pending", pending ?: org.json.JSONObject.NULL)

    private companion object {
        const val TAG = "adb-self"
        const val HOST = "127.0.0.1"
        const val CONNECT_TIMEOUT_MS = 1_500

        /** How long a first connection waits for Allow on the TV. */
        const val ALLOW_TIMEOUT_MS = 60_000
        const val PROBE_EVERY_MS = 20_000L
    }
}
