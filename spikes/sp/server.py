"""HTTP server that streams the test signal with real-time pacing and a write-by-write log.

Every ``send`` is timed. The socket send buffer is small (16 KB) so that once
the renderer stops reading (its buffer is full) our ``send`` blocks: the time
blocked and the cumulative bytes then show the renderer's actual read rate.
"""
from __future__ import annotations

import errno
import select
import socket
import threading
from typing import Any, Callable, Dict, List, Optional, Tuple

from . import testsignal as ts
from .common import RunLog, mono
from .sources import INFO, StreamSource

SERVER_NAME = "sonos-probe/0.1"


class PeerClosed(Exception):
    def __init__(self, how: str):
        super().__init__(how)
        self.how = how


class Stopping(Exception):
    """The server itself is shutting down."""


class ConnState:
    """What happened on one TCP connection (kept for the end-of-run summary)."""

    def __init__(self, cid: int, peer: Tuple[str, int]):
        self.cid = cid
        self.peer = peer
        self.t_accept = mono()
        self.method = ""
        self.path = ""
        self.headers: Dict[str, str] = {}
        self.t_first_byte: Optional[float] = None
        self.t_last_write: Optional[float] = None
        self.bytes_total = 0
        self.payload = 0
        self.samples = 0
        self.base_samples = 0
        self.writes = 0
        self.blocked = 0.0
        self.max_blocked = 0.0
        self.t_closed: Optional[float] = None
        self.closed_by = ""
        self.close_how = ""
        self.status = 0
        self.sndbuf = 0


