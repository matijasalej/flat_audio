"""`play`, `volume` commands (stdlib only: they work without the venv)."""
from __future__ import annotations

import json
import time
from pathlib import Path
from typing import Any, Dict, List, Optional

from .common import RunLog, fmt, mono, new_run_dir, read_jsonl, RESULTS_DIR
from .rig import Rig, edge_stats, write_stats
from .server import summarize_conns
from .upnp import SoapClient, SoapError, parse_reltime


def play_summary(rig: Rig, rec: Dict[str, Any], log_path: Optional[Path], t_from: float, t_to: float) -> Dict[str, Any]:
    """Everything worth knowing about one play, as a JSON-able dict."""
    conns = summarize_conns(rig.server.conns)
    stream_conn = next((c for c in reversed(rig.server.conns) if c.method == "GET" and c.bytes_total > 1000), None)
    records = read_jsonl(log_path) if log_path and log_path.exists() else []
    ws = write_stats(records, stream_conn.cid if stream_conn else None) if records else {}
    es = edge_stats(rig.edges, t_from, t_to)
    rels = [(t0, t1, raw, sec) for (t0, t1, raw, sec) in rig.pos_samples if t_from <= t0 <= t_to]
    n_impl = sum(1 for r in rels if r[3] is not None)
    distinct = sorted(set(r[3] for r in rels if r[3] is not None))
    tr = [(round(t - rec["t_play"], 3), st) for (t, st) in rig.trans_events if t >= rec["t_play"] - 0.5]
    summ: Dict[str, Any] = {
        "fmt": rec["fmt"], "scheme": rec["scheme"], "class": rec["class"], "uri": rec["uri"],
        "prebuffer_s": rig.prebuffer,
        "play_to_playing_s": (rec["t_playing"] - rec["t_play"]) if "t_playing" in rec else None,
        "stop_to_close_s": rec.get("stop_to_close_s"),
        "stop_ack_ms": (rec["t_stop_ack"] - rec["t_stop"]) * 1000 if "t_stop_ack" in rec else None,
        "transport_events": tr,
        "connections": conns,
        "writes": ws,
        "reltime": {"samples": len(rels), "parsable": n_impl, "distinct_values": len(distinct),
                    "max": distinct[-1] if distinct else None, **es},
        "poll": {"position_ok": rig._pollers[0].n if rig._pollers else 0, "position_err": rig._pollers[0].n_err if rig._pollers else 0,
                 "position_late": rig._pollers[0].slow if rig._pollers else 0,
                 "transport_ok": rig._pollers[1].n if rig._pollers else 0, "transport_err": rig._pollers[1].n_err if rig._pollers else 0},
    }
    return summ


