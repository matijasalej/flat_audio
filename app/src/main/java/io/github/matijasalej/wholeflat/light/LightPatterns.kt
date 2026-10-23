package io.github.matijasalej.wholeflat.light

/**
 * Test pictures for the lights, for checking on the TV what the Ambilight makes of a painted picture (`/lights/test`):
 * - `solid`: one colour everywhere;
 * - `split`: red on the left, green on top, blue on the right;
 * - `zones`: every zone its own hue, round the colour circle in the TV's zone order (shows how the TV numbers its zones);
 * - `chase`: one white zone going round, a zone every [stepMs];
 * - `pulse`: [color] on every zone, flashing at [bpm] from the start of the test (60 % deep, decaying in 0.4 of a beat);
 * - `steps`: black and [color] in turn, every [stepMs] (for the delay from the picture to the lights);
 * - `clicks`: white for 100 ms from every whole second of the capture, the frames on which the calibration clicks are (`/tv/clicks`):
 *   a film of the TV with its sound then shows how far the light is from the click ([atFrame]; spikes/lights_offset.py).
 *
 * Plain Kotlin: the 16 colours of a pattern at [elapsedMs] since its start.
 */
class LightPatterns(val pattern: String, val color: Int = Colors.rgb(255, 255, 255), val bpm: Double = 120.0, val stepMs: Int = 250) {
    fun at(elapsedMs: Long): IntArray = when (pattern) {
        "solid" -> IntArray(Zones.COUNT) { color }
        "split" -> IntArray(Zones.COUNT) {
            when {
                it < Zones.LEFT -> Colors.rgb(255, 0, 0)
                it < Zones.LEFT + Zones.TOP -> Colors.rgb(0, 255, 0)
                else -> Colors.rgb(0, 0, 255)
            }
        }
        "zones" -> IntArray(Zones.COUNT) { Colors.hsv(it * 360.0 / Zones.COUNT, 1.0, 1.0) }
        "chase" -> {
            val lit = ((elapsedMs / stepMs) % Zones.COUNT).toInt()
            IntArray(Zones.COUNT) { if (it == lit) Colors.rgb(255, 255, 255) else Colors.rgb(0, 0, 0) }
        }
        "pulse" -> {
            val period = 60_000.0 / bpm
            val since = elapsedMs % period
            val f = 0.4 + 0.6 * Math.exp(-since / (0.4 * period))
            IntArray(Zones.COUNT) { Colors.scale(color, f) }
        }
        "steps" -> {
            val on = (elapsedMs / stepMs) % 2 == 1L
            IntArray(Zones.COUNT) { if (on) color else Colors.rgb(0, 0, 0) }
        }
        else -> IntArray(Zones.COUNT)
    }

    /** True if the pattern is set by the capture frame rather than the time since its start. */
    val byFrame: Boolean get() = pattern == "clicks"

    /** The colours of the `clicks` pattern at capture [frame] (at [rate]): white for the first 100 ms of every second, else black. */
    fun atFrame(frame: Long, rate: Int): IntArray {
        val inSecond = ((frame % rate) + rate) % rate
        val on = inSecond < rate / 10
        return IntArray(Zones.COUNT) { if (on) Colors.rgb(255, 255, 255) else Colors.rgb(0, 0, 0) }
    }

    companion object {
        val NAMES = listOf("solid", "split", "zones", "chase", "pulse", "steps", "clicks")

        /** "ff8800" or "#ff8800" as a colour; null if it is not one. */
        fun parseColor(s: String?): Int? {
            val hex = s?.trim()?.removePrefix("#") ?: return null
            if (hex.length != 6) return null
            val v = hex.toIntOrNull(16) ?: return null
            return v or (0xFF shl 24)
        }
    }
}
