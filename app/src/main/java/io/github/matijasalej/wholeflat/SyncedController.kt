package io.github.matijasalej.wholeflat

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import io.github.matijasalej.wholeflat.sync.AmbilightControl
import io.github.matijasalej.wholeflat.sync.AmbilightStore
import io.github.matijasalej.wholeflat.sync.Foreground
import io.github.matijasalej.wholeflat.sync.ForegroundPolicy
import io.github.matijasalej.wholeflat.sync.Mode
import io.github.matijasalej.wholeflat.sync.ModeManager
import io.github.matijasalej.wholeflat.sync.TvPresentation
import org.json.JSONObject
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

/**
 * Synced mode, put together: reads the state of the box once a second, asks the [ModeManager] what to do about the TV, and carries it out
 * (grab or release the TV's player, change the mode), holds the Sonos to the common delay of the two rooms, and sets what the TV
 * shows (the screen, the Ambilight).
 *
 * **The kitchen.** While the TV plays our stream the Sonos is held at the common delay D (plus the kitchen offset, which is the fine
 * sync between the rooms), and a captured stream is started with the lead that puts it there at once; when D appears or goes (the TV
 * starts or is given back) and the kitchen is far from the new lag, its stream is started again, because climbing 1 s at 3 ms per second
 * takes minutes.
 *
 * **The warm-up.** The TV's video stream starts with a splash and silence until the TV has settled ([TvRenderer]); the kitchen does not
 * start meanwhile (a stream that plays goes on), and when the music goes live on the TV the kitchen is started, or started again, timed
 * so that its first sound is the TV's first sound: the two rooms begin together.
 *
 * **The TV.** In Synced mode the screen is off (the stream goes on, only the sound matters) unless karaoke is on; the Ambilight
 * follows the audio whenever lyrics are visible on the TV (karaoke, or Lyrics mode with the Spotify app on the screen), and what it was
 * before is put back afterwards. A screen that this app switched off is switched on again when the TV stops playing our stream. Both
 * the remembered state of the lights and the fact that the screen was switched off are kept in the preferences, so that an app that
 * crashed does not leave the TV dark or lit for ever.
 */
