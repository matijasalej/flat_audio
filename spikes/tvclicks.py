#!/usr/bin/env python3
"""tvclicks: the offset of the kitchen against the TV in Synced mode, from the app's click track and the Mac's microphone.

Starts /tv/clicks?seconds=N on the box (Synced must be live, and something must play in the Cast player for the kitchen to stream),
records the microphone with sox `rec` meanwhile, stops the clicks, and finds the TV's clicks (2 kHz) and the kitchen's (3 kHz); the
markers every tenth second (4 and 6 kHz) are left out, 6 kHz being a harmonic of both. offset = t_kitchen - t_tv for each second, then
its median and spread: positive means the kitchen is heard later. Run it once with the audio-only stream (Listening, tv_audio_only on)
and once with the lyrics video (Screen): the medians should agree within about 15 ms, or the audio-only stream needs its own extra.

usage: python3 spikes/tvclicks.py [--box 192.168.0.165] [--seconds 60] [--out tvclicks.wav]
       python3 spikes/tvclicks.py --analyze tvclicks.wav
       python3 spikes/tvclicks.py --selftest
Needs numpy, and sox for the recording.
"""
import argparse
import subprocess
import sys
import time
import urllib.request
import wave

import numpy as np

RATE = 48000
TV_HZ = 2000.0
KITCHEN_HZ = 3000.0
CLICK_S = 0.005
NEAR_S = 0.5


def envelope(x, fs, f):
    """The level of x at f: demodulated and averaged over the length of a click (5 ms, whose response has its null 1 kHz away)."""
    n = np.arange(len(x))
    z = x * np.exp(-2j * np.pi * f * n / fs)
    w = max(1, int(round(fs * CLICK_S)))
    c = np.concatenate(([0], np.cumsum(z)))
    avg = (c[w:] - c[:-w]) / w
    return np.abs(np.concatenate((np.zeros(w // 2), avg, np.zeros(len(x) - len(avg) - w // 2))))


def onsets(env, fs, snr_db=14.0, gap_s=0.5):
    """The clicks in one band: the peaks above the floor by snr_db, the strongest of any within gap_s of each other (the edge of the
    other room's click leaks into this band, weaker than a click of its own), each timed at the half-height point before its peak."""
    floor = np.median(env) + 1e-12
    mid = env[1:-1]
    idx = np.nonzero((mid > floor * 10 ** (snr_db / 20)) & (mid >= env[:-2]) & (mid > env[2:]))[0] + 1
    gap, taken = int(gap_s * fs), []
    for p in idx[np.argsort(-env[idx])]:
        if all(abs(int(p) - q) >= gap for q in taken):
            taken.append(int(p))
    out = []
    for p in sorted(taken):
        lo = max(0, p - int(0.01 * fs))
        out.append((lo + int(np.argmax(env[lo:p + 1] >= env[p] / 2))) / fs)
    return out


def analyze(x, fs):
    tv = onsets(envelope(x, fs, TV_HZ), fs)
    kit = onsets(envelope(x, fs, KITCHEN_HZ), fs)
    pairs = []
    for t in tv:
        if kit:
            k = min(kit, key=lambda u: abs(u - t))
            if abs(k - t) <= NEAR_S:
                pairs.append((t, (k - t) * 1000.0))
    off = np.array([p[1] for p in pairs])
    res = {"tv_clicks": len(tv), "kitchen_clicks": len(kit), "pairs": len(pairs)}
    if len(off):
        res.update(median_ms=round(float(np.median(off)), 1), p5_ms=round(float(np.percentile(off, 5)), 1), p95_ms=round(float(np.percentile(off, 95)), 1))
    return res, pairs


def read_wav(path):
    with wave.open(path, "rb") as w:
        fs, ch, sw = w.getframerate(), w.getnchannels(), w.getsampwidth()
        raw = w.readframes(w.getnframes())
    x = np.frombuffer(raw, dtype={2: np.int16, 4: np.int32}[sw]).astype(np.float64)
    return x.reshape(-1, ch).mean(axis=1), fs


def get(url):
    with urllib.request.urlopen(url, timeout=8) as r:
        return r.read().decode("utf-8", "replace").strip()


def run(box, seconds, out):
    rec = subprocess.Popen(["rec", "-q", "-c", "1", "-r", str(RATE), "-b", "16", out, "trim", "0", str(seconds + 6)])
    try:
        time.sleep(1.5)
        print("clicks:", get("http://%s:8080/tv/clicks?seconds=%d" % (box, seconds))[:300])
        time.sleep(seconds + 2)
        print("stop:", get("http://%s:8080/tv/clicks/stop" % box)[:200])
    finally:
        rec.wait(timeout=seconds + 30)
    return out


def selftest():
    """Synthetic clicks in noise: the kitchen 46 ms late and a third as loud, then 30 ms early and 20 dB down."""
    fs, rng, ok = RATE, np.random.default_rng(1), True
    t = np.arange(int(CLICK_S * fs)) / fs
    for lag, kamp in ((0.046, 0.1), (-0.030, 0.03)):
        x = rng.normal(0, 0.01, fs * 20)
        for k in range(1, 19):
            for f, at, amp in ((TV_HZ, k + 0.0, 0.3), (KITCHEN_HZ, k + lag, kamp)):
                i = int(at * fs)
                x[i:i + len(t)] += amp * np.sin(2 * np.pi * f * t)
        res, _ = analyze(x, fs)
        good = res["pairs"] >= 17 and abs(res["median_ms"] - lag * 1000.0) <= 1.0
        ok = ok and good
        print(res, "ok" if good else "FAILED")
    print("selftest", "ok" if ok else "FAILED")
    return 0 if ok else 1


def main():
    a = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    a.add_argument("--box", default="192.168.0.165")
    a.add_argument("--seconds", type=int, default=60)
    a.add_argument("--out", default="tvclicks.wav")
    a.add_argument("--analyze", help="analyze this recording instead of making one")
    a.add_argument("--selftest", action="store_true")
    args = a.parse_args()
    if args.selftest:
        return selftest()
    path = args.analyze or run(args.box, args.seconds, args.out)
    x, fs = read_wav(path)
    res, pairs = analyze(x, fs)
    for t, off in pairs:
        print("%7.2f s  kitchen %+7.1f ms" % (t, off))
    print(res)
    return 0


if __name__ == "__main__":
    sys.exit(main())
