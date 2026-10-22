"""Stream sources: the test signal in each format, cut into paced units."""
from __future__ import annotations

from pathlib import Path
from typing import Dict, Iterator, List, NamedTuple, Optional, Tuple

from . import codecs
from . import flac as flacmod
from . import testsignal as ts
from .common import CACHE_DIR

FORMATS = ("wav", "flac-chunked", "flac-nolen", "mp3", "aac")
PCM_UNIT_SAMPLES = 882          # 20 ms of PCM per paced write


class Unit(NamedTuple):
    data: bytes
    samples: int                # audio samples (per channel) this unit adds; 0 for headers


class FormatInfo(NamedTuple):
    ext: str
    mime: str
    transfer: str               # 'length' | 'chunked' | 'close'
    samples_per_unit: int


INFO: Dict[str, FormatInfo] = {
    "wav": FormatInfo("wav", "audio/wav", "length", PCM_UNIT_SAMPLES),
    "flac-chunked": FormatInfo("flac", "audio/flac", "chunked", flacmod.BLOCKSIZE),
    "flac-nolen": FormatInfo("flac", "audio/flac", "close", flacmod.BLOCKSIZE),
    "mp3": FormatInfo("mp3", "audio/mpeg", "close", codecs.MP3_SAMPLES_PER_FRAME),
    "aac": FormatInfo("aac", "audio/aac", "close", 1024),
}


def stream_path(fmt: str) -> str:
    """URL path for a format; the extension hints the type, the name tells variants apart."""
    i = INFO[fmt]
    tag = {"wav": "stream", "flac-chunked": "stream-chunked", "flac-nolen": "stream-nolen",
           "mp3": "stream", "aac": "stream"}[fmt]
    return "/%s.%s" % (tag, i.ext)


class StreamSource:
    """One connection's worth of the test signal in one format."""

    def __init__(self, fmt: str, aac_path: Optional[Path] = None):
        self.fmt = fmt
        self.info = INFO[fmt]
        self.aac_path = aac_path
        self._closers: List = []
        self.base_samples = 0

    # -- HTTP framing ----------------------------------------------------------
    @property
    def transfer(self) -> str:
        return self.info.transfer

    @property
    def content_type(self) -> str:
        return self.info.mime

    def content_length(self) -> Optional[int]:
        return ts.WAV_CONTENT_LENGTH if self.fmt == "wav" else None

    def supports_range(self) -> bool:
        return self.fmt == "wav"

    # -- payload ---------------------------------------------------------------
    def units(self, start_byte: int = 0) -> Iterator[Unit]:
        f = self.fmt
        if f == "wav":
            yield from self._wav_units(start_byte)
        elif f in ("flac-chunked", "flac-nolen"):
            yield from self._flac_units()
        elif f == "mp3":
            yield from self._mp3_units()
        elif f == "aac":
            yield from self._aac_units()
        else:
            raise ValueError(f)

    def _wav_units(self, start_byte: int) -> Iterator[Unit]:
        hdr = ts.endless_wav_header()
        if start_byte < len(hdr):
            yield Unit(hdr[start_byte:], 0)
            pcm_off = 0
        else:
            pcm_off = start_byte - len(hdr)
        sample = pcm_off // 4
        skip = pcm_off % 4
        self.base_samples = sample
        tr = ts.track()
        limit = ts.WAV_MAX_DATA // 4
        while sample < limit:
            n = min(PCM_UNIT_SAMPLES, limit - sample)
            data = tr.pcm(sample, n)
            if skip:
                data = data[skip:]
                skip = 0
                yield Unit(data, n)      # partial first sample accounted as a whole one
            else:
                yield Unit(data, n)
            sample += n

    def _flac_units(self) -> Iterator[Unit]:
        yield Unit(flacmod.stream_header(), 0)
        tr = ts.track()
        num = 0
        pos = 0
        while True:
            pcm = tr.pcm(pos, flacmod.BLOCKSIZE)
            yield Unit(flacmod.frame(num, pcm, flacmod.BLOCKSIZE), flacmod.BLOCKSIZE)
            pos += flacmod.BLOCKSIZE
            num += 1

    def _mp3_units(self) -> Iterator[Unit]:
        enc = codecs.Mp3Encoder()
        self._closers.append(enc.close)
        try:
            for data, samples in enc.frames():
                yield Unit(data, samples)
        finally:
            enc.close()

    def _aac_units(self) -> Iterator[Unit]:
        if self.aac_path is None:
            raise RuntimeError("AAC source needs a cached file (codecs.ensure_aac)")
        for data, samples in codecs.aac_frames(self.aac_path):
            yield Unit(data, samples)

    def close(self) -> None:
        for c in self._closers:
            try:
                c()
            except Exception:
                pass
        self._closers = []


def make_source_factory(fmt: str, seconds_needed: float = 900.0, cache_dir: Path = CACHE_DIR, log=print):
    """Prepare whatever a format needs once (AAC cache) and return a per-connection factory."""
    if fmt not in INFO:
        raise ValueError("unknown format %r (choose from %s)" % (fmt, ", ".join(FORMATS)))
    aac_path = None
    if fmt == "aac":
        aac_path = codecs.ensure_aac(seconds_needed, cache_dir, log=log)
    if fmt == "mp3":
        from .common import have_cmd
        if not have_cmd("lame"):
            raise RuntimeError("lame not found (brew install lame)")

    def factory() -> StreamSource:
        return StreamSource(fmt, aac_path=aac_path)

    return factory
