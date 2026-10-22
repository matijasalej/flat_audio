"""Mock of the Sonos local websocket API on top of MockRenderer (tests only; needs `websockets`).

Implements exactly the behaviour documented in cmd_ws.py (taken from aiosonos):
HTTPS info endpoint + wss://.../websocket/api with the API key header, the
v1.api.smartspeaker.audio subprotocol, [header, body] messages as JSON.
"""
from __future__ import annotations

import asyncio
import http
import json
import ssl
import subprocess
import threading
from pathlib import Path
from typing import Any, Dict, List, Optional

from .cmd_ws import LOCAL_API_KEY, SUBPROTOCOL
from .mock import MockRenderer
from .common import have_cmd

HOUSEHOLD = "Sonos_MockHousehold0001"
PLAYER = "RINCON_MOCK000000001400"
GROUP = PLAYER + ":1"


def make_selfsigned(dirpath: Path) -> ssl.SSLContext:
    dirpath.mkdir(parents=True, exist_ok=True)
    key, crt = dirpath / "key.pem", dirpath / "cert.pem"
    exe = have_cmd("openssl")
    if not exe:
        raise RuntimeError("openssl not found")
    subprocess.run([exe, "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", str(key), "-out", str(crt), "-days", "2",
                    "-subj", "/CN=localhost"], check=True, capture_output=True)
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(str(crt), str(key))
    return ctx


