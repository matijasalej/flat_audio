package io.github.matijasalej.wholeflat.capture

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import io.github.matijasalej.wholeflat.AppLog
import org.json.JSONObject

/**
 * The media sessions of the players that are captured (the Cast receiver and the Spotify TV app): play,
 * pause and the track, a little before the sound shows it. The audio decides what is playing; this only
 * lets the speaker be prepared early and fills in the title for the screen.
 *
 * It needs the notification listener access, which can only be granted over adb:
 * `adb shell cmd notification allow_listener io.github.matijasalej.wholeflat/io.github.matijasalej.wholeflat.capture.MediaWatcher`.
 * Without it nothing is lost but the head start.
 */
object MediaWatch {
    private const val TAG = "media"

    class Info(val playing: Boolean, val packageName: String, val title: String?, val artist: String?)

    @Volatile
    var listener: ((Info) -> Unit)? = null

    /** "no access" until the system has connected the listener service. */
    @Volatile
    var status = "no access"
        private set

    @Volatile
    var lastInfo: Info? = null
        private set

    private val handler = Handler(Looper.getMainLooper())
    private val watched = HashMap<String, Pair<MediaController, MediaController.Callback>>()
    private var manager: MediaSessionManager? = null
    private var component: ComponentName? = null
    private var sessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null

    /** Called by [MediaWatcher] when the system has connected it. */
    @Synchronized
    fun connect(context: Context, listenerComponent: ComponentName) {
        disconnectLocked()
        try {
            val m = context.getSystemService(MediaSessionManager::class.java) ?: return
            val l = MediaSessionManager.OnActiveSessionsChangedListener { controllers -> onSessions(controllers) }
            m.addOnActiveSessionsChangedListener(l, listenerComponent, handler)
            manager = m
            component = listenerComponent
            sessionsListener = l
            status = "connected"
            AppLog.add(TAG, "the media sessions are watched")
            onSessions(m.getActiveSessions(listenerComponent))
        } catch (e: SecurityException) {
            status = "no access: $e"
            AppLog.error(TAG, "no access to the media sessions", e)
        } catch (e: Exception) {
            status = "failed: $e"
            AppLog.error(TAG, "cannot watch the media sessions", e)
        }
    }

    @Synchronized
    fun disconnect() {
        disconnectLocked()
        status = "no access"
    }

    private fun disconnectLocked() {
        for ((controller, callback) in watched.values) {
            try {
                controller.unregisterCallback(callback)
            } catch (e: Exception) {
                // gone already
            }
        }
        watched.clear()
        try {
            sessionsListener?.let { manager?.removeOnActiveSessionsChangedListener(it) }
        } catch (e: Exception) {
            // not registered any more
        }
        sessionsListener = null
        manager = null
        component = null
    }

    @Synchronized
    private fun onSessions(controllers: List<MediaController>?) {
        val mine = controllers.orEmpty().filter { it.packageName in CaptureSource.PACKAGES }
        val names = mine.map { it.packageName }.toSet()
        val gone = watched.keys.filter { it !in names }
        for (name in gone) {
            watched.remove(name)?.let { (c, cb) ->
                try {
                    c.unregisterCallback(cb)
                } catch (e: Exception) {
                    // gone already
                }
            }
        }
        for (c in mine) {
            val known = watched[c.packageName]
            if (known != null && known.first.sessionToken == c.sessionToken) continue
            known?.let {
                try {
                    it.first.unregisterCallback(it.second)
                } catch (e: Exception) {
                    // gone already
                }
            }
            val callback = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = publish()

                override fun onMetadataChanged(metadata: MediaMetadata?) = publish()

                override fun onSessionDestroyed() = publish()
            }
            c.registerCallback(callback, handler)
            watched[c.packageName] = Pair(c, callback)
        }
        publish()
    }

    /** The state of all watched sessions, as one: playing if any plays; the title of the one that plays (or the first). */
    @Synchronized
    private fun publish() {
        var playing = false
        var pkg = ""
        var title: String? = null
        var artist: String? = null
        for ((name, pair) in watched) {
            val c = pair.first
            val state = try {
                c.playbackState?.state ?: PlaybackState.STATE_NONE
            } catch (e: Exception) {
                PlaybackState.STATE_NONE
            }
            val isPlaying = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING ||
                state == PlaybackState.STATE_CONNECTING
            val meta = try {
                c.metadata
            } catch (e: Exception) {
                null
            }
            if (isPlaying && !playing || pkg.isEmpty()) {
                pkg = name
                title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
                artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            }
            if (isPlaying) playing = true
        }
        val info = Info(playing, pkg, title, artist)
        lastInfo = info
        listener?.invoke(info)
    }

    fun statusJson(): JSONObject {
        val o = JSONObject()
        o.put("access", status)
        val i = lastInfo
        if (i != null) {
            o.put("playing", i.playing)
            o.put("package", i.packageName)
            o.put("title", i.title ?: JSONObject.NULL)
            o.put("artist", i.artist ?: JSONObject.NULL)
        }
        return o
    }
}
