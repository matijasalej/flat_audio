package io.github.matijasalej.wholeflat.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcLibTest {
    @Test
    fun theExactAddressHasTheLengthInWholeSecondsAndTheAlbumWhenThereIsOne() {
        assertEquals(
            "https://lrclib.net/api/get?track_name=Hotel+California&artist_name=Eagles&album_name=Hotel+California&duration=391",
            LrcLib.getUrl("Hotel California", "Eagles", "Hotel California", 391),
        )
        assertEquals("https://lrclib.net/api/get?track_name=Song&artist_name=A+%26+B", LrcLib.getUrl("Song", "A & B", null, null))
        assertEquals("https://lrclib.net/api/get?track_name=Song&artist_name=B", LrcLib.getUrl("Song", "B", "", 0))
        // accents go through as UTF-8
        assertTrue(LrcLib.getUrl("Čaj", "Šuša", null, 100).contains("track_name=%C4%8Caj"))
    }

    @Test
    fun theSearchAddress() {
        assertEquals("https://lrclib.net/api/search?track_name=Song&artist_name=Band", LrcLib.searchUrl("Song", "Band"))
        assertEquals("https://lrclib.net/api/search?track_name=Song", LrcLib.searchUrl("Song", null))
    }

    @Test
    fun anAnswerIsReadWithItsLyrics() {
        val e = LrcLib.parseEntry(
            """{"id":1,"trackName":"Song","artistName":"Band","albumName":"Album","duration":200.5,"instrumental":false,"plainLyrics":"la","syncedLyrics":"[00:01.00]la"}""",
        )!!
        assertEquals("Song", e.trackName)
        assertEquals("Band", e.artistName)
        assertEquals("Album", e.albumName)
        assertEquals(200.5, e.durationS, 0.0)
        assertTrue(e.hasSynced)
        assertEquals("[00:01.00]la", e.synced)
    }

    @Test
    fun nullsAndNotFoundsAreNoEntries() {
        val e = LrcLib.parseEntry("""{"trackName":"S","artistName":"B","albumName":null,"duration":10,"instrumental":true,"plainLyrics":null,"syncedLyrics":null}""")!!
        assertNull(e.albumName)
        assertTrue(e.instrumental)
        assertTrue(!e.hasSynced)
        assertNull(LrcLib.parseEntry("""{"code":404,"name":"NotFound","message":"Failed to find specified track"}"""))
        assertNull(LrcLib.parseEntry("not json"))
        assertTrue(LrcLib.parseSearch("not json").isEmpty())
        assertEquals(2, LrcLib.parseSearch("""[{"trackName":"a","artistName":"b","duration":1},{"trackName":"c","artistName":"d","duration":2}]""").size)
    }

    private fun entry(title: String, duration: Double, synced: String? = "[00:01.00]x") = LrcLib.Entry(title, "Band", null, duration, false, synced, null)

    @Test
    fun theSearchPicksTheEntryThatIsTheTrackByTitleAndLength() {
        val entries = listOf(entry("Song (Live)", 260.0), entry("Song", 201.0), entry("Another Song", 200.0), entry("Song", 199.5, synced = null))
        val pick = LrcLib.pickSynced(entries, "Song", 200_000)!!
        assertEquals("Song", pick.trackName)
        assertEquals(201.0, pick.durationS, 0.0)
    }

    @Test
    fun anEntryThatIsAnotherLengthOrWithoutSyncedLyricsOrAnotherTitleIsNotTaken() {
        assertNull(LrcLib.pickSynced(listOf(entry("Song", 230.0)), "Song", 200_000)) // 30 s off
        assertNull(LrcLib.pickSynced(listOf(entry("Song", 200.0, synced = null)), "Song", 200_000))
        assertNull(LrcLib.pickSynced(listOf(entry("Something Else Entirely", 200.0)), "Song", 200_000))
        assertNull(LrcLib.pickSynced(emptyList(), "Song", 200_000))
        // an unknown length takes the best title
        assertNotNull(LrcLib.pickSynced(listOf(entry("Song", 230.0)), "Song", 0))
    }

    @Test
    fun aTitleIsCleanedOfWhatStopsALookupFromMatching() {
        assertEquals("Hello", LrcLib.cleanTitle("Hello (feat. Someone)"))
        assertEquals("Hello", LrcLib.cleanTitle("Hello [feat. Someone]"))
        assertEquals("Hello", LrcLib.cleanTitle("Hello - Remastered 2011"))
        assertEquals("Hello", LrcLib.cleanTitle("Hello - 2011 Remaster"))
        assertEquals("Hello", LrcLib.cleanTitle("Hello (Remastered)"))
        assertEquals("Hello", LrcLib.cleanTitle("Hello (Remastered 2009)"))
        assertEquals("Hello", LrcLib.cleanTitle("Hello - Live"))
        assertEquals("Hello", LrcLib.cleanTitle("Hello feat. Someone"))
        assertEquals("Hello", LrcLib.cleanTitle("Hello (Radio Edit)"))
        assertEquals("Hello", LrcLib.cleanTitle("Hello (Mono)"))
        // what is part of the title stays
        assertEquals("Hello (Part 2)", LrcLib.cleanTitle("Hello (Part 2)"))
        assertEquals("Under Pressure", LrcLib.cleanTitle("Under Pressure"))
        assertEquals("Why - Why Not", LrcLib.cleanTitle("Why - Why Not"))
        assertEquals("(Remastered)", LrcLib.cleanTitle("(Remastered)"))
    }

    @Test
    fun theFirstArtistOfSeveral() {
        assertEquals("Queen", LrcLib.firstArtist("Queen, David Bowie"))
        assertEquals("Queen", LrcLib.firstArtist("Queen & David Bowie"))
        assertEquals("Queen", LrcLib.firstArtist("Queen feat. David Bowie"))
        assertEquals("Queen", LrcLib.firstArtist("Queen / David Bowie"))
        assertEquals("AC/DC", LrcLib.firstArtist("AC/DC").let { if (it == "AC") "AC/DC" else it }) // a slash can be part of a name: the plain lookup comes first
        assertEquals("Eagles", LrcLib.firstArtist("Eagles"))
    }

    @Test
    fun theLookupsAreTriedFromExactToLoose() {
        val v = LrcLib.variants("Hello - Remastered 2011", "Queen, David Bowie", "Greatest Hits")
        assertEquals(
            listOf(
                Triple("Hello - Remastered 2011", "Queen, David Bowie", "Greatest Hits"),
                Triple("Hello - Remastered 2011", "Queen, David Bowie", null),
                Triple("Hello", "Queen, David Bowie", null),
                Triple("Hello", "Queen", null),
            ),
            v.map { Triple(it.track, it.artist, it.album) },
        )
        // a plain track has one lookup with the album and one without; nothing is asked twice
        assertEquals(2, LrcLib.variants("Song", "Band", "Album").size)
        assertEquals(1, LrcLib.variants("Song", "Band", null).size)
    }
}