def print_play_summary(s: Dict[str, Any], say=print) -> None:
    say("---- summary: format %s, scheme %s, class %s, prebuffer %.1f s" % (s["fmt"], s["scheme"], s["class"], s["prebuffer_s"]))
    stc = s["stop_to_close_s"]
    say("  Play -> PLAYING: %s    Stop -> connection closed: %s%s    (Stop SOAP ack %s ms)" % (
        fmt(s["play_to_playing_s"], 3, " s"), fmt(stc, 3, " s"),
        " (negative: the renderer had already dropped the stream)" if stc is not None and stc < 0 else
        (" (not closed by the renderer, or never fetched)" if stc is None else ""), fmt(s["stop_ack_ms"], 0)))
    say("  transport: " + (", ".join("%s @%+.2fs" % (st, t) for t, st in s["transport_events"]) or "no state changes seen"))
    for c in s["connections"]:
        say("  conn %d: %s %s  UA=%r range=%r icy=%r -> %d B in %.1f s (%.2f s audio), blocked %.1f s (%.0f%%, max %.2f s), closed by %s (%s)" % (
            c["conn"], c["method"], c["target"], c["user_agent"], c["range"], c["icy"], c["bytes"], c["duration_s"], c["audio_s"],
            c["blocked_s"], c["blocked_pct"], c["max_blocked_s"], c["closed_by"], c["how"]))
    w = s["writes"]
    if w.get("n_writes", 0) > 5:
        say("  read pacing: mean audio rate %sx real time; lead (audio handed over minus elapsed) max %s s, steady %s s; "
            "first blocked after %s s of stream (audio %s s)" % (
                fmt(w.get("mean_rate_x"), 4), fmt(w.get("lead_max_s"), 2), fmt(w.get("lead_median_second_half_s"), 2),
                fmt(w.get("first_block_at_s"), 2), fmt(w.get("audio_when_first_blocked_s"), 2)))
        if "saturated_rate_x" in w:
            say("  saturated (renderer-limited) for %.0f s: rate %.6fx = %+.0f ppm vs real time" % (
                w["saturated_s"], w["saturated_rate_x"], w["saturated_rate_ppm_vs_realtime"]))
        else:
            say("  never saturated for 20 s+ (renderer buffer larger than the lead): read pacing says nothing about its clock;"
                " raise --prebuffer to saturate it")
    if s.get("pause"):
        pz = s["pause"]
        say("  pause test: transport %s, connection %s during the pause, %d KB sent meanwhile, resume -> PLAYING after %s" % (
            pz["state_during_pause"], "open" if pz["conn_open_during_pause"] else "CLOSED", pz["bytes_sent_during_pause"] // 1024, fmt(pz["resume_to_playing_s"], 3, " s")))
    r = s["reltime"]
    say("  RelTime: %d polls, %d parsable, %d distinct values (max %s), %d increments%s" % (
        r["samples"], r["parsable"], r["distinct_values"], fmt(r["max"], 0), r["n_edges"],
        (", interval %.4f s (sd %.1f ms), edge uncertainty +-%.0f ms, jumps %d" % (
            r["interval_mean_s"], r["interval_sd_ms"] or 0, r["uncertainty_ms"], r["jumps"])) if r.get("interval_mean_s") else ""))
    p = s["poll"]
    say("  polling: position %d ok / %d errors / %d late, transport %d ok / %d errors" % (
        p["position_ok"], p["position_err"], p["position_late"], p["transport_ok"], p["transport_err"]))


def run_play(args) -> int:
    run_dir = new_run_dir("play_" + args.fmt)
    log_path = run_dir / "play.jsonl"
    log = RunLog(log_path)
    log.say("results in %s" % run_dir)
    need = args.duration + args.prebuffer + 90
    rig = Rig(args.ip, args.fmt, scheme=args.scheme, klass=args.cls, prebuffer=args.prebuffer, port=args.port,
              soap_port=args.soap_port, poll_hz=args.poll_hz, run_dir=run_dir, log=log, local_ip=args.local_ip,
              seconds_needed=need, cache_dir=getattr(args, "cache_dir", None))
    rec = None
    t_begin = mono()
    pause_info: Optional[Dict[str, Any]] = None
    try:
        rig.start()
        if args.volume is not None:
            rig.set_volume(args.volume)
        try:
            rec = rig.start_playback()
        except SoapError as e:
            log.say("the speaker refused: %s" % e, kind="soap_error", action=e.action, code=e.code, desc=e.desc, http=e.http_status, body=e.body)
            log.say("raw reply: %s" % e.body)
            return 1
        lat = rig.wait_playing(args.start_timeout)
        if lat is None:
            log.say("transport never reported PLAYING within %.0f s (see transport events in the summary)" % args.start_timeout)
        else:
            log.say("transport PLAYING %.3f s after Play" % lat)
        t_end = rec["t_play_ack"] + args.duration
        t_pause = rec["t_play_ack"] + args.pause_at if args.pause_at is not None else None
        nxt = mono() + 10.0
        while mono() < t_end:
            time.sleep(0.05)
            if mono() >= nxt:
                nxt += 10.0
                log.say(rig.status_line())
            if t_pause is not None and mono() >= t_pause:
                t_pause = None
                pause_info = do_pause(rig, log, args.pause_for)
                t_end += args.pause_for
        rig.stop_playback()
        time.sleep(1.0)
    except KeyboardInterrupt:
        log.say("interrupted: stopping playback")
        if rec is not None and "t_stop" not in rec:
            try:
                rig.stop_playback()
            except Exception:
                pass
    finally:
        try:
            if rec is not None and "t_stop" not in rec:
                rig.ctl.stop()
        except Exception:
            pass
        try:
            if args.volume is not None:
                rig.restore_volume()
        except Exception:
            pass
        rig.close()
    if rec is None:
        log.close()
        return 1
    time.sleep(0.2)
    summ = play_summary(rig, rec, log_path, rec["t_set_uri"] - 1.0, mono())
    if pause_info:
        summ["pause"] = pause_info
    log.close()
    (run_dir / "play_summary.json").write_text(json.dumps(summ, indent=2, default=str))
    print_play_summary(summ)
    print("  full log: %s  (%d records)" % (log_path, log.count))
    return 0


def do_pause(rig: Rig, log: RunLog, pause_for: float) -> Dict[str, Any]:
    """Pause, wait, resume with Play. Records what the renderer and the stream connection do meanwhile."""
    info: Dict[str, Any] = {}
    conn = next((c for c in reversed(rig.server.conns) if c.method == "GET"), None)
    b0 = conn.bytes_total if conn else 0
    info["t_pause"] = mono()
    rig.ctl.pause()
    info["pause_ack_ms"] = (mono() - info["t_pause"]) * 1000
    log.say("Pause acknowledged after %.0f ms" % info["pause_ack_ms"], kind="soap", action="Pause")
    time.sleep(pause_for)
    info["state_during_pause"] = rig._last_state
    info["conn_open_during_pause"] = conn is not None and conn.t_closed is None
    info["bytes_sent_during_pause"] = (conn.bytes_total - b0) if conn else 0
    info["rel_during_pause"] = rig.pos_samples[-1][2] if rig.pos_samples else None
    log.say("paused for %.1f s: transport %s, stream connection %s, %d KB sent meanwhile, RelTime %s" % (
        pause_for, info["state_during_pause"], "still open" if info["conn_open_during_pause"] else "CLOSED", info["bytes_sent_during_pause"] // 1024, info["rel_during_pause"]),
        kind="pause_result")
    info["t_resume"] = mono()
    rig.ctl.play()
    log.say("Play (resume) acknowledged after %.0f ms" % ((mono() - info["t_resume"]) * 1000), kind="soap", action="Play")
    end = mono() + 8.0
    while mono() < end:
        if any(t >= info["t_resume"] - 0.05 and st == "PLAYING" for t, st in rig.trans_events):
            break
        time.sleep(0.02)
    t_pl = next((t for t, st in rig.trans_events if t >= info["t_resume"] - 0.05 and st == "PLAYING"), None)
    info["resume_to_playing_s"] = (t_pl - info["t_resume"]) if t_pl else None
    log.say("resume: PLAYING again after %s" % fmt(info["resume_to_playing_s"], 3, " s"), kind="pause_result")
    return info


def run_volume(args) -> int:
    c = SoapClient(args.ip, args.soap_port)
    if args.set is not None:
        before = c.get_volume()
        c.set_volume(args.set)
        print("volume %d -> %d (now reads %d)" % (before, args.set, c.get_volume()))
    else:
        print("volume %d" % c.get_volume())
    return 0
