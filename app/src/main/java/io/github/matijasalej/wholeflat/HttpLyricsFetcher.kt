package io.github.matijasalej.wholeflat

import io.github.matijasalej.wholeflat.sync.LyricsFetcher
import io.github.matijasalej.wholeflat.sync.Reply
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** LRCLIB over HTTPS, with a User-Agent that names the app (what LRCLIB asks of its users). A network that fails is code -1. */
class HttpLyricsFetcher(private val userAgent: String) : LyricsFetcher {
    override fun fetch(url: String): Reply {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 6_000
            conn.readTimeout = 8_000
            conn.setRequestProperty("User-Agent", userAgent)
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
            Reply(code, if (body.length > MAX_BODY) body.substring(0, MAX_BODY) else body)
        } catch (e: IOException) {
            Reply(-1, "")
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val MAX_BODY = 4_000_000
    }
}
