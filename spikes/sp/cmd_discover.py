"""discover, listen, wol, mock (stdlib only)."""
from __future__ import annotations

import json
import re
import select
import socket
import struct
import subprocess
import sys
import threading
import time
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple
from urllib.parse import urlsplit

from .common import RunLog, arp_lookup, fmt, have_cmd, local_ip_for, mono, new_run_dir, pick_iface
from .upnp import fetch_description, parse_description

SSDP_ADDR = ("239.255.255.250", 1900)
ST_ZONEPLAYER = "urn:schemas-upnp-org:device:ZonePlayer:1"


# ------------------------------------------------------------------ SSDP

def parse_http_headers(data: bytes) -> Tuple[str, Dict[str, str]]:
    text = data.decode("latin-1", "replace")
    lines = text.split("\r\n")
    hdr: Dict[str, str] = {}
    for ln in lines[1:]:
        if ":" in ln:
            k, v = ln.split(":", 1)
            hdr[k.strip().lower()] = v.strip()
    return lines[0], hdr


def ssdp_search(st: str = ST_ZONEPLAYER, mx: int = 1, wait: float = 3.0, local_ip: Optional[str] = None,
                dest: Tuple[str, int] = SSDP_ADDR) -> List[Dict[str, Any]]:
    """Send an M-SEARCH and collect answers for ``wait`` seconds."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    s.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 2)
    if local_ip:
        s.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_IF, socket.inet_aton(local_ip))
        s.bind((local_ip, 0))
    else:
        s.bind(("", 0))
    msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: %d\r\nST: %s\r\n"
           "USER-AGENT: sonos-probe/0.1\r\n\r\n" % (mx, st)).encode()
    found: Dict[str, Dict[str, Any]] = {}
    t0 = mono()
    sent = 0
    while mono() - t0 < wait:
        if sent < 2 and mono() - t0 >= sent * 0.4:
            s.sendto(msg, dest)
            sent += 1
        r, _, _ = select.select([s], [], [], 0.1)
        if r:
            data, addr = s.recvfrom(65535)
            first, h = parse_http_headers(data)
            if not first.startswith("HTTP/1.1 200"):
                continue
            key = h.get("usn", addr[0])
            if key not in found:
                found[key] = {"ip": addr[0], "t": mono() - t0, "headers": h}
    s.close()
    return list(found.values())


# ------------------------------------------------------------------ mDNS via dns-sd

_BROWSE_RE = re.compile(r"^\s*(\d{1,2}:\d\d:\d\d\.\d+)\s+(Add|Rmv)\s+(\d+)\s+(\d+)\s+(\S+)\s+(\S+)\s+(.*?)\s*$")


def run_timed(cmd: List[str], seconds: float) -> List[str]:
    """Run a command that never exits (dns-sd) for ``seconds``; return its output lines."""
    p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
    lines: List[str] = []

    def reader() -> None:
        assert p.stdout is not None
        for ln in p.stdout:
            lines.append(ln.rstrip("\n"))

    th = threading.Thread(target=reader, daemon=True)
    th.start()
    time.sleep(seconds)
    p.terminate()
    try:
        p.wait(timeout=2)
    except Exception:
        p.kill()
    th.join(timeout=1)
    return lines


def mdns_browse(service: str = "_sonos._tcp", seconds: float = 3.0) -> List[Dict[str, str]]:
    if not have_cmd("dns-sd"):
        return []
    out = []
    for ln in run_timed(["dns-sd", "-B", service, "local"], seconds):
        m = _BROWSE_RE.match(ln)
        if m and m.group(2) == "Add":
            out.append({"instance": m.group(7), "domain": m.group(5), "iface": m.group(4), "service": m.group(6)})
    return out


def mdns_resolve(instance: str, service: str = "_sonos._tcp", seconds: float = 2.5) -> Dict[str, Any]:
    """dns-sd -L: host, port, TXT; then the IPv4 address through the system resolver."""
    res: Dict[str, Any] = {"instance": instance}
    for ln in run_timed(["dns-sd", "-L", instance, service, "local"], seconds):
        m = re.search(r"can be reached at (\S+?):(\d+)", ln)
        if m:
            res["host"], res["port"] = m.group(1).rstrip("."), int(m.group(2))
        if "=" in ln and "can be reached" not in ln and "Lookup" not in ln:
            res.setdefault("txt", []).extend(ln.split())
    loc = next((t.split("=", 1)[1] for t in res.get("txt", []) if t.startswith("location=")), None)
    if loc:
        res["location"] = loc               # Sonos publishes the device description URL in its TXT record
    if "host" in res:
        try:
            res["ip"] = socket.gethostbyname(res["host"])
        except OSError:
            pass
    if loc and urlsplit(loc).hostname:
        res["ip"] = urlsplit(loc).hostname
    return res


# ------------------------------------------------------------------ discover

def describe(ip: str, port: int = 1400, extra: Optional[Dict[str, str]] = None, say=print) -> Optional[Dict[str, Any]]:
    try:
        xml, rtt = fetch_description(ip, port)
    except Exception as e:
        say("  %s:%d  device description not reachable: %r" % (ip, port, e))
        return None
    d = parse_description(xml)
    mac = d.get("MACAddress") or ""
    mac_src = "device description"
    if not mac:
        mac = arp_lookup(ip) or ""
        mac_src = "arp -n"
    say("  %s:%d  (description fetched in %.0f ms)" % (ip, port, rtt * 1000))
    say("    model        : %s  (model number %s; %s)" % (d.get("modelName", "?"), d.get("modelNumber", "?"), d.get("modelDescription", "?")))
    say("    display name : %s   room: %s" % (d.get("displayName", "?"), d.get("roomName", "?")))
    say("    software     : display %s, build %s, swGen %s, hardware %s, API %s" % (
        d.get("displayVersion", "?"), d.get("softwareVersion", "?"), d.get("swGen", "?"), d.get("hardwareVersion", "?"), d.get("apiVersion", "?")))
    say("    serial / MAC : %s / %s%s" % (d.get("serialNum", "?"), mac or "?", "  (from %s)" % mac_src if mac else ""))
    say("    UDN          : %s" % d.get("UDN", "?"))
    if extra:
        hh = extra.get("x-rincon-household") or extra.get("household.smartspeaker.audio")
        if hh:
            say("    household    : %s   SSDP server: %s   boot seq: %s" % (hh, extra.get("server", "?"), extra.get("x-rincon-bootseq", "?")))
    svc = ["%s %s" % (s.get("serviceType", "").split(":")[-2] if s.get("serviceType", "").count(":") >= 2 else s.get("serviceType"),
                      s.get("controlURL", "")) for s in d.get("services", [])]
    say("    services     : " + "; ".join(svc))
    say("    sub-devices  : " + ", ".join("%s" % x["type"].split(":")[-2] for x in d.get("devices", [])[1:] if x["type"].count(":") >= 2))
    d["mac"] = mac
    return d


def run_discover(args) -> int:
    local_ip = args.local_ip or local_ip_for(None)
    print("this Mac: %s   (SSDP M-SEARCH ST %s, MX 1, %.0f s; dns-sd _sonos._tcp)" % (local_ip, ST_ZONEPLAYER, args.seconds))
    dest = SSDP_ADDR
    if args.ssdp_target:
        h, _, p = args.ssdp_target.partition(":")
        dest = (h, int(p or 1900))
    results: Dict[str, Any] = {}
    mdns: List[Dict[str, str]] = []

    def do_mdns() -> None:
        if not args.no_mdns:
            mdns.extend(mdns_browse("_sonos._tcp", args.seconds))

    th = threading.Thread(target=do_mdns)
    th.start()
    ssdp = ssdp_search(ST_ZONEPLAYER, 1, args.seconds, local_ip=local_ip if not args.ssdp_target else None, dest=dest)
    th.join()
    print("\nSSDP: %d device(s) answered" % len(ssdp))
    for r in ssdp:
        h = r["headers"]
        print("  %s  after %.2f s   LOCATION %s   USN %s" % (r["ip"], r["t"], h.get("location", "?"), h.get("usn", "?")))
    if not args.no_mdns:
        print("\nmDNS _sonos._tcp: %d instance(s)" % len(mdns))
        for m in mdns:
            print("  %s (if %s)" % (m["instance"], m["iface"]))
    # resolve mDNS instances that SSDP did not show
    ips = {r["ip"]: r for r in ssdp}
    for m in mdns:
        info = mdns_resolve(m["instance"])
        if info.get("ip"):
            print("  mDNS %s -> %s:%s" % (m["instance"], info["ip"], info.get("port")))
            ips.setdefault(info["ip"], {"ip": info["ip"], "headers": {"location": info["location"]} if info.get("location") else {}, "t": 0.0, "mdns": info})
    if not ips:
        print("\nNo Sonos device found. Is the speaker awake (on its charger / Battery Saver off)? Is this Mac on the same subnet?")
        print("macOS may also be blocking local network access for your terminal (System Settings > Privacy & Security > Local Network).")
        return 1
    if args.no_fetch:
        return 0
    print("\ndevice description")
    for ip, r in ips.items():
        loc = r["headers"].get("location")
        port = urlsplit(loc).port if loc else args.device_port
        host = urlsplit(loc).hostname if loc else ip
        d = describe(host or ip, port or args.device_port, r["headers"])
        results[ip] = d
    return 0


# ------------------------------------------------------------------ listen

def is_sonos_notify(h: Dict[str, str]) -> bool:
    blob = " ".join([h.get("server", ""), h.get("usn", ""), h.get("nt", ""), h.get("st", "")]).lower()
    return "sonos" in blob or "rincon" in blob or "zoneplayer" in blob or "smartspeaker" in blob


class DevicePoller(threading.Thread):
    """Polls one device's description every ``interval`` s and logs reachability transitions."""

    def __init__(self, ip: str, port: int, interval: float, log: RunLog, stop: threading.Event):
        super().__init__(name="poll-" + ip, daemon=True)
        self.ip, self.port, self.interval, self.log, self.stop_ev = ip, port, interval, log, stop
        self.state: Optional[str] = None
        self.since = mono()
        self.fails = 0
        self.last_info: Dict[str, str] = {}

    def run(self) -> None:
        nxt = mono()
        while not self.stop_ev.is_set():
            now = mono()
            if now < nxt:
                self.stop_ev.wait(nxt - now)
                continue
            nxt += self.interval
            ping = subprocess.Popen(["/sbin/ping", "-c", "1", "-W", "1000", self.ip], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            t0 = mono()
            err = ""
            info: Dict[str, str] = {}
            try:
                xml, rtt = fetch_description(self.ip, self.port, timeout=1.5)
                d = parse_description(xml)
                info = {k: d.get(k, "") for k in ("roomName", "displayVersion", "softwareVersion", "modelName")}
                ok = True
            except Exception as e:
                ok, rtt, err = False, mono() - t0, repr(e)
            try:
                ping_ok = ping.wait(timeout=2.0) == 0
            except Exception:
                ping.kill()
                ping_ok = False
            new_state = "UP" if ok else "DOWN"
            if ok:
                self.fails = 0
                if info and self.last_info and info != self.last_info:
                    changes = {k: (self.last_info.get(k), v) for k, v in info.items() if self.last_info.get(k) != v}
                    self.log.say("%s description changed: %s" % (self.ip, changes), kind="desc_change", ip=self.ip, changes=changes)
                self.last_info = info or self.last_info
            else:
                self.fails += 1
            self.log.emit("poll", ip=self.ip, ok=ok, rtt=round(rtt, 4), ping=ping_ok, err=err)
            if new_state != self.state:
                dur = mono() - self.since
                if self.state is None:
                    msg = "%s first poll: %s" % (self.ip, new_state)
                else:
                    msg = "%s %s -> %s after %.1f s in %s" % (self.ip, self.state, new_state, dur, self.state)
                msg += "  (HTTP %s%s; ping %s)" % ("ok %.0f ms" % (rtt * 1000) if ok else "failed", "" if ok else " " + err, "ok" if ping_ok else "lost")
                if ok and info:
                    msg += "  room=%s fw=%s" % (info.get("roomName"), info.get("displayVersion"))
                self.log.say(msg, kind="reach", ip=self.ip, state=new_state, prev=self.state, ping=ping_ok, err=err, after_s=round(dur, 2))
                self.state, self.since = new_state, mono()


def run_listen(args) -> int:
    run_dir = new_run_dir("listen")
    log = RunLog(run_dir / "listen.jsonl")
    local_ip = args.local_ip or local_ip_for(args.ip[0] if args.ip else None)
    log.say("listening for SSDP NOTIFY on 239.255.255.250:1900 via %s; log %s" % (local_ip, run_dir / "listen.jsonl"))
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEPORT, 1)
    except (AttributeError, OSError):
        pass
    s.bind(("", 1900))
    mreq = struct.pack("4s4s", socket.inet_aton("239.255.255.250"), socket.inet_aton(local_ip))
    s.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, mreq)
    stop = threading.Event()
    pollers: Dict[str, DevicePoller] = {}

    def track(ip: str, port: Optional[int] = None) -> None:
        if args.no_poll or ip in pollers:
            return
        p = DevicePoller(ip, port or args.device_port, args.interval, log, stop)
        pollers[ip] = p
        p.start()
        log.say("polling %s every %.0f s" % (ip, args.interval))

    for ip in args.ip:
        track(ip)
    # one M-SEARCH at the start to learn who is awake right now
    if not args.no_poll:
        for r in ssdp_search(ST_ZONEPLAYER, 1, 2.0, local_ip=local_ip):
            log.say("M-SEARCH answer from %s (%s)" % (r["ip"], r["headers"].get("usn", "?")), kind="msearch", ip=r["ip"])
            loc = urlsplit(r["headers"].get("location", ""))
            track(loc.hostname or r["ip"], loc.port)
    last_print: Dict[Tuple[str, str], float] = {}
    counts: Dict[Tuple[str, str], int] = {}
    t_end = mono() + args.minutes * 60 if args.minutes else None
    try:
        while t_end is None or mono() < t_end:
            r, _, _ = select.select([s], [], [], 0.5)
            if not r:
                continue
            data, addr = s.recvfrom(65535)
            first, h = parse_http_headers(data)
            if not first.startswith("NOTIFY"):
                continue
            if not is_sonos_notify(h):
                continue
            nts = h.get("nts", "?")
            nt = h.get("nt", "")
            ip = addr[0]
            log.emit("notify", ip=ip, nts=nts, nt=nt, usn=h.get("usn"), location=h.get("location"), bootseq=h.get("x-rincon-bootseq"),
                     server=h.get("server"), household=h.get("x-rincon-household"), maxage=h.get("cache-control"))
            key = (ip, nts)
            counts[key] = counts.get(key, 0) + 1
            if args.verbose or mono() - last_print.get(key, -99) > 5.0:
                last_print[key] = mono()
                log.echo and print("[%8.3f] NOTIFY %-10s from %s  NT=%s  boot=%s%s" % (
                    mono() - log.t0, nts, ip, nt.split(":")[-2] if nt.count(":") >= 2 else nt, h.get("x-rincon-bootseq", "?"),
                    "  (+%d more in this burst)" % (counts[key] - 1) if counts[key] > 1 and not args.verbose else ""), flush=True)
                counts[key] = 0
            if nts == "ssdp:alive" and not args.no_poll:
                loc = urlsplit(h.get("location", ""))
                track(loc.hostname or ip, loc.port)
    except KeyboardInterrupt:
        log.say("stopped")
    finally:
        stop.set()
        s.close()
        log.close()
    return 0