class StreamServer:
    def __init__(self, routes: Dict[str, Callable[[], StreamSource]], port: int = 8090, host: str = "0.0.0.0",
                 prebuffer: float = 3.0, log: Optional[RunLog] = None, sndbuf: int = 16384,
                 dlna_headers: bool = True):
        self.routes = routes
        self.port = port
        self.host = host
        self.prebuffer = prebuffer
        self.log = log or RunLog(echo=False)
        self.sndbuf = sndbuf
        self.dlna_headers = dlna_headers
        self.sock: Optional[socket.socket] = None
        self.thread: Optional[threading.Thread] = None
        self.stopping = False
        self.conns: List[ConnState] = []
        self._active: Dict[int, socket.socket] = {}
        self._lock = threading.Lock()
        self._cid = 0
        self.on_connection: Optional[Callable[[ConnState], None]] = None
        # in-memory copy of every payload write: (t_after_send, conn id, wire bytes, payload bytes, audio_s, blocked_s)
        self.write_tap: List[Tuple[float, int, int, int, float, float]] = []

    # -- lifecycle -------------------------------------------------------------
    def start(self) -> int:
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, self.sndbuf)
        try:
            s.bind((self.host, self.port))
        except OSError as e:
            raise SystemExit("cannot listen on %s:%d (%s). Is another stream server still running? use --port to pick another port" % (self.host, self.port, e))
        s.listen(8)
        self.port = s.getsockname()[1]
        self.sock = s
        self.thread = threading.Thread(target=self._accept_loop, name="http-accept", daemon=True)
        self.thread.start()
        return self.port

    def stop(self) -> None:
        self.stopping = True
        try:
            if self.sock:
                self.sock.close()
        except Exception:
            pass
        self.close_all("stop")

    def close_all(self, why: str = "close_all") -> int:
        """Shut down every open connection (server-initiated); returns how many were open."""
        with self._lock:
            conns = dict(self._active)
        by_id = {st.cid: st for st in self.conns}
        for cid, c in conns.items():
            if cid in by_id:
                self._mark_closed(by_id[cid], "server", why)      # first, so the handler's EOF is not blamed on the peer
            try:
                c.shutdown(socket.SHUT_RDWR)
            except Exception:
                pass
        return len(conns)

    def active_connections(self) -> int:
        with self._lock:
            return len(self._active)

    # -- accept / request ------------------------------------------------------
    def _accept_loop(self) -> None:
        assert self.sock is not None
        while not self.stopping:
            try:
                conn, addr = self.sock.accept()
            except OSError:
                return
            with self._lock:
                self._cid += 1
                cid = self._cid
            st = ConnState(cid, addr)
            self.conns.append(st)
            threading.Thread(target=self._handle, args=(conn, st), name="http-conn-%d" % cid, daemon=True).start()

    @staticmethod
    def _read_request(conn: socket.socket) -> Tuple[str, str, str, Dict[str, str], str]:
        conn.settimeout(10.0)
        buf = b""
        while b"\r\n\r\n" not in buf:
            chunk = conn.recv(4096)
            if not chunk:
                raise PeerClosed("eof_before_request")
            buf += chunk
            if len(buf) > 65536:
                raise ValueError("request too large")
        head = buf.split(b"\r\n\r\n", 1)[0].decode("latin-1")
        lines = head.split("\r\n")
        parts = lines[0].split(" ")
        method, target, version = parts[0], parts[1] if len(parts) > 1 else "/", parts[2] if len(parts) > 2 else "HTTP/1.0"
        headers: Dict[str, str] = {}
        for ln in lines[1:]:
            if ":" in ln:
                k, v = ln.split(":", 1)
                headers[k.strip().lower()] = v.strip()
        return method, target, version, headers, head

    def _head_bytes(self, src: StreamSource, status: int, rng: Optional[Tuple[int, int]]) -> bytes:
        reason = {200: "OK", 206: "Partial Content", 404: "Not Found", 405: "Method Not Allowed"}[status]
        h = ["HTTP/1.1 %d %s" % (status, reason), "Content-Type: %s" % src.content_type]
        if src.transfer == "length":
            total = src.content_length() or 0
            if status == 206 and rng is not None:
                h.append("Content-Range: bytes %d-%d/%d" % (rng[0], rng[1], total))
                h.append("Content-Length: %d" % (rng[1] - rng[0] + 1))
            else:
                h.append("Content-Length: %d" % total)
        elif src.transfer == "chunked":
            h.append("Transfer-Encoding: chunked")
        h.append("Accept-Ranges: none")      # we still honour a Range request for wav if one arrives
        h.append("Cache-Control: no-cache, no-store")
        h.append("Connection: close")
        if self.dlna_headers:
            h.append("transferMode.dlna.org: Streaming")
        h.append("Server: %s" % SERVER_NAME)
        return ("\r\n".join(h) + "\r\n\r\n").encode("latin-1")

    def _simple(self, conn: socket.socket, status: int, text: str) -> None:
        body = text.encode()
        reason = {404: "Not Found", 405: "Method Not Allowed"}.get(status, "Error")
        conn.sendall(("HTTP/1.1 %d %s\r\nContent-Length: %d\r\nConnection: close\r\n\r\n" % (status, reason, len(body))).encode() + body)

    # -- one connection --------------------------------------------------------
    def _handle(self, conn: socket.socket, st: ConnState) -> None:
        log = self.log
        with self._lock:
            self._active[st.cid] = conn
        src: Optional[StreamSource] = None
        try:
            try:
                conn.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, self.sndbuf)
                conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            except OSError:
                pass
            st.sndbuf = conn.getsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF)
            rcv = conn.getsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF)
            try:
                method, target, version, headers, raw = self._read_request(conn)
            except (PeerClosed, socket.timeout, ValueError, OSError) as e:
                log.emit("conn_fail", c=st.cid, peer=st.peer[0], err=repr(e))
                return
            path = target.split("?", 1)[0]
            st.method, st.path, st.headers = method, target, headers
            log.emit("req", c=st.cid, peer="%s:%d" % st.peer, method=method, target=target, version=version,
                     headers=headers, sndbuf=st.sndbuf, rcvbuf=rcv)
            log.say("conn %d from %s: %s %s  UA=%r Range=%r Icy-MetaData=%r" % (
                st.cid, st.peer[0], method, target, headers.get("user-agent", ""), headers.get("range", ""),
                headers.get("icy-metadata", "")), kind="conn_info")
            factory = self.routes.get(path)
            if factory is None:
                st.status = 404
                self._simple(conn, 404, "not found")
                return
            if method not in ("GET", "HEAD"):
                st.status = 405
                self._simple(conn, 405, "method not allowed")
                return
            src = factory()
            rng: Optional[Tuple[int, int]] = None
            status = 200
            start_byte = 0
            if src.supports_range() and "range" in headers and headers["range"].startswith("bytes="):
                try:
                    a, _, b = headers["range"][6:].partition("-")
                    total = src.content_length() or 0
                    start_byte = int(a) if a else max(0, total - int(b))
                    end = int(b) if (b and a) else total - 1
                    rng = (start_byte, min(end, total - 1))
                    status = 206
                except ValueError:
                    pass
            st.status = status
            head = self._head_bytes(src, status, rng)
            conn.settimeout(None)
            if method == "HEAD":
                conn.sendall(head)
                return
            self._stream(conn, st, src, head, start_byte)
        except Stopping:
            self._mark_closed(st, "server", "stopped")
        except PeerClosed as e:
            self._mark_closed(st, "peer", e.how)
        except (BrokenPipeError, ConnectionResetError) as e:
            self._mark_closed(st, "peer", "reset" if isinstance(e, ConnectionResetError) else "epipe")
        except OSError as e:
            if self.stopping or e.errno in (errno.EBADF, errno.ENOTCONN, errno.EPIPE, errno.ECONNRESET, errno.EINVAL):
                self._mark_closed(st, "server" if self.stopping else "peer", "oserror:%s" % errno.errorcode.get(e.errno, e.errno))
            else:
                log.emit("conn_error", c=st.cid, err=repr(e))
                self._mark_closed(st, "peer", "oserror:%r" % (e,))
        except Exception as e:  # pragma: no cover
            log.emit("conn_error", c=st.cid, err=repr(e))
            self._mark_closed(st, "error", repr(e))
        finally:
            if st.t_closed is None:
                self._mark_closed(st, "server", "end")
            if src is not None:
                src.close()
            try:
                conn.close()
            except OSError:
                pass
            with self._lock:
                self._active.pop(st.cid, None)

    def _mark_closed(self, st: ConnState, by: str, how: str) -> None:
        if st.t_closed is not None:
            return
        st.t_closed = mono()
        st.closed_by, st.close_how = by, how
        self.log.emit("closed", t=st.t_closed, c=st.cid, by=by, how=how, bytes=st.bytes_total, audio_s=round(st.samples / float(ts.FS), 4))
        self.log.say("conn %d closed by %s (%s) after %.1f s, %d bytes, %.2f s of audio" % (
            st.cid, by, how, st.t_closed - st.t_accept, st.bytes_total, st.samples / float(ts.FS)), kind="conn_info")

    # -- streaming loop --------------------------------------------------------
    def _wait_until(self, conn: socket.socket, due: float) -> None:
        """Sleep until ``due`` (monotonic), noticing if the peer closes meanwhile."""
        while True:
            rem = due - mono()
            if rem <= 0:
                return
            r, _, _ = select.select([conn], [], [], min(rem, 0.2))
            if r:
                data = conn.recv(4096)
                if not data:
                    raise Stopping() if self.stopping else PeerClosed("eof")

    def _send_logged(self, conn: socket.socket, st: ConnState, data: bytes, kind: str, due: float) -> None:
        """Push ``data`` into the socket, watching the peer at the same time.

        The socket is non-blocking and driven by select() on readability *and*
        writability: a plain blocking send() is not woken when the peer closes
        while our window is shut (macOS only notices at the next persist-timer
        probe, seconds later), which would make "time until the connection
        closes" meaningless. One log record per unit: when its last byte was
        accepted by the kernel, and how long the whole push took ("blocked").
        """
        view = memoryview(data)
        n_total = len(view)
        off = 0
        t_before = mono()
        t_after = t_before
        while off < n_total:
            if self.stopping:
                raise Stopping()
            r, w, _ = select.select([conn], [conn], [], 0.25)
            if r:
                d = conn.recv(4096)          # ConnectionResetError on RST propagates
                if not d:
                    raise Stopping() if self.stopping else PeerClosed("eof")
            if w:
                try:
                    n = conn.send(view[off:])
                except (BlockingIOError, InterruptedError):
                    continue
                off += n
                t_after = mono()
        blk = t_after - t_before
        st.bytes_total += n_total
        st.writes += 1
        st.blocked += blk
        st.max_blocked = max(st.max_blocked, blk)
        st.t_last_write = t_after
        if st.t_first_byte is None:
            st.t_first_byte = t_before
        aud = (st.base_samples + st.samples) / float(ts.FS)
        if kind == "w":
            self.write_tap.append((t_after, st.cid, st.bytes_total, st.payload, aud, blk))
        self.log.emit(kind, t=t_after, c=st.cid, n=n_total, cum=st.bytes_total, pay=st.payload,
                      aud=round(aud, 5), blk=round(blk, 5), late=round(t_before - due, 5))

    def _stream(self, conn: socket.socket, st: ConnState, src: StreamSource, head: bytes, start_byte: int) -> None:
        t0 = mono()
        conn.setblocking(False)
        self._send_logged(conn, st, head, "wh", t0)           # HTTP response header
        it = src.units(start_byte)
        chunked = src.transfer == "chunked"
        warned = 0.0
        for unit in it:
            if self.stopping:
                break
            st.base_samples = src.base_samples
            a_start = st.samples / float(ts.FS)
            due = t0 + max(0.0, a_start - self.prebuffer)
            self._wait_until(conn, due)
            data = unit.data
            if chunked:
                data = ("%X\r\n" % len(data)).encode() + data + b"\r\n"
            st.payload += len(unit.data)
            st.samples += unit.samples
            self._send_logged(conn, st, data, "w", due)
            late = mono() - due
            if late > 0.5 and mono() - warned > 10.0:
                warned = mono()
                lead = st.samples / float(ts.FS) - (mono() - t0)
                self.log.say("conn %d: peer buffer full - sends now block (lead over real time %.2f s, target prebuffer %.1f s); "
                             "expected when --prebuffer exceeds the renderer's buffer" % (st.cid, lead, self.prebuffer), kind="late")
        if chunked and not self.stopping:
            self._send_logged(conn, st, b"0\r\n\r\n", "wt", mono())
        # let the peer finish reading what it has, then close
        self._mark_closed(st, "server", "end_of_stream")
        try:
            conn.shutdown(socket.SHUT_WR)
        except OSError:
            pass


def summarize_conns(conns: List[ConnState]) -> List[Dict[str, Any]]:
    rows = []
    for c in conns:
        dur = (c.t_closed or mono()) - (c.t_first_byte or c.t_accept)
        rows.append({
            "conn": c.cid, "peer": c.peer[0], "method": c.method, "target": c.path, "status": c.status,
            "user_agent": c.headers.get("user-agent", ""), "range": c.headers.get("range", ""),
            "icy": c.headers.get("icy-metadata", ""), "bytes": c.bytes_total, "writes": c.writes,
            "audio_s": round(c.samples / float(ts.FS), 3), "duration_s": round(dur, 3),
            "blocked_s": round(c.blocked, 3), "blocked_pct": round(100.0 * c.blocked / dur, 1) if dur > 0 else 0.0,
            "max_blocked_s": round(c.max_blocked, 3), "closed_by": c.closed_by, "how": c.close_how,
            "t_closed": c.t_closed,
        })
    return rows
