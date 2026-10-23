package io.github.matijasalej.wholeflat.light

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import io.github.matijasalej.wholeflat.AppLog
import io.github.matijasalej.wholeflat.AppState
import io.github.matijasalej.wholeflat.CoverCache
import io.github.matijasalej.wholeflat.Settings
import io.github.matijasalej.wholeflat.capture.CaptureFeed
import io.github.matijasalej.wholeflat.capture.CaptureFrames
import io.github.matijasalej.wholeflat.capture.MediaWatch
import io.github.matijasalej.wholeflat.sync.FrameSource
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * The mood lights. They listen to the captured music on a thread of their own and choose, song by song and phrase by phrase, which of
 * the TV's own Ambilight styles it shows ([TvLooks]):
 * - calm songs: the song's colour, drifting;
 * - most songs: its colours, livelier;
 * - energetic songs: the TV's beat-reactive modes, a new one every phrase, Party at a chorus or a drop.
 *
 * How it works:
 * - **Analysis:** an [Analyzer] reads the capture as the kitchen does (it never slows the capture down). A new song (a new key of the media
 *   session) starts it again, with the tempo the song had last time.
 * - **Look:** every half second the song's look is chosen again ([Looks]) from the analysis, the hues of the cover and his override. A new
 *   class has to hold for two seconds.
 * - **Timing:** the TV plays the capture later (in Synced mode the delay of its stream, about 2.4 s). So every tenth of a second the look
 *   is worked out for the frame that the room will hear when a call made now has reached the TV, and it is sent if it changed. A song
 *   change, a chorus or a new phrase then lands when it is heard, not when it is captured. While the previous song is still being heard,
 *   its look stays.
 * - **Calls:** through the app's Ambilight control ([io.github.matijasalej.wholeflat.sync.AmbilightControl], which holds the lights only
 *   while they are wanted), at most one a second.
 *
 * With `lights` = `tv` all of this stands aside: nothing is analysed and the TV's style is the app's own setting, as before.
 */
class LightEngine(context: Context, private val feed: CaptureFeed, private val covers: CoverCache) {
    private val prefs = context.applicationContext.getSharedPreferences("wholeflat_lights", Context.MODE_PRIVATE)

    @Volatile
    var options: LightOptions = LightOptions.of(prefs.getString(KEY_OPTIONS, null))
        private set

    val memory = SongMemory(object : SongStore {
        override fun read(): String? = prefs.getString(KEY_MEMORY, null)

        override fun write(json: String) {
            prefs.edit().putString(KEY_MEMORY, json).apply()
        }
    })

    private val rate = feed.rate
    private val analyzer = Analyzer(rate)

    @Volatile
    private var running = false
    private var analysing = false
    private var worker: Thread? = null
    private val tvExec = Executors.newSingleThreadExecutor { r -> Thread(r, "wf-lights-tv").also { it.isDaemon = true } }

    /** A song as it was captured: where it began, its look, and (once a newer one began) its analysis as it ended. */
    private class Song(val key: String?, val title: String?, val artist: String?, val startFrame: Long) {
        @Volatile var look: SongLook = SongLook(key ?: "", LightClass.MONO, DEFAULT_COLOR, Colors.hsv(250.0, 0.5, 0.6), IntArray(4) { DEFAULT_COLOR }, "nothing known yet")
        @Volatile var hues: CoverHues = CoverHues.NONE
        @Volatile var ended: Analysis? = null
        var huesKnown = false
        var pendingClass: LightClass? = null
        var pendingCount = 0
        var newPlay = true
        var lastRememberNanos = 0L
    }

    @Volatile private var current = Song(null, null, null, 0)
    @Volatile private var previous: Song? = null

    // the TV
    @Volatile private var wanted: TvLook? = null
    @Volatile private var wantedSong: Song? = null
    @Volatile private var heardFrame = -1L
    @Volatile private var delayUsedMs = Double.NaN
    @Volatile private var sentJson: String? = null
    @Volatile private var sent = 0
    @Volatile private var lastSentAt = ""
    private var lastSendNanos = 0L

