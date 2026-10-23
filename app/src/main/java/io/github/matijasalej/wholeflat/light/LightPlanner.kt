package io.github.matijasalej.wholeflat.light

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor

/**
 * The 16 zone colours of the lights at one capture frame, from the analysis of the music and the song's look.
 *
 * - **Colours.** Mono is the home colour on every zone. Duo is the home colour on the left and the accent on the right, blended across
 *   the top; they swap at every section event. Multi has four colours on four groups (left, top left, top right, right), one step round
 *   every four beats. Colour changes are smoothed round the hue circle (no grey in between): slowly for Mono, quickly for Multi.
 * - **Sections.** A chorus or a drop (a section event up) lifts the class one step for [HOLD_BEATS] beats, a breakdown lowers it. In a
 *   lifted Multi the pulse runs round the frame once a beat (a chase).
 * - **Pulses** (only on a locked, clear beat):
 *   - below [SWELL_BPM], one swell a beat;
 *   - up to [HALF_TIME_BPM], a hit on every beat that decays in [HIT_DECAY] of a beat;
 *   - above that, a hit on every second beat.
 *   - Mono blooms from the top centre outwards.
 *   - How deep a pulse goes grows with the energy and the strength setting. It is capped at [BAND_DEPTH] on the screen's glow band,
 *     and at [FlashLimiter.RED_DEPTH] on saturated red.
 *   - Every pulse asks the [FlashLimiter]: two start at least 360 ms apart.
 * - **No beat to pulse on** (not locked, unclear, silence): the brightness follows the loudness, quickly up and slowly down.
 * - **Song start:** the home colour at [INTRO_LEVEL] until the tempo locks. Silence dims to [SILENT_LEVEL].
 *
 * Plain Kotlin, one thread (the painter's).
 */
class LightPlanner(private val rate: Int = 44100) {
    /** Where the lights are drawn: the whole picture (screen off) or the glow band round the lyrics (screen on, gentler pulses). */
    enum class Variant { FULL, BAND }

    enum class Strength(val key: String, val factor: Double) {
        SUBTLE("subtle", 0.5),
        ADAPTIVE("adaptive", 1.0),
        PUNCHY("punchy", 1.35),
        ;

        companion object {
            fun of(key: String?): Strength = entries.firstOrNull { it.key == key } ?: ADAPTIVE
        }
    }

    private val limiter = FlashLimiter.forRate(rate)
    private val base = IntArray(Zones.COUNT)
    private val target = IntArray(Zones.COUNT)

    // the base colours as they move towards the targets, kept as HSV in doubles (8 bit steps would stall a slow fade)
    private val baseH = DoubleArray(Zones.COUNT)
    private val baseS = DoubleArray(Zones.COUNT)
    private val baseV = DoubleArray(Zones.COUNT)
    private val env = DoubleArray(Zones.COUNT)
    private var haveBase = false
    private var lastT = Long.MIN_VALUE
    private var lookKey: String? = null
    private var level = INTRO_LEVEL
    private var follow = 0.5
    private var seenBeat = Double.NaN
    private var pulseBeat = Double.NaN
    private var halfAnchor = Double.NaN

    /** The class shown at the last frame (the song's, or lifted or lowered by a section). */
    @Volatile
    var classNow = LightClass.MONO
        private set

    /** True if the last frame pulsed on the beat (false: it followed the loudness). */
    @Volatile
    var pulsing = false
        private set

    val pulsesAllowed: Long get() = limiter.allowed
    val pulsesHeld: Long get() = limiter.held

    /** Fills [out] (16 colours, the TV's zone order) for capture frame [t]. */
    fun frame(t: Long, a: Analysis, look: SongLook, strength: Strength, variant: Variant, out: IntArray) {
        val dt = if (lastT == Long.MIN_VALUE) 0.0 else ((t - lastT).toDouble() / rate).coerceIn(0.0, 0.5)
        lastT = t
        if (look.key != lookKey) {
            lookKey = look.key
            seenBeat = Double.NaN
            pulseBeat = Double.NaN
            halfAnchor = Double.NaN
        }
        val up = activeSection(t, a)
        val cls = when (up) {
            null -> look.cls
            true -> look.cls.up()
            false -> look.cls.down()
        }
        classNow = cls
        targets(t, a, look, cls, target)
        val tau = when (cls) {
            LightClass.MONO -> 0.6
            LightClass.DUO -> 0.35
            LightClass.MULTI -> 0.15
        }
        val k = if (!haveBase) 1.0 else 1.0 - exp(-dt / tau)
        for (z in 0 until Zones.COUNT) {
            val hsv = Colors.toHsv(target[z])
            // a grey has no hue to turn from or to
            if (!haveBase || baseS[z] < 0.05 || hsv[1] < 0.05) baseH[z] = hsv[0] else baseH[z] = (baseH[z] + Colors.shortest(baseH[z], hsv[0]) * k + 360.0) % 360.0
            baseS[z] += (hsv[1] - baseS[z]) * k
            baseV[z] += (hsv[2] - baseV[z]) * k
            base[z] = Colors.hsv(baseH[z], baseS[z], baseV[z])
        }
        haveBase = true

        val songS = (t - a.firstFrame).toDouble() / rate
        val wanted = when {
            a.silent -> SILENT_LEVEL
            !a.locked && songS < INTRO_S -> INTRO_LEVEL
            else -> 1.0
        }
        level += (wanted - level) * (1.0 - exp(-dt / LEVEL_TAU))

        val beat = a.locked && a.clarity >= PULSE_CLARITY && !a.silent && !a.periodFrames.isNaN() && !a.beatFrame.isNaN()
        pulsing = beat
        if (beat) {
            val chase = cls == LightClass.MULTI && up == true
            pulses(t, a, cls, chase)
            var depth = when (cls) {
                LightClass.MONO -> if (a.bpm < SWELL_BPM) 0.10 + 0.10 * a.energyNow else 0.15 + 0.15 * a.energyNow
                LightClass.DUO -> 0.15 + 0.25 * a.energyNow
                LightClass.MULTI -> 0.20 + 0.25 * a.energyNow
            } * strength.factor
            depth = depth.coerceAtMost(MAX_DEPTH)
            if (variant == Variant.BAND) depth = depth.coerceAtMost(BAND_DEPTH)
            for (z in 0 until Zones.COUNT) {
                val d = if (FlashLimiter.isSaturatedRed(base[z])) minOf(depth, FlashLimiter.RED_DEPTH) else depth
                out[z] = finish(base[z], level * (1.0 - d + d * env[z]), a.silent)
            }
        } else {
            val loud = ((a.loudDb - FOLLOW_FLOOR_DB) / FOLLOW_RANGE_DB).coerceIn(0.0, 1.0)
            val ftau = if (loud > follow) 0.2 else 1.0
            follow += (loud - follow) * (1.0 - exp(-dt / ftau))
            for (z in 0 until Zones.COUNT) out[z] = finish(base[z], level * (0.75 + 0.25 * follow), a.silent)
        }
    }

