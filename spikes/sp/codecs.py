"""MP3 (streaming LAME) and AAC (pre-encoded ADTS) helpers plus frame parsers.

Pacing needs an exact map from bytes to audio time, so everything that is sent
is cut into whole codec frames: MP3 frames are 1152 samples, ADTS AAC frames
1024 samples, FLAC frames 4096 samples, WAV is 4 bytes per sample.
"""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import threading
import time
from pathlib import Path
from typing import Dict, Iterator, List, Optional, Tuple

from . import flac as flacmod
from . import testsignal as ts
from .common import CACHE_DIR, have_cmd, mono

# ------------------------------------------------------------------ MPEG audio layer III

_MP3_BITRATES = {  # MPEG-1 layer III kbit/s by index
    1: 32, 2: 40, 3: 48, 4: 56, 5: 64, 6: 80, 7: 96, 8: 112, 9: 128, 10: 160, 11: 192, 12: 224, 13: 256, 14: 320}
_MP3_RATES = {0: 44100, 1: 48000, 2: 32000}
MP3_SAMPLES_PER_FRAME = 1152


def mp3_frame_len(h: bytes) -> Optional[Tuple[int, int, int]]:
    """Parse a 4-byte MPEG-1 layer III header -> (frame_bytes, samples, sample_rate) or None."""
    if len(h) < 4 or h[0] != 0xFF or (h[1] & 0xE0) != 0xE0:
        return None
    ver = (h[1] >> 3) & 3
    layer = (h[1] >> 1) & 3
    if ver != 3 or layer != 1:          # MPEG-1 only, layer III only
        return None
    br = _MP3_BITRATES.get(h[2] >> 4)
    sr = _MP3_RATES.get((h[2] >> 2) & 3)
    if br is None or sr is None:
        return None
    pad = (h[2] >> 1) & 1
    return 144 * br * 1000 // sr + pad, MP3_SAMPLES_PER_FRAME, sr


class FrameSplitter:
    """Incremental splitter turning a byte stream into whole frames (MP3 or ADTS)."""

    def __init__(self, kind: str):
        assert kind in ("mp3", "aac")
        self.kind = kind
        self.buf = bytearray()
        self.lost_sync_bytes = 0

    def feed(self, data: bytes) -> List[Tuple[bytes, int]]:
        self.buf += data
        out: List[Tuple[bytes, int]] = []
        while True:
            if len(self.buf) < 7:
                break
            info = self._parse(self.buf)
            if info is None:
                # resync: drop bytes up to the next plausible sync byte
                nxt = self.buf.find(b"\xff", 1)
                drop = len(self.buf) if nxt < 0 else nxt
                self.lost_sync_bytes += drop
                del self.buf[:drop]
                continue
            flen, samples = info
            if len(self.buf) < flen:
                break
            out.append((bytes(self.buf[:flen]), samples))
            del self.buf[:flen]
        return out

    def _parse(self, b: bytearray) -> Optional[Tuple[int, int]]:
        if self.kind == "mp3":
            r = mp3_frame_len(bytes(b[:4]))
            return (r[0], r[1]) if r else None
        r2 = adts_frame_len(bytes(b[:7]))
        return r2


# ------------------------------------------------------------------ ADTS AAC

def adts_frame_len(h: bytes) -> Optional[Tuple[int, int]]:
    """Parse a 7-byte ADTS header -> (frame_bytes, samples) or None."""
    if len(h) < 7 or h[0] != 0xFF or (h[1] & 0xF6) != 0xF0:
        return None
    flen = ((h[3] & 0x03) << 11) | (h[4] << 3) | (h[5] >> 5)
    blocks = (h[6] & 0x03) + 1
    if flen < 7:
        return None
    return flen, 1024 * blocks


# ------------------------------------------------------------------ LAME streaming encoder

LAME_ARGS = ["-r", "-s", "44.1", "--bitwidth", "16", "--signed", "--little-endian", "-m", "s",
             "--cbr", "-b", "320", "-t", "--noreplaygain", "--silent", "-", "-"]


class Mp3Encoder:
    """Long-running ``lame`` fed with the test signal; yields whole MP3 frames.

    320 kbit/s CBR, raw stereo input on stdin, no Xing/INFO tag, no ID3.
    The feeder thread is not paced (pipe back-pressure keeps it bounded); the
    caller paces the *output* frames.
    """

    def __init__(self, start_sample: int = 0, track: Optional[ts.ClickTrack] = None, max_seconds: Optional[float] = None):
        exe = have_cmd("lame")
        if not exe:
            raise RuntimeError("lame not found (brew install lame)")
        self.track = track or ts.track()
        self.pos = start_sample
        self.max_samples = None if max_seconds is None else int(max_seconds * ts.FS) + start_sample
        self.proc = subprocess.Popen([exe] + LAME_ARGS, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                     stderr=subprocess.DEVNULL, bufsize=0)
        self._stop = False
        self.splitter = FrameSplitter("mp3")
        self.thread = threading.Thread(target=self._feed, name="lame-feeder", daemon=True)
        self.thread.start()

    def _feed(self) -> None:
        try:
            while not self._stop:
                n = ts.FS // 2
                if self.max_samples is not None:
                    n = min(n, self.max_samples - self.pos)
                    if n <= 0:
                        break
                self.proc.stdin.write(self.track.pcm(self.pos, n))
                self.pos += n
        except (BrokenPipeError, ValueError, OSError):
            pass
        finally:
            try:
                self.proc.stdin.close()
            except Exception:
                pass

    def frames(self) -> Iterator[Tuple[bytes, int]]:
        fd = self.proc.stdout.fileno()
        while True:
            data = os.read(fd, 65536)
            if not data:
                return
            for fr in self.splitter.feed(data):
                yield fr

    def close(self) -> None:
        self._stop = True
        try:
            self.proc.kill()
        except Exception:
            pass
        try:
            self.proc.wait(timeout=2)
        except Exception:
            pass


