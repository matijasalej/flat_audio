package io.github.matijasalej.wholeflat.sync

import java.net.URI

/**
 * The description of a UPnP media renderer (the XML at its location, `http://<tv>:2870/dmr.xml` for the Philips TV): its name and the
 * control URLs of its AV services, which differ from one renderer to the next (a Sonos has `/MediaRenderer/AVTransport/Control`, the
 * Philips `control/AVTransport`, relative to the description). Plain strings and a regex like [SonosXml]: the document is small and
 * regular, and this runs on the JVM tests as it is.
 */
class DmrDescription(
    val location: String,
    val friendlyName: String,
    val manufacturer: String,
    val modelName: String,
    val services: List<Service>,
    /** `URLBase` of the description, if it has one; relative URLs are resolved against it, else against [location]. */
    val urlBase: String = "",
) {
    class Service(val serviceType: String, val controlUrl: String, val eventSubUrl: String, val scpdUrl: String)

    /** Where to send the SOAP calls of one service: the port and the path (with the query, if any). */
    class Control(val port: Int, val path: String, val serviceType: String)

    /** The service of [kind] ("AVTransport", "RenderingControl", "ConnectionManager"), whatever its version. */
    fun service(kind: String): Service? = services.firstOrNull { it.serviceType.contains(":service:$kind:") }

    /** The control endpoint of [kind], the control URL resolved against the description; null if the renderer has no such service. */
    fun control(kind: String): Control? {
        val s = service(kind) ?: return null
        val base = urlBase.ifEmpty { location }
        return try {
            val resolved = URI(base).resolve(s.controlUrl.trim())
            val port = if (resolved.port > 0) resolved.port else 80
            val path = (resolved.rawPath ?: "/") + (resolved.rawQuery?.let { "?$it" } ?: "")
            Control(port, path, s.serviceType)
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        /** The control paths that the Philips TV used in a manual test, for when its description cannot be read. */
        fun philipsFallback(ip: String): DmrDescription = DmrDescription(
            location = "http://$ip:2870/dmr.xml",
            friendlyName = "IPI Media Renderer",
            manufacturer = "Philips",
            modelName = "",
            services = listOf(
                Service(SonosXml.AVTRANSPORT, "control/AVTransport", "", ""),
                Service(SonosXml.RENDERING, "control/RenderingControl", "", ""),
                Service(SonosXml.CONNECTIONMANAGER, "control/ConnectionManager", "", ""),
            ),
        )

        /** The renderer described by [xml], fetched from [location]; null if it has no AVTransport service. */
        fun parse(xml: String, location: String): DmrDescription? {
            val services = ArrayList<Service>()
            for (m in SERVICE.findAll(xml)) {
                val block = m.groupValues[1]
                val type = SonosXml.tag(block, "serviceType") ?: continue
                val control = SonosXml.tag(block, "controlURL") ?: continue
                services.add(Service(type, control, SonosXml.tag(block, "eventSubURL") ?: "", SonosXml.tag(block, "SCPDURL") ?: ""))
            }
            val d = DmrDescription(
                location = location,
                friendlyName = SonosXml.tag(xml, "friendlyName") ?: "",
                manufacturer = SonosXml.tag(xml, "manufacturer") ?: "",
                modelName = SonosXml.tag(xml, "modelName") ?: "",
                services = services,
                urlBase = SonosXml.tag(xml, "URLBase") ?: "",
            )
            return if (d.service("AVTransport") == null) null else d
        }

        private val SERVICE = Regex("<service>(.*?)</service>", RegexOption.DOT_MATCHES_ALL)
    }
}
