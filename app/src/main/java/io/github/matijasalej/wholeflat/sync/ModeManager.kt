package io.github.matijasalej.wholeflat.sync

/** What is in front on this box (the Chromecast with Google TV that the app runs on), as far as it matters for Synced mode. */
enum class Foreground {
    /** The launcher, the screen saver, the settings: the user is in the interface of the box. */
    LAUNCHER,

    /** The Spotify TV app. */
    SPOTIFY,

    /** This app. */
    OWN_APP,

    /** The Cast receiver: a Cast session is on the screen. */
    MEDIASHELL,

    /** Anything else: a video app, a game. */
    VIDEO_APP,

    /** Not known (the usage access is not granted, or nothing was seen). */
    UNKNOWN,
}

object ForegroundPolicy {
    const val SPOTIFY_TV = "com.spotify.tv.android"
    const val MEDIASHELL = "com.google.android.apps.mediashell"

    /** The class of the package in front; [launchers] are the home screens of the box. */
    fun classify(pkg: String?, ownPackage: String, launchers: Set<String>): Foreground = when {
        pkg == null -> Foreground.UNKNOWN
        pkg == ownPackage -> Foreground.OWN_APP
        pkg == SPOTIFY_TV -> Foreground.SPOTIFY
        pkg == MEDIASHELL -> Foreground.MEDIASHELL
        pkg in VideoPolicy.QUIET_PACKAGES || pkg in launchers -> Foreground.LAUNCHER
        else -> Foreground.VIDEO_APP
    }
}

/**
 * Decides, from a snapshot of the state, what Synced mode does about the TV and about the mode itself. Pure logic with a little memory
 * of its own (which departures it handled, when it re-grabbed); the glue in the service reads the state, asks and carries out the
 * actions.
 *
 * - **Synced** (the setting `mode`, the default): while the capture lives and Spotify's own playback (Whole Flat) does not play, the TV's
 *   DLNA player gets the capture, from the first sound on. It keeps playing through pauses and skips (the capture streams silence
 *   between tracks, so none of them pays the 2 s of a start) for `tv_hold_silence_min` minutes of silence (8), then the TV is given
 *   back: before the box sleeps (its 10-minute timeout runs from about a minute after the music), because the CEC Standby of its sleep
 *   switches the TV off, and a TV switched off with our stream in its player is the likeliest way that player hangs.
 * - The TV **leaves its player** (its transport stops, it closes the stream): with the launcher, Spotify or this app in front, the user
 *   meant it (Home, the input key): the mode becomes **Lyrics** (the Spotify app stays on the screen, the kitchen follows). With the
 *   Cast receiver in front a Cast session took the screen: the TV is grabbed again once the session has settled, and if it leaves
 *   again within a minute of that the mode becomes Lyrics (no fighting over the TV). With a video app in front the TV is released
 *   and not grabbed while the app is there. A TV that does not answer is given up until it does.
 * - **Lyrics** goes back to Synced after more than 10 minutes of silence, unless the TV was given up (below).
 * - A **hung player** of the TV (see [StuckTv]) makes the mode Lyrics with what to do (unplug the TV), and it stays Lyrics until the user picks a
 *   setting on the phone page, which says the player is free.
 * - Three starts of the TV in a row that fail make the mode Lyrics, with the reason. It stays Lyrics until the user asks for Synced (a
 *   display on the phone page, which also has the failed starts forgotten) or the app starts again: the reset after a long silence and
 *   this rule undid each other on every step, hundreds of times a second. A Synced that the user has just asked for is not turned back
 *   by the failures from before it.
 */
