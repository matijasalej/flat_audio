"""Recording: sox `rec` (or a synthetic stand-in for tests) -> exact wav chunks + clock mapping.

The recorder reads raw 16-bit mono PCM from a pipe and writes the wav files
itself, so that (a) chunk boundaries are sample-exact and (b) every read can be
timestamped with ``time.monotonic()``. A read can never happen before the
audio was captured, so the *lower envelope* of ``t_read - n_samples/fs`` is the
best estimate of when sample 0 was captured (plus the unknown, constant input
latency of the capture chain, typically 10-40 ms; it cancels in trial-to-trial
comparisons and is the main residual uncertainty of absolute offsets).
"""
from __future__ import annotations

import collections
import os
import signal
import subprocess
import threading
import time
from pathlib import Path
from typing import Any, Callable, Deque, Dict, List, Optional, Tuple

from .common import have_cmd, mono
from .wavio import WavChunkWriter


class RecInfo:
    """A finished recording and its mapping to the monotonic clock."""

    def __init__(self, paths: List[Path], fs: int, n_samples: int, reads_t: List[float], reads_n: List[int],
                 t_call: float):
        import numpy as np
        self.paths, self.fs, self.n_samples, self.t_call = paths, fs, n_samples, t_call
        self.reads_t = np.asarray(reads_t, dtype=np.float64)
        self.reads_n = np.asarray(reads_n, dtype=np.float64)
        self.t_origin = float("nan")      # monotonic time of sample 0 (+ constant input latency)
        self.ratio = 1.0                  # monotonic seconds per recorded second
        self.clock_scatter_ms = float("nan")
        self.clock_fit = "none"
        self._fit()

    def _fit(self) -> None:
        import numpy as np
        if self.reads_t.size < 3:
            return
        x = self.reads_n / float(self.fs)               # recorded seconds at the end of each read
        y = self.reads_t
        dur = float(x[-1])
        # lower envelope: per window the read with the smallest (t_read - x)
        b0 = 1.0
        nwin = max(1, int(dur // 20.0))
        if dur >= 120.0 and nwin >= 6:
            for _ in range(3):
                idx = []
                edges = np.linspace(0, x.size, nwin + 1).astype(int)
                for a, b in zip(edges[:-1], edges[1:]):
                    if b > a:
                        seg = y[a:b] - b0 * x[a:b]
                        idx.append(a + int(np.argmin(seg)))
                idx = np.asarray(idx)
                b1, a1 = np.polyfit(x[idx], y[idx], 1)
                b0 = float(b1)
            self.ratio, self.t_origin = b0, float(a1)
            res = y[idx] - (a1 + b1 * x[idx])
            self.clock_scatter_ms = float(np.std(res) * 1000.0)
            self.clock_fit = "regression of per-20s minimum read latency (%d points)" % idx.size
        else:
            self.ratio = 1.0
            self.t_origin = float(np.min(y - x))
            self.clock_fit = "offset only (lower envelope of read times; recording too short for a rate fit)"
            self.clock_scatter_ms = float(np.percentile(y - x - self.t_origin, 50) * 1000.0)

    # -- mapping ------------------------------------------------------------------
    def to_mono(self, t_rec: float) -> float:
        return self.t_origin + t_rec * self.ratio

    def to_rec(self, t_mono: float) -> float:
        return (t_mono - self.t_origin) / self.ratio

    @property
    def duration(self) -> float:
        return self.n_samples / float(self.fs)

    @property
    def rate_ppm(self) -> float:
        return (self.ratio - 1.0) * 1e6


class PipeRecorder:
    """Reads raw s16le mono from a file descriptor; subclasses provide the source."""

    def __init__(self, out_dir: Path, name: str, fs: int = 48000, chunk_seconds: Optional[float] = None,
                 recent_seconds: float = 90.0):
        self.out_dir, self.name, self.fs = Path(out_dir), name, fs
        self.chunk_samples = int(chunk_seconds * fs) if chunk_seconds else None
        self.recent_seconds = recent_seconds
        self.out_dir.mkdir(parents=True, exist_ok=True)
        self.paths: List[Path] = []
        self._writer: Optional[WavChunkWriter] = None
        self._chunk_idx = 0
        self._chunk_fill = 0
        self.n_samples = 0
        self.reads_t: List[float] = []
        self.reads_n: List[int] = []
        self.t_call = 0.0
        self._recent: Deque[Tuple[int, bytes]] = collections.deque()
        self._recent_samples = 0
        self._lock = threading.Lock()
        self._thread: Optional[threading.Thread] = None
        self._fd = -1
        self._carry = b""
        self._first_data = threading.Event()
        self.error: Optional[str] = None
        self.stderr_text = ""

    # -- subclass hooks -------------------------------------------------------------
    def _open_source(self) -> int:
        raise NotImplementedError

    def _close_source(self) -> None:
        pass

    # -- API ------------------------------------------------------------------------
    def start(self, wait: float = 10.0) -> None:
        self.t_call = mono()
        self._fd = self._open_source()
        self._thread = threading.Thread(target=self._reader, name="recorder", daemon=True)
        self._thread.start()
        if not self._first_data.wait(wait):
            self.stop()
            raise RuntimeError("no audio arrived from the recorder within %.0f s. %s" % (wait, self.stderr_text.strip()))

    def stop(self) -> RecInfo:
        self._close_source()
        if self._thread:
            self._thread.join(timeout=5)
        with self._lock:
            if self._writer:
                self._writer.close()
                self._writer = None
        return RecInfo(list(self.paths), self.fs, self.n_samples, self.reads_t, self.reads_n, self.t_call)

    def recent(self, seconds: float):
        """Last ``seconds`` of audio as (float32 array, recording-time of its first sample)."""
        import numpy as np
        with self._lock:
            blocks = list(self._recent)
        if not blocks:
            return np.zeros(0, np.float32), 0.0
        want = int(seconds * self.fs)
        data = np.frombuffer(b"".join(b for _e, b in blocks), dtype="<i2")
        end = blocks[-1][0]
        data = data[-want:]
        return data.astype(np.float32) / 32768.0, (end - data.size) / float(self.fs)

    # -- internals ------------------------------------------------------------------
    def _chunk_path(self) -> Path:
        if self.chunk_samples:
            return self.out_dir / ("%s_%03d.wav" % (self.name, self._chunk_idx))
        return self.out_dir / ("%s.wav" % self.name)

    def _open_chunk(self) -> None:
        p = self._chunk_path()
        self._writer = WavChunkWriter(str(p), self.fs, 1)
        self.paths.append(p)
        self._chunk_fill = 0

    def _write(self, data: bytes) -> None:
        with self._lock:
            while data:
                if self._writer is None:
                    self._open_chunk()
                if self.chunk_samples:
                    room = (self.chunk_samples - self._chunk_fill) * 2
                    part, data = data[:room], data[room:]
                else:
                    part, data = data, b""
                self._writer.write(part)
                self._chunk_fill += len(part) // 2
                if self.chunk_samples and self._chunk_fill >= self.chunk_samples:
                    self._writer.close()
                    self._writer = None
                    self._chunk_idx += 1

    def _reader(self) -> None:
        fd = self._fd
        try:
            while True:
                data = os.read(fd, 16384)
                t = mono()
                if not data:
                    break
                data = self._carry + data
                if len(data) % 2:
                    self._carry = data[-1:]
                    data = data[:-1]
                else:
                    self._carry = b""
                if not data:
                    continue
                self._write(data)
                ns = len(data) // 2
                self.n_samples += ns
                self.reads_t.append(t)
                self.reads_n.append(self.n_samples)
                with self._lock:
                    self._recent.append((self.n_samples, data))
                    self._recent_samples += ns
                    while self._recent_samples - len(self._recent[0][1]) // 2 > self.recent_seconds * self.fs:
                        old = self._recent.popleft()
                        self._recent_samples -= len(old[1]) // 2
                self._first_data.set()
        except OSError as e:
            self.error = repr(e)


class SoxRecorder(PipeRecorder):
    """`rec` (sox) from the default input device -> raw pipe."""

    def __init__(self, *a, device: Optional[str] = None, **kw):
        super().__init__(*a, **kw)
        self.device = device
        self.proc: Optional[subprocess.Popen] = None

    def _open_source(self) -> int:
        rec = have_cmd("rec")
        if not rec:
            raise RuntimeError("`rec` not found (brew install sox)")
        # -D: no dither, so that a muted / permission-blocked mic stays digital silence instead of +-1 LSB noise
        env = dict(os.environ)
        if self.device:
            env["AUDIODEV"] = self.device
        cmd = [rec, "-q", "-D", "--buffer", "1024", "-r", str(self.fs), "-c", "1", "-b", "16", "-e", "signed-integer", "-t", "raw", "-"]
        self.proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env, bufsize=0)

        def drain_err() -> None:
            assert self.proc and self.proc.stderr
            try:
                self.stderr_text = self.proc.stderr.read().decode("utf-8", "replace")
            except Exception:
                pass

        threading.Thread(target=drain_err, daemon=True).start()
        return self.proc.stdout.fileno()

    def _close_source(self) -> None:
        if self.proc and self.proc.poll() is None:
            try:
                self.proc.send_signal(signal.SIGINT)
                self.proc.wait(timeout=3)
            except Exception:
                try:
                    self.proc.kill()
                except Exception:
                    pass


class SynthRecorder(PipeRecorder):
    """Test stand-in for `rec`: renders what the mock renderer would make audible, in real time.

    ``render(t_start_mono, n_samples, fs) -> float32 array`` supplies the acoustic scene.
    The "capture chain" delivers each block ``latency_s`` (+ jitter) after the
    last sample of the block was captured, like a real input device.
    """

    def __init__(self, *a, render: Callable[[float, int, int], Any], latency_s: float = 0.018, jitter_s: float = 0.004,
                 block: int = 1024, **kw):
        super().__init__(*a, **kw)
        self.render, self.latency_s, self.jitter_s, self.block = render, latency_s, jitter_s, block
        self._r = -1
        self._w = -1
        self._stop_flag = False
        self.true_origin = float("nan")
        self._th: Optional[threading.Thread] = None

    def _open_source(self) -> int:
        self._r, self._w = os.pipe()
        self.true_origin = mono()
        self._th = threading.Thread(target=self._gen, name="synth-capture", daemon=True)
        self._th.start()
        return self._r

    def _gen(self) -> None:
        import numpy as np
        rng = np.random.default_rng(7)
        n0 = 0
        try:
            while not self._stop_flag:
                n1 = n0 + self.block
                t_cap_end = self.true_origin + n1 / float(self.fs)
                due = t_cap_end + self.latency_s + float(rng.uniform(-self.jitter_s, self.jitter_s))
                dt = due - mono()
                if dt > 0:
                    time.sleep(dt)
                x = self.render(self.true_origin + n0 / float(self.fs), self.block, self.fs)
                pcm = np.clip(np.round(x * 32768.0), -32768, 32767).astype("<i2").tobytes()
                os.write(self._w, pcm)
                n0 = n1
        except OSError:
            pass
        finally:
            try:
                os.close(self._w)
            except OSError:
                pass

    def _close_source(self) -> None:
        self._stop_flag = True
        if self._th:
            self._th.join(timeout=3)