    // what the analysis costs
    @Volatile private var cpuMsPerS = Double.NaN

    fun start() {
        if (running) return
        running = true
        worker = thread(name = "wf-lights", isDaemon = true) { loop() }
    }

    fun stop() {
        running = false
        worker?.interrupt()
        worker = null
        rememberSong(current)
        tvExec.shutdownNow()
    }

    /** The TV's style for the app's Ambilight control: the look for what is heard now; null while `lights` is `tv` (the app's own then). */
    fun tvConfig(): String? = if (options.mood) wanted?.json else null

    // ---- the analysis and the timing -----------------------------------------------------------

    private fun loop() {
        val buf = ShortArray(CHUNK * 2)
        var gen = Long.MIN_VALUE
        var source: CaptureFrames? = null
        var cursor = 0L
        var lastTrack = 0L
        var lastLook = 0L
        var lastTv = 0L
        var cpuFrom = SystemClock.currentThreadTimeMillis()
        var cpuAt = SystemClock.elapsedRealtime()
        while (running) {
            try {
                if (source == null || feed.generation != gen) {
                    val restarted = gen != Long.MIN_VALUE && feed.generation != gen
                    gen = feed.generation
                    source = CaptureFrames(feed.buffer, gen)
                    cursor = feed.buffer.writeIndex
                    if (restarted) {
                        // a new capture numbers its frames from 0 again: the song goes on from here
                        val c = current
                        current = Song(c.key, c.title, c.artist, cursor).also {
                            it.look = c.look
                            it.hues = c.hues
                            it.huesKnown = c.huesKnown
                        }
                        previous = null
                    }
                    analyzer.reset(cursor, rememberedBpm(current.key))
                }
                val n = source.read(cursor, buf, CHUNK, 100)
                val mood = options.mood
                if (mood && !analysing) analyzer.reset(cursor, rememberedBpm(current.key)) // switched on: the song's analysis starts afresh
                analysing = mood
                when {
                    n > 0 -> {
                        if (mood) analyzer.push(buf, n) // with `lights` = `tv` nothing is analysed
                        cursor += n
                    }
                    n == FrameSource.EPOCH -> source = null
                    n == FrameSource.LAPPED -> {
                        cursor = feed.buffer.writeIndex
                        analyzer.reset(cursor, rememberedBpm(current.key))
                    }
                    n == FrameSource.CLOSED -> Thread.sleep(200)
                }
                val now = System.nanoTime()
                if (now - lastTrack >= 200_000_000L) {
                    lastTrack = now
                    checkTrack(cursor)
                }
                if (mood && now - lastLook >= 500_000_000L) {
                    lastLook = now
                    updateLook(now)
                }
                if (now - lastTv >= 100_000_000L) {
                    lastTv = now
                    tvStep(now)
                }
                val el = SystemClock.elapsedRealtime()
                if (el - cpuAt >= 10_000L) {
                    val cpu = SystemClock.currentThreadTimeMillis()
                    cpuMsPerS = (cpu - cpuFrom) * 1000.0 / (el - cpuAt)
                    cpuFrom = cpu
                    cpuAt = el
                }
            } catch (e: InterruptedException) {
                break
            } catch (t: Throwable) {
                AppLog.error(TAG, "the mood lights failed", t)
                try {
                    Thread.sleep(500)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun rememberedBpm(key: String?): Double? = key?.let { memory.get(it)?.bpm }?.takeIf { !it.isNaN() }

    private fun checkTrack(cursor: Long) {
        val np = AppState.nowPlaying()?.takeIf { it.hasTrack }
        val key = np?.key
        val song = current
        if (key != song.key) {
            rememberSong(song)
            song.ended = analyzer.snapshot
            previous = song
            val next = Song(key, np?.title, np?.artist, cursor)
            current = next
            analyzer.reset(cursor, rememberedBpm(key))
            if (key != null) {
                val m = memory.get(key)
                if (m != null) next.look = Looks.choose(key, m.energy, m.clarity, m.mood, next.hues, m.override)
                AppLog.add(TAG, "song ${np?.title} / ${np?.artist}: ${m?.let { "known, ${if (it.bpm.isNaN()) "no" else it.bpm.toInt().toString()} BPM, ${(it.override ?: it.cls)?.key}" } ?: "new"}")
            }
        }
        val c = current
        if (np != null && key != null && !c.huesKnown) {
            val cover = try {
                covers.coverFor(np, MediaWatch.art)
            } catch (t: Throwable) {
                null
            }
            if (cover != null) {
                c.hues = huesOf(cover.bitmap)
                c.huesKnown = true
            }
        }
    }

    private fun updateLook(now: Long) {
        val song = current
        val key = song.key ?: return
        val a = analyzer.snapshot
        val mem = memory.get(key)
        val songS = a.songSeconds(rate)
        // early in a song, what it was last time; then what it is
        val known = mem != null && !mem.bpm.isNaN()
        val energy = if (songS >= 10 || !known) a.energySong else mem!!.energy
        val clarity = if (a.locked || !known) a.clarity else mem!!.clarity
        val mood = if (songS >= 15 || !known) a.mood else mem!!.mood
        val chosen = Looks.choose(key, energy, clarity, mood, song.hues, mem?.override)
        val shown = song.look
        val cls = if (chosen.cls == shown.cls || mem?.override != null) {
            song.pendingClass = null
            song.pendingCount = 0
            chosen.cls
        } else {
            // a new class has to hold for two seconds (four readings) before it is shown
            if (song.pendingClass == chosen.cls) song.pendingCount++ else {
                song.pendingClass = chosen.cls
                song.pendingCount = 1
            }
            if (song.pendingCount >= 4) chosen.cls else shown.cls
        }
        song.look = if (cls == chosen.cls) chosen else SongLook(chosen.key, cls, chosen.home, chosen.accent, chosen.multi, chosen.why + " (held at ${cls.key})")
        // the memory is written at the end of a song; while one plays, once a minute (a few hundred kB at most, on a small flash)
        if (songS >= 20 && now - song.lastRememberNanos >= 60_000_000_000L) rememberSong(song)
    }

    /** The look for the frame that will be heard when a call made now has reached the TV; sent if it changed (at most once a second). */
    private fun tvStep(now: Long) {
        val o = options
        if (!o.mood) {
            wanted = null
            sentJson = null
            return
        }
        // how long after its capture a frame is heard: the delay of the TV's stream in Synced mode; at once over HDMI in Lyrics mode
        val delayMs = if (Settings.values.mode == "lyrics") 0.0 else AppState.tvRenderer?.takeIf { it.live }?.delayMs ?: Double.NaN
        delayUsedMs = delayMs
        if (delayMs.isNaN()) return // no stream: the TV's lights are not ours to set now
        val heard = feed.clock.frameAtNanos(now + APPLY_NANOS - Math.round(delayMs * 1e6)) ?: return
        heardFrame = heard
        val cur = current
        val prev = previous
        val song: Song
        val a: Analysis
        if (prev != null && heard < cur.startFrame) {
            song = prev
            a = prev.ended ?: analyzer.snapshot
        } else {
            if (prev != null) previous = null
            song = cur
            a = analyzer.snapshot
        }
        if (song.key == null) return
        val look = TvSchedule.lookAt(heard, a, song.look, rate, o.strength, o.modeLooks(), song.key.hashCode())
        wanted = look
        wantedSong = song
        if (look.json != sentJson && now - lastSendNanos >= MIN_SEND_GAP_NANOS) {
            sentJson = look.json
            lastSendNanos = now
            sent++
            lastSentAt = "${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date())} ${look.name} for ${song.title}"
            val synced = AppState.synced
            if (synced != null) tvExec.execute { synced.restyleAmbilight() }
        }
    }

    /** Notes [song] (if it has played long enough to say something about it), with the class the app would give it itself. */
    private fun rememberSong(song: Song) {
        val key = song.key ?: return
        if (song !== current) return
        val a = analyzer.snapshot
        if (a.songSeconds(rate) < 20) return
        val own = Looks.classify(a.energySong, a.clarity, song.hues.colourful, null)
        memory.remember(key, song.title, song.artist, if (a.locked) a.bpm else Double.NaN, a.energySong, a.clarity, a.mood, own, System.currentTimeMillis(), song.newPlay)
        song.newPlay = false
        song.lastRememberNanos = System.nanoTime()
    }

    private fun huesOf(bitmap: Bitmap): CoverHues = try {
        val small = Bitmap.createScaledBitmap(bitmap, 32, 32, true)
        val px = IntArray(32 * 32)
        small.getPixels(px, 0, 32, 0, 0, 32, 32)
        if (small !== bitmap) small.recycle()
        CoverHues.of(px)
    } catch (t: Throwable) {
        CoverHues.NONE
    }

    // ---- the debug routes -----------------------------------------------------------------------

    class Reply(val status: Int, val type: String, val body: ByteArray) {
        companion object {
            fun json(o: Any, status: Int = 200) = Reply(status, "application/json; charset=utf-8", o.toString().toByteArray(Charsets.UTF_8))
        }
    }

    /** `/lights` and below (GET): the state, the history, the settings, and his less, just right or more colour. */
    fun handle(path: String, query: Map<String, String>): Reply = when (path) {
        "/lights" -> Reply.json(statusJson())
        "/lights/history" -> Reply.json(JSONArray().also { arr -> memory.all().forEach { arr.put(it.toJson()) } })
        "/lights/set" -> set(query)
        "/lights/colour" -> colour(query["step"] ?: "")
        else -> Reply.json(JSONObject().put("error", "not found"), 404)
    }

    private fun set(query: Map<String, String>): Reply {
        val before = options
        val (o, refused) = before.apply(query)
        options = o
        prefs.edit().putString(KEY_OPTIONS, o.toJson().toString()).apply()
        if (o != before) {
            AppLog.add(TAG, "settings ${o.toJson()}")
            if (o.mode != before.mode) {
                // the control takes the app's own style again (tv) or ours from the next look (mood)
                sentJson = null
                AppState.synced?.let { s -> tvExec.execute { s.restyleAmbilight() } }
            }
        }
        return Reply.json(JSONObject().put("ok", refused.isEmpty()).put("options", o.toJson()).put("refused", JSONArray(refused)))
    }

    /**
     * His choice for the song that plays: one class less or more than it shows now, the one it shows ("keep": just right, which tells the
     * review that the app chose well), or back to the automatic one.
     */
    private fun colour(step: String): Reply {
        val song = current
        val key = song.key ?: return Reply.json(JSONObject().put("ok", false).put("error", "no song plays"), 409)
        val shown = song.look.cls
        val cls = when (step) {
            "less" -> shown.down()
            "more" -> shown.up()
            "keep" -> shown
            "auto" -> null
            else -> return Reply.json(JSONObject().put("ok", false).put("error", "step: less, keep, more or auto"), 400)
        }
        memory.override(key, cls, System.currentTimeMillis())
        AppLog.add(TAG, "his choice for ${song.title}: ${cls?.key ?: "automatic"}")
        updateLook(System.nanoTime())
        return Reply.json(JSONObject().put("ok", true).put("class", song.look.cls.key).put("override", cls?.key ?: JSONObject.NULL))
    }

    /** What the phone page shows of the lights: on or off, the class, what the TV shows, and the tempo. */
    fun phoneJson(): JSONObject {
        val song = current
        val a = analyzer.snapshot
        val mem = song.key?.let { memory.get(it) }
        return JSONObject()
            .put("on", options.mood)
            .put("class", song.look.cls.key)
            .put("override", mem?.override?.key ?: JSONObject.NULL)
            .put("tv", wanted?.name ?: JSONObject.NULL)
            .put("bpm", if (a.locked) Math.round(a.bpm) else JSONObject.NULL)
    }

    fun statusJson(): JSONObject {
        val a = analyzer.snapshot
        val song = current
        val o = JSONObject()
        o.put("options", options.toJson())
        o.put("track", JSONObject().put("key", song.key ?: JSONObject.NULL).put("title", song.title ?: JSONObject.NULL).put("artist", song.artist ?: JSONObject.NULL))
        o.put(
            "analysis",
            JSONObject()
                .put("song_s", round(a.songSeconds(rate), 1))
                .put("bpm", if (a.bpm.isNaN()) JSONObject.NULL else round(a.bpm, 1))
                .put("locked", a.locked)
                .put("clarity", round(a.clarity, 2))
                .put("energy_now", round(a.energyNow, 2))
                .put("energy_song", round(a.energySong, 2))
                .put("mood", round(a.mood, 2))
                .put("mode", round(a.mode, 2))
                .put("loud_db", round(a.loudDb, 1))
                .put("silent", a.silent)
                .put("sections", JSONArray().also { arr -> a.sections.forEach { s -> arr.put(JSONObject().put("at_s", round((s.frame - a.firstFrame).toDouble() / rate, 1)).put("up", s.up)) } }),
        )
        val l = song.look
        o.put("look", JSONObject().put("class", l.cls.key).put("home", hex(l.home)).put("accent", hex(l.accent)).put("why", l.why))
        o.put("cover", JSONObject().put("grey", song.hues.grey).put("colourful", song.hues.colourful).put("hues", JSONArray().also { arr -> song.hues.clusters.forEach { c -> arr.put(JSONObject().put("hue", Math.round(c.hue)).put("share", round(c.share, 2))) } }))
        val heard = heardFrame
        val ws = wantedSong
        val next = if (ws != null && heard >= 0) TvSchedule.nextSwitch(heard, if (ws === song) a else (ws.ended ?: a), ws.look, rate, options.strength) else -1L
        o.put(
            "tv",
            JSONObject()
                .put("look", wanted?.name ?: JSONObject.NULL)
                .put("for", ws?.title ?: JSONObject.NULL)
                .put("config", wanted?.json ?: JSONObject.NULL)
                .put("delay_ms", if (delayUsedMs.isNaN()) JSONObject.NULL else Math.round(delayUsedMs))
                .put("next_change_s", if (next < 0 || heard < 0) JSONObject.NULL else round((next - heard).toDouble() / rate, 1))
                .put("sent", sent)
                .put("last_sent", lastSentAt),
        )
        o.put("cpu_ms_per_s", if (cpuMsPerS.isNaN()) JSONObject.NULL else round(cpuMsPerS, 1))
        return o
    }

    private fun hex(c: Int): String = String.format("#%06x", c and 0xFFFFFF)

    private fun round(v: Double, digits: Int): Double {
        if (v.isNaN() || v.isInfinite()) return 0.0
        val f = Math.pow(10.0, digits.toDouble())
        return Math.round(v * f) / f
    }

    private companion object {
        const val TAG = "lights"
        const val KEY_OPTIONS = "options"
        const val KEY_MEMORY = "songs"

        /** Frames read at a time: 40 ms. */
        const val CHUNK = 1764

        /** How long a style call takes to show on the TV (the call and the TV's own step), sent this much ahead. */
        const val APPLY_NANOS = 150_000_000L

        /** The TV's server is slow: at most one style a second. */
        const val MIN_SEND_GAP_NANOS = 1_000_000_000L

        val DEFAULT_COLOR = Colors.rgb(64, 92, 140)
    }
}
