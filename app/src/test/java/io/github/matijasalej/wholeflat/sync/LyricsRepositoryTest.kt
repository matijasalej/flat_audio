package io.github.matijasalej.wholeflat.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsRepositoryTest {
    private val synced = """{"trackName":"Song","artistName":"Band","albumName":"Album","duration":200,"instrumental":false,"syncedLyrics":"[00:01.00]one\n[00:05.00]two"}"""
    private val notFound = Reply(404, """{"code":404}""")

    private class Fake(val replies: (String) -> Reply) : LyricsFetcher {
        val asked = ArrayList<String>()
        override fun fetch(url: String): Reply {
            asked.add(url)
            return replies(url)
        }
    }

    private val track = LyricsRepository.Track("id1", "Song", "Band", "Album", 200_000)

    private fun repo(f: Fake, now: () -> Long = { 0L }) = LyricsRepository(f, now, { it.run() })

    @Test
    fun anExactHitIsTheLyricsAndOneRequestWasEnough() {
        val f = Fake { if (it.contains("/get?")) Reply(200, synced) else notFound }
        val r = repo(f)
        val s = r.state(track)
        s as LyricsRepository.State.Synced
        assertEquals(listOf(1_000L, 5_000L), s.lines.map { it.timeMs })
        assertEquals(1, f.asked.size)
        assertTrue(f.asked[0].contains("duration=200"))
        assertTrue(f.asked[0].contains("album_name=Album"))
        // asked once, kept
        r.state(track)
        assertEquals(1, f.asked.size)
    }

    @Test
    fun withoutTheAlbumAndWithACleanedTitleThenTheSearch() {
        // the exact and the album-less lookups say 404; the cleaned title is found
        val t = LyricsRepository.Track("id2", "Song - Remastered 2011", "Band", "Album", 200_000)
        val f = Fake { if (it.contains("track_name=Song&") && it.contains("/get?")) Reply(200, synced) else notFound }
        val s = repo(f).state(t)
        assertTrue(s is LyricsRepository.State.Synced)
        assertEquals(3, f.asked.size)
        assertTrue(f.asked[2].contains("track_name=Song&artist_name=Band"))
    }

    @Test
    fun whenEveryExactLookupSaysNoTheSearchFindsIt() {
        val t = LyricsRepository.Track("id3", "Song", "Band", null, 200_000)
        val f = Fake {
            when {
                it.contains("/search?") -> Reply(200, "[$synced]")
                else -> notFound
            }
        }
        val s = repo(f).state(t)
        assertTrue(s is LyricsRepository.State.Synced)
        assertTrue((s as LyricsRepository.State.Synced).found.endsWith("(search)"))
    }

    @Test
    fun aTrackThatIsNotThereIsNotAskedForAgainForAnHourAndThenIs() {
        var now = 0L
        val f = Fake { notFound }
        val r = repo(f) { now }
        assertTrue(r.state(track) is LyricsRepository.State.None)
        val asked = f.asked.size
        assertTrue(asked >= 3)
        now = 3_599_000L
        assertTrue(r.state(track) is LyricsRepository.State.None)
        assertEquals(asked, f.asked.size)
        now = 3_601_000L
        r.state(track)
        assertTrue(f.asked.size > asked)
    }

    @Test
    fun aNetworkThatFailedIsAskedAgainAfterAMinuteNotAnHour() {
        var now = 0L
        var up = false
        val f = Fake { if (up) Reply(200, synced) else Reply(-1, "") }
        val r = repo(f) { now }
        assertTrue(r.state(track) is LyricsRepository.State.None)
        val asked = f.asked.size
        now = 30_000L
        r.state(track)
        assertEquals(asked, f.asked.size)
        up = true
        now = 61_000L
        assertTrue(r.state(track) is LyricsRepository.State.Synced)
    }

    @Test
    fun anInstrumentalIsSaidSo() {
        val f = Fake { Reply(200, """{"trackName":"Song","artistName":"Band","duration":200,"instrumental":true,"syncedLyrics":null}""") }
        assertTrue(repo(f).state(track) is LyricsRepository.State.Instrumental)
    }

    @Test
    fun anEntryOfAnotherLengthIsNotTheTrack() {
        val other = """{"trackName":"Song","artistName":"Band","duration":260,"instrumental":false,"syncedLyrics":"[00:01.00]one"}"""
        val f = Fake { if (it.contains("/get?")) Reply(200, other) else notFound }
        assertTrue(repo(f).state(track) is LyricsRepository.State.None)
    }

    @Test
    fun aLookupThatIsRunningIsNotStartedTwice() {
        val queued = ArrayList<Runnable>()
        val f = Fake { Reply(200, synced) }
        val r = LyricsRepository(f, { 0L }, { queued.add(it) })
        assertTrue(r.state(track) is LyricsRepository.State.Loading)
        assertTrue(r.state(track) is LyricsRepository.State.Loading)
        assertEquals(1, queued.size)
        queued[0].run()
        assertTrue(r.state(track) is LyricsRepository.State.Synced)
        assertEquals(1, f.asked.size)
    }

    @Test
    fun aTrackWithoutTitleOrArtistIsNotLookedFor() {
        val f = Fake { Reply(200, synced) }
        val r = repo(f)
        assertTrue(r.state(LyricsRepository.Track("x", "", "Band", null, 0)) is LyricsRepository.State.None)
        assertTrue(r.state(LyricsRepository.Track("y", "Song", "", null, 0)) is LyricsRepository.State.None)
        assertEquals(0, f.asked.size)
    }

    @Test
    fun aFetcherThatThrowsIsANetworkFailure() {
        val r = LyricsRepository({ throw java.io.IOException("down") }, { 0L }, { it.run() })
        assertTrue(r.state(track) is LyricsRepository.State.None)
        assertTrue(r.known(track) is LyricsRepository.State.None)
    }

    @Test
    fun onlyTheMostRecentHundredAreKept() {
        val f = Fake { Reply(200, synced) }
        val r = repo(f)
        for (i in 0 until 130) r.state(LyricsRepository.Track("k$i", "Song", "Band", "Album", 200_000))
        val before = f.asked.size
        r.state(LyricsRepository.Track("k129", "Song", "Band", "Album", 200_000)) // newest: kept
        assertEquals(before, f.asked.size)
        r.state(LyricsRepository.Track("k0", "Song", "Band", "Album", 200_000)) // oldest: gone, asked again
        assertTrue(f.asked.size > before)
    }
}
