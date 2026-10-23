package io.github.matijasalej.wholeflat.sync

/** The Spotify volume as librespot maps it with its default 60 dB logarithmic control. */
object VolumeCurve {
    /** `unit` is the Connect volume from 0.0 to 1.0; the result is the amplitude factor. */
    fun logGain(unit: Double): Double {
        if (unit <= 0.0) return 0.0
        if (unit >= 1.0) return 1.0
        return Math.pow(1000.0, unit - 1.0)
    }

    /** The Connect volume (0 to 65535) as a Sonos volume 0 to 100, plus the kitchen balance. */
    fun sonosVolume(volume: Int, balance: Int): Int {
        val percent = Math.round(volume.coerceIn(0, 65535) * 100.0 / 65535.0).toInt()
        return (percent + balance).coerceIn(0, 100)
    }
}
