#!/usr/bin/env python3
"""sonos_probe: measurement tooling for the Whole Flat Audio Sonos spike.

Subcommands: discover, listen, play, ws, measure, drift, volume, wol,
             analyze, mock, selftest      (see README.md)

discover / listen / play / volume / wol / mock run with plain /usr/bin/python3.
ws, measure, drift, analyze and selftest need the venv (numpy, websockets):
    .venv/bin/python sonos_probe.py <command> ...
"""
from __future__ import annotations

import argparse
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)

FORMATS = ["wav", "flac-chunked", "flac-nolen", "mp3", "aac"]
SCHEMES = ["http", "x-rincon-mp3radio"]


def add_stream_args(p: argparse.ArgumentParser, duration_default: float = 60.0) -> None:
    p.add_argument("--ip", required=True, help="speaker IP address")
    p.add_argument("--fmt", choices=FORMATS, default="wav", help="stream format (default wav)")
    p.add_argument("--scheme", choices=SCHEMES, default="x-rincon-mp3radio",
                   help="URI scheme given to the speaker (default x-rincon-mp3radio, SoCo force_radio style)")
    p.add_argument("--class", dest="cls", choices=["broadcast", "track"], default="broadcast",
                   help="DIDL-Lite class: audioBroadcast (SoCo radio item) or musicTrack (default broadcast)")
    p.add_argument("--prebuffer", type=float, default=3.0, metavar="SECONDS",
                   help="burst this much audio immediately, then real-time pacing (default 3)")
    p.add_argument("--duration", type=float, default=duration_default, metavar="SECONDS", help="seconds to play before Stop")
    p.add_argument("--volume", type=int, default=None, metavar="N", help="set speaker volume first (restored afterwards)")
    p.add_argument("--port", type=int, default=8090, help="HTTP port of the stream server on this Mac (default 8090)")
    p.add_argument("--soap-port", type=int, default=1400, help="UPnP port of the speaker (default 1400)")
    p.add_argument("--poll-hz", type=float, default=20.0, help="GetPositionInfo / GetTransportInfo poll rate (default 20)")
    p.add_argument("--local-ip", default=None, help="this Mac's IP as the speaker should see it (default: detected)")


