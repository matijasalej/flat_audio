"""Rig: stream server + UPnP control + 20 Hz pollers for one renderer (stdlib only)."""
from __future__ import annotations

import json
import secrets
import threading
import time
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional, Tuple

from . import testsignal as ts
from .common import RunLog, fmt, keep_awake, local_ip_for, mono, median, mean, stdev, linfit, percentile
from .server import StreamServer, summarize_conns
from .sources import INFO, make_source_factory, stream_path
from .upnp import (SoapClient, SoapError, build_uri, didl_broadcast, didl_track, parse_reltime)


class Poller(threading.Thread):
    """Calls ``fn`` at a fixed rate (absolute schedule) on its own thread."""

    def __init__(self, name: str, hz: float, fn: Callable[[], Any], on_result, on_error):
        super().__init__(name=name, daemon=True)
        self.period = 1.0 / hz
        self.fn, self.on_result, self.on_error = fn, on_result, on_error
        self._halt = threading.Event()
        self.n = 0
        self.n_err = 0
        self.slow = 0

    def run(self) -> None:
        nxt = mono()
        while not self._halt.is_set():
            now = mono()
            if now < nxt:
                self._halt.wait(nxt - now)
                continue
            t0 = mono()
            try:
                res = self.fn()
                self.on_result(t0, mono(), res)
                self.n += 1
            except Exception as e:      # keep polling through errors; they are logged
                self.n_err += 1
                self.on_error(t0, mono(), e)
            nxt += self.period
            if nxt < mono() - 5 * self.period:      # fell behind: do not burst to catch up
                self.slow += 1
                nxt = mono() + self.period

    def halt(self) -> None:
        self._halt.set()


