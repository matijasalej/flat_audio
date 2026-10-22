"""Offline self-tests (`sonos_probe.py selftest`). Everything talks to 127.0.0.1 only.

Never contacts a real device, never records from the microphone, sends no multicast.
"""
from __future__ import annotations

import contextlib
import io
import json
import os
import shutil
import socket
import struct
import subprocess
import sys
import threading
import time
import traceback
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional, Tuple

from . import common
from . import codecs
from . import flac as flacmod
from . import testsignal as ts
from . import wavio
from .common import RunLog, have_cmd, mono, read_jsonl

try:
    import numpy as np
except ImportError:      # pragma: no cover
    raise SystemExit("selftest needs numpy and websockets: run with .venv/bin/python (pip install -r requirements.txt)")

from . import analysis as an
from . import synth

CTX: Dict[str, Any] = {}


def check(cond: bool, msg: str) -> None:
    if not cond:
        raise AssertionError(msg)


def parse_args(argv: List[str]):
    import sonos_probe
    return sonos_probe.build_parser().parse_args(argv)


@contextlib.contextmanager
def quiet():
    buf = io.StringIO()
    with contextlib.redirect_stdout(buf):
        yield buf


# ------------------------------------------------------------------ signal / headers / FLAC

def t_signal() -> str:
    tr = ts.track()
    n = 12 * ts.FS
    x = np.asarray(tr.mono_i16(0, n), dtype=np.int32)
    tone_amp = 10 ** (ts.TONE_DBFS / 20)
    tone = np.rint(32767 * tone_amp * np.sin(2 * np.pi * ts.TONE_HZ * (np.arange(n) % ts.FS) / ts.FS)).astype(np.int32)
    d = x - tone
    cl = ts.click_len()
    for k in range(11):
        a = k * ts.FS
        check(d[a] == 0 and d[a + 1] != 0, "click %d does not start at sample k*44100 (d[a]=%d d[a+1]=%d)" % (k, d[a], d[a + 1]))
        win = d[a:a + cl + 50]
        nz = np.flatnonzero(np.abs(win) > 2)
        check(nz.size and nz[0] <= 2 and nz[-1] <= cl, "click %d has the wrong extent %s" % (k, (nz[0], nz[-1]) if nz.size else None))
        rest = d[a + cl + 5:a + ts.FS]
        check(np.abs(rest).max() <= 1, "click %d leaks outside its 5 ms" % k)
        crossings = int(np.sum(np.signbit(win[:cl - 40]).astype(np.int8)[1:] != np.signbit(win[:cl - 40]).astype(np.int8)[:-1]))
        expect = 2 * 10 * (cl - 40) / cl if k % 10 else 2 * 20 * (cl - 40) / cl
        check(abs(crossings - expect) <= 3, "click %d: %d zero crossings, expected ~%.0f (frequency)" % (k, crossings, expect))
    # arbitrary-offset slices equal slices of the long signal
    for start, ln in ((12345, 10000), (ts.FS * 3 - 7, 20000), (ts.FS * 10 + 5, 3000)):
        a = np.asarray(tr.mono_i16(start, ln), dtype=np.int32)
        check((a == x[start:start + ln]).all(), "slice at %d differs" % start)
    st = np.frombuffer(tr.pcm(777, 4000), dtype="<i2").reshape(-1, 2)
    check((st[:, 0] == st[:, 1]).all() and (st[:, 0] == x[777:4777]).all(), "stereo pcm not equal to mono signal")
    return "click k at sample k*44100 exactly, 2 kHz / 4 kHz markers at k%%10==0, %d-sample bursts, tone periodic with 1 s" % cl


def t_wav_header() -> str:
    h = ts.endless_wav_header()
    check(len(h) == 44, "header size")
    riff = struct.unpack("<I", h[4:8])[0]
    data = struct.unpack("<I", h[40:44])[0]
    check(h[:4] == b"RIFF" and h[8:12] == b"WAVE" and h[12:16] == b"fmt " and h[36:40] == b"data", "chunk ids")
    tag, ch, fs, br, ba, bits = struct.unpack("<HHIIHH", h[20:36])
    check((tag, ch, fs, br, ba, bits) == (1, 2, 44100, 176400, 4, 16), "fmt chunk %r" % ((tag, ch, fs, br, ba, bits),))
    check(riff == 36 + data and riff < 2 ** 31 and data < 2 ** 31, "sizes must be just under 2**31: riff %d data %d" % (riff, data))
    check(2 ** 31 - 2 ** 10 < riff < 2 ** 31 and data % 4 == 0, "sizes not 'just under' 2**31")
    check(ts.WAV_CONTENT_LENGTH == 44 + data and ts.WAV_CONTENT_LENGTH < 2 ** 31, "Content-Length")
    d = CTX["tmp"] / "hdr"
    d.mkdir(exist_ok=True)
    p = d / "endless.wav"
    p.write_bytes(h + ts.track().pcm(0, ts.FS))
    info = wavio.read_info(str(p))
    check(info.frames == ts.FS, "reader must fall back to the file size for an endless header, got %d frames" % info.frames)
    return "RIFF size %d, data size %d, Content-Length %d (all < 2^31), header parses" % (riff, data, ts.WAV_CONTENT_LENGTH)


def t_flac() -> str:
    d = CTX["tmp"] / "flac"
    d.mkdir(exist_ok=True)
    tr = ts.track()
    notes = []
    for n in (10 * ts.FS, 107 * 4096, 107 * 4096 + 1, 100):
        pcm = tr.pcm(0, n)
        data = flacmod.encode_pcm(pcm)
        p = d / "t.flac"
        p.write_bytes(data)
        check(data[:4] == b"fLaC" and data[4] == 0x80 and data[5:8] == b"\x00\x00\x22", "marker / STREAMINFO block header")
        si = data[8:42]
        check(struct.unpack(">HH", si[:4]) == (4096, 4096), "blocksize in STREAMINFO")
        packed = struct.unpack(">Q", si[10:18])[0]
        check((packed >> 44, ((packed >> 41) & 7) + 1, ((packed >> 36) & 31) + 1, packed & ((1 << 36) - 1)) == (44100, 2, 16, 0), "STREAMINFO rate/channels/bits/total samples 0")
        check(si[18:] == b"\x00" * 16, "MD5 must be zero")
        w = flacmod.FrameWalker()
        for i in range(0, len(data), 1013):
            w.feed(data[i:i + 1013])
        check(w.samples == n, "frame walker counted %d samples, expected %d" % (w.samples, n))
        out = d / "afc.wav"
        r = subprocess.run(["afconvert", "-f", "WAVE", "-d", "LEI16", str(p), str(out)], capture_output=True, text=True)
        check(r.returncode == 0, "afconvert failed to decode: %s" % r.stderr.strip())
        got = wavio.read_pcm_bytes(str(out))
        check(got == pcm, "afconvert decode of %d samples is not bit-exact" % n)
        if have_cmd("sox"):
            out2 = d / "sox.wav"
            r = subprocess.run(["sox", str(p), "-b", "16", str(out2)], capture_output=True, text=True)
            if r.returncode == 0:
                check(wavio.read_pcm_bytes(str(out2)) == pcm, "sox decode of %d samples is not bit-exact" % n)
                notes.append("sox")
    for v in (0, 0x7F, 0x80, 0x7FF, 0x800, 0xFFFF, 0x10000, 0x10FFFF):
        check(flacmod.utf8_number(v) == chr(v).encode("utf-8", "surrogatepass"), "UTF-8 frame number %x" % v)
    for v in (0x200000, 0x3FFFFFF, 0x4000000, 0xFFFFFFFFF):
        e = flacmod.utf8_number(v)
        check(flacmod.parse_utf8_number(e, 0) == (v, len(e)), "UTF-8 roundtrip %x" % v)
    return "4 lengths (10 s, 107 full frames, +1 sample, 100 samples) bit-exact via afconvert%s; 0.06 %% overhead; STREAMINFO total samples 0 / MD5 0" % (" and sox" if notes else "")


