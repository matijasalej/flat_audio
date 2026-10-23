package io.github.matijasalej.wholeflat.sync

/**
 * The design of the lyrics picture on the TV (1920 x 1080) as numbers and the maths that moves it, kept apart from the drawing so that it
 * can be tested. The look is that of the mock that was agreed on: a solid background in the dominant colour of the cover; big bold
 * lines at the left that scroll up smoothly (0.35 s, ease-out) as the song goes on, the line that is sung now in white, the lines sung
 * before it in white at 55 % over the background, the lines to come in black at 45 %; a footer with the cover, the title, the artist
 * and a progress bar with the time elapsed and the time left. The names and marks of no streaming service are used.
 */
object LyricsLayout {
    const val W = 1920
    const val H = 1080

    // lyric lines
    const val LINE_X = 200f
    const val ACTIVE_Y = 250f // the top of the line that is sung now
    const val LINE_H = 118f
    const val SCROLL_S = 0.35
    const val LINE_SIZE = 78f
    const val LINE_MIN_SIZE = 50f
    const val LINE_MAX_WIDTH = (W - 2 * 200).toFloat()
    const val VISIBLE_TOP = 40f
    const val VISIBLE_BOTTOM = 820f

    // footer
    const val COVER_X = 200f
    const val COVER_Y = 880f
    const val COVER_SIZE = 132f
    const val TITLE_X = 360f
    const val TITLE_Y = 892f
    const val TITLE_SIZE = 46f
    const val ARTIST_Y = 950f
    const val ARTIST_SIZE = 36f
    const val TEXT_MAX_WIDTH = 520f // between the cover and the progress bar
    const val BAR_X0 = 900f
    const val BAR_X1 = (W - 200).toFloat()
    const val BAR_Y = 940f
    const val BAR_H = 6f
    const val TIME_Y = 958f
    const val TIME_SIZE = 28f

    // no lyrics: a big cover, the title and the artist in the middle, and a calm note
    const val BIG_COVER = 400f
    const val BIG_COVER_Y = 130f
    const val BIG_TITLE_Y = 600f
    const val BIG_TITLE_SIZE = 64f
    const val BIG_ARTIST_Y = 690f
    const val BIG_ARTIST_SIZE = 44f
    const val NOTE_Y = 790f
    const val NOTE_SIZE = 34f
    const val BIG_TEXT_MAX_WIDTH = (W - 2 * 240).toFloat()

    // the splash while the TV warms up
    const val SPLASH_SIZE = 120f

    /** How a line is drawn. */
    enum class Role { SUNG, ACTIVE, AHEAD }

    /** The fade of the colours of the two lines that change in the instant scroll (s). */
    const val INSTANT_FADE_S = 0.15

    /**
     * A line of the picture: which one, where its top is, how it is drawn, and (for the active line) how far the fade-in is (0 to 1).
     * [settling]: the line that was sung until a moment ago (instant scroll only), whose colour goes from white to the sung colour with [fade].
     */
    class Row(val index: Int, val y: Float, val role: Role, val fade: Double, val settling: Boolean = false)

    /** The ease-out of the scroll and of the fade (a cubic): 0 at 0, 1 at 1. */
    fun ease(u: Double): Double {
        val x = u.coerceIn(0.0, 1.0)
        return 1.0 - (1.0 - x) * (1.0 - x) * (1.0 - x)
    }

    /**
     * The lines to draw: the ones of the lines that fall between the top and the bottom of the text area, at the positions they have
     * [sinceActiveMs] after the line [active] began (-1: none has begun, the first waits in the next place; no scroll then). With [instant]
     * the lines do not move: they stand in their new places at once and only the colours of the two lines that change fade, in
     * [INSTANT_FADE_S]; a scroll moves every pixel of the text for a third of a second, which is the most the video encoder is asked for
     * between two key pictures (the TV was seen to conceal the pictures of a scroll with blocks and ghosts).
     */
    fun rows(lineCount: Int, active: Int, sinceActiveMs: Long, instant: Boolean = false): List<Row> {
        val e = if (active < 0 || instant) 1.0 else ease(sinceActiveMs / 1000.0 / SCROLL_S)
        val f = if (active < 0) 1.0 else if (instant) ease(sinceActiveMs / 1000.0 / INSTANT_FADE_S) else e
        val result = ArrayList<Row>()
        for (j in maxOf(0, active - 2)..minOf(lineCount - 1, active + 5)) {
            val y = ACTIVE_Y + ((j - active) + (1.0 - e)).toFloat() * LINE_H
            if (y < VISIBLE_TOP || y > VISIBLE_BOTTOM) continue
            val role = when {
                j < active -> Role.SUNG
                j == active -> Role.ACTIVE
                else -> Role.AHEAD
            }
            val settling = instant && role == Role.SUNG && j == active - 1 && f < 1.0
            result.add(Row(j, y, role, if (role == Role.ACTIVE || settling) f else 1.0, settling))
        }
        return result
    }

    /** The colours over the background [bg] (ARGB): the active line fades in from [ahead] to white with [fade]. */
    class Colors(bg: Int) {
        val background = bg
        val white = CoverColor.rgb(255, 255, 255)
        val sung = CoverColor.mix(bg, white, 0.55)
        val ahead = CoverColor.mix(bg, CoverColor.rgb(0, 0, 0), 0.45)
        val track = CoverColor.mix(bg, white, 0.30)
        val secondary = CoverColor.mix(bg, white, 0.70)
        val note = CoverColor.mix(bg, white, 0.55)

        fun active(fade: Double): Int = CoverColor.mix(ahead, white, fade)

        fun of(role: Role, fade: Double): Int = when (role) {
            Role.SUNG -> sung
            Role.ACTIVE -> active(fade)
            Role.AHEAD -> ahead
        }

        /** The colour of a row: a line that has just been sung goes from white to the sung colour. */
        fun of(row: Row): Int = if (row.settling) CoverColor.mix(white, sung, row.fade) else of(row.role, row.fade)
    }

    /** The biggest text size not above [size] (and not below [minSize]) at which [widthAt] says the text is no wider than [maxWidth]. */
    fun fitSize(widthAt: (Float) -> Float, maxWidth: Float, size: Float, minSize: Float): Float {
        if (widthAt(size) <= maxWidth) return size
        var s = size
        while (s > minSize) {
            s = maxOf(minSize, s - 2f)
            if (widthAt(s) <= maxWidth) return s
        }
        return minSize
    }

    /** "m:ss" (and "h:mm:ss" from an hour) for a time in ms; negative counts as 0. */
    fun clock(ms: Long): String {
        val total = maxOf(0L, ms) / 1000
        val s = total % 60
        val m = (total / 60) % 60
        val h = total / 3600
        return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s) else String.format(java.util.Locale.US, "%d:%02d", m, s)
    }

    /** The time left as shown at the right of the bar: a minus and the time (or nothing when the length of the track is not known). */
    fun remaining(positionMs: Long, durationMs: Long): String = if (durationMs <= 0) "" else "-" + clock(durationMs - positionMs)

    /** How far along the track is (0 to 1); 0 when the length is not known. */
    fun progress(positionMs: Long, durationMs: Long): Double = if (durationMs <= 0) 0.0 else (positionMs.toDouble() / durationMs).coerceIn(0.0, 1.0)
}
