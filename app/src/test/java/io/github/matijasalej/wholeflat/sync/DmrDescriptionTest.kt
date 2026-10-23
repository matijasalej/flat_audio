package io.github.matijasalej.wholeflat.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DmrDescriptionTest {
    /** The shape of a UPnP MediaRenderer description with relative control URLs, as the Philips TV serves it (a synthetic copy). */
    private val philips = """<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0" xmlns:dlna="urn:schemas-dlna-org:device-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <device>
    <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
    <friendlyName>IPI Media Renderer</friendlyName>
    <manufacturer>Philips</manufacturer>
    <modelName>55PUS6753/12</modelName>
    <serviceList>
      <service>
        <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>
        <controlURL>control/RenderingControl</controlURL>
        <eventSubURL>event/RenderingControl</eventSubURL>
        <SCPDURL>RenderingControl.xml</SCPDURL>
      </service>
      <service>
        <serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>
        <controlURL>control/ConnectionManager</controlURL>
        <eventSubURL>event/ConnectionManager</eventSubURL>
        <SCPDURL>ConnectionManager.xml</SCPDURL>
      </service>
      <service>
        <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
        <controlURL>control/AVTransport</controlURL>
        <eventSubURL>event/AVTransport</eventSubURL>
        <SCPDURL>AVTransport.xml</SCPDURL>
      </service>
    </serviceList>
  </device>
</root>"""

    @Test
    fun relativeControlUrlsAreResolvedAgainstTheLocation() {
        val d = DmrDescription.parse(philips, "http://192.168.0.69:2870/dmr.xml")!!
        assertEquals("IPI Media Renderer", d.friendlyName)
        assertEquals("Philips", d.manufacturer)
        assertEquals("55PUS6753/12", d.modelName)
        val av = d.control("AVTransport")!!
        assertEquals(2870, av.port)
        assertEquals("/control/AVTransport", av.path)
        assertEquals("urn:schemas-upnp-org:service:AVTransport:1", av.serviceType)
        assertEquals("/control/RenderingControl", d.control("RenderingControl")!!.path)
        assertEquals("/control/ConnectionManager", d.control("ConnectionManager")!!.path)
    }

    @Test
    fun absolutePathsAndFullUrlsAreKept() {
        val xml = philips.replace("control/AVTransport", "/MediaRenderer/AVTransport/Control")
            .replace("<controlURL>control/RenderingControl", "<controlURL>http://10.0.0.7:1400/MediaRenderer/RenderingControl/Control")
        val d = DmrDescription.parse(xml, "http://10.0.0.7:1400/xml/device_description.xml")!!
        assertEquals("/MediaRenderer/AVTransport/Control", d.control("AVTransport")!!.path)
        assertEquals(1400, d.control("RenderingControl")!!.port)
        assertEquals("/MediaRenderer/RenderingControl/Control", d.control("RenderingControl")!!.path)
    }

    @Test
    fun theUrlBaseCountsWhenThereIsOne() {
        val xml = philips.replace("<specVersion>", "<URLBase>http://192.168.0.69:49152/</URLBase><specVersion>")
        val d = DmrDescription.parse(xml, "http://192.168.0.69:2870/dmr.xml")!!
        assertEquals(49152, d.control("AVTransport")!!.port)
        assertEquals("/control/AVTransport", d.control("AVTransport")!!.path)
    }

    @Test
    fun aDescriptionWithoutAvTransportIsNoRenderer() {
        assertNull(DmrDescription.parse("<root><device><friendlyName>Printer</friendlyName></device></root>", "http://x:1/d.xml"))
        assertNull(DmrDescription.parse("not xml at all", "http://x:1/d.xml"))
    }

    @Test
    fun aServiceCanBeFoundWhateverItsVersion() {
        val d = DmrDescription.parse(philips.replace("AVTransport:1", "AVTransport:2"), "http://h:2870/dmr.xml")!!
        assertEquals("urn:schemas-upnp-org:service:AVTransport:2", d.control("AVTransport")!!.serviceType)
        assertNull(d.control("Printing"))
    }

    @Test
    fun theFallbackIsTheOneThatWorkedInTheManualTest() {
        val d = DmrDescription.philipsFallback("192.168.0.69")
        assertEquals("http://192.168.0.69:2870/dmr.xml", d.location)
        val av = d.control("AVTransport")
        assertNotNull(av)
        assertEquals(2870, av!!.port)
        assertEquals("/control/AVTransport", av.path)
        assertEquals("/control/RenderingControl", d.control("RenderingControl")!!.path)
    }
}
