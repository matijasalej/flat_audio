package io.github.matijasalej.wholeflat.light

import io.github.matijasalej.wholeflat.sync.Picture

/**
 * Where the TV picture asks for the lights: the 16 zone colours for the picture of a capture time, or null to draw the picture as it
 * always was. The light engine sets [source] while it runs. The frame loop of the stream calls [frame]; the preview of the debug page calls
 * [preview], which has a planner of its own (the planner keeps state and is not shared between threads). Plain Kotlin.
 */
object LightHook {
    @Volatile
    var source: ((captureNanos: Long, picture: Picture) -> IntArray?)? = null

    @Volatile
    var previewSource: ((captureNanos: Long, picture: Picture) -> IntArray?)? = null

    /** How wide the glow band round the lyrics is, as a share of the picture's width (the setting `lights_band_pct`). */
    @Volatile
    var bandShare: Double = 0.05

    fun frame(captureNanos: Long, picture: Picture): IntArray? = ask(source, captureNanos, picture)

    fun preview(captureNanos: Long, picture: Picture): IntArray? = ask(previewSource, captureNanos, picture)

    private fun ask(s: ((Long, Picture) -> IntArray?)?, captureNanos: Long, picture: Picture): IntArray? = try {
        s?.invoke(captureNanos, picture)
    } catch (t: Throwable) {
        null // the lights never stop the picture
    }
}
