package io.github.matijasalej.wholeflat.sync

/**
 * The switch of Synced on HDMI on the phone page. The switch is the mute of the Spotify app ([ReplayGate]): on, its PLAY_AUDIO is
 * "ignore" and the app replays the living room; off, "allow" and the Spotify app plays on HDMI itself. An app cannot set another app's
 * app-ops, so this one runs the command as the box's own adb would: it connects to the box's adb daemon on [PORT] (`AdbSelf`), which
 * listens there once it was told so from the Mac ([ARM_COMMAND]) and until the box restarts. The first connection asks on the TV
 * whether to allow it ("Always allow from this computer").
 *
 * What the switch shows is not what was last asked but what the app-op is (`LivingReplay` reads it every second), so a mute from the Mac
 * and a tap on the phone agree. Plain Kotlin.
 */
object ReplaySwitch {
    const val PORT = 5555

    /** What makes the box's adb listen on [PORT] (from the Mac, while it is connected over wireless debugging), until the box restarts. */
    const val ARM_COMMAND = "adb tcpip $PORT"

    /** The shell command that mutes the Spotify app ([on]: the living room is replayed) or gives it its sound back. */
    fun command(on: Boolean): String = "cmd appops set ${ReplayGate.SPOTIFY_TV} PLAY_AUDIO ${if (on) "ignore" else "allow"}"

    /** How far the app can reach the box's adb. */
    enum class Link(val key: String) {
        /** Not tried yet. */
        UNKNOWN("unknown"),

        /** A command ran. */
        READY("ready"),

        /** Connected, and the TV asks whether to allow it: nothing runs until someone taps Allow. */
        WAITING_ALLOW("waiting_allow"),

        /** Nothing listens on [PORT]: the box restarted since it was armed, or it never was. */
        NOT_ARMED("not_armed"),

        /** The last try failed otherwise (see the error). */
        FAILED("failed"),
    }

    /**
     * The line under the switch: what it does now, or what stands in its way. [muted] is the Spotify app's mute as read (null: unknown),
     * [replaying] whether the room is the replay now, [pending] the state asked for and not yet seen.
     */
    fun text(link: Link, muted: Boolean?, replaying: Boolean, pending: Boolean?, error: String?): String = when {
        link == Link.WAITING_ALLOW -> "Tap Allow on the TV (\"Always allow from this computer\")"
        link == Link.NOT_ARMED -> "The switch needs the box armed from the Mac once after a power cut: $ARM_COMMAND"
        link == Link.FAILED -> "The switch failed: ${error ?: "unknown error"}"
        pending != null && pending != muted -> if (pending) "Switching on…" else "Switching off…"
        muted == null -> "Whether the Spotify app is muted cannot be read"
        muted && replaying -> "On: the box plays the living room in step with the kitchen"
        muted -> "On, but the living room is not replayed now"
        else -> "Off: the Spotify app plays the TV itself"
    }
}
