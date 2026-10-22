"""measure, drift, analyze (need numpy; use the venv)."""
from __future__ import annotations

import csv
import json
import math
import platform
import secrets
import subprocess
import sys
import threading
import time
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional, Sequence, Tuple

try:
    import numpy as np
except ImportError:      # pragma: no cover
    raise SystemExit("numpy is missing: run this command with .venv/bin/python (see README: pip install -r requirements.txt)")

from . import analysis as an
from . import testsignal as ts
from .codecs import codec_delay_s
from .common import RESULTS_DIR, RunLog, fmt, have_cmd, linfit, mean, median, mono, new_run_dir, percentile, stdev
from .record import PipeRecorder, RecInfo, SoxRecorder
from .rig import Rig, edge_stats

Pairs = List[Tuple[float, float]]

CLOCK_NOTE = ("The recording is mapped to the Mac's monotonic clock through the time at which each block of audio was read from `rec`. "
              "That gives every event a constant, unknown bias of +(input latency of the mic chain), typically 10-40 ms, which is "
              "the same in every trial: stdev / spread columns are unaffected, absolute offsets (play->sound, position signal "
              "offsets) carry it. Acoustic travel at 1 m adds 3 ms.")


# ------------------------------------------------------------------ small helpers

def env_info() -> Dict[str, str]:
    def run(cmd: List[str]) -> str:
        try:
            return subprocess.run(cmd, capture_output=True, text=True, timeout=5).stdout.strip().splitlines()[0]
        except Exception:
            return "?"
    d = {"python": sys.version.split()[0], "numpy": np.__version__, "platform": platform.platform(),
         "macos": run(["sw_vers", "-productVersion"]), "sox": run(["sox", "--version"]), "lame": run(["lame", "--version"])}
    try:
        import websockets
        d["websockets"] = websockets.__version__
    except ImportError:
        d["websockets"] = "not installed"
    return d


def col_stats(v: Sequence[Optional[float]]) -> Dict[str, Optional[float]]:
    x = [a for a in v if a is not None and a == a]
    if not x:
        return {"n": 0, "mean": None, "sd": None, "min": None, "max": None, "median": None}
    return {"n": len(x), "mean": mean(x), "sd": stdev(x) if len(x) > 1 else None, "min": min(x), "max": max(x), "median": median(x)}


def pair_offsets(pairs: Pairs, base: float) -> Dict[str, Any]:
    """Offsets of a position signal vs the acoustic truth.

    ``pairs`` are (time on the Mac clock, position in seconds) claims: "at time t the
    renderer was at position p". ``base`` = Mac-clock time at which decoder position 0
    was heard. offset = t - p - base: how late the claim is vs what was heard
    (negative = the signal runs ahead of the sound).
    """
    if not pairs:
        return {"n": 0}
    off = [t - p - base for t, p in pairs]
    return {"n": len(off), "median_s": median(off), "mean_s": mean(off), "sd_s": stdev(off) if len(off) > 1 else None,
            "min_s": min(off), "max_s": max(off)}


def slope_ppm(pairs: Pairs, quantum: float = 0.0) -> Optional[Dict[str, float]]:
    """Rate of a position signal vs the Mac clock: slope of (t - p) over t, in ppm.

    Positive = the signal advances slower than the Mac clock (renderer plays slower).
    ``quantum``: timing resolution of the claims in seconds (RelTime edges are only known to
    within one poll interval, and that error is correlated, not random): the reported standard
    error is never smaller than quantum / (2 * span).
    """
    if len(pairs) < 20:
        return None
    t = [a for a, _ in pairs]
    y = [a - b for a, b in pairs]
    span = max(t) - min(t)
    if span < 30:
        return None
    a, b = linfit(t, y)
    res = [yi - (a + b * ti) for ti, yi in zip(t, y)]
    sd = stdev(res)
    se = sd / (math.sqrt(len(t)) * stdev(t)) if stdev(t) > 0 else float("nan")
    se = max(se, 0.5 * quantum / span)
    return {"ppm": b * 1e6, "se_ppm": se * 1e6, "span_s": span, "n": len(t), "resid_sd_ms": sd * 1000}


def mic_check(make_recorder: Callable[..., PipeRecorder], run_dir: Path, log: RunLog, patience: float = 60.0) -> float:
    """Record 1.5 s of room noise; wait (up to ``patience`` s) for microphone permission. Returns noise rms in dBFS."""
    t_start = mono()
    first = True
    while True:
        try:
            rec = make_recorder(run_dir, "miccheck", chunk_seconds=None)
            rec.start(wait=12.0)
            time.sleep(1.5)
            info = rec.stop()
            tl = an.Timeline(info.paths)
            x = tl.read(0, info.n_samples)
            tl.close()
            if x.size and float(np.abs(x).max()) >= 3.0 / 32768.0:
                rms = 20 * math.log10(max(float(np.sqrt(np.mean(x.astype(np.float64) ** 2))), 1e-9))
                log.say("microphone ok: room noise %.1f dBFS rms, peak %.1f dBFS" % (rms, 20 * math.log10(float(np.abs(x).max()))))
                return rms
            err = "digital silence (peak below 3 LSB)"
        except Exception as e:
            err = repr(e)
        if first:
            log.say("no usable microphone signal yet (%s). Allow microphone access for your terminal app in the dialog "
                    "(System Settings > Privacy & Security > Microphone) and check that the input is not muted." % err)
            first = False
        if mono() - t_start > patience:
            raise SystemExit("microphone gives no signal after %.0f s (%s): grant microphone permission to your terminal and retry" % (patience, err))
        time.sleep(2.0)


