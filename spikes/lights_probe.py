#!/usr/bin/env python3
"""The TV test of the mood lights: what the Philips Ambilight does with its own styles while music plays.

Runs on the Mac against the box (the Whole Flat app with the mood lights, Synced mode playing) and the TV (JointSpace on :1925).
It reads the lights back from the TV (GET /6/ambilight/processed, 3 a second at most: the TV's server stalls from about
10 calls a second) and never GETs /6/ambilight/cached (reported to crash the server).

    python3 lights_probe.py s2 [--display ambilight]   # the TV's own styles while music plays: which light up, which react
    python3 lights_probe.py s3                          # style calls at 0.5, 1 and 2 a second: errors, stalls and the TV's player

(The first version also painted test pictures into the TV stream; with the screen off the TV ignores the picture, so that is gone.)

Everything it changes is saved first and put back at the end (also after Ctrl-C): the display, the app's Ambilight style, the
lights' settings, the TV's Ambilight configuration. Results land in results/<time>_lights_<step>/ (JSONL and summary.md).
"""
import argparse
import colorsys
import json
import math
import os
import statistics
import sys
import time
import urllib.parse
import urllib.request

BOX = "http://192.168.0.165:8080"
TV = "http://192.168.0.69:1925/6"
POLL_S = 0.5  # 2 a second: the sync session is chasing freezes of the TV's player, so the load is kept low
WINDOWS = []  # (start, end, what) of every stretch that talks to the TV, printed at the end for the freeze hunt

ZONES = [("left", i) for i in range(4)] + [("top", i) for i in range(8)] + [("right", i) for i in range(4)]
SYNCED_STYLES = ["FOLLOW_VIDEO/GAME", "FOLLOW_VIDEO/STANDARD", "FOLLOW_AUDIO/ENERGY_ADAPTIVE_BRIGHTNESS"]
AUDIO_ALGORITHMS = ["ENERGY_ADAPTIVE_BRIGHTNESS", "ENERGY_ADAPTIVE_COLORS", "VU_METER", "SPECTRUM_ANALYZER",
                    "KNIGHT_RIDER_CLOCKWISE", "KNIGHT_RIDER_ALTERNATING", "RANDOM_PIXEL_FLASH", "STROBE", "PARTY"]


# ---- http ------------------------------------------------------------------------------------------

def get(url, timeout=3.0):
    t0 = time.time()
    with urllib.request.urlopen(url, timeout=timeout) as r:
        body = r.read()
    return json.loads(body) if body else None, time.time() - t0


def post(url, obj, timeout=3.0):
    data = json.dumps(obj).encode() if not isinstance(obj, (bytes, str)) else (obj.encode() if isinstance(obj, str) else obj)
    req = urllib.request.Request(url, data=data, method="POST", headers={"Content-Type": "application/json"})
    t0 = time.time()
    with urllib.request.urlopen(req, timeout=timeout) as r:
        status = r.status
        r.read()
    return status, time.time() - t0


def box(path, **query):
    q = ("?" + urllib.parse.urlencode(query)) if query else ""
    return get(BOX + path + q)[0]


def processed():
    """The 16 colours the TV shows, in the lights' zone order, or None."""
    try:
        j, _ = get(TV + "/ambilight/processed", timeout=2.0)
    except Exception:
        return None
    layer = (j or {}).get("layer1", {})
    out = []
    for side, i in ZONES:
        px = layer.get(side, {}).get(str(i))
        if px is None:
            return None
        out.append((px["r"], px["g"], px["b"]))
    return out


# ---- colour ----------------------------------------------------------------------------------------

def hex_rgb(h):
    h = h.lstrip("#")
    return int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16)


def lab(c):
    def lin(v):
        v /= 255.0
        return v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4
    r, g, b = (lin(x) for x in c)
    x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047
    y = 0.2126 * r + 0.7152 * g + 0.0722 * b
    z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883

    def f(t):
        return t ** (1 / 3) if t > 216 / 24389 else (24389 / 27 * t + 16) / 116
    fx, fy, fz = f(x), f(y), f(z)
    return 116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)


