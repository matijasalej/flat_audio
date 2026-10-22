"""Shared helpers: paths, clocks, run logs, small statistics, LAN helpers (stdlib only)."""
from __future__ import annotations

import datetime
import ipaddress
import json
import math
import os
import re
import socket
import subprocess
import sys
import threading
import time
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Sequence, Tuple

SPIKES_DIR = Path(__file__).resolve().parent.parent
RESULTS_DIR = SPIKES_DIR / "results"
CACHE_DIR = RESULTS_DIR / "cache"


def mono() -> float:
    """The one clock every timestamp in this toolkit uses (CLOCK_UPTIME_RAW on macOS)."""
    return time.monotonic()


def new_run_dir(label: str = "", base: Optional[Path] = None) -> Path:
    base = base or RESULTS_DIR
    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    name = stamp + ("_" + label if label else "")
    p = base / name
    n = 1
    while p.exists():
        n += 1
        p = base / ("%s_%d" % (name, n))
    p.mkdir(parents=True, exist_ok=True)
    return p


class RunLog:
    """Thread-safe JSONL log + timestamped console output.

    Every record carries ``t`` (monotonic seconds, same clock everywhere in
    the process), ``w`` (wall clock epoch seconds) and ``k`` (kind).
    """

    def __init__(self, path: Optional[os.PathLike] = None, echo: bool = True, t0: Optional[float] = None):
        self.path = Path(path) if path else None
        self.fh = open(self.path, "a", buffering=1) if self.path else None
        self.lock = threading.Lock()
        self.echo = echo
        self.t0 = mono() if t0 is None else t0
        self.count = 0

    def emit(self, kind: str, t: Optional[float] = None, **fields: Any) -> None:
        rec: Dict[str, Any] = {"t": round(mono() if t is None else t, 6), "w": round(time.time(), 3), "k": kind}
        rec.update(fields)
        line = json.dumps(rec, separators=(",", ":"))
        with self.lock:
            self.count += 1
            if self.fh:
                self.fh.write(line + "\n")

    def say(self, msg: str, kind: str = "msg", **fields: Any) -> None:
        now = mono()
        if self.echo:
            with self.lock:
                print("[%8.3f] %s" % (now - self.t0, msg), flush=True)
        self.emit(kind, t=now, msg=msg, **fields)

    def close(self) -> None:
        with self.lock:
            if self.fh:
                self.fh.close()
                self.fh = None


def read_jsonl(path: os.PathLike) -> List[Dict[str, Any]]:
    out = []
    with open(path) as f:
        for line in f:
            line = line.strip()
            if line:
                out.append(json.loads(line))
    return out


# ---------------------------------------------------------------- statistics (stdlib)

def mean(v: Sequence[float]) -> float:
    return sum(v) / len(v) if v else float("nan")