# ------------------------------------------------------------------ wol

def magic_packet(mac: str) -> bytes:
    hexs = re.sub(r"[^0-9a-fA-F]", "", mac)
    if len(hexs) != 12:
        raise ValueError("MAC address must have 12 hex digits: %r" % mac)
    b = bytes.fromhex(hexs)
    return b"\xff" * 6 + b * 16


def run_wol(args) -> int:
    try:
        pkt = magic_packet(args.mac)
    except ValueError as e:
        print(e)
        return 2
    iface = pick_iface(None, args.local_ip)
    dests: List[Tuple[str, int]] = [] if getattr(args, "only_bcast", False) else [("255.255.255.255", 9)]
    if iface and iface.broadcast and not getattr(args, "only_bcast", False):
        dests.append((iface.broadcast, 9))
    for d in args.bcast:
        h, _, p = d.partition(":")
        dests.append((h, int(p or 9)))
    if getattr(args, "dry_run", False):
        for d in dests:
            print("would send the magic packet for %s to %s:%d (from %s)" % (args.mac, d[0], d[1], iface.ip if iface else "?"))
        return 0
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    if iface:
        try:
            s.bind((iface.ip, 0))
        except OSError:
            pass
    for d in dests:
        try:
            s.sendto(pkt, d)
            print("magic packet for %s sent to %s:%d (from %s)" % (args.mac, d[0], d[1], iface.ip if iface else "?"))
        except OSError as e:
            print("could not send to %s:%d: %r" % (d[0], d[1], e))
    s.close()
    return 0


# ------------------------------------------------------------------ mock (development)

def run_mock(args) -> int:
    from .mock import MockRenderer
    m = MockRenderer(port=args.port, host="127.0.0.1", buffer_s=args.buffer, reltime_mode=args.reltime)
    port = m.start()
    print("mock Sonos renderer on 127.0.0.1:%d (buffer %.1f s, RelTime %s)" % (port, args.buffer, args.reltime), flush=True)
    print("try: python3 sonos_probe.py play --ip 127.0.0.1 --soap-port %d --fmt wav --duration 10" % port)
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        pass
    m.stop()
    return 0
