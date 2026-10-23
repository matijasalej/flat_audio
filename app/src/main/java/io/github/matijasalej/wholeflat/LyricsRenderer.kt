package io.github.matijasalej.wholeflat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.view.Surface
import io.github.matijasalej.wholeflat.sync.LyricsLayout
import io.github.matijasalej.wholeflat.sync.Picture
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Puts the pictures on the input surface of the video encoder. Three ways to do it, tried in this order unless the setting `tv_render`
 * says one: the hardware canvas of the surface (the GPU draws the text; the cheapest), the software canvas of the surface, and OpenGL
 * (the picture is drawn into a bitmap and shown as a texture, with the time of the picture given to the encoder). A surface can be
 * connected to one way only, so the way is chosen before the first frame and a failure ends the encoder and starts the next way with a
 * new surface (see `TvTsPipeline`). Used by one thread.
 */
class LyricsRenderer(context: Context, private val surface: Surface, val path: Path, private val width: Int = LyricsLayout.W, private val height: Int = LyricsLayout.H) {
    enum class Path(val key: String) {
        HW("hw"),
        SW("sw"),
        GL("gl"),
        ;

        companion object {
            /** The ways to try for the setting [setting] ("auto", "hw", "sw" or "gl"). */
            fun order(setting: String): List<Path> = when (setting) {
                "hw" -> listOf(HW)
                "sw" -> listOf(SW)
                "gl" -> listOf(GL)
                else -> listOf(HW, SW, GL)
            }
        }
    }

    private val drawer = SceneDrawer(context)
    private var gl: GlSink? = null
    private var bitmap: Bitmap? = null
    private var bitmapCanvas: Canvas? = null

    /** Frames put on the surface, and the time it took (ms, the latest and the sum). */
    var frames = 0L
        private set
    var lastRenderMs = 0.0
        private set
    private var renderMsSum = 0.0

    val averageRenderMs: Double get() = if (frames == 0L) 0.0 else renderMsSum / frames

    init {
        if (path == Path.GL) {
            val b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap = b
            bitmapCanvas = Canvas(b)
            gl = GlSink(surface, width, height)
        }
    }

    /**
     * Draws one picture and puts it on the surface; returns the time the encoder will give it (System.nanoTime, as its timestamp):
     * with the canvas it is the moment the picture was queued, with OpenGL it is [timestampNanos]. Throws if the surface does not take it.
     */
    fun render(picture: Picture, scene: TvScene?, timestampNanos: Long): Long {
        val t0 = System.nanoTime()
        var stamp = timestampNanos
        when (path) {
            Path.HW -> {
                val c = surface.lockHardwareCanvas()
                try {
                    drawer.draw(c, picture, scene)
                } finally {
                    surface.unlockCanvasAndPost(c)
                }
                stamp = System.nanoTime()
            }
            Path.SW -> {
                val c = surface.lockCanvas(null)
                try {
                    drawer.draw(c, picture, scene)
                } finally {
                    surface.unlockCanvasAndPost(c)
                }
                stamp = System.nanoTime()
            }
            Path.GL -> {
                val canvas = bitmapCanvas!!
                drawer.draw(canvas, picture, scene)
                gl!!.post(bitmap!!, timestampNanos)
            }
        }
        lastRenderMs = (System.nanoTime() - t0) / 1e6
        renderMsSum += lastRenderMs
        frames++
        return stamp
    }

    fun close() {
        try {
            gl?.release()
        } catch (t: Throwable) {
            AppLog.error("tvvideo", "closing the GL surface failed", t)
        }
        gl = null
        bitmap?.recycle()
        bitmap = null
    }

    /** OpenGL ES: a bitmap as a texture on a quad that fills an EGL window surface made of the encoder's input surface. */
    private class GlSink(surface: Surface, private val w: Int, private val h: Int) {
        private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var context: EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        private var program = 0
        private var texture = 0
        private var uploaded = false
        private val vertices: FloatBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            // x, y, s, t: the bitmap's first row is the top of the picture
            put(floatArrayOf(-1f, -1f, 0f, 1f, 1f, -1f, 1f, 1f, -1f, 1f, 0f, 0f, 1f, 1f, 1f, 0f))
            position(0)
        }

        init {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY) { "no EGL display" }
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
            val attribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) && count[0] > 0) { "no EGL config for the encoder surface" }
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            check(context != EGL14.EGL_NO_CONTEXT) { "no EGL context" }
            eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0)
            check(eglSurface != EGL14.EGL_NO_SURFACE) { "no EGL window surface" }
            check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "eglMakeCurrent failed" }
            program = buildProgram()
            val t = IntArray(1)
            GLES20.glGenTextures(1, t, 0)
            texture = t[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }

        fun post(bitmap: Bitmap, timestampNanos: Long) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            if (!uploaded) {
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
                uploaded = true
            } else {
                GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap)
            }
            GLES20.glViewport(0, 0, w, h)
            GLES20.glUseProgram(program)
            val pos = GLES20.glGetAttribLocation(program, "aPos")
            val tex = GLES20.glGetAttribLocation(program, "aTex")
            vertices.position(0)
            GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 16, vertices)
            GLES20.glEnableVertexAttribArray(pos)
            vertices.position(2)
            GLES20.glVertexAttribPointer(tex, 2, GLES20.GL_FLOAT, false, 16, vertices)
            GLES20.glEnableVertexAttribArray(tex)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTex"), 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            EGLExt.eglPresentationTimeANDROID(display, eglSurface, timestampNanos)
            check(EGL14.eglSwapBuffers(display, eglSurface)) { "eglSwapBuffers failed" }
        }

        fun release() {
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
            display = EGL14.EGL_NO_DISPLAY
            context = EGL14.EGL_NO_CONTEXT
            eglSurface = EGL14.EGL_NO_SURFACE
        }

        private fun buildProgram(): Int {
            val vs = compile(GLES20.GL_VERTEX_SHADER, "attribute vec2 aPos; attribute vec2 aTex; varying vec2 vTex; void main() { gl_Position = vec4(aPos, 0.0, 1.0); vTex = aTex; }")
            val fs = compile(GLES20.GL_FRAGMENT_SHADER, "precision mediump float; varying vec2 vTex; uniform sampler2D uTex; void main() { gl_FragColor = texture2D(uTex, vTex); }")
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, vs)
            GLES20.glAttachShader(p, fs)
            GLES20.glLinkProgram(p)
            val status = IntArray(1)
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] == GLES20.GL_TRUE) { "the shader program does not link: ${GLES20.glGetProgramInfoLog(p)}" }
            return p
        }

        private fun compile(type: Int, source: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, source)
            GLES20.glCompileShader(s)
            val status = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, status, 0)
            check(status[0] == GLES20.GL_TRUE) { "a shader does not compile: ${GLES20.glGetShaderInfoLog(s)}" }
            return s
        }

        private companion object {
            const val EGL_RECORDABLE_ANDROID = 0x3142
        }
    }
}
