package io.github.matijasalej.wholeflat.light

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/** One look of the TV's Ambilight: a short name (for the status and the phone page) and its JointSpace configuration. */
class TvLook(val name: String, val json: String)

/**
 * The TV's own Ambilight styles that the mood lights use. All of them were measured on the 55PUS6753 with the screen off on 23.10.2026
 * (`/6/ambilight/processed`, while music played):
 * - **Calm** (Mono): the song's colour held by the TV (expert `FOLLOW_COLOR`, `MANUAL_HUE`), drifting a little around itself, slowly.
 * - **Lively** (Duo): the same colour drifting further, from the home colour towards its accent, faster for faster songs.
 * - **Modes** (Multi): the TV's styles that light up with the screen off and react to the music. Party works only in the expert form;
 *   Rhythm is random pixel flashes; then Scanner, VU meter, Spectrum and Colora.
 * - **Never used:** Strobe (flashes) and Lumina (dark with the screen off, because it takes its colours from the picture).
 *
 * The drift is the TV's own: `colorDelta` says how far the colour moves, `speed` how fast. It follows the tempo only roughly; the modes
 * follow the beat themselves, from the sound the TV plays. Plain Kotlin.
 */
object TvLooks {
    val MODES: List<TvLook> = listOf(
        TvLook("party", audioExpert("PARTY")),
        TvLook("rhythm", audio("RANDOM_PIXEL_FLASH")),
        TvLook("scanner", audio("KNIGHT_RIDER_ALTERNATING")),
        TvLook("vu", audio("VU_METER")),
        TvLook("scanner-cw", audio("KNIGHT_RIDER_CLOCKWISE")),
        TvLook("spectrum", audio("SPECTRUM_ANALYZER")),
        TvLook("colora", audio("ENERGY_ADAPTIVE_COLORS")),
    )

    /** The modes in a breakdown of an energetic song, calmest first. */
    val CALMER = listOf("scanner", "colora", "vu")

    fun mode(name: String): TvLook? = MODES.firstOrNull { it.name == name }

    /** Calm: [c] drifting a little (about ±8° and a fifth of its brightness); [liveliness] (0 to 1) makes the drift faster. */
    fun calm(c: Int, liveliness: Double): TvLook =
        TvLook("calm", drift(c, hueDelta = 6, brightnessDelta = 50, speed = (20 + 60 * liveliness.coerceIn(0.0, 1.0)).roundToInt()))

    /**
     * Lively: drifting between [home] and [accent]. The colour held is halfway between them (at most 40° from home), and the drift reaches
     * both. Faster songs and more [liveliness] drift faster. [bpm] is NaN if the tempo is not known.
     */
    fun lively(home: Int, accent: Int, bpm: Double, liveliness: Double): TvLook {
        val h = Colors.toHsv(home)
        val turn = Colors.shortest(h[0], Colors.toHsv(accent)[0]).coerceIn(-40.0, 40.0)
        val mid = Colors.hsv(h[0] + turn / 2, h[1], h[2])
        val hueDelta = (abs(turn) / 2 / 360.0 * 255).roundToInt().coerceAtLeast(8)
        val tempo = if (bpm.isNaN()) 0.5 else ((bpm - 70.0) / 90.0).coerceIn(0.0, 1.0)
        val speed = (60 + 150 * (0.6 * tempo + 0.4 * liveliness.coerceIn(0.0, 1.0))).roundToInt().coerceIn(40, 230)
        return TvLook("lively", drift(mid, hueDelta = hueDelta, brightnessDelta = 110, speed = speed))
    }

    /** [c] held by the TV, moving by up to [hueDelta] and [brightnessDelta] (the TV's 0 to 255) at [speed] (0 to 255). */
    fun drift(c: Int, hueDelta: Int, brightnessDelta: Int, speed: Int): String = JSONObject()
        .put("styleName", "FOLLOW_COLOR")
        .put("isExpert", true)
        .put("algorithm", "MANUAL_HUE")
        .put(
            "colorSettings",
            JSONObject()
                .put("color", hsb(c))
                .put("colorDelta", JSONObject().put("hue", hueDelta.coerceIn(0, 255)).put("saturation", 0).put("brightness", brightnessDelta.coerceIn(0, 255)))
                .put("speed", speed.coerceIn(0, 255)),
        )
        .toString()

    /** A `FOLLOW_AUDIO` algorithm as its menu setting (the plain form). */
    fun audio(algorithm: String): String = JSONObject().put("styleName", "FOLLOW_AUDIO").put("isExpert", false).put("menuSetting", algorithm).toString()

    /** A `FOLLOW_AUDIO` algorithm in the expert form (the only one that works for Party). */
    fun audioExpert(algorithm: String): String = JSONObject().put("styleName", "FOLLOW_AUDIO").put("isExpert", true).put("algorithm", algorithm).toString()