def start_ws(args, log: RunLog):
    """Websocket status poller for --ws; a failure only costs that signal, not the session."""
    try:
        from .cmd_ws import WsStatusPoller
        ws = WsStatusPoller(args.ip, args.ws_port, args.status_hz, log)
        ws.start()
        return ws
    except BaseException as e:
        if isinstance(e, KeyboardInterrupt):
            raise
        log.say("websocket unavailable (%s): continuing without the positionMillis signal" % (e if str(e) else repr(e)))
        return None


def default_recorder_factory(args) -> Callable[..., PipeRecorder]:
    if not have_cmd("rec"):
        raise SystemExit("`rec` (sox) not found: brew install sox")

    def make(out_dir: Path, name: str, chunk_seconds: Optional[float] = None) -> PipeRecorder:
        return SoxRecorder(out_dir, name, fs=48000, chunk_seconds=chunk_seconds, device=getattr(args, "rec_device", None))
    return make


# ------------------------------------------------------------------ one trial

def analyze_trial(n: int, info: RecInfo, rig: Rig, play: Dict[str, Any], stop: Dict[str, Any],
                  ws_samples: Optional[list], D: float, snr_db: float = an.DEFAULT_SNR_DB) -> Dict[str, Any]:
    t_play, t_stop = play["t_play"], stop["t_stop"]
    res = an.analyze_files(info.paths, snr_db=snr_db, stop_hint=info.to_rec(t_stop))
    fit = res["fit"]
    out: Dict[str, Any] = {"trial": n, "ok": bool(fit.get("ok")), "recording": [str(p) for p in info.paths],
                           "clock": {"ratio_ppm": info.rate_ppm, "fit": info.clock_fit, "scatter_ms": info.clock_scatter_ms},
                           "play_to_playing_s": (play["t_playing"] - t_play) if "t_playing" in play else None,
                           "stop_to_close_s": stop.get("stop_to_close_s"),
                           "set_uri_ms": play["set_uri_rtt"] * 1000, "play_ack_ms": (play["t_play_ack"] - t_play) * 1000,
                           "stop_ack_ms": (stop["t_stop_ack"] - t_stop) * 1000}
    if not fit.get("ok"):
        out["why"] = fit.get("why", "analysis failed")
        out["n_detections"] = res["n_detections"]
        return out
    t0_rec = fit["t0"]
    t0_mono = info.to_mono(t0_rec)
    base = t0_mono - D                                  # Mac-clock time at which decoder position 0 was heard
    sound_end = (res.get("stop") or {}).get("silence_t_6db")
    sound_end20 = (res.get("stop") or {}).get("silence_t_20db")
    tone = res.get("tone", {})
    out.update(
        t0_mono=t0_mono, first_k=fit["k_first"], codec_delay_s=D,
        play_to_sound_s=t0_mono - t_play,
        stop_to_silence_s=(info.to_mono(sound_end) - t_stop) if sound_end else None,
        stop_to_silence_20db_s=(info.to_mono(sound_end20) - t_stop) if sound_end20 else None,
        sound_until_end=bool((res.get("stop") or {}).get("sound_until_end")),
        clicks_found=fit["n_clicks"], clicks_expected=fit["n_expected"], missing=fit["missing"],
        jitter_ms=fit["jitter_ms"], snr_db=fit["median_snr_db"], n_segments=fit["n_segments"], jumps=fit["jumps"],
        slope_ppm_rec=fit["slope_ppm"], outliers=fit["outliers"],
        tone_reliable=tone.get("reliable"), tone_snr_db=tone.get("tone_snr_db"),
        gaps=tone.get("gaps", []), phase_jumps=tone.get("phase_jumps", []),
        tone_first_sound_s=(info.to_mono(res["tone_first_sound_t"]) - t_play) if res.get("tone_first_sound_t") else None,
    )
    # --- RelTime edges (UPnP)
    edges = [e for e in rig.edges if t_play <= e["t"] <= t_stop and e["to"] > e["from"] and e["to"] >= 1]
    pairs = [(e["t"], e["to"]) for e in edges]
    out["reltime"] = {**pair_offsets(pairs, base), **edge_stats(rig.edges, t_play, t_stop)}
    out["reltime"]["first_edge_after_play_s"] = (edges[0]["t"] - t_play) if edges else None
    # --- websocket positionMillis
    if ws_samples is not None:
        wp = [(0.5 * (a + b), p) for (a, b, p, st) in ws_samples
              if t_play <= a <= t_stop and p is not None and st == "PLAYBACK_STATE_PLAYING" and p > 0]
        out["ws"] = pair_offsets(wp, base)
        any_samples = [s for s in ws_samples if t_play <= s[0] <= t_stop]
        out["ws"]["polls"] = len(any_samples)
        out["ws"]["advancing"] = bool(len(wp) > 5 and (max(p for _, p in wp) - min(p for _, p in wp)) > 2.0)
    # --- read pacing (bytes consumed once the renderer's buffer is full)
    recs = [r for r in rig.server.write_tap if play["t_set_uri"] - 0.5 <= r[0] <= t_stop]
    out["readpace"] = readpace_offsets(recs, base)
    return out