class MockSonosWs:
    def __init__(self, renderer: MockRenderer, workdir: Path, host: str = "127.0.0.1", port: int = 0):
        self.r = renderer
        self.workdir = workdir
        self.host, self.port = host, port
        self.loop: Optional[asyncio.AbstractEventLoop] = None
        self.thread: Optional[threading.Thread] = None
        self.ready = threading.Event()
        self.seen: Dict[str, Any] = {"subprotocol": None, "api_key_ok": None, "frame_types": set(), "commands": []}
        self._stop: Optional[asyncio.Future] = None
        self.session_id = "mocksession-0001"

    def start(self) -> int:
        self.thread = threading.Thread(target=lambda: asyncio.run(self._main()), name="mock-ws", daemon=True)
        self.thread.start()
        if not self.ready.wait(10):
            raise RuntimeError("mock ws server did not start")
        return self.port

    def stop(self) -> None:
        if self.loop and self._stop and not self._stop.done():
            self.loop.call_soon_threadsafe(self._stop.set_result, True)
        if self.thread:
            self.thread.join(timeout=5)

    async def _main(self) -> None:
        from websockets.asyncio.server import serve
        self.loop = asyncio.get_running_loop()
        self._stop = self.loop.create_future()
        ctx = make_selfsigned(self.workdir)

        def process_request(connection, request):
            if request.headers.get("X-Sonos-Api-Key") != LOCAL_API_KEY:
                self.seen["api_key_ok"] = False
                return connection.respond(http.HTTPStatus.UNAUTHORIZED, "bad key\n")
            self.seen["api_key_ok"] = True
            if request.path == "/api/v1/players/local/info":
                info = {"_objectType": "discoveryInfo", "device": {"_objectType": "deviceInfo", "id": PLAYER, "modelDisplayName": "Mock Sonos Play",
                                                                     "serialNumber": "00-00-00-00-00-00:0", "softwareVersion": "99.9-00000",
                                                                     "apiVersion": "1.0", "capabilities": ["PLAYBACK", "CLOUD"]},
                        "householdId": HOUSEHOLD, "playerId": PLAYER, "groupId": GROUP, "locationId": "lc_mock",
                        "websocketUrl": "wss://%s:%d/websocket/api" % (self.host, self.port), "restUrl": "https://%s:%d/api/v1/players/local" % (self.host, self.port)}
                return connection.respond(http.HTTPStatus.OK, json.dumps(info) + "\n")
            return None

        async with serve(self._handler, self.host, self.port, ssl=ctx, subprotocols=[SUBPROTOCOL], process_request=process_request,
                         compression=None) as server:
            self.port = server.sockets[0].getsockname()[1]
            self.ready.set()
            await self._stop

    # -- one connection ---------------------------------------------------------------
    def _status_body(self) -> Dict[str, Any]:
        st = self.r._state_now()
        state = {"PLAYING": "PLAYBACK_STATE_PLAYING", "TRANSITIONING": "PLAYBACK_STATE_BUFFERING",
                 "PAUSED_PLAYBACK": "PLAYBACK_STATE_PAUSED"}.get(st, "PLAYBACK_STATE_IDLE")
        pos = int(self.r.position_s() * 1000)
        return {"_objectType": "playbackStatus", "availablePlaybackActions": {"_objectType": "playbackAction", "canPlay": True, "canPause": True},
                "isDucking": False, "playbackState": state, "playModes": {"_objectType": "playModes"},
                "positionMillis": pos, "previousPositionMillis": max(0, pos - 100)}

    async def _handler(self, ws) -> None:
        self.seen["subprotocol"] = ws.subprotocol
        subscribed = False
        async for raw in ws:
            self.seen["frame_types"].add("binary" if isinstance(raw, (bytes, bytearray)) else "text")
            try:
                header, body = json.loads(raw)
            except Exception:
                await ws.close(1007)
                return
            ns, cmd = header.get("namespace", ""), header.get("command", "")
            self.seen["commands"].append((ns, cmd))
            reply_h = {"namespace": ns, "response": cmd, "cmdId": header.get("cmdId"), "householdId": HOUSEHOLD}
            for k in ("groupId", "sessionId"):
                if k in header:
                    reply_h[k] = header[k]
            ok, out = True, {}
            if (ns, cmd) == ("groups:1", "getGroups"):
                out = {"_objectType": "groups", "partial": False,
                       "groups": [{"_objectType": "group", "coordinatorId": PLAYER, "id": GROUP, "name": "Kitchen (mock)",
                                   "playbackState": self._status_body()["playbackState"], "playerIds": [PLAYER]}],
                       "players": [{"_objectType": "player", "id": PLAYER, "name": "Kitchen (mock)", "softwareVersion": "99.9-00000"}]}
            elif (ns, cmd) == ("playback:1", "getPlaybackStatus"):
                out = self._status_body()
                reply_h["type"] = "playbackStatus"
            elif (ns, cmd) == ("playback:1", "subscribe"):
                subscribed = True
            elif (ns, cmd) == ("playback:1", "pause"):
                self.r._stop(pause=True)
            elif (ns, cmd) == ("playbackSession:1", "createSession"):
                if not body.get("appId") or not body.get("appContext"):
                    ok, out = False, {"errorCode": "ERROR_INVALID_PARAMETER", "reason": "appId/appContext required"}
                else:
                    out = {"_objectType": "sessionStatus", "sessionId": self.session_id, "sessionState": "SESSION_STATE_CONNECTED",
                           "sessionCreated": True, "customData": ""}
            elif (ns, cmd) == ("playbackSession:1", "loadStreamUrl"):
                url = body.get("streamUrl")
                if header.get("sessionId") != self.session_id or not url or not isinstance(body.get("stationMetadata"), dict):
                    ok, out = False, {"errorCode": "ERROR_INVALID_PARAMETER", "reason": "bad loadStreamUrl"}
                else:
                    with self.r.lock:
                        self.r.uri, self.r.meta, self.r.state = url, "", "STOPPED"
                    self.r._play()
            elif (ns, cmd) == ("playbackSession:1", "suspend"):
                self.r._stop()
            else:
                ok, out = False, {"errorCode": "ERROR_UNSUPPORTED_COMMAND", "reason": "%s %s" % (ns, cmd)}
            reply_h["success"] = ok
            await ws.send(json.dumps([reply_h, out]))
            if ok and subscribed and (ns, cmd) == ("playbackSession:1", "loadStreamUrl"):
                ev_h = {"namespace": "playback:1", "type": "playbackStatus", "groupId": GROUP, "householdId": HOUSEHOLD}
                await ws.send(json.dumps([ev_h, self._status_body()]))
