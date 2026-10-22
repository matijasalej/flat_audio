"""A local mock "Sonos": SOAP renderer + device description, for offline tests.

On Play it fetches the stream URL like the real thing (small receive buffer,
Icy-MetaData header), counts the bytes, and keeps `buffer_s` seconds of audio
in hand: it only reads from the socket while it holds less than that, so the
sender's blocking behaviour looks like a speaker with a full buffer. Position
(RelTime) comes from the audio it has actually "played".

It also records ground truth (`truth`) about when sound would be audible so a
fake recorder can render it for the end-to-end measure/drift tests.
"""
from __future__ import annotations

import http.client
import http.server
import socket
import threading
import time
from typing import Any, Dict, List, Optional, Tuple
from urllib.parse import urlsplit
from xml.etree import ElementTree as ET
from xml.sax.saxutils import escape

from . import testsignal as ts
from .codecs import StreamCounter
from .common import mono
from .upnp import AVT, RCS, local

DESCRIPTION_XML = """<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <device>
    <deviceType>urn:schemas-upnp-org:device:ZonePlayer:1</deviceType>
    <friendlyName>%(ip)s - Mock Sonos Play - RINCON_MOCK000000001400</friendlyName>
    <manufacturer>Sonos, Inc.</manufacturer>
    <modelNumber>S99</modelNumber>
    <modelDescription>Mock Sonos Play</modelDescription>
    <modelName>Mock Sonos Play</modelName>
    <softwareVersion>99.9-00000</softwareVersion>
    <swGen>2</swGen>
    <hardwareVersion>1.0.0.0-0</hardwareVersion>
    <serialNum>00-00-00-00-00-00:0</serialNum>
    <MACAddress>00:00:00:00:00:00</MACAddress>
    <UDN>uuid:RINCON_MOCK000000001400</UDN>
    <displayVersion>99.9</displayVersion>
    <roomName>Kitchen (mock)</roomName>
    <displayName>Mock Sonos Play</displayName>
    <serviceList>
      <service><serviceType>urn:schemas-upnp-org:service:AlarmClock:1</serviceType><serviceId>urn:upnp-org:serviceId:AlarmClock</serviceId><controlURL>/AlarmClock/Control</controlURL><eventSubURL>/AlarmClock/Event</eventSubURL><SCPDURL>/xml/AlarmClock1.xml</SCPDURL></service>
    </serviceList>
    <deviceList>
      <device>
        <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
        <friendlyName>Kitchen (mock) - Sonos Play Media Renderer</friendlyName>
        <serviceList>
          <service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType><serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId><controlURL>/MediaRenderer/RenderingControl/Control</controlURL><eventSubURL>/MediaRenderer/RenderingControl/Event</eventSubURL><SCPDURL>/xml/RenderingControl1.xml</SCPDURL></service>
          <service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><serviceId>urn:upnp-org:serviceId:AVTransport</serviceId><controlURL>/MediaRenderer/AVTransport/Control</controlURL><eventSubURL>/MediaRenderer/AVTransport/Event</eventSubURL><SCPDURL>/xml/AVTransport1.xml</SCPDURL></service>
        </serviceList>
      </device>
    </deviceList>
  </device>
</root>
"""


class _SmallRcvConn(http.client.HTTPConnection):
    """HTTPConnection whose socket gets a small receive buffer *before* connecting."""

    def __init__(self, host, port, timeout, rcvbuf):
        super().__init__(host, port, timeout=timeout)
        self._rcvbuf = rcvbuf

    def connect(self):
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, self._rcvbuf)
        s.settimeout(self.timeout)
        s.connect((self.host, self.port))
        self.sock = s


def fmt_from_response(path: str, headers: Dict[str, str]) -> str:
    p = path.split("?", 1)[0].lower()
    if p.endswith(".wav"):
        return "wav"
    if p.endswith(".flac"):
        return "flac-chunked" if headers.get("transfer-encoding", "").lower() == "chunked" else "flac-nolen"
    if p.endswith(".mp3"):
        return "mp3"
    if p.endswith(".aac"):
        return "aac"
    ct = headers.get("content-type", "")
    return {"audio/wav": "wav", "audio/mpeg": "mp3", "audio/aac": "aac"}.get(ct, "wav")