def readpace_offsets(recs: list, base: float, settle_s: float = 1.0) -> Dict[str, Any]:
    """Offset of 'audio handed to the renderer' vs 'audio heard', once sends block (renderer buffer full)."""
    if not recs:
        return {"n": 0, "saturated": False}
    # main connection = the one with most payload
    by: Dict[int, list] = {}
    for r in recs:
        by.setdefault(r[1], []).append(r)
    cid = max(by, key=lambda c: len(by[c]))
    rr = by[cid]
    first_block = next((i for i, r in enumerate(rr) if r[5] > 0.05), None)
    if first_block is None:
        return {"n": 0, "saturated": False, "blocked_pct": 0.0}
    t_sat = rr[first_block][0] + settle_s
    sat = [r for r in rr if r[0] >= t_sat]
    if len(sat) < 20:
        return {"n": len(sat), "saturated": False}
    pairs = [(r[0], r[4]) for r in sat]
    o = pair_offsets(pairs, base)
    o["saturated"] = True
    o["buffer_s"] = -o["median_s"]
    o["first_block_after_s"] = rr[first_block][0] - rr[0][0]
    o["t_sat"] = t_sat
    o["cid"] = cid
    return o


# ------------------------------------------------------------------ tables

def trial_row(t: Dict[str, Any]) -> List[str]:
    if not t.get("ok"):
        return [str(t["trial"]), "FAILED: " + str(t.get("why", "?"))] + [""] * 8
    rt, ws, rp = t.get("reltime", {}), t.get("ws"), t.get("readpace", {})
    miss = len(t["missing"])
    disc = (t["n_segments"] - 1) + len(t["phase_jumps"]) + len(t["gaps"])
    notes = []
    if t["first_k"] != 0:
        notes.append("first click k=%d" % t["first_k"])
    if t.get("tone_reliable") is False:
        notes.append("tone too quiet for stutter check")
    if t.get("sound_until_end"):
        notes.append("sound still audible at end of recording")
    if rt.get("n", 0) == 0:
        notes.append("RelTime did not advance")
    return [str(t["trial"]), fmt(t["play_to_sound_s"], 3), fmt(t.get("stop_to_silence_s"), 3),
            "%d/%d" % (t["clicks_found"], t["clicks_expected"]), fmt(t["jitter_ms"], 2),
            "%d gap, %d jump, %d miss, %d seg-break" % (len(t["gaps"]), len(t["phase_jumps"]), miss, t["n_segments"] - 1),
            "%s (%d)" % (fmt(rt.get("median_s"), 3), rt.get("n", 0)) if rt.get("n") else "n/a",
            ("%s (%d)" % (fmt(ws.get("median_s"), 3), ws.get("n", 0)) if ws and ws.get("n") else "n/a") if ws is not None else "-",
            fmt(rp.get("buffer_s"), 2) if rp.get("saturated") else "not full",
            "; ".join(notes)]


HEAD = ["trial", "play->sound s", "stop->silence s", "clicks", "jitter ms", "dropouts", "RelTime off s (n)", "ws pos off s (n)", "renderer buffer s", "notes"]


