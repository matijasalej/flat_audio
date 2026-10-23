package io.github.matijasalej.wholeflat.capture

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.matijasalej.wholeflat.AppLog
import io.github.matijasalej.wholeflat.sync.NowPlaying
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

    /**
     * What the watched sessions say now: playing or not, the package and the title and artist of the session that plays (or the first), and
     * everything else of that track in [now] (album, length, media id, cover address, position) with the cover itself in [art] when the
     * session gives it as a picture.
     */
    class Info(
        val playing: Boolean,
        val packageName: String,
        val title: String?,
        val artist: String?,
        val now: NowPlaying? = null,
        val art: Bitmap? = null,
    )

    @Volatile
    var listener: ((Info) -> Unit)? = null

    /** "no access" until the system has connected the listener service. */
    @Volatile
    var status = "no access"
        private set

    @Volatile
    var lastInfo: Info? = null
        private set

    /** The track that plays (or played last) with its position, null while no session has a title. */
    val nowPlaying: NowPlaying? get() = lastInfo?.now?.takeIf { it.hasTrack }

    /** The cover of that track as the session's own picture, if it has one. */
    val art: Bitmap? get() = lastInfo?.art

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
        var now: NowPlaying? = null
        var art: Bitmap? = null
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
                now = nowPlayingOf(name, c, meta, isPlaying)
                art = artOf(meta)
            }
            if (isPlaying) playing = true
        }
        val info = Info(playing, pkg, title, artist, now, art)
        lastInfo = info
        listener?.invoke(info)
    }

    /** The track of a session as [NowPlaying]: the position is brought from the session's clock (elapsed realtime) to nanoTime with one reading of both. */
    private fun nowPlayingOf(pkg: String, c: MediaController, meta: MediaMetadata?, playing: Boolean): NowPlaying? {
        val state = try {
            c.playbackState
        } catch (e: Exception) {
            null
        }
        val elapsedNow = SystemClock.elapsedRealtime()
        val nanoNow = System.nanoTime()
        val position = state?.position ?: PlaybackState.PLAYBACK_POSITION_UNKNOWN
        val at = if (state != null) NowPlaying.toNanoTime(state.lastPositionUpdateTime, elapsedNow, nanoNow) else nanoNow
        val speed = state?.playbackSpeed?.toDouble()?.takeIf { it > 0.0 } ?: 1.0
        val duration = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val art = firstHttp(
            meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI),
            meta?.getString(MediaMetadata.METADATA_KEY_ART_URI),
            meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI),
        )
        return NowPlaying(
            pkg,
            // buffering is not moving the position either; only a session that plays carries it on
            playing && state?.state == PlaybackState.STATE_PLAYING,
            meta?.getString(MediaMetadata.METADATA_KEY_TITLE),
            meta?.getString(MediaMetadata.METADATA_KEY_ARTIST),
            meta?.getString(MediaMetadata.METADATA_KEY_ALBUM),
            duration,
            meta?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
            art,
            if (position == PlaybackState.PLAYBACK_POSITION_UNKNOWN) -1L else position,
            at,
            speed,
        )
    }

    private fun firstHttp(vararg uris: String?): String? = uris.firstOrNull { it != null && (it.startsWith("http://") || it.startsWith("https://")) }

    private fun artOf(meta: MediaMetadata?): Bitmap? = try {
        meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
    } catch (e: Exception) {
        null
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
            val n = i.now
            if (n != null) {
                o.put("album", n.album ?: JSONObject.NULL)
                o.put("duration_ms", n.durationMs)
                o.put("media_id", n.mediaId ?: JSONObject.NULL)
                o.put("art_uri", n.artUri ?: JSONObject.NULL)
                o.put("art_bitmap", i.art != null)
                o.put("position_ms", n.positionMs)
                o.put("position_now_ms", n.positionAt(System.nanoTime()))
                o.put("speed", n.speed)
            }
        }
        return o
    }
}