class Rig:
    def __init__(self, ip: str, fmt_name: str, scheme: str = "x-rincon-mp3radio", klass: str = "broadcast",
                 prebuffer: float = 3.0, port: int = 8090, soap_port: int = 1400, poll_hz: float = 20.0,
                 run_dir: Optional[Path] = None, log: Optional[RunLog] = None, local_ip: Optional[str] = None,
                 seconds_needed: float = 900.0, title: str = "Whole Flat Audio test signal",
                 sndbuf: int = 16384, cache_dir: Optional[Path] = None, dlna_headers: bool = True):
        self.ip, self.fmt, self.scheme, self.klass = ip, fmt_name, scheme, klass
        self.prebuffer, self.port, self.soap_port, self.poll_hz = prebuffer, port, soap_port, poll_hz
        self.title = title
        self.run_dir = run_dir
        self.log = log or RunLog(echo=True)
        self.mac_ip = local_ip or local_ip_for(ip)
        kw = {}
        if cache_dir is not None:
            kw["cache_dir"] = cache_dir
        factory = make_source_factory(fmt_name, seconds_needed=seconds_needed, log=lambda m: self.log.say(m), **kw)
        self.path = stream_path(fmt_name)
        self.server = StreamServer({self.path: factory}, port=port, prebuffer=prebuffer, log=self.log, sndbuf=sndbuf,
                                   dlna_headers=dlna_headers)
        self.ctl = SoapClient(ip, soap_port)
        self.nonce = secrets.token_hex(3)
        # poll data
        self.pos_samples: List[Tuple[float, float, str, Optional[float]]] = []   # (t0, t1, raw, seconds)
        self.edges: List[Dict[str, Any]] = []
        self.trans_events: List[Tuple[float, str]] = []                          # (t, state) on change
        self.trans_samples = 0
        self._last_rel: Optional[float] = None
        self._last_rel_mid: Optional[float] = None
        self._last_state: Optional[str] = None
        self.errors: List[Tuple[float, str, str]] = []
        self._pollers: List[Poller] = []
        self.plays: List[Dict[str, Any]] = []
        self.prev_volume: Optional[int] = None
        self.verbose_edges = True

    # -- urls -------------------------------------------------------------------
    def stream_url(self) -> str:
        return "http://%s:%d%s?r=%s" % (self.mac_ip, self.server.port, self.path, self.nonce)

    # -- lifecycle ----------------------------------------------------------------
    def start(self) -> None:
        self._awake = keep_awake()
        self.server.start()
        self.log.say("stream server listening on 0.0.0.0:%d, URL %s (format %s, prebuffer %.1f s)" % (
            self.server.port, self.stream_url(), self.fmt, self.prebuffer))
        # reachability pre-check
        try:
            ti = SoapClient(self.ip, self.soap_port, timeout=4.0).transport_info()
            self.log.say("renderer reachable: transport state %s" % ti.get("CurrentTransportState"), kind="reach", ok=True)
        except Exception as e:
            raise SystemExit("cannot reach the renderer at %s:%d (%r).\n"
                             "Is the speaker awake? (on its charger, or Battery Saver off, UPnP enabled in the Sonos app)" % (
                                 self.ip, self.soap_port, e))
        self._start_pollers()

    def _start_pollers(self) -> None:
        pos_client = SoapClient(self.ip, self.soap_port, timeout=3.0)
        trans_client = SoapClient(self.ip, self.soap_port, timeout=3.0)
        p1 = Poller("poll-position", self.poll_hz, pos_client.position_info, self._on_pos, self._on_err("GetPositionInfo"))
        p2 = Poller("poll-transport", self.poll_hz, trans_client.transport_info, self._on_trans, self._on_err("GetTransportInfo"))
        self._pollers = [p1, p2]
        for p in self._pollers:
            p.start()

    def close(self) -> None:
        for p in self._pollers:
            p.halt()
        self.server.stop()
        self.ctl.close()

    # -- poll callbacks -----------------------------------------------------------
    def _on_err(self, what: str):
        def cb(t0: float, t1: float, e: Exception) -> None:
            self.errors.append((t0, what, repr(e)))
            self.log.emit("poll_err", t=t1, what=what, err=repr(e), rtt=round(t1 - t0, 4))
            if len(self.errors) in (1, 10, 100, 1000):
                self.log.say("%s error #%d: %r" % (what, len(self.errors), e))
        return cb

    def _on_pos(self, t0: float, t1: float, res: Dict[str, str]) -> None:
        raw = res.get("RelTime", "")
        sec = parse_reltime(raw)
        mid = 0.5 * (t0 + t1)
        self.pos_samples.append((t0, t1, raw, sec))
        if len(self.pos_samples) == 1:
            self.log.emit("pos", t=t1, t0=round(t0, 5), rel=raw, uri=res.get("TrackURI", "")[:80], dur=res.get("TrackDuration"))
        else:
            self.log.emit("pos", t=t1, t0=round(t0, 5), rel=raw)
        if sec is not None:
            if self._last_rel is not None and sec != self._last_rel:
                edge = {"t": 0.5 * (self._last_rel_mid + mid), "lo": self._last_rel_mid, "hi": mid,
                        "from": self._last_rel, "to": sec}
                self.edges.append(edge)
                self.log.emit("edge", t=edge["t"], lo=round(edge["lo"], 5), hi=round(edge["hi"], 5), frm=self._last_rel, to=sec)
                if self.verbose_edges:
                    tp = self.plays[-1]["t_play"] if self.plays else None
                    self.log.say("RelTime %s -> %s%s" % (fmt_hms_s(self._last_rel), fmt_hms_s(sec),
                                                         "  (%.3f s after Play)" % (edge["t"] - tp) if tp else ""), kind="msg_edge")
            self._last_rel, self._last_rel_mid = sec, mid

    def _on_trans(self, t0: float, t1: float, res: Dict[str, str]) -> None:
        st = res.get("CurrentTransportState", "")
        self.trans_samples += 1
        if st != self._last_state:
            mid = 0.5 * (t0 + t1)
            self.trans_events.append((mid, st))
            self.log.emit("trans", t=mid, state=st, status=res.get("CurrentTransportStatus"))
            tp = self.plays[-1]["t_play"] if self.plays else None
            self.log.say("transport state %s%s" % (st, "  (%.3f s after Play)" % (mid - tp) if tp else ""), kind="msg_trans")
            self._last_state = st

    # -- control ------------------------------------------------------------------
    def set_volume(self, v: int) -> None:
        try:
            self.prev_volume = self.ctl.get_volume()
        except Exception as e:
            self.log.say("GetVolume failed: %r" % (e,))
        self.ctl.set_volume(v)
        self.log.say("volume %s -> %d" % (self.prev_volume, v), kind="volume", prev=self.prev_volume, now=v)

    def restore_volume(self) -> None:
        if self.prev_volume is not None:
            try:
                self.ctl.set_volume(self.prev_volume)
                self.log.say("volume restored to %d" % self.prev_volume)
            except Exception as e:
                self.log.say("could not restore volume: %r" % (e,))

    def start_playback(self) -> Dict[str, Any]:
        url = self.stream_url()
        uri = build_uri(url, self.scheme)
        meta = (didl_broadcast(self.title) if self.klass == "broadcast"
                else didl_track(self.title, INFO[self.fmt].mime, uri))
        rec: Dict[str, Any] = {"uri": uri, "class": self.klass, "scheme": self.scheme, "fmt": self.fmt, "meta": meta}
        t = mono()
        self.ctl.set_uri(uri, meta)
        rec["t_set_uri"], rec["set_uri_rtt"] = t, mono() - t
        self.log.say("SetAVTransportURI ok in %.0f ms: %s" % (rec["set_uri_rtt"] * 1000, uri), kind="soap", action="SetAVTransportURI",
                     uri=uri, meta=meta, rtt=rec["set_uri_rtt"])
        try:
            mi = self.ctl.media_info()
            self.log.emit("soap", action="GetMediaInfo", resp=mi)
        except Exception as e:
            self.log.emit("soap_err", action="GetMediaInfo", err=repr(e))
        rec["t_play"] = mono()
        self.plays.append(rec)
        self.ctl.play()
        rec["t_play_ack"] = mono()
        self.log.say("Play acknowledged after %.0f ms" % ((rec["t_play_ack"] - rec["t_play"]) * 1000), kind="soap", action="Play",
                     rtt=rec["t_play_ack"] - rec["t_play"])
        return rec

    def wait_playing(self, timeout: float = 20.0) -> Optional[float]:
        """Seconds from the last Play to the first PLAYING transport state (None if never)."""
        t_play = self.plays[-1]["t_play"]
        end = mono() + timeout
        while mono() < end:
            for (t, st) in self.trans_events:
                if t >= t_play - 0.05 and st == "PLAYING":
                    self.plays[-1]["t_playing"] = t
                    return t - t_play
            time.sleep(0.02)
        return None

    def stop_playback(self, close_timeout: float = 10.0) -> Dict[str, Any]:
        rec = self.plays[-1]
        rec["t_stop"] = mono()
        err = None
        try:
            self.ctl.stop()
        except Exception as e:
            err = repr(e)
        rec["t_stop_ack"] = mono()
        self.log.say("Stop acknowledged after %.0f ms%s" % ((rec["t_stop_ack"] - rec["t_stop"]) * 1000, "  ERROR " + err if err else ""),
                     kind="soap", action="Stop", rtt=rec["t_stop_ack"] - rec["t_stop"], err=err)

        def stream_conns():
            return [c for c in self.server.conns if c.method == "GET" and c.t_accept >= rec["t_set_uri"] - 0.5]

        rec["stop_to_close_s"] = None
        if not stream_conns():
            self.log.say("the speaker never fetched the stream (no GET since SetAVTransportURI): check the Mac firewall for python on port %d, that --local-ip (%s) is the address the speaker can reach, and the URI scheme / class" % (self.server.port, self.mac_ip), kind="closed_after_stop", dt=None)
        else:
            end = mono() + close_timeout
            while mono() < end and not all(c.t_closed is not None for c in stream_conns()):
                time.sleep(0.01)
            cs = stream_conns()
            if all(c.t_closed is not None for c in cs):
                rec["t_conn_closed"] = max(c.t_closed for c in cs)
                rec["stop_to_close_s"] = rec["t_conn_closed"] - rec["t_stop"]
                who = ", ".join("conn %d: %s/%s" % (c.cid, c.closed_by, c.close_how) for c in cs)
                if rec["stop_to_close_s"] < 0:
                    self.log.say("the stream connection had already closed %.3f s BEFORE Stop (%s): the renderer dropped the stream by itself" % (
                        -rec["stop_to_close_s"], who), kind="closed_after_stop", dt=rec["stop_to_close_s"])
                else:
                    self.log.say("connection closed %.3f s after Stop was sent (%s)" % (rec["stop_to_close_s"], who),
                                 kind="closed_after_stop", dt=rec["stop_to_close_s"])
            else:
                n = self.server.close_all("not_closed_by_renderer")
                self.log.say("connection still open %.0f s after Stop: the renderer did not close it (we closed %d connection(s) ourselves)" % (
                    close_timeout, n), kind="closed_after_stop", dt=None)
        try:
            rec["transport_after_stop"] = self.ctl.transport_info().get("CurrentTransportState")
        except Exception:
            pass
        return rec

    # -- status line ----------------------------------------------------------------
    def status_line(self) -> str:
        c = next((c for c in reversed(self.server.conns) if c.method == "GET"), None)
        rel = self.pos_samples[-1][2] if self.pos_samples else "-"
        if c is None or c.t_first_byte is None:
            return "no stream connection yet, RelTime %s, state %s" % (rel, self._last_state)
        el = mono() - c.t_first_byte
        lead = c.samples / float(ts.FS) - el
        return "stream: %.1f s sent in %.1f s (lead %.2f s, blocked %.0f%%), RelTime %s, state %s" % (
            c.samples / float(ts.FS), el, lead, 100.0 * c.blocked / el if el > 0 else 0.0, rel, self._last_state)