class ModeManager(
    /** How long the TV keeps playing silence after the last sound before it is given back (ms): the setting `tv_hold_silence_min`. */
    private val silenceKeepMs: () -> Long = { SILENCE_KEEP_MS },
    private val resetAfterMs: Long = RESET_AFTER_MS,
    private val settleMs: Long = SETTLE_MS,
    private val pingPongMs: Long = PING_PONG_MS,
    private val giveUpAfter: Int = GIVE_UP_AFTER,
) {
    enum class Tv { IDLE, STARTING, PLAYING, LEFT, FAILED }

    enum class LeftKind { STOPPED, CLOSED, UNREACHABLE }

    class Inputs(
        val nowMs: Long,
        /** The setting `mode`. */
        val mode: Mode,
        /** "capture", "librespot" or "idle" (see SourceArbiter). */
        val source: String,
        /** The capture of the Cast receiver runs. */
        val captureAlive: Boolean,
        /** Since the last sound of the capture (ms); a very big number if there was none. */
        val silentForMs: Long,
        /** The address of the TV is known. */
        val tvKnown: Boolean,
        val tv: Tv,
        /** How many times the TV left its player so far, and how the last time. */
        val leftCount: Int,
        val leftKind: LeftKind?,
        /** What is in front now; it is looked at when the TV has left. */
        val foreground: Foreground,
        val backoffRemainingMs: Long,
        val failedGrabs: Int,
        /**
         * How long nothing has played, for the rules that fall back to the default after a long silence: since the last sound, but not
         * longer than the app has run (a start without music is not ten minutes of silence). The grab and the release of the TV go by
         * [silentForMs], for which "no sound yet" is no sound.
         */
        val idleForMs: Long = silentForMs,
        /**
         * The TV's stream is being made again, or its last start failed a moment ago: the TV is tried again whether there is sound at this
         * moment or not (a pause of the music while a re-anchor retries must not leave the TV without a stream until the music comes back).
         */
        val retryingTv: Boolean = false,
        /**
         * Some sound has been heard since the app began. Before it the silence is counted from the app's start, so the first seconds after
         * a start (a reboot, an update) looked like music and the TV was grabbed for nothing: a TV in standby that answers is woken for it,
         * and a TV that shows something else is taken.
         */
        val heardSound: Boolean = true,
        /** The TV's player is hung (see [StuckTv]): it is not asked for a stream until the user says it is free (a setting on the phone page). */
        val tvStuck: Boolean = false,
    )

    sealed class Action {
        object Grab : Action()

        class Release(val why: String) : Action()

        class SetMode(val mode: Mode, val why: String) : Action()
    }

    private var handledLeft = 0
    private var regrabAtMs = 0L
    private var lastRegrabMs = 0L
    private var videoSuppressed = false
    private var seenMode: Mode? = null
    private var modeSinceMs = 0L
    private var ownSetMode: Mode? = null

    /** Why the last decision was taken, for the status. */
    @Volatile
    var lastWhy = ""
        private set

    @Synchronized
    fun decide(i: Inputs): List<Action> {
        val actions = ArrayList<Action>()
        var mode = i.mode
        if (seenMode == null) {
            // the first look: the mode was not asked for just now
            seenMode = mode
            modeSinceMs = i.nowMs - 2 * JUST_ASKED_MS
        } else if (seenMode != mode) {
            seenMode = mode
            // a mode that this manager set itself (a reset after silence, a fall back) is not the user asking for the TV
            modeSinceMs = if (mode == ownSetMode) i.nowMs - 2 * JUST_ASKED_MS else i.nowMs
            ownSetMode = null
            if (mode == Mode.LYRICS) videoSuppressed = false
        }

        // 1. the TV left its player: each departure is handled once
        if (i.tv == Tv.LEFT && i.leftCount > handledLeft) {
            handledLeft = i.leftCount
            when {
                i.leftKind == LeftKind.UNREACHABLE -> {
                    actions.add(Action.Release("the TV does not answer"))
                    why("the TV does not answer: nothing is asked of it until it does")
                }
                i.foreground == Foreground.LAUNCHER || i.foreground == Foreground.SPOTIFY || i.foreground == Foreground.OWN_APP -> {
                    mode = Mode.LYRICS
                    actions.add(Action.SetMode(Mode.LYRICS, "the TV left its player with ${i.foreground.name.lowercase()} in front: the user left"))
                    actions.add(Action.Release("the user left"))
                }
                i.foreground == Foreground.VIDEO_APP -> {
                    videoSuppressed = true
                    actions.add(Action.Release("a video app is in front"))
                    why("a video app is in front, the TV is not taken while it is there")
                }
                else -> {
                    // the Cast receiver (a Cast session took the screen), or not known
                    if (lastRegrabMs != 0L && i.nowMs - lastRegrabMs < pingPongMs) {
                        mode = Mode.LYRICS
                        actions.add(Action.SetMode(Mode.LYRICS, "the TV left again within ${pingPongMs / 1000} s of a re-grab"))
                    } else {
                        regrabAtMs = i.nowMs + settleMs
                        why("a Cast session took the TV (${i.foreground.name.lowercase()} in front), it is grabbed again in ${settleMs / 1000} s")
                    }
                }
            }
        }
        // a video app that has gone from the front lets the TV be taken again
        if (videoSuppressed && i.foreground != Foreground.VIDEO_APP && i.foreground != Foreground.UNKNOWN) videoSuppressed = false

        // 2. the TV is not wanted: release it
        val wantTv = mode == Mode.SYNCED && i.captureAlive && i.source != "librespot" && i.tvKnown && !videoSuppressed && !i.tvStuck
        val holding = i.tv == Tv.STARTING || i.tv == Tv.PLAYING
        if (holding && !wantTv && actions.none { it is Action.Release }) {
            val reason = when {
                mode != Mode.SYNCED -> "the mode is lyrics"
                i.source == "librespot" -> "Spotify's own playback plays (Whole Flat)"
                !i.captureAlive -> "the capture is gone"
                videoSuppressed -> "a video app is in front"
                i.tvStuck -> "the TV's player is stuck"
                else -> "no TV"
            }
            actions.add(Action.Release(reason))
        }
        // 3. a long silence: the TV is given back (before the box sleeps and its CEC Standby switches the TV off with our stream in it)
        val keepMs = silenceKeepMs()
        if (holding && wantTv && i.silentForMs >= keepMs && actions.none { it is Action.Release }) {
            actions.add(Action.Release("${keepMs / 60_000} minutes of silence"))
        }

        // 4. grab: with sound (or when the user has just asked for Synced), or the re-grab after a Cast session
        val releasing = actions.any { it is Action.Release }
        val idleTv = i.tv == Tv.IDLE || i.tv == Tv.FAILED || i.tv == Tv.LEFT
        val justAsked = i.nowMs - modeSinceMs < JUST_ASKED_MS && seenMode == Mode.SYNCED
        if (wantTv && idleTv && !releasing && i.backoffRemainingMs <= 0L) {
            val rePending = regrabAtMs != 0L
            val soundNow = i.heardSound && i.silentForMs < GRAB_WITHIN_SOUND_MS
            if (rePending && i.nowMs >= regrabAtMs) {
                regrabAtMs = 0L
                lastRegrabMs = i.nowMs
                actions.add(Action.Grab)
                why("grabbing the TV again after the Cast session")
            } else if (!rePending && (soundNow || justAsked || i.retryingTv)) {
                actions.add(Action.Grab)
                why("grabbing the TV: sound, mode synced")
            }
        }
        if (!wantTv) regrabAtMs = 0L

        // 5. a hung player (not while the user has just asked for Synced: they say it is free, and the flag is being cleared), and starts that
        // keep failing (not those from before the user asked for Synced: they are being forgotten)
        if (mode == Mode.SYNCED && i.tvStuck && !justAsked) {
            mode = Mode.LYRICS
            actions.add(Action.SetMode(Mode.LYRICS, StuckTv.ADVICE.replaceFirstChar { it.lowercase() }))
        } else if (mode == Mode.SYNCED && i.failedGrabs >= giveUpAfter && !justAsked) {
            mode = Mode.LYRICS
            actions.add(Action.SetMode(Mode.LYRICS, "the TV could not be taken ${i.failedGrabs} times in a row"))
        }

        // 6. Lyrics goes back to Synced after a long silence, but not while the TV is given up or its player hung (rule 5 would turn it back at once)
        val givenUp = i.failedGrabs >= giveUpAfter || i.tvStuck
        if (mode == Mode.LYRICS && i.mode == Mode.LYRICS && i.idleForMs >= resetAfterMs && !givenUp && actions.none { it is Action.SetMode }) {
            actions.add(Action.SetMode(Mode.SYNCED, "more than ${resetAfterMs / 60_000} minutes of silence"))
        }
        actions.filterIsInstance<Action.SetMode>().lastOrNull()?.let { ownSetMode = it.mode }
        return actions
    }

    /** Whether the glue has to find out what is in front now (a departure is waiting to be handled, or a video app keeps the TV out). */
    @Synchronized
    fun wantsForeground(tv: Tv, leftCount: Int): Boolean = (tv == Tv.LEFT && leftCount > handledLeft) || videoSuppressed

    private fun why(text: String) {
        lastWhy = text
    }

    companion object {
        /** The TV keeps playing silence for this long after the last sound, then it is given back (the default of `tv_hold_silence_min`). */
        const val SILENCE_KEEP_MS = SettingsModel.DEFAULT_TV_HOLD_SILENCE_MIN * 60_000L

        /** Lyrics mode goes back to Synced after this much silence. */
        const val RESET_AFTER_MS = 10 * 60_000L

        /** A Cast session is given this long to settle before the TV is grabbed again. */
        const val SETTLE_MS = 3_000L

        /** A second departure this soon after a re-grab is the user, not a Cast session. */
        const val PING_PONG_MS = 60_000L
        const val GIVE_UP_AFTER = 3

        /** A grab starts with sound this recent (ms). */
        private const val GRAB_WITHIN_SOUND_MS = 5_000L

        /** After the user asked for Synced the TV is grabbed even if nothing plays (ms). */
        private const val JUST_ASKED_MS = 10_000L
    }
}