# ------------------------------------------------------------------ codecs and detector

def t_codecs() -> str:
    work = CTX["tmp"] / "codec"
    res = codecs.measure_codec_delays(work, seconds=24, log=lambda *_: None)
    check("mp3" in res and "aac" in res, "delay measurement incomplete: %r" % res)
    m, a = res["mp3"], res["aac"]
    check(0 < m["used_samples"] < 1300, "mp3 delay implausible: %r" % m)
    check(1500 < a["used_samples"] < 3000, "aac delay implausible: %r" % a)
    # every click found after decode: delay_of() already requires >= seconds-4 clicks; check explicitly for the wav files
    for name in ("mp3_lame.wav", "mp3_afc.wav", "aac_afc.wav"):
        p = work / name
        if p.exists():
            r = an.analyze_files([p], tone=False)
            f = r["fit"]
            check(f["ok"] and f["n_clicks"] >= 22 and f["n_missing"] == 0 and f["k_first"] == 0, "%s: clicks %s missing %s" % (name, f.get("n_clicks"), f.get("n_missing")))
    # store for the analyses (real cache)
    common.CACHE_DIR.mkdir(parents=True, exist_ok=True)
    (common.CACHE_DIR / codecs.DELAY_FILE).write_text(json.dumps(res, indent=2))
    CTX["delays"] = res
    return "MP3 (lame 320k CBR stream): %d samples = %.3f ms [lame --decode %.3f ms, afconvert %.3f ms]; AAC (afconvert 256k ADTS): %d samples = %.3f ms; all clicks found" % (
        m["used_samples"], m["used_s"] * 1000, m.get("lame_decode_s", float("nan")) * 1000, m.get("afconvert_decode_s", float("nan")) * 1000,
        a["used_samples"], a["used_s"] * 1000)


def t_detector() -> str:
    d = CTX["tmp"] / "det"
    d.mkdir(exist_ok=True)
    out = []
    # 1) the specified case: delay 1.234 s, scaled, noise, a short dropout
    for fs in (48000, 44100):
        D = 1.234
        x = synth.synth_recording(fs=fs, duration=30, delay_s=D, gain=0.05, noise_dbfs=-55, dropouts=[(D + 7.45, 0.08)], seed=5)
        synth.write_wav_f32(str(d / "a.wav"), x, fs)
        r = an.analyze_files([d / "a.wav"])
        f = r["fit"]
        err_ms = (f["t0"] - D) * 1000
        check(abs(err_ms) <= 1.0, "fs %d: delay error %.3f ms" % (fs, err_ms))
        gaps = r["tone"]["gaps"]
        check(len(gaps) == 1 and abs(gaps[0]["t"] - (D + 7.45)) < 0.03 and 60 < gaps[0]["dur_ms"] < 110, "dropout not flagged correctly: %r" % gaps)
        check(f["n_missing"] == 0 and f["n_segments"] == 1, "unexpected missing / segments")
        out.append("%d Hz: delay error %+.3f ms, 80 ms dropout flagged as %.0f ms at %.3f s" % (fs, err_ms, gaps[0]["dur_ms"], gaps[0]["t"]))
    # 2) dropout that swallows a click
    x = synth.synth_recording(fs=48000, duration=30, delay_s=1.234, gain=0.05, noise_dbfs=-55, dropouts=[(13.9, 0.5)])
    synth.write_wav_f32(str(d / "b.wav"), x, 48000)
    f = an.analyze_files([d / "b.wav"])["fit"]
    check(f["missing"] == [13], "missing clicks %r" % f["missing"])
    # 3) stutters: fractional skip, whole-second skip, repeat
    for name, skips, expect in (("skip 0.2137 s", [(11.0, 0.2137)], -213.7), ("skip 3 s", [(11.0, 3.0)], -3000.0), ("repeat 0.2 s", [(11.0, -0.2)], 200.0)):
        x = synth.synth_recording(fs=48000, duration=30, delay_s=1.234, gain=0.05, noise_dbfs=-55, skips=skips)
        synth.write_wav_f32(str(d / "c.wav"), x, 48000)
        f = an.analyze_files([d / "c.wav"])["fit"]
        check(f["n_segments"] == 2 and len(f["jumps"]) == 1 and abs(f["jumps"][0]["jump_ms"] - expect) < 1.5,
              "%s: segments %d jumps %r" % (name, f["n_segments"], f["jumps"]))
        check(abs((f["t0"] - 1.234) * 1000) <= 1.0, "%s: t0 of the first segment must stay correct" % name)
    # 4) clock error and several chunks
    chunks = []
    # delay 2.998 s: click 27 starts ~1 ms before the first chunk boundary (30 s), so a burst straddles two files
    x = synth.synth_recording(fs=48000, duration=120, delay_s=2.998, gain=0.05, noise_dbfs=-55, clock_ppm=37.0, dropouts=[(100.0, 0.3)], seed=9)
    for i in range(4):
        p = d / ("chunk_%d.wav" % i)
        synth.write_wav_f32(str(p), x[i * 30 * 48000:(i + 1) * 30 * 48000], 48000)
        chunks.append(p)
    r = an.analyze_files(chunks)
    f = r["fit"]
    check(abs(f["slope_ppm"] - 37.0) < 0.3, "slope %.3f ppm, expected 37" % f["slope_ppm"])
    # with delay 2.998 s and +37 ppm click 97 arrives at 100.0016 s, i.e. inside the 0.3 s dropout: it must be reported missing, nothing else
    check(f["missing"] == [97] and f["n_segments"] == 1 and f["n_clicks"] == 116, "chunked analysis: clicks %d missing %r segments %d" % (f["n_clicks"], f["missing"], f["n_segments"]))
    check(r["tone"]["gaps"] and abs(r["tone"]["gaps"][0]["t"] - (100.0)) < 0.05, "dropout across chunks: %r" % r["tone"]["gaps"])
    out.append("4 chunks (one click straddles a file boundary), +37 ppm clock: recovered %.3f ppm, jitter %.3f ms" % (f["slope_ppm"], f["jitter_ms"]))
    # 5) noise bursts at the wrong phase must not disturb the click series
    x = synth.synth_recording(fs=48000, duration=30, delay_s=1.234, gain=0.05, noise_dbfs=-55, extra_events=[(5.4, -20), (9.33, -15), (17.8, -25), (20.0, -10)])
    synth.write_wav_f32(str(d / "e.wav"), x, 48000)
    f = an.analyze_files([d / "e.wav"])["fit"]
    check(f["n_clicks"] == 29 and f["n_segments"] == 1 and abs((f["t0"] - 1.234) * 1000) <= 1.0, "noise bursts disturbed the series: %r" % {k: f[k] for k in ("n_clicks", "n_segments")})
    # 6) low tone SNR must not produce false dropout reports
    x = synth.synth_recording(fs=48000, duration=30, delay_s=1.234, gain=0.05, noise_dbfs=-38)
    synth.write_wav_f32(str(d / "f.wav"), x, 48000)
    r = an.analyze_files([d / "f.wav"])
    check(r["tone"]["reliable"] is False and not r["tone"]["gaps"] and r["fit"]["n_clicks"] == 29, "low-SNR handling")
    return "; ".join(out) + "; chunk boundaries, click-swallowing dropout, 3 kinds of stutter, noise bursts, low SNR all handled"