def fmt_hms_s(sec: float) -> str:
    s = int(sec)
    return "%d:%02d:%02d" % (s // 3600, (s // 60) % 60, s % 60)


# ------------------------------------------------------------------ statistics over the logs

def edge_stats(edges: List[Dict[str, Any]], t_from: float, t_to: float) -> Dict[str, Any]:
    """How does RelTime advance? intervals between increments (+1 s expected)."""
    ev = [e for e in edges if t_from <= e["t"] <= t_to and e["to"] > e["from"]]
    out: Dict[str, Any] = {"n_edges": len(ev)}
    if len(ev) >= 2:
        iv = [b["t"] - a["t"] for a, b in zip(ev[:-1], ev[1:]) if b["to"] - a["to"] == 1]
        out["interval_mean_s"] = mean(iv) if iv else None
        out["interval_sd_ms"] = stdev(iv) * 1000 if len(iv) > 1 else None
        out["uncertainty_ms"] = median([(e["hi"] - e["lo"]) * 500 for e in ev])
        out["jumps"] = sum(1 for a, b in zip(ev[:-1], ev[1:]) if b["to"] - a["to"] != 1)
        a, b = linfit([e["t"] for e in ev], [e["to"] for e in ev])
        out["slope_s_per_s"] = b
    return out


def write_stats(records: List[Dict[str, Any]], cid: Optional[int] = None, bin_s: float = 5.0) -> Dict[str, Any]:
    """Read-pacing statistics from the server's write log records (kind 'w').

    lead(t) = audio time handed to the socket minus elapsed time since the first
    byte. While the renderer's buffer is not full the lead equals the prebuffer;
    when the renderer stops reading it saturates at (renderer buffer + socket
    buffers) and ``blk`` (time blocked in send) shows the renderer's own read
    rate. Bins of ``bin_s`` seconds in which more than half the time is spent
    blocked are "saturated": there the audio rate is the renderer's consumption rate.
    """
    w = [r for r in records if r["k"] == "w" and (cid is None or r["c"] == cid)]
    if len(w) < 5:
        return {"n_writes": len(w)}
    first = [r for r in records if r["k"] == "wh" and (cid is None or r["c"] == cid)]
    t_first = first[0]["t"] - first[0]["blk"] if first else w[0]["t"]
    el = [r["t"] - t_first for r in w]
    aud = [r["aud"] for r in w]
    blk = [r["blk"] for r in w]
    lead = [a - e for a, e in zip(aud, el)]
    total = el[-1]
    out: Dict[str, Any] = {"n_writes": len(w), "duration_s": total, "audio_s": aud[-1],
                           "mean_rate_x": aud[-1] / total if total > 0 else None,
                           "blocked_total_s": sum(blk), "blocked_pct": 100.0 * sum(blk) / total if total > 0 else None,
                           "max_blocked_s": max(blk), "lead_max_s": max(lead), "lead_final_s": lead[-1],
                           "lead_median_second_half_s": median(lead[len(lead) // 2:])}
    big = [i for i, b in enumerate(blk) if b > 0.05]
    if big:
        out["first_block_at_s"] = el[big[0]]
        out["audio_when_first_blocked_s"] = aud[big[0]]
    # bins
    bins: Dict[int, List[int]] = {}
    for i, e in enumerate(el):
        bins.setdefault(int(e // bin_s), []).append(i)
    sat_dt = sat_da = 0.0
    n_sat = 0
    for b, idx in sorted(bins.items()):
        if len(idx) < 3:
            continue
        dt = el[idx[-1]] - el[idx[0]]
        if dt < 0.5 * bin_s:
            continue
        frac = sum(blk[i] for i in idx[1:]) / dt
        if frac > 0.5:
            n_sat += 1
            sat_dt += dt
            sat_da += aud[idx[-1]] - aud[idx[0]]
    out["saturated_bins"] = n_sat
    out["saturated_s"] = sat_dt
    if sat_dt >= 20.0:
        out["saturated_rate_x"] = sat_da / sat_dt
        out["saturated_rate_ppm_vs_realtime"] = (sat_da / sat_dt - 1.0) * 1e6
    return out
