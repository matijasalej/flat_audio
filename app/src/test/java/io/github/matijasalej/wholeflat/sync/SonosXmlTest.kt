package io.github.matijasalej.wholeflat.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SonosXmlTest {
    @Test
    fun theEnvelopeEscapesArguments() {
        val xml = SonosXml.envelope(
            SonosXml.AVTRANSPORT,
            "SetAVTransportURI",
            listOf("InstanceID" to "0", "CurrentURI" to "http://h:8080/k/a.flac?x=1&y=2", "CurrentURIMetaData" to "<DIDL-Lite/>"),
        )
        assertTrue(xml.contains("<u:SetAVTransportURI xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"))
        assertTrue(xml.contains("<InstanceID>0</InstanceID>"))
        assertTrue(xml.contains("<CurrentURI>http://h:8080/k/a.flac?x=1&amp;y=2</CurrentURI>"))
        assertTrue(xml.contains("<CurrentURIMetaData>&lt;DIDL-Lite/&gt;</CurrentURIMetaData>"))
        assertTrue(xml.endsWith("</u:SetAVTransportURI></s:Body></s:Envelope>"))
    }

    @Test
    fun theDidlItemIsAMusicTrackWithAFlacResource() {
        val didl = SonosXml.didlTrack("Whole Flat", "http://192.168.0.165:8080/k/abc.flac")
        assertTrue(didl.contains("<upnp:class>object.item.audioItem.musicTrack</upnp:class>"))
        assertTrue(didl.contains("<res protocolInfo=\"http-get:*:audio/flac:*\">http://192.168.0.165:8080/k/abc.flac</res>"))
        assertTrue(didl.contains("<dc:title>Whole Flat</dc:title>"))
        assertTrue(didl.startsWith("<DIDL-Lite xmlns:dc="))
    }

    @Test
    fun tagsAreReadAndUnescaped() {
        val body = "<s:Envelope><s:Body><u:GetPositionInfoResponse xmlns:u=\"x\">" +
            "<Track>1</Track><TrackURI>http://h/k/a.flac?a=1&amp;b=2</TrackURI><RelTime>0:00:03</RelTime>" +
            "</u:GetPositionInfoResponse></s:Body></s:Envelope>"
        assertEquals("http://h/k/a.flac?a=1&b=2", SonosXml.tag(body, "TrackURI"))
        assertEquals("0:00:03", SonosXml.tag(body, "RelTime"))
        assertNull(SonosXml.tag(body, "Missing"))
    }

    @Test
    fun anEmptyTagIsAnEmptyString() {
        assertEquals("", SonosXml.tag("<a><TrackURI></TrackURI></a>", "TrackURI"))
    }

    @Test
    fun faultCodesAreFound() {
        val fault = "<s:Envelope><s:Body><s:Fault><detail><UPnPError><errorCode>701</errorCode>" +
            "<errorDescription>Transition not available</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>"
        assertEquals(701, SonosXml.errorCode(fault))
        assertNull(SonosXml.errorCode("<ok/>"))
    }

    @Test
    fun theTransportStateIsRead() {
        val body = "<u:GetTransportInfoResponse xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">" +
            "<CurrentTransportState>PLAYING</CurrentTransportState><CurrentTransportStatus>OK</CurrentTransportStatus>" +
            "<CurrentSpeed>1</CurrentSpeed></u:GetTransportInfoResponse>"
        assertEquals("PLAYING", SonosXml.tag(body, "CurrentTransportState"))
    }

    @Test
    fun ourUrisAreRecognised() {
        assertTrue(SonosPolicy.isOurUri("http://192.168.0.165:8080/k/1a2b3c4d.flac"))
        assertFalse(SonosPolicy.isOurUri("x-sonos-spotify:spotify%3atrack"))
        assertFalse(SonosPolicy.isOurUri(""))
        assertEquals("1a2b3c4d", SonosPolicy.tokenOf("http://192.168.0.165:8080/k/1a2b3c4d.flac"))
        assertNull(SonosPolicy.tokenOf("http://x/y.mp3"))
    }

    @Test
    fun onlyAForeignSourceThatPlaysKeepsTheKitchenOut() {
        val other = "x-rincon-stream:RINCON_123"
        assertEquals(SonosPolicy.Availability.FREE, SonosPolicy.classify("STOPPED", ""))
        assertEquals(SonosPolicy.Availability.FREE, SonosPolicy.classify("NO_MEDIA_PRESENT", ""))
        assertEquals(SonosPolicy.Availability.FREE, SonosPolicy.classify("STOPPED", other))
        assertEquals(SonosPolicy.Availability.BUSY, SonosPolicy.classify("PLAYING", other))
        assertEquals(SonosPolicy.Availability.BUSY, SonosPolicy.classify("TRANSITIONING", other))
        // paused is free: a paused Spotify Connect session must not keep the kitchen out
        assertEquals(SonosPolicy.Availability.FREE, SonosPolicy.classify("PAUSED_PLAYBACK", other))
        assertEquals(
            SonosPolicy.Availability.FREE,
            SonosPolicy.classify("PAUSED_PLAYBACK", "x-sonos-vli:RINCON_542A1B9AEFC601400:2,spotify:abc"),
        )
        // anything else that is not playing is free as well
        assertEquals(SonosPolicy.Availability.FREE, SonosPolicy.classify("", other))
        assertEquals(SonosPolicy.Availability.OURS, SonosPolicy.classify("PLAYING", "http://h:8080/k/ab.flac"))
        assertEquals(SonosPolicy.Availability.OURS, SonosPolicy.classify("STOPPED", "http://h:8080/k/ab.flac"))
        assertEquals(SonosPolicy.Availability.OURS, SonosPolicy.classify("PAUSED_PLAYBACK", "http://h:8080/k/ab.flac"))
    }

    @Test
    fun aPausedForeignSessionIsTakenOverBeforeOurStreamIsSet() {
        val vli = "x-sonos-vli:RINCON_542A1B9AEFC601400:2,spotify:6f3a"
        assertTrue(SonosPolicy.needsTakeover("PAUSED_PLAYBACK", vli))
        assertTrue(SonosPolicy.needsTakeover("PAUSED_PLAYBACK", "x-rincon-queue:RINCON_542A1B9AEFC601400#0"))
        // nothing to take over when it is stopped, playing (that is busy), ours or empty
        assertFalse(SonosPolicy.needsTakeover("STOPPED", vli))
        assertFalse(SonosPolicy.needsTakeover("PLAYING", vli))
        assertFalse(SonosPolicy.needsTakeover("PAUSED_PLAYBACK", "http://h:8080/k/ab.flac"))
        assertFalse(SonosPolicy.needsTakeover("PAUSED_PLAYBACK", ""))
    }

    @Test
    fun theQueueUriIsBuiltFromTheUdn() {
        assertEquals(
            "x-rincon-queue:RINCON_542A1B9AEFC601400#0",
            SonosPolicy.queueUri("uuid:RINCON_542A1B9AEFC601400"),
        )
        // a sub-device of the same speaker names the same player
        assertEquals(
            "x-rincon-queue:RINCON_542A1B9AEFC601400#0",
            SonosPolicy.queueUri("uuid:RINCON_542A1B9AEFC601400_MR"),
        )
        assertNull(SonosPolicy.queueUri(""))
        assertNull(SonosPolicy.queueUri("uuid:something-else"))
    }
}