def make_summary(trials: List[Dict[str, Any]], params: Dict[str, Any], env: Dict[str, str], dev: Dict[str, Any]) -> str:
    ok = [t for t in trials if t.get("ok")]
    L: List[str] = []
    L.append("# measure: %s via %s, class %s, prebuffer %.0f s\n" % (params["fmt"], params["scheme"], params["cls"], params["prebuffer"]))
    L.append("%d trials, %d analysed. Speaker %s (%s), volume %s. Codec delay subtracted from position-signal offsets: %.1f ms.\n" % (
        len(trials), len(ok), dev.get("modelName", "?"), dev.get("displayVersion", "?"), params.get("volume", "unchanged"),
        (ok[0]["codec_delay_s"] * 1000) if ok else 0.0))
    L.append("| " + " | ".join(HEAD) + " |")
    L.append("|" + "|".join(["---"] * len(HEAD)) + "|")
    for t in trials:
        L.append("| " + " | ".join(trial_row(t)) + " |")

    def agg(label: str, get: Callable[[Dict[str, Any]], Optional[float]], nd: int = 3) -> str:
        s = col_stats([get(t) for t in ok])
        return "| %s | %s | %s | %s | %s | %d |" % (label, fmt(s["mean"], nd), fmt(s["sd"], nd), fmt(s["min"], nd), fmt(s["max"], nd), s["n"])

    L.append("\n## Across trials (seconds)\n")
    L.append("| quantity | mean | stdev | min | max | n |")
    L.append("|---|---|---|---|---|---|")
    L.append(agg("play -> sound (Play sent to first click heard)", lambda t: t["play_to_sound_s"]))
    L.append(agg("stop -> silence (Stop sent to tone at -6 dB)", lambda t: t.get("stop_to_silence_s")))
    L.append(agg("UPnP Play -> PLAYING state", lambda t: t.get("play_to_playing_s")))
    L.append(agg("Stop sent -> stream connection closed", lambda t: t.get("stop_to_close_s")))
    L.append(agg("RelTime edge offset vs heard (median per trial)", lambda t: t.get("reltime", {}).get("median_s")))
    L.append(agg("websocket positionMillis offset vs heard", lambda t: (t.get("ws") or {}).get("median_s")))
    L.append(agg("read pacing: audio buffered in renderer + sockets", lambda t: (t.get("readpace") or {}).get("buffer_s")))
    L.append(agg("within-trial sd of RelTime offset", lambda t: t.get("reltime", {}).get("sd_s")))
    L.append("\n## How to read this\n")
    L.append("* **play -> sound**: from the Play SOAP request being sent until click 0 is heard (includes codec delay). Its stdev across trials is the "
             "uncertainty of the start latency per session, i.e. what a sync app cannot know without feedback.")
    L.append("* **offsets** (RelTime, websocket, read pacing): `t_signal(P) - P - t_heard(0)`; negative = the signal runs ahead of the sound. "
             "A signal is usable for synchronisation if its offset is *stable* (small stdev across trials); a constant offset can be calibrated.")
    L.append("* **read pacing** needs the renderer's buffer to be full (writes block): 'not full' means `--prebuffer` was smaller than the buffer; "
             "the number includes the kernel socket buffers on both sides (our send buffer is 16 KB).")
    L.append("* **dropouts**: gap = the 440 Hz tone dropped by more than 9 dB; jump = a phase jump of the tone (skipped / repeated audio); "
             "miss = a click index never heard; seg-break = the click grid shifted (stutter).")
    L.append("* " + CLOCK_NOTE)
    L.append("\nEnvironment: " + ", ".join("%s %s" % (k, v) for k, v in env.items()))
    return "\n".join(L) + "\n"


# ------------------------------------------------------------------ measure

