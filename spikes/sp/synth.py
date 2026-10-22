"""Synthetic recordings of the test signal (numpy) for tests and the fake recorder."""
from __future__ import annotations

from typing import Iterable, List, Optional, Sequence, Tuple

import numpy as np

from . import testsignal as ts
from . import wavio


def click_track_f32(fs: int, start_sample: int, n: int) -> np.ndarray:
    """Mono float32 click track (click k at sample k*fs) for [start, start+n)."""
    return np.asarray(ts.track(fs).mono_i16(start_sample, n), dtype=np.float32) / 32768.0


def synth_recording(fs: int = 48000, duration: float = 30.0, delay_s: float = 1.234, gain: float = 0.05,
                    noise_dbfs: float = -60.0, dropouts: Sequence[Tuple[float, float]] = (),
                    clock_ppm: float = 0.0, seed: int = 1, stop_at: Optional[float] = None,
                    tone_gain: float = 1.0, extra_events: Sequence[Tuple[float, float]] = (),
                    skips: Sequence[Tuple[float, float]] = ()) -> np.ndarray:
    """A "recording" of the click track: delayed, scaled, noisy.

    delay_s     time before the first click is heard (any real number; sub-sample by linear interp)
    clock_ppm   playback clock error: the Nth click arrives at delay + N*(1 + ppm*1e-6)
    dropouts    list of (start_s, duration_s): signal muted (noise floor stays)
    stop_at     signal ends at this time (hard stop)
    extra_events list of (time_s, level_dbfs) impulsive noise bursts (door slam, voice)
    skips       list of (time_s, skip_s): from time_s on the source jumps ahead by skip_s
                (a stutter that drops audio; negative = repeats audio)
    """
    n = int(round(duration * fs))
    t = np.arange(n, dtype=np.float64) / fs
    # source time for every output sample
    src_t = (t - delay_s) / (1.0 + clock_ppm * 1e-6)
    for (ts_, sk) in skips:
        src_t = src_t + np.where(t >= ts_, sk, 0.0)
    src_idx = src_t * fs
    need = int(max(1, np.ceil(src_idx.max()) + 4))
    base = click_track_f32(fs, 0, max(need, 1))
    sig = np.interp(src_idx, np.arange(base.size, dtype=np.float64), base, left=0.0, right=0.0).astype(np.float32)
    sig[src_idx < 0] = 0.0
    sig = sig * np.float32(gain)
    if tone_gain != 1.0:
        # scale only the tone: subtract the click part by recomputing is overkill; apply to whole for simplicity
        sig = sig * np.float32(tone_gain)
    for (d0, dd) in dropouts:
        a, b = int(round(d0 * fs)), int(round((d0 + dd) * fs))
        sig[a:b] = 0.0
    if stop_at is not None:
        sig[int(round(stop_at * fs)):] = 0.0
    rng = np.random.default_rng(seed)
    noise = rng.standard_normal(n).astype(np.float32) * np.float32(10.0 ** (noise_dbfs / 20.0))
    for (t0, lvl) in extra_events:
        a = int(round(t0 * fs))
        m = int(0.02 * fs)
        burst = rng.standard_normal(m).astype(np.float32) * np.float32(10.0 ** (lvl / 20.0))
        noise[a:a + m] += burst[:max(0, min(m, n - a))]
    return sig + noise


def to_i16(x: np.ndarray) -> np.ndarray:
    return np.clip(np.round(x * 32768.0), -32768, 32767).astype("<i2")


def write_wav_f32(path: str, x: np.ndarray, fs: int) -> None:
    wavio.write_pcm16(path, to_i16(x).tobytes(), fs, 1)


class MockScene:
    """What a microphone next to the mock renderer would hear (for SynthRecorder).

    Sound of play #i is audible from ``t_audible_start`` until ``t_audible_end``
    (set when Stop arrives); stream sample s is heard at
    ``t_audible_start + codec_delay + s / fs``. Plus white noise at ``noise_dbfs``.
    """

    def __init__(self, mock, fs: int = 48000, gain: float = 0.04, noise_dbfs: float = -62.0, seed: int = 11):
        self.mock, self.fs, self.gain = mock, fs, gain
        self.noise = 10.0 ** (noise_dbfs / 20.0)
        self.rng = np.random.default_rng(seed)
        self.rec_latency = 0.0

    def render(self, t_start: float, n: int, fs: int) -> np.ndarray:
        out = (self.rng.standard_normal(n) * self.noise).astype(np.float32)
        t_end = t_start + n / float(fs)
        for rec in list(self.mock.truth):
            a0 = rec.get("t_audible_start")
            if a0 is None:
                continue
            a1 = rec.get("t_audible_end")
            if a0 >= t_end or (a1 is not None and a1 <= t_start):
                continue
            d = rec.get("codec_delay_s", 0.0)
            # stream sample for every output sample
            rate = 1.0 + rec.get("rate_ppm", 0.0) * 1e-6
            s0 = (t_start - a0 - d) * fs / rate
            s = np.rint(s0 + np.arange(n) / rate).astype(np.int64)
            valid = s >= 0
            if a1 is not None:
                tt = t_start + np.arange(n) / float(fs)
                valid &= tt < a1
            tt0 = t_start + np.arange(n) / float(fs)
            valid &= tt0 >= a0
            if not valid.any():
                continue
            smin, smax = int(s[valid].min()), int(s[valid].max())
            base = click_track_f32(fs, smin, smax - smin + 1)
            seg = np.zeros(n, np.float32)
            seg[valid] = base[s[valid] - smin]
            out += seg * np.float32(self.gain)
        return out
