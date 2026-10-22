"""Minimal FLAC encoder using only VERBATIM subframes (no compression).

Why: to test how a renderer copes with an endless FLAC stream whose STREAMINFO
says "unknown length" (total samples 0, MD5 0) without depending on a flac
binary. A verbatim FLAC is just the PCM wrapped in valid frames (about 0.1 %
overhead), so it is cheap to generate on the fly at exact sample positions.

Layout (RFC 9639):
    "fLaC" + STREAMINFO block (last-metadata-block flag set)
    frame* = header(sync 0xFFF8 fixed-blocksize, blocksize code, rate code,
                    channel assignment, sample size, UTF-8 frame number, CRC-8)
             + one VERBATIM subframe per channel (1 header byte + raw BE samples)
             + CRC-16
"""
from __future__ import annotations

import struct
import sys
from array import array
from typing import Iterator, List, Optional, Tuple

BLOCKSIZE = 4096
SAMPLE_RATE = 44100
CHANNELS = 2
BITS = 16

_BS_CODE = {192: 1, 576: 2, 1152: 3, 2304: 4, 4608: 5, 256: 8, 512: 9, 1024: 10,
            2048: 11, 4096: 12, 8192: 13, 16384: 14, 32768: 15}
_RATE_CODE = {88200: 1, 176400: 2, 192000: 3, 8000: 4, 16000: 5, 22050: 6, 24000: 7,
              32000: 8, 44100: 9, 48000: 10, 96000: 11}


def _make_crc8() -> List[int]:
    tbl = []
    for i in range(256):
        c = i
        for _ in range(8):
            c = ((c << 1) ^ 0x07) & 0xFF if c & 0x80 else (c << 1) & 0xFF
        tbl.append(c)
    return tbl


def _make_crc16() -> List[int]:
    tbl = []
    for i in range(256):
        c = i << 8
        for _ in range(8):
            c = ((c << 1) ^ 0x8005) & 0xFFFF if c & 0x8000 else (c << 1) & 0xFFFF
        tbl.append(c)
    return tbl


_CRC8 = _make_crc8()
_CRC16 = _make_crc16()


def crc8(data: bytes, crc: int = 0) -> int:
    for b in data:
        crc = _CRC8[crc ^ b]
    return crc


def crc16(data: bytes, crc: int = 0) -> int:
    t = _CRC16
    for b in data:
        crc = ((crc << 8) & 0xFFFF) ^ t[(crc >> 8) ^ b]
    return crc


def utf8_number(n: int) -> bytes:
    """FLAC's UTF-8 style coding of the frame number (up to 36 bits)."""
    if n < 0:
        raise ValueError("negative")
    if n < 0x80:
        return bytes([n])
    for nbytes, limit in ((2, 0x800), (3, 0x10000), (4, 0x200000), (5, 0x4000000),
                          (6, 0x80000000), (7, 0x1000000000)):
        if n < limit:
            lead_bits = nbytes + 1  # number of leading 1 bits in first byte
            first_mask = (0xFF << (8 - nbytes)) & 0xFF
            out = bytearray(nbytes)
            for i in range(nbytes - 1, 0, -1):
                out[i] = 0x80 | (n & 0x3F)
                n >>= 6
            out[0] = first_mask | n
            return bytes(out)
    raise ValueError("frame number too large")


def parse_utf8_number(data: bytes, pos: int) -> Tuple[int, int]:
    """Inverse of utf8_number: returns (value, next position)."""
    b = data[pos]
    if b < 0x80:
        return b, pos + 1
    n = 0
    mask = 0x80
    while b & mask:
        n += 1
        mask >>= 1
    value = b & (mask - 1)
    for i in range(1, n):
        value = (value << 6) | (data[pos + i] & 0x3F)
    return value, pos + n


def streaminfo(total_samples: int = 0, md5: bytes = b"\x00" * 16, blocksize: int = BLOCKSIZE,
               sample_rate: int = SAMPLE_RATE, channels: int = CHANNELS, bits: int = BITS,
               min_frame: int = 0, max_frame: int = 0) -> bytes:
    """'fLaC' marker + STREAMINFO metadata block (flagged as last block)."""
    body = struct.pack(">HH", blocksize, blocksize)
    body += struct.pack(">I", min_frame)[1:] + struct.pack(">I", max_frame)[1:]
    packed = (sample_rate << 44) | ((channels - 1) << 41) | ((bits - 1) << 36) | total_samples
    body += struct.pack(">Q", packed)
    body += md5
    assert len(body) == 34
    return b"fLaC" + bytes([0x80 | 0]) + struct.pack(">I", 34)[1:] + body