# ------------------------------------------------------------------ server

def read_stream(port: int, path: str, seconds: float, headers: Optional[Dict[str, str]] = None) -> Tuple[Dict[str, str], bytes, float]:
    import http.client
    c = http.client.HTTPConnection("127.0.0.1", port)
    t0 = mono()
    c.request("GET", path, headers=headers or {"Icy-MetaData": "1"})
    r = c.getresponse()
    hdr = {k.lower(): v for k, v in r.getheaders()}
    buf = bytearray()
    while mono() - t0 < seconds:
        d = r.read1(8192)
        if not d:
            break
        buf += d
    el = mono() - t0
    c.close()
    return hdr, bytes(buf), el


def t_server_formats() -> str:
    from .server import StreamServer
    from .sources import FORMATS, INFO, make_source_factory, stream_path
    tmp = CTX["tmp"] / "srv"
    tmp.mkdir(parents=True, exist_ok=True)
    cache = tmp / "cache"
    tr = ts.track()
    notes = []
    for fmt in FORMATS:
        log = RunLog(None, echo=False)
        fac = make_source_factory(fmt, seconds_needed=60, cache_dir=cache, log=lambda *_: None)
        path = stream_path(fmt)
        srv = StreamServer({path: fac}, port=0, host="127.0.0.1", prebuffer=3.0, log=log)
        port = srv.start()
        hdr, data, el = read_stream(port, path + "?x=1", 3.0)
        srv.stop()
        time.sleep(0.2)
        info = INFO[fmt]
        check(hdr.get("content-type") == info.mime, "%s content-type %r" % (fmt, hdr.get("content-type")))
        counter = codecs.StreamCounter(fmt)
        if info.transfer == "length":
            check(hdr.get("content-length") == str(ts.WAV_CONTENT_LENGTH) and "transfer-encoding" not in hdr, "wav headers %r" % hdr)
        elif info.transfer == "chunked":
            check(hdr.get("transfer-encoding") == "chunked" and "content-length" not in hdr, "flac-chunked headers %r" % hdr)
        else:
            check("content-length" not in hdr and "transfer-encoding" not in hdr, "%s must be close-delimited: %r" % (fmt, hdr))
        got_s = counter.feed(data) / float(ts.FS)
        check(abs(got_s - (el + 3.0)) < 0.4, "%s: %.2f s of audio after %.2f s with prebuffer 3 (expected ~%.2f)" % (fmt, got_s, el, el + 3.0))
        if fmt in ("mp3", "aac"):
            check(counter.split.lost_sync_bytes == 0 and len(counter.split.buf) < 1600, "%s: frames do not tile the stream exactly (lost %d, partial %d)" % (
                fmt, counter.split.lost_sync_bytes, len(counter.split.buf)))
        if fmt == "wav":
            nsamp = (len(data) - 44) // 4
            check(data[:44] == ts.endless_wav_header() and data[44:44 + 4 * nsamp] == tr.pcm(0, nsamp), "wav payload differs from the generator")
        if fmt.startswith("flac"):
            p = tmp / ("s_%s.flac" % fmt)
            w = flacmod.FrameWalker()
            w.feed(data)
            n = w.samples
            p.write_bytes(data[:w.consumed])          # cut at the last complete frame
            r = subprocess.run(["afconvert", "-f", "WAVE", "-d", "LEI16", str(p), str(tmp / "s.wav")], capture_output=True, text=True)
            check(r.returncode == 0, "afconvert cannot decode the live %s stream: %s" % (fmt, r.stderr.strip()[:200]))
            pcm = wavio.read_pcm_bytes(str(tmp / "s.wav"))
            check(len(pcm) // 4 >= n - 4096 and pcm == tr.pcm(0, len(pcm) // 4), "live %s stream does not decode to the exact signal" % fmt)
        if fmt == "aac":
            fpath = codecs.ensure_aac(60, cache, log=lambda *_: None)
            check(fpath.read_bytes()[:len(data)] == data, "aac stream is not a byte-exact prefix of the cached file")
        if fmt == "mp3":
            p = tmp / "s.mp3"
            p.write_bytes(data)
            r = subprocess.run(["lame", "--silent", "--decode", str(p), str(tmp / "m.wav")], capture_output=True, text=True)
            check(r.returncode == 0, "lame cannot decode the live mp3 stream")
            f = an.analyze_files([tmp / "m.wav"], tone=False)["fit"]
            check(f["ok"] and f["n_clicks"] >= int(got_s) - 1 and f["k_first"] == 0, "mp3 stream: clicks %r" % f.get("n_clicks"))
        notes.append("%s %.2fs/%.2fs" % (fmt, got_s, el))
    return "audio delivered = elapsed + 3 s prebuffer (" + ", ".join(notes) + "); headers per format; payload bit-exact (wav, flac via afconvert, aac file prefix), mp3 frames tile the stream"


def t_server_blocking() -> str:
    """A slow reader makes our sends block (16 KB send buffer) and the log records it."""
    import http.client
    from .server import StreamServer
    from .sources import make_source_factory, stream_path
    log = RunLog(CTX["tmp"] / "block.jsonl", echo=False)
    srv = StreamServer({stream_path("wav"): make_source_factory("wav")}, port=0, host="127.0.0.1", prebuffer=20.0, log=log, sndbuf=16384)
    port = srv.start()
    c = http.client.HTTPConnection("127.0.0.1", port)
    c.request("GET", "/stream.wav")
    r = c.getresponse()
    t0 = mono()
    total = 0
    while mono() - t0 < 5.0:               # read at ~1x real time (176 400 B/s) after the burst
        d = r.read(17640)
        total += len(d)
        time.sleep(0.1)
    t_close = mono()
    r.close()                              # (with `Connection: close` http.client hands the socket to the response object)
    c.close()
    for _ in range(30):
        time.sleep(0.05)
        if srv.conns and srv.conns[0].t_closed:
            break
    check(srv.conns[0].t_closed is not None and srv.conns[0].t_closed - t_close < 1.0 and srv.conns[0].closed_by == "peer",
          "the server did not notice the peer closing while its sends were blocked (closed_by=%r)" % srv.conns[0].closed_by)
    detect = srv.conns[0].t_closed - t_close
    srv.stop()
    time.sleep(0.2)
    log.close()
    recs = read_jsonl(CTX["tmp"] / "block.jsonl")
    req = next(x for x in recs if x["k"] == "req")
    check(req["sndbuf"] == 16384, "SO_SNDBUF is %r" % req["sndbuf"])
    w = [x for x in recs if x["k"] == "w"]
    check(len(w) > 20 and all(k in w[0] for k in ("t", "n", "cum", "pay", "aud", "blk", "late")), "write records incomplete: %r" % (w[0] if w else None))
    blocked = sum(x["blk"] for x in w)
    check(blocked > 2.0, "sender was blocked only %.2f s by a reader at real-time speed" % blocked)
    check(all(b["cum"] >= a["cum"] for a, b in zip(w[:-1], w[1:])), "cumulative bytes not monotonic")
    from .rig import write_stats
    ws = write_stats(recs)
    check(ws["blocked_pct"] > 40, "blocked %% %.1f" % ws["blocked_pct"])
    check(any(x["k"] == "closed" for x in recs), "connection close not logged")
    return "SO_SNDBUF=16384; %d writes logged (t, cum bytes, audio s, blocked s); %.1f s blocked of 5 s with a real-time reader; peer close noticed %.0f ms after it happened although sends were blocked" % (len(w), blocked, detect * 1000)


def t_server_http() -> str:
    """HEAD, Range (wav), 404, 405."""
    import http.client
    from .server import StreamServer
    from .sources import make_source_factory, stream_path
    srv = StreamServer({"/stream.wav": make_source_factory("wav")}, port=0, host="127.0.0.1", prebuffer=1.0, log=RunLog(None, echo=False))
    port = srv.start()
    c = http.client.HTTPConnection("127.0.0.1", port)
    c.request("HEAD", "/stream.wav")
    r = c.getresponse()
    check(r.status == 200 and r.getheader("Content-Length") == str(ts.WAV_CONTENT_LENGTH), "HEAD")
    c.close()
    c = http.client.HTTPConnection("127.0.0.1", port)
    c.request("GET", "/stream.wav", headers={"Range": "bytes=%d-" % (44 + 4 * 88200)})
    r = c.getresponse()
    check(r.status == 206 and r.getheader("Content-Range", "").startswith("bytes %d-" % (44 + 4 * 88200)), "Range response %r" % r.getheaders())
    body = r.read(4 * 4410)
    check(body == ts.track().pcm(88200, 4410), "ranged payload is not the signal from sample 88200")
    c.close()
    c = http.client.HTTPConnection("127.0.0.1", port)
    c.request("GET", "/nope")
    check(c.getresponse().status == 404, "404")
    c.close()
    c = http.client.HTTPConnection("127.0.0.1", port)
    c.request("POST", "/stream.wav", body=b"x")
    check(c.getresponse().status == 405, "405")
    c.close()
    srv.stop()
    return "HEAD ok, Range bytes=N- gives 206 and the signal from that sample, 404, 405"


# ------------------------------------------------------------------ mock renderer: play for each format

def run_play_on_mock(fmt: str, extra: Optional[List[str]] = None, mock_kw: Optional[Dict[str, Any]] = None, duration: int = 10) -> Dict[str, Any]:
    from .mock import MockRenderer
    from .cmd_play import run_play
    m = MockRenderer(port=0, **(mock_kw or {}))
    mp = m.start()
    argv = ["play", "--ip", "127.0.0.1", "--soap-port", str(mp), "--fmt", fmt, "--duration", str(duration), "--prebuffer", "6", "--port", "0",
            "--volume", "17", "--scheme", "x-rincon-mp3radio", "--class", "broadcast"] + (extra or [])
    args = parse_args(argv)
    args.cache_dir = CTX["tmp"] / "cache"
    with quiet() as buf:
        rc = run_play(args)
    m.stop()
    check(rc == 0, "play returned %s:\n%s" % (rc, buf.getvalue()[-1500:]))
    runs = sorted(common.RESULTS_DIR.glob("*_play_%s" % fmt))
    check(runs, "no results directory for %s" % fmt)
    run = runs[-1]
    return {"run": run, "mock": m, "out": buf.getvalue()}


def t_play(fmt: str) -> str:
    r = run_play_on_mock(fmt)
    run, m = r["run"], r["mock"]
    recs = read_jsonl(run / "play.jsonl")               # every line must parse
    kinds = {x["k"] for x in recs}
    for k in ("req", "wh", "w", "pos", "trans", "edge", "closed", "soap"):
        check(k in kinds, "log has no %r records (kinds: %s)" % (k, sorted(kinds)))
    s = json.loads((run / "play_summary.json").read_text())
    check(s["fmt"] == fmt and s["scheme"] == "x-rincon-mp3radio" and s["class"] == "broadcast", "summary header")
    check(s["play_to_playing_s"] is not None and 0.2 < s["play_to_playing_s"] < 2.5, "play->PLAYING %r" % s["play_to_playing_s"])
    check(s["stop_to_close_s"] is not None and s["stop_to_close_s"] < 2.0, "stop->close %r" % s["stop_to_close_s"])
    rt = s["reltime"]
    check(rt["n_edges"] >= 8 and abs(rt["interval_mean_s"] - 1.0) < 0.01 and rt["jumps"] == 0, "RelTime stats %r" % rt)
    check(s["poll"]["position_err"] == 0 and s["poll"]["transport_err"] == 0 and s["poll"]["position_ok"] > 150, "poll counts %r" % s["poll"])
    c = s["connections"][0]
    check(c["method"] == "GET" and c["closed_by"] in ("peer", "server") and c["bytes"] > 10000 and "Sonos" in c["user_agent"], "connection row %r" % c)
    w = s["writes"]
    check(w["n_writes"] > 20 and w["lead_max_s"] > 2.0, "write stats %r" % w)
    check("17" in "".join(x.get("msg", "") for x in recs if x["k"] == "volume" or x["k"] == "msg") and m.volume == 20, "volume set and restored (mock volume %d)" % m.volume)
    check("x-rincon-mp3radio://" in m.uri and m.last_didl_class == "object.item.audioItem.audioBroadcast", "mock got %r / %r" % (m.uri, m.last_didl_class))
    check("PLAYING" in r["out"] and "---- summary" in r["out"], "console summary missing")
    return "log %d records all parse, summary json ok, Play->PLAYING %.2f s, %d RelTime edges (%.4f s apart), Stop->close %.3f s, URI scheme and DIDL class as requested" % (
        len(recs), s["play_to_playing_s"], rt["n_edges"], rt["interval_mean_s"], s["stop_to_close_s"])


def t_play_variants() -> str:
    """http scheme + musicTrack class; frozen / not-implemented RelTime; Pause / resume."""
    from .mock import MockRenderer
    from .cmd_play import run_play
    notes = []
    for label, argv_extra, mock_kw in (("http+track", ["--scheme", "http", "--class", "track"], {}),
                                      ("RelTime frozen", [], {"reltime_mode": "frozen"}),
                                      ("RelTime NOT_IMPLEMENTED", [], {"reltime_mode": "not_implemented"})):
        r = run_play_on_mock("wav", argv_extra, mock_kw, duration=4)
        s = json.loads((r["run"] / "play_summary.json").read_text())
        m = r["mock"]
        if label == "http+track":
            check(m.uri.startswith("http://") and m.last_didl_class == "object.item.audioItem.musicTrack", "%r %r" % (m.uri, m.last_didl_class))
        else:
            check(s["reltime"]["n_edges"] == 0 and s["reltime"]["distinct_values"] <= 1, "%s: %r" % (label, s["reltime"]))
        notes.append(label)
    r = run_play_on_mock("wav", ["--pause-at", "3", "--pause-for", "2"], {}, duration=8)
    s = json.loads((r["run"] / "play_summary.json").read_text())
    pz = s.get("pause")
    check(pz and pz["state_during_pause"] == "PAUSED_PLAYBACK" and pz["conn_open_during_pause"] and pz["resume_to_playing_s"] is not None and pz["resume_to_playing_s"] < 3,
          "pause result %r" % pz)
    check(pz["rel_during_pause"] in ("0:00:02", "0:00:03"), "RelTime should freeze during the pause: %r" % pz["rel_during_pause"])
    notes.append("Pause/resume (connection stays open, RelTime frozen %s, PLAYING again %.2f s after Play)" % (pz["rel_during_pause"], pz["resume_to_playing_s"]))
    # failure modes: the speaker accepts Play but never fetches the stream; the speaker drops the stream by itself
    r = run_play_on_mock("wav", ["--start-timeout", "2"], {"never_fetch": True}, duration=3)
    s = json.loads((r["run"] / "play_summary.json").read_text())
    check(s["play_to_playing_s"] is None and s["stop_to_close_s"] is None and not s["connections"], "never_fetch summary %r" % {k: s[k] for k in ("play_to_playing_s", "stop_to_close_s")})
    check("never fetched the stream" in "".join(x.get("msg", "") for x in read_jsonl(r["run"] / "play.jsonl")), "missing 'never fetched' diagnosis")
    r = run_play_on_mock("flac-nolen", [], {"die_after_s": 3.0}, duration=7)
    s = json.loads((r["run"] / "play_summary.json").read_text())
    check(s["stop_to_close_s"] is not None and s["stop_to_close_s"] < -2.0 and s["connections"][0]["closed_by"] == "peer", "dropped stream summary %r" % {k: s[k] for k in ("stop_to_close_s",)})
    check("BEFORE Stop" in "".join(x.get("msg", "") for x in read_jsonl(r["run"] / "play.jsonl")), "missing 'dropped by itself' diagnosis")
    notes.append("speaker never fetching the stream and speaker dropping it after 3 s are both diagnosed")
    return "ok: " + ", ".join(notes)


def t_soap_errors_volume() -> str:
    from .mock import MockRenderer
    from .upnp import SoapClient, SoapError, didl_broadcast, didl_track, build_uri, parse_reltime
    from .cmd_play import run_volume
    m = MockRenderer(port=0)
    mp = m.start()
    c = SoapClient("127.0.0.1", mp)
    m.fail_set_uri_with = ("714", "Illegal MIME-Type")
    try:
        c.set_uri("x-rincon-mp3radio://127.0.0.1:1/x", didl_broadcast("t"))
        raise AssertionError("expected SoapError")
    except SoapError as e:
        check(e.code == "714" and "Illegal" in e.desc, "SoapError fields %r %r" % (e.code, e.desc))
    m.fail_set_uri_with = None
    try:
        c.set_uri("http://127.0.0.1:1/x", "<not xml")
        raise AssertionError("expected SoapError for bad DIDL")
    except SoapError as e:
        check(e.code == "714", "bad DIDL code %r" % e.code)
    c.set_uri("http://x/y.wav", didl_track("t & <b>", "audio/wav", "http://x/y.wav?a=1&b=2"))
    check(m.last_didl_class == "object.item.audioItem.musicTrack", "track DIDL")
    with quiet() as buf:
        run_volume(parse_args(["volume", "--ip", "127.0.0.1", "--soap-port", str(mp), "--set", "33"]))
        run_volume(parse_args(["volume", "--ip", "127.0.0.1", "--soap-port", str(mp), "--get"]))
    check(m.volume == 33 and "volume 33" in buf.getvalue(), "volume cli: %r" % buf.getvalue())
    check(build_uri("http://h:1/p?x=1", "x-rincon-mp3radio") == "x-rincon-mp3radio://h:1/p?x=1" and build_uri("http://h:1/p", "http") == "http://h:1/p", "build_uri")
    check(parse_reltime("0:01:05") == 65 and parse_reltime("1:00:00.5") == 3600.5 and parse_reltime("NOT_IMPLEMENTED") is None and parse_reltime("") is None, "parse_reltime")
    m.stop()
    return "UPnP errors parsed (714), bad DIDL rejected, volume get/set via CLI, URI scheme rewrite, RelTime parsing"


# ------------------------------------------------------------------ websocket

def t_ws() -> str:
    from .mock import MockRenderer
    from .mockws import MockSonosWs
    from .cmd_ws import run_ws
    m = MockRenderer(port=0)
    mp = m.start()
    w = MockSonosWs(m, CTX["tmp"] / "wscert")
    wp = w.start()
    args = parse_args(["ws", "--ip", "127.0.0.1", "--ws-port", str(wp), "--soap-port", str(mp), "--fmt", "mp3", "--scheme", "http",
                       "--prebuffer", "4", "--duration", "7", "--port", "0", "--also-upnp-poll"])
    args.cache_dir = CTX["tmp"] / "cache"
    with quiet() as buf:
        rc = run_ws(args)
    w.stop()
    m.stop()
    check(rc == 0, "ws rc %s\n%s" % (rc, buf.getvalue()[-1500:]))
    seen = w.seen
    check(seen["subprotocol"] == "v1.api.smartspeaker.audio" and seen["api_key_ok"] is True, "handshake: %r" % {k: seen[k] for k in ("subprotocol", "api_key_ok")})
    check(seen["frame_types"] == {"binary"}, "commands must be binary frames (aiosonos send_bytes): %r" % seen["frame_types"])
    names = [c[1] for c in seen["commands"] if c[1] != "getPlaybackStatus"]
    check(names == ["getGroups", "subscribe", "createSession", "loadStreamUrl", "suspend"], "command order %r" % names)
    runs = sorted(common.RESULTS_DIR.glob("*_ws_mp3"))
    s = json.loads((runs[-1] / "ws_summary.json").read_text())
    pos = s["ws_position"]
    check(pos.get("advancing") and abs(pos["slope_s_per_s"] - 1.0) < 0.01 and pos["n"] >= 50, "positionMillis stats %r" % pos)
    check(s["stop_to_close_s"] is not None and s["stop_to_close_s"] < 2, "stop->close")
    recs = read_jsonl(runs[-1] / "ws.jsonl")
    check(any(r["k"] == "wspos" for r in recs) and any(r["k"] == "ws_event" for r in recs), "ws log lacks position / event records")
    n_poll = sum(1 for c in seen["commands"] if c[1] == "getPlaybackStatus")
    return "wss + self-signed cert, subprotocol and X-Sonos-Api-Key accepted, binary [header, body] frames, getGroups > subscribe > createSession > loadStreamUrl > suspend, %d status polls, positionMillis slope %.4f s/s" % (n_poll, pos["slope_s_per_s"])


def t_recinfo_clock() -> str:
    """The recorder-clock mapping recovers a known rate error and offset from noisy read timestamps."""
    from .record import RecInfo
    rng = np.random.default_rng(3)
    fs, blk = 48000, 512
    out = []
    for minutes, rho_ppm in ((15, 23.0), (0.5, 0.0)):
        n_blocks = int(minutes * 60 * fs / blk)
        n = (np.arange(1, n_blocks + 1) * blk).astype(np.float64)
        T0, Lmin = 1000.0, 0.018
        t = T0 + n / fs * (1 + rho_ppm * 1e-6) + Lmin + rng.exponential(0.008, n.size)
        info = RecInfo([], fs, int(n[-1]), list(t), list(n), T0)
        check(abs(info.t_origin - (T0 + Lmin)) < 0.004, "t_origin %.4f vs %.4f" % (info.t_origin, T0 + Lmin))
        if minutes >= 2:
            check(abs(info.rate_ppm - rho_ppm) < 3.0, "rate %.2f ppm vs %.1f (%s)" % (info.rate_ppm, rho_ppm, info.clock_fit))
            out.append("%.0f min: rate %+.2f ppm (true %+.0f), offset error %.2f ms" % (minutes, info.rate_ppm, rho_ppm, (info.t_origin - T0 - Lmin) * 1000))
        else:
            check(info.rate_ppm == 0.0 and "offset only" in info.clock_fit, "short recordings must not fit a rate")
            out.append("%.1f min: offset only, error %.2f ms" % (minutes, (info.t_origin - T0 - Lmin) * 1000))
    return "; ".join(out)


def t_sox_recorder_stub() -> str:
    """The real SoxRecorder code path with a stub `rec` on PATH (no microphone, no audio device)."""
    from .record import SoxRecorder
    d = CTX["tmp"] / "fakerec"
    d.mkdir(exist_ok=True)
    stub = d / "rec"
    stub.write_text("#!/usr/bin/env python3\n"
                    "import sys, time, os, struct, math, signal\n"
                    "open(os.environ['FAKE_REC_LOG'], 'w').write(' '.join(sys.argv[1:]) + '|' + os.environ.get('AUDIODEV', ''))\n"
                    "stop = []\n"
                    "signal.signal(signal.SIGINT, lambda *a: stop.append(1))\n"
                    "fs, n, t0 = 48000, 0, time.monotonic()\n"
                    "while not stop:\n"
                    "    data = b''.join(struct.pack('<h', int(300 * math.sin(2 * math.pi * 1000 * (n + i) / fs))) for i in range(512))\n"
                    "    n += 512\n"
                    "    dt = t0 + n / fs - time.monotonic()\n"
                    "    if dt > 0: time.sleep(dt)\n"
                    "    sys.stdout.buffer.write(data); sys.stdout.buffer.flush()\n")
    stub.chmod(0o755)
    old_path = os.environ.get("PATH", "")
    os.environ["PATH"] = str(d) + os.pathsep + old_path
    os.environ["FAKE_REC_LOG"] = str(d / "argv.txt")
    try:
        rec = SoxRecorder(d / "out", "chunks", fs=48000, chunk_seconds=1.0, device="Fake Mic")
        rec.start()
        time.sleep(3.3)
        x, t_first = rec.recent(1.0)
        info = rec.stop()
    finally:
        os.environ["PATH"] = old_path
    argv, dev = (d / "argv.txt").read_text().split("|")
    check(argv == "-q -D --buffer 1024 -r 48000 -c 1 -b 16 -e signed-integer -t raw -" and dev == "Fake Mic", "rec arguments %r / AUDIODEV %r" % (argv, dev))
    check(len(info.paths) >= 3 and all(p.exists() for p in info.paths), "chunk files %r" % info.paths)
    infos = [wavio.read_info(str(p)) for p in info.paths]
    check(all(i.fs == 48000 and i.channels == 1 and i.bits == 16 for i in infos), "wav format")
    check(all(i.frames == 48000 for i in infos[:-1]), "chunks must be exactly 1 s: %r" % [i.frames for i in infos])
    check(abs(sum(i.frames for i in infos) - info.n_samples) <= 1, "sample count vs chunks")
    tl = an.Timeline(info.paths)
    y = tl.read(0, tl.total)
    check(float(np.abs(y).max()) > 0.005, "recorded audio is silent")
    check(x.size == 48000 and abs(t_first - (info.n_samples - 48000) / 48000.0) < 0.5, "recent(): %d samples from %.2f" % (x.size, t_first))
    check(abs(info.rate_ppm) < 500 and abs((info.t_origin - info.t_call) - 0.05) < 0.5, "clock mapping origin")
    return "argv as designed (-q -D --buffer 1024 -r 48000 -c 1 -b 16 -e signed-integer -t raw -), AUDIODEV honoured, %d exact 1 s chunks, recent() ring buffer, SIGINT stops it cleanly" % (len(info.paths) - 1)


# ------------------------------------------------------------------ measure / drift end to end

def make_scene_recorder(mock):
    from .record import SynthRecorder
    scene = synth.MockScene(mock)

    def make(out_dir, name, chunk_seconds=None):
        return SynthRecorder(out_dir, name, fs=48000, chunk_seconds=chunk_seconds, render=scene.render)
    return make


def t_measure_e2e() -> str:
    from .mock import MockRenderer
    from .mockws import MockSonosWs
    from .cmd_measure import run_measure
    D = CTX.get("delays", {}).get("mp3", {}).get("used_s", 0.013061)
    m = MockRenderer(port=0, codec_delay_s=D)
    mp = m.start()
    w = MockSonosWs(m, CTX["tmp"] / "wscert2")
    wp = w.start()
    out = CTX["tmp"] / "measure"
    args = parse_args(["measure", "--ip", "127.0.0.1", "--soap-port", str(mp), "--fmt", "mp3", "--trials", "2", "--play-seconds", "9", "--lead", "1",
                       "--tail", "2.5", "--gap", "1", "--port", "0", "--out", str(out), "--ws", "--ws-port", str(wp), "--prebuffer", "12"])
    args.cache_dir = CTX["tmp"] / "cache"
    with quiet() as buf:
        rc = run_measure(args, make_recorder=make_scene_recorder(m))
    w.stop()
    m.stop()
    check(rc == 0, "measure rc %s\n%s" % (rc, buf.getvalue()[-2000:]))
    md = (out / "summary.md").read_text()
    check("| trial |" in md and "## Across trials" in md and "play -> sound" in md, "summary.md structure")
    trials = json.loads((out / "trials.json").read_text())
    check(len(trials) == 2 and all(t["ok"] for t in trials), "trials %r" % [t.get("why") for t in trials])
    for r in read_jsonl(out / "measure.jsonl"):
        pass                                             # every line parses
    for t, truth in zip(trials, m.truth):
        exp_p2s = truth["t_audible_start"] - truth["t_play"] + D
        check(abs(t["play_to_sound_s"] - exp_p2s) < 0.045, "play->sound %.3f vs truth %.3f" % (t["play_to_sound_s"], exp_p2s))
        exp_s2s = truth["t_audible_end"] - truth["t_stop"]
        check(t["stop_to_silence_s"] is not None and abs(t["stop_to_silence_s"] - exp_s2s) < 0.045, "stop->silence %r vs truth %.3f" % (t["stop_to_silence_s"], exp_s2s))
        check(t["clicks_found"] == t["clicks_expected"] >= 8 and not t["gaps"] and not t["phase_jumps"] and t["n_segments"] == 1, "clean trial expected: %r" % {k: t[k] for k in ("clicks_found", "clicks_expected", "gaps", "n_segments")})
        check(t["reltime"]["n"] >= 7 and abs(t["reltime"]["median_s"]) < 0.08, "RelTime offset %r" % t["reltime"])
        check(t["ws"]["n"] >= 40 and t["ws"]["advancing"] and abs(t["ws"]["median_s"]) < 0.2, "ws offset %r" % t["ws"])
        check(t["readpace"]["saturated"] and 2.5 < t["readpace"]["buffer_s"] < 20, "read pacing %r" % t["readpace"])
        check(abs(t["codec_delay_s"] - D) < 1e-6, "codec delay used")
    bias = [t["play_to_sound_s"] - (tr["t_audible_start"] - tr["t_play"] + D) for t, tr in zip(trials, m.truth)]
    return "2 trials on the mock + synthetic mic: play->sound error vs truth %+.0f/%+.0f ms (recorder latency bias), stop->silence ok, RelTime / websocket / read-pacing offsets computed, summary.md + trials.json + jsonl written" % (bias[0] * 1000, bias[1] * 1000)


def t_drift_e2e() -> str:
    from .mock import MockRenderer
    from .cmd_measure import run_drift
    ppm = 250.0
    m = MockRenderer(port=0, rate_ppm=ppm)
    mp = m.start()
    out = CTX["tmp"] / "drift"
    args = parse_args(["drift", "--ip", "127.0.0.1", "--soap-port", str(mp), "--fmt", "wav", "--minutes", "0.8", "--chunk-seconds", "12", "--lead", "1.5",
                       "--tail", "2.5", "--port", "0", "--out", str(out), "--prebuffer", "12"])
    args.cache_dir = CTX["tmp"] / "cache"
    with quiet() as buf:
        rc = run_drift(args, make_recorder=make_scene_recorder(m))
    m.stop()
    check(rc == 0, "drift rc %s\n%s" % (rc, buf.getvalue()[-2000:]))
    chunks = sorted(out.glob("drift_*.wav"))
    check(len(chunks) >= 4, "expected several chunks, got %d" % len(chunks))
    rep = json.loads((out / "drift.json").read_text())
    check(rep["ok"] and rep["clicks"] >= 44 and rep["n_missing"] == 0 and len(rep["jumps"]) == 0, "drift report %r" % {k: rep.get(k) for k in ("ok", "clicks", "n_missing", "jumps")})
    check(abs(rep["drift_vs_mac_clock_ppm"] - ppm) < 25.0, "drift %.1f ppm vs injected %.0f" % (rep["drift_vs_mac_clock_ppm"], ppm))
    check(abs(rep["total_offset_change_ms"] - ppm * 1e-6 * rep["clicks"] * 1000) < 2.0, "total offset change %.2f ms" % rep["total_offset_change_ms"])
    md = (out / "drift_summary.md").read_text()
    check("positive ppm = the speaker plays slower" in md and "acoustic clicks" in md and "UPnP RelTime" in md, "drift_summary.md")
    with open(out / "drift_offsets.csv") as f:
        rows = f.read().splitlines()
    check(len(rows) == rep["clicks"] + 1, "csv rows")
    return "%d chunks of 12 s, %d clicks, injected %+.0f ppm recovered as %+.1f ppm (uncertainty %.0f ppm for a %.0f s run), csv + md + json written" % (
        len(chunks), rep["clicks"], ppm, rep["drift_vs_mac_clock_ppm"], rep["recorder_clock_uncertainty_ppm"], rep["duration_s"])


# ------------------------------------------------------------------ discovery, listen, wol

def t_discover() -> str:
    from .mock import MockRenderer
    from .cmd_discover import run_discover
    m = MockRenderer(port=0)
    mp = m.start()
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.bind(("127.0.0.1", 0))
    sp = s.getsockname()[1]
    seen: List[bytes] = []
    stop = threading.Event()

    def responder() -> None:
        s.settimeout(0.2)
        while not stop.is_set():
            try:
                data, addr = s.recvfrom(4096)
            except socket.timeout:
                continue
            seen.append(data)
            if b"M-SEARCH" in data and b"ZonePlayer:1" in data:
                reply = ("HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age = 1800\r\nEXT:\r\nLOCATION: http://127.0.0.1:%d/xml/device_description.xml\r\n"
                         "SERVER: Linux UPnP/1.0 Sonos/99.9-00000 (ZPS99)\r\nST: urn:schemas-upnp-org:device:ZonePlayer:1\r\n"
                         "USN: uuid:RINCON_MOCK000000001400::urn:schemas-upnp-org:device:ZonePlayer:1\r\nX-RINCON-HOUSEHOLD: Sonos_MockHousehold0001\r\n"
                         "X-RINCON-BOOTSEQ: 42\r\n\r\n" % mp).encode()
                s.sendto(reply, addr)

    th = threading.Thread(target=responder, daemon=True)
    th.start()
    args = parse_args(["discover", "--seconds", "1.5", "--no-mdns", "--ssdp-target", "127.0.0.1:%d" % sp, "--local-ip", "127.0.0.1"])
    with quiet() as buf:
        rc = run_discover(args)
    stop.set()
    th.join()
    s.close()
    m.stop()
    out = buf.getvalue()
    check(rc == 0, "discover rc %s\n%s" % (rc, out))
    msg = seen[0].decode()
    check(msg.startswith("M-SEARCH * HTTP/1.1\r\n") and 'MAN: "ssdp:discover"' in msg and "MX: 1" in msg and "ST: urn:schemas-upnp-org:device:ZonePlayer:1" in msg and "HOST: 239.255.255.250:1900" in msg, "M-SEARCH format: %r" % msg)
    for needle in ("Mock Sonos Play", "S99", "Kitchen (mock)", "99.9-00000", "00-00-00-00-00-00:0", "00:00:00:00:00:00", "RINCON_MOCK000000001400",
                   "AVTransport", "RenderingControl", "Sonos_MockHousehold0001", "ZonePlayer"):
        check(needle in out, "discover output lacks %r:\n%s" % (needle, out))
    return "M-SEARCH (ZonePlayer:1, MX 1) correct; device model / number / display+build version / room / serial / MAC / UDN / household / services printed"


def t_listen() -> str:
    from .mock import MockRenderer
    from .cmd_discover import run_listen
    m = MockRenderer(port=0)
    mp = m.start()
    ip = common.local_ip_for(None)
    runs_before = set(common.RESULTS_DIR.glob("*_listen"))
    args = parse_args(["listen", "--ip", "127.0.0.1", "--device-port", str(mp), "--interval", "0.5", "--minutes", "0.17", "--local-ip", ip, "--verbose"])
    done = threading.Event()

    def runner() -> None:
        with quiet():
            run_listen(args)
        done.set()
    th = threading.Thread(target=runner, daemon=True)
    th.start()
    time.sleep(1.3)
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)       # unicast to ourselves: nothing is put on the LAN

    def notify(nts: str, nt: str = "urn:schemas-upnp-org:device:ZonePlayer:1") -> None:
        msg = ("NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nCACHE-CONTROL: max-age = 1800\r\nLOCATION: http://127.0.0.1:%d/xml/device_description.xml\r\n"
               "NT: %s\r\nNTS: %s\r\nSERVER: Linux UPnP/1.0 Sonos/99.9-00000 (ZPS99)\r\nUSN: uuid:RINCON_MOCK000000001400::%s\r\n"
               "X-RINCON-BOOTSEQ: 42\r\n\r\n" % (mp, nt, nts, nt)).encode()
        s.sendto(msg, (ip, 1900))
    notify("ssdp:alive")
    # a non-Sonos announcement must be ignored
    s.sendto(b"NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nNT: upnp:rootdevice\r\nNTS: ssdp:alive\r\nSERVER: Linux/3.0 UPnP/1.0 MiniUPnPd/2.1\r\nUSN: uuid:igd::upnp:rootdevice\r\n\r\n", (ip, 1900))
    time.sleep(1.2)
    m.stop()                                   # device "falls asleep": HTTP polls fail
    time.sleep(1.5)
    notify("ssdp:byebye")
    time.sleep(0.4)
    done.wait(15)
    s.close()
    runs = sorted(set(common.RESULTS_DIR.glob("*_listen")) - runs_before)
    check(runs, "no listen results")
    recs = read_jsonl(runs[-1] / "listen.jsonl")
    nots = [r for r in recs if r["k"] == "notify" and "RINCON_MOCK" in (r.get("usn") or "")]
    check([r["nts"] for r in nots] == ["ssdp:alive", "ssdp:byebye"], "NOTIFY records %r (is another process using UDP 1900?)" % [r["nts"] for r in nots])
    check(not any("igd" in (r.get("usn") or "") for r in recs if r["k"] == "notify"), "non-Sonos NOTIFY was not ignored")
    check(all("t" in r and "w" in r and r["usn"].startswith("uuid:RINCON") for r in nots), "NOTIFY fields")
    reach = [r for r in recs if r["k"] == "reach" and r["ip"] == "127.0.0.1"]
    check([r["state"] for r in reach][:2] == ["UP", "DOWN"], "reachability transitions %r" % [(r["state"], r.get("after_s")) for r in reach])
    check(reach[1]["after_s"] > 0.5, "transition timing")
    polls = [r for r in recs if r["k"] == "poll"]
    check(len(polls) >= 4 and all("ping" in p and "rtt" in p for p in polls), "poll records")
    return "NOTIFY alive/byebye logged with timestamps (non-Sonos NOTIFY ignored), description poll every 0.5 s logged UP -> DOWN transition after %.1f s" % reach[1]["after_s"]


