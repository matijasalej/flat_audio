package io.github.matijasalej.wholeflat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.matijasalej.wholeflat.sync.CoverColor
import io.github.matijasalej.wholeflat.sync.LyricsLayout
import io.github.matijasalej.wholeflat.sync.NowPlaying
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * The cover of the track that plays, for the lyrics picture and the phone page: the session's own picture if it gives one, else the picture
 * at its address, fetched on a thread of its own (the answer is "none yet" until it is there) and kept (the last few). With the cover comes
 * its colour: the dominant colour, made dark enough for white text (see [CoverCache.Cover]), and the two squares it is drawn from.
 *
 * **Nothing here runs on the thread that draws the pictures.** The colour and the squares (a scaling of 2 MB of pixels) used to be made by
 * the first picture of a new track, at the moment the box is busiest with the new track and the encoder has the most to do; now they are
 * made on a thread of their own and the answer is "none yet" (the picture shows the place of the cover) for the few milliseconds it takes.
 */
class CoverCache(private val download: (String) -> Bitmap? = ::downloadCover) {
    /** The cover, its colour, and the middle square of it scaled to [SMALL_PX] and [BIG_PX] pixels (null if that could not be made). */
    class Cover(val bitmap: Bitmap, val color: Int, val small: Bitmap? = null, val big: Bitmap? = null)

    private val lock = Any()
    private val covers = object : LinkedHashMap<String, Cover>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cover>?): Boolean = size > MAX_KEPT
    }
    private val asked = HashSet<String>()
    private val failed = HashMap<String, Long>()
    private val fetcher = Executors.newSingleThreadExecutor { r -> Thread(r, "wf-cover").also { it.isDaemon = true } }
    private val preparer = Executors.newSingleThreadExecutor { r -> Thread(r, "wf-cover-prep").also { it.isDaemon = true } }

    /** The cover of [now] ([art]: the session's own picture of it, if it has one); null while there is none (yet). */
    fun coverFor(now: NowPlaying, art: Bitmap?): Cover? {
        val key = now.key
        synchronized(lock) {
            covers[key]?.let { return it }
        }
        if (art != null && !art.isRecycled) {
            prepareLater(key, art)
            return null
        }
        val uri = now.artUri ?: return null
        synchronized(lock) {
            val failedAt = failed[key]
            if (failedAt != null && System.nanoTime() - failedAt < RETRY_NANOS) return null
            if (!asked.add(key)) return null
        }
        fetcher.execute {
            val bmp = try {
                download(uri)
            } catch (t: Throwable) {
                null
            }
            val cover = if (bmp == null) null else try {
                prepare(bmp)
            } catch (t: Throwable) {
                Cover(bmp, CoverColor.DEFAULT)
            }
            synchronized(lock) {
                asked.remove(key)
                if (cover == null) failed[key] = System.nanoTime() else covers[key] = cover
            }
        }
        return null
    }

    /** The session's own picture: made into a [Cover] on the thread for it, once. */
    private fun prepareLater(key: String, art: Bitmap) {
        synchronized(lock) {
            val failedAt = failed[key]
            if (failedAt != null && System.nanoTime() - failedAt < ART_RETRY_NANOS) return
            if (!asked.add(key)) return
        }
        preparer.execute {
            var cover: Cover? = null
            try {
                if (!art.isRecycled) cover = prepare(art)
            } catch (t: Throwable) {
                AppLog.add("tvvideo", "the cover of the track could not be prepared ($t)")
            }
            synchronized(lock) {
                asked.remove(key)
                if (cover == null) failed[key] = System.nanoTime() else covers[key] = cover
            }
        }
    }

    private fun prepare(src: Bitmap): Cover = Cover(src, colorOf(src), squareOf(src, SMALL_PX), squareOf(src, BIG_PX))

    private fun colorOf(bitmap: Bitmap): Int = try {
        val small = Bitmap.createScaledBitmap(bitmap, 32, 32, true)
        val pixels = IntArray(32 * 32)
        small.getPixels(pixels, 0, 32, 0, 0, 32, 32)
        if (small !== bitmap) small.recycle()
        CoverColor.of(pixels)
    } catch (t: Throwable) {
        CoverColor.DEFAULT
    }

    /** The middle square of [src] as a bitmap of [size] pixels a side; null if it cannot be made (the picture is then drawn from the cover itself). */
    private fun squareOf(src: Bitmap, size: Int): Bitmap? = try {
        val side = minOf(src.width, src.height)
        val crop = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        val out = if (crop.width == size) crop else Bitmap.createScaledBitmap(crop, size, size, true)
        if (crop !== out && crop !== src) crop.recycle()
        out
    } catch (t: Throwable) {
        null
    }

    companion object {
        private const val MAX_KEPT = 3
        private const val RETRY_NANOS = 60_000_000_000L
        private const val ART_RETRY_NANOS = 5_000_000_000L

        /** The squares the cover is drawn from: twice the size of the footer cover and one and a half times the big cover (see [LyricsLayout]); 1.4 MB at most each. */
        val SMALL_PX = LyricsLayout.COVER_SIZE.toInt() * 2
        val BIG_PX = LyricsLayout.BIG_COVER.toInt() * 3 / 2
    }
}

/** The picture at [uri] (http or https), no larger than about 640 px; null if it cannot be had. */
fun downloadCover(uri: String): Bitmap? {
    val conn = URL(uri).openConnection() as HttpURLConnection
    return try {
        conn.connectTimeout = 6_000
        conn.readTimeout = 8_000
        if (conn.responseCode !in 200..299) return null
        val bytes = conn.inputStream.use { it.readBytes() }
        if (bytes.size > 8_000_000) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 640 && bounds.outHeight / (sample * 2) >= 640) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (e: Exception) {
        null
    } finally {
        conn.disconnect()
    }
}