def delta_e(a, b):
    la, lb = lab(a), lab(b)
    return math.sqrt(sum((p - q) ** 2 for p, q in zip(la, lb)))


def hue_of(c):
    h, s, v = colorsys.rgb_to_hsv(*(x / 255 for x in c))
    return h * 360, s, v


def chroma_match(measured, expected):
    """ΔE after scaling the measured colour to the expected brightness (the TV dims and boosts; the hue is what has to match)."""
    pm, pe = max(measured) or 1, max(expected) or 1
    scaled = tuple(min(255, x * pe / pm) for x in measured)
    return delta_e(scaled, expected)


# ---- saving and putting back -----------------------------------------------------------------------

class Saved:
    def __init__(self):
        self.settings = box("/settings")
        self.lights = box("/lights")["options"]
        self.tv_config, _ = get(TV + "/ambilight/currentconfiguration")
        self.tv_power, _ = get(TV + "/ambilight/power")

    def restore(self, tv_direct):
        s = self.settings.get("values", self.settings)
        try:
            post(BOX + "/settings", {k: s[k] for k in ("display", "ambilight_style", "ambilight_menu") if k in s})
            box("/lights/set", **{k: v for k, v in self.lights.items()})
        except Exception as e:
            print("could not put the app back:", e)
        if tv_direct:
            try:
                post(TV + "/ambilight/currentconfiguration", self.tv_config)
                if self.tv_power and self.tv_power.get("power") == "Off":
                    post(TV + "/ambilight/power", {"power": "Off"})
            except Exception as e:
                print("could not put the TV's Ambilight back:", e)
        print("put back:", {k: s.get(k) for k in ("display", "ambilight_style", "ambilight_menu")}, "lights", self.lights.get("lights"))


def results_dir(step):
    d = os.path.join(os.path.dirname(os.path.abspath(__file__)), "results", time.strftime("%Y%m%d-%H%M%S") + "_lights_" + step)
    os.makedirs(d, exist_ok=True)
    return d


def record(seconds, log, label):
    """Polls the TV's lights for [seconds]; returns [(t, colours)] and writes them to [log]."""
    out = []
    t_start = time.time()
    t_end = t_start + seconds
    while time.time() < t_end:
        t0 = time.time()
        c = processed()
        if c is not None:
            out.append((t0, c))
            log.write(json.dumps({"label": label, "t": round(t0, 3), "zones": c}) + "\n")
        time.sleep(max(0.0, POLL_S - (time.time() - t0)))
    WINDOWS.append((t_start, time.time(), f"GET processed at {1 / POLL_S:.0f}/s: {label}"))
    return out


def check_playing():
    d = box("/tv/dlna")
    state = d.get("state") or d.get("transport_state")
    live = d.get("live")
    delay = d.get("delay_ms")
    print(f"TV player: state {state}, live {live}, delay {delay} ms")
    return d


# ---- S2: the TV's own styles -----------------------------------------------------------------------