def run_measure(args, make_recorder: Optional[Callable[..., PipeRecorder]] = None, rig_hook: Optional[Callable[[Rig], None]] = None) -> int:
    make_recorder = make_recorder or default_recorder_factory(args)
    run_dir = Path(args.out) if args.out else new_run_dir("measure_" + args.fmt)
    run_dir.mkdir(parents=True, exist_ok=True)
    log = RunLog(run_dir / "measure.jsonl")
    log.say("results in %s" % run_dir)
    play_seconds = args.play_seconds if args.play_seconds is not None else args.duration
    D = codec_delay_s(args.fmt)
    log.say("format %s: encoder+decoder delay %.2f ms (subtracted from position-signal offsets)" % (args.fmt, D * 1000))
    need = args.trials * (play_seconds + 20) + 120
    rig = Rig(args.ip, args.fmt, scheme=args.scheme, klass=args.cls, prebuffer=args.prebuffer, port=args.port,
              soap_port=args.soap_port, poll_hz=args.poll_hz, run_dir=run_dir, log=log, local_ip=args.local_ip,
              seconds_needed=play_seconds + args.prebuffer + 60, cache_dir=getattr(args, "cache_dir", None))
    rig.verbose_edges = False
    ws = None
    trials: List[Dict[str, Any]] = []
    dev: Dict[str, Any] = {}
    rc = 0
    try:
        rig.start()
        if rig_hook:
            rig_hook(rig)
        try:
            from .upnp import fetch_description, parse_description
            dev = parse_description(fetch_description(args.ip, args.soap_port)[0])
        except Exception:
            dev = {}
        if args.volume is not None:
            rig.set_volume(args.volume)
        if args.ws:
            ws = start_ws(args, log)
        mic_check(make_recorder, run_dir, log)
        for n in range(1, args.trials + 1):
            if rc == 130:
                break
            log.say("---- trial %d/%d" % (n, args.trials))
            tdir = run_dir
            rec = None
            rec_info = None
            try:
                rig.nonce = secrets.token_hex(3)
                rec = make_recorder(tdir, "trial_%02d" % n, chunk_seconds=None)
                rec.start()
                time.sleep(args.lead)
                play = rig.start_playback()
                lat = rig.wait_playing(args.start_timeout if hasattr(args, "start_timeout") else 20.0)
                t_end = play["t_play_ack"] + play_seconds
                while mono() < t_end:
                    time.sleep(0.05)
                stop = rig.stop_playback()
                time.sleep(args.tail)
                rec_info = rec.stop()
                rec = None
                t = analyze_trial(n, rec_info, rig, play, stop, ws.samples if ws else None, D, getattr(args, "snr_db", an.DEFAULT_SNR_DB))
            except KeyboardInterrupt:
                log.say("interrupted")
                rc = 130
                break
            except SystemExit:
                raise
            except Exception as e:
                log.say("trial %d failed: %r" % (n, e))
                t = {"trial": n, "ok": False, "why": repr(e)}
                try:
                    rig.ctl.stop()
                except Exception:
                    pass
            finally:
                if rec is not None:
                    try:
                        rec.stop()
                    except Exception:
                        pass
            trials.append(t)
            log.emit("trial", **{k: v for k, v in t.items() if k not in ("recording",)})
            row = trial_row(t)
            log.say("trial %d: " % n + "; ".join("%s=%s" % (h, v) for h, v in zip(HEAD, row) if v and h != "trial"))
            if t.get("ok") and (t["clicks_found"] < 0.7 * t["clicks_expected"] or t["snr_db"] < 15):
                log.say("WARNING: weak microphone signal (%d of %d clicks, SNR %.0f dB): raise the speaker volume (--volume), move the Mac "
                        "closer to the speaker, and check Mic Mode = Standard" % (t["clicks_found"], t["clicks_expected"], t["snr_db"]))
            if n == 1 and not t.get("ok"):
                log.say("trial 1 produced no usable recording: check the microphone level / distance / speaker volume (see README) "
                        "before spending 10 trials. Aborting.")
                rc = 1
                break
            try:
                time.sleep(args.gap)
            except KeyboardInterrupt:
                log.say("interrupted")
                rc = 130
                break
    except KeyboardInterrupt:
        log.say("interrupted")
        rc = 130
    finally:
        try:
            rig.ctl.stop()
        except Exception:
            pass
        if args.volume is not None:
            rig.restore_volume()
        if ws:
            ws.stop()
        rig.close()
    params = {"fmt": args.fmt, "scheme": args.scheme, "cls": args.cls, "prebuffer": args.prebuffer, "volume": args.volume if args.volume is not None else "unchanged"}
    env = env_info()
    md = make_summary(trials, params, env, dev)
    (run_dir / "summary.md").write_text(md)
    (run_dir / "trials.json").write_text(json.dumps(trials, indent=2, default=str))
    log.close()
    print("\n" + md)
    print("summary: %s" % (run_dir / "summary.md"))
    return rc


# ------------------------------------------------------------------ drift

