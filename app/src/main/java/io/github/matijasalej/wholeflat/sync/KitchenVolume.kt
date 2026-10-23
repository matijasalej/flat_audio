package io.github.matijasalej.wholeflat.sync

/**
 * Which volume the Sonos is given.
 *
 * On this box the phone's Cast volume changes nothing (HDMI is a full-volume device with CEC volume control off, and the living
 * room is turned on the amplifier with the Google TV remote), so a captured stream has no volume of its own to follow: the kitchen
 * volume is a setting (`kitchen_volume`, set with the slider of the phone page) and the app never reads or restores the box's
 * STREAM_MUSIC. Where Cast does change that volume, `capture_volume_mirror` makes the Sonos follow it as before. Spotify's own ring
 * (Whole Flat) keeps following the Connect volume of the phone, with the kitchen balance.
 */
object KitchenVolume {
    /**
     * The Sonos volume (0 to 100) for a stream, or -1 to leave the speaker as it is. [capture]: the stream is a captured one.
     * [kitchenVolume] the setting (-1: unset), [mirrorOn] and [mirrorRaw] the mirror of the box's music volume (a Connect volume
     * 0 to 65535, -1 before the first report), [connectRaw] the Connect volume of Spotify's own playback.
     */
    fun sonos(capture: Boolean, kitchenVolume: Int, mirrorOn: Boolean, mirrorRaw: Int, connectRaw: Int, balance: Int): Int {
        if (capture) {
            if (kitchenVolume >= 0) return kitchenVolume.coerceIn(0, 100)
            return if (mirrorOn && mirrorRaw >= 0) VolumeCurve.sonosVolume(mirrorRaw, balance) else -1
        }
        return if (connectRaw >= 0) VolumeCurve.sonosVolume(connectRaw, balance) else -1
    }
}