def s2(args):
    d = results_dir("s2")
    saved = Saved()
    log = open(os.path.join(d, "s2.jsonl"), "w")
    rows = []
    home, accent = {"hue": 150, "saturation": 220, "brightness": 230}, {"hue": 20, "saturation": 220, "brightness": 230}
    configs = []
    for a in AUDIO_ALGORITHMS:
        configs.append((f"plain:{a}", {"styleName": "FOLLOW_AUDIO", "isExpert": False, "menuSetting": a}))
    for a in AUDIO_ALGORITHMS:
        # Party only works this way (seen by the sync session)
        configs.append((f"expert:{a}", {"styleName": "FOLLOW_AUDIO", "isExpert": True, "algorithm": a}))
    for a in ("ENERGY_ADAPTIVE_BRIGHTNESS", "ENERGY_ADAPTIVE_COLORS"):
        configs.append((f"COLORS:{a}", {"styleName": "FOLLOW_AUDIO", "isExpert": True, "audioSettings": {"algorithm": a, "color": home, "colorDelta": accent}, "tuning": 0}))
    configs.append(("EXPCOL:ENERGY_ADAPTIVE_BRIGHTNESS", {"styleName": "FOLLOW_AUDIO", "isExpert": True, "algorithm": "ENERGY_ADAPTIVE_BRIGHTNESS",
                                                         "colorSettings": {"color": home, "colorDelta": accent, "speed": 255}}))
    configs.append(("COLOR", {"styleName": "FOLLOW_COLOR", "isExpert": True, "algorithm": "MANUAL_HUE",
                              "colorSettings": {"color": home, "colorDelta": {"hue": 0, "saturation": 0, "brightness": 0}, "speed": 255}}))
    # does the TV move a held colour by itself (a delta and a speed, as its lounge light does)?
    for speed in (60, 200):
        configs.append((f"COLOR~{speed}", {"styleName": "FOLLOW_COLOR", "isExpert": True, "algorithm": "MANUAL_HUE",
                                           "colorSettings": {"color": home, "colorDelta": {"hue": 20, "saturation": 0, "brightness": 120}, "speed": speed}}))
    try:
        if args.display:
            box("/remote/action", display=args.display)  # put back with the rest at the end
            time.sleep(3)
        check_playing()
        for name, cfg in configs:
            tp = time.time()
            try:
                status, secs = post(TV + "/ambilight/currentconfiguration", cfg)
            except Exception as e:
                status, secs = str(e), None
            WINDOWS.append((tp, time.time(), f"POST currentconfiguration {name}"))
            time.sleep(1.5)
            back, _ = get(TV + "/ambilight/currentconfiguration")
            rec = record(6, log, name)
            rows.append(look_of(name, status, back, rec))
            print(json.dumps(rows[-1]))
    finally:
        log.close()
        saved.restore(tv_direct=True)
    write_summary(d, "S2: the TV's own styles while music plays", rows, s2_verdict(rows))


