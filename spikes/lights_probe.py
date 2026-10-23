#!/usr/bin/env python3
"""The TV test of the mood lights: what the Philips Ambilight makes of the painted picture and of its own styles.

Runs on the Mac against the box (the Whole Flat app with the mood lights, Synced mode playing) and the TV (JointSpace on :1925).
It reads the lights back from the TV (GET /6/ambilight/processed, 3 a second at most: the TV's server stalls from about
10 calls a second) and never GETs /6/ambilight/cached (reported to crash the server).

    python3 lights_probe.py s1            # painted picture: the screen off and on, FOLLOW_VIDEO settings and Lumina
    python3 lights_probe.py s2            # the TV's own styles while music plays (the look book of Lyrics mode)
    python3 lights_probe.py s3            # style calls at 0.5, 1 and 2 a second: errors, stalls and the TV's player
    python3 lights_probe.py all

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
            box("/lights/test", pattern="off")
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


# ---- S1: the painted picture -----------------------------------------------------------------------

def s1(args):
    d = results_dir("s1")
    saved = Saved()
    log = open(os.path.join(d, "s1.jsonl"), "w")
    rows = []
    try:
        check_playing()
        box("/lights/set", lights="mood")
        for display in ("ambilight", "screen"):
            box("/remote/action", display=display)
            time.sleep(3)
            for style in SYNCED_STYLES:
                box("/lights/set", lights_synced_style=style)
                time.sleep(2.5)
                widths = [5] if display == "ambilight" else [3, 5, 10]
                if display == "screen" and not style.startswith(("FOLLOW_VIDEO/GAME", "FOLLOW_AUDIO")):
                    continue  # the band is tried with the fastest FOLLOW_VIDEO and with Lumina only
                for w in widths:
                    if display == "screen":
                        box("/lights/set", lights_band_pct=w)
                    rows.append(one_s1(display, style, w, log))
                    print(json.dumps(rows[-1]))
    finally:
        log.close()
        saved.restore(tv_direct=False)
    write_summary(d, "S1: the painted picture", rows, s1_verdict(rows))


def one_s1(display, style, width, log):
    delay = (box("/tv/dlna").get("delay_ms") or 2500) / 1000.0
    label = f"{display}|{style}|{width}"
    # every zone its own hue: does each zone show its own colour, and in the order we think?
    exp = [hex_rgb(h) for h in box("/lights/test", pattern="zones", seconds=8)["zones_at_start"]]
    time.sleep(delay + 1.5)
    rec = record(5, log, label + "|zones")
    zone_de, order_ok = None, None
    if rec:
        med = [tuple(statistics.median(r[1][z][k] for r in rec) for k in range(3)) for z in range(16)]
        zone_de = round(statistics.median(chroma_match(med[z], exp[z]) for z in range(16)), 1)
        best = [min(range(16), key=lambda e: chroma_match(med[z], exp[e])) for z in range(16)]
        order_ok = sum(1 for z in range(16) if best[z] == z)
    # a 120 BPM pulse, 60 % deep: how much of it is left on the wall?
    box("/lights/test", pattern="pulse", seconds=10, color="ffffff", bpm=120)
    time.sleep(delay + 1.0)
    rec = record(8, log, label + "|pulse")
    depth = None
    if rec:
        b = [statistics.mean(max(c) for c in r[1]) for r in rec]
        hi, lo = max(b), min(b)
        depth = round((hi - lo) / hi, 2) if hi > 0 else 0.0
    if display == "screen":
        box("/lights/test", pattern="off")
        return {"display": display, "style": style, "band_pct": width, "zones_dE": zone_de, "zones_in_order": order_ok,
                "pulse_depth_left": depth, "lag_after_sound_ms": None, "tv_delay_s": delay}
    # black and white every 500 ms: the delay from the picture's moment to the lights (from the steps the polls catch)
    t0 = time.time()
    box("/lights/test", pattern="steps", seconds=24, color="ffffff", step_ms=500)
    rec = record(delay + 22, log, label + "|steps")
    lag_ms = step_lag(rec, t0 + delay, 0.5)
    box("/lights/test", pattern="off")
    return {"display": display, "style": style, "band_pct": width, "zones_dE": zone_de, "zones_in_order": order_ok,
            "pulse_depth_left": depth, "lag_after_sound_ms": lag_ms, "tv_delay_s": delay}


def step_lag(rec, start, step):
    """The mean delay of the light's steps after their moment ([start] + k*[step]), from polls; None if too few."""
    lags = []
    prev = None
    for t, c in rec:
        on = statistics.mean(max(z) for z in c) > 128
        if prev is not None and on != prev:
            k = round((t - start) / step)
            lags.append(t - (start + k * step))
        prev = on
    if len(lags) < 6:
        return None
    # a poll sees a step up to one poll late: take off half a poll
    return round((statistics.mean(lags) - POLL_S / 2) * 1000)


