#!/usr/bin/env python3
"""How far the Ambilight is from the sound: a film of the TV (and the wall) while the TV stream has the calibration clicks
(`/tv/clicks?seconds=60`) and the lights flash on the same capture frames (`/lights/test?pattern=clicks&seconds=60`).

Film from the seat (the sound takes about 3 ms a metre, and that is part of what the eye and ear get there), at 60 frames a
second, for 30 s or more, with the TV and the wall behind it in the picture.

    python3 lights_offset.py clip.mov [--region x,y,w,h]   # region in % of the picture (default: all of it)

Prints the delay of every flash after its click and their mean: positive means the light comes after the sound. The setting
`lights_offset_ms` draws the lights that much ahead, so set it to about mean + 20 to land 20 ms early (early light up to 125 ms
goes unnoticed, late light from 45 ms does). Needs ffmpeg and ffprobe.
"""
import argparse
import array
import json
import statistics
import subprocess
import sys


def audio(path, rate=48000):
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", path, "-ac", "1", "-ar", str(rate), "-f", "s16le", "-"], capture_output=True, check=True).stdout
    a = array.array("h")
    a.frombytes(raw)
    return a, rate


def clicks(samples, rate):
    """The starts of the clicks (s): 1 ms energy that jumps far above the floor, at least 0.5 s apart."""
    win = rate // 1000
    energy = []
    for i in range(0, len(samples) - win, win):
        e = 0
        for v in samples[i:i + win]:
            e += v * v
        energy.append(e / win)
    floor = statistics.median(energy) + 1
    out = []
    last = -1e9
    for i, e in enumerate(energy):
        t = i / 1000.0
        if e > 30 * floor and t - last > 0.5:
            out.append(t)
            last = t
    return out


def video(path, region):
    probe = json.loads(subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v", "-show_entries", "frame=pts_time", "-of", "json", path], capture_output=True, check=True).stdout)
    times = [float(f["pts_time"]) for f in probe["frames"] if "pts_time" in f]
    x, y, w, h = region
    crop = f"crop=iw*{w / 100}:ih*{h / 100}:iw*{x / 100}:ih*{y / 100},scale=32:18,format=gray"
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", path, "-vf", crop, "-f", "rawvideo", "-"], capture_output=True, check=True).stdout
    n = 32 * 18
    levels = [sum(raw[k:k + n]) / n for k in range(0, len(raw) - n + 1, n)]
    return times[:len(levels)], levels


def flashes(times, levels):
    """The starts of the flashes (s), between the frames where the level crosses halfway from dark to bright."""
    lo, hi = sorted(levels)[len(levels) // 10], sorted(levels)[-len(levels) // 10]
    mid = (lo + hi) / 2
    out = []
    for i in range(1, len(levels)):
        if levels[i - 1] < mid <= levels[i]:
            f = (mid - levels[i - 1]) / max(1e-9, levels[i] - levels[i - 1])
            out.append(times[i - 1] + f * (times[i] - times[i - 1]))
    return out, hi - lo


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("clip")
    p.add_argument("--region", default="0,0,100,100", help="x,y,w,h in %% of the picture")
    args = p.parse_args()
    region = [float(v) for v in args.region.split(",")]
    samples, rate = audio(args.clip)
    cs = clicks(samples, rate)
    ts, levels = video(args.clip, region)
    fs, swing = flashes(ts, levels)
    print(f"{len(cs)} clicks, {len(fs)} flashes (brightness swing {swing:.0f} of 255)")
    delays = []
    for f in fs:
        c = min(cs, key=lambda c: abs(c - f)) if cs else None
        if c is not None and abs(f - c) < 0.4:
            delays.append((f - c) * 1000)
    if len(delays) < 5:
        print("too few flashes next to clicks: is the TV in the picture, and are the clicks and the flash pattern both on?")
        sys.exit(1)
    print("light after sound (ms):", " ".join(f"{d:.0f}" for d in delays))
    m, sd = statistics.mean(delays), statistics.pstdev(delays)
    print(f"mean {m:.0f} ms, sd {sd:.0f} ms, n {len(delays)} (one frame of the film is {1000 * (ts[1] - ts[0]):.0f} ms)")
    print(f"→ lights_offset_ms ≈ {round(m + 20)} (to land 20 ms before the sound)")


if __name__ == "__main__":
    main()