def drift_report(res: Dict[str, Any], info: RecInfo, rig: Rig, play: Dict[str, Any], stop: Dict[str, Any],
                 ws_samples: Optional[list], D: float, run_dir: Path) -> Dict[str, Any]:
    fit = res["fit"]
    rep: Dict[str, Any] = {"ok": bool(fit.get("ok"))}
    if not fit.get("ok"):
        rep["why"] = fit.get("why")
        return rep
    clicks = res["clicks"]
    k = np.array([c["k"] for c in clicks], dtype=np.float64)
    t_rec = np.array([c["t"] for c in clicks], dtype=np.float64)
    seg = np.array([c["seg"] for c in clicks])
    ratio = info.ratio
    s_rec = fit["slope_ppm"] * 1e-6
    drift_mac_ppm = ((1.0 + s_rec) * ratio - 1.0) * 1e6
    dur = info.duration
    ratio_unc_ppm = (info.clock_scatter_ms / 1000.0) / dur * 1e6 if dur > 0 and info.clock_scatter_ms == info.clock_scatter_ms else float("nan")
    t_mono = info.t_origin + ratio * t_rec
    t0_mono = info.to_mono(fit["t0"])
    # offset of each click from the schedule 'k seconds after click 0' on the Mac clock, per segment-corrected line
    off_ms = (t_mono - k - t0_mono) * 1000.0
    rep.update(
        clicks=fit["n_clicks"], expected=fit["n_expected"], missing=fit["missing"], n_missing=fit["n_missing"],
        k_first=fit["k_first"], k_last=fit["k_last"], segments=fit["segments"], jumps=fit["jumps"],
        jitter_ms=fit["jitter_ms"], p99_ms=fit["p99_abs_ms"], snr_db=fit["median_snr_db"],
        slope_ppm_recording_clock=fit["slope_ppm"], recorder_clock_vs_mac_ppm=info.rate_ppm,
        recorder_clock_uncertainty_ppm=ratio_unc_ppm, clock_fit=info.clock_fit,
        drift_vs_mac_clock_ppm=drift_mac_ppm,
        total_offset_change_ms=float(off_ms[-1] - off_ms[0]) if off_ms.size else None,
        gaps=res.get("tone", {}).get("gaps", []), phase_jumps=res.get("tone", {}).get("phase_jumps", []),
        tone_reliable=res.get("tone", {}).get("reliable"), tone_snr_db=res.get("tone", {}).get("tone_snr_db"),
        duration_s=dur, codec_delay_s=D, t0_mono=t0_mono,
        stream_connections=[{"conn": c.cid, "t_rel_s": c.t_accept - play["t_play"], "closed_by": c.closed_by, "how": c.close_how,
                             "audio_s": c.samples / float(ts.FS)} for c in rig.server.conns if c.method == "GET"],
    )
    # per-chunk table (recording time bins of 5 minutes)
    rows = []
    binw = 300.0
    for b in range(int(t_rec.max() // binw) + 1):
        m = (t_rec >= b * binw) & (t_rec < (b + 1) * binw)
        if not m.any():
            continue
        o = off_ms[m]
        rows.append({"minute": b * binw / 60.0, "clicks": int(m.sum()), "median_offset_ms": float(np.median(o)), "min_ms": float(o.min()), "max_ms": float(o.max())})
    rep["bins"] = rows
    # position signals
    base = t0_mono - D
    t_play, t_stop = play["t_play"], stop["t_stop"]
    edges = [e for e in rig.edges if t_play <= e["t"] <= t_stop and e["to"] > e["from"] and e["to"] - e["from"] == 1]
    rp = [(e["t"], e["to"]) for e in edges]
    rep["reltime"] = {"n": len(rp), **edge_stats(rig.edges, t_play, t_stop), "offset": pair_offsets(rp, base),
                      "drift": slope_ppm(rp, quantum=1.0 / rig.poll_hz)}
    if ws_samples is not None:
        wp = [(0.5 * (a + b), p) for (a, b, p, st) in ws_samples if t_play <= a <= t_stop and p is not None and st == "PLAYBACK_STATE_PLAYING" and p > 0]
        rep["ws"] = {"n": len(wp), "offset": pair_offsets(wp, base), "drift": slope_ppm(wp)}
    recs = [r for r in rig.server.write_tap if play["t_set_uri"] - 0.5 <= r[0] <= t_stop]
    rpace = readpace_offsets(recs, base, settle_s=5.0)
    if rpace.get("saturated"):
        pr = [(r[0], r[4]) for r in recs if r[1] == rpace["cid"] and r[0] >= rpace["t_sat"]]
        rpace["drift"] = slope_ppm(pr)
    rep["readpace"] = rpace
    # csv of per-click offsets
    with open(run_dir / "drift_offsets.csv", "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["k", "t_mono_s", "offset_ms_vs_schedule_on_mac_clock", "segment"])
        for i in range(k.size):
            w.writerow([int(k[i]), "%.6f" % t_mono[i], "%.3f" % off_ms[i], int(seg[i])])
    return rep


def drift_summary_md(rep: Dict[str, Any], params: Dict[str, Any], env: Dict[str, str]) -> str:
    L: List[str] = []
    L.append("# drift: %s via %s, %.1f min, prebuffer %.0f s\n" % (params["fmt"], params["scheme"], params["minutes"], params["prebuffer"]))
    if not rep.get("ok"):
        return "\n".join(L) + "NO RESULT: %s\n" % rep.get("why")
    L.append("Convention: **positive ppm = the speaker plays slower than the Mac sends** (events arrive later and later on the Mac clock).\n")
    L.append("| signal | drift vs Mac clock | details |")
    L.append("|---|---|---|")
    L.append("| acoustic clicks | **%s ppm** (+-%s, uncertainty of the recorder-clock mapping) | %d clicks, jitter %s ms (p99 %s ms), total change over the run %s ms |" % (
        fmt(rep["drift_vs_mac_clock_ppm"], 2), fmt(rep["recorder_clock_uncertainty_ppm"], 1), rep["clicks"], fmt(rep["jitter_ms"], 3),
        fmt(rep["p99_ms"], 3), fmt(rep.get("total_offset_change_ms"), 1)))

    def sig(label: str, d: Optional[Dict[str, Any]]) -> None:
        if not d:
            return
        dr = d.get("drift")
        if dr:
            L.append("| %s | %s ppm (+-%s) | %d claims over %.0f s, residual sd %s ms, offset vs heard median %s s |" % (
                label, fmt(dr["ppm"], 2), fmt(dr["se_ppm"], 2), dr["n"], dr["span_s"], fmt(dr["resid_sd_ms"], 1), fmt(d.get("offset", {}).get("median_s"), 3)))
        else:
            L.append("| %s | n/a | %d claims (needs >= 20 over >= 30 s) |" % (label, d.get("n", 0)))
    sig("UPnP RelTime edges", rep.get("reltime"))
    sig("websocket positionMillis", rep.get("ws"))
    rp = rep.get("readpace") or {}
    if rp.get("saturated") and rp.get("drift"):
        sig("read pacing (renderer-limited sends)", {"drift": rp["drift"], "offset": {"median_s": rp.get("median_s")}, "n": rp["n"]})
    else:
        L.append("| read pacing | n/a | renderer buffer never full (raise --prebuffer) |")
    L.append("\nRecorder clock vs Mac monotonic clock: %s ppm (%s). The acoustic figure above is already corrected for it.\n" % (
        fmt(rep["recorder_clock_vs_mac_ppm"], 2), rep["clock_fit"]))
    L.append("## Dropouts\n")
    L.append("* missing clicks: %d of %d%s" % (rep["n_missing"], rep["expected"], (" (k = %s%s)" % (", ".join(map(str, rep["missing"][:20])), " ..." if rep["n_missing"] > 20 else "")) if rep["n_missing"] else ""))
    L.append("* click-grid breaks (stutters/skips): %d%s" % (len(rep["jumps"]), "".join("\n  * after click %d: %+.1f ms at t=%.0f s" % (j["after_k"], j["jump_ms"], j["t"]) for j in rep["jumps"][:20])))
    L.append("* 440 Hz tone gaps: %d%s" % (len(rep["gaps"]), "".join("\n  * t=%.1f s: %.0f ms (%.0f dB)" % (g["t"], g["dur_ms"], g["min_db"]) for g in rep["gaps"][:20])) if rep.get("tone_reliable") else
             "* 440 Hz tone gaps: not evaluated (tone SNR %s dB too low; raise the volume)" % fmt(rep.get("tone_snr_db"), 1))
    L.append("* tone phase jumps: %d" % len(rep["phase_jumps"]) if rep.get("tone_reliable") else "")
    L.append("* stream connections: %d%s" % (len(rep["stream_connections"]), "".join("\n  * conn %d opened %+.1f s after Play, closed by %s (%s) after %.0f s of audio" % (
        c["conn"], c["t_rel_s"], c["closed_by"], c["how"], c["audio_s"]) for c in rep["stream_connections"][:20])))
    L.append("\n## Offset of clicks from the schedule (Mac clock), per 5 minutes\n")
    L.append("| minute | clicks | median offset ms | min | max |")
    L.append("|---|---|---|---|---|")
    for b in rep["bins"]:
        L.append("| %.0f | %d | %.2f | %.2f | %.2f |" % (b["minute"], b["clicks"], b["median_offset_ms"], b["min_ms"], b["max_ms"]))
    L.append("\n" + CLOCK_NOTE)
    L.append("\nEnvironment: " + ", ".join("%s %s" % (k, v) for k, v in env.items()))
    return "\n".join(x for x in L if x != "") + "\n"


def run_drift(args, make_recorder: Optional[Callable[..., PipeRecorder]] = None, rig_hook: Optional[Callable[[Rig], None]] = None) -> int:
    make_recorder = make_recorder or default_recorder_factory(args)
    run_dir = Path(args.out) if args.out else new_run_dir("drift_" + args.fmt)
    run_dir.mkdir(parents=True, exist_ok=True)
    log = RunLog(run_dir / "drift.jsonl")
    total = args.minutes * 60.0
    log.say("results in %s; %.1f minutes, chunks of %.0f s" % (run_dir, args.minutes, args.chunk_seconds))
    D = codec_delay_s(args.fmt)
    rig = Rig(args.ip, args.fmt, scheme=args.scheme, klass=args.cls, prebuffer=args.prebuffer, port=args.port,
              soap_port=args.soap_port, poll_hz=args.poll_hz, run_dir=run_dir, log=log, local_ip=args.local_ip,
              seconds_needed=total + args.prebuffer + 120, cache_dir=getattr(args, "cache_dir", None))
    rig.verbose_edges = False
    ws = None
    rec: Optional[PipeRecorder] = None
    play = stop = None
    rc = 0
    info: Optional[RecInfo] = None
    try:
        rig.start()
        if rig_hook:
            rig_hook(rig)
        if args.volume is not None:
            rig.set_volume(args.volume)
        if args.ws:
            ws = start_ws(args, log)
        mic_check(make_recorder, run_dir, log)
        rec = make_recorder(run_dir, "drift", chunk_seconds=args.chunk_seconds)
        rec.start()
        time.sleep(args.lead)
        play = rig.start_playback()
        rig.wait_playing(20.0)
        t_end = play["t_play_ack"] + total
        t_level = play["t_play_ack"] + 30.0
        next_status = mono() + 60.0
        level_done = False
        try:
            while mono() < t_end:
                time.sleep(0.2)
                if not level_done and mono() >= t_level:
                    level_done = True
                    x, t_first = rec.recent(20.0)
                    cl = an.detect_clicks(x, rec.fs, t_offset_samples=int(t_first * rec.fs))
                    if len(cl) < 10:
                        log.say("WARNING: only %d clicks heard in the last 20 s - microphone level / volume too low? (Ctrl-C to abort, "
                                "or raise --volume / move the Mac closer)" % len(cl))
                    else:
                        log.say("level check: %d clicks in 20 s, median SNR %.1f dB" % (len(cl), float(np.median([c.snr_db for c in cl]))))
                if mono() >= next_status:
                    next_status += 60.0
                    x, t_first = rec.recent(30.0)
                    cl = an.detect_clicks(x, rec.fs, t_offset_samples=int(t_first * rec.fs))
                    log.say("minute %.0f: %s | last 30 s: %d clicks%s | ws %s" % (
                        (mono() - play["t_play_ack"]) / 60.0, rig.status_line(), len(cl),
                        " (SNR %.0f dB)" % float(np.median([c.snr_db for c in cl])) if cl else " (!)",
                        ("%.2f s" % ws.samples[-1][2]) if ws and ws.samples and ws.samples[-1][2] is not None else "-"))
        except KeyboardInterrupt:
            log.say("interrupted: finishing and analysing what was recorded")
            rc = 130
        stop = rig.stop_playback()
        time.sleep(args.tail)
        info = rec.stop()
        rec = None
    finally:
        try:
            rig.ctl.stop()
        except Exception:
            pass
        if rec is not None:
            try:
                rec.stop()
            except Exception:
                pass
        if args.volume is not None:
            rig.restore_volume()
        if ws:
            ws.stop()
        rig.close()
    if info is None or play is None or stop is None:
        log.close()
        return 1
    log.say("recorded %.1f min in %d chunk(s); analysing" % (info.duration / 60.0, len(info.paths)))
    last = [0.0]

    def progress(t_s: float) -> None:
        if t_s - last[0] >= 300:
            last[0] = t_s
            log.say("  analysed %.0f min of %.0f" % (t_s / 60.0, info.duration / 60.0))
    res = an.analyze_files(info.paths, snr_db=getattr(args, "snr_db", an.DEFAULT_SNR_DB), stop_hint=info.to_rec(stop["t_stop"]), progress=progress)
    rep = drift_report(res, info, rig, play, stop, ws.samples if ws else None, D, run_dir)
    params = {"fmt": args.fmt, "scheme": args.scheme, "prebuffer": args.prebuffer, "minutes": args.minutes}
    md = drift_summary_md(rep, params, env_info())
    (run_dir / "drift_summary.md").write_text(md)
    (run_dir / "drift.json").write_text(json.dumps(rep, indent=2, default=str))
    (run_dir / "clicks.json").write_text(json.dumps(res["clicks"]))
    log.close()
    print("\n" + md)
    print("summary: %s" % (run_dir / "drift_summary.md"))
    return rc


# ------------------------------------------------------------------ analyze

def print_analysis(res: Dict[str, Any], say=print) -> None:
    f = res["fit"]
    say("recording: %.1f s at %d Hz; %d detections" % (res["duration_s"], res["fs"], res["n_detections"]))
    if not f.get("ok"):
        say("no clicks found: %s" % f.get("why"))
        return
    say("clicks: %d found, k = %d..%d (%d expected, %d missing%s)" % (
        f["n_clicks"], f["k_first"], f["k_last"], f["n_expected"], f["n_missing"], ": " + str(f["missing"][:10]) if f["n_missing"] else ""))
    say("click 0 heard at t = %.4f s (recording clock); slope %+.2f ppm vs recording clock; jitter %.3f ms (p99 %.3f); median SNR %.1f dB" % (
        f["t0"], f["slope_ppm"], f["jitter_ms"], f["p99_abs_ms"], f["median_snr_db"]))
    if f["n_segments"] > 1:
        say("click grid breaks (stutter / skip): %d" % (f["n_segments"] - 1))
        for j in f["jumps"][:20]:
            say("   after click %d: %+.1f ms (at t = %.1f s)" % (j["after_k"], j["jump_ms"], j["t"]))
    tone = res.get("tone")
    if tone:
        if tone.get("reliable"):
            say("440 Hz tone: level %.1f dBFS (SNR %s dB): %d gap(s), %d phase jump(s)" % (
                tone["ref_db"], fmt(tone.get("tone_snr_db"), 1), len(tone["gaps"]), len(tone["phase_jumps"])))
            for g in tone["gaps"][:20]:
                say("   gap at t = %.3f s, %.0f ms long (%.0f dB)" % (g["t"], g["dur_ms"], g["min_db"]))
            for j in tone["phase_jumps"][:20]:
                say("   phase jump at t = %.3f s (= %+.2f ms of audio)" % (j["t"], j["equiv_ms"]))
        else:
            say("440 Hz tone: SNR %s dB is too low for gap / stutter detection" % fmt(tone.get("tone_snr_db"), 1))
    if res.get("tone_first_sound_t") is not None:
        say("tone starts at t = %.3f s" % res["tone_first_sound_t"])
    st = res.get("stop")
    if st and st.get("silence_t_6db"):
        say("tone ends at t = %.3f s (-6 dB)%s" % (st["silence_t_6db"], ", -20 dB at %.3f s" % st["silence_t_20db"] if st.get("silence_t_20db") else ""))


def run_analyze(args) -> int:
    res = an.analyze_files(args.files, snr_db=getattr(args, "snr_db", an.DEFAULT_SNR_DB))
    print_analysis(res)
    if args.json:
        Path(args.json).write_text(json.dumps(res, indent=2, default=str))
    return 0 if res["fit"].get("ok") else 1
