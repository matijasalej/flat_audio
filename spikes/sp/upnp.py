"""UPnP: SOAP client for AVTransport / RenderingControl, DIDL-Lite, device description parsing."""
from __future__ import annotations

import http.client
import re
import socket
import threading
import urllib.request
from typing import Any, Dict, List, Optional, Sequence, Tuple
from xml.etree import ElementTree as ET
from xml.sax.saxutils import escape

from .common import mono

AVT = "urn:schemas-upnp-org:service:AVTransport:1"
RCS = "urn:schemas-upnp-org:service:RenderingControl:1"
AVT_CONTROL = "/MediaRenderer/AVTransport/Control"
RCS_CONTROL = "/MediaRenderer/RenderingControl/Control"
DESCRIPTION_PATH = "/xml/device_description.xml"


class SoapError(Exception):
    def __init__(self, action: str, code: Optional[str], desc: str, http_status: int = 0, body: bytes = b""):
        super().__init__("%s failed: UPnP error %s %s (HTTP %s)" % (action, code, desc, http_status))
        self.action, self.code, self.desc, self.http_status = action, code, desc, http_status
        self.body = body[:600].decode("utf-8", "replace")


def local(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def soap_envelope(service: str, action: str, args: Sequence[Tuple[str, Any]]) -> bytes:
    inner = "".join("<%s>%s</%s>" % (k, escape(str(v)), k) for k, v in args)
    return ('<?xml version="1.0" encoding="utf-8"?>'
            '<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" '
            's:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">'
            '<s:Body><u:%s xmlns:u="%s">%s</u:%s></s:Body></s:Envelope>' % (action, service, inner, action)).encode("utf-8")


def parse_soap_response(action: str, status: int, body: bytes) -> Dict[str, str]:
    try:
        root = ET.fromstring(body)
    except ET.ParseError:
        raise SoapError(action, None, "unparseable response: %r" % body[:200], status, body)
    out: Dict[str, str] = {}
    fault_code = fault_desc = None
    for el in root.iter():
        n = local(el.tag)
        if n == "errorCode":
            fault_code = (el.text or "").strip()
        elif n == "errorDescription":
            fault_desc = (el.text or "").strip()
    if status >= 400 or fault_code is not None:
        raise SoapError(action, fault_code, fault_desc or "", status, body)
    for el in root.iter():
        if local(el.tag) == action + "Response":
            for ch in el:
                out[local(ch.tag)] = (ch.text or "")
            return out
    return out


class SoapClient:
    """SOAP over a persistent HTTP connection (one per thread; not thread-safe)."""

    def __init__(self, ip: str, port: int = 1400, timeout: float = 4.0, keepalive: bool = True):
        self.ip, self.port, self.timeout, self.keepalive = ip, port, timeout, keepalive
        self._conn: Optional[http.client.HTTPConnection] = None
        self.last_rtt = 0.0

    def _connect(self) -> http.client.HTTPConnection:
        if self._conn is None:
            self._conn = http.client.HTTPConnection(self.ip, self.port, timeout=self.timeout)
        return self._conn

    def close(self) -> None:
        if self._conn is not None:
            try:
                self._conn.close()
            except Exception:
                pass
            self._conn = None

    def call(self, service: str, control: str, action: str, args: Sequence[Tuple[str, Any]] = ()) -> Dict[str, str]:
        body = soap_envelope(service, action, args)
        headers = {
            "Content-Type": 'text/xml; charset="utf-8"',
            "SOAPACTION": '"%s#%s"' % (service, action),
            "User-Agent": "sonos-probe/0.1",
            "Connection": "keep-alive" if self.keepalive else "close",
        }
        last_exc: Optional[Exception] = None
        for attempt in (0, 1):
            conn = self._connect()
            try:
                t0 = mono()
                conn.request("POST", control, body=body, headers=headers)
                resp = conn.getresponse()
                data = resp.read()
                self.last_rtt = mono() - t0
                if not self.keepalive or resp.getheader("Connection", "").lower() == "close":
                    self.close()
                return parse_soap_response(action, resp.status, data)
            except SoapError:
                raise
            except (http.client.HTTPException, OSError) as e:
                last_exc = e
                self.close()
        raise last_exc if last_exc else RuntimeError("soap call failed")

    # -- AVTransport -----------------------------------------------------------
    def set_uri(self, uri: str, meta: str) -> Dict[str, str]:
        return self.call(AVT, AVT_CONTROL, "SetAVTransportURI", [("InstanceID", 0), ("CurrentURI", uri), ("CurrentURIMetaData", meta)])

    def play(self) -> Dict[str, str]:
        return self.call(AVT, AVT_CONTROL, "Play", [("InstanceID", 0), ("Speed", 1)])

    def stop(self) -> Dict[str, str]:
        return self.call(AVT, AVT_CONTROL, "Stop", [("InstanceID", 0)])

    def pause(self) -> Dict[str, str]:
        return self.call(AVT, AVT_CONTROL, "Pause", [("InstanceID", 0)])

    def transport_info(self) -> Dict[str, str]:
        return self.call(AVT, AVT_CONTROL, "GetTransportInfo", [("InstanceID", 0)])

    def position_info(self) -> Dict[str, str]:
        return self.call(AVT, AVT_CONTROL, "GetPositionInfo", [("InstanceID", 0)])

    def media_info(self) -> Dict[str, str]:
        return self.call(AVT, AVT_CONTROL, "GetMediaInfo", [("InstanceID", 0)])

    # -- RenderingControl ------------------------------------------------------
    def get_volume(self) -> int:
        r = self.call(RCS, RCS_CONTROL, "GetVolume", [("InstanceID", 0), ("Channel", "Master")])
        return int(r.get("CurrentVolume", "-1"))

    def set_volume(self, v: int) -> Dict[str, str]:
        return self.call(RCS, RCS_CONTROL, "SetVolume", [("InstanceID", 0), ("Channel", "Master"), ("DesiredVolume", int(v))])


# ------------------------------------------------------------------ DIDL-Lite

DIDL_NS = ('xmlns:dc="http://purl.org/dc/elements/1.1/" '
           'xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" '
           'xmlns:r="urn:schemas-rinconnetworks-com:metadata-1-0/" '
           'xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"')


def didl_broadcast(title: str) -> str:
    """Exactly SoCo's template for ``play_uri(title=...)`` (TuneIn-style radio item)."""
    return ('<DIDL-Lite %s><item id="R:0/0/0" parentID="R:0/0" restricted="true">'
            '<dc:title>%s</dc:title><upnp:class>object.item.audioItem.audioBroadcast</upnp:class>'
            '<desc id="cdudn" nameSpace="urn:schemas-rinconnetworks-com:metadata-1-0/">SA_RINCON65031_</desc>'
            '</item></DIDL-Lite>' % (DIDL_NS, escape(title)))


def didl_track(title: str, mime: str, uri: str) -> str:
    """A plain music-track item with the stream as its resource."""
    return ('<DIDL-Lite %s><item id="-1" parentID="-1" restricted="true">'
            '<res protocolInfo="http-get:*:%s:*">%s</res>'
            '<dc:title>%s</dc:title><upnp:class>object.item.audioItem.musicTrack</upnp:class>'
            '</item></DIDL-Lite>' % (DIDL_NS, mime, escape(uri), escape(title)))


def build_uri(url: str, scheme: str) -> str:
    """Replace the URL scheme the way SoCo's force_radio does: http://h:p/x -> x-rincon-mp3radio://h:p/x."""
    if scheme == "http":
        return url
    colon = url.find(":")
    return "%s%s" % (scheme, url[colon:]) if colon > 0 else url


def parse_reltime(s: Optional[str]) -> Optional[float]:
    """'H:MM:SS' (optionally with fraction) -> seconds; None for NOT_IMPLEMENTED / empty."""
    if not s:
        return None
    m = re.match(r"^\s*(\d+):(\d{1,2}):(\d{1,2})(?:\.(\d+))?\s*$", s)
    if not m:
        return None
    h, mi, se = int(m.group(1)), int(m.group(2)), int(m.group(3))
    frac = float("0." + m.group(4)) if m.group(4) else 0.0
    return h * 3600 + mi * 60 + se + frac


# ------------------------------------------------------------------ device description

def fetch_description(ip: str, port: int = 1400, timeout: float = 3.0) -> Tuple[str, float]:
    """GET the UPnP device description. Returns (xml text, round-trip seconds)."""
    t0 = mono()
    req = urllib.request.Request("http://%s:%d%s" % (ip, port, DESCRIPTION_PATH), headers={"User-Agent": "sonos-probe/0.1"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        data = r.read()
    return data.decode("utf-8", "replace"), mono() - t0


def parse_description(xml_text: str) -> Dict[str, Any]:
    """Flat dict of the root device's simple fields plus a list of services (all sub-devices)."""
    root = ET.fromstring(xml_text)
    out: Dict[str, Any] = {"services": [], "devices": []}
    dev = None
    for el in root.iter():
        if local(el.tag) == "device":
            dev = el
            break
    if dev is None:
        return out
    for ch in dev:
        n = local(ch.tag)
        if n in ("serviceList", "deviceList", "iconList"):
            continue
        if len(ch) == 0:
            out[n] = (ch.text or "").strip()

    def walk(d, depth):
        dt = ""
        fn = ""
        for ch in d:
            if local(ch.tag) == "deviceType":
                dt = (ch.text or "").strip()
            elif local(ch.tag) == "friendlyName":
                fn = (ch.text or "").strip()
        out["devices"].append({"type": dt, "name": fn, "depth": depth})
        for ch in d:
            if local(ch.tag) == "serviceList":
                for sv in ch:
                    row = {local(x.tag): (x.text or "").strip() for x in sv}
                    row["device"] = dt.split(":")[-2] if dt.count(":") >= 2 else dt
                    out["services"].append(row)
            elif local(ch.tag) == "deviceList":
                for sub in ch:
                    walk(sub, depth + 1)

    walk(dev, 0)
    return out