def t_wol() -> str:
    from .cmd_discover import magic_packet, run_wol
    pkt = magic_packet("54:2a:1b:12:34:56")
    check(len(pkt) == 102 and pkt[:6] == b"\xff" * 6 and pkt[6:] == bytes.fromhex("542a1b123456") * 16, "magic packet layout")
    check(magic_packet("54-2A-1B-12-34-56") == pkt and magic_packet("542a1b123456") == pkt, "mac formats")
    try:
        magic_packet("12:34")
        raise AssertionError("bad mac accepted")
    except ValueError:
        pass
    r = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    r.bind(("127.0.0.1", 0))
    r.settimeout(2)
    port = r.getsockname()[1]
    with quiet() as buf:
        run_wol(parse_args(["wol", "--mac", "54:2a:1b:12:34:56", "--only-bcast", "--bcast", "127.0.0.1:%d" % port]))
    got = r.recv(4096)
    r.close()
    check(got == pkt, "packet on the wire differs")
    with quiet() as buf:                                   # default destinations: print only, never touch the LAN in a test
        run_wol(parse_args(["wol", "--mac", "54:2a:1b:12:34:56", "--dry-run"]))
    out = buf.getvalue()
    check("255.255.255.255:9" in out, "limited broadcast target missing:\n" + out)
    ifc = common.pick_iface(None)
    if ifc and ifc.broadcast:
        check("%s:9" % ifc.broadcast in out, "subnet broadcast %s missing:\n%s" % (ifc.broadcast, out))
    return "102-byte magic packet (6x FF + 16x MAC) arrives intact over UDP; default destinations 255.255.255.255:9 and %s:9" % (ifc.broadcast if ifc and ifc.broadcast else "subnet broadcast")


