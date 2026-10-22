"""Deterministic test signal with exact sample positions.

PCM 44.1 kHz / 16-bit / stereo (both channels identical):

* click k starts at sample k * 44100 (one per second), 5 ms long, sharp attack,
  2 kHz, except every 10th click (k % 10 == 0) which is 4 kHz, so the index k
  can be recovered from a recording;
* a continuous quiet 440 Hz tone (-30 dBFS) runs underneath, so stutters,
  gaps and skips are detectable between the clicks.

440 Hz is exactly 440 cycles per 44100 samples, so the whole signal is periodic
with 1 s except for the click frequency, i.e. two pre-computed one-second
templates (normal / marker) are enough to generate any position instantly.
"""
from __future__ import annotations

import math
import sys
from array import array
from typing import Dict, Optional

FS = 44100
CHANNELS = 2
BYTES_PER_FRAME = 4          # 16-bit stereo
BYTES_PER_SECOND = FS * BYTES_PER_FRAME

CLICK_SECONDS = 0.005        # 5 ms burst
CLICK_ATTACK_S = 0.00015     # 0.15 ms raised-cosine attack (sharp)
CLICK_RELEASE_S = 0.001      # 1 ms release to avoid splatter
CLICK_AMP = 0.5              # -6 dBFS
TONE_HZ = 440.0
TONE_DBFS = -30.0
CLICK_HZ_NORMAL = 2000.0
CLICK_HZ_MARK = 4000.0
MARK_EVERY = 10

# WAV "endless" stream: sizes just under 2**31 (see wav_header)
WAV_HEADER_SIZE = 44
WAV_MAX_DATA = ((2 ** 31 - 1 - WAV_HEADER_SIZE) // BYTES_PER_FRAME) * BYTES_PER_FRAME  # 2147483600
WAV_CONTENT_LENGTH = WAV_HEADER_SIZE + WAV_MAX_DATA                                    # 2147483644


def click_hz(k: int) -> float:
    return CLICK_HZ_MARK if k % MARK_EVERY == 0 else CLICK_HZ_NORMAL


def click_len(fs: int = FS) -> int:
    return int(round(CLICK_SECONDS * fs))


def _to_i16(v: float) -> int:
    x = int(round(v * 32767.0))
    return -32768 if x < -32768 else (32767 if x > 32767 else x)


def second_mono(mark: bool, fs: int = FS) -> array:
    """One second of the mono signal (click at sample 0 of the second)."""
    tone_amp = 10.0 ** (TONE_DBFS / 20.0)
    vals = [tone_amp * math.sin(2.0 * math.pi * TONE_HZ * i / fs) for i in range(fs)]
    f = CLICK_HZ_MARK if mark else CLICK_HZ_NORMAL
    n = click_len(fs)
    att = max(2, int(round(CLICK_ATTACK_S * fs)))
    rel = max(2, int(round(CLICK_RELEASE_S * fs)))
    for m in range(n):
        env = 1.0
        if m < att:
            env = 0.5 - 0.5 * math.cos(math.pi * (m + 1) / (att + 1))
        if m >= n - rel:
            env *= 0.5 + 0.5 * math.cos(math.pi * (m - (n - rel) + 1) / (rel + 1))
        vals[m] += CLICK_AMP * env * math.sin(2.0 * math.pi * f * m / fs)
    return array("h", [_to_i16(v) for v in vals])


def _stereo_bytes(mono: array) -> bytes:
    st = array("h", bytes(len(mono) * 4))
    st[0::2] = mono
    st[1::2] = mono
    if sys.byteorder == "big":  # pragma: no cover (Apple Silicon / x86 are little endian)
        st.byteswap()
    return st.tobytes()


class ClickTrack:
    """Random access to the stereo test signal at a given sample rate."""

    def __init__(self, fs: int = FS):
        self.fs = fs
        self.mono_normal = second_mono(False, fs)
        self.mono_mark = second_mono(True, fs)
        self._st = {False: _stereo_bytes(self.mono_normal), True: _stereo_bytes(self.mono_mark)}
        self.bytes_per_second = fs * 4

    def pcm(self, start_sample: int, n_samples: int) -> bytes:
        """Stereo int16 little-endian interleaved bytes for [start, start + n)."""
        out = []
        fs = self.fs
        pos = start_sample
        left = n_samples
        while left > 0:
            sec, off = divmod(pos, fs)
            take = min(left, fs - off)
            tpl = self._st[sec % MARK_EVERY == 0]
            out.append(tpl[off * 4:(off + take) * 4])
            pos += take
            left -= take
        return b"".join(out)

    def mono_i16(self, start_sample: int, n_samples: int) -> array:
        """Mono int16 array (native order) for [start, start + n)."""
        out = array("h")
        fs = self.fs
        pos = start_sample
        left = n_samples
        while left > 0:
            sec, off = divmod(pos, fs)
            take = min(left, fs - off)
            tpl = self.mono_mark if sec % MARK_EVERY == 0 else self.mono_normal
            out.extend(tpl[off:off + take])
            pos += take
            left -= take
        return out


_TRACKS: Dict[int, ClickTrack] = {}


def track(fs: int = FS) -> ClickTrack:
    t = _TRACKS.get(fs)
    if t is None:
        t = _TRACKS[fs] = ClickTrack(fs)
    return t


def wav_header(data_size: int, fs: int = FS, channels: int = CHANNELS, bits: int = 16) -> bytes:
    """44-byte canonical PCM WAV header for ``data_size`` payload bytes."""
    import struct
    block = channels * bits // 8
    return (b"RIFF" + struct.pack("<I", 36 + data_size) + b"WAVE" +
            b"fmt " + struct.pack("<IHHIIHH", 16, 1, channels, fs, fs * block, block, bits) +
            b"data" + struct.pack("<I", data_size))


def endless_wav_header() -> bytes:
    """Header for the 'endless' WAV stream: RIFF and data sizes just under 2**31."""
    return wav_header(WAV_MAX_DATA)


def write_wav(path: str, seconds: float, start_sample: int = 0, fs: int = FS) -> int:
    """Write ``seconds`` of the test signal as a normal WAV file; returns sample count."""
    n = int(round(seconds * fs))
    tr = track(fs)
    with open(path, "wb") as f:
        f.write(wav_header(n * 4, fs))
        pos = start_sample
        left = n
        while left > 0:
            take = min(left, fs * 10)
            f.write(tr.pcm(pos, take))
            pos += take
            left -= take
    return n


def expected_click_samples(k: int, fs: int = FS) -> int:
    return k * fs


if __name__ == "__main__":  # tiny smoke test
    tr = track()
    b = tr.pcm(0, FS * 2)
    assert len(b) == FS * 2 * 4
    print("ok", len(b), "bytes; header", len(endless_wav_header()), "WAV_CONTENT_LENGTH", WAV_CONTENT_LENGTH)
