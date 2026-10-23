package io.github.matijasalej.wholeflat.sync

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * LRCLIB (lrclib.net), the free database of synced lyrics: the addresses to ask, what the answers say, which of several answers is the
 * track, and how a title is cleaned of what stops a lookup from matching ("(feat. X)", "- Remastered 2011"). Plain Kotlin.
 */
object LrcLib {
    const val BASE = "https://lrclib.net/api"

    /** One answer of LRCLIB. */
    class Entry(
        val trackName: String,
        val artistName: String,
        val albumName: String?,
        val durationS: Double,
        val instrumental: Boolean,
        val synced: String?,
        val plain: String?,
    ) {
        val hasSynced: Boolean get() = !synced.isNullOrBlank()
    }

    /** A track as it is asked for. */
    class Query(val track: String, val artist: String, val album: String?)

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** The address of the exact lookup (LRCLIB wants the length in whole seconds and matches within 2 s). */
    fun getUrl(track: String, artist: String, album: String?, durationS: Int?): String {
        val sb = StringBuilder("$BASE/get?track_name=${enc(track)}&artist_name=${enc(artist)}")
        if (!album.isNullOrBlank()) sb.append("&album_name=").append(enc(album))
        if (durationS != null && durationS > 0) sb.append("&duration=").append(durationS)
        return sb.toString()
    }

    fun searchUrl(track: String, artist: String?): String {
        val sb = StringBuilder("$BASE/search?track_name=${enc(track)}")
        if (!artist.isNullOrBlank()) sb.append("&artist_name=").append(enc(artist))
        return sb.toString()
    }

    /** The entry in the answer of `/api/get`; null if it is not one. */
    fun parseEntry(json: String): Entry? = try {
        entryOf(JSONObject(json))
    } catch (e: Exception) {
        null
    }

    /** The entries in the answer of `/api/search`. */
    fun parseSearch(json: String): List<Entry> = try {
        val a = JSONArray(json)
        (0 until a.length()).mapNotNull { entryOf(a.optJSONObject(it)) }
    } catch (e: Exception) {
        emptyList()
    }

    private fun entryOf(o: JSONObject?): Entry? {
        if (o == null || !o.has("trackName")) return null
        return Entry(
            o.optString("trackName"),
            o.optString("artistName"),
            if (o.isNull("albumName")) null else o.optString("albumName"),
            o.optDouble("duration", 0.0),
            o.optBoolean("instrumental", false),
            if (o.isNull("syncedLyrics")) null else o.optString("syncedLyrics"),
            if (o.isNull("plainLyrics")) null else o.optString("plainLyrics"),
        )
    }

    /** The entry of [entries] that is the track: synced lyrics, a length within 3 s of [durationMs] (when known), and the title that is the likest. */
    fun pickSynced(entries: List<Entry>, title: String, durationMs: Long): Entry? {
        val wanted = normalise(title)
        return entries
            .filter { it.hasSynced }
            .filter { durationMs <= 0 || Math.abs(it.durationS * 1000 - durationMs) <= DURATION_SLACK_MS }
            .maxByOrNull { similarity(wanted, normalise(it.trackName)) - (if (durationMs > 0) Math.abs(it.durationS * 1000 - durationMs) / 100_000.0 else 0.0) }
            ?.takeIf { similarity(wanted, normalise(it.trackName)) >= MIN_SIMILARITY }
    }

    const val DURATION_SLACK_MS = 3_000.0

    private fun normalise(s: String): String = cleanTitle(s).lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /** How alike two normalised titles are, 0 to 1 (the words they share out of all the words they have between them). */
    private fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        val wa = a.split(' ').toSet()
        val wb = b.split(' ').toSet()
        val common = wa.count { it in wb }
        return common.toDouble() / (wa.size + wb.size - common)
    }

    /** A title that is less alike than this is another track. */
    private const val MIN_SIMILARITY = 0.6

    private val BRACKETED = Regex("\\s*[(\\[][^)\\]]*(feat\\.?|ft\\.?|featuring|with|remaster(ed)?|live|version|mono|stereo|radio edit|deluxe|bonus|\\d{4})[^)\\]]*[)\\]]", RegexOption.IGNORE_CASE)
    private val DASHED = Regex("\\s+[-\u2013\u2014]\\s+(\\d{4}\\s+)?(remaster(ed)?|live|mono|stereo|radio edit|single version|album version|deluxe|bonus|feat\\.?|ft\\.?)\\b.*$", RegexOption.IGNORE_CASE)
    private val FEAT_TAIL = Regex("\\s+(feat\\.?|ft\\.?|featuring)\\s+.*$", RegexOption.IGNORE_CASE)

    /** The title without "(feat. X)", "(Remastered 2011)", "- Remastered 2011", "- Live" and the like. */
    fun cleanTitle(title: String): String {
        var t = title.trim()
        t = BRACKETED.replace(t, "")
        t = DASHED.replace(t, "")
        t = FEAT_TAIL.replace(t, "")
        return t.trim().ifEmpty { title.trim() }
    }

    /** The first artist of "A, B & C", "A feat. B", "A / B". */
    fun firstArtist(artist: String): String {
        val a = artist.split(Regex("\\s*(,|&|/|;|\\bfeat\\.?\\b|\\bft\\.?\\b|\\bfeaturing\\b| x )\\s*", RegexOption.IGNORE_CASE)).firstOrNull()?.trim()
        return if (a.isNullOrEmpty()) artist.trim() else a
    }

    /**
     * The lookups to try, in order: as it is, without the album, with the title cleaned, with the first artist only. Each differs from
     * the ones before it.
     */
    fun variants(title: String, artist: String, album: String?): List<Query> {
        val out = ArrayList<Query>()
        fun add(q: Query) {
            if (out.none { it.track == q.track && it.artist == q.artist && it.album == q.album }) out.add(q)
        }
        val cleaned = cleanTitle(title)
        val first = firstArtist(artist)
        add(Query(title.trim(), artist.trim(), album?.trim()?.ifEmpty { null }))
        add(Query(title.trim(), artist.trim(), null))
        add(Query(cleaned, artist.trim(), null))
        add(Query(cleaned, first, null))
        return out
    }
}
