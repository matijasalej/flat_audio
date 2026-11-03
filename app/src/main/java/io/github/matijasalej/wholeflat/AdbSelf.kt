package io.github.matijasalej.wholeflat

import android.content.Context
import dadb.AdbKeyPair
import dadb.AdbShellPacket
import dadb.Dadb
import io.github.matijasalej.wholeflat.sync.ReplaySwitch
import io.github.matijasalej.wholeflat.sync.ReplaySwitch.Link
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * The box's own adb, as this app's client: what lets the phone page switch Synced on HDMI ([ReplaySwitch]), and what reads the box's
 * log for the remote's power button ([PowerFollow]). The app connects to the adb daemon on 127.0.0.1:[ReplaySwitch.PORT] with a key of
 * its own (made once, in its files), and runs a shell command there. The daemon listens on that port only after `adb tcpip 5555` from
 * the Mac, until the box restarts; the first connection with the key asks on the TV whether to allow it. One command at a time, on a
 * thread of its own: a connection may wait for that tap. A [stream] runs on its caller's thread, on a connection of its own.
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

    /** Runs [command] on the box's adb after any command before it, and waits for it: its output, or null (see [link] and [error]). */
    fun runCommand(command: String): String? = try {
        exec.submit(Callable { run(command) }).get()
    } catch (e: Exception) {
        null
    }

    @Volatile
    private var streaming: Dadb? = null

    /**
     * Runs [command] on the box's adb on the calling thread, and hands each line of its output to [onLine] until it ends, the connection
     * drops or [stopStream] is called. [onOpen] is called once the command runs. False if it could not start (see [link] and [error]). The
     * connection has no read time-out: a log watch may wait for hours for its next line.
     */
    fun stream(command: String, onOpen: () -> Unit, onLine: (String) -> Unit): Boolean {
        if (!listens()) {
            link = Link.NOT_ARMED
            return false
        }
        var opened = false
        return try {
            Dadb.create(HOST, ReplaySwitch.PORT, keyPair(), CONNECT_TIMEOUT_MS, 0).use { adb ->
                streaming = adb
                adb.openShell(command).use { shell ->
                    link = Link.READY
                    error = null
                    opened = true
                    onOpen()
                    val pending = ByteArrayOutputStream()
                    while (true) {
                        val packet = shell.read()
                        if (packet is AdbShellPacket.Exit) break
                        if (packet !is AdbShellPacket.StdOut) continue
                        for (b in packet.payload) {
                            if (b == '\n'.code.toByte()) {
                                onLine(pending.toString(Charsets.UTF_8.name()).trimEnd('\r'))
                                pending.reset()
                            } else {
                                pending.write(b.toInt())
                            }
                        }
                    }
                }
            }
            true
        } catch (t: Throwable) {
            // a stop closes the connection under the read: that is no failure
            if (streaming != null) {
                error = t.message ?: t.javaClass.simpleName
                if (!opened) link = Link.FAILED
                AppLog.error(TAG, "the box's adb stream ended for \"$command\"", t)
            }
            opened
        } finally {
            streaming = null
        }
    }

    /** Ends the [stream] that runs, if one does. */
    fun stopStream() {
        val adb = streaming ?: return
        streaming = null
        try {
            adb.close()
        } catch (e: Exception) {
            // closed already
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

    @Synchronized
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
