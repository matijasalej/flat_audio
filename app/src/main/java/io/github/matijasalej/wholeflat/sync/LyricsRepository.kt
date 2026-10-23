package io.github.matijasalej.wholeflat.sync

/** What came back from an address: the status (-1 when the network failed) and the body. */
class Reply(val code: Int, val body: String)

/** The wire to LRCLIB; the app's is [io.github.matijasalej.wholeflat.HttpLyricsFetcher], the tests have their own. */
fun interface LyricsFetcher {
    fun fetch(url: String): Reply
}

/**
 * The synced lyrics of the track that plays, from LRCLIB, asked for once per track and kept. [state] answers at once: what is known, or
 * "loading" while a lookup that it started runs on a thread of its own. A lookup tries the exact address, the address without the album,
 * with the title cleaned of "(feat. X)" and "- Remastered", with the first artist only, and then the search; the track is None only when
 * every one of them says no, and that is remembered for an hour (a network that failed is not: it is asked again after a minute). Found
 * lyrics are kept for good (the 100 most recent). Plain Kotlin.
 */
class LyricsRepository(
    private val fetcher: LyricsFetcher,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val runAsync: (Runnable) -> Unit = { r -> Thread(r, "wf-lyrics").also { it.isDaemon = true }.start() },
    private val log: (String) -> Unit = {},
) {
    /** The track as the media session gives it. */
    class Track(val key: String, val title: String, val artist: String, val album: String?, val durationMs: Long)

    sealed class State {
        object Loading : State()
        object None : State()
        object Instrumental : State()
        class Synced(val lines: List<LyricLine>, val found: String) : State()
    }

    private class Kept(val state: State, val expiresMs: Long)

    private val lock = Any()
    private val cache = object : LinkedHashMap<String, Kept>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Kept>?): Boolean = size > MAX_KEPT
    }
    private val inFlight = HashSet<String>()

    /** Requests made to LRCLIB (for the status and the tests). */
    @Volatile
    var requests = 0
        private set

    /** What is known of the track now; a lookup is started if nothing is (and [State.Loading] said). */
    fun state(track: Track): State {
        synchronized(lock) {
            val kept = cache[track.key]
            if (kept != null && (kept.expiresMs == 0L || kept.expiresMs > nowMs())) return kept.state
            if (!inFlight.add(track.key)) return State.Loading
        }
        runAsync(
            Runnable {
                val result = try {
                    lookup(track)
                } catch (t: Throwable) {
                    log("the lookup of ${track.title} failed: $t")
                    Outcome(State.None, retryable = true)
                }
                synchronized(lock) {
                    val expires = when {
                        result.state is State.Synced -> 0L
                        result.retryable -> nowMs() + RETRY_AFTER_MS
                        else -> nowMs() + NOT_FOUND_FOR_MS
                    }
                    cache[track.key] = Kept(result.state, expires)
                    inFlight.remove(track.key)
                }
            },
        )
        // a runner that ran it at once has the answer already
        return synchronized(lock) { cache[track.key]?.state ?: State.Loading }
    }

    /** Whether lyrics of the track are known at all (found, or looked for and not there). */
    fun known(track: Track): State? = synchronized(lock) { cache[track.key]?.state }

    class Outcome(val state: State, val retryable: Boolean)

    /** The lookup itself (blocks on the network). */
    fun lookup(track: Track): Outcome {
        if (track.title.isBlank() || track.artist.isBlank()) return Outcome(State.None, retryable = false)
        val durationS = if (track.durationMs > 0) Math.round(track.durationMs / 1000.0).toInt() else null
        var trouble = false
        for (q in LrcLib.variants(track.title, track.artist, track.album)) {
            val reply = ask(LrcLib.getUrl(q.track, q.artist, q.album, durationS))
            when {
                reply.code == 200 -> {
                    val e = LrcLib.parseEntry(reply.body) ?: continue
                    if (e.instrumental) return Outcome(State.Instrumental, retryable = false)
                    if (e.hasSynced && durationFits(e, track)) {
                        val lines = Lrc.parse(e.synced!!)
                        if (lines.isNotEmpty()) return Outcome(State.Synced(lines, "${e.trackName} - ${e.artistName}"), retryable = false)
                    }
                }
                reply.code == 404 -> Unit
                else -> trouble = true
            }
        }
        val title = LrcLib.cleanTitle(track.title)
        val first = LrcLib.firstArtist(track.artist)
        for (artist in listOf(first, null)) {
            val reply = ask(LrcLib.searchUrl(title, artist))
            if (reply.code == 200) {
                val pick = LrcLib.pickSynced(LrcLib.parseSearch(reply.body), title, track.durationMs)
                if (pick != null) {
                    val lines = Lrc.parse(pick.synced!!)
                    if (lines.isNotEmpty()) return Outcome(State.Synced(lines, "${pick.trackName} - ${pick.artistName} (search)"), retryable = false)
                }
            } else if (reply.code != 404) {
                trouble = true
            }
        }
        log("no synced lyrics for ${track.title} - ${track.artist}${if (trouble) " (the network gave trouble)" else ""}")
        return Outcome(State.None, retryable = trouble)
    }

    private fun durationFits(e: LrcLib.Entry, track: Track): Boolean =
        track.durationMs <= 0 || e.durationS <= 0 || Math.abs(e.durationS * 1000 - track.durationMs) <= LrcLib.DURATION_SLACK_MS

    private fun ask(url: String): Reply {
        requests++
        return try {
            fetcher.fetch(url)
        } catch (t: Throwable) {
            Reply(-1, "")
        }
    }

    companion object {
        const val MAX_KEPT = 100

        /** A track that was not found is not asked for again for this long (ms). */
        const val NOT_FOUND_FOR_MS = 3_600_000L

        /** A lookup that met a network that failed is asked again after this long (ms). */
        const val RETRY_AFTER_MS = 60_000L
    }
}
