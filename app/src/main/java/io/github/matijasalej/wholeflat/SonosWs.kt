package io.github.matijasalej.wholeflat

import io.github.matijasalej.wholeflat.sync.SonosWsEvents
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/**
 * The Sonos local websocket API (wss://IP:1443/websocket/api), used for one thing: a fresh
 * `positionMillis` of the group, asked for with `playback:getPlaybackStatus`. The position runs
 * 0.156 s +- 4 ms ahead of the sound and tracks the speaker's clock, which is what the kitchen is
 * synchronised with. The protocol was taken from the spikes (spikes/sp/cmd_ws.py): a JSON array
 * [header, body] per message, sent as a binary frame, answered as a text frame; the speaker's
 * certificate is self-signed, so it is not verified.
 */
class SonosWs {

    class Position(val sentNanos: Long, val receivedNanos: Long, val positionMs: Long, val state: String, val error: String = "")

    private class Pending(
        val sentNanos: Long,
        val onReply: (Long, Long, JSONObject, JSONObject) -> Unit,
        val onFail: (String) -> Unit,
    )

    @Volatile
    var isOpen = false
        private set

    /** closed, connecting, open, or failed */
    @Volatile
    var status = "closed"
        private set

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    var groupId = ""
        private set

    /** The last `playbackStatus` state the speaker announced, and when (the events come once we have subscribed). */
    @Volatile
    var lastEventState = ""
        private set

    @Volatile
    var lastEventNanos = 0L
        private set

    /** The last `playbackError` the speaker announced, and when. */
    @Volatile
    var lastPlaybackError = ""
        private set

    @Volatile
    var lastPlaybackErrorNanos = 0L
        private set

    @Volatile
    private var playerId = ""

    @Volatile
    private var householdId = ""

    @Volatile
    private var generation = 0

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var lastGroupRefreshNanos = 0L

    private val pending = ConcurrentHashMap<String, Pending>()

    /** Connects in the background; [isOpen] says when it is ready. */
    @Synchronized
    fun connect(ip: String) {
        close()
        val gen = ++generation
        status = "connecting"
        lastError = null
        thread(name = "wf-sonos-ws", isDaemon = true) {
            try {
                val info = fetchInfo(ip)
                if (gen != generation) return@thread
                playerId = info.optString("playerId")
                householdId = info.optString("householdId")
                groupId = info.optString("groupId")
                val url = info.optString("websocketUrl").ifEmpty { "wss://$ip:1443/websocket/api" }
                val request = Request.Builder()
                    .url(url)
                    .header("X-Sonos-Api-Key", API_KEY)
                    .header("Sec-WebSocket-Protocol", SUBPROTOCOL)
                    .build()
                val ws = client.newWebSocket(request, Listener(gen))
                synchronized(this) {
                    if (gen == generation) socket = ws else ws.cancel()
                }
            } catch (e: Exception) {
                if (gen == generation) {
                    status = "failed"
                    lastError = e.toString()
                    AppLog.add(TAG, "websocket to $ip failed: $e")
                }
            }
        }
    }

    @Synchronized
    fun close() {
        generation++
        isOpen = false
        status = "closed"
        val s = socket
        socket = null
        try {
            s?.close(1000, "bye")
        } catch (e: Exception) {
            // closed already
        }
        failPending("closed")
    }

    /** Asks for the position of the group; false if the socket is not open. */
    fun requestPosition(onPosition: (Position) -> Unit, onFail: (String) -> Unit): Boolean {
        if (!isOpen) return false
        return send(
            "playback",
            "getPlaybackStatus",
            JSONObject(),
            mapOf("groupId" to groupId),
            { sent, received, _, body ->
                onPosition(
                    Position(
                        sent,
                        received,
                        body.optLong("positionMillis", -1L),
                        body.optString("playbackState", ""),
                        SonosWsEvents.errorText(body),
                    ),
                )
            },
            { reason ->
                refreshGroupsSoon()
                onFail(reason)
            },
        )
    }

    private fun refreshGroupsSoon() {
        val now = System.nanoTime()
        if (now - lastGroupRefreshNanos < 3_000_000_000L) return
        lastGroupRefreshNanos = now
        send(
            "groups",
            "getGroups",
            JSONObject().put("includeDeviceInfo", false),
            mapOf("householdId" to householdId),
            { _, _, _, body -> readGroups(body) },
            { reason -> AppLog.add(TAG, "getGroups failed: $reason") },
        )
    }

    private fun readGroups(body: JSONObject) {
        val groups = body.optJSONArray("groups") ?: return
        for (i in 0 until groups.length()) {
            val g = groups.optJSONObject(i) ?: continue
            if (g.optString("coordinatorId") == playerId) {
                val id = g.optString("id")
                if (id.isNotEmpty() && id != groupId) {
                    AppLog.add(TAG, "group id is now $id")
                    groupId = id
                    subscribe()
                }
                return
            }
        }
    }

    private fun send(
        namespace: String,
        command: String,
        body: JSONObject,
        path: Map<String, String>,
        onReply: (Long, Long, JSONObject, JSONObject) -> Unit,
        onFail: (String) -> Unit,
    ): Boolean {
        val ws = socket ?: return false
        val cmdId = UUID.randomUUID().toString().replace("-", "")
        val header = JSONObject()
        header.put("namespace", "$namespace:1")
        header.put("command", command)
        header.put("cmdId", cmdId)
        for ((key, value) in path) header.put(key, value)
        val message = JSONArray()
        message.put(header)
        message.put(body)
        val sent = System.nanoTime()
        pending[cmdId] = Pending(sent, onReply, onFail)
        // answers that never came
        val iterator = pending.entries.iterator()
        while (iterator.hasNext()) {
            if (sent - iterator.next().value.sentNanos > 10_000_000_000L) iterator.remove()
        }
        // a binary frame, as the Sonos app library does
        val ok = ws.send(message.toString().encodeUtf8())
        if (!ok) pending.remove(cmdId)
        return ok
    }

