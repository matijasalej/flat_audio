package io.github.matijasalej.wholeflat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import io.github.matijasalej.wholeflat.sync.CoverColor
import io.github.matijasalej.wholeflat.sync.Lrc
import io.github.matijasalej.wholeflat.sync.LyricsLayout
import io.github.matijasalej.wholeflat.sync.Picture

/**
 * Draws the picture of the TV's stream on a canvas of 1920 x 1080: the splash, the lyrics (or the cover with a calm note when there
 * are none), or black. The numbers are in [LyricsLayout]; the font is Figtree (SIL Open Font License, in the assets). Nothing here is
 * the design of any streaming service. Not thread safe: one drawer per thread.
 */
class SceneDrawer(context: Context) {
    private val lineFace = font(context, "Figtree-ExtraBold.ttf", Typeface.create(Typeface.DEFAULT, Typeface.BOLD))
    private val titleFace = font(context, "Figtree-SemiBold.ttf", Typeface.create(Typeface.DEFAULT, Typeface.BOLD))
    private val bodyFace = font(context, "Figtree-Medium.ttf", Typeface.DEFAULT)

    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shape = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private val srcRect = Rect()

    // sizes that were worked out for a text (a long line is shrunk to fit), by face, size, width and text
    private val fitted = object : LinkedHashMap<String, Float>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Float>?): Boolean = size > 256
    }

    private var coverSource: Bitmap? = null
    private var coverScaled: Bitmap? = null
    private var coverScaledSize = 0

    /**
     * Draws [picture] with what is known of the track in [scene]. With [fadeFrom] (the last picture of the track before) and a [fadeAlpha]
     * under 1 the lyrics picture is the old scene with the new one blended over it, which is how the track changes in the TV's stream.
     */
    fun draw(canvas: Canvas, picture: Picture, scene: TvScene?, fadeFrom: TvScene? = null, fadeAlpha: Float = 1f) {
        // the design is made for 1920 x 1080; a smaller picture (the setting `tv_video`) is the same design on a smaller canvas
        val scale = canvas.width.toFloat() / LyricsLayout.W
        val scaled = canvas.width > 0 && Math.abs(scale - 1f) > 0.001f
        if (scaled) {
            canvas.save()
            canvas.scale(scale, scale)
        }
        try {
            when (picture) {
                Picture.BLACK -> canvas.drawColor(Color.BLACK)
                Picture.SPLASH -> splash(canvas, scene?.background ?: CoverColor.DEFAULT)
                Picture.LYRICS -> when {
                    scene == null -> splash(canvas, CoverColor.DEFAULT)
                    fadeFrom != null && fadeAlpha < 0.999f -> fade(canvas, fadeFrom, scene, fadeAlpha)
                    else -> lyrics(canvas, scene)
                }
            }
        } finally {
            if (scaled) canvas.restore()
        }
    }

    // ---- the splash ------------------------------------------------------------------------------

    private fun splash(canvas: Canvas, background: Int) {
        canvas.drawColor(background)
        text.typeface = lineFace
        text.textSize = LyricsLayout.SPLASH_SIZE
        text.textAlign = Paint.Align.CENTER
        text.color = Color.WHITE
        val y = LyricsLayout.H / 2f - (text.ascent() + text.descent()) / 2f
        canvas.drawText("Whole Flat", LyricsLayout.W / 2f, y, text)
        text.textAlign = Paint.Align.LEFT
    }

    // ---- the change of track ---------------------------------------------------------------------

    /** The old scene as it was, and the new one over it with [alpha] of its strength (each scene fills the picture, so the layer is opaque inside). */
    private fun fade(canvas: Canvas, from: TvScene, to: TvScene, alpha: Float) {
        lyrics(canvas, from)
        // a canvas that will not make the layer gets the new scene as it is: a problem of the fade is never a problem of the stream
        val layer = try {
            canvas.saveLayerAlpha(0f, 0f, LyricsLayout.W.toFloat(), LyricsLayout.H.toFloat(), (alpha.coerceIn(0f, 1f) * 255f).toInt())
        } catch (t: Throwable) {
            -1
        }
        if (layer < 0) {
            lyrics(canvas, to)
            return
        }
        try {
            lyrics(canvas, to)
        } finally {
            canvas.restoreToCount(layer)
        }
    }

    // ---- the lyrics ------------------------------------------------------------------------------

    private fun lyrics(canvas: Canvas, sc: TvScene) {
        val colors = LyricsLayout.Colors(sc.background)
        canvas.drawColor(sc.background)
        val lines = sc.lines
        if (lines.isNullOrEmpty() || sc.lyricsStatus != LyricsStatus.SYNCED || sc.positionMs < 0) {
            noLyrics(canvas, sc, colors)
            return
        }
        val pos = sc.positionMs
        val active = Lrc.lineAt(lines, pos)
        val since = if (active >= 0) pos - lines[active].timeMs else 0L
        text.typeface = lineFace
        text.textAlign = Paint.Align.LEFT
        text.textSize = LyricsLayout.LINE_SIZE
        val ascent = -text.ascent() // the lines are placed by the metrics of the full size, also when one is shrunk
        for (row in LyricsLayout.rows(lines.size, active, since, sc.instantScroll)) {
            val s = lines[row.index].text
            if (s.isBlank()) continue
            val (shown, size) = fit(s, lineFace, LyricsLayout.LINE_SIZE, LyricsLayout.LINE_MIN_SIZE, LyricsLayout.LINE_MAX_WIDTH)
            text.typeface = lineFace
            text.textSize = size
            text.color = colors.of(row)
            canvas.drawText(shown, LyricsLayout.LINE_X, row.y + ascent, text)
        }
        footer(canvas, sc, colors)
    }

    private fun footer(canvas: Canvas, sc: TvScene, colors: LyricsLayout.Colors) {
        drawCover(canvas, sc.cover, sc.coverSmall, LyricsLayout.COVER_X, LyricsLayout.COVER_Y, LyricsLayout.COVER_SIZE, colors)
        text.textAlign = Paint.Align.LEFT
        drawText(canvas, sc.title, titleFace, LyricsLayout.TITLE_SIZE, LyricsLayout.TITLE_X, LyricsLayout.TITLE_Y, Color.WHITE, LyricsLayout.TEXT_MAX_WIDTH)
        drawText(canvas, sc.artist, bodyFace, LyricsLayout.ARTIST_SIZE, LyricsLayout.TITLE_X, LyricsLayout.ARTIST_Y, colors.secondary, LyricsLayout.TEXT_MAX_WIDTH)
        if (sc.durationMs <= 0 || sc.positionMs < 0) return
        // the bar: the track, and the part that has been played
        shape.style = Paint.Style.FILL
        shape.color = colors.track
        rect.set(LyricsLayout.BAR_X0, LyricsLayout.BAR_Y, LyricsLayout.BAR_X1, LyricsLayout.BAR_Y + LyricsLayout.BAR_H)
        canvas.drawRoundRect(rect, 3f, 3f, shape)
        val played = LyricsLayout.progress(sc.positionMs, sc.durationMs)
        shape.color = Color.WHITE
        rect.set(LyricsLayout.BAR_X0, LyricsLayout.BAR_Y, LyricsLayout.BAR_X0 + (LyricsLayout.BAR_X1 - LyricsLayout.BAR_X0) * played.toFloat(), LyricsLayout.BAR_Y + LyricsLayout.BAR_H)
        canvas.drawRoundRect(rect, 3f, 3f, shape)
        // the times: elapsed at the left, a minus and what is left at the right
        drawText(canvas, LyricsLayout.clock(sc.positionMs), bodyFace, LyricsLayout.TIME_SIZE, LyricsLayout.BAR_X0, LyricsLayout.TIME_Y, colors.secondary, 200f)
        text.textAlign = Paint.Align.RIGHT
        drawText(canvas, LyricsLayout.remaining(sc.positionMs, sc.durationMs), bodyFace, LyricsLayout.TIME_SIZE, LyricsLayout.BAR_X1, LyricsLayout.TIME_Y, colors.secondary, 200f)
        text.textAlign = Paint.Align.LEFT
    }

    // ---- no lyrics -------------------------------------------------------------------------------

    private fun noLyrics(canvas: Canvas, sc: TvScene, colors: LyricsLayout.Colors) {
        val cx = LyricsLayout.W / 2f
        drawCover(canvas, sc.cover, sc.coverBig, cx - LyricsLayout.BIG_COVER / 2f, LyricsLayout.BIG_COVER_Y, LyricsLayout.BIG_COVER, colors)
        text.textAlign = Paint.Align.CENTER
        drawText(canvas, sc.title, lineFace, LyricsLayout.BIG_TITLE_SIZE, cx, LyricsLayout.BIG_TITLE_Y, Color.WHITE, LyricsLayout.BIG_TEXT_MAX_WIDTH)
        drawText(canvas, sc.artist, bodyFace, LyricsLayout.BIG_ARTIST_SIZE, cx, LyricsLayout.BIG_ARTIST_Y, colors.secondary, LyricsLayout.BIG_TEXT_MAX_WIDTH)
        if (sc.lyricsStatus == LyricsStatus.NONE) {
            drawText(canvas, "No lyrics for this song", bodyFace, LyricsLayout.NOTE_SIZE, cx, LyricsLayout.NOTE_Y, colors.note, LyricsLayout.BIG_TEXT_MAX_WIDTH)
        }
        text.textAlign = Paint.Align.LEFT
    }

    // ---- parts -----------------------------------------------------------------------------------

    /** [str] at the top-left (or centre, or right, as [text] is aligned) of a line whose top is at [top], in one line: shrunk to fit, else cut with an ellipsis. */
    private fun drawText(canvas: Canvas, str: String, face: Typeface, size: Float, x: Float, top: Float, color: Int, maxWidth: Float) {
        if (str.isBlank()) return
        val (shown, fit) = fit(str, face, size, size * 0.75f, maxWidth)
        text.typeface = face
        text.textSize = size
        val ascent = -text.ascent()
        text.textSize = fit
        text.color = color
        canvas.drawText(shown, x, top + ascent, text)
    }

    /** The cover in the square at [x], [y]: from the square made for it ([prepared], away from this thread) if there is one, else from [cover] itself. */
    private fun drawCover(canvas: Canvas, cover: Bitmap?, prepared: Bitmap?, x: Float, y: Float, size: Float, colors: LyricsLayout.Colors) {
        rect.set(x, y, x + size, y + size)
        if (prepared != null && !prepared.isRecycled) {
            canvas.drawBitmap(prepared, null, rect, bitmapPaint)
            return
        }
        if (cover == null || cover.isRecycled) {
            shape.style = Paint.Style.FILL
            shape.color = colors.track
            canvas.drawRect(rect, shape)
            return
        }
        val scaled = scaledCover(cover, size.toInt())
        canvas.drawBitmap(scaled, null, rect, bitmapPaint)
    }

    /** The cover as a square of twice the size it is drawn at (cut from the middle), made once per cover. */
    private fun scaledCover(cover: Bitmap, drawnSize: Int): Bitmap {
        val want = drawnSize * 2
        val cached = coverScaled
        if (cached != null && coverSource === cover && coverScaledSize == want) return cached
        val side = minOf(cover.width, cover.height)
        srcRect.set((cover.width - side) / 2, (cover.height - side) / 2, (cover.width + side) / 2, (cover.height + side) / 2)
        val out = Bitmap.createBitmap(want, want, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(cover, srcRect, Rect(0, 0, want, want), bitmapPaint)
        coverSource = cover
        coverScaled = out
        coverScaledSize = want
        return out
    }

    /** The text and the size to draw it in: the largest size from [size] down to [minSize] at which it fits, and if it does not fit then, cut with an ellipsis. */
    private fun fit(str: String, face: Typeface, size: Float, minSize: Float, maxWidth: Float): Pair<String, Float> {
        text.typeface = face
        val key = "${System.identityHashCode(face)}|$size|$minSize|$maxWidth|$str"
        val known = fitted[key]
        val chosen = known ?: LyricsLayout.fitSize({ s ->
            text.textSize = s
            text.measureText(str)
        }, maxWidth, size, minSize).also { fitted[key] = it }
        text.textSize = chosen
        if (text.measureText(str) <= maxWidth) return Pair(str, chosen)
        var s = str
        while (s.length > 1 && text.measureText("$s…") > maxWidth) s = s.dropLast(1)
        return Pair("${s.trimEnd()}…", chosen)
    }

    private fun font(context: Context, name: String, fallback: Typeface): Typeface = try {
        Typeface.createFromAsset(context.assets, "fonts/$name")
    } catch (e: Exception) {
        AppLog.add("tvvideo", "the font $name cannot be loaded ($e), the system font is used")
        fallback
    }
}
