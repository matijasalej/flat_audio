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
import io.github.matijasalej.wholeflat.sync.Picture
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * The mood lights: they listen to the captured music on a thread of their own and decide, song by song, what the Ambilight shows.
 * - **Analysis:** an [Analyzer] reads the capture (it never slows the capture down; it reads as the kitchen does). A new song (a new key of
 *   the media session) starts it again, with the tempo the song had last time.
 * - **Look:** every half second the song's look is chosen again ([Looks]) from the analysis, the hues of the cover and his override; a new
 *   class has to hold for two seconds before it is shown.
 * - **Synced mode:** the TV picture carries the lights. The frame loop of the stream asks [LightHook] for the 16 colours of each picture's
 *   capture time, and the TV's style is [LightOptions.syncedStyle].
 * - **Lyrics mode:** the TV's own styles, one call per song or section ([NativeStyles]), through the app's Ambilight control.
 *
 * With `lights` = `tv` all of this stands aside: the picture is black and the TV's style is the app's own setting, as before.
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
    private val planner = LightPlanner(rate)
    private val previewPlanner = LightPlanner(rate)

    @Volatile
    private var running = false
    private var analysing = false
    private var worker: Thread? = null
    private val tvExec = Executors.newSingleThreadExecutor { r -> Thread(r, "wf-lights-tv").also { it.isDaemon = true } }

    // the song
    @Volatile private var trackKey: String? = null
    @Volatile private var trackTitle: String? = null
    @Volatile private var trackArtist: String? = null
    @Volatile private var hues: CoverHues = CoverHues.NONE
    private var huesKey: String? = null
    @Volatile private var look: SongLook = SongLook("", LightClass.MONO, Colors.rgb(64, 92, 140), Colors.rgb(140, 92, 64), IntArray(4) { Colors.rgb(64, 92, 140) }, "nothing plays yet")
    private var pendingClass: LightClass? = null
    private var pendingCount = 0
    private var newPlay = true
    private var lastRememberNanos = 0L

    // what the painter did last, for the status and the probe
    @Volatile private var lastZones = IntArray(Zones.COUNT)
    @Volatile private var frames = 0L

    // a test picture in force: the pattern, the capture time it began at, and when it ends
    @Volatile private var test: LightPatterns? = null
    @Volatile private var testStartNanos = 0L
    @Volatile private var testEndNanos = 0L

    // the TV's style in Lyrics mode
    @Volatile private var nativeSent: String? = null
    private var lastRestyleNanos = 0L
    @Volatile private var restyles = 0

    // what the analysis costs
    @Volatile private var cpuMsPerS = Double.NaN

    fun start() {
        if (running) return
        running = true
        LightHook.bandShare = options.bandPct / 100.0
        LightHook.source = { f, c, p -> frameFor(planner, f, c, p, true) }
        LightHook.previewSource = { f, c, p -> frameFor(previewPlanner, f, c, p, false) }
        worker = thread(name = "wf-lights", isDaemon = true) { loop() }
    }

    fun stop() {
        running = false
        LightHook.source = null
        LightHook.previewSource = null
        worker?.interrupt()
        worker = null
        rememberSong()
        tvExec.shutdownNow()
    }

    private var tvKey: String? = null
    private var tvJson: String? = null

    /**
     * The TV's style for the app's Ambilight control: null while `lights` is `tv` (the app's own setting is used then). The same string
     * until the song, its class (a section) or the cover's colours change, not with every small drift of the tint: the control sends a
     * configuration whenever it differs from the one the TV holds, and the TV's server is slow.
     */
    @Synchronized
    fun tvConfig(): String? {
        val o = options
        if (!o.mood) return null
        val lyrics = Settings.values.mode == "lyrics"
        val cls = classAt(analyzer.snapshot)
        val key = if (!lyrics) "synced|${o.syncedStyle}" else "lyrics|$trackKey|${cls.key}|${huesKey != null}|${o.nativeMono}|${o.nativeDuo}|${o.nativeMulti}"
        if (key != tvKey) {
            tvJson = if (!lyrics) NativeStyles.plain(o.syncedStyle) else NativeStyles.forLook(look, cls, o.nativeMono, o.nativeDuo, o.nativeMulti)
            tvKey = key
        }
        return tvJson
    }

    // ---- the picture ----------------------------------------------------------------------------

    private fun frameFor(p: LightPlanner, captureFrame: Long, captureNanos: Long, picture: Picture, live: Boolean): IntArray? {
        val o = options
        if (!o.mood || picture == Picture.SPLASH) return null
        // the frame the picture is stamped with; before the stream has its clock, the capture's own
        val frame = if (captureFrame >= 0) captureFrame else feed.clock.frameAtNanos(captureNanos) ?: return null
        val t = test
        if (t != null && System.nanoTime() < testEndNanos) {
            // on the frames of the calibration clicks themselves, with no offset: what is measured is the whole way to the wall
            if (t.byFrame) return t.atFrame(frame, rate).also { if (live) note(it) }
            val elapsedMs = (captureNanos - testStartNanos) / 1_000_000L
            if (elapsedMs < 0) return null
            return t.at(elapsedMs).also { if (live) note(it) }
        }
        if (Settings.values.display == "listen") return null
        val ahead = frame + o.offsetMs.toLong() * rate / 1000
        val variant = if (picture == Picture.LYRICS) LightPlanner.Variant.BAND else LightPlanner.Variant.FULL
        val strength = if (o.tvPulses) LightPlanner.Strength.NONE else LightPlanner.Strength.of(o.strength)
        val out = IntArray(Zones.COUNT)
        p.frame(ahead, analyzer.snapshot, look, strength, variant, out)
        if (live) note(out)
        return out
    }

    private fun note(zones: IntArray) {
        lastZones = zones
        frames++
    }

    // ---- the analysis ---------------------------------------------------------------------------

    private fun loop() {
        val buf = ShortArray(CHUNK * 2)
        var gen = Long.MIN_VALUE
        var source: CaptureFrames? = null
        var cursor = 0L
        var lastTrack = 0L
        var lastLook = 0L
        var cpuFrom = SystemClock.currentThreadTimeMillis()
        var cpuAt = SystemClock.elapsedRealtime()
        while (running) {
            try {
                if (source == null || feed.generation != gen) {
                    gen = feed.generation
                    source = CaptureFrames(feed.buffer, gen)
                    cursor = feed.buffer.writeIndex
                    analyzer.reset(cursor, rememberedBpm())
                }
                val n = source.read(cursor, buf, CHUNK, 100)
                val mood = options.mood
                if (mood && !analysing) analyzer.reset(cursor, rememberedBpm()) // switched on: start the song's analysis afresh
                analysing = mood
                when {
                    n > 0 -> {
                        if (mood) analyzer.push(buf, n) // with `lights` = `tv` nothing is analysed
                        cursor += n
                    }
                    n == FrameSource.EPOCH -> source = null
                    n == FrameSource.LAPPED -> {
                        cursor = feed.buffer.writeIndex
                        analyzer.reset(cursor, rememberedBpm())
                    }
                    n == FrameSource.CLOSED -> Thread.sleep(200)
                }
                val now = System.nanoTime()
                if (now - lastTrack >= 200_000_000L) {
                    lastTrack = now
                    checkTrack(cursor)
                }
                if (now - lastLook >= 500_000_000L) {
                    lastLook = now
                    updateLook(now)
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
                AppLog.error(TAG, "the analysis of the lights failed", t)
                try {
                    Thread.sleep(500)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun rememberedBpm(): Double? = trackKey?.let { memory.get(it)?.bpm }?.takeIf { !it.isNaN() }

    private fun checkTrack(cursor: Long) {
        val np = AppState.nowPlaying()?.takeIf { it.hasTrack }
        val key = np?.key
        if (key != trackKey) {
            rememberSong()
            trackKey = key
            trackTitle = np?.title
            trackArtist = np?.artist
            hues = CoverHues.NONE
            huesKey = null
            pendingClass = null
            pendingCount = 0
            newPlay = true
            analyzer.reset(cursor, rememberedBpm())
            if (key != null) AppLog.add(TAG, "song ${np?.title} / ${np?.artist}: ${memory.get(key)?.let { "known, ${if (it.bpm.isNaN()) "no" else it.bpm.toInt().toString()} BPM, ${(it.override ?: it.cls)?.key}" } ?: "new"}")
        }
        if (np != null && key != null && huesKey != key) {
            val cover = try {
                covers.coverFor(np, MediaWatch.art)
            } catch (t: Throwable) {
                null
            }
            if (cover != null) {
                hues = huesOf(cover.bitmap)
                huesKey = key
            }
        }
    }

    private fun updateLook(now: Long) {
        val a = analyzer.snapshot
        val key = trackKey ?: ""
        val mem = memory.get(key)
        val songS = a.songSeconds(rate)
        // early in a song, what it was last time; then what it is
        val energy = if (songS >= 10 || mem == null || mem.bpm.isNaN()) a.energySong else mem.energy
        val clarity = if (a.locked || mem == null || mem.bpm.isNaN()) a.clarity else mem.clarity
        val mood = if (songS >= 15 || mem == null || mem.bpm.isNaN()) a.mood else mem.mood
        val chosen = Looks.choose(key, energy, clarity, mood, hues, mem?.override)
        val current = look
        val cls = if (current.key != key || chosen.cls == current.cls || mem?.override != null) {
            pendingClass = null
            pendingCount = 0
            chosen.cls
        } else {
            // a new class has to hold for two seconds (four readings) before it is shown
            if (pendingClass == chosen.cls) pendingCount++ else {
                pendingClass = chosen.cls
                pendingCount = 1
            }
            if (pendingCount >= 4) chosen.cls else current.cls
        }
        look = if (cls == chosen.cls) chosen else SongLook(chosen.key, cls, chosen.home, chosen.accent, chosen.multi, chosen.why + " (held at ${cls.key})")
        // the memory is written at the end of a song; while one plays, once a minute (it is a few hundred kB at most, on a small flash)
        if (trackKey != null && songS >= 20 && now - lastRememberNanos >= 60_000_000_000L) rememberSong()
        restyleIfNeeded(now)
    }

    /** Notes the song that plays (if it has played long enough to say something about it), with the class the app would give it itself. */
    private fun rememberSong() {
        val key = trackKey ?: return
        val a = analyzer.snapshot
        if (a.songSeconds(rate) < 20) return
        val own = Looks.classify(a.energySong, a.clarity, hues.colourful, null)
        memory.remember(key, trackTitle, trackArtist, if (a.locked) a.bpm else Double.NaN, a.energySong, a.clarity, a.mood, own, System.currentTimeMillis(), newPlay)
        newPlay = false
        lastRememberNanos = System.nanoTime()
    }

    /** The class at the moment of [a]: the song's, lifted or lowered by a section (as the planner does it). */
    private fun classAt(a: Analysis): LightClass {
        val e = a.sections.lastOrNull() ?: return look.cls
        val hold = if (a.periodFrames.isNaN()) 16.0 * rate else LightPlanner.HOLD_BEATS * a.periodFrames
        if (a.frame - e.frame > hold) return look.cls
        return if (e.up) look.cls.up() else look.cls.down()
    }

    /**
     * In Lyrics mode a new style for a new song, a new class (a section) or when the cover's colours arrive, at most one call a second,
     * through the app's Ambilight control. Not for every small drift of the tint: the TV's server is slow and the style is a whole call.
     */
    private fun restyleIfNeeded(now: Long) {
        val want = tvConfig() ?: return
        if (want == nativeSent || now - lastRestyleNanos < 1_000_000_000L) return
        val first = nativeSent == null
        nativeSent = want
        lastRestyleNanos = now
        if (first) return // the control puts it on when it switches the lights on
        restyles++
        val synced = AppState.synced ?: return
        tvExec.execute { synced.restyleAmbilight() }
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

    /** `/lights` and below (GET): the state, the history, the settings, the test pictures and his more or less colour. */
    fun handle(path: String, query: Map<String, String>): Reply = when (path) {
        "/lights" -> Reply.json(statusJson())
        "/lights/history" -> Reply.json(JSONArray().also { arr -> memory.all().forEach { arr.put(it.toJson()) } })
        "/lights/zones" -> Reply.json(JSONObject().put("zones", hexes(lastZones)).put("frames", frames))
        "/lights/set" -> set(query)
        "/lights/test" -> test(query)
        "/lights/colour" -> colour(query["step"] ?: "")
        else -> Reply.json(JSONObject().put("error", "not found"), 404)
    }

    private fun set(query: Map<String, String>): Reply {
        val before = options
        val (o, refused) = before.apply(query)
        options = o
        prefs.edit().putString(KEY_OPTIONS, o.toJson().toString()).apply()
        LightHook.bandShare = o.bandPct / 100.0
        if (o != before) AppLog.add(TAG, "settings ${o.toJson()}")
        if (o.mode != before.mode || o.syncedStyle != before.syncedStyle || o.nativeMono != before.nativeMono || o.nativeDuo != before.nativeDuo || o.nativeMulti != before.nativeMulti) {
            nativeSent = null // the next look update notes the new style; the call goes out now
            restyles++
            AppState.synced?.let { s -> tvExec.execute { s.restyleAmbilight() } }
        }
        return Reply.json(JSONObject().put("ok", refused.isEmpty()).put("options", o.toJson()).put("refused", JSONArray(refused)))
    }

    private fun test(query: Map<String, String>): Reply {
        val name = query["pattern"] ?: ""
        if (name == "off" || name.isEmpty()) {
            test = null
            return Reply.json(JSONObject().put("ok", true).put("pattern", "off"))
        }
        if (name !in LightPatterns.NAMES) return Reply.json(JSONObject().put("ok", false).put("error", "pattern: one of ${LightPatterns.NAMES}"), 400)
        val seconds = (query["seconds"]?.toIntOrNull() ?: 20).coerceIn(1, 600)
        val p = LightPatterns(
            name,
            LightPatterns.parseColor(query["color"]) ?: Colors.rgb(255, 255, 255),
            query["bpm"]?.toDoubleOrNull()?.coerceIn(30.0, 240.0) ?: 120.0,
            query["step_ms"]?.toIntOrNull()?.coerceIn(50, 10_000) ?: 250,
        )
        val now = System.nanoTime()
        testStartNanos = now
        testEndNanos = now + seconds * 1_000_000_000L + 3_000_000_000L // the pictures of the last seconds are drawn about 2 s after their capture
        test = p
        AppLog.add(TAG, "test picture $name for $seconds s")
        return Reply.json(JSONObject().put("ok", true).put("pattern", name).put("seconds", seconds).put("start_capture_ms", now / 1_000_000L).put("zones_at_start", hexes(p.at(0))))
    }

    /**
     * His choice for the song that plays: one class less or more than it shows now, the one it shows ("keep": just right, which is what
     * tells the review that the app chose well), or back to the automatic one.
     */
    private fun colour(step: String): Reply {
        val key = trackKey ?: return Reply.json(JSONObject().put("ok", false).put("error", "no song plays"), 409)
        val shown = look.cls
        val cls = when (step) {
            "less" -> shown.down()
            "more" -> shown.up()
            "keep" -> shown
            "auto" -> null
            else -> return Reply.json(JSONObject().put("ok", false).put("error", "step: less, keep, more or auto"), 400)
        }
        memory.override(key, cls, System.currentTimeMillis())
        AppLog.add(TAG, "his choice for $trackTitle: ${cls?.key ?: "automatic"}")
        updateLook(System.nanoTime())
        return Reply.json(JSONObject().put("ok", true).put("class", look.cls.key).put("override", cls?.key ?: JSONObject.NULL))
    }

    /** What the phone page shows of the lights: on or off, the class and the tempo. */
    fun phoneJson(): JSONObject {
        val a = analyzer.snapshot
        val mem = trackKey?.let { memory.get(it) }
        return JSONObject()
            .put("on", options.mood)
            .put("class", look.cls.key)
            .put("override", mem?.override?.key ?: JSONObject.NULL)
            .put("bpm", if (a.locked) Math.round(a.bpm) else JSONObject.NULL)
    }

    fun statusJson(): JSONObject {
        val a = analyzer.snapshot
        val o = JSONObject()
        o.put("options", options.toJson())
        o.put("track", JSONObject().put("key", trackKey ?: JSONObject.NULL).put("title", trackTitle ?: JSONObject.NULL).put("artist", trackArtist ?: JSONObject.NULL))
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
        val l = look
        o.put("look", JSONObject().put("class", l.cls.key).put("class_now", planner.classNow.key).put("home", hex(l.home)).put("accent", hex(l.accent)).put("multi", hexes(l.multi)).put("why", l.why))
        o.put("cover", JSONObject().put("grey", hues.grey).put("colourful", hues.colourful).put("hues", JSONArray().also { arr -> hues.clusters.forEach { c -> arr.put(JSONObject().put("hue", Math.round(c.hue)).put("share", round(c.share, 2))) } }))
        o.put("painter", JSONObject().put("frames", frames).put("pulsing", planner.pulsing).put("pulses", planner.pulsesAllowed).put("pulses_held", planner.pulsesHeld).put("zones", hexes(lastZones)))
        o.put("tv", JSONObject().put("config", tvConfig() ?: JSONObject.NULL).put("restyles", restyles))
        val t = test
        o.put("test", if (t != null && System.nanoTime() < testEndNanos) JSONObject().put("pattern", t.pattern).put("left_s", (testEndNanos - System.nanoTime()) / 1_000_000_000L) else JSONObject.NULL)
        o.put("cpu_ms_per_s", if (cpuMsPerS.isNaN()) JSONObject.NULL else round(cpuMsPerS, 1))
        return o
    }

    private fun hex(c: Int): String = String.format("#%06x", c and 0xFFFFFF)

    private fun hexes(cs: IntArray): JSONArray = JSONArray().also { arr -> cs.forEach { arr.put(hex(it)) } }

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
    }
}