    private fun failPending(reason: String) {
        val all = pending.values.toList()
        pending.clear()
        for (p in all) {
            try {
                p.onFail(reason)
            } catch (e: Exception) {
                // the caller's problem
            }
        }
    }

    private fun handle(text: String) {
        val received = System.nanoTime()
        try {
            val array = JSONArray(text)
            val header = array.getJSONObject(0)
            val body = if (array.length() > 1) array.optJSONObject(1) ?: JSONObject() else JSONObject()
            val id = header.optString("cmdId", "")
            if (id.isEmpty()) {
                onEvent(header, body) // an event, not an answer
                return
            }
            val p = pending.remove(id) ?: return
            if (!header.optBoolean("success", true) || body.has("errorCode")) {
                p.onFail(body.optString("errorCode", "error") + " " + body.optString("reason", ""))
            } else {
                p.onReply(p.sentNanos, received, header, body)
            }
        } catch (e: Exception) {
            // not a message we understand
        }
    }

    /** Asks the speaker for the playback events of the group (best effort: the answers are what the kitchen relies on). */
    private fun subscribe() {
        if (groupId.isEmpty()) return
        val ok = send(
            "playback",
            "subscribe",
            JSONObject(),
            mapOf("groupId" to groupId),
            { _, _, _, _ -> AppLog.add(TAG, "subscribed to the playback events of group $groupId") },
            { reason -> AppLog.add(TAG, "no playback events ($reason), only the answers are used") },
        )
        if (!ok) AppLog.add(TAG, "cannot subscribe to the playback events: the socket is not open")
    }

    private fun onEvent(header: JSONObject, body: JSONObject) {
        val event = SonosWsEvents.parse(header, body) ?: return
        val now = System.nanoTime()
        when (event.type) {
            "playbackError" -> {
                lastPlaybackError = event.error
                lastPlaybackErrorNanos = now
                AppLog.add(TAG, "the speaker reports a playback error: ${event.error}")
            }
            "playbackStatus" -> {
                if (event.state != lastEventState) AppLog.add(TAG, "playback state ${event.state}" + if (event.error.isNotEmpty()) " (${event.error})" else "")
                lastEventState = event.state
                lastEventNanos = now
                if (event.error.isNotEmpty()) {
                    lastPlaybackError = event.error
                    lastPlaybackErrorNanos = now
                }
            }
        }
    }

    /** One line for the log when a start failed: the socket's state and what the speaker last announced. */
    fun describe(): String {
        val now = System.nanoTime()
        val sb = StringBuilder("websocket ").append(status)
        if (lastEventState.isNotEmpty()) {
            sb.append(", last state ").append(lastEventState).append(" (").append((now - lastEventNanos) / 1_000_000_000L).append(" s ago)")
        }
        if (lastPlaybackError.isNotEmpty()) {
            sb.append(", error ").append(lastPlaybackError).append(" (").append((now - lastPlaybackErrorNanos) / 1_000_000_000L).append(" s ago)")
        }
        return sb.toString()
    }

    private inner class Listener(private val gen: Int) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (gen != generation) return
            status = "loading groups"
            val ok = send(
                "groups",
                "getGroups",
                JSONObject().put("includeDeviceInfo", false),
                mapOf("householdId" to householdId),
                { _, _, _, body ->
                    readGroups(body)
                    markOpen()
                },
                { reason ->
                    AppLog.add(TAG, "getGroups failed: $reason, using the group of the player info")
                    markOpen()
                },
            )
            if (!ok) markOpen()
        }

        private fun markOpen() {
            if (gen != generation) return
            isOpen = true
            status = "open"
            AppLog.add(TAG, "websocket open, group $groupId")
            subscribe()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (gen == generation) handle(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (gen == generation) handle(bytes.utf8())
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            closedBy("closing $code $reason")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            closedBy("closed $code $reason")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            closedBy("failure: $t")
        }

        private fun closedBy(why: String) {
            if (gen != generation) return
            isOpen = false
            status = "closed"
            lastError = why
            AppLog.add(TAG, "websocket $why")
            failPending(why)
        }
    }

    private fun fetchInfo(ip: String): JSONObject {
        val request = Request.Builder()
            .url("https://$ip:1443/api/v1/players/local/info")
            .header("X-Sonos-Api-Key", API_KEY)
            .build()
        return client.newBuilder().callTimeout(5, TimeUnit.SECONDS).build().newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            JSONObject(response.body?.string() ?: "{}")
        }
    }

    companion object {
        private const val TAG = "sonos-ws"
        private const val API_KEY = "123e4567-e89b-12d3-a456-426655440000"
        private const val SUBPROTOCOL = "v1.api.smartspeaker.audio"

        /** The speaker's certificate is self-signed and has no usable name, so nothing is verified. */
        private val client: OkHttpClient by lazy {
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}

                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}

                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val context = SSLContext.getInstance("TLS")
            context.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
            OkHttpClient.Builder()
                .sslSocketFactory(context.socketFactory, trustAll)
                .hostnameVerifier { _, _ -> true }
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .pingInterval(55, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build()
        }
    }
}
