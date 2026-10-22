"""Sonos local websocket API (needs the `websockets` package) and the `ws` command.

Protocol facts, taken from the source of github.com/music-assistant/aiosonos
(main 496ae13, release 0.1.15; aiosonos/const.py, api/websockets.py, utils.py,
api/namespaces/*.py, group.py) and from music_assistant/providers/sonos/player.py:

* discovery info:   GET https://<ip>:1443/api/v1/players/local/info
                    header  X-Sonos-Api-Key: 123e4567-e89b-12d3-a456-426655440000
                    -> {householdId, playerId, groupId, websocketUrl, device{...}}
* websocket:        wss://<ip>:1443/websocket/api   (TLS >= 1.2, certificate NOT verified)
                    headers X-Sonos-Api-Key: <same key>, Sec-WebSocket-Protocol: v1.api.smartspeaker.audio
* every message is a JSON array of two objects [header, body], sent as a BINARY frame
  (aiosonos uses send_bytes); replies arrive as TEXT frames.
* command header:   {"namespace": "<ns>:1", "command": "<cmd>", "cmdId": "<uuid hex>", <path ids>}
                    e.g. {"namespace":"playback:1","command":"getPlaybackStatus","groupId":...}
* reply header:     {"namespace","response","success":true|false,"cmdId","householdId",...}
                    a failure carries {"errorCode","reason"} in the body
* event header:     {"namespace","type":"playbackStatus","groupId","householdId"} + body
* commands used:    groups:1 getGroups(householdId; body includeDeviceInfo),
                    playback:1 getPlaybackStatus / subscribe / pause (groupId),
                    playbackSession:1 createSession (groupId; body appId, appContext),
                    playbackSession:1 loadStreamUrl (sessionId; body streamUrl, playOnCompletion,
                       stationMetadata{_objectType:container,name,type,id{objectId},service{...}}),
                    playbackSession:1 suspend (sessionId)
* playbackStatus body: {playbackState: PLAYBACK_STATE_(IDLE|BUFFERING|PAUSED|PLAYING),
                    positionMillis, previousPositionMillis, availablePlaybackActions, playModes, isDucking}
  aiosonos extrapolates positionMillis between events with time.time(), so the field is a
  snapshot (this tool polls getPlaybackStatus to get a fresh one).
"""
from __future__ import annotations

import asyncio
import json
import ssl
import threading
import time
import urllib.request
import uuid
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

from .common import RunLog, fmt, local_ip_for, mono, new_run_dir, median, mean, stdev, linfit

LOCAL_API_KEY = "123e4567-e89b-12d3-a456-426655440000"      # aiosonos.const.LOCAL_API_TOKEN
SUBPROTOCOL = "v1.api.smartspeaker.audio"
DEFAULT_APP_ID = "com.aiosonos.playback"                     # aiosonos group.py DEFAULT_APP_ID
DEFAULT_APP_CONTEXT = "1"


class WsError(Exception):
    def __init__(self, command: str, code: str, reason: str = ""):
        super().__init__("%s failed: %s %s" % (command, code, reason))
        self.command, self.code, self.reason = command, code, reason


def ssl_ctx() -> ssl.SSLContext:
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    ctx.minimum_version = ssl.TLSVersion.TLSv1_2
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    return ctx