def mp3_encode_seconds(seconds: float, path: Path) -> int:
    """Encode ``seconds`` of the test signal with the streaming encoder to a file; returns frames."""
    enc = Mp3Encoder(max_seconds=seconds)
    n = 0
    with open(path, "wb") as f:
        for data, _s in enc.frames():
            f.write(data)
            n += 1
    enc.close()
    return n


# ------------------------------------------------------------------ AAC cache (afconvert)

AAC_BITRATE = 256000
AAC_TIERS_S = (600, 4200, 8400, 15000)      # cache file lengths in seconds (10 min, 70 min, 2h20, 4h10)


def aac_cache_path(seconds_tier: int, cache_dir: Path = CACHE_DIR) -> Path:
    return cache_dir / ("aac%dk_%ds.aac" % (AAC_BITRATE // 1000, seconds_tier))


def ensure_aac(seconds_needed: float, cache_dir: Path = CACHE_DIR, log=print) -> Path:
    """Return a cached ADTS AAC file of the click track covering ``seconds_needed``.

    afconvert cannot stream, so a long file is encoded once (about 100x real
    time) and then served frame by frame.
    """
    afc = have_cmd("afconvert")
    if not afc:
        raise RuntimeError("afconvert not found (macOS only)")
    cache_dir.mkdir(parents=True, exist_ok=True)
    # any existing file that is long enough will do
    for tier in AAC_TIERS_S:
        p = aac_cache_path(tier, cache_dir)
        if tier >= seconds_needed and p.exists() and p.stat().st_size > 0:
            return p
    tier = next((t for t in AAC_TIERS_S if t >= seconds_needed), int(seconds_needed) + 60)
    out = aac_cache_path(tier, cache_dir)
    wav = cache_dir / ("tmp_click_%ds.wav" % tier)
    log("building AAC cache %s (%d s) - one time, takes a while for long tiers" % (out.name, tier))
    t0 = time.time()
    n = tier * ts.FS
    tr = ts.track()
    with open(wav, "wb") as f:
        f.write(ts.wav_header(n * 4))
        pos = 0
        while pos < n:
            take = min(ts.FS * 30, n - pos)
            f.write(tr.pcm(pos, take))
            pos += take
    tmp = out.with_suffix(".part")
    r = subprocess.run([afc, "-f", "adts", "-d", "aac", "-b", str(AAC_BITRATE), "-s", "0", str(wav), str(tmp)],
                       capture_output=True, text=True)
    try:
        wav.unlink()
    except OSError:
        pass
    if r.returncode != 0:
        raise RuntimeError("afconvert failed: %s" % r.stderr.strip()[:300])
    tmp.rename(out)
    log("AAC cache ready: %s (%.1f MB, %.0f s)" % (out.name, out.stat().st_size / 1e6, time.time() - t0))
    return out


def aac_frames(path: Path) -> Iterator[Tuple[bytes, int]]:
    """Yield (frame bytes, samples) from an ADTS file."""
    import mmap
    with open(path, "rb") as f:
        mm = mmap.mmap(f.fileno(), 0, access=mmap.ACCESS_READ)
        try:
            pos = 0
            size = len(mm)
            while pos + 7 <= size:
                info = adts_frame_len(mm[pos:pos + 7])
                if info is None:
                    nxt = mm.find(b"\xff", pos + 1)
                    if nxt < 0:
                        return
                    pos = nxt
                    continue
                flen, samples = info
                if pos + flen > size:
                    return
                yield mm[pos:pos + flen], samples
                pos += flen
        finally:
            mm.close()


# ------------------------------------------------------------------ byte -> audio time (for the mock renderer / tests)

class StreamCounter:
    """Counts audio samples in a byte stream of any of our formats (payload only, de-chunked)."""

    def __init__(self, fmt: str):
        self.fmt = fmt
        self.bytes = 0
        self.samples = 0
        self.hdr = 0
        self._wav_bytes = 0
        if fmt in ("mp3", "aac"):
            self.split = FrameSplitter(fmt)
        elif fmt in ("flac-chunked", "flac-nolen"):
            self.flac = flacmod.FrameWalker()

    def feed(self, data: bytes) -> int:
        self.bytes += len(data)
        if self.fmt == "wav":
            if self.hdr < ts.WAV_HEADER_SIZE:
                take = min(len(data), ts.WAV_HEADER_SIZE - self.hdr)
                self.hdr += take
                data = data[take:]
            self._wav_bytes += len(data)
            self.samples = self._wav_bytes // 4
        elif self.fmt in ("mp3", "aac"):
            for _d, s in self.split.feed(data):
                self.samples += s
        else:
            self.samples = self.flac.feed(data)
        return self.samples


# ------------------------------------------------------------------ codec delay store

DELAY_FILE = "codec_delays.json"


def _decode_with(cmd: List[str], out: Path) -> bool:
    r = subprocess.run(cmd, capture_output=True, text=True)
    return r.returncode == 0 and out.exists()


def measure_codec_delays(work: Path, seconds: int = 24, log=print) -> Dict[str, Dict[str, float]]:
    """Encode -> decode -> locate the clicks. Needs numpy (via sp.analysis).

    The delay is the offset of click k from k seconds in the decoded output,
    i.e. what the encoder delay + the decoder's own delay add up to for that
    decoder. Recorded per decoder; the analyses subtract ``used_s``.
    """
    from . import analysis as an
    work.mkdir(parents=True, exist_ok=True)
    res: Dict[str, Dict[str, float]] = {}

    def delay_of(wav: Path) -> Optional[float]:
        r = an.analyze_files([wav], tone=False)
        f = r["fit"]
        if not f.get("ok") or f["n_clicks"] < seconds - 4:
            return None
        return float(f["t0"])

    # --- MP3 (streaming lame, exactly the stream the server sends)
    mp3 = work / "delay_test.mp3"
    mp3_encode_seconds(seconds, mp3)
    d: Dict[str, float] = {}
    lame = have_cmd("lame")
    if lame:
        out = work / "mp3_lame.wav"
        if _decode_with([lame, "--silent", "--decode", str(mp3), str(out)], out):
            v = delay_of(out)
            if v is not None:
                d["lame_decode_s"] = v
    afc = have_cmd("afconvert")
    if afc:
        out = work / "mp3_afc.wav"
        if _decode_with([afc, "-f", "WAVE", "-d", "LEI16", str(mp3), str(out)], out):
            v = delay_of(out)
            if v is not None:
                d["afconvert_decode_s"] = v
    if d:
        d["used_s"] = sorted(d.values())[len(d) // 2] if len(d) > 1 else list(d.values())[0]
        d["used_samples"] = round(d["used_s"] * ts.FS)
        res["mp3"] = d
        log("mp3 encoder+decoder delay: " + ", ".join("%s=%.3f ms" % (k, v * 1000) for k, v in d.items() if k.endswith("_s")))

    # --- AAC (afconvert encode of the same signal, ADTS)
    if afc:
        src = work / "delay_src.wav"
        ts.write_wav(str(src), seconds)
        aac = work / "delay_test.aac"
        r = subprocess.run([afc, "-f", "adts", "-d", "aac", "-b", str(AAC_BITRATE), "-s", "0", str(src), str(aac)],
                           capture_output=True, text=True)
        if r.returncode == 0:
            out = work / "aac_afc.wav"
            if _decode_with([afc, "-f", "WAVE", "-d", "LEI16", str(aac), str(out)], out):
                v = delay_of(out)
                if v is not None:
                    res["aac"] = {"afconvert_decode_s": v, "used_s": v, "used_samples": round(v * ts.FS)}
                    log("aac encoder+decoder delay: afconvert_decode=%.3f ms" % (v * 1000))
    return res


def codec_delays(cache_dir: Path = CACHE_DIR, refresh: bool = False, log=print) -> Dict[str, Dict[str, float]]:
    """Stored delays (measured on first use, needs numpy); {} if they cannot be measured."""
    p = cache_dir / DELAY_FILE
    if p.exists() and not refresh:
        try:
            return json.loads(p.read_text())
        except Exception:
            pass
    try:
        res = measure_codec_delays(cache_dir / "delay_work", log=log)
    except ImportError:
        return {}
    except Exception as e:  # pragma: no cover
        log("could not measure codec delays: %r" % (e,))
        return {}
    if res:
        cache_dir.mkdir(parents=True, exist_ok=True)
        p.write_text(json.dumps(res, indent=2))
        shutil.rmtree(cache_dir / "delay_work", ignore_errors=True)
    return res


def codec_delay_s(fmt: str, cache_dir: Path = CACHE_DIR) -> float:
    """Encoder + decoder delay in seconds for a stream format (0 for wav / flac)."""
    key = "mp3" if fmt == "mp3" else ("aac" if fmt == "aac" else None)
    if key is None:
        return 0.0
    d = codec_delays(cache_dir, log=lambda *_: None)
    return float(d.get(key, {}).get("used_s", 0.0))
