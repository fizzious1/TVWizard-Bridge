package ai.djwizard.tvbridge

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// The AccessibilityService is already a long-lived, system-managed component —
// it stays bound as long as the user leaves the toggle on. We give it the
// WebSocket too, which avoids Android 14's FGS-type permission matrix and
// keeps the app to one owner of the network + one owner of key dispatch.
class TVAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wsJob: Job? = null
    private var webSocket: WebSocket? = null

    // No OkHttp ping interval — Caddy's reverse_proxy doesn't forward WebSocket
    // ping frames reliably, and TCP keepalive plus the relay's 5s command
    // timeout covers liveness. Inactive bridges are cheap for the relay to hold.
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.SECONDS)
            .build()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "accessibility service connected, starting ws loop")
        instance = this
        if (wsJob?.isActive != true) {
            wsJob = scope.launch { runLoop() }
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "accessibility service unbound, stopping ws loop")
        wsJob?.cancel()
        webSocket?.close(1000, "service unbound")
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* no-op */ }
    override fun onInterrupt() { /* no-op */ }

    fun dispatchKey(key: String): Boolean {
        val action = mapKey(key)
        return when (action) {
            is KeyAction.Global -> {
                val ok = performGlobalAction(action.globalAction)
                Log.i(TAG, "dispatchKey key=$key global=${action.globalAction} ok=$ok")
                ok
            }
            is KeyAction.Volume -> {
                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val dir = if (action.direction > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
                Log.i(TAG, "dispatchKey key=$key volume direction=${action.direction} ok=true")
                true
            }
            KeyAction.Unsupported -> {
                Log.w(TAG, "dispatchKey key=$key unsupported")
                false
            }
        }
    }

    // runLoop reconnects the WebSocket with exponential backoff. Every inbound
    // frame is acked so the relay's Send() can resolve its waiters.
    private suspend fun runLoop() {
        var backoffMs = 1_000L
        while (scope.coroutineContext[Job]?.isActive == true) {
            val token = BuildConfig.BRIDGE_TOKEN
            if (token.isBlank()) {
                Log.w(TAG, "no BRIDGE_TOKEN configured; sleeping")
                delay(30_000)
                continue
            }
            val wsUrl = toWs(BuildConfig.RELAY_URL) + "/bridge"
            val req = Request.Builder()
                .url(wsUrl)
                .header("Authorization", "Bearer $token")
                .build()
            Log.i(TAG, "dialing $wsUrl as ${BuildConfig.DEVICE_ID}")
            val latch = kotlinx.coroutines.CompletableDeferred<Unit>()
            val ws = client.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.i(TAG, "ws open status=${response.code}")
                    backoffMs = 1_000L
                }
                override fun onMessage(webSocket: WebSocket, text: String) = handleFrame(webSocket, text)
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handleFrame(webSocket, bytes.utf8())
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Log.i(TAG, "ws closing code=$code reason=$reason")
                    webSocket.close(code, reason)
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.w(TAG, "ws failure: ${t.message} status=${response?.code}")
                    latch.complete(Unit)
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.i(TAG, "ws closed code=$code reason=$reason")
                    latch.complete(Unit)
                }
            })
            webSocket = ws
            latch.await()
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(30_000)
        }
    }

    private fun handleFrame(ws: WebSocket, text: String) {
        val frame = parseInbound(text) ?: run {
            Log.w(TAG, "drop unparseable frame: $text")
            return
        }
        Log.i(TAG, "<- id=${frame.id} op=${frame.op} params=${frame.params}")
        val out = when (frame.op) {
            OP_KEY -> handleKey(frame)
            else -> OutboundFrame(frame.id, ok = false, message = "unsupported op: ${frame.op}")
        }
        ws.send(encodeOutbound(out))
        Log.i(TAG, "-> id=${out.id} ok=${out.ok} message=${out.message}")
    }

    private fun handleKey(frame: InboundFrame): OutboundFrame {
        val key = frame.params["key"].orEmpty()
        if (key.isEmpty()) return OutboundFrame(frame.id, ok = false, message = "key is required")
        val ok = dispatchKey(key)
        return OutboundFrame(
            id = frame.id,
            ok = ok,
            message = if (ok) "" else "could not dispatch $key",
            data = mapOf("key" to key, "device_id" to BuildConfig.DEVICE_ID),
        )
    }

    companion object {
        private const val TAG = "TVBridge"
        @Volatile
        private var instance: TVAccessibilityService? = null

        fun isEnabled(): Boolean = instance != null
        fun get(): TVAccessibilityService? = instance
    }
}

private fun toWs(httpUrl: String): String = when {
    httpUrl.startsWith("https://") -> "wss://" + httpUrl.removePrefix("https://")
    httpUrl.startsWith("http://") -> "ws://" + httpUrl.removePrefix("http://")
    else -> httpUrl
}

private fun parseInbound(text: String): InboundFrame? = try {
    val j = JSONObject(text)
    val id = j.optString("id")
    val op = j.optString("op")
    val params = mutableMapOf<String, String>()
    j.optJSONObject("params")?.let { p ->
        for (k in p.keys()) params[k] = p.optString(k)
    }
    if (id.isEmpty() || op.isEmpty()) null else InboundFrame(id, op, params)
} catch (_: Throwable) { null }

private fun encodeOutbound(frame: OutboundFrame): String {
    val j = JSONObject()
    j.put("id", frame.id)
    j.put("ok", frame.ok)
    if (frame.message.isNotEmpty()) j.put("message", frame.message)
    if (frame.data.isNotEmpty()) {
        val d = JSONObject()
        frame.data.forEach { (k, v) -> d.put(k, v) }
        j.put("data", d)
    }
    return j.toString()
}
