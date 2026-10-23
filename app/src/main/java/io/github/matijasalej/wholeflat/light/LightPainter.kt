package io.github.matijasalej.wholeflat.light

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader

/**
 * Paints the lights into the TV picture, which the Ambilight then reads at its edges.
 * - [full] is for the screen-off display: the whole picture is the light, each edge in the colours of its zones.
 * - [band] is for the lyrics: the light shows in a band at the left, top and right edges and fades into the background of the lyrics
 *   within the band. The outer half of the band carries the light at full strength.
 *
 * The light is a small picture ([Zones.image]) scaled up with filtering, so drawing it costs about as much as one cover. Not thread safe:
 * one painter per drawer.
 */
class LightPainter {
    private val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
    private val pixels = IntArray(W * H)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fade = Paint()
    private val dst = RectF()
    private val clip = Rect()

    /** The whole [width] x [height] canvas as the light of [zones]. */
    fun full(canvas: Canvas, zones: IntArray, width: Float, height: Float) {
        draw(zones)
        dst.set(0f, 0f, width, height)
        canvas.drawBitmap(bmp, null, dst, paint)
    }

    /** The light of [zones] in a band of [share] of [width] at the left, top and right, fading into [background]. */
    fun band(canvas: Canvas, zones: IntArray, width: Float, height: Float, share: Double, background: Int) {
        val b = (width * share).toFloat().coerceIn(8f, width / 4)
        draw(zones)
        dst.set(0f, 0f, width, height)
        val bg = background or (0xFF shl 24)
        val clear = background and 0x00FFFFFF
        // left, right, then the top over the corners: the light, then the background coming in from the inner side
        side(canvas, 0f, 0f, b, height, LinearGradient(0f, 0f, b, 0f, intArrayOf(clear, clear, bg), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP))
        side(canvas, width - b, 0f, width, height, LinearGradient(width, 0f, width - b, 0f, intArrayOf(clear, clear, bg), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP))
        side(canvas, 0f, 0f, width, b, LinearGradient(0f, 0f, 0f, b, intArrayOf(clear, clear, bg), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP))
    }

    private fun side(canvas: Canvas, l: Float, t: Float, r: Float, bottom: Float, gradient: LinearGradient) {
        canvas.save()
        clip.set(l.toInt(), t.toInt(), Math.ceil(r.toDouble()).toInt(), Math.ceil(bottom.toDouble()).toInt())
        canvas.clipRect(clip)
        canvas.drawBitmap(bmp, null, dst, paint)
        fade.shader = gradient
        canvas.drawRect(l, t, r, bottom, fade)
        canvas.restore()
    }

    private fun draw(zones: IntArray) {
        Zones.image(zones, W, H, pixels)
        bmp.setPixels(pixels, 0, W, 0, 0, W, H)
    }

    private companion object {
        const val W = 64
        const val H = 36
    }
}