    /** Hue, saturation and brightness of [c] on the TV's scale of 0 to 255. */
    fun hsb(c: Int): JSONObject {
        val hsv = Colors.toHsv(c)
        return JSONObject()
            .put("hue", Math.round(hsv[0] / 360.0 * 255).toInt().coerceIn(0, 255))
            .put("saturation", Math.round(hsv[1] * 255).toInt().coerceIn(0, 255))
            .put("brightness", Math.round(hsv[2] * 255).toInt().coerceIn(0, 255))
    }
}

/**
 * Which look the TV is to show when a capture frame of a song is heard:
 * - **The class** at that frame: the song's own, lifted for [HOLD_BEATS] beats after a chorus or a drop, lowered after a breakdown.
 * - **Mono** is calm, **Duo** is lively, in the song's colours.
 * - **Multi** goes through the modes, a new one every phrase ([phraseBeats], 32 beats unless the strength says otherwise). The phrases
 *   count from the last section event, put on the beat grid. A chorus or a drop starts with Party, a breakdown with a calmer mode.
 *   Before the tempo has locked, a Multi song keeps one mode, picked by its [seed].
 *
 * Plain Kotlin.
 */
object TvSchedule {
    const val HOLD_BEATS = 32

    fun phraseBeats(strength: String): Int = when (strength) {
        "subtle" -> 64
        "punchy" -> 16
        else -> 32
    }

    fun liveliness(energy: Double, strength: String): Double = when (strength) {
        "subtle" -> 0.4 * energy
        "punchy" -> (1.3 * energy).coerceAtMost(1.0)
        else -> energy
    }.coerceIn(0.0, 1.0)

    /** The class at capture frame [f]: the song's, lifted or lowered by the section in force then. */
    fun classAt(f: Long, a: Analysis, look: SongLook, rate: Int): LightClass {
        val e = a.sections.lastOrNull { it.frame <= f } ?: return look.cls
        val hold = if (a.periodFrames.isNaN()) 16.0 * rate else HOLD_BEATS * a.periodFrames
        if (f - e.frame > hold) return look.cls
        return if (e.up) look.cls.up() else look.cls.down()
    }

    /** The look at capture frame [f] of the song analysed in [a] with the look [look]; [modes] are the modes allowed. */
    fun lookAt(f: Long, a: Analysis, look: SongLook, rate: Int, strength: String, modes: List<TvLook>, seed: Int): TvLook {
        val live = liveliness(a.energyNow, strength)
        return when (classAt(f, a, look, rate)) {
            LightClass.MONO -> TvLooks.calm(look.home, live)
            LightClass.DUO -> TvLooks.lively(look.home, look.accent, a.bpm, live)
            LightClass.MULTI -> modeAt(f, a, rate, strength, modes, seed)
        }
    }

    /** The mode of an energetic song at capture frame [f]. */
    fun modeAt(f: Long, a: Analysis, rate: Int, strength: String, modes: List<TvLook>, seed: Int): TvLook {
        if (modes.isEmpty()) return TvLooks.MODES[0]
        val n = modes.size
        if (!a.locked || a.periodFrames.isNaN() || a.beatFrame.isNaN()) return modes[Math.floorMod(seed, n)]
        val e = a.sections.lastOrNull { it.frame <= f }
        val period = a.periodFrames
        val from = (e?.frame ?: a.firstFrame).toDouble()
        val anchor = a.beatFrame + Math.round((from - a.beatFrame) / period) * period
        val k = floor((f - anchor) / (phraseBeats(strength) * period)).toInt().coerceAtLeast(0)
        if (e != null && k == 0) {
            val first = if (e.up) listOf("party") else TvLooks.CALMER
            first.firstNotNullOfOrNull { name -> modes.firstOrNull { it.name == name } }?.let { return it }
        }
        val events = a.sections.count { it.frame <= f }
        return modes[Math.floorMod(seed + 3 * events + k, n)]
    }

    /** The capture frame at which the mode after [f] begins, or -1 if the mode does not change on its own (no grid, or not Multi). */
    fun nextSwitch(f: Long, a: Analysis, look: SongLook, rate: Int, strength: String): Long {
        if (classAt(f, a, look, rate) != LightClass.MULTI || !a.locked || a.periodFrames.isNaN() || a.beatFrame.isNaN()) return -1
        val e = a.sections.lastOrNull { it.frame <= f }
        val period = a.periodFrames
        val from = (e?.frame ?: a.firstFrame).toDouble()
        val anchor = a.beatFrame + Math.round((from - a.beatFrame) / period) * period
        val phrase = phraseBeats(strength) * period
        val k = floor((f - anchor) / phrase).toLong().coerceAtLeast(0)
        return Math.round(anchor + (k + 1) * phrase)
    }
}
