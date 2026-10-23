package io.github.matijasalej.wholeflat.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvDidlTest {
    private val url = "http://192.168.0.165:8080/k/abc123def456.l16"

    @Test
    fun theItemIsAMusicTrackWhoseResourceIsTheL16Stream() {
        val d = TvDidl.track("Hotel California", "Eagles", "Hotel California", "https://i.scdn.co/image/ab67616d", url)
        assertTrue(d.startsWith("<DIDL-Lite "))
        assertTrue(d.contains("xmlns:dc=\"http://purl.org/dc/elements/1.1/\""))
        assertTrue(d.contains("xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\""))
        assertTrue(d.contains("xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\""))
        assertTrue(d.contains("<upnp:class>object.item.audioItem.musicTrack</upnp:class>"))
        assertEquals("Hotel California", SonosXml.tag(d, "title"))
        assertEquals("Eagles", SonosXml.tag(d, "artist"))
        assertEquals("Hotel California", SonosXml.tag(d, "album"))
        assertEquals("https://i.scdn.co/image/ab67616d", SonosXml.tag(d, "albumArtURI"))
        assertEquals(url, SonosXml.tag(d, "res"))
        assertTrue(d.endsWith("</item></DIDL-Lite>"))
    }

    @Test
    fun theProtocolInfoIsWhatThePhilipsAcceptsForLpcm() {
        assertEquals("http-get:*:audio/L16;rate=44100;channels=2:DLNA.ORG_PN=LPCM", TvDidl.PROTOCOL_INFO)
        val d = TvDidl.track("t", null, null, null, url)
        assertTrue(d.contains("<res protocolInfo=\"http-get:*:audio/L16;rate=44100;channels=2:DLNA.ORG_PN=LPCM\">"))
    }

    @Test
    fun whatIsNotKnownIsLeftOut() {
        val d = TvDidl.track("Whole Flat", null, "", null, url)
        assertFalse(d.contains("upnp:artist"))
        assertFalse(d.contains("upnp:album"))
        assertFalse(d.contains("albumArtURI"))
        assertEquals("Whole Flat", SonosXml.tag(d, "title"))
    }

    @Test
    fun textIsEscapedSoThatATitleCannotBreakTheDocument() {
        val d = TvDidl.track("Rock & Roll <Part 2>", "AC/DC \"live\"", "It's", "http://x/a?b=1&c=2", "http://h/k/t.l16?x=1&y=2")
        assertEquals("Rock & Roll <Part 2>", SonosXml.tag(d, "title"))
        assertEquals("AC/DC \"live\"", SonosXml.tag(d, "artist"))
        assertEquals("It's", SonosXml.tag(d, "album"))
        assertEquals("http://x/a?b=1&c=2", SonosXml.tag(d, "albumArtURI"))
        assertEquals("http://h/k/t.l16?x=1&y=2", SonosXml.tag(d, "res"))
        assertFalse("no raw ampersand outside an entity", Regex("&(?!amp;|lt;|gt;|quot;|apos;)").containsMatchIn(d))
    }

    @Test
    fun theModeIsReadFromTheSettingInAnyCase() {
        assertEquals(Mode.SYNCED, Mode.of("synced"))
        assertEquals(Mode.LYRICS, Mode.of(" Lyrics "))
        assertEquals(null, Mode.of("both"))
        assertEquals(null, Mode.of(null))
    }
}
