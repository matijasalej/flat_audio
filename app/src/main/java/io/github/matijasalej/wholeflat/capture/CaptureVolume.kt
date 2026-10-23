package io.github.matijasalej.wholeflat.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import io.github.matijasalej.wholeflat.AppLog
import io.github.matijasalej.wholeflat.Settings
import org.json.JSONObject

/**
 * The volume of a captured player, for boxes where the phone's Cast volume does change STREAM_MUSIC; it is off unless the
 * setting `capture_volume_mirror` is on, and on a box where Cast changes nothing (HDMI as a full-volume device, CEC volume control
 * off: the one this app is built for) it never reads or touches STREAM_MUSIC at all. The capture is taken before that volume is
 * applied, so the Sonos always gets the full signal and its volume is set by hand to follow ([VolumeMirror] decides when).
 * With "living room fixed volume" the box's own volume is put back to its maximum after each change (the loudness is set on the
 * amplifier), which for a moment makes the living room quieter and then back: the brief blip, and the Cast volume in the phone
 * jumps back to full.
 *
 * It only watches while capture mode is the source ([setActive]) and the mirror is on; in the Spotify mode STREAM_MUSIC is none
 * of its business. It reads the index every [POLL_MS] and also listens for the system's volume broadcast, which some devices do
 * not send.
 */
class CaptureVolume(context: Context, private val onVolume: (Int) -> Unit) {
    private val app = context.applicationContext
    private val audio: AudioManager? = app.getSystemService(AudioManager::class.java)
    private val mirror = VolumeMirror()

    @Volatile
    private var running = false

    @Volatile
    var active = false
        private set

    @Volatile
    private var thread: Thread? = null

    @Volatile
    var index = -1
        private set

    @Volatile
    var max = 0
        private set

    @Volatile
    var changes = 0
        private set

    @Volatile
    var restores = 0
        private set

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!active) return
            if (intent?.getIntExtra(EXTRA_STREAM_TYPE, -1) == AudioManager.STREAM_MUSIC) {
                try {
                    thread?.interrupt() // looks at once
                } catch (e: Exception) {
                    // the poll catches up
                }
            }
        }
    }

    @Synchronized
    fun start() {
        if (running) return
        running = true
        try {
            val filter = IntentFilter(ACTION_VOLUME_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                app.registerReceiver(receiver, filter)
            }
        } catch (e: Exception) {
            AppLog.error(TAG, "cannot listen for the volume broadcast, polling only", e)
        }
        thread = Thread({ loop() }, "wf-capture-volume").also {
            it.isDaemon = true
            it.start()
        }
    }

    @Synchronized
    fun stop() {
        running = false
        try {
            app.unregisterReceiver(receiver)
        } catch (e: Exception) {
            // not registered
        }
        thread?.interrupt()
        thread = null
        active = false
    }

    /** Capture mode is the source (whether or not the mirror is on). */
    @Volatile
    private var wanted = false

    /** Capture mode became the source or stopped being it. */
    fun setActive(on: Boolean) {
        wanted = on
        update()
    }

    /** The setting `capture_volume_mirror` changed. */
    fun refresh() = update()

    /** On while capture mode is the source and the mirror is switched on: the current volume is passed on at once, later changes as they come. */
    @Synchronized
    private fun update() {
        val on = wanted && Settings.values.captureVolumeMirror
        if (on == active) return
        if (on) {
            val am = audio
            if (am != null) {
                val i = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                val m = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                index = i
                max = m
                mirror.seed(i, m)
                val initial = VolumeMirror.initialRaw(i, m, Settings.values.livingFixedVolume)
                if (initial != null) {
                    AppLog.add(TAG, "capture volume: the box is at $i of $m, the Sonos follows it")
                    onVolume(initial)
                } else {
                    AppLog.add(TAG, "capture volume: the box is at $i of $m (fixed living room volume), the Sonos keeps its volume until the Cast volume is changed")
                }
            }
        } else {
            mirror.clear()
        }
        active = on
        thread?.interrupt()
    }

    private fun loop() {
        while (running) {
            try {
                if (active) step()
                Thread.sleep(POLL_MS)
            } catch (e: InterruptedException) {
                // woken: look now
            } catch (t: Throwable) {
                AppLog.error(TAG, "volume watch failed", t)
                try {
                    Thread.sleep(1_000)
                } catch (e: InterruptedException) {
                    // fine
                }
            }
        }
    }

    private fun step() {
        val am = audio ?: return
        val now = SystemClock.elapsedRealtime()
        val i = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val m = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (i != index || m != max) {
            changes++
            index = i
            max = m
        }
        mirror.onIndex(i, m, now)
        val action = mirror.poll(now, Settings.values.livingFixedVolume) ?: return
        AppLog.add(TAG, "capture volume: $i of $m, the Sonos gets ${action.sonosRaw * 100 / 65535} %")
        onVolume(action.sonosRaw)
        if (action.restoreMax) {
            try {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, m, 0)
                restores++
                AppLog.add(TAG, "capture volume: the box is put back to $m (fixed living room volume)")
            } catch (e: Exception) {
                AppLog.error(TAG, "cannot restore the music volume", e)
            }
        }
    }

    fun statusJson(): JSONObject {
        val o = JSONObject()
        o.put("mirror", Settings.values.captureVolumeMirror)
        o.put("watching", active)
        o.put("index", index)
        o.put("max", max)
        o.put("changes", changes)
        o.put("restores", restores)
        return o
    }

    private companion object {
        const val TAG = "volume"
        const val POLL_MS = 200L

        /** AudioManager.VOLUME_CHANGED_ACTION and its extra are hidden API, their strings are stable. */
        const val ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
        const val EXTRA_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
    }
}