def build_parser() -> argparse.ArgumentParser:
    ap = argparse.ArgumentParser(prog="sonos_probe.py", description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("discover", help="SSDP + mDNS discovery and device description")
    p.add_argument("--seconds", type=float, default=3.0, help="how long to collect SSDP / mDNS answers (default 3)")
    p.add_argument("--no-fetch", action="store_true", help="do not fetch device descriptions")
    p.add_argument("--no-mdns", action="store_true", help="skip dns-sd browsing")
    p.add_argument("--ssdp-target", default=None, metavar="HOST:PORT", help="send M-SEARCH here instead of 239.255.255.250:1900 (tests)")
    p.add_argument("--device-port", type=int, default=1400, help="HTTP port for the device description (default 1400)")
    p.add_argument("--local-ip", default=None, help="interface IP for multicast (default: detected)")

    p = sub.add_parser("listen", help="log SSDP NOTIFY alive/byebye/update and poll reachability")
    p.add_argument("--ip", action="append", default=[], help="speaker IP to poll (repeatable); others are added when they announce")
    p.add_argument("--minutes", type=float, default=0.0, help="stop after N minutes (default: until Ctrl-C)")
    p.add_argument("--interval", type=float, default=3.0, help="device description poll interval in seconds (default 3)")
    p.add_argument("--no-poll", action="store_true", help="only listen, never contact a device")
    p.add_argument("--device-port", type=int, default=1400)
    p.add_argument("--local-ip", default=None)
    p.add_argument("--verbose", action="store_true", help="print every NOTIFY (default: one line per device and state change)")

    p = sub.add_parser("play", help="serve the test signal and play it on the speaker via UPnP")
    add_stream_args(p)
    p.add_argument("--start-timeout", type=float, default=20.0, help="seconds to wait for the PLAYING state (default 20)")
    p.add_argument("--pause-at", type=float, default=None, metavar="SECONDS", help="also test Pause: pause this long after Play, then resume")
    p.add_argument("--pause-for", type=float, default=5.0, metavar="SECONDS", help="length of the pause (default 5)")

    p = sub.add_parser("ws", help="Sonos local websocket API: loadStreamUrl and poll playback status")
    add_stream_args(p)
    p.add_argument("--ws-port", type=int, default=1443, help="HTTPS/websocket port of the speaker (default 1443)")
    p.add_argument("--status-hz", type=float, default=10.0, help="getPlaybackStatus poll rate (default 10)")
    p.add_argument("--app-id", default="com.aiosonos.playback", help="createSession appId (aiosonos default)")
    p.add_argument("--app-context", default="1", help="createSession appContext (aiosonos default)")
    p.add_argument("--info-only", action="store_true", help="only print household / group / player ids and the playback status")
    p.add_argument("--also-upnp-poll", action="store_true", help="also poll GetPositionInfo (UPnP) while the websocket plays")

    p = sub.add_parser("measure", help="repeated-start experiment with acoustic truth (records via sox)")
    add_stream_args(p, duration_default=20.0)
    p.set_defaults(prebuffer=15.0)     # larger than any renderer buffer, so that writes block and read pacing becomes a signal
    p.add_argument("--trials", type=int, default=10)
    p.add_argument("--play-seconds", type=float, default=None, help="seconds of playback per trial (default: --duration, 20)")
    p.add_argument("--tail", type=float, default=3.0, help="keep recording this long after Stop (default 3)")
    p.add_argument("--lead", type=float, default=1.5, help="record this long before Play (default 1.5)")
    p.add_argument("--gap", type=float, default=4.0, help="pause between trials in seconds (default 4)")
    p.add_argument("--ws", action="store_true", help="also log the websocket positionMillis")
    p.add_argument("--ws-port", type=int, default=1443)
    p.add_argument("--status-hz", type=float, default=10.0)
    p.add_argument("--rec-device", default=None, help="sox input device name for AUDIODEV (default: system default input)")
    p.add_argument("--out", default=None, help="results directory (default results/<timestamp>)")
    p.add_argument("--snr-db", type=float, default=14.0, help="click detection threshold above the noise floor (default 14 dB)")

    p = sub.add_parser("drift", help="one long low-volume stream with continuous recording; click timing vs the Mac clock")
    add_stream_args(p, duration_default=0)
    p.set_defaults(prebuffer=15.0, volume=12)
    p.add_argument("--minutes", type=float, default=60.0)
    p.add_argument("--chunk-seconds", type=float, default=300.0, help="recording chunk length (default 300 = 5 min)")
    p.add_argument("--ws", action="store_true")
    p.add_argument("--ws-port", type=int, default=1443)
    p.add_argument("--status-hz", type=float, default=10.0)
    p.add_argument("--rec-device", default=None)
    p.add_argument("--out", default=None)
    p.add_argument("--snr-db", type=float, default=14.0, help="click detection threshold above the noise floor (default 14 dB)")
    p.add_argument("--lead", type=float, default=5.0)
    p.add_argument("--tail", type=float, default=6.0)

    p = sub.add_parser("volume", help="get or set the speaker volume (RenderingControl)")
    p.add_argument("--ip", required=True)
    g = p.add_mutually_exclusive_group()
    g.add_argument("--get", action="store_true", help="print the volume (default)")
    g.add_argument("--set", type=int, default=None, metavar="N")
    p.add_argument("--soap-port", type=int, default=1400)

    p = sub.add_parser("wol", help="send a Wake-on-LAN magic packet")
    p.add_argument("--mac", required=True, help="MAC address, e.g. 54:2a:1b:12:34:56")
    p.add_argument("--bcast", action="append", default=[], help="extra destination host[:port] (default 255.255.255.255:9 and the subnet broadcast)")
    p.add_argument("--only-bcast", action="store_true", help="send only to the --bcast destinations")
    p.add_argument("--dry-run", action="store_true", help="print the destinations without sending")
    p.add_argument("--local-ip", default=None)

    p = sub.add_parser("analyze", help="analyse existing recording(s): clicks, drift, dropouts")
    p.add_argument("files", nargs="+", help="wav files (several = contiguous chunks of one recording)")
    p.add_argument("--json", default=None, help="write the full analysis here")
    p.add_argument("--snr-db", type=float, default=14.0, help="click detection threshold above the noise floor (default 14 dB)")

    p = sub.add_parser("mock", help="run a mock Sonos renderer on localhost (development)")
    p.add_argument("--port", type=int, default=1400)
    p.add_argument("--buffer", type=float, default=3.0, help="seconds of audio the mock keeps in hand")
    p.add_argument("--reltime", choices=["advance", "frozen", "not_implemented"], default="advance")

    p = sub.add_parser("selftest", help="run all offline tests")
    p.add_argument("--quick", action="store_true", help="skip the slow real-time tests")
    p.add_argument("--keep", action="store_true", help="keep the temporary results directory")
    return ap


def main(argv=None) -> int:
    args = build_parser().parse_args(argv)
    c = args.cmd
    if c == "play":
        from sp.cmd_play import run_play
        return run_play(args)
    if c == "volume":
        from sp.cmd_play import run_volume
        return run_volume(args)
    if c == "discover":
        from sp.cmd_discover import run_discover
        return run_discover(args)
    if c == "listen":
        from sp.cmd_discover import run_listen
        return run_listen(args)
    if c == "wol":
        from sp.cmd_discover import run_wol
        return run_wol(args)
    if c == "mock":
        from sp.cmd_discover import run_mock
        return run_mock(args)
    if c == "ws":
        from sp.cmd_ws import run_ws
        return run_ws(args)
    if c == "measure":
        from sp.cmd_measure import run_measure
        return run_measure(args)
    if c == "drift":
        from sp.cmd_measure import run_drift
        return run_drift(args)
    if c == "analyze":
        from sp.cmd_measure import run_analyze
        return run_analyze(args)
    if c == "selftest":
        from sp.selftest import run_selftest
        return run_selftest(args)
    return 2


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(130)