def s1_verdict(rows):
    lines = []
    off = [r for r in rows if r["display"] == "ambilight"]
    good = [r for r in off if r["style"].startswith("FOLLOW_VIDEO") and (r["zones_dE"] or 99) < 10 and (r["pulse_depth_left"] or 0) >= 0.5 * 0.6]
    if good:
        pick = good[0]["style"]  # the list is in order of preference (GAME first)
        lines.append(f"Screen off: **{pick}** follows the painted picture (ΔE {good[0]['zones_dE']}, pulse depth kept "
                     f"{good[0]['pulse_depth_left']}): set `lights_synced_style={pick}` (our pulses).")
    else:
        lumina = [r for r in off if r["style"].startswith("FOLLOW_AUDIO") and (r["zones_dE"] or 99) < 15]
        if lumina:
            lines.append("Screen off: FOLLOW_VIDEO does not follow the picture well enough; **Lumina** takes the painted colours: keep the default.")
        else:
            lines.append("Screen off: neither follows the painted picture: the lights go **TV-native per song** in Synced mode too.")
    on = [r for r in rows if r["display"] == "screen" and (r["zones_dE"] or 99) < 10]
    if on:
        w = min(r["band_pct"] for r in on)
        lines.append(f"Screen on: the narrowest band that carries the zones is **{w} %**: set `lights_band_pct={w}`.")
    else:
        lines.append("Screen on: no band width carries the zones within ΔE 10; keep 5 % and look at it by eye.")
    return lines


# ---- S2: the TV's own styles -----------------------------------------------------------------------

def s2(args):
    d = results_dir("s2")
    saved = Saved()
    log = open(os.path.join(d, "s2.jsonl"), "w")
    rows = []
    home, accent = {"hue": 150, "saturation": 220, "brightness": 230}, {"hue": 20, "saturation": 220, "brightness": 230}
    configs = []
    for a in AUDIO_ALGORITHMS:
        configs.append((f"{a}", {"styleName": "FOLLOW_AUDIO", "isExpert": False, "menuSetting": a}))
    for a in ("ENERGY_ADAPTIVE_BRIGHTNESS", "VU_METER", "KNIGHT_RIDER_CLOCKWISE", "SPECTRUM_ANALYZER"):
        configs.append((f"COLORS:{a}", {"styleName": "FOLLOW_AUDIO", "isExpert": True, "audioSettings": {"algorithm": a, "color": home, "colorDelta": accent}, "tuning": 0}))
    configs.append(("COLOR", {"styleName": "FOLLOW_COLOR", "isExpert": True, "algorithm": "MANUAL_HUE",
                              "colorSettings": {"color": home, "colorDelta": {"hue": 0, "saturation": 0, "brightness": 0}, "speed": 255}}))
    try:
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
            rec = record(8, log, name)
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
    took = back is not None and (back.get("algorithm") == name.split(":")[-1] or back.get("menuSetting") == name or
                                 (name.startswith("COLORS:") and back.get("isExpert") is True) or (name == "COLOR" and back.get("algorithm") == "MANUAL_HUE"))
    return {"style": name, "http": status, "took": took, "distinct_hues": distinct, "reactivity": react,
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
    p.add_argument("step", choices=["s1", "s2", "s3", "all"])
    args = p.parse_args()
    steps = {"s1": [s1], "s2": [s2], "s3": [s3], "all": [s1, s2]}[args.step]  # s3 (many style calls) only when asked for
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
