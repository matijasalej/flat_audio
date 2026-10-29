package io.github.matijasalej.wholeflat.sync

/**
 * Whether the living room is a replay of the capture: Synced on HDMI. The Spotify TV app's own sound is muted with its PLAY_AUDIO app-op
 * ([MUTE_COMMAND]), and this app plays what the capture took on the box's HDMI output, held to the same delay after the capture as the
 * kitchen (see `LivingReplay`). The TV stays on its HDMI input with the Spotify app on the screen: nothing is handed to its DLNA player,
 * whose fresh starts hung on 28.10. (it took the item, never asked for the stream and fell back to its tuner).
 *
 * The capture still hears the muted app: AudioFlinger gives a track whose app may not play audio the volume 0 in its mixer, but the copy for
 * the capture is taken from the track's frames before any volume is applied (`Track::interceptBuffer` in `releaseBuffer`, Android 14).
 *
 * The mute is the switch. Muted, the room is replayed (without the replay it would be silent); not muted, it is not (it would hear the music
 * twice, the second time a second late). Pure logic.
 */
object ReplayGate {
    const val SPOTIFY_TV = ForegroundPolicy.SPOTIFY_TV

    /** The app-op that mutes an app's players, as AppOpsManager names it. */
    const val OP_PLAY_AUDIO = "android:play_audio"

    /** What mutes the Spotify app's own sound (from the Mac), and what gives it back. */
    const val MUTE_COMMAND = "adb shell cmd appops set $SPOTIFY_TV PLAY_AUDIO ignore"
    const val UNMUTE_COMMAND = "adb shell cmd appops set $SPOTIFY_TV PLAY_AUDIO allow"

    /**
     * @param replay the living room is to be the replay
     * @param silent the Spotify app is muted and nothing is replayed: what it plays is not heard in the living room
     */
    class Decision(val replay: Boolean, val why: String, val silent: Boolean = false)

    /**
     * @param muted whether the Spotify app's own sound is muted (its PLAY_AUDIO is not allowed); null when that cannot be read
     * @param captureRunning the capture runs, so there is something to replay
     * @param otherPlaying the package of another captured app whose media session plays now (the Cast receiver); null when none does.
     *   One that only paused does not count: the replay goes on with the silence, so the first second after Spotify's next Play is not lost.
     * @param boxAwake the box's screen is on. Asleep, its HDMI output and the TV are off and nothing plays (the app keeps the box awake
     *   while the music plays), and a track there takes nothing: no replay, and nothing to warn of.
     */
    fun decide(muted: Boolean?, captureRunning: Boolean, otherPlaying: String?, boxAwake: Boolean = true): Decision = when {
        muted == null -> Decision(false, "whether the Spotify app is muted cannot be read, so nothing is replayed", silent = true)
        !muted -> Decision(false, "the Spotify app plays on HDMI itself (its PLAY_AUDIO is allowed)")
        !boxAwake -> Decision(false, "the box sleeps: its HDMI output and the TV are off")
        !captureRunning -> Decision(false, "the Spotify app is muted, but the capture does not run: there is nothing to replay", silent = true)
        // the Cast receiver is captured too, and it is not muted: its sound is in the room already
        otherPlaying != null && otherPlaying != SPOTIFY_TV -> Decision(false, "$otherPlaying plays and is not muted: a replay would be heard twice")
        else -> Decision(true, "the Spotify app is muted: the living room is its capture, replayed on HDMI")
    }

    /** What the phone page says while the living room cannot hear the Spotify app; null when it can. */
    fun warning(d: Decision): String? =
        if (d.silent) "The living room is silent: the Spotify app is muted and its sound is not replayed (${d.why}). To hear it directly again: $UNMUTE_COMMAND" else null
}
