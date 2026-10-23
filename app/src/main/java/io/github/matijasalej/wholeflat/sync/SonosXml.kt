package io.github.matijasalej.wholeflat.sync

/**
 * The little bit of XML the Sonos conversation needs: SOAP envelopes, the DIDL item that describes
 * the stream, and a few tags out of the answers. Plain strings, no parser: the answers are small and
 * regular, and this runs on the JVM tests as it is.
 */
object SonosXml {
    const val AVTRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
    const val RENDERING = "urn:schemas-upnp-org:service:RenderingControl:1"
    const val AVTRANSPORT_CONTROL = "/MediaRenderer/AVTransport/Control"
    const val RENDERING_CONTROL = "/MediaRenderer/RenderingControl/Control"

    private const val DIDL_NS =
        "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" " +
            "xmlns:r=\"urn:schemas-rinconnetworks-com:metadata-1-0/\" " +
            "xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\""

    fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

    fun unescape(s: String): String =
        s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    /** A SOAP request body; the argument values are escaped here. */
    fun envelope(service: String, action: String, args: List<Pair<String, String>>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        sb.append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
        sb.append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">")
        sb.append("<s:Body><u:").append(action).append(" xmlns:u=\"").append(service).append("\">")
        for ((name, value) in args) {
            sb.append('<').append(name).append('>').append(escape(value)).append("</").append(name).append('>')
        }
        sb.append("</u:").append(action).append("></s:Body></s:Envelope>")
        return sb.toString()
    }

    /** A plain music track whose resource is the stream, of MIME type [mime] (the class that makes the Sonos play it as a file). */
    fun didlTrack(title: String, uri: String, mime: String = "audio/flac"): String =
        "<DIDL-Lite $DIDL_NS><item id=\"-1\" parentID=\"-1\" restricted=\"true\">" +
            "<res protocolInfo=\"http-get:*:$mime:*\">${escape(uri)}</res>" +
            "<dc:title>${escape(title)}</dc:title><upnp:class>object.item.audioItem.musicTrack</upnp:class>" +
            "</item></DIDL-Lite>"

    /** The text of the first `<name>...</name>` (namespace prefixes ignored), unescaped; null if absent. */
    fun tag(xml: String, name: String): String? {
        val match = Regex("<(?:[A-Za-z0-9_]+:)?" + Regex.escape(name) + "(?:\\s[^>]*)?>(.*?)</(?:[A-Za-z0-9_]+:)?" + Regex.escape(name) + ">", RegexOption.DOT_MATCHES_ALL)
            .find(xml) ?: return null
        return unescape(match.groupValues[1].trim())
    }

    /** The UPnP error code of a SOAP fault, if the answer is one. */
    fun errorCode(xml: String): Int? = tag(xml, "errorCode")?.toIntOrNull()
}
