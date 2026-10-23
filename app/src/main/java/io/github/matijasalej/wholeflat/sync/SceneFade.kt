package io.github.matijasalej.wholeflat.sync

/**
 * The change from one track to the next in the picture of the TV, as a **cross-fade** instead of a cut: a cut changes every pixel of the
 * picture at once (the background, the cover, the text), which is the most that the hardware encoder is ever asked to do in one picture,
 * and it comes just when the track changes, when the rest of the box is busy with the new track too. Over [fadeNanos] (a second) the new
 * scene is blended over the last picture of the old one; the encoder then gets a part of the change in every picture.
 *
 * The fade is counted in the capture time of the picture (the time its sound is heard), so the picture that goes with a sound is the
 * same whenever it is drawn. [step] says, for the scene of a picture, the scene to fade from (null: none, the picture is just the scene)
 * and how much of the new one shows (0 to 1). A scene is told from the one before it by its [key]; the first scene of a stream, and the first
 * after [reset] (the picture was not the lyrics for a while), are not faded in. Not thread safe: used by the one thread that draws. Plain Kotlin.
 */
class SceneFade<T : Any>(private val fadeNanos: Long = FADE_NANOS) {
    class Step<T>(val scene: T, val from: T?, val alpha: Float)

    private var key: String? = null
    private var shown: T? = null
    private var from: T? = null
    private var start = 0L

    /** The scene [scene] of the track [key] is to be drawn for the picture of capture time [captureNanos]. */
    fun step(key: String, scene: T, captureNanos: Long): Step<T> {
        val before = this.key
        val last = shown
        if (before != null && before != key && last != null) {
            // the track changed: the old track's last picture stays under the new one while it comes in (a change in the middle of a
            // fade fades from the scene that was coming in)
            from = last
            start = captureNanos
        }
        this.key = key
        shown = scene
        val old = from ?: return Step(scene, null, 1f)
        val u = (captureNanos - start).toDouble() / fadeNanos
        if (u >= 1.0) {
            from = null
            return Step(scene, null, 1f)
        }
        return Step(scene, old, smooth(u).toFloat())
    }

    /** The picture was not a scene for a while (the splash, black): the next scene comes in as it is. */
    fun reset() {
        key = null
        shown = null
        from = null
    }

    companion object {
        /** About a second: long enough to spread the change over thirty pictures, short enough to be over before the first line is sung. */
        const val FADE_NANOS = 1_000_000_000L

        /** Smoothstep: 0 at 0, 1 at 1, slow at both ends. */
        fun smooth(u: Double): Double {
            val x = u.coerceIn(0.0, 1.0)
            return x * x * (3.0 - 2.0 * x)
        }
    }
}
