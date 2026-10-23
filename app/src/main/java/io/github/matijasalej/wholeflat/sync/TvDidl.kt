package io.github.matijasalej.wholeflat.sync

/**
 * The item that tells the TV's DLNA renderer what it is given: one music track whose resource is the L16 stream of the app. The
 * Philips renderer takes `audio/L16;rate=44100;channels=2` (big-endian, no FLAC), and shows the title, the artist and the cover in its
 * own player. The cover is the art URL of the media session of the player (an http URL on the net or one served by this app).
 */
object TvDidl {
    /** What the renderer accepts for this stream (the protocolInfo of its sink list). */
    const val PROTOCOL_INFO = "http-get:*:audio/L16;rate=44100;channels=2:DLNA.ORG_PN=LPCM"

    private const val NS =
        "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" " +
            "xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\""

    /** What the renderer accepts for the video stream (the protocolInfo of its sink list: any MPEG stream). */
    const val PROTOCOL_INFO_VIDEO = "http-get:*:video/mpeg:*"

    /**
     * The item for the lyrics video: a video item with [title] and the stream [url], and nothing else, as in the device test that the TV
     * played (it takes `object.item.videoItem` with `http-get:*:video/mpeg:*`).
     */
    fun video(title: String, url: String): String {
        val sb = StringBuilder()
        sb.append("<DIDL-Lite ").append(NS).append("><item id=\"1\" parentID=\"0\" restricted=\"1\">")
        sb.append("<dc:title>").append(SonosXml.escape(title)).append("</dc:title>")
        sb.append("<upnp:class>object.item.videoItem</upnp:class>")
        sb.append("<res protocolInfo=\"").append(SonosXml.escape(PROTOCOL_INFO_VIDEO)).append("\">").append(SonosXml.escape(url)).append("</res>")
        sb.append("</item></DIDL-Lite>")
        return sb.toString()
    }

    /** The DIDL-Lite document for [url]; the artist, the album and the cover are left out when null or empty. */
    fun track(title: String, artist: String?, album: String?, artUri: String?, url: String): String {
        val sb = StringBuilder()
        sb.append("<DIDL-Lite ").append(NS).append("><item id=\"1\" parentID=\"0\" restricted=\"1\">")
        sb.append("<dc:title>").append(SonosXml.escape(title)).append("</dc:title>")
        sb.append("<upnp:class>object.item.audioItem.musicTrack</upnp:class>")
        if (!artist.isNullOrEmpty()) sb.append("<upnp:artist>").append(SonosXml.escape(artist)).append("</upnp:artist>")
        if (!album.isNullOrEmpty()) sb.append("<upnp:album>").append(SonosXml.escape(album)).append("</upnp:album>")
        if (!artUri.isNullOrEmpty()) sb.append("<upnp:albumArtURI>").append(SonosXml.escape(artUri)).append("</upnp:albumArtURI>")
        sb.append("<res protocolInfo=\"").append(SonosXml.escape(PROTOCOL_INFO)).append("\">").append(SonosXml.escape(url)).append("</res>")
        sb.append("</item></DIDL-Lite>")
        return sb.toString()
    }
}