class MockRenderer:
    def __init__(self, port: int = 0, host: str = "127.0.0.1", buffer_s: float = 3.0, start_threshold_s: float = 1.0,
                 startup_s: float = 0.6, stop_latency_s: float = 0.15, rcvbuf: int = 16384,
                 reltime_mode: str = "advance", codec_delay_s: float = 0.0, silent_after_stop: bool = True,
                 rate_ppm: float = 0.0, never_fetch: bool = False, die_after_s: Optional[float] = None):
        self.host, self.port = host, port
        self.buffer_s, self.start_threshold_s, self.startup_s = buffer_s, start_threshold_s, startup_s
        self.stop_latency_s, self.rcvbuf = stop_latency_s, rcvbuf
        self.reltime_mode = reltime_mode          # advance | frozen | not_implemented
        self.codec_delay_s = codec_delay_s
        self.rate_ppm = rate_ppm                   # playback clock error: +ppm = plays slower than real time
        self.never_fetch = never_fetch             # accept Play but never fetch the stream (firewall / bad URI)
        self.die_after_s = die_after_s             # drop the stream connection this long after it was opened
        self.lock = threading.RLock()
        self.uri = ""
        self.meta = ""
        self.volume = 20
        self.state = "STOPPED"
        self.gen = 0
        self.t_audible_start: Optional[float] = None
        self.t_audible_end: Optional[float] = None
        self.consumed_samples = 0
        self.stream_ended = False
        self._paused_pos = 0.0
        self.truth: List[Dict[str, Any]] = []     # one dict per play
        self.calls: List[Tuple[float, str]] = []
        self.last_didl_class = ""
        self.fetch_headers: Dict[str, str] = {}
        self._conn: Optional[http.client.HTTPConnection] = None
        self._srv: Optional[http.server.ThreadingHTTPServer] = None
        self._thread: Optional[threading.Thread] = None
        self.fail_set_uri_with: Optional[Tuple[str, str]] = None   # (errorCode, description)

    # -- lifecycle -------------------------------------------------------------
    def start(self) -> int:
        outer = self

        class H(http.server.BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, *a, **k):
                pass

            def do_GET(self):
                if self.path.split("?")[0] == "/xml/device_description.xml":
                    body = (DESCRIPTION_XML % {"ip": outer.host}).encode()
                    self.send_response(200)
                    self.send_header("Content-Type", "text/xml")
                    self.send_header("Content-Length", str(len(body)))
                    self.end_headers()
                    self.wfile.write(body)
                else:
                    self.send_response(404)
                    self.send_header("Content-Length", "0")
                    self.end_headers()

            def do_POST(self):
                n = int(self.headers.get("Content-Length", "0"))
                raw = self.rfile.read(n)
                action_hdr = self.headers.get("SOAPACTION", "").strip('"')
                status, body = outer._soap(self.path, action_hdr, raw)
                self.send_response(status)
                self.send_header("Content-Type", 'text/xml; charset="utf-8"')
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

        class S(http.server.ThreadingHTTPServer):
            daemon_threads = True
            allow_reuse_address = True

        self._srv = S((self.host, self.port), H)
        self.port = self._srv.server_address[1]
        self._thread = threading.Thread(target=self._srv.serve_forever, name="mock-soap", daemon=True)
        self._thread.start()
        return self.port

    def stop(self) -> None:
        self._abort_fetch()
        if self._srv:
            self._srv.shutdown()
            self._srv.server_close()

    # -- SOAP ------------------------------------------------------------------
    def _soap(self, path: str, action_hdr: str, raw: bytes) -> Tuple[int, bytes]:
        try:
            root = ET.fromstring(raw)
        except ET.ParseError:
            return 400, b"bad xml"
        body = next((e for e in root.iter() if local(e.tag) == "Body"), None)
        act_el = list(body)[0] if body is not None and len(body) else None
        if act_el is None:
            return 400, b"no action"
        action = local(act_el.tag)
        service = act_el.tag[1:].split("}")[0] if act_el.tag.startswith("{") else ""
        args = {local(c.tag): (c.text or "") for c in act_el}
        self.calls.append((mono(), action))
        if action_hdr != "%s#%s" % (service, action):
            return 500, self._fault("401", "Invalid Action (SOAPACTION %r != %r)" % (action_hdr, service + "#" + action))
        try:
            out = self._dispatch(service, action, args)
        except _Upnp as e:
            return 500, self._fault(e.code, e.desc)
        inner = "".join("<%s>%s</%s>" % (k, escape(str(v)), k) for k, v in out.items())
        return 200, ('<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" '
                     's:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:%sResponse xmlns:u="%s">%s</u:%sResponse>'
                     '</s:Body></s:Envelope>' % (action, service, inner, action)).encode()

    @staticmethod
    def _fault(code: str, desc: str) -> bytes:
        return ('<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" '
                's:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><s:Fault><faultcode>s:Client</faultcode>'
                '<faultstring>UPnPError</faultstring><detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0">'
                '<errorCode>%s</errorCode><errorDescription>%s</errorDescription></UPnPError></detail></s:Fault></s:Body>'
                '</s:Envelope>' % (code, escape(desc))).encode()

    def _dispatch(self, service: str, action: str, a: Dict[str, str]) -> Dict[str, Any]:
        if service == AVT:
            if action == "SetAVTransportURI":
                if self.fail_set_uri_with:
                    raise _Upnp(*self.fail_set_uri_with)
                meta = a.get("CurrentURIMetaData", "")
                if meta:
                    try:
                        d = ET.fromstring(meta)
                        self.last_didl_class = next((e.text or "" for e in d.iter() if local(e.tag) == "class"), "")
                    except ET.ParseError:
                        raise _Upnp("714", "Illegal MIME-Type (bad DIDL)")
                with self.lock:
                    self._abort_fetch()
                    self.uri, self.meta = a.get("CurrentURI", ""), meta
                    self.state = "STOPPED"
                return {}
            if action == "Play":
                self._play()
                return {}
            if action in ("Stop", "Pause"):
                self._stop(pause=(action == "Pause"))
                return {}
            if action == "GetTransportInfo":
                return {"CurrentTransportState": self._state_now(), "CurrentTransportStatus": "OK", "CurrentSpeed": "1"}
            if action == "GetPositionInfo":
                return {"Track": 1, "TrackDuration": "0:00:00", "TrackMetaData": self.meta, "TrackURI": self.uri,
                        "RelTime": self._reltime(), "AbsTime": "NOT_IMPLEMENTED", "RelCount": 2147483647, "AbsCount": 2147483647}
            if action == "GetMediaInfo":
                return {"NrTracks": 1, "MediaDuration": "NOT_IMPLEMENTED", "CurrentURI": self.uri, "CurrentURIMetaData": self.meta,
                        "NextURI": "", "NextURIMetaData": "", "PlayMedium": "NETWORK", "RecordMedium": "NOT_IMPLEMENTED",
                        "WriteStatus": "NOT_IMPLEMENTED"}
        if service == RCS:
            if action == "GetVolume":
                return {"CurrentVolume": self.volume}
            if action == "SetVolume":
                self.volume = max(0, min(100, int(a.get("DesiredVolume", "0"))))
                return {}
        raise _Upnp("401", "Invalid Action %s" % action)

    # -- playback simulation ---------------------------------------------------
    def _state_now(self) -> str:
        with self.lock:
            if self.state == "TRANSITIONING" and self.t_audible_start is not None and mono() >= self.t_audible_start:
                self.state = "PLAYING"
            return self.state

    def position_s(self) -> float:
        with self.lock:
            st = self._state_now()
            if st == "PAUSED_PLAYBACK":
                return self._paused_pos
            if st != "PLAYING" or self.t_audible_start is None:
                return 0.0
            return max(0.0, mono() - self.t_audible_start) / (1.0 + self.rate_ppm * 1e-6)

    def _reltime(self) -> str:
        if self.reltime_mode == "not_implemented":
            return "NOT_IMPLEMENTED"
        p = 0.0 if self.reltime_mode == "frozen" else self.position_s()
        s = int(p)
        return "%d:%02d:%02d" % (s // 3600, (s // 60) % 60, s % 60)

    def _abort_fetch(self) -> None:
        with self.lock:
            self.gen += 1
            c = self._conn
            self._conn = None
        if c is not None:
            try:
                if c.sock is not None:
                    c.sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            try:
                c.close()
            except Exception:
                pass

    def _play(self) -> None:
        with self.lock:
            if not self.uri:
                raise _Upnp("701", "No contents")
            if self.state in ("PLAYING", "TRANSITIONING"):
                return
            if self.state == "PAUSED_PLAYBACK" and self.t_audible_start is not None:
                # resume: the stream connection is still open, the position continues where it stopped
                self.t_audible_start = mono() - self._paused_pos * (1.0 + self.rate_ppm * 1e-6)
                self.state = "TRANSITIONING"
                return
            self._abort_fetch()
            self.state = "TRANSITIONING"
            self.t_audible_start = None
            self.t_audible_end = None
            self.consumed_samples = 0
            self.stream_ended = False
            gen = self.gen
            rec = {"t_play": mono(), "uri": self.uri}
            self.truth.append(rec)
            if self.never_fetch:
                return
        threading.Thread(target=self._fetch, args=(gen, rec), name="mock-fetch", daemon=True).start()

    def _stop(self, pause: bool = False) -> None:
        with self.lock:
            t = mono()
            if pause:
                if self._state_now() == "PLAYING":
                    self._paused_pos = self.position_s()
                    self.state = "PAUSED_PLAYBACK"
                return
            if self.truth and "t_stop" not in self.truth[-1] and self.state in ("PLAYING", "TRANSITIONING", "PAUSED_PLAYBACK"):
                self.truth[-1]["t_stop"] = t
                if self.t_audible_start is not None:
                    self.t_audible_end = t + self.stop_latency_s
                    self.truth[-1]["t_audible_end"] = self.t_audible_end
            self.state = "STOPPED"
        self._abort_fetch()
        with self.lock:
            if self.truth:
                self.truth[-1]["t_conn_closed"] = mono()

    def _fetch(self, gen: int, rec: Dict[str, Any]) -> None:
        url = self.uri
        sp = urlsplit(url.replace("x-rincon-mp3radio://", "http://", 1))
        try:
            conn = _SmallRcvConn(sp.hostname, sp.port or 80, 5.0, self.rcvbuf)
            with self.lock:
                if gen != self.gen:
                    return
                self._conn = conn
            conn.request("GET", sp.path + ("?" + sp.query if sp.query else ""),
                         headers={"User-Agent": "Linux UPnP/1.0 Sonos/99.9-00000 (MockSonos)", "Icy-MetaData": "1",
                                  "Accept": "*/*", "Connection": "close"})
            resp = conn.getresponse()
            hdrs = {k.lower(): v for k, v in resp.getheaders()}
            self.fetch_headers = hdrs
            fmt = fmt_from_response(sp.path, hdrs)
            rec["fmt"] = fmt
            rec["scheme"] = url.split(":", 1)[0]
            counter = StreamCounter(fmt)
            t_fetch0 = mono()
            while gen == self.gen:
                if self.die_after_s is not None and mono() - t_fetch0 > self.die_after_s:
                    break
                with self.lock:
                    started = self.t_audible_start is not None
                    if self.state == "PAUSED_PLAYBACK":
                        pos = self._paused_pos
                    else:
                        pos = (mono() - self.t_audible_start) / (1.0 + self.rate_ppm * 1e-6) if started else 0.0
                    ahead = counter.samples / float(ts.FS) - pos
                if ahead > self.buffer_s:
                    time.sleep(0.004)
                    continue
                data = resp.read1(4096)
                if not data:
                    break
                counter.feed(data)
                with self.lock:
                    self.consumed_samples = counter.samples
                    if self.t_audible_start is None and counter.samples / float(ts.FS) >= self.start_threshold_s:
                        self.t_audible_start = mono() + self.startup_s
                        rec["t_audible_start"] = self.t_audible_start
                        rec["codec_delay_s"] = self.codec_delay_s
                        rec["rate_ppm"] = self.rate_ppm
                        rec["buffer_s"] = self.buffer_s
        except (OSError, http.client.HTTPException):
            pass
        finally:
            try:
                if self._conn is not None and self.die_after_s is not None:
                    self._conn.close()
            except Exception:
                pass
            with self.lock:
                if gen == self.gen:
                    self.stream_ended = True


class _Upnp(Exception):
    def __init__(self, code: str, desc: str):
        super().__init__(desc)
        self.code, self.desc = code, desc
