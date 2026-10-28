#!/usr/bin/env python3
"""tvclicks: the offset of the kitchen against the TV in Synced mode, from the app's click track and the Mac's microphone.

Starts /tv/clicks?seconds=N on the box (Synced must be live, and something must play in the Cast player for the kitchen to stream),
records the microphone with sox `rec` meanwhile, stops the clicks, and finds the TV's clicks (2 kHz, every tenth at 4 kHz) and the
kitchen's (3 kHz, every tenth at 6 kHz). A click counts only in the band where it is the strongest at that moment: the edge of a click
leaks into the other band, and on 28.10. that leak made the kitchen look 1 ms from the TV when the TV was 4.48 s behind it. The tenth
clicks are numbered by the capture frame in both rooms, so they give the whole seconds; the clicks between them give the fine part.
offset = t_kitchen - t_tv: positive means the kitchen is heard later, negative that it is ahead.

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


def own(bands, f, others, fs):
    """The clicks of band f: its onsets where f is at least twice as strong as each of the other bands (a leak is weaker than its click)."""
    env = bands[f]
    out = []
    for t in onsets(env, fs):
        i = min(len(env) - 1, int(t * fs) + int(CLICK_S * fs / 2))
        if all(env[i] > 2.0 * bands[o][i] for o in others):
            out.append(t)
    return out


def analyze(x, fs):
    bands = {f: envelope(x, fs, f) for f in (TV_HZ, KITCHEN_HZ, 2 * TV_HZ, 2 * KITCHEN_HZ)}
    tv = own(bands, TV_HZ, [KITCHEN_HZ], fs)
    kit = own(bands, KITCHEN_HZ, [TV_HZ], fs)
    tv_mark = own(bands, 2 * TV_HZ, [2 * KITCHEN_HZ, TV_HZ], fs)
    kit_mark = own(bands, 2 * KITCHEN_HZ, [2 * TV_HZ, KITCHEN_HZ], fs)
    res = {"tv_clicks": len(tv), "kitchen_clicks": len(kit), "tv_marks": len(tv_mark), "kitchen_marks": len(kit_mark)}
    # the whole seconds: a tenth click of the kitchen and the TV's nearest one within 5 s are the same capture frame
    coarse = [k - min(tv_mark, key=lambda u: abs(u - k)) for k in kit_mark if tv_mark and abs(min(tv_mark, key=lambda u: abs(u - k)) - k) < 5.0]
    shift = float(np.median(coarse)) if coarse else 0.0
    if not coarse:
        res["warning"] = "no tenth clicks heard in both rooms: the offset is known only up to whole seconds"
    pairs = []
    for t in tv:
        want = t + shift
        if kit:
            k = min(kit, key=lambda u: abs(u - want))
            if abs(k - want) <= NEAR_S:
                pairs.append((t, (k - t) * 1000.0))
    off = np.array([p[1] for p in pairs])
    res["pairs"] = len(pairs)
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
    """Synthetic click tracks in noise, with the tenth clicks at twice the pitch: the kitchen 46 ms late, 30 ms early and 20 dB down, and
    4.48 s ahead of the TV (28.10.: the leak of each click into the other band read that as 1 ms)."""
    fs, rng, ok = RATE, np.random.default_rng(1), True
    t = np.arange(int(CLICK_S * fs)) / fs
    for lag, kamp in ((0.046, 0.1), (-0.030, 0.03), (-4.48, 0.3)):
        x = rng.normal(0, 0.01, fs * 40)
        for k in range(0, 40):
            for f, at, amp in ((TV_HZ, 6.0 + k, 0.3), (KITCHEN_HZ, 6.0 + k + lag, kamp)):
                i = int(at * fs)
                if 0 <= i < len(x) - len(t):
                    x[i:i + len(t)] += amp * np.sin(2 * np.pi * (2 * f if k % 10 == 0 else f) * t)
        res, _ = analyze(x, fs)
        good = res["pairs"] >= 20 and abs(res.get("median_ms", 1e9) - lag * 1000.0) <= 1.0
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
