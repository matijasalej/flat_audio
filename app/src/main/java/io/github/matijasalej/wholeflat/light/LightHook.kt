package io.github.matijasalej.wholeflat.light

import io.github.matijasalej.wholeflat.sync.Picture

/**
 * Where the TV picture asks for the lights: the 16 zone colours for the picture of a capture frame, or null to draw the picture as it
 * always was. The frame is the one the picture's time stamp is made from (the stream's own clock), so the lights go with exactly the
 * audio that the TV plays with that picture; -1 if the stream has no clock yet. The light engine sets [source] while it runs. The frame
 * loop of the stream calls [frame]; the preview of the debug page calls [preview], which has a planner of its own (the planner keeps
 * state and is not shared between threads). Plain Kotlin.
 */
object LightHook {
    @Volatile
    var source: ((captureFrame: Long, captureNanos: Long, picture: Picture) -> IntArray?)? = null

    @Volatile
    var previewSource: ((captureFrame: Long, captureNanos: Long, picture: Picture) -> IntArray?)? = null

    /** How wide the glow band round the lyrics is, as a share of the picture's width (the setting `lights_band_pct`). */
    @Volatile
    var bandShare: Double = 0.05

    fun frame(captureFrame: Long, captureNanos: Long, picture: Picture): IntArray? = ask(source, captureFrame, captureNanos, picture)

    fun preview(captureFrame: Long, captureNanos: Long, picture: Picture): IntArray? = ask(previewSource, captureFrame, captureNanos, picture)

    private fun ask(s: ((Long, Long, Picture) -> IntArray?)?, captureFrame: Long, captureNanos: Long, picture: Picture): IntArray? = try {
        s?.invoke(captureFrame, captureNanos, picture)
    } catch (t: Throwable) {
        null // the lights never stop the picture
    }
}