    /** The pulse envelope of every zone at [t] into [env] (0 to 1, 1 at the onset). */
    private fun pulses(t: Long, a: Analysis, cls: LightClass, chase: Boolean) {
        val period = a.periodFrames
        val half = a.bpm > HALF_TIME_BPM
        val step = if (half) 2 * period else period
        var beat = a.beatFrame + floor((t - a.beatFrame) / period) * period
        if (half) {
            if (halfAnchor.isNaN()) halfAnchor = beat
            val n = Math.round((beat - halfAnchor) / period)
            if (n % 2L != 0L) beat -= period
        }
        if (seenBeat.isNaN() || abs(beat - seenBeat) > 0.5 * step) {
            seenBeat = beat
            if (limiter.allow(beat.toLong())) pulseBeat = beat
        }
        if (pulseBeat.isNaN()) {
            env.fill(0.0)
            return
        }
        val stepS = step / rate
        val swell = a.bpm < SWELL_BPM
        for (z in 0 until Zones.COUNT) {
            val delay = when {
                chase -> z * stepS / Zones.COUNT
                cls == LightClass.MONO -> abs(z - (Zones.COUNT - 1) / 2.0) * BLOOM_STEP_S
                else -> 0.0
            }
            var since = (t - pulseBeat) / rate - delay
            if (since < 0) since += stepS // this zone still shows the pulse before
            env[z] = if (swell) 0.5 * (1.0 + Math.cos(2 * Math.PI * since / stepS)) else exp(-since / (HIT_DECAY * stepS))
        }
    }

    /** The base colours of [cls] at [t]. */
    private fun targets(t: Long, a: Analysis, look: SongLook, cls: LightClass, out: IntArray) {
        when (cls) {
            LightClass.MONO -> out.fill(look.home)
            LightClass.DUO -> {
                val swapped = a.sections.count { it.frame <= t } % 2 == 1
                val left = if (swapped) look.accent else look.home
                val right = if (swapped) look.home else look.accent
                for (z in 0 until Zones.LEFT) out[z] = left
                for (j in 0 until Zones.TOP) out[Zones.LEFT + j] = Colors.mixHue(left, right, ((j - 2) / 3.0).coerceIn(0.0, 1.0))
                for (z in Zones.LEFT + Zones.TOP until Zones.COUNT) out[z] = right
            }
            LightClass.MULTI -> {
                val anchor = a.sections.lastOrNull { it.frame <= t }?.frame ?: a.firstFrame
                val stepFrames = if (a.periodFrames.isNaN()) 4.0 * rate else 4.0 * a.periodFrames
                val step = floor((t - anchor) / stepFrames).toInt().coerceAtLeast(0)
                for (z in 0 until Zones.COUNT) out[z] = look.multi[(z / 4 + step) % 4]
            }
        }
    }

    /** The section in force at [t]: true lifted (chorus, drop), false lowered (breakdown), null none. */
    private fun activeSection(t: Long, a: Analysis): Boolean? {
        val e = a.sections.lastOrNull { it.frame <= t } ?: return null
        val hold = if (a.periodFrames.isNaN()) 16.0 * rate else HOLD_BEATS * a.periodFrames
        return if (t - e.frame > hold) null else e.up
    }

    private fun finish(c: Int, f: Double, silent: Boolean): Int {
        val out = Colors.scale(c, f.coerceIn(0.0, 1.0))
        val peak = Colors.peak(out)
        if (silent || peak >= FLOOR_PEAK || Colors.peak(c) == 0) return out
        // dim LEDs look dead and flicker: keep the brightest channel at the floor
        return Colors.scale(out, FLOOR_PEAK.toDouble() / maxOf(1, peak))
    }

    companion object {
        const val SWELL_BPM = 75.0
        const val HALF_TIME_BPM = 150.0
        const val HIT_DECAY = 0.4
        const val BLOOM_STEP_S = 0.008
        const val HOLD_BEATS = 32
        const val PULSE_CLARITY = 0.3
        const val MAX_DEPTH = 0.6
        const val BAND_DEPTH = 0.30
        const val INTRO_LEVEL = 0.6
        const val INTRO_S = 8.0
        const val SILENT_LEVEL = 0.3
        const val LEVEL_TAU = 0.8
        const val FOLLOW_FLOOR_DB = -40.0
        const val FOLLOW_RANGE_DB = 30.0

        /** About 15 % of full brightness. */
        const val FLOOR_PEAK = 38
    }
}
