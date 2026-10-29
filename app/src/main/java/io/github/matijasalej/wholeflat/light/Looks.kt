package io.github.matijasalej.wholeflat.light

/** How many colours a song's lights use: one, a home colour and an accent, or three to four. */
enum class LightClass(val key: String) {
    MONO("mono"),
    DUO("duo"),
    MULTI("multi"),
    ;

    fun up(): LightClass = if (this == MONO) DUO else MULTI

    fun down(): LightClass = if (this == MULTI) DUO else MONO

    companion object {
        fun of(key: String?): LightClass? = entries.firstOrNull { it.key == key }
    }
}

/**
 * The look of one song: its class, its home colour, the accent of Duo, the four colours of Multi, and why it came out so (for the status
 * and the review). Made by [Looks]; never changed.
 */
class SongLook(
    /** The song it belongs to ([io.github.matijasalej.wholeflat.sync.NowPlaying.key]). */
    val key: String,
    val cls: LightClass,
    val home: Int,
    val accent: Int,
    val multi: IntArray,
    val why: String,
)

/**
 * Chooses a song's look.
 *
 * **The class:**
 * - Mono for calm songs or songs without a clear beat (energy < [MONO_ENERGY] or clarity < [MONO_CLARITY]).
 * - Multi for energetic songs with a clear beat (energy ≥ [MULTI_ENERGY] and clarity ≥ [MULTI_CLARITY]).
 * - Duo for everything else.
 * - A colourful cover lifts Duo to Multi from [COLOURFUL_ENERGY].
 * - His override wins.
 *
 * **The colours** are the cover's, tinted by the mood (Palmer 2013, Whiteford 2018):
 * - More energy is more saturated and brighter.
 * - A happy song turns its hue up to [TINT_DEGREES] towards yellow-orange and lightens it. A sad song turns towards blue and darkens it.
 * - A grey cover takes the colour of its mood quadrant.
 * - The Duo accent is the cover's second hue, or a neighbour (calm) or a split complement (energetic).
 *
 * Plain Kotlin.
 */
object Looks {
    const val MONO_ENERGY = 0.35
    const val MONO_CLARITY = 0.3
    const val MULTI_ENERGY = 0.7
    const val MULTI_CLARITY = 0.5
    const val COLOURFUL_ENERGY = 0.55
    const val TINT_DEGREES = 25.0
    const val WARM_HUE = 40.0
    const val COOL_HUE = 225.0

    /** The class for a song of [energy] and beat [clarity], [colourful] cover, and his [override]. */
    fun classify(energy: Double, clarity: Double, colourful: Boolean, override: LightClass?): LightClass {
        if (override != null) return override
        if (energy < MONO_ENERGY || clarity < MONO_CLARITY) return LightClass.MONO
        if (energy >= MULTI_ENERGY && clarity >= MULTI_CLARITY) return LightClass.MULTI
        if (colourful && energy >= COLOURFUL_ENERGY) return LightClass.MULTI
        return LightClass.DUO
    }

    /** The look of song [key] from what is known of it so far. */
    fun choose(key: String, energy: Double, clarity: Double, mood: Double, hues: CoverHues, override: LightClass?): SongLook {
        val cls = classify(energy, clarity, hues.colourful, override)
        val e = energy.coerceIn(0.0, 1.0)
        val m = mood.coerceIn(0.0, 1.0)
        val happy = (m - 0.5) * 2 // -1 sad to 1 happy
        val sat = (0.55 + 0.4 * e - 0.15 * maxOf(0.0, happy)).coerceIn(0.35, 0.95)
        val value = (0.62 + 0.33 * e + 0.08 * happy).coerceIn(0.3, 1.0)
        val dominant = hues.dominant
        val home: Int
        val homeHue: Double
        val why = StringBuilder()
        if (dominant == null) {
            // a grey cover: the colour of the mood
            val q = quadrant(e, m)
            homeHue = q[0]
            home = Colors.hsv(q[0], q[1], q[2])
            why.append("grey cover, mood colour")
        } else {
            homeHue = tint(dominant.hue, happy)
            home = Colors.hsv(homeHue, sat, value)
            why.append("cover hue ${dominant.hue.toInt()}° tinted to ${homeHue.toInt()}°")
        }
        val second = hues.second()
        val accentHue = when {
            second != null -> tint(second.hue, happy)
            e < 0.5 -> Colors.turnToward(homeHue, if (happy >= 0) WARM_HUE else COOL_HUE, 40.0).let { if (Colors.hueDistance(it, homeHue) < 20) (homeHue + 40) % 360 else it }
            else -> (homeHue + 150.0) % 360.0
        }
        val accent = Colors.hsv(accentHue, (sat - 0.05).coerceAtLeast(0.35), value)
        val others = hues.clusters.drop(1).filter { Colors.hueDistance(it.hue, accentHue) >= 30 && Colors.hueDistance(it.hue, homeHue) >= 30 }.map { tint(it.hue, happy) }
        val third = others.getOrNull(0) ?: ((homeHue + 75.0) % 360.0)
        val fourth = others.getOrNull(1) ?: ((homeHue + 225.0) % 360.0)
        val multiSat = (sat + 0.1).coerceAtMost(1.0)
        val multi = intArrayOf(home, accent, Colors.hsv(third, multiSat, value), Colors.hsv(fourth, multiSat, value))
        why.append("; energy %.2f, clarity %.2f, mood %.2f%s → %s".format(e, clarity, m, if (override != null) ", his choice" else "", cls.key))
        return SongLook(key, cls, home, accent, multi, why.toString())
    }

    /** [hue] turned towards warm (happy > 0) or cool (happy < 0) by up to [TINT_DEGREES] times how happy or sad. */
    fun tint(hue: Double, happy: Double): Double =
        if (happy >= 0) Colors.turnToward(hue, WARM_HUE, TINT_DEGREES * happy) else Colors.turnToward(hue, COOL_HUE, TINT_DEGREES * -happy)

    /** Hue, saturation and value for a grey cover, by energy and mood. */
    fun quadrant(energy: Double, mood: Double): DoubleArray = when {
        energy >= 0.5 && mood >= 0.5 -> doubleArrayOf(40.0, 0.9, 1.0) // yellow-orange
        energy >= 0.5 -> doubleArrayOf(320.0, 0.85, 0.9) // red-magenta
        mood >= 0.5 -> doubleArrayOf(35.0, 0.45, 0.75) // pale amber
        else -> doubleArrayOf(235.0, 0.7, 0.45) // dim indigo
    }
}
