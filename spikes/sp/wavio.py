"""Tolerant WAV reader/writer (stdlib only).

Python's ``wave`` rejects WAVE_FORMAT_EXTENSIBLE (which afconvert writes) and
trusts the header sizes (which are wrong for interrupted or streamed
recordings). This reader walks the chunks itself and falls back to the file
size when the data size is bogus.
"""
from __future__ import annotations

import os
import struct
from typing import NamedTuple, Optional


class WavInfo(NamedTuple):
    fs: int
    channels: int
    bits: int
    fmt_tag: int          # 1 = PCM, 3 = float (extensible resolved to its sub-format)
    data_offset: int
    data_bytes: int
    frames: int


def read_info(path: str) -> WavInfo:
    size = os.path.getsize(path)
    with open(path, "rb") as f:
        head = f.read(12)
        if len(head) < 12 or head[:4] not in (b"RIFF", b"RF64") or head[8:12] != b"WAVE":
            raise ValueError("not a RIFF/WAVE file: %s" % path)
        fmt = None
        pos = 12
        while pos + 8 <= size:
            f.seek(pos)
            cid, csz = struct.unpack("<4sI", f.read(8))
            body = pos + 8
            if cid == b"fmt ":
                raw = f.read(min(csz, 40))
                tag, ch, fs, _br, _ba, bits = struct.unpack("<HHIIHH", raw[:16])
                if tag == 0xFFFE and len(raw) >= 26:
                    tag = struct.unpack("<H", raw[24:26])[0]
                fmt = (tag, ch, fs, bits)
            elif cid == b"data":
                if fmt is None:
                    raise ValueError("data before fmt")
                avail = size - body
                dsz = csz if (csz <= avail and csz != 0) else avail
                tag, ch, fs, bits = fmt
                block = ch * bits // 8
                dsz -= dsz % block
                return WavInfo(fs, ch, bits, tag, body, dsz, dsz // block)
            pos = body + csz + (csz & 1)
    raise ValueError("no data chunk in %s" % path)


def read_pcm_bytes(path: str, info: Optional[WavInfo] = None) -> bytes:
    info = info or read_info(path)
    with open(path, "rb") as f:
        f.seek(info.data_offset)
        return f.read(info.data_bytes)


def write_pcm16(path: str, pcm: bytes, fs: int, channels: int) -> None:
    from .testsignal import wav_header
    with open(path, "wb") as f:
        f.write(wav_header(len(pcm), fs, channels, 16))
        f.write(pcm)


class WavChunkWriter:
    """Incremental 16-bit PCM WAV writer that keeps the header valid when closed."""

    def __init__(self, path: str, fs: int, channels: int = 1):
        self.path = path
        self.fs = fs
        self.channels = channels
        self.f = open(path, "wb")
        self.written = 0
        from .testsignal import wav_header
        self.f.write(wav_header(0, fs, channels, 16))

    def write(self, data: bytes) -> None:
        self.f.write(data)
        self.written += len(data)

    def close(self) -> None:
        if self.f is None:
            return
        from .testsignal import wav_header
        self.f.seek(0)
        self.f.write(wav_header(self.written, self.fs, self.channels, 16))
        self.f.close()
        self.f = None