def look_of(name, status, back, rec):
    hues = []
    bright = []
    for _, c in rec:
        frame = []
        for z in c:
            h, s, v = hue_of(z)
            if s > 0.25 and v > 0.1:
                hues.append(h)
            frame.append(v)
        bright.append(statistics.mean(frame))
    # distinct hues: 30° buckets that hold at least 8 % of the coloured readings
    buckets = [0] * 12
    for h in hues:
        buckets[int(h // 30) % 12] += 1
    n = max(1, len(hues))
    distinct = sum(1 for b in buckets if b / n >= 0.08)
    react = round(statistics.pstdev(bright) / max(1e-6, statistics.mean(bright)), 2) if len(bright) > 2 else None
    peak = max((max(max(z) for z in c) for _, c in rec), default=0)
    alg = name.split(":")[-1].split("~")[0]
    if back is None:
        took = False
    elif name.startswith("plain:"):
        took = back.get("menuSetting") == alg
    elif name.startswith("COLOR"):
        took = back.get("algorithm") == "MANUAL_HUE" or (back.get("isExpert") is True and ((back.get("audioSettings") or {}).get("algorithm") == alg or back.get("algorithm") == alg))
    else:
        took = back.get("isExpert") is True and back.get("algorithm") == alg
    return {"style": name, "http": status, "took": took, "peak": peak, "distinct_hues": distinct, "reactivity": react,
            "class": "mono" if distinct <= 1 else ("duo" if distinct == 2 else "multi")}


def s2_verdict(rows):
    lines = []
    took = {r["style"]: r for r in rows if r["took"]}
    if any(k.startswith("COLORS:") for k in took):
        best = max((r for k, r in took.items() if k.startswith("COLORS:")), key=lambda r: (r["distinct_hues"] == 2, r["reactivity"] or 0))
        lines.append(f"The TV takes our colours in FOLLOW_AUDIO: `lights_native_duo={best['style']}` (hues {best['distinct_hues']}, reactivity {best['reactivity']}).")
    else:
        duo = [r for r in rows if r["took"] and r["class"] == "duo"]
        if duo:
            lines.append(f"The TV does not take our colours in FOLLOW_AUDIO; its own two-colour style: `lights_native_duo={duo[0]['style']}`.")
    multi = sorted((r for r in rows if r["took"] and r["class"] == "multi" and not r["style"].startswith("COLORS:")), key=lambda r: -(r["reactivity"] or 0))
    if multi:
        lines.append(f"Multi: `lights_native_multi={multi[0]['style']}` (the most reactive multi-colour style).")
    if "COLOR" in took:
        lines.append("Mono: MANUAL_HUE holds our colour (`lights_native_mono=COLOR`).")
    else:
        lines.append("Mono: MANUAL_HUE was not taken; use ENERGY_ADAPTIVE_BRIGHTNESS.")
    return lines


# ---- S3: the call budget ---------------------------------------------------------------------------

def s3(args):
    d = results_dir("s3")
    saved = Saved()
    log = open(os.path.join(d, "s3.jsonl"), "w")
    rows = []
    a = {"styleName": "FOLLOW_COLOR", "isExpert": True, "algorithm": "MANUAL_HUE",
         "colorSettings": {"color": {"hue": 150, "saturation": 220, "brightness": 200}, "colorDelta": {"hue": 0, "saturation": 0, "brightness": 0}, "speed": 255}}
    b = json.loads(json.dumps(a))
    b["colorSettings"]["color"]["hue"] = 20
    try:
        before = box("/tv/last-sessions")
        for rate in (0.5, 1.0, 2.0):
            lat, errors = [], 0
            t_end = time.time() + 60
            k = 0
            while time.time() < t_end:
                t0 = time.time()
                try:
                    _, secs = post(TV + "/ambilight/currentconfiguration", a if k % 2 == 0 else b, timeout=3.0)
                    lat.append(secs * 1000)
                except Exception:
                    errors += 1
                k += 1
                log.write(json.dumps({"rate": rate, "t": round(t0, 3), "ms": round(lat[-1]) if lat else None}) + "\n")
                time.sleep(max(0.0, 1 / rate - (time.time() - t0)))
            lat.sort()
            row = {"per_s": rate, "calls": k, "errors": errors, "p50_ms": round(lat[len(lat) // 2]) if lat else None,
                   "p95_ms": round(lat[int(len(lat) * 0.95)]) if lat else None, "max_ms": round(lat[-1]) if lat else None}
            rows.append(row)
            print(json.dumps(row))
        after = box("/tv/last-sessions")
        rows.append({"tv_player_connections_before": len(before or []), "after": len(after or [])})
    finally:
        log.close()
        saved.restore(tv_direct=True)
    ok = [r for r in rows if "per_s" in r and r["errors"] == 0 and (r["p95_ms"] or 9e9) < 300]
    verdict = [f"Clean at up to {max(r['per_s'] for r in ok)} calls a second." if ok else "Not clean even at 0.5 calls a second: keep one call per song."]
    write_summary(d, "S3: style calls a second", rows, verdict)


def write_summary(d, title, rows, verdict):
    with open(os.path.join(d, "summary.md"), "w") as f:
        f.write(f"# {title}\n\n")
        for line in verdict:
            f.write(f"- {line}\n")
        f.write("\n```\n")
        for r in rows:
            f.write(json.dumps(r) + "\n")
        f.write("```\n")
    print("\n".join(verdict))
    print("results:", d)


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("step", choices=["s2", "s3"])
    p.add_argument("--display", choices=["ambilight", "screen"], help="s2: the display to run in (the one before is put back)")
    args = p.parse_args()
    steps = {"s2": [s2], "s3": [s3]}[args.step]
    try:
        for st in steps:
            st(args)
    finally:
        if WINDOWS:
            print("\nTV traffic (local time):")
            for a, b, what in WINDOWS:
                print(f"  {time.strftime('%H:%M:%S', time.localtime(a))}-{time.strftime('%H:%M:%S', time.localtime(b))}  {what}")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(130)