def t_analyze_cli() -> str:
    from .cmd_measure import run_analyze
    d = CTX["tmp"] / "an"
    d.mkdir(exist_ok=True)
    x = synth.synth_recording(fs=48000, duration=25, delay_s=2.0, gain=0.05, noise_dbfs=-55, dropouts=[(10.2, 0.2)], stop_at=22.0)
    synth.write_wav_f32(str(d / "x.wav"), x, 48000)
    with quiet() as buf:
        rc = run_analyze(parse_args(["analyze", str(d / "x.wav"), "--json", str(d / "x.json")]))
    out = buf.getvalue()
    import re
    check(rc == 0 and re.search(r"click 0 heard at t = 2\.000\d", out) and re.search(r"gap at t = 10\.[12]\d\d s", out)
          and re.search(r"tone ends at t = (21\.99|22\.00)\d", out), "analyze output:\n" + out)
    json.loads((d / "x.json").read_text())
    return "analyze prints t0, slope, jitter, missing clicks, tone gap, tone end for an arbitrary wav"


def t_py39() -> str:
    """No syntax newer than 3.9 in any module (match statements, X | Y annotations evaluated at runtime)."""
    import ast
    bad = []
    here = Path(__file__).resolve().parent
    for p in list(here.glob("*.py")) + [here.parent / "sonos_probe.py"]:
        tree = ast.parse(p.read_text(), feature_version=(3, 9))
        for node in ast.walk(tree):
            if node.__class__.__name__ == "Match":
                bad.append("%s: match statement" % p.name)
    check(not bad, "; ".join(bad))
    check(sys.version_info[:2] >= (3, 9), "python")
    return "all modules parse as Python 3.9 (running on %s)" % sys.version.split()[0]


