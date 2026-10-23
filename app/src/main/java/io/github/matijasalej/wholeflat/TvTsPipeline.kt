package io.github.matijasalej.wholeflat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import io.github.matijasalej.wholeflat.capture.CaptureFeed
import io.github.matijasalej.wholeflat.capture.CaptureFrames
import io.github.matijasalej.wholeflat.sync.Adts
import io.github.matijasalej.wholeflat.sync.CalibrationClicks
import io.github.matijasalej.wholeflat.sync.ClickTrack
import io.github.matijasalej.wholeflat.sync.FrameLabels
import io.github.matijasalej.wholeflat.sync.FrameTimes
import io.github.matijasalej.wholeflat.sync.H264
import io.github.matijasalej.wholeflat.sync.LateVideoGate
import io.github.matijasalej.wholeflat.sync.Lateness
import io.github.matijasalej.wholeflat.sync.LyricsLayout
import io.github.matijasalej.wholeflat.sync.Picture
import io.github.matijasalej.wholeflat.sync.SceneFade
import io.github.matijasalej.wholeflat.sync.SlewClock
import io.github.matijasalej.wholeflat.sync.TsFeed
import io.github.matijasalej.wholeflat.sync.TsHistory
import io.github.matijasalej.wholeflat.sync.TsMuxer
import io.github.matijasalej.wholeflat.sync.TsWriter
import io.github.matijasalej.wholeflat.sync.TvPicture
import io.github.matijasalej.wholeflat.sync.TvVideoSpec
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

/**
 * The live transport stream for the TV: the captured audio (AAC) and the lyrics picture (H.264), muxed and written into a ring that the
 * connections of the TV read from. One of these lives as long as one grab of the TV.
 *
 * **The clock.** Frame f of the capture is to sound in the TV [holdNanos] after its capture plus the TV's own delay; so it is *written*
 * at capture(f) + hold, and the PTS of a frame is the position of that capture frame in the stream (sample counted, so the audio never
 * jitters). The stream is constant in rate (9 Mbit/s, padded with null packets: see [TsMuxer]) and its clock is the number of the packet
 * slot, which follows the capture clock: the slot that is due at a time is the one that goes with the capture frame of that time less the
 * hold, and the PCR of a slot is what the position in the stream says, a lead (700 ms, as ffmpeg writes it) behind the PTS of what is sent then.
 * The pictures are drawn at 30 per second on the wall clock, each labelled with the capture time whose lyric state it shows (its drawing
 * time less the hold plus the [VIDEO_BUDGET_NANOS] the encoder is given), so the line that is sung in the sound is the line that is lit in
 * the picture. The hold is fixed for the life of the stream: the TV's player follows the PTS and PCR of what it was given, so a hold that
 * moves would move the picture and the sound against the other room.
 *
 * **Video never holds up the sound.** The audio, the clock and the padding go out on the slot clock whatever the video does; a picture
 * is written when it is due, which is half a second after it was drawn (the budget of a slow encoder), and one that still comes later
 * than the TV could take it is dropped with what refers to it, up to the next key picture ([LateVideoGate]); a TV that is given a
 * picture after its time has been seen to freeze and to move its sound against the other room. The change of track is a cross-fade
 * ([SceneFade]), not a cut, so that the encoder is never asked for a picture that is new in every pixel.
 *
 * **The warm-up.** Until [goLive] the stream carries the splash and silence, and an IDR picture every 200 ms (a static picture costs
 * nothing), so that the connections the TV makes while it settles can start where the policy says whatever the moment. [goLive]
 * switches the audio at a block boundary and the picture at an IDR, both at the capture time of the first music block.
 *
 * Threads: audio (feeds the AAC encoder at due time), video (draws and owns the video encoder), writer (muxes in order of due time
 * into the ring), a watch (which only tells when the app was kept from running), and the output threads of the two encoders. The threads
 * of the stream run at the priority of an audio thread ([ThreadBoost]).
 */