class SyncedController(
    private val context: Context,
    private val kitchen: Kitchen,
    private val renderer: TvRenderer,
    private val tv: TvControl,
) {
    private val exec = ScheduledThreadPoolExecutor(1, ThreadFactory { r -> Thread(r, "wf-synced").also { it.isDaemon = true } })
    private val manager = ModeManager()
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences("wholeflat_tv", Context.MODE_PRIVATE)

    private val ambilight = AmbilightControl(
        tv.ambilight,
        object : AmbilightStore {
            override fun read(): AmbilightControl.Saved? {
                val power = prefs.getString(AMBI_POWER, null) ?: return null
                val config = prefs.getString(AMBI_CONFIG, null) ?: return null
                return AmbilightControl.Saved(power, config)
            }

            override fun write(saved: AmbilightControl.Saved?) {
                val e = prefs.edit()
                if (saved == null) e.remove(AMBI_POWER).remove(AMBI_CONFIG) else e.putString(AMBI_POWER, saved.power).putString(AMBI_CONFIG, saved.config)
                e.apply()
            }
        },
        { Settings.values.ambilightStyle },
        { Settings.values.ambilightMenu },
    )

    @Volatile
    private var screenOffByUs = prefs.getBoolean(SCREEN_OFF_KEY, false)
    private var screenAction = ""
    private var lightsAction = ""
    private var lastScreenTryMs = 0L
    private var lastLightsTryMs = 0L
    private var lastRestartMs = 0L
    private var lastForegroundMs = 0L
    private var lastForegroundValue = Foreground.UNKNOWN
    private var recovered = false
    private var recoverTries = 0
    private var lastRefreshMs = 0L

    /** The lights as the TV reports them, read now and then for the status. */
    @Volatile
    private var currentPower: String? = null

    @Volatile
    private var currentConfig: String? = null

    @Volatile
    private var currentReadAtMs = 0L

    @Volatile
    private var lastDecision = ""

    /** A re-anchor of the TV's stream is wanted at the next song boundary; and the track that was playing when it became due. */
    @Volatile
    private var reanchorWanted = false
    private var trackWhenWanted = ""

    @Volatile
    private var kitchenAlign = ""

    @Volatile
    private var lastInputs = ""

    private val settingsListener: (Set<String>) -> Unit = { changed ->
        if (changed.any { it in WATCHED }) {
            if (changed.contains("tv_latency_ms") || changed.contains("tv_format") || changed.any { it.startsWith("kitchen_") }) renderer.settingsChanged()
            if (changed.contains("ambilight_style") || changed.contains("ambilight_menu")) post { ambilight.restyle() }
            tickSoon()
        }
    }

    // ---- life cycle ------------------------------------------------------------------------------

    fun start() {
        renderer.onLeft = { _, _ -> tickSoon() }
        renderer.onState = { tickSoon() }
        renderer.onDelay = { tickSoon() }
        renderer.onLive = { info -> post { onLive(info) } }
        renderer.onGaveUp = { why ->
            AppLog.add(TAG, "the TV could not be taken three times in a row: $why")
            tickSoon()
        }
        Settings.addListener(settingsListener)
        exec.scheduleWithFixedDelay({ post { tick() } }, 2_000, TICK_MS, TimeUnit.MILLISECONDS)
        AppLog.add(TAG, "Synced mode: started, mode ${Settings.values.mode}")
    }

    fun stop() {
        Settings.removeListener(settingsListener)
        try {
            // the lights and the screen are put back before the app is gone
            ambilight.leave()
            if (screenOffByUs) {
                if (tv.setScreen(true)) setScreenOffByUs(false)
            }
        } catch (t: Throwable) {
            AppLog.error(TAG, "putting the TV back failed", t)
        }
        exec.shutdownNow()
    }

    private fun post(block: () -> Unit) {
        try {
            exec.execute {
                try {
                    block()
                } catch (t: Throwable) {
                    AppLog.error(TAG, "the Synced mode step failed", t)
                }
            }
        } catch (e: Exception) {
            // shut down
        }
    }

    /** Looks at the state now instead of at the next second. */
    fun tickSoon() = post { tick() }

    private fun nowMs(): Long = SystemClock.elapsedRealtime()

    // ---- the step --------------------------------------------------------------------------------

    private fun tick() {
        val v = Settings.values
        val now = nowMs()
        val mode = Mode.of(v.mode) ?: Mode.SYNCED
        val source = AppState.source
        val captureAlive = AppState.capture?.state == "running"
        val silent = if (source == "librespot") 0L else (AppState.capture?.silentForMs() ?: (Long.MAX_VALUE / 4))
        val tvState = when (renderer.state) {
            TvRenderer.State.IDLE -> ModeManager.Tv.IDLE
            TvRenderer.State.STARTING -> ModeManager.Tv.STARTING
            TvRenderer.State.PLAYING -> ModeManager.Tv.PLAYING
            TvRenderer.State.LEFT -> ModeManager.Tv.LEFT
            TvRenderer.State.FAILED -> ModeManager.Tv.FAILED
        }
        val leftKind = when (renderer.leftKind) {
            TvRenderer.LeftKind.STOPPED -> ModeManager.LeftKind.STOPPED
            TvRenderer.LeftKind.CLOSED -> ModeManager.LeftKind.CLOSED
            TvRenderer.LeftKind.UNREACHABLE -> ModeManager.LeftKind.UNREACHABLE
            null -> null
        }
        val foreground = if (manager.wantsForeground(tvState, renderer.leftCount)) foreground(now, force = tvState == ModeManager.Tv.LEFT) else Foreground.UNKNOWN
        val inputs = ModeManager.Inputs(
            nowMs = now, mode = mode, source = source, captureAlive = captureAlive, silentForMs = silent,
            tvKnown = tv.currentIp() != null, tv = tvState, leftCount = renderer.leftCount, leftKind = leftKind,
            foreground = foreground, backoffRemainingMs = renderer.backoffRemainingMs(), failedGrabs = renderer.failedGrabs(),
        )
        lastInputs = "mode ${mode.key}, source $source, capture ${if (captureAlive) "alive" else "gone"}, silent ${if (silent > 86_400_000L) "never" else "${silent / 1000} s"}, " +
            "TV ${renderer.state.label}, in front ${foreground.name.lowercase()}"
        for (action in manager.decide(inputs)) {
            when (action) {
                is ModeManager.Action.Grab -> {
                    AppLog.add(TAG, "taking the TV: ${manager.lastWhy.ifEmpty { "synced" }}")
                    lastDecision = "grab"
                    renderer.grab(manager.lastWhy)
                }
                is ModeManager.Action.Release -> {
                    AppLog.add(TAG, "giving the TV back: ${action.why}")
                    lastDecision = "release: ${action.why}"
                    renderer.release(action.why)
                }
                is ModeManager.Action.SetMode -> {
                    AppLog.add(TAG, "the mode becomes ${action.mode.key}: ${action.why}")
                    lastDecision = "mode ${action.mode.key}: ${action.why}"
                    Settings.set("mode", action.mode.key)
                }
            }
        }
        val effectiveMode = Mode.of(Settings.values.mode) ?: mode
        // the TV plays our music (not just the splash of the warm-up)
        val playing = renderer.state == TvRenderer.State.PLAYING && renderer.live && effectiveMode == Mode.SYNCED
        kitchen.holdStart = if (effectiveMode == Mode.SYNCED && renderer.warming) "the TV is warming up (a splash and silence), its music is not live yet" else null
        // while the TV warms up again (a re-anchor) the kitchen is not sent back to the lag of Lyrics mode: it would be started twice
        holdTheKitchen(playing, now, keepTarget = effectiveMode == Mode.SYNCED && renderer.warming)
        present(effectiveMode, playing, source, now)
        reanchorCheck(playing, silent)
        recoverAfterCrash(now)
        refreshCurrent(now)
    }

    /**
     * The music went live on the TV: the Sonos is held at the common delay from now, and its stream is started (or started again) so that its
     * first sound is the TV's first sound. The TV sounds the first frame [TvTsPipeline.LiveInfo.dueNanos] + its own delay from now; the
     * kitchen needs its preparation (mute, stop, volume, address) and then the Play-to-sound delay.
     */
    private fun onLive(info: TvTsPipeline.LiveInfo) {
        val v = Settings.values
        val now = nowMs()
        val d = renderer.delayMs
        if (!d.isNaN()) {
            val target = d + v.kitchenOffsetMs
            kitchen.syncedTargetMs = target
            kitchen.syncedLeadMs = maxOf(0.0, target - kitchen.fixedLagMs())
        }
        if (info.firstFrame < 0) return // the audio-only stream has no warm-up: the kitchen is started again when it is far from its lag
        val untilDueMs = maxOf(0.0, (info.dueNanos - System.nanoTime()) / 1e6)
        val delay = maxOf(0.0, untilDueMs + renderer.tvLatencyMs() - kitchen.playToSoundMs() - KITCHEN_PREPARE_MS).toLong()
        lastRestartMs = now + delay + 3_000
        kitchenAlign = "the kitchen starts in $delay ms (the TV's first music is written in ${Math.round(untilDueMs)} ms and sounds ${renderer.tvLatencyMs()} ms later; " +
            "Play to sound ${Math.round(kitchen.playToSoundMs())} ms, preparation ${KITCHEN_PREPARE_MS.toInt()} ms)"
        AppLog.add(TAG, kitchenAlign)
        kitchen.syncedStartAfter(delay, "the TV's music goes live")
    }

    /** Asks for a re-anchor of the TV's stream at the next song boundary (see [reanchorCheck]); the debug page and the settings use it. */
    fun requestReanchor() {
        reanchorWanted = true
        trackWhenWanted = trackKey()
        AppLog.add(TAG, "a re-anchor of the TV's stream is wanted at the next song boundary")
    }

    private fun trackKey(): String = renderer.track().let { "${it.title}|${it.artist}" }

    /**
     * The TV gives no position, so if its timing turns out to wander the only remedy is a new start of its stream (with a warm-up). It is
     * done where nobody hears the gap: when a song ends (the track changes) or the music has been silent for a moment. With
     * `tv_reanchor_min` set, a stream that has run that long is due one; [requestReanchor] asks for one at once.
     */
    private fun reanchorCheck(playing: Boolean, silentMs: Long) {
        val minutes = Settings.values.tvReanchorMin
        if (!playing) {
            reanchorWanted = false
            return
        }
        val age = renderer.streamAgeSeconds()
        if (!reanchorWanted && minutes > 0 && age >= minutes * 60L) {
            reanchorWanted = true
            trackWhenWanted = trackKey()
            AppLog.add(TAG, "the TV's stream is $age s old: a re-anchor is due and waits for a song boundary")
        }
        if (!reanchorWanted) return
        val boundary = trackKey() != trackWhenWanted || silentMs in REANCHOR_SILENCE_MS..SILENT_NOT_PLAYING_MS
        if (boundary) {
            reanchorWanted = false
            renderer.reanchor("a song boundary, the stream was $age s old")
        }
    }

    /** What is in front, looked up at most every 3 s (the usage events of half a day are not cheap). */
    private fun foreground(now: Long, force: Boolean): Foreground {
        if (!force && now - lastForegroundMs < 3_000) return lastForegroundValue
        lastForegroundMs = now
        lastForegroundValue = ForegroundPolicy.classify(tv.foregroundPackage(), context.packageName, tv.launcherPackages())
        return lastForegroundValue
    }

    // ---- the kitchen -----------------------------------------------------------------------------

    private fun holdTheKitchen(playing: Boolean, now: Long, keepTarget: Boolean = false) {
        val offset = Settings.values.kitchenOffsetMs
        if (playing) {
            val d = renderer.delayMs
            if (!d.isNaN()) {
                val target = d + offset
                kitchen.syncedTargetMs = target
                // the lead that puts the lag at the common delay at once: the target less the Play-to-sound delay and the pipeline
                kitchen.syncedLeadMs = maxOf(0.0, target - kitchen.fixedLagMs())
            }
        } else if (!keepTarget) {
            kitchen.syncedTargetMs = Double.NaN
            kitchen.syncedLeadMs = Double.NaN
        }
        // far from where it is to be: its stream is started again with the right lead (once in a while)
        val lag = kitchen.lagNowMs()
        val target = kitchen.currentTargetMs()
        if (!lag.isNaN() && !target.isNaN() && Math.abs(lag - target) > RESTART_FAR_MS && now - lastRestartMs > RESTART_EVERY_MS) {
            lastRestartMs = now
            AppLog.add(TAG, "the kitchen is ${Math.round(lag)} ms behind the capture and is to be at ${Math.round(target)} ms: its stream starts again")
            kitchen.restartStream("the lag of the kitchen is to be ${Math.round(target)} ms")
        }
    }

    // ---- the TV's picture and lights -------------------------------------------------------------

    private fun present(mode: Mode, playing: Boolean, source: String, now: Long) {
        val v = Settings.values
        val want = TvPresentation.want(mode, playing, v.karaoke, source, v.ambilightSynced, screenOffByUs)
        when (want.screen) {
            TvPresentation.Screen.OFF -> if (!screenOffByUs && now - lastScreenTryMs > RETRY_MS) {
                lastScreenTryMs = now
                if (tv.setScreen(false)) {
                    setScreenOffByUs(true)
                    screenAction = "off (Synced mode, no lyrics on the TV)"
                } else {
                    screenAction = "off: the TV refused"
                }
            }
            TvPresentation.Screen.ON -> if (screenOffByUs && now - lastScreenTryMs > RETRY_MS) {
                lastScreenTryMs = now
                if (tv.setScreen(true)) {
                    setScreenOffByUs(false)
                    screenAction = "on again"
                } else {
                    screenAction = "on: the TV refused"
                }
            }
            TvPresentation.Screen.LEAVE -> Unit
        }
        if (want.lights) {
            if (!ambilight.entered && now - lastLightsTryMs > RETRY_MS) {
                lastLightsTryMs = now
                ambilight.enter()
                lightsAction = ambilight.lastAction
                AppLog.add(TAG, lightsAction)
            }
        } else if ((ambilight.entered || ambilight.remembered != null) && now - lastLightsTryMs > RETRY_MS) {
            lastLightsTryMs = now
            ambilight.leave()
            lightsAction = ambilight.lastAction
            AppLog.add(TAG, lightsAction)
        }
    }

    private fun setScreenOffByUs(off: Boolean) {
        screenOffByUs = off
        prefs.edit().putBoolean(SCREEN_OFF_KEY, off).apply()
    }

    /** After a crash: lights that were left on and a screen that was left off are put back as soon as the TV answers. */
    private fun recoverAfterCrash(now: Long) {
        if (recovered || now < RECOVER_AFTER_MS) return
        if (tv.currentIp() == null && recoverTries < 6) {
            recoverTries++
            return
        }
        if (ambilight.remembered == null && !screenOffByUs) {
            recovered = true
            return
        }
        if (ambilight.entered || renderer.state == TvRenderer.State.PLAYING) return // this run is using them
        recoverTries++
        if (ambilight.remembered != null) {
            ambilight.recover()
            AppLog.add(TAG, ambilight.lastAction)
        }
        if (screenOffByUs && tv.setScreen(true)) {
            setScreenOffByUs(false)
            AppLog.add(TAG, "the screen of the TV was off from an earlier run and is on again")
        }
        if ((ambilight.remembered == null && !screenOffByUs) || recoverTries > 20) recovered = true
    }

    private fun refreshCurrent(now: Long) {
        if (now - lastRefreshMs < CURRENT_EVERY_MS) return
        // the TV is asked only when its lights matter (an unreachable TV would hold the step up for the length of two time-outs)
        val inUse = ambilight.entered || ambilight.remembered != null || renderer.state == TvRenderer.State.PLAYING || currentReadAtMs == 0L
        if (!inUse || tv.currentIp() == null) return
        lastRefreshMs = now
        currentPower = tv.ambilight.power()
        currentConfig = tv.ambilight.config()
        currentReadAtMs = now
    }

    // ---- status ----------------------------------------------------------------------------------

    fun statusJson(): JSONObject {
        val o = JSONObject()
        val v = Settings.values
        o.put("mode", v.mode)
        o.put("karaoke", v.karaoke)
        o.put("tv_format", v.tvFormat)
        o.put("state", renderer.state.label)
        o.put("delay_ms", if (renderer.delayMs.isNaN()) JSONObject.NULL else Math.round(renderer.delayMs * 10.0) / 10.0)
        o.put("kitchen_synced_target_ms", if (kitchen.syncedTargetMs.isNaN()) JSONObject.NULL else Math.round(kitchen.syncedTargetMs * 10.0) / 10.0)
        o.put("kitchen_hold", kitchen.holdStart ?: JSONObject.NULL)
        o.put("kitchen_align", kitchenAlign.ifEmpty { JSONObject.NULL })
        o.put("reanchor_wanted", reanchorWanted)
        o.put("reanchor_after_min", v.tvReanchorMin)
        o.put("last_decision", lastDecision.ifEmpty { JSONObject.NULL })
        o.put("why", manager.lastWhy.ifEmpty { JSONObject.NULL })
        o.put("inputs", lastInputs)
        val screen = JSONObject()
        screen.put("off_by_us", screenOffByUs)
        screen.put("last_action", screenAction.ifEmpty { JSONObject.NULL })
        o.put("screen", screen)
        val a = JSONObject()
        a.put("enabled", v.ambilightSynced)
        a.put("style", v.ambilightStyle)
        a.put("menu", v.ambilightMenu)
        a.put("on_for_lyrics", ambilight.entered)
        val saved = ambilight.remembered
        a.put("remembered", if (saved == null) JSONObject.NULL else JSONObject().put("power", saved.power).put("config", saved.config))
        a.put("current_power", currentPower ?: JSONObject.NULL)
        a.put("current_config", currentConfig ?: JSONObject.NULL)
        a.put("current_age_s", if (currentReadAtMs == 0L) -1L else (nowMs() - currentReadAtMs) / 1000)
        a.put("last_action", ambilight.lastAction.ifEmpty { JSONObject.NULL })
        a.put("last_action_ok", ambilight.lastActionOk)
        o.put("ambilight", a)
        o.put("renderer", renderer.statusJson())
        return o
    }

    companion object {
        private const val TAG = "synced"
        private const val TICK_MS = 1_000L
        private const val RETRY_MS = 10_000L
        private const val CURRENT_EVERY_MS = 10_000L
        private const val RECOVER_AFTER_MS = 20_000L

        /** The kitchen is started again when it is this far from the lag it is to have (ms), and not more often than this (ms). */
        private const val RESTART_FAR_MS = 150.0
        private const val RESTART_EVERY_MS = 20_000L

        /** What the kitchen's own start takes before its Play (the speaker is muted and stopped, its volume and the address are set), a guess to be read from the log (ms). */
        private const val KITCHEN_PREPARE_MS = 300.0

        /** A song boundary for a re-anchor: the music has been silent this long (ms), but not so long that nothing is playing at all. */
        private const val REANCHOR_SILENCE_MS = 2_000L
        private const val SILENT_NOT_PLAYING_MS = 600_000L

        private const val AMBI_POWER = "ambi_power"
        private const val AMBI_CONFIG = "ambi_config"
        private const val SCREEN_OFF_KEY = "screen_off_by_us"

        /** The settings that make the controller look again at once. */
        private val WATCHED = setOf(
            "mode", "karaoke", "tv_format", "tv_latency_ms", "kitchen_offset_ms", "kitchen_target_ms", "kitchen_floor_ms",
            "ambilight_synced", "ambilight_style", "ambilight_menu", "tv_reanchor_min",
        )
    }
}