# ------------------------------------------------------------------ runner

def run_selftest(args) -> int:
    common.python_ok()
    base = common.RESULTS_DIR / ("selftest_" + time.strftime("%Y%m%d-%H%M%S"))
    base.mkdir(parents=True, exist_ok=True)
    CTX["tmp"] = base / "work"
    CTX["tmp"].mkdir()
    saved_results = common.RESULTS_DIR
    common.RESULTS_DIR = base / "runs"
    common.RESULTS_DIR.mkdir()
    saved_tiers = codecs.AAC_TIERS_S
    codecs.AAC_TIERS_S = (120, 600, 4200, 8400, 15000)       # a 2-minute AAC tier keeps the tests quick
    tests: List[Tuple[str, Callable[[], str], bool]] = [
        ("py39_syntax", t_py39, False),
        ("signal", t_signal, False),
        ("wav_header", t_wav_header, False),
        ("flac_bit_exact", t_flac, False),
        ("mp3_aac_codecs", t_codecs, False),
        ("detector", t_detector, False),
        ("server_formats", t_server_formats, False),
        ("server_blocking", t_server_blocking, True),
        ("server_http", t_server_http, False),
        ("soap_errors_volume", t_soap_errors_volume, False),
        ("play_wav", lambda: t_play("wav"), False),
        ("play_flac_chunked", lambda: t_play("flac-chunked"), True),
        ("play_flac_nolen", lambda: t_play("flac-nolen"), True),
        ("play_mp3", lambda: t_play("mp3"), True),
        ("play_aac", lambda: t_play("aac"), True),
        ("play_variants", t_play_variants, True),
        ("recorder_clock_fit", t_recinfo_clock, False),
        ("sox_recorder_stub", t_sox_recorder_stub, False),
        ("websocket", t_ws, False),
        ("measure_e2e", t_measure_e2e, True),
        ("drift_e2e", t_drift_e2e, True),
        ("discover", t_discover, False),
        ("listen", t_listen, False),
        ("wol", t_wol, False),
        ("analyze_cli", t_analyze_cli, False),
    ]
    ok = fail = 0
    lines: List[str] = []
    t_all = time.time()
    try:
        for name, fn, slow in tests:
            if slow and args.quick and name not in ("play_mp3",):
                lines.append("SKIP  %-20s (--quick)" % name)
                print(lines[-1], flush=True)
                continue
            t0 = time.time()
            try:
                detail = fn()
                ok += 1
                lines.append("PASS  %-20s %5.1fs  %s" % (name, time.time() - t0, detail))
            except Exception as e:
                fail += 1
                tb = traceback.format_exc().strip().splitlines()
                lines.append("FAIL  %-20s %5.1fs  %s: %s\n        %s" % (name, time.time() - t0, type(e).__name__, str(e).splitlines()[0] if str(e) else "", "\n        ".join(tb[-6:])))
            print(lines[-1], flush=True)
    finally:
        common.RESULTS_DIR = saved_results
        codecs.AAC_TIERS_S = saved_tiers
        if not args.keep:
            shutil.rmtree(base, ignore_errors=True)
    print("\nSELFTEST SUMMARY: %d passed, %d failed, %d skipped in %.0f s" % (ok, fail, len(tests) - ok - fail, time.time() - t_all))
    return 1 if fail else 0