class TvTsPipeline(
    private val context: Context,
    private val feed: CaptureFeed,
    /** From the capture of a frame to its write (ns), fixed for the life of the stream. */
    private val holdNanos: Long,
    private val scenes: SceneSource,
    /** Whether the screen shows the lyrics (the display "Screen + Ambilight"); else the picture is black. */
    private val showLyrics: () -> Boolean,
    private val params: () -> Params,
    /**
     * The stream carries the audio alone (the setting `tv_audio_only` while the screen is off): no encoder, no drawing, no video PID; the clock
     * is on the audio, every table of the stream is a place to start, and the rate and the PCR lead are those of the video stream.
     */
    val audioOnly: Boolean = false,
) : TsFeed {
    class Params(val burstMs: Int, val pcrLeadMs: Int, val render: String, val video: TvVideoSpec = TvVideoSpec.DEFAULT)

    /** What [goLive] decided: the first capture frame of the music and when it is written to the TV. */
    class LiveInfo(val firstFrame: Long, val dueNanos: Long)

    /** What the video is made as (the setting `tv_video`), fixed for the life of the stream. */
    private val spec = params().video
    private val frameNanos = 1_000_000_000L / spec.fps

    override val history = TsHistory(maxOf(HISTORY_BYTES, spec.muxRate / 8L * 14), HISTORY_NANOS)
    private val rate = feed.rate
    private val mux = TsMuxer(pcrPid = if (audioOnly) TsMuxer.AUDIO_PID else TsMuxer.VIDEO_PID, muxRate = spec.muxRate, hasVideo = !audioOnly)
    private val writer = TsWriter(mux, history) { t -> slotOf(t) }

    /** Lets a picture into the stream only while it can still reach the TV in time: the PCR lead less [GATE_ROOM_MS]. */
    private val gate = LateVideoGate(spec.muxRate, maxOf(MIN_GATE_MS, params().pcrLeadMs - GATE_ROOM_MS) * 1_000_000L)

    init {
        writer.gate = gate
    }

    private val fade = SceneFade<TvScene>()
    private val writerLate = Lateness(50.0)
    private val watchLate = Lateness(100.0)
    private var lastKeyAsk = 0L

    @Volatile
    private var running = false
    private var f0 = 0L
    private var cap0Nanos = 0L
    private lateinit var slew: SlewClock
    private var leadTicks = 0L
    private var startedNanos = 0L

    // the audio chain
    private var aenc: TvAudioEncoder? = null
    private val audioOut = AtomicLong()

    @Volatile
    private var audioNextBlock = 0L

    @Volatile
    private var liveFromBlock = Long.MAX_VALUE

    @Volatile
    private var liveFromNanos = Long.MAX_VALUE

    @Volatile
    var live = false
        private set

    // the video chain
    private var venc: TvVideoEncoder? = null
    private var renderer: LyricsRenderer? = null
    private var lastVideoPts = Long.MIN_VALUE
    private var lastVideoPtsOut = Long.MIN_VALUE
    private var lastVideoDue = Long.MIN_VALUE
    private val labels = FrameLabels()
    private val startLatch = CountDownLatch(1)

    @Volatile
    private var startError: String? = null

    @Volatile
    var picture = Picture.SPLASH
        private set

    private val threads = ArrayList<Thread>()

    private enum class Boost { NONE, URGENT, DISPLAY }

    // what the status says
    @Volatile
    private var captureTrouble: String? = null
    private val shortBlocks = AtomicLong()
    private val unlabeledFrames = AtomicLong()
    private val skippedFrames = AtomicLong()
    private val renderFailures = AtomicLong()
    private val clickBlocks = AtomicLong()
    private val syncRequestsForPicture = AtomicLong()

    @Volatile
    private var lastStats = ""

    private val previewDrawer by lazy { SceneDrawer(context) }

    // ---- TsFeed ----------------------------------------------------------------------------------

    override fun leadNanos(): Long = params().burstMs * 1_000_000L

    /**
     * The clock of this stream: when capture frame f is to be written to the TV is this plus the hold, and the kitchen counts its lag from
     * it in Synced mode (what the capture's own timestamps do, steps of a period or two included, does not move the rooms against each other).
     * Null before the stream has started.
     */
    fun frameTimes(): FrameTimes? = if (::slew.isInitialized) slew else null

    override val streamBitsPerSecond: Int get() = spec.muxRate

    override fun requestSync() {
        venc?.requestSync()
    }

    // ---- life cycle ------------------------------------------------------------------------------

    /** Starts the encoders and the threads. Null if it runs; else why it does not (the TV is then given the audio-only stream). */
    fun start(): String? {
        val now = System.nanoTime()
        val p = params()
        leadTicks = p.pcrLeadMs * 90L
        // the first block of audio is the frame that was captured one hold before the first write, which is about START_DELAY from now
        val target = now + START_DELAY_NANOS - holdNanos
        val exact = feed.clock.frameAtNanosExact(target)
        val newest = feed.buffer.writeIndex
        f0 = maxOf(feed.buffer.validStart + rate, (exact ?: (newest - (holdNanos - START_DELAY_NANOS) * rate / 1e9)).toLong())
        cap0Nanos = feed.clock.nanosOfFrame(f0) ?: (now - (newest - f0) * 1_000_000_000L / rate)
        slew = SlewClock(rate, f0, cap0Nanos, SLEW_PPM, SLEW_STEP_MS)
        startedNanos = now
        // slot 0 is the write of the first audio block, whose PCR is a lead behind its PTS
        mux.pcrBase27 = (PTS_BASE - leadTicks) * 300L

        val audio = TvAudioEncoder(rate, AUDIO_BITRATE) { raw -> onAudioFrame(raw) }
        try {
            audio.start()
        } catch (t: Throwable) {
            return "the AAC encoder does not start: $t"
        }
        aenc = audio
        running = true
        threads.add(thread("wf-ts-writer", Boost.URGENT) { writerLoop() })
        threads.add(thread("wf-ts-audio", Boost.URGENT) { audioLoop() })
        if (audioOnly) startLatch.countDown() else threads.add(thread("wf-ts-video", Boost.DISPLAY) { videoLoop() })
        threads.add(thread("wf-ts-watch") { watchLoop() })
        if (!startLatch.await(START_WAIT_S, TimeUnit.SECONDS)) startError = startError ?: "the video encoder did not start in $START_WAIT_S s"
        val err = startError
        if (err != null) {
            stop()
            return err
        }
        AppLog.add(
            TAG,
            (if (audioOnly) "the audio-only stream runs: ${audio.name}, ${spec.muxRate / 1000} kbit/s of stream, " else "the lyrics video runs: ${venc?.name} (${venc?.profile}), render ${renderer?.path?.key}, ${audio.name}, ") +
                "hold ${holdNanos / 1_000_000L} ms, PCR lead ${p.pcrLeadMs} ms, new connections start ${p.burstMs} ms behind live, first audio frame $f0",
        )
        return null
    }

    fun stop() {
        if (!running && threads.isEmpty()) return
        running = false
        for (t in threads) t.interrupt()
        for (t in threads) {
            try {
                t.join(1_500)
            } catch (e: InterruptedException) {
                // going
            }
        }
        if (threads.any { it.isAlive }) {
            // a drawing that is stuck on a surface that the encoder no longer takes from is freed by ending the encoder
            AppLog.add(TAG, "a thread of the lyrics video does not end, the encoders are stopped under it")
            venc?.stop()
        }
        threads.clear()
        aenc?.stop()
        aenc = null
        history.close()
        AppLog.add(TAG, "the lyrics video stopped: ${lastStats.ifEmpty { "no statistics" }}")
    }

    private fun thread(name: String, boost: Boost = Boost.NONE, body: () -> Unit): Thread = Thread({
        when (boost) {
            Boost.URGENT -> ThreadBoost.urgent()
            Boost.DISPLAY -> ThreadBoost.display()
            Boost.NONE -> Unit
        }
        try {
            body()
        } catch (t: Throwable) {
            if (running) {
                AppLog.error(TAG, "$name failed", t)
                captureTrouble = "$name: $t"
            }
        }
    }, name).also {
        it.isDaemon = true
        it.start()
    }

    /** Parks until [targetNanos]; false if the pipeline was stopped meanwhile. */
    private fun sleepUntil(targetNanos: Long): Boolean {
        while (running) {
            val remaining = targetNanos - System.nanoTime()
            if (remaining <= 0) return true
            LockSupport.parkNanos(minOf(remaining, 10_000_000L))
        }
        return false
    }

    // ---- the clock -------------------------------------------------------------------------------

    /**
     * The slot of the stream that is due at [nowNanos]: the packets of the stream go out at the mux rate, counted on the capture clock (the
     * frame of the capture that is at the live edge now is the one captured one hold ago), from slot 0 at the first audio block.
     */
    private fun slotOf(nowNanos: Long): Long {
        val frames = slew.frameAt(nowNanos - holdNanos)
        return Math.floor((frames - f0) / rate * mux.slotsPerSecond).toLong()
    }

    private fun audioPts(block: Long): Long = PTS_BASE + Math.round(block * BLOCK * 90_000.0 / rate)

    private fun videoPts(captureNanos: Long): Long {
        var p = PTS_BASE + Math.round((slew.frameAt(captureNanos) - f0) * 90_000.0 / rate)
        if (p <= lastVideoPts) p = lastVideoPts + 1
        lastVideoPts = p
        return p
    }

    // ---- going live ------------------------------------------------------------------------------

    /**
     * The warm-up is over: from a block a little ahead of the audio that is being fed the stream carries the music, and the picture
     * changes to the lyrics (or black) at an IDR at the same capture time.
     */
    fun goLive(): LiveInfo {
        val block = audioNextBlock + 2
        val first = f0 + block * BLOCK
        val captureT = slew.nanosOf(first)
        liveFromNanos = captureT
        liveFromBlock = block
        live = true
        return LiveInfo(first, captureT + holdNanos)
    }

    // ---- audio -----------------------------------------------------------------------------------

    private fun audioLoop() {
        val pcm = ShortArray(BLOCK * 2)
        val tmp = ShortArray(BLOCK * 2)
        val source = CaptureFrames(feed.buffer, feed.generation)
        val encoder = aenc ?: return
        val pipelineNanos = { (feed.pipelineMs().takeUnless { it.isNaN() } ?: 80.0) * 1e6 }
        var block = 0L
        while (running) {
            val first = f0 + block * BLOCK
            val due = slew.nanosOf(first) + holdNanos
            // fed early enough for the encoder to have its output by the time it is due, but not before the frames have been captured
            val lead = (holdNanos - pipelineNanos() - 45_000_000.0).toLong().coerceIn(30_000_000L, 250_000_000L)
            if (!sleepUntil(due - lead)) break
            audioNextBlock = block
            if (block >= liveFromBlock) {
                if (CalibrationClicks.active()) {
                    ClickTrack.fill(pcm, BLOCK, first, rate, ClickTrack.TV_HZ)
                    clickBlocks.incrementAndGet()
                } else {
                    var got = 0
                    while (got < BLOCK && running) {
                        val n = source.read(first + got, tmp, BLOCK - got, maxOf(1L, (due - System.nanoTime()) / 1_000_000L).toInt().coerceAtMost(30))
                        if (n < 0) {
                            captureTrouble = if (n == -3) "the capture started again" else if (n == -2) "the capture frames are gone" else "the capture closed"
                            break
                        }
                        if (n == 0) {
                            if (System.nanoTime() > due) break
                            continue
                        }
                        System.arraycopy(tmp, 0, pcm, got * 2, n * 2)
                        got += n
                    }
                    if (got < BLOCK) {
                        java.util.Arrays.fill(pcm, got * 2, BLOCK * 2, 0.toShort())
                        shortBlocks.incrementAndGet()
                    }
                }
            } else {
                java.util.Arrays.fill(pcm, 0.toShort())
            }
            encoder.queue(pcm, BLOCK, block * BLOCK * 1_000_000L / rate)
            block++
        }
    }

    private fun onAudioFrame(raw: ByteArray) {
        val k = audioOut.getAndIncrement()
        val due = slew.nanosOf(f0 + k * BLOCK) + holdNanos
        writer.submitAudio(due, Adts.frame(raw, 0, raw.size, rate, 2), audioPts(k))
    }

    // ---- video -----------------------------------------------------------------------------------

    private fun videoLoop() {
        var started = false
        try {
            for (path in LyricsRenderer.Path.order(params().render)) {
                try {
                    startVideoChain(path)
                    started = true
                    break
                } catch (t: Throwable) {
                    AppLog.add(TAG, "the picture cannot be put on the encoder the ${path.key} way: $t")
                    startError = "the ${path.key} way: $t"
                    stopVideoChain()
                }
            }
            if (!started) return
            startError = null
        } finally {
            startLatch.countDown()
        }
        var n = 1L
        val t0 = System.nanoTime()
        var wasLive = false
        var lastRefresh = t0
        var consecutiveFailures = 0
        try {
            while (running) {
                val w = t0 + n * frameNanos
                if (!sleepUntil(w)) break
                val now = System.nanoTime()
                wakeLateMs = (now - w) / 1e6
                if (now - w > 4 * frameNanos) {
                    // far behind (a stall): the frames that could not be drawn in time are not drawn late
                    val skipped = (now - w) / frameNanos
                    skippedFrames.addAndGet(skipped)
                    n += skipped + 1
                    continue
                }
                val c = w - holdNanos + VIDEO_BUDGET_NANOS
                val liveNow = c >= liveFromNanos
                val pic = TvPicture.choose(warming = !liveNow, lyrics = showLyrics())
                if (gate.waitingForKey && now - lastKeyAsk >= KEY_ASK_EVERY_NANOS) {
                    // pictures were dropped for being late and the ones after them refer to them: the TV needs a key picture to go on from
                    lastKeyAsk = now
                    venc?.requestSync()
                    syncRequestsForPicture.incrementAndGet()
                }
                if (liveNow && !wasLive) {
                    wasLive = true
                    venc?.requestSync() // the music begins with a key picture
                    syncRequestsForPicture.incrementAndGet()
                    lastRefresh = now
                } else if (!liveNow && now - lastRefresh >= WARMUP_IDR_EVERY_NANOS) {
                    // a static picture costs nothing as a key picture, and a connection can start anywhere
                    venc?.requestSync()
                    syncRequestsForPicture.incrementAndGet()
                    lastRefresh = now
                } else if (pic != picture && picture != Picture.SPLASH) {
                    venc?.requestSync() // the picture changed (the display was changed)
                    syncRequestsForPicture.incrementAndGet()
                }
                try {
                    renderFrame(c, pic)
                    consecutiveFailures = 0
                } catch (t: Throwable) {
                    renderFailures.incrementAndGet()
                    consecutiveFailures++
                    if (consecutiveFailures == 1 || consecutiveFailures % 100 == 0) AppLog.error(TAG, "a picture could not be drawn ($consecutiveFailures in a row)", t)
                    if (consecutiveFailures >= 30) {
                        captureTrouble = "the pictures cannot be drawn: $t"
                        break
                    }
                }
                picture = pic
                n++
            }
        } finally {
            stopVideoChain()
        }
    }

    private fun startVideoChain(path: LyricsRenderer.Path) {
        val enc = TvVideoEncoder(spec) { au, ptsUs, key -> onVideoFrame(au, ptsUs, key) }
        val surface = enc.start()
        venc = enc
        val r = LyricsRenderer(context, surface, path, spec.width, spec.height)
        renderer = r
        // the first picture proves that the way works: the splash, as the stream begins
        renderFrame(System.nanoTime() - holdNanos + VIDEO_BUDGET_NANOS, Picture.SPLASH)
    }

    private fun stopVideoChain() {
        try {
            renderer?.close()
        } catch (t: Throwable) {
            // closing
        }
        renderer = null
        venc?.stop()
        venc = null
    }

    private fun renderFrame(captureNanos: Long, pic: Picture) {
        val r = renderer ?: return
        val pts = videoPts(captureNanos)
        val scene = if (pic == Picture.BLACK) null else scenes.scene(captureNanos)
        var from: TvScene? = null
        var alpha = 1f
        if (pic == Picture.LYRICS && scene != null) {
            // a new track comes in over the old one in about a second instead of replacing it in one picture
            val step = fade.step(scene.key, scene, captureNanos)
            from = step.from
            alpha = step.alpha
        } else {
            fade.reset()
        }
        val stamp = r.render(pic, scene, System.nanoTime(), from, alpha)
        labels.add(stamp, captureNanos, pts)
        synchronized(drawn) {
            drawn.addLast(Drawn(stamp / 1_000_000L, stamp / 1000L, r.lastRenderMs, wakeLateMs, scene?.positionMs ?: -1L))
            while (drawn.size > RECENT_DRAWN) drawn.removeFirst()
        }
    }

    /** One picture drawn: when it was put on the surface (ms of System.nanoTime) and its time for the encoder (us), how long drawing took, and how late the loop woke for it (ms). */
    private class Drawn(val wallMs: Long, val stampUs: Long, val drawMs: Double, val lateMs: Double, val positionMs: Long)

    private val drawn = java.util.ArrayDeque<Drawn>()

    @Volatile
    private var wakeLateMs = 0.0

    /**
     * The last ten seconds or so of the video as CSV lines, for a look at where a scroll goes wrong: `draw,wall_ms,stamp_us,draw_ms,late_ms,pos_ms`
     * for every picture drawn (late: how long after its time the loop woke; pos: the position in the song that the picture was made for, which
     * should advance by 33.3 ms from one picture to the next at 30 a second, or the scroll jerks) and `enc,wall_ms,pts_us,bytes,key,encode_ms` for every picture the
     * encoder gave (encode: from the stamp of the picture to its bytes). The pts of an `enc` line is the stamp of a `draw` line.
     */
    fun framesCsv(): String {
        val sb = StringBuilder()
        synchronized(drawn) {
            for (d in drawn) {
                sb.append("draw,").append(d.wallMs).append(',').append(d.stampUs).append(',')
                    .append(String.format(java.util.Locale.US, "%.1f,%.1f,%d", d.drawMs, d.lateMs, d.positionMs)).append('\n')
            }
        }
        sb.append(venc?.recentCsv() ?: "")
        return sb.toString()
    }

    private fun onVideoFrame(au: ByteArray, ptsUs: Long, key: Boolean) {
        val label = labels.find(ptsUs * 1000L)
        val pts: Long
        val due: Long
        if (label != null) {
            pts = label.second
            due = label.first + holdNanos
        } else {
            // a picture that was not drawn by us (the encoder repeated one): it follows the last, in the stream and in its time (the
            // pictures wait for their time, so "now" would put it ahead of the one it repeats)
            unlabeledFrames.incrementAndGet()
            pts = (if (lastVideoPtsOut == Long.MIN_VALUE) PTS_BASE else lastVideoPtsOut) + spec.frameTicks
            due = if (lastVideoDue == Long.MIN_VALUE) System.nanoTime() else lastVideoDue + frameNanos
        }
        if (pts <= lastVideoPtsOut) return // never two pictures with one time
        lastVideoPtsOut = pts
        lastVideoDue = due
        val isKey = key || H264.isKey(au)
        val unit = H264.accessUnit(au, 0, au.size, venc?.parameterSets, isKey)
        writer.submitVideo(due, unit, pts, isKey)
    }

    // ---- the writer ------------------------------------------------------------------------------

    private fun writerLoop() {
        var lastLog = System.nanoTime()
        var lastBytes = 0L
        var lastFrames = 0L
        var lastEncoded = 0L
        var lastNulls = 0L
        var lastShort = 0L
        var starvedPeriods = 0
        var lastSlewNanos = 0L
        var expectedWake = 0L
        while (running) {
            val now = System.nanoTime()
            if (expectedWake != 0L) writerLate.note((now - expectedWake) / 1e6)
            if (now - lastSlewNanos >= 250_000_000L) {
                lastSlewNanos = now
                slew.update(now - holdNanos, feed.clock.frameAtNanosExact(now - holdNanos))
            }
            val next = writer.step(now)
            if (now - lastLog >= STATS_EVERY_NANOS) {
                val seconds = (now - lastLog) / 1e9
                val s = writer.takeStats()
                val frames = venc?.framesOut ?: 0L
                val period = venc?.takePeriod()
                val encodeAvg = period?.first ?: 0.0
                val encodeMax = period?.second ?: 0.0
                lastEncodeAvg = encodeAvg
                lastEncodeMax = encodeMax
                lastStats = String.format(
                    java.util.Locale.US,
                    "video %.1f fps, the encoder gave %.0f kbit/s, the stream %.0f kbit/s of which %.0f %% padding, a picture takes %.1f ms to draw (%s) " +
                        "and %.0f ms to encode (at most %.0f), queued for sending late: audio %.1f ms avg %.0f max, video %.1f avg %.0f max; " +
                        "%d waiting (%d packets, %d of them video), %d KB in the ring, %d short audio blocks, %d unlabeled and %d skipped pictures, " +
                        "clock %.2f ms from the plain count, %s",
                    (frames - lastFrames) / seconds, ((venc?.bytesOut ?: 0L) - lastEncoded) * 8 / seconds / 1000.0,
                    (writer.bytes - lastBytes) * 8 / seconds / 1000.0, (mux.nullPackets - lastNulls) * 100.0 / maxOf(1L, (writer.bytes - lastBytes) / TsMuxer.PACKET),
                    renderer?.averageRenderMs ?: 0.0, renderer?.path?.key ?: "-", encodeAvg, encodeMax,
                    s.audioLateAvgMs, s.audioLateMaxMs, s.videoLateAvgMs, s.videoLateMaxMs, s.queued, writer.packetsWaiting, writer.videoPacketsWaiting,
                    history.heldBytes / 1024, shortBlocks.get(), unlabeledFrames.get(), skippedFrames.get(), slew.adjustMs, if (live) "live" else "warming up",
                )
                AppLog.add(TAG, lastStats)
                noteHeldUp(s)
                // A stream whose clock runs ahead of the capture's (the capture slower than the system clock by more than the clock follows)
                // meets frames that do not exist yet, block after block. A hiccup of the capture is a few blocks; this is the stream starved.
                val shortNow = shortBlocks.get()
                if (live && shortNow - lastShort >= STARVED_BLOCKS) starvedPeriods++ else starvedPeriods = 0
                lastShort = shortNow
                if (starvedPeriods >= 2 && captureTrouble == null) {
                    captureTrouble = "the stream is ahead of the capture ($shortNow short audio blocks so far)"
                    AppLog.add(TAG, "$captureTrouble: it is made again")
                }
                lastLog = now
                lastBytes = writer.bytes
                lastFrames = frames
                lastEncoded = venc?.bytesOut ?: 0L
                lastNulls = mux.nullPackets
            }
            val wait = next - System.nanoTime()
            if (wait > 0) {
                val park = minOf(wait, 5_000_000L)
                expectedWake = System.nanoTime() + park
                LockSupport.parkNanos(park)
            } else {
                expectedWake = 0L
            }
        }
    }

    @Volatile
    private var lastEncodeAvg = 0.0

    @Volatile
    private var lastEncodeMax = 0.0

    @Volatile
    private var heldUp = ""

    /**
     * Says in the log, when a period (5 s) had a sign of it, that the stream was held up and what was seen: the writer waking up late (and
     * making up the slots that passed), the app kept from running by the system, pictures dropped for being late, and the state of the box.
     */
    private fun noteHeldUp(s: TsWriter.Stats) {
        val wl = writerLate.take()
        val wt = watchLate.take()
        if (wl.over == 0 && wt.over == 0 && s.videoDropped == 0 && s.maxStepSlots <= HELD_UP_SLOTS) return
        heldUp = String.format(
            java.util.Locale.US,
            "the writer woke up %.0f ms late at most (%d times over 50 ms) and made up %d slots (%.0f ms of stream) in one step at most; the app was kept " +
                "from running for %.0f ms at most (%d times over 100 ms); %d pictures were dropped for being late (%d times so far); %s",
            wl.maxMs, wl.over, s.maxStepSlots, s.maxStepSlots * 1000.0 / mux.slotsPerSecond, wt.maxMs, wt.over, s.videoDropped, gate.episodes, BoxStats.brief(),
        )
        AppLog.add(TAG, "WARNING the stream was held up in the last 5 s: $heldUp")
    }

    // ---- the watch -------------------------------------------------------------------------------

    /**
     * Sleeps 20 ms at a time at the normal priority and tells when a sleep took much longer: the system did not run this app for that long
     * (a busy box, memory being reclaimed, the garbage collector). If the encoder's pictures and the writes to the TV are late at the same
     * moment as this, it was the box and not the stream.
     */
    private fun watchLoop() {
        var prev = System.nanoTime()
        var lastLog = 0L
        var gc0 = BoxStats.blockingGc()
        while (running) {
            LockSupport.parkNanos(WATCH_TICK_NANOS)
            val now = System.nanoTime()
            val lateMs = (now - prev - WATCH_TICK_NANOS) / 1e6
            prev = now
            watchLate.note(lateMs)
            if (lateMs >= STALL_LOG_MS && now - lastLog > 1_000_000_000L) {
                lastLog = now
                val gc1 = BoxStats.blockingGc()
                val gc = if (gc0 != null && gc1 != null) ", the garbage collector held the app up ${gc1.first - gc0.first} times for ${gc1.second - gc0.second} ms since the last stall" else ""
                gc0 = gc1
                AppLog.add(TAG, "WARNING this app was not run by the system for ${lateMs.toLong()} ms (a sleep of 20 ms took ${lateMs.toLong() + 20} ms): ${BoxStats.brief()}$gc")
            }
        }
    }

    /** The state of the stream in a line, for the warning the connection to the TV writes when the data it gave was old (see `KitchenStream`). */
    override fun health(): String = String.format(
        java.util.Locale.US,
        "encoder %.0f ms per picture on average and %.0f at most in the last 5 s, %d pictures skipped, %d dropped as too late (%d times, %d sent more than 150 ms late); " +
            "the writer woke up %.0f ms late at worst, the app was kept from running for %.0f ms at worst; %s",
        lastEncodeAvg, lastEncodeMax, skippedFrames.get(), gate.dropped, gate.episodes, gate.sentLate, writerLate.worstMs, watchLate.worstMs, BoxStats.brief(),
    )

    // ---- what the status and the debug pages want ------------------------------------------------

    /** A small JPEG of what the picture shows now (a fresh drawing of the scene, on a smaller canvas), for `/tv/preview.jpg`. */
    fun previewJpeg(): ByteArray? {
        if (audioOnly) return null
        return try {
            val c = System.nanoTime() - holdNanos + VIDEO_BUDGET_NANOS
            val pic = picture
            val scene = if (pic == Picture.BLACK) null else scenes.scene(c)
            val bmp = Bitmap.createBitmap(LyricsLayout.W / 2, LyricsLayout.H / 2, Bitmap.Config.ARGB_8888)
            synchronized(previewDrawer) {
                // the drawer scales the 1920 x 1080 design to the canvas it is given
                previewDrawer.draw(Canvas(bmp), pic, scene)
            }
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
            bmp.recycle()
            out.toByteArray()
        } catch (t: Throwable) {
            AppLog.error(TAG, "the preview could not be made", t)
            null
        }
    }

    /** The reason the stream cannot go on (the capture ended, the pictures cannot be drawn), null while it is well. */
    fun trouble(): String? = captureTrouble

    fun statusJson(): JSONObject {
        val o = JSONObject()
        val v = venc
        o.put("running", running)
        o.put("live", live)
        o.put("picture", picture.key)
        o.put("video_spec", spec.text())
        o.put("audio_only", audioOnly)
        o.put("video_encoder", v?.name ?: JSONObject.NULL)
        o.put("video_profile", v?.profile ?: JSONObject.NULL)
        o.put("audio_encoder", aenc?.name ?: JSONObject.NULL)
        o.put("render_path", renderer?.path?.key ?: JSONObject.NULL)
        o.put("render_ms_avg", Math.round((renderer?.averageRenderMs ?: 0.0) * 10.0) / 10.0)
        o.put("render_ms_last", Math.round((renderer?.lastRenderMs ?: 0.0) * 10.0) / 10.0)
        o.put("render_failures", renderFailures.get())
        o.put("video_frames_out", v?.framesOut ?: 0L)
        o.put("video_key_frames_out", v?.keyFramesOut ?: 0L)
        o.put("video_sync_requests", v?.syncRequests ?: 0L)
        o.put("video_encode_ms_avg", Math.round((v?.latencyAvgMs ?: 0.0) * 10.0) / 10.0)
        o.put("video_encode_ms_max", Math.round((v?.latencyMaxMs ?: 0.0) * 10.0) / 10.0)
        o.put("audio_frames_in", aenc?.framesIn ?: 0L)
        o.put("audio_frames_out", aenc?.framesOut ?: 0L)
        o.put("audio_blocks_dropped", aenc?.droppedBlocks ?: 0L)
        o.put("short_audio_blocks", shortBlocks.get())
        o.put("click_blocks", clickBlocks.get())
        o.put("unlabeled_pictures", unlabeledFrames.get())
        o.put("encoder_clock_offset_ms", if (labels.learnedOffset) labels.epochOffsetNanos / 1_000_000L else 0L)
        o.put("skipped_pictures", skippedFrames.get())
        o.put("video_budget_ms", VIDEO_BUDGET_NANOS / 1_000_000L)
        o.put("video_dropped", gate.dropped)
        o.put("video_dropped_late", gate.droppedLate)
        o.put("video_dropped_following", gate.droppedBroken)
        o.put("video_drop_episodes", gate.episodes)
        o.put("video_sent_late", gate.sentLate)
        o.put("video_waiting_for_key", gate.waitingForKey)
        o.put("writer_late_ms_worst", Math.round(writerLate.worstMs * 10.0) / 10.0)
        o.put("app_not_run_ms_worst", Math.round(watchLate.worstMs * 10.0) / 10.0)
        o.put("held_up", heldUp.ifEmpty { JSONObject.NULL })
        o.put("ring_kb", history.heldBytes / 1024)
        o.put("ring_key_units", history.keyUnits)
        o.put("queued", writer.queued)
        o.put("packets_waiting", writer.packetsWaiting)
        o.put("video_packets_waiting", writer.videoPacketsWaiting)
        o.put("muxed_kb", writer.bytes / 1024)
        o.put("mux_rate_kbit_s", mux.muxRate / 1000)
        o.put("padding_percent", Math.round(mux.nullPackets * 1000.0 / maxOf(1L, mux.packets)) / 10.0)
        o.put("hold_ms", holdNanos / 1_000_000L)
        o.put("clock_steps_ignored", if (::slew.isInitialized) slew.stepsIgnored else 0L)
        o.put("first_audio_frame", f0)
        o.put("age_s", (System.nanoTime() - startedNanos) / 1_000_000_000L)
        o.put("trouble", captureTrouble ?: JSONObject.NULL)
        o.put("statistics", lastStats.ifEmpty { JSONObject.NULL })
        return o
    }

    companion object {
        private const val TAG = "tvvideo"

        /** The ring holds the last 12 s or 16 MB of the stream (9 Mbit/s is 1.1 MB a second); a connection starts up to 2.5 s back. */
        private const val HISTORY_BYTES = 16L shl 20
        private const val HISTORY_NANOS = 12_000_000_000L
        const val AUDIO_BITRATE = 192_000

        /**
         * How fast the clock of the stream follows the capture's (ppm): the capture clock of this box has been seen to hold the system clock to
         * under 1 ppm for minutes, so only a slow real drift is meant to be followed. A step of its timestamps (46.8 ms once, a period of the
         * audio path or two) is not followed at all: [SLEW_STEP_MS].
         */
        const val SLEW_PPM = 10.0
        const val SLEW_STEP_MS = 8.0
        private const val BLOCK = 1024

        /** The first PTS of the stream (90 kHz): 2 s, so that the PCR (which runs a lead behind it) is never negative. */
        private const val PTS_BASE = 180_000L

        /** The time to start the encoders in: the first audio block is due this long after the start. */
        private const val START_DELAY_NANOS = 150_000_000L

        /**
         * What the encoder is given from the drawing of a picture to the moment it is due in the stream (ns). A picture drawn at time w is
         * labelled with the capture time w - hold + this, so it is due at w + this; the encoder gave its bytes 25 to 30 ms after the drawing
         * on most pictures and 150 to 850 ms after it on the key pictures and when the box was busy, and a picture that is not there by its time
         * is dropped (see [LateVideoGate]). With 100 ms every slow picture was late; with half a second only a stall of the encoder is. The
         * price is that what the picture shows of the track (the cover, the title) comes about 0.3 s after the sound of it (the position in the
         * track is counted for the time of the sound, and goes with it exactly).
         */
        private const val VIDEO_BUDGET_NANOS = 500_000_000L
        private const val WARMUP_IDR_EVERY_NANOS = 200_000_000L
        private const val STATS_EVERY_NANOS = 5_000_000_000L
        private const val RECENT_DRAWN = 600

        /** A picture must have ended this much (ms) before the PCR lead is used up at the TV; and the least time the gate allows whatever the lead. */
        private const val GATE_ROOM_MS = 150L
        private const val MIN_GATE_MS = 200L

        /** While the stream waits for a key picture after a drop, the encoder is asked for one at most this often. */
        private const val KEY_ASK_EVERY_NANOS = 300_000_000L

        /** The watch sleeps this long and tells when it slept much longer (the app was kept from running); a stall this long (ms) is put in the log. */
        private const val WATCH_TICK_NANOS = 20_000_000L
        private const val STALL_LOG_MS = 150.0

        /** A step of the writer that made up more slots than this (about 100 ms of the stream at 9 Mbit/s) is put in the log with the others. */
        private const val HELD_UP_SLOTS = 600L

        /** Short audio blocks in one statistics period (5 s, about 215 blocks) that mean the stream has run out of frames. */
        private const val STARVED_BLOCKS = 40
        private const val START_WAIT_S = 10L
    }
}
