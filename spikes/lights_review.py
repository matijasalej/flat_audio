#!/usr/bin/env python3
"""The review of the mood lights, from what the box remembers of every song (`/lights/history`):

- the tempo against Deezer's (looked up by title and artist; 0 there means Deezer does not know it): right within 4 %, and right
  up to a double or a half (the lights pulse at half time above 150 BPM anyway);
- the class against his choices on the phone page (less colour, just right, more colour): how often the app's own class was
  the one he wanted, and, once there are 15 songs or more with a choice, the thresholds that would have agreed with him most.

    python3 lights_review.py [--box http://192.168.0.165:8080] [--no-deezer]
"""
import argparse
import itertools
import json
import os
import time
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, "results", "cache", "deezer_bpm.json")


def get(url):
    with urllib.request.urlopen(url, timeout=8) as r:
        return json.loads(r.read())


def deezer_bpm(title, artist, cache):
    key = f"{title}|{artist}"
    if key in cache:
        return cache[key]
    bpm = None
    try:
        q = urllib.parse.quote(f'artist:"{artist}" track:"{title}"')
        found = get(f"https://api.deezer.com/search?q={q}&limit=1").get("data") or []
        if found:
            bpm = get(f"https://api.deezer.com/track/{found[0]['id']}").get("bpm") or None
        time.sleep(0.25)  # Deezer allows about 50 calls in 5 s
    except Exception:
        return None
    cache[key] = bpm
    return bpm


def classify(e, c, t):
    mono_e, mono_c, multi_e, multi_c = t
    if e < mono_e or c < mono_c:
        return "mono"
    if e >= multi_e and c >= multi_c:
        return "multi"
    return "duo"


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--box", default="http://192.168.0.165:8080")
    p.add_argument("--no-deezer", action="store_true")
    args = p.parse_args()
    songs = get(args.box + "/lights/history")
    out = [f"# Mood lights review ({time.strftime('%Y-%m-%d %H:%M')}, {len(songs)} songs)", ""]

    # ---- the tempo
    if not args.no_deezer:
        os.makedirs(os.path.dirname(CACHE), exist_ok=True)
        cache = json.load(open(CACHE)) if os.path.exists(CACHE) else {}
        rows = []
        for s in songs:
            if s.get("bpm") is None or not s.get("title") or not s.get("artist"):
                continue
            ref = deezer_bpm(s["title"], s["artist"], cache)
            if ref:
                rows.append((s, ref))
        json.dump(cache, open(CACHE, "w"), indent=0)
        if rows:
            acc1 = sum(1 for s, r in rows if abs(s["bpm"] / r - 1) <= 0.04)
            acc2 = sum(1 for s, r in rows if any(abs(s["bpm"] / (r * m) - 1) <= 0.04 for m in (1, 2, 0.5, 3, 1 / 3)))
            n = len(rows)
            out += ["## Tempo against Deezer", "",
                    f"- {n} songs with a Deezer tempo: right within 4 %: **{acc1}/{n} ({100 * acc1 // n} %)** (target 75 %); "
                    f"up to a double or a half: **{acc2}/{n} ({100 * acc2 // n} %)** (target 90 %).", ""]
            for s, r in rows:
                mark = "" if any(abs(s["bpm"] / (r * m) - 1) <= 0.04 for m in (1, 2, 0.5, 3, 1 / 3)) else "  ← wrong"
                out.append(f"  - {s['title']} / {s['artist']}: box {s['bpm']:.0f}, Deezer {r:.0f}{mark}")
            out.append("")

    # ---- the class against his choices
    rated = [s for s in songs if s.get("override") and s.get("class")]
    out += ["## The class against his choices", ""]
    if not rated:
        out.append("- No choices yet: press Less colour, Just right or More colour on the phone page while songs play.")
    else:
        agree = sum(1 for s in rated if s["override"] == s["class"])
        out.append(f"- {len(rated)} songs with his choice: the app's own class was his in **{agree}/{len(rated)} ({100 * agree // len(rated)} %)** (target 80 %).")
        for s in rated:
            out.append(f"  - {s.get('title')} / {s.get('artist')}: app {s['class']}, his {s['override']} "
                       f"(energy {s['energy']:.2f}, clarity {s['clarity']:.2f}, mood {s['mood']:.2f})")
        if len(rated) >= 15:
            grid = itertools.product([0.25, 0.3, 0.35, 0.4, 0.45], [0.2, 0.25, 0.3, 0.35], [0.6, 0.65, 0.7, 0.75, 0.8], [0.4, 0.45, 0.5, 0.55, 0.6])
            best = max(grid, key=lambda t: sum(1 for s in rated if classify(s["energy"], s["clarity"], t) == s["override"]))
            hits = sum(1 for s in rated if classify(s["energy"], s["clarity"], best) == s["override"])
            out.append("")
            out.append(f"- The thresholds that would have agreed most ({hits}/{len(rated)}): MONO_ENERGY {best[0]}, MONO_CLARITY {best[1]}, "
                       f"MULTI_ENERGY {best[2]}, MULTI_CLARITY {best[3]} (now 0.35, 0.3, 0.7, 0.5; light/Looks.kt).")
    out.append("")
    text = "\n".join(out)
    d = os.path.join(HERE, "results", time.strftime("%Y%m%d-%H%M%S") + "_lights_review")
    os.makedirs(d, exist_ok=True)
    open(os.path.join(d, "summary.md"), "w").write(text)
    print(text)
    print("written:", d)


if __name__ == "__main__":
    main()