def median(v: Sequence[float]) -> float:
    s = sorted(v)
    n = len(s)
    if n == 0:
        return float("nan")
    return s[n // 2] if n % 2 else 0.5 * (s[n // 2 - 1] + s[n // 2])


def stdev(v: Sequence[float]) -> float:
    n = len(v)
    if n < 2:
        return float("nan")
    m = mean(v)
    return math.sqrt(sum((x - m) ** 2 for x in v) / (n - 1))


def linfit(x: Sequence[float], y: Sequence[float]) -> Tuple[float, float]:
    """Least squares y = a + b x. Returns (a, b); b = nan if degenerate."""
    n = len(x)
    if n < 2:
        return (float(y[0]) if y else float("nan"), float("nan"))
    mx, my = mean(x), mean(y)
    sxx = sum((xi - mx) ** 2 for xi in x)
    if sxx == 0:
        return my, float("nan")
    b = sum((xi - mx) * (yi - my) for xi, yi in zip(x, y)) / sxx
    return my - b * mx, b


def percentile(v: Sequence[float], p: float) -> float:
    s = sorted(v)
    if not s:
        return float("nan")
    i = (len(s) - 1) * p / 100.0
    lo, hi = int(math.floor(i)), int(math.ceil(i))
    return s[lo] + (s[hi] - s[lo]) * (i - lo)


def fmt(v: Optional[float], nd: int = 3, unit: str = "") -> str:
    if v is None or (isinstance(v, float) and (math.isnan(v) or math.isinf(v))):
        return "n/a"
    return ("%." + str(nd) + "f%s") % (v, unit)


def fmt_hms(seconds: float) -> str:
    seconds = int(round(seconds))
    return "%d:%02d:%02d" % (seconds // 3600, (seconds // 60) % 60, seconds % 60)


# ---------------------------------------------------------------- LAN helpers

class Iface:
    def __init__(self, name: str, ip: str, netmask: str, broadcast: Optional[str]):
        self.name, self.ip, self.netmask, self.broadcast = name, ip, netmask, broadcast

    @property
    def network(self) -> ipaddress.IPv4Network:
        return ipaddress.ip_network("%s/%s" % (self.ip, self.netmask), strict=False)

    def __repr__(self) -> str:
        return "Iface(%s %s/%s bc=%s)" % (self.name, self.ip, self.netmask, self.broadcast)


def list_ifaces() -> List[Iface]:
    """IPv4 interfaces parsed from ``ifconfig`` (macOS format)."""
    try:
        txt = subprocess.run(["/sbin/ifconfig"], capture_output=True, text=True, timeout=5).stdout
    except Exception:
        return []
    out: List[Iface] = []
    cur = None
    for line in txt.splitlines():
        m = re.match(r"^([a-z0-9]+):", line)
        if m:
            cur = m.group(1)
            continue
        m = re.match(r"\s+inet (\d+\.\d+\.\d+\.\d+) netmask (0x[0-9a-f]+)(?: broadcast (\d+\.\d+\.\d+\.\d+))?", line)
        if m and cur:
            mask = socket.inet_ntoa(int(m.group(2), 16).to_bytes(4, "big"))
            out.append(Iface(cur, m.group(1), mask, m.group(3)))
    return out


def pick_iface(target_ip: Optional[str] = None, prefer: Optional[str] = None) -> Optional[Iface]:
    """Choose the LAN interface: the one whose subnet contains ``target_ip``,
    else ``prefer`` (an IP), else a private non-loopback en* interface."""
    ifs = [i for i in list_ifaces() if not i.ip.startswith("127.")]
    if prefer:
        for i in ifs:
            if i.ip == prefer:
                return i
    if target_ip:
        try:
            tip = ipaddress.ip_address(target_ip)
            if tip.is_loopback:
                return Iface("lo0", "127.0.0.1", "255.0.0.0", None)
            for i in ifs:
                if tip in i.network:
                    return i
        except ValueError:
            pass
    for i in ifs:
        if i.name.startswith("en") and ipaddress.ip_address(i.ip).is_private and i.broadcast:
            return i
    for i in ifs:
        if ipaddress.ip_address(i.ip).is_private:
            return i
    return ifs[0] if ifs else None


def local_ip_for(target_ip: Optional[str] = None, prefer: Optional[str] = None) -> str:
    """The Mac's IPv4 address that the speaker can reach (detected at runtime)."""
    if target_ip and ipaddress.ip_address(target_ip).is_loopback:
        return "127.0.0.1"
    i = pick_iface(target_ip, prefer)
    if i:
        return i.ip
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect((target_ip or "192.0.2.1", 9))
        return s.getsockname()[0]
    finally:
        s.close()


def arp_lookup(ip: str) -> Optional[str]:
    """MAC address of ``ip`` from the ARP cache (``arp -n``), if present."""
    try:
        txt = subprocess.run(["/usr/sbin/arp", "-n", ip], capture_output=True, text=True, timeout=5).stdout
    except Exception:
        return None
    m = re.search(r"at ((?:[0-9a-fA-F]{1,2}:){5}[0-9a-fA-F]{1,2})", txt)
    if not m:
        return None
    return ":".join("%02x" % int(p, 16) for p in m.group(1).split(":"))


def have_cmd(name: str) -> Optional[str]:
    from shutil import which
    return which(name)


def python_ok() -> None:
    if sys.version_info < (3, 9):
        raise SystemExit("Python 3.9+ required")


def keep_awake() -> Optional[subprocess.Popen]:
    """Stop macOS from sleeping while this process runs (`caffeinate -dim -w <pid>`)."""
    exe = have_cmd("caffeinate")
    if not exe:
        return None
    try:
        return subprocess.Popen([exe, "-dim", "-w", str(os.getpid())], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except OSError:
        return None
