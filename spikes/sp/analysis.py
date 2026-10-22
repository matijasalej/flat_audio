"""Acoustic analysis of recordings of the test signal (needs numpy).

Pipeline
--------
1. ``detect_clicks``: running single-bin DFT (Goertzel equivalent, 5 ms window,
   2.5 ms hop) at 2 kHz and 4 kHz -> adaptive threshold from the local noise
   floor -> events -> classification 2 kHz / 4 kHz -> onset refined to well
   under 1 ms with a 1 ms envelope and a calibrated threshold crossing.
2. ``assign_indices``: click index k from link-by-spacing plus the 4 kHz markers
   (k % 10 == 0); segments are split at phase jumps (stutters / skips).
3. ``fit_clicks``: robust line  t_k = a + b*k  -> acoustic start t0, slope (ppm),
   jitter, missing clicks.
4. ``tone_track``: 440 Hz amplitude / phase in 9 ms windows -> gaps and phase
   jumps (dropouts, stutters), first sound, end of sound.

All times are seconds on the *recording* timeline (sample 0 = 0.0). Mapping to
the Mac's monotonic clock happens in ``sp.record.RecInfo``.
"""
from __future__ import annotations

import math
import sys
from dataclasses import dataclass, field
from typing import Any, Dict, Iterator, List, Optional, Sequence, Tuple

import numpy as np

from . import testsignal as ts
from . import wavio

F2 = 2000.0
F4 = 4000.0
FT = 440.0

COARSE_WIN_S = 0.005
COARSE_HOP_S = 0.0025
FINE_WIN_S = 0.001
FINE_ALPHA = 0.4
FLOOR_SEG_S = 2.0
MERGE_S = 0.10
DEFAULT_SNR_DB = 14.0
ABS_FLOOR = 10.0 ** (-75.0 / 20.0)   # -75 dBFS: never trigger below this


# ------------------------------------------------------------------ loading

class Timeline:
    """Contiguous recording made of one or more 16-bit PCM wav chunks."""

    def __init__(self, paths: Sequence[Any]):
        self.paths = [str(p) for p in paths]
        self.infos = [wavio.read_info(p) for p in self.paths]
        fss = {i.fs for i in self.infos}
        if len(fss) != 1:
            raise ValueError("chunks differ in sample rate: %s" % sorted(fss))
        for i in self.infos:
            if i.bits != 16 or i.fmt_tag != 1:
                raise ValueError("only 16-bit PCM wav supported (got %d bit, tag %d)" % (i.bits, i.fmt_tag))
        self.fs = self.infos[0].fs
        self.lengths = [i.frames for i in self.infos]
        self.starts = [0]
        for n in self.lengths[:-1]:
            self.starts.append(self.starts[-1] + n)
        self.total = sum(self.lengths)
        self._mm: Dict[int, Any] = {}

    @property
    def duration(self) -> float:
        return self.total / float(self.fs)

    def _map(self, idx: int):
        mm = self._mm.get(idx)
        if mm is None:
            i = self.infos[idx]
            if i.frames == 0:
                mm = np.zeros((0, i.channels), dtype="<i2")
            else:
                mm = np.memmap(self.paths[idx], dtype="<i2", mode="r", offset=i.data_offset,
                               shape=(i.frames * i.channels,)).reshape(i.frames, i.channels)
            self._mm[idx] = mm
        return mm

    def read(self, start: int, n: int) -> np.ndarray:
        """Mono float32 samples [start, start+n) in [-1, 1)."""
        n = max(0, min(n, self.total - start))
        parts = []
        pos = start
        left = n
        for idx, (s0, ln) in enumerate(zip(self.starts, self.lengths)):
            if left <= 0:
                break
            if pos >= s0 + ln:
                continue
            a = pos - s0
            take = min(left, ln - a)
            blk = np.asarray(self._map(idx)[a:a + take], dtype=np.float32)
            parts.append(blk.mean(axis=1) if blk.shape[1] > 1 else blk[:, 0])
            pos += take
            left -= take
        if not parts:
            return np.zeros(0, dtype=np.float32)
        return (np.concatenate(parts) / 32768.0).astype(np.float32)

    def blocks(self, block_s: float = 30.0, overlap_s: float = 0.6) -> Iterator[Tuple[int, np.ndarray]]:
        B = int(block_s * self.fs)
        O = int(overlap_s * self.fs)
        step = max(1, B - O)
        s = 0
        while s < self.total:
            x = self.read(s, B)
            yield s, x
            if s + B >= self.total:
                break
            s += step

    def close(self) -> None:
        self._mm.clear()