def fetch_info(ip: str, port: int = 1443, timeout: float = 5.0) -> Dict[str, Any]:
    req = urllib.request.Request("https://%s:%d/api/v1/players/local/info" % (ip, port), headers={"X-Sonos-Api-Key": LOCAL_API_KEY})
    with urllib.request.urlopen(req, context=ssl_ctx(), timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8"))


def station_container(name: str, object_id: str) -> Dict[str, Any]:
    """stationMetadata exactly as Music Assistant sends it with loadStreamUrl."""
    return {"_objectType": "container", "name": name, "type": "track",
            "id": {"_objectType": "id", "objectId": object_id},
            "service": {"_objectType": "service", "name": "Music Assistant", "id": "mass"}}


class SonosWs:
    def __init__(self, ip: str, port: int = 1443, log: Optional[RunLog] = None):
        self.ip, self.port = ip, port
        self.log = log or RunLog(echo=False)
        self.info: Dict[str, Any] = {}
        self.ws = None
        self._pending: Dict[str, asyncio.Future] = {}
        self._reader: Optional[asyncio.Task] = None
        self.events: List[Tuple[float, Dict[str, Any], Dict[str, Any]]] = []
        self.household = ""
        self.player_id = ""
        self.group_id = ""
        self.closed = False

    async def connect(self) -> None:
        try:
            import websockets
        except ImportError:
            raise SystemExit("the `websockets` package is missing: use .venv/bin/python (pip install -r requirements.txt)")
        loop = asyncio.get_running_loop()
        self.info = await loop.run_in_executor(None, fetch_info, self.ip, self.port)
        self.household = self.info.get("householdId", "")
        self.player_id = self.info.get("playerId", "")
        self.group_id = self.info.get("groupId", "")
        url = self.info.get("websocketUrl") or "wss://%s:%d/websocket/api" % (self.ip, self.port)
        hdr = {"X-Sonos-Api-Key": LOCAL_API_KEY}
        kw: Dict[str, Any] = dict(ssl=ssl_ctx(), subprotocols=[SUBPROTOCOL], compression=None, max_size=None,
                                  open_timeout=10, ping_interval=55, ping_timeout=55)
        try:
            from websockets.asyncio.client import connect as aconnect
            self.ws = await aconnect(url, additional_headers=hdr, **kw)
        except ImportError:
            from websockets.client import connect as lconnect     # websockets < 13
            self.ws = await lconnect(url, extra_headers=hdr, **kw)
        self.log.emit("ws_connected", url=url, subprotocol=getattr(self.ws, "subprotocol", None))
        self._reader = asyncio.ensure_future(self._read_loop())

    async def close(self) -> None:
        self.closed = True
        if self.ws is not None:
            try:
                await self.ws.close()
            except Exception:
                pass
        if self._reader:
            self._reader.cancel()

    async def _read_loop(self) -> None:
        try:
            async for raw in self.ws:
                t = mono()
                try:
                    msg = json.loads(raw if isinstance(raw, str) else raw.decode("utf-8"))
                    header, body = msg[0], msg[1]
                except Exception as e:
                    self.log.emit("ws_bad_message", t=t, err=repr(e), raw=str(raw)[:200])
                    continue
                cid = header.get("cmdId")
                if cid and cid in self._pending and not self._pending[cid].done():
                    self._pending[cid].set_result((t, header, body))
                else:
                    self.events.append((t, header, body))
                    self.log.emit("ws_event", t=t, header=header, body=body if header.get("type") != "playbackStatus" else
                                  {k: body.get(k) for k in ("playbackState", "positionMillis", "previousPositionMillis")})
        except Exception as e:
            if not self.closed:
                self.log.emit("ws_read_error", err=repr(e))
        finally:
            for f in self._pending.values():
                if not f.done():
                    f.cancel()

    async def command(self, namespace: str, command: str, body: Optional[Dict[str, Any]] = None, timeout: float = 10.0,
                      **path: str) -> Tuple[float, float, Dict[str, Any], Dict[str, Any]]:
        """Send one command; returns (t_sent, t_received, reply header, reply body)."""
        cid = uuid.uuid4().hex
        header = {"namespace": "%s:1" % namespace, "command": command, "cmdId": cid}
        header.update(path)
        fut = asyncio.get_running_loop().create_future()
        self._pending[cid] = fut
        t_sent = mono()
        await self.ws.send(json.dumps([header, body or {}]).encode("utf-8"))      # binary frame, like aiosonos send_bytes
        try:
            t_recv, rh, rb = await asyncio.wait_for(fut, timeout)
        finally:
            self._pending.pop(cid, None)
        if "errorCode" in rb or rh.get("success") is False:
            raise WsError("%s:%s" % (namespace, command), rb.get("errorCode", "unknown"), rb.get("reason", ""))
        return t_sent, t_recv, rh, rb

    # -- convenience --------------------------------------------------------------
    async def get_groups(self) -> Dict[str, Any]:
        _, _, _, b = await self.command("groups", "getGroups", {"includeDeviceInfo": True}, householdId=self.household)
        for g in b.get("groups", []):
            if g.get("coordinatorId") == self.player_id:
                self.group_id = g["id"]
        return b

    async def playback_status(self) -> Tuple[float, float, Dict[str, Any]]:
        ts_, tr, _, b = await self.command("playback", "getPlaybackStatus", groupId=self.group_id)
        return ts_, tr, b

    async def subscribe_playback(self) -> None:
        await self.command("playback", "subscribe", groupId=self.group_id)

    async def create_session(self, app_id: str = DEFAULT_APP_ID, app_context: str = DEFAULT_APP_CONTEXT) -> str:
        _, _, _, b = await self.command("playbackSession", "createSession", {"appId": app_id, "appContext": app_context},
                                        groupId=self.group_id)
        return b["sessionId"]

    async def load_stream_url(self, session_id: str, url: str, name: str = "Whole Flat Audio test signal") -> None:
        await self.command("playbackSession", "loadStreamUrl",
                           {"streamUrl": url, "playOnCompletion": True, "stationMetadata": station_container(name, url)},
                           sessionId=session_id)

    async def suspend(self, session_id: str) -> None:
        await self.command("playbackSession", "suspend", {}, sessionId=session_id)


class WsStatusPoller:
    """Background thread: connects, then polls getPlaybackStatus at ``hz`` (for measure / drift)."""

    def __init__(self, ip: str, port: int, hz: float, log: RunLog):
        self.ip, self.port, self.hz, self.log = ip, port, hz, log
        self.samples: List[Tuple[float, float, Optional[float], str]] = []     # (t_sent, t_recv, position_s, state)
        self.thread = threading.Thread(target=self._run, name="ws-poller", daemon=True)
        self.stop_ev = threading.Event()
        self.ready = threading.Event()
        self.error: Optional[str] = None
        self.n_err = 0

    def start(self, timeout: float = 15.0) -> None:
        self.thread.start()
        if not self.ready.wait(timeout):
            raise RuntimeError("websocket did not become ready: %s" % (self.error or "timeout"))
        if self.error:
            raise RuntimeError("websocket failed: %s" % self.error)

    def stop(self) -> None:
        self.stop_ev.set()
        self.thread.join(timeout=5)

    def _run(self) -> None:
        try:
            asyncio.run(self._main())
        except Exception as e:
            self.error = repr(e)
            self.ready.set()

    async def _main(self) -> None:
        c = SonosWs(self.ip, self.port, self.log)
        try:
            await c.connect()
            await c.get_groups()
            try:
                await c.subscribe_playback()
            except WsError as e:
                self.log.say("ws subscribe failed: %s" % e)
            self.log.say("websocket ready: household %s, player %s, group %s" % (c.household, c.player_id, c.group_id), kind="ws_ready")
            self.ready.set()
            period = 1.0 / self.hz
            nxt = mono()
            while not self.stop_ev.is_set():
                now = mono()
                if now < nxt:
                    await asyncio.sleep(nxt - now)
                    continue
                nxt += period
                if nxt < mono() - 5 * period:
                    nxt = mono() + period
                try:
                    ts_, tr, b = await c.playback_status()
                    pos = b.get("positionMillis")
                    self.samples.append((ts_, tr, pos / 1000.0 if pos is not None else None, b.get("playbackState", "")))
                    self.log.emit("wspos", t=tr, ts=round(ts_, 5), pos=pos, prev=b.get("previousPositionMillis"), st=b.get("playbackState"))
                except Exception as e:
                    self.n_err += 1
                    self.log.emit("wspos_err", err=repr(e))
                    if self.n_err in (1, 10, 100):
                        self.log.say("websocket poll error #%d: %r" % (self.n_err, e))
                    if c.ws is None or getattr(c.ws, "close_code", None) is not None:
                        break
        except Exception as e:
            self.error = repr(e)
            self.ready.set()
        finally:
            await c.close()


def ws_position_stats(samples: List[Tuple[float, float, Optional[float], str]], t_from: float, t_to: float) -> Dict[str, Any]:
    """How does positionMillis behave while playing?"""
    s = [(0.5 * (a + b), p, st) for (a, b, p, st) in samples if t_from <= a <= t_to and p is not None]
    out: Dict[str, Any] = {"n": len(s)}
    if len(s) < 5:
        return out
    play = [(t, p) for (t, p, st) in s if st == "PLAYBACK_STATE_PLAYING"]
    out["n_playing"] = len(play)
    states: Dict[str, int] = {}
    for _, _, st in s:
        states[st] = states.get(st, 0) + 1
    out["states"] = states
    if len(play) >= 5:
        vals = [p for _, p in play]
        out["pos_min"], out["pos_max"] = min(vals), max(vals)
        distinct = sorted(set(vals))
        out["distinct"] = len(distinct)
        diffs = [b - a for a, b in zip(distinct[:-1], distinct[1:])]
        out["min_step_ms"] = min(diffs) * 1000 if diffs else None
        a, b = linfit([t for t, _ in play], vals)
        out["slope_s_per_s"] = b
        out["advancing"] = b == b and abs(b - 1.0) < 0.2
    return out


# ------------------------------------------------------------------ the `ws` command

def run_ws(args) -> int:
    try:
        import websockets  # noqa: F401
    except ImportError:
        print("the `websockets` package is missing: use .venv/bin/python sonos_probe.py ws ... (see README)")
        return 2
    run_dir = new_run_dir("ws_" + ("info" if args.info_only else args.fmt))
    log = RunLog(run_dir / "ws.jsonl")
    log.say("results in %s" % run_dir)
    return asyncio.run(_run_ws(args, log, run_dir))


async def _run_ws(args, log: RunLog, run_dir: Path) -> int:
    from .rig import Poller
    c = SonosWs(args.ip, args.ws_port, log)
    session_id = None
    try:
        await c.connect()
        i = c.info
        log.say("player info: playerId %s, householdId %s, groupId %s, websocketUrl %s" % (
            c.player_id, c.household, c.group_id, i.get("websocketUrl")))
        d = i.get("device", {})
        log.say("device: %s %s sw %s api %s (%s)" % (d.get("modelDisplayName"), d.get("serialNumber"), d.get("softwareVersion"), d.get("apiVersion"),
                                                       ",".join(d.get("capabilities", []))))
        groups = await c.get_groups()
        for g in groups.get("groups", []):
            log.say("group %s: %r coordinator %s players %s state %s" % (g.get("id"), g.get("name"), g.get("coordinatorId"), g.get("playerIds"), g.get("playbackState")))
        log.say("using group %s" % c.group_id)
        ts_, tr, st = await c.playback_status()
        log.say("playback status: state %s position %s ms (round trip %.0f ms)" % (st.get("playbackState"), st.get("positionMillis"), (tr - ts_) * 1000))
        if args.info_only:
            return 0
        try:
            await c.subscribe_playback()
        except WsError as e:
            log.say("subscribe failed (continuing with polling only): %s" % e)
        from .server import StreamServer, summarize_conns
        from .sources import make_source_factory, stream_path
        import secrets
        need = args.duration + args.prebuffer + 90
        factory = make_source_factory(args.fmt, seconds_needed=need, log=lambda m: log.say(m))
        path = stream_path(args.fmt)
        server = StreamServer({path: factory}, port=args.port, prebuffer=args.prebuffer, log=log)
        server.start()
        mac_ip = args.local_ip or local_ip_for(args.ip)
        from .upnp import build_uri
        url = build_uri("http://%s:%d%s?r=%s" % (mac_ip, server.port, path, secrets.token_hex(3)), args.scheme)
        # optional UPnP position poll alongside
        upnp_samples: List[Tuple[float, float, str]] = []
        upnp_poller = None
        if args.also_upnp_poll:
            from .upnp import SoapClient
            cl = SoapClient(args.ip, args.soap_port, timeout=3.0)
            upnp_poller = Poller("upnp-pos", args.poll_hz, cl.position_info,
                                 lambda t0, t1, r: upnp_samples.append((t0, t1, r.get("RelTime", ""))),
                                 lambda t0, t1, e: log.emit("upnp_err", err=repr(e)))
            upnp_poller.start()
        session_id = await c.create_session(args.app_id, args.app_context)
        log.say("session %s created; loadStreamUrl %s" % (session_id, url))
        t_load = mono()
        await c.load_stream_url(session_id, url)
        t_loaded = mono()
        log.say("loadStreamUrl acknowledged after %.0f ms" % ((t_loaded - t_load) * 1000))
        samples: List[Tuple[float, float, Optional[float], str]] = []
        period = 1.0 / args.status_hz
        t_end = t_loaded + args.duration
        nxt = mono()
        last_state = None
        next_status = mono() + 10
        while mono() < t_end:
            now = mono()
            if now < nxt:
                await asyncio.sleep(nxt - now)
                continue
            nxt += period
            try:
                ts_, tr, b = await c.playback_status()
            except Exception as e:
                log.emit("wspos_err", err=repr(e))
                continue
            pos = b.get("positionMillis")
            stt = b.get("playbackState", "")
            samples.append((ts_, tr, pos / 1000.0 if pos is not None else None, stt))
            log.emit("wspos", t=tr, ts=round(ts_, 5), pos=pos, prev=b.get("previousPositionMillis"), st=stt)
            if stt != last_state:
                log.say("playback state %s (%.2f s after loadStreamUrl), positionMillis %s" % (stt, tr - t_loaded, pos), kind="ws_state")
                last_state = stt
            if mono() >= next_status:
                next_status += 10
                log.say("positionMillis %s, state %s, %s" % (pos, stt, "stream: %d conns, %.1f s sent" % (
                    len(server.conns), sum(x.samples for x in server.conns) / 44100.0)))
        t_stop = mono()
        try:
            await c.suspend(session_id)
            log.say("session suspended")
        except Exception as e:
            log.say("suspend failed (%s), trying pause" % e)
            try:
                await c.command("playback", "pause", groupId=c.group_id)
            except Exception as e2:
                log.say("pause failed too: %s" % e2)
        end = mono() + 10
        conns = [x for x in server.conns if x.method == "GET"]
        while mono() < end and not (conns and all(x.t_closed for x in conns)):
            await asyncio.sleep(0.02)
            conns = [x for x in server.conns if x.method == "GET"]
        closed = [x.t_closed for x in conns if x.t_closed]
        stop_to_close = (max(closed) - t_stop) if closed and len(closed) == len(conns) else None
        log.say("stream connection closed %s after the stop command" % (fmt(stop_to_close, 3, " s") if stop_to_close is not None else "NOT (still open after 10 s)"))
        stats = ws_position_stats(samples, t_loaded, t_stop + 1)
        log.say("positionMillis: %s" % json.dumps(stats))
        summ = {"ws_position": stats, "stop_to_close_s": stop_to_close, "connections": summarize_conns(server.conns)}
        (run_dir / "ws_summary.json").write_text(json.dumps(summ, indent=2, default=str))
        for row in summ["connections"]:
            log.say("conn %(conn)d %(method)s %(target)s UA=%(user_agent)r bytes %(bytes)d closed by %(closed_by)s (%(how)s)" % row)
        if stats.get("advancing"):
            log.say("RESULT: positionMillis advances in real time (%.4f s/s) with steps of >= %s ms" % (stats["slope_s_per_s"], fmt(stats.get("min_step_ms"), 1)))
        else:
            log.say("RESULT: positionMillis does NOT behave like a running clock for this stream (see ws_summary.json): %s" % json.dumps(stats))
        server.stop()
        if upnp_poller:
            upnp_poller.halt()
        return 0
    except WsError as e:
        log.say("ERROR %s" % e)
        return 1
    finally:
        await c.close()
        log.close()