def stream_header() -> bytes:
    """Header of the endless stream: unknown total samples, MD5 all zero."""
    return streaminfo(0)


def _be16(samples: array) -> bytes:
    a = array("h", samples)
    if sys.byteorder == "little":
        a.byteswap()
    return a.tobytes()


def frame_header(frame_number: int, blocksize: int = BLOCKSIZE, sample_rate: int = SAMPLE_RATE,
                 channels: int = CHANNELS, bits: int = BITS) -> bytes:
    if channels != 2 or bits != 16 or sample_rate not in _RATE_CODE:
        raise ValueError("only 16-bit stereo at standard rates")
    if blocksize in _BS_CODE:
        bs_code, bs_tail = _BS_CODE[blocksize], b""
    elif blocksize <= 256:
        bs_code, bs_tail = 6, bytes([blocksize - 1])
    else:
        bs_code, bs_tail = 7, struct.pack(">H", blocksize - 1)
    h = bytes([0xFF, 0xF8, (bs_code << 4) | _RATE_CODE[sample_rate],
               (1 << 4) | (4 << 1)])  # channel assignment 0001 = L/R, sample size 100 = 16 bit
    h += utf8_number(frame_number) + bs_tail
    return h + bytes([crc8(h)])


def frame(frame_number: int, pcm_le: bytes, blocksize: Optional[int] = None) -> bytes:
    """One FLAC frame from interleaved stereo 16-bit little-endian PCM."""
    nsamp = len(pcm_le) // 4
    if blocksize is None:
        blocksize = nsamp
    if nsamp != blocksize:
        raise ValueError("pcm length does not match blocksize")
    a = array("h")
    a.frombytes(pcm_le)
    if sys.byteorder == "big":  # pragma: no cover
        a.byteswap()
    left, right = a[0::2], a[1::2]
    body = frame_header(frame_number, blocksize)
    body += b"\x02" + _be16(left)   # VERBATIM subframe, no wasted bits
    body += b"\x02" + _be16(right)
    return body + struct.pack(">H", crc16(body))


def verbatim_frame_len(blocksize: int, header_len: int) -> int:
    return header_len + 2 * (1 + 2 * blocksize) + 2


def encode_pcm(pcm_le: bytes, total_samples_in_header: bool = False) -> bytes:
    """Encode a whole PCM buffer (used by tests and the offline files)."""
    n = len(pcm_le) // 4
    out = [streaminfo(n if total_samples_in_header else 0)]
    num = 0
    pos = 0
    while pos < n:
        take = min(BLOCKSIZE, n - pos)
        out.append(frame(num, pcm_le[pos * 4:(pos + take) * 4], take))
        pos += take
        num += 1
    return b"".join(out)


class FrameWalker:
    """Incremental parser that counts audio samples in a verbatim FLAC byte stream.

    Used by the mock renderer (and tests) to turn "bytes consumed" into audio
    time. Only understands the frames this module writes.
    """

    def __init__(self):
        self.buf = bytearray()
        self.samples = 0
        self.header_done = False
        self.frames = 0
        self.consumed = 0          # bytes of complete frames (plus header) seen so far

    def feed(self, data: bytes) -> int:
        self.buf += data
        if not self.header_done:
            if len(self.buf) < 42:
                return self.samples
            if bytes(self.buf[:4]) != b"fLaC":
                raise ValueError("not FLAC")
            del self.buf[:42]
            self.consumed += 42
            self.header_done = True
        while True:
            if len(self.buf) < 6:
                break
            if self.buf[0] != 0xFF or (self.buf[1] & 0xFE) != 0xF8:
                raise ValueError("lost FLAC sync")
            bs_code = self.buf[2] >> 4
            if len(self.buf) < 12:
                break
            _, pos = parse_utf8_number(bytes(self.buf), 4)
            if bs_code == 6:
                blocksize = self.buf[pos] + 1
                pos += 1
            elif bs_code == 7:
                blocksize = struct.unpack(">H", bytes(self.buf[pos:pos + 2]))[0] + 1
                pos += 2
            else:
                blocksize = {v: k for k, v in _BS_CODE.items()}[bs_code]
            pos += 1  # crc8
            total = verbatim_frame_len(blocksize, pos)
            if len(self.buf) < total:
                break
            del self.buf[:total]
            self.consumed += total
            self.samples += blocksize
            self.frames += 1
        return self.samples