# ------------------------------------------------------------------ detector

@dataclass
class Click:
    t: float                 # refined onset, seconds on the recording timeline
    cls: int                 # 2 (2 kHz), 4 (4 kHz marker) or 0 (undecided)
    amp: float               # peak band amplitude (full scale = 1.0)
    snr_db: float            # peak amplitude vs local noise floor
    k: int = -1              # click index (after assign_indices)
    seg: int = 0             # segment id (phase-continuous run)
    flags: str = ""

    def as_dict(self) -> Dict[str, Any]:
        return {"k": self.k, "t": round(self.t, 6), "cls": self.cls, "amp_db": round(20 * math.log10(max(self.amp, 1e-9)), 1),
                "snr_db": round(self.snr_db, 1), "seg": self.seg, "flags": self.flags}


def _cum_demod(x: np.ndarray, f: float, fs: int, n0: int = 0) -> np.ndarray:
    """Cumulative sum of x[n] * exp(-j 2 pi f (n0+n) / fs)  (length N+1, c[0] = 0)."""
    n = np.arange(n0, n0 + x.size, dtype=np.float64)
    z = x.astype(np.float64) * np.exp((-2j * math.pi * f / fs) * n)
    c = np.empty(x.size + 1, dtype=np.complex128)
    c[0] = 0
    np.cumsum(z, out=c[1:])
    return c


def _rolling_floor(a: np.ndarray, seg: int) -> np.ndarray:
    n = a.size
    m = max(1, (n + seg - 1) // seg)
    pad = np.pad(a, (0, m * seg - n), mode="edge")
    med = np.median(pad.reshape(m, seg), axis=1)
    return np.repeat(med, seg)[:n]


_BIAS_CACHE: Dict[Tuple[int, float], Dict[int, float]] = {}


def click_bias(fs: int, alpha: float = FINE_ALPHA) -> Dict[int, float]:
    """Systematic onset bias (samples) of the refined detector on the ideal signal.

    Calibrated by running the detector on the generator's own output, so that
    ideal clicks are located exactly at k * fs. Subtracted from every detection.
    """
    key = (fs, alpha)
    if key in _BIAS_CACHE:
        return _BIAS_CACHE[key]
    secs = 22
    x = np.asarray(ts.track(fs).mono_i16(0, secs * fs), dtype=np.float32) / 32768.0
    clicks = detect_clicks(x, fs, apply_bias=False, snr_db=10.0)
    errs: Dict[int, List[float]] = {2: [], 4: []}
    for c in clicks:
        k = int(round(c.t))
        if 0 <= k < secs - 1 and c.cls in errs:
            errs[c.cls].append((c.t - k) * fs)
    bias = {c: (float(np.median(v)) if v else 0.0) for c, v in errs.items()}
    bias[0] = 0.5 * (bias[2] + bias[4])
    _BIAS_CACHE[key] = bias
    return bias


def detect_clicks(x: np.ndarray, fs: int, t_offset_samples: int = 0, snr_db: float = DEFAULT_SNR_DB,
                  apply_bias: bool = True, alpha: float = FINE_ALPHA) -> List[Click]:
    """Detect clicks in a mono float signal. Returns onsets in seconds (global timeline)."""
    win = int(round(COARSE_WIN_S * fs))
    hop = int(round(COARSE_HOP_S * fs))
    w1 = int(round(FINE_WIN_S * fs))
    if x.size < win + hop:
        return []
    c2 = _cum_demod(x, F2, fs)
    c4 = _cum_demod(x, F4, fs)
    ends = np.arange(win, x.size + 1, hop)
    a2 = 2.0 * np.abs(c2[ends] - c2[ends - win]) / win
    a4 = 2.0 * np.abs(c4[ends] - c4[ends - win]) / win
    seg = max(8, int(round(FLOOR_SEG_S / (hop / fs))))
    f2 = np.maximum(_rolling_floor(a2, seg), 1e-7)
    f4 = np.maximum(_rolling_floor(a4, seg), 1e-7)
    lin = 10.0 ** (snr_db / 20.0)
    thr2 = np.maximum(f2 * lin, ABS_FLOOR)
    thr4 = np.maximum(f4 * lin, ABS_FLOOR)
    r = np.maximum(a2 / thr2, a4 / thr4)
    idx = np.flatnonzero(r > 1.0)
    if idx.size == 0:
        return []
    merge_hops = max(1, int(round(MERGE_S / (hop / fs))))
    groups = np.split(idx, np.flatnonzero(np.diff(idx) > merge_hops) + 1)
    bias = click_bias(fs, alpha) if apply_bias else {0: 0.0, 2: 0.0, 4: 0.0}
    out: List[Click] = []
    for g in groups:
        h0 = int(g[0])
        pk = int(g[np.argmax(r[g])])
        a2p, a4p = float(a2[pk]), float(a4[pk])
        cls = 4 if a4p > 3.0 * a2p else (2 if a2p > 3.0 * a4p else 0)
        fc = F4 if (cls == 4 or (cls == 0 and a4p > a2p)) else F2
        peak_amp = a4p if fc == F4 else a2p
        floor = float((f4 if fc == F4 else f2)[pk])
        lo = max(0, int(ends[h0]) - win - 3 * hop - w1)
        hi = min(x.size, int(ends[h0]) + 2 * win + w1)
        if hi - lo < 3 * w1:
            continue
        seg_x = x[lo:hi]
        cf = _cum_demod(seg_x, fc, fs, n0=lo)
        env = 2.0 * np.abs(cf[w1:] - cf[:-w1]) / w1        # env[m]: window [lo+m, lo+m+w1)
        m_pk = int(np.argmax(env))
        a_pk = float(env[m_pk])
        level = alpha * a_pk
        m = m_pk
        while m > 0 and env[m - 1] >= level:
            m -= 1
        if m == 0 and env[0] >= level:
            frac = 0.0      # crossing before the segment: keep the edge (flagged by caller via flags)
            flags = "early"
        else:
            e0, e1 = float(env[m - 1]), float(env[m])
            frac = (level - e0) / (e1 - e0) if e1 > e0 else 0.0
            frac -= 1.0
            flags = ""
        onset = lo + m + frac + (1.0 - alpha) * w1
        onset -= bias.get(cls, bias[0])
        out.append(Click(t=(t_offset_samples + onset) / fs, cls=cls, amp=peak_amp,
                         snr_db=20.0 * math.log10(max(peak_amp, 1e-9) / floor), flags=flags))
    return out


def detect_clicks_timeline(tl: Timeline, snr_db: float = DEFAULT_SNR_DB, progress=None) -> List[Click]:
    """Detect over a (possibly long) timeline in overlapping blocks and merge duplicates."""
    allc: List[Click] = []
    for start, x in tl.blocks():
        allc.extend(detect_clicks(x, tl.fs, t_offset_samples=start, snr_db=snr_db))
        if progress:
            progress(start / float(tl.fs))
    allc.sort(key=lambda c: c.t)
    merged: List[Click] = []
    for c in allc:
        if merged and abs(c.t - merged[-1].t) < 0.03:
            if c.amp > merged[-1].amp:
                merged[-1] = c
            continue
        merged.append(c)
    return merged


# ------------------------------------------------------------------ indices and fits

def _chains(cs: List[Click], spacing: float, tol: float, max_gap: int = 12, min_len: int = 3) -> Tuple[List[List[int]], List[int]]:
    """Longest 1 s-spaced chains of detections (spurious events fall out).

    Dynamic programming: every detection links to the best predecessor whose
    distance is a whole number of seconds (within ``tol``); chains are then
    peeled off longest first. Returns (chains as index lists, dropped indices).
    """
    n = len(cs)
    t = [c.t for c in cs]
    L = [1.0] * n
    pred = [-1] * n
    lo = 0
    for i in range(n):
        while t[i] - t[lo] > max_gap * spacing + tol:
            lo += 1
        best, bj = 1.0, -1
        for j in range(lo, i):
            dt = t[i] - t[j]
            m = int(round(dt / spacing))
            if m >= 1 and abs(dt - m * spacing) <= tol:
                val = L[j] + 1.0 - 0.001 * m
                if val > best:
                    best, bj = val, j
        L[i], pred[i] = best, bj
    used = [False] * n
    chains: List[List[int]] = []
    for i in sorted(range(n), key=lambda q: -L[q]):
        if used[i]:
            continue
        chain = []
        j = i
        while j != -1 and not used[j]:
            chain.append(j)
            j = pred[j]
        chain.reverse()
        if len(chain) >= min_len:
            for j in chain:
                used[j] = True
            chains.append(chain)
    dropped = [i for i in range(n) if not used[i]]
    chains.sort(key=lambda ch: t[ch[0]])
    return chains, dropped


def _split_by_markers(seg: List[Click], rel: List[int]) -> List[Tuple[List[Click], List[int]]]:
    """Split a chain where the 4 kHz marker pattern contradicts itself.

    A whole number of seconds of audio skipped or repeated keeps the 1 s spacing
    but moves the markers; a lone mis-classified click (checked against the next
    marker) is not enough to split.
    """
    out: List[Tuple[List[Click], List[int]]] = []
    start = 0
    res: Optional[int] = None
    n = len(seg)
    i = 0
    while i < n:
        c, r = seg[i], rel[i]
        if res is None:
            if c.cls == 4:
                res = r % 10
        else:
            is_marker_slot = (r % 10 == res)
            contradiction = (c.cls == 4 and not is_marker_slot) or (c.cls == 2 and is_marker_slot)
            if contradiction:
                # look ahead: the next 4 kHz click decides whether the old pattern still holds
                nxt = next((q for q in range(i + 1, n) if seg[q].cls == 4), None)
                old_ok = nxt is not None and (rel[nxt] % 10 == res)
                if not old_ok:
                    out.append((seg[start:i], rel[start:i]))
                    start = i
                    res = (r % 10) if c.cls == 4 else None
        i += 1
    out.append((seg[start:], rel[start:]))
    return [(s, [x - r[0] for x in r]) for s, r in out if s]


def assign_indices(clicks: List[Click], spacing: float = 1.0, tol: float = 0.05,
                   min_rel_amp: float = 0.08) -> Tuple[List[Click], Dict[str, Any]]:
    """Assign the click index k to each detection.

    1. detections far quieter than their class median are discarded (noise);
    2. 1 s-spaced chains are extracted (spurious events drop out; a phase jump
       - stutter, skip - ends a chain and starts the next);
    3. chains are split where the 4 kHz marker pattern contradicts itself;
    4. inside each segment the markers (k % 10 == 0) fix k mod 10; the first
       segment gets the smallest k >= 0 that fits, later ones the value closest
       to continuity with the previous segment.
    """
    info: Dict[str, Any] = {"rejected_quiet": 0, "rejected_offgrid": 0}
    if not clicks:
        info["segments"] = []
        return [], info
    cs = sorted(clicks, key=lambda c: c.t)
    med: Dict[int, float] = {}
    for cl in (2, 4):
        v = [c.amp for c in cs if c.cls == cl]
        if v:
            med[cl] = float(np.median(v))
    allmed = float(np.median([c.amp for c in cs]))
    keep = []
    for c in cs:
        if c.amp < min_rel_amp * med.get(c.cls, allmed):
            info["rejected_quiet"] += 1
        else:
            keep.append(c)
    cs = keep
    chains, dropped = _chains(cs, spacing, tol)
    info["rejected_offgrid"] = len(dropped)
    info["dropped_t"] = [round(cs[i].t, 3) for i in dropped[:20]]
    segs: List[Tuple[List[Click], List[int]]] = []
    for ch in chains:
        seg = [cs[i] for i in ch]
        rel = [0]
        for a, b in zip(seg[:-1], seg[1:]):
            rel.append(rel[-1] + int(round((b.t - a.t) / spacing)))
        segs.extend(_split_by_markers(seg, rel))
    out: List[Click] = []
    seginfo = []
    prev_last_k: Optional[int] = None
    prev_last_t: Optional[float] = None
    for si, (seg, rr) in enumerate(segs):
        marks = [r for c, r in zip(seg, rr) if c.cls == 4]
        shift: Optional[int] = None
        conf = 0.0
        if marks:
            votes: Dict[int, int] = {}
            for r in marks:
                votes[r % 10] = votes.get(r % 10, 0) + 1
            res, cnt = max(sorted(votes.items()), key=lambda kv: kv[1])
            shift = (-res) % 10
            conf = cnt / float(len(marks))
        if prev_last_k is None:
            k_first = shift if shift is not None else 0
        else:
            gap = max(1, int(round((seg[0].t - prev_last_t) / spacing)))
            expect = prev_last_k + gap
            if shift is None:
                k_first = expect
            else:
                m = int(round((expect - shift) / 10.0))
                k_first = max(0, shift + 10 * m)
        for c, r in zip(seg, rr):
            c.k = k_first + r
            c.seg = si
            exp_mark = (c.k % 10 == 0)
            if (c.cls == 4 and not exp_mark) or (c.cls == 2 and exp_mark):
                c.flags = (c.flags + " class_mismatch").strip()
            out.append(c)
        prev_last_k, prev_last_t = seg[-1].k, seg[-1].t
        seginfo.append({"seg": si, "n": len(seg), "k_first": seg[0].k, "k_last": seg[-1].k,
                        "t_first": seg[0].t, "t_last": seg[-1].t, "marker_conf": conf, "markers": len(marks),
                        "no_marker": shift is None})
    info["segments"] = seginfo
    return out, info


def _segment_fit(clicks: List[Click], spacing: float, clip: float = 0.004) -> Dict[str, Any]:
    """Common slope b with one intercept per segment (robust, iterated)."""
    segs: Dict[int, List[Click]] = {}
    for c in clicks:
        segs.setdefault(c.seg, []).append(c)
    ks = {s: np.array([c.k for c in v], dtype=np.float64) for s, v in segs.items()}
    es = {s: np.array([c.t for c in v], dtype=np.float64) - ks[s] * spacing for s, v in segs.items()}
    inl = {s: np.ones(ks[s].size, bool) for s in segs}
    b = 0.0
    a = {s: float(np.median(es[s])) for s in segs}
    for _ in range(6):
        # slope from within-segment variation of the inliers (only if enough span)
        num = den = 0.0
        span = 0.0
        for s in segs:
            k, e, m = ks[s], es[s], inl[s]
            if m.sum() < 3:
                continue
            kk, ee = k[m], e[m]
            km, em = kk.mean(), ee.mean()
            num += float(((kk - km) * (ee - em)).sum())
            den += float(((kk - km) ** 2).sum())
            span = max(span, float(kk.max() - kk.min()))
        b = num / den if (den > 0 and span >= 25) else 0.0
        for s in segs:
            r = es[s] - b * ks[s]
            a[s] = float(np.median(r[inl[s]])) if inl[s].any() else float(np.median(r))
        allres = np.concatenate([es[s] - b * ks[s] - a[s] for s in segs])
        mad = 1.4826 * float(np.median(np.abs(allres - np.median(allres))))
        lim = max(clip, 3.0 * mad)
        for s in segs:
            inl[s] = np.abs(es[s] - b * ks[s] - a[s]) <= lim
    return {"b": b, "a": a, "ks": ks, "es": es, "inl": inl, "segs": segs}


def fit_clicks(clicks: List[Click], spacing: float = 1.0) -> Dict[str, Any]:
    """Summary of an indexed click series: t0, slope (ppm), jitter, missing, segments, jumps."""
    res: Dict[str, Any] = {"n_clicks": len(clicks)}
    if not clicks:
        res.update(ok=False, why="no clicks detected")
        return res
    fit = _segment_fit(clicks, spacing)
    b, a = fit["b"], fit["a"]
    order = sorted(fit["segs"])
    resid_all, out_list = [], []
    seg_rows = []
    missing: List[int] = []
    for s in order:
        k, e, m = fit["ks"][s], fit["es"][s], fit["inl"][s]
        r = e - b * k - a[s]
        resid_all.append(r[m])
        for q in np.flatnonzero(~m):
            out_list.append((int(k[q]), round(float(r[q]) * 1000.0, 2)))
        have = set(int(v) for v in k)
        missing.extend(kk for kk in range(int(k.min()), int(k.max()) + 1) if kk not in have)
        seg_rows.append({"seg": s, "n": int(k.size), "k_first": int(k.min()), "k_last": int(k.max()),
                         "t_first": float(fit["segs"][s][0].t), "intercept": a[s],
                         "jitter_ms": float(np.std(r[m]) * 1000.0) if m.sum() > 1 else float("nan")})
    resid = np.concatenate(resid_all) * 1000.0 if resid_all else np.zeros(0)
    jumps = []
    for prev, cur in zip(seg_rows[:-1], seg_rows[1:]):
        jumps.append({"after_k": prev["k_last"], "before_k": cur["k_first"], "t": cur["t_first"],
                      "jump_ms": (cur["intercept"] - prev["intercept"]) * 1000.0})
    k_all = np.array([c.k for c in clicks], dtype=np.float64)
    first = seg_rows[0]
    t0 = first["intercept"] if first["n"] >= 3 else float(np.median(np.concatenate([fit["es"][s] - b * fit["ks"][s] for s in order])))
    t0_all = float(np.median(np.concatenate([fit["es"][s] - b * fit["ks"][s] for s in order])))
    res.update(
        ok=True,
        k_first=int(k_all.min()), k_last=int(k_all.max()), n_expected=int(k_all.max() - k_all.min() + 1),
        n_missing=len(missing), missing=missing[:200],
        t0=t0, t0_all=t0_all, slope_ppm=b * 1e6,
        jitter_ms=float(np.std(resid)) if resid.size > 1 else float("nan"),
        p99_abs_ms=float(np.percentile(np.abs(resid), 99)) if resid.size else float("nan"),
        n_outliers=len(out_list), outliers=out_list[:50],
        n_segments=len(seg_rows), segments=seg_rows, jumps=jumps,
        median_snr_db=float(np.median([c.snr_db for c in clicks])),
        n_class_mismatch=sum(1 for c in clicks if "class_mismatch" in c.flags),
        n_4k=sum(1 for c in clicks if c.cls == 4), n_2k=sum(1 for c in clicks if c.cls == 2),
    )
    return res


# ------------------------------------------------------------------ tone (440 Hz) track

@dataclass
class ToneTrack:
    t: np.ndarray        # window centre times (s)
    amp: np.ndarray      # 440 Hz amplitude (FS = 1.0)
    ph: np.ndarray       # phase (rad)
    valid: np.ndarray    # False inside click masks
    hop_s: float
    win_s: float


def tone_track(tl: Timeline, clicks: Sequence[Click], t_from: float = 0.0, t_to: Optional[float] = None,
               mask_before: float = 0.004, mask_after: float = 0.030) -> ToneTrack:
    fs = tl.fs
    W = int(round(4.0 * fs / FT))
    hop = int(round(0.002 * fs))
    s_from = max(0, int(t_from * fs))
    s_to = tl.total if t_to is None else min(tl.total, int(t_to * fs))
    ts_, amps, phs = [], [], []
    B = 20 * fs
    pos = s_from
    while pos < s_to:
        n = min(B + W, s_to - pos)
        x = tl.read(pos, n)
        if x.size < W + hop:
            break
        c = _cum_demod(x, FT, fs, n0=pos)
        ends = np.arange(W, x.size + 1, hop)
        # keep only windows whose end lies in this block's own span so blocks do not overlap
        keep_end = pos + ends <= min(pos + B + W, s_to)
        own = (pos + ends - W) < (pos + B)
        sel = keep_end & own
        e = ends[sel]
        S = c[e] - c[e - W]
        amps.append((2.0 * np.abs(S) / W).astype(np.float32))
        phs.append(np.angle(S).astype(np.float32))
        ts_.append((pos + e - W / 2.0) / fs)
        pos += B
    if not ts_:
        z = np.zeros(0)
        return ToneTrack(z, z.astype(np.float32), z.astype(np.float32), z.astype(bool), hop / fs, W / fs)
    t = np.concatenate(ts_)
    amp = np.concatenate(amps)
    ph = np.concatenate(phs)
    valid = np.ones(t.size, bool)
    half = W / fs / 2.0
    ct = np.array([c.t for c in clicks], dtype=np.float64)
    if ct.size:
        # a window is invalid if it overlaps [onset - mask_before, onset + mask_after]
        lo = np.searchsorted(ct, t - half - mask_after, side="left")
        hi = np.searchsorted(ct, t + half + mask_before, side="right")
        valid = hi <= lo
    return ToneTrack(t, amp, ph, valid, hop / fs, W / fs)


def tone_events(tt: ToneTrack, t_from: float, t_to: float, noise_amp: Optional[float] = None,
                gap_db: float = -9.0, phase_rad: float = 0.6, min_snr_db: float = 12.0) -> Dict[str, Any]:
    """Gaps (level drops) and phase jumps of the 440 Hz tone inside [t_from, t_to].

    ``noise_amp``: 440 Hz amplitude measured while the stream is silent. If the
    tone is less than ``min_snr_db`` above it, events are not reported (they
    would be noise) and ``reliable`` is False.
    """
    res: Dict[str, Any] = {"gaps": [], "phase_jumps": [], "ref_db": float("nan"), "reliable": False,
                           "tone_snr_db": float("nan")}
    if tt.t.size == 0:
        return res
    m = (tt.t >= t_from) & (tt.t <= t_to)
    t, amp, ph, valid = tt.t[m], tt.amp[m], tt.ph[m], tt.valid[m]
    if t.size < 10 or not valid.any():
        return res
    ref = float(np.median(amp[valid]))
    res["ref_db"] = 20 * math.log10(max(ref, 1e-9))
    if noise_amp is not None and noise_amp > 0:
        res["tone_snr_db"] = res["ref_db"] - 20 * math.log10(noise_amp)
        res["reliable"] = res["tone_snr_db"] >= min_snr_db
    else:
        res["reliable"] = True
    if not res["reliable"]:
        return res
    thr = ref * 10.0 ** (gap_db / 20.0)
    low = (amp < thr) | (~valid)
    nvalid_low = (amp < thr) & valid
    i = 0
    n = t.size
    while i < n:
        if not low[i]:
            i += 1
            continue
        j = i
        while j < n and low[j]:
            j += 1
        if int(nvalid_low[i:j].sum()) >= 3:
            a, b = i, j - 1
            while a < b and not nvalid_low[a]:
                a += 1
            while b > a and not nvalid_low[b]:
                b -= 1
            res["gaps"].append({"t": float(t[a] - tt.win_s / 2), "dur_ms": float((t[b] - t[a]) * 1000.0 + tt.hop_s * 1000.0),
                                "min_db": float(20 * math.log10(max(float(amp[a:b + 1].min()), 1e-9)) - res["ref_db"])})
        i = j
    ok = valid & (amp > ref * 0.5)
    both = ok[1:] & ok[:-1]
    d = np.angle(np.exp(1j * (ph[1:].astype(np.float64) - ph[:-1].astype(np.float64))))
    if both.sum() > 20:
        d = d - float(np.median(d[both]))
        sig = 1.4826 * float(np.median(np.abs(d[both])))
        thr_p = max(phase_rad, 8.0 * sig)
    else:
        thr_p = phase_rad
    res["phase_threshold_rad"] = thr_p
    jumps = np.flatnonzero(both & (np.abs(d) > thr_p))
    last = -10
    for jx in jumps:
        if jx - last > 3:
            res["phase_jumps"].append({"t": float(t[jx]), "rad": float(d[jx]),
                                       "equiv_ms": float(d[jx] / (2 * math.pi * FT) * 1000.0)})
        last = jx
    return res


def stop_analysis(tt: ToneTrack, ref_level: float, t_hint: float) -> Dict[str, Any]:
    """When does the tone end? ``t_hint`` = rough time (recording clock) of the Stop command."""
    out: Dict[str, Any] = {}
    if tt.t.size == 0 or not (ref_level > 0):
        return out
    m = tt.t >= t_hint - 1.0
    t, amp = tt.t[m], tt.amp[m]
    if t.size < 10:
        return out
    n_tail = max(5, int(1.0 / tt.hop_s))
    noise = float(np.median(amp[-n_tail:]))
    out["noise_db"] = 20 * math.log10(max(noise, 1e-9))
    out["tone_db"] = 20 * math.log10(max(ref_level, 1e-9))
    out["tone_snr_db"] = out["tone_db"] - out["noise_db"]
    for name, frac in (("silence_t_6db", 0.5), ("silence_t_20db", 0.1)):
        thr = ref_level * frac
        if thr < 2.0 * noise:
            out[name] = None
            continue
        above = np.flatnonzero(amp >= thr)
        if above.size == 0:
            out[name] = None
            continue
        last = int(above[-1])
        if last >= t.size - 3:
            out[name] = None
            out["sound_until_end"] = True
        else:
            a0, a1 = float(amp[last]), float(amp[last + 1])
            f = (a0 - thr) / (a0 - a1) if a0 > a1 else 0.0
            out[name] = float(t[last] + f * (t[last + 1] - t[last]))
    return out


# ------------------------------------------------------------------ high level

def analyze_files(paths: Sequence[Any], snr_db: float = DEFAULT_SNR_DB, tone: bool = True,
                  stop_hint: Optional[float] = None, progress=None) -> Dict[str, Any]:
    """Full analysis of one recording (one or more contiguous chunks)."""
    tl = Timeline(paths)
    try:
        clicks = detect_clicks_timeline(tl, snr_db=snr_db, progress=progress)
        idx, info = assign_indices(clicks)
        fit = fit_clicks(idx)
        res: Dict[str, Any] = {"fs": tl.fs, "duration_s": tl.duration, "n_detections": len(clicks),
                               "assign": info, "fit": fit, "clicks": [c.as_dict() for c in idx]}
        if fit.get("ok") and tone:
            t_first = min(c.t for c in idx)
            t_last = max(c.t for c in idx)
            lead = max(0.0, t_first - 1.0)
            tt = tone_track(tl, idx, t_from=max(0.0, t_first - 2.0), t_to=min(tl.duration, t_last + 8.0))
            # noise at 440 Hz while the stream is silent: before the first click, else after the end
            noise_amp = None
            pre = tt.amp[(tt.t < t_first - 0.5)]
            if pre.size >= 50:
                noise_amp = float(np.median(pre))
            ev = tone_events(tt, t_first + 0.02, t_last - 0.02, noise_amp=noise_amp)
            res["tone"] = ev
            res["tone_ref_db"] = ev["ref_db"]
            ref = 10.0 ** (ev["ref_db"] / 20.0) if ev["ref_db"] == ev["ref_db"] else 0.0
            if ref > 0 and tt.t.size > 10:
                ok = (tt.amp > 0.5 * ref)
                run = np.convolve(ok.astype(np.int32), np.ones(5, dtype=np.int32), mode="valid") == 5
                first = np.flatnonzero(run)
                # window centre where the tone reaches half amplitude = moment the tone starts
                res["tone_first_sound_t"] = float(tt.t[first[0]]) if first.size else None
                hint = stop_hint if stop_hint is not None else t_last + 0.5
                res["stop"] = stop_analysis(tt, ref, hint)
        return res
    finally:
        tl.close()
