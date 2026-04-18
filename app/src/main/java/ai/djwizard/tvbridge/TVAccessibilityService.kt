package ai.djwizard.tvbridge

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
// WebSocket too, plus pairing, plus state. One owner for everything network
// and key-related.
class TVAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wsJob: Job? = null
    private var pairJob: Job? = null
    private var webSocket: WebSocket? = null

    private lateinit var config: ConfigStore

    private val client: OkHttpClient by lazy {
        // No pingInterval — Caddy's reverse_proxy drops WS control frames.
        // TCP keepalive + the relay's command timeout cover liveness.
        OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.SECONDS)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        config = ConfigStore(applicationContext)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "accessibility service connected")
        instance = this
        startOrContinuePairing()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "accessibility service unbound")
        wsJob?.cancel()
        pairJob?.cancel()
        webSocket?.close(1000, "service unbound")
        stateSink.value = BridgeState.AwaitingAccessibility
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* no-op */ }
    override fun onInterrupt() { /* no-op */ }

    // Kicks off pairing or the WebSocket loop depending on what's in storage.
    // Called on service bind; the MainActivity can also trigger pairing
    // explicitly (via beginPairing()) after the user wipes state.
    private fun startOrContinuePairing() {
        val token = config.token
        if (token.isBlank()) {
            beginPairing()
        } else {
            stateSink.value = BridgeState.Connecting
            startWsLoop()
        }
    }

    fun beginPairing() {
        pairJob?.cancel()
        wsJob?.cancel()
        webSocket?.close(1000, "repairing")
        stateSink.value = BridgeState.NeedsPairing
        pairJob = scope.launch {
            val relay = RelayClient(BuildConfig.RELAY_URL)
            var backoffMs = 2_000L
            while (scope.coroutineContext[Job]?.isActive == true) {
                val result = runCatching { withContext(Dispatchers.IO) { relay.pairInit() } }
                result.fold(
                    onSuccess = { r ->
                        Log.i(TAG, "pair/init code=${r.code} token=***")
                        config.token = r.token
                        config.pendingPairCode = r.code
                        stateSink.value = BridgeState.Pairing(r.code)
                        // Once we have a token, start the WS loop — it will
                        // reject until the code is claimed, then succeed.
                        startWsLoop()
                        return@launch
                    },
                    onFailure = { t ->
                        Log.w(TAG, "pair/init failed: ${t.message}")
                        stateSink.value = BridgeState.Error("pair/init: ${t.message}")
                        delay(backoffMs)
                        backoffMs = (backoffMs * 2).coerceAtMost(30_000)
                    },
                )
            }
        }
    }

    private fun startWsLoop() {
        if (wsJob?.isActive == true) return
        wsJob = scope.launch {
            var backoffMs = 1_000L
            while (scope.coroutineContext[Job]?.isActive == true) {
                val token = config.token
                if (token.isBlank()) {
                    stateSink.value = BridgeState.NeedsPairing
                    return@launch
                }
                val wsUrl = toWs(BuildConfig.RELAY_URL) + "/bridge"
                val req = Request.Builder()
                    .url(wsUrl)
                    .header("Authorization", "Bearer $token")
                    .build()
                val pendingCode = config.pendingPairCode
                stateSink.value = if (pendingCode != null) BridgeState.Pairing(pendingCode) else BridgeState.Connecting
                Log.i(TAG, "dialing $wsUrl")
                val latch = kotlinx.coroutines.CompletableDeferred<Unit>()
                val ws = client.newWebSocket(req, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        Log.i(TAG, "ws open status=${response.code}")
                        backoffMs = 1_000L
                        config.pendingPairCode = null
                        stateSink.value = BridgeState.Online
                    }
                    override fun onMessage(webSocket: WebSocket, text: String) = handleFrame(webSocket, text)
                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handleFrame(webSocket, bytes.utf8())
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        Log.i(TAG, "ws closing code=$code reason=$reason")
                        webSocket.close(code, reason)
                    }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        // 401 is the expected response while a pair code is
                        // awaiting claim — the token exists but isn't yet
                        // bound to a device_id. Keep retrying; the user
                        // clears state explicitly via the Reset button.
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
                // While pairing is pending, poll faster so claim transitions
                // to Online feel instant; once online-and-dropped, backoff.
                val pendingNow = config.pendingPairCode != null
                val sleep = if (pendingNow) 2_000L else backoffMs
                delay(sleep)
                if (!pendingNow) backoffMs = (backoffMs * 2).coerceAtMost(30_000)
            }
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
            OP_LAUNCH_APP -> handleLaunchApp(frame)
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
            data = mapOf("key" to key),
        )
    }

    private fun handleLaunchApp(frame: InboundFrame): OutboundFrame {
        val data = frame.params["data"].orEmpty()
        if (data.isEmpty()) return OutboundFrame(frame.id, ok = false, message = "data is required")
        val pkg = frame.params["package"].orEmpty()

        val uri = try {
            Uri.parse(data)
        } catch (t: Throwable) {
            return OutboundFrame(frame.id, ok = false, message = "invalid data URI: ${t.message}")
        }
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (pkg.isNotEmpty()) setPackage(pkg)
        }
        return try {
            startActivity(intent)
            Log.i(TAG, "launch_app data=$data package=$pkg ok=true")
            OutboundFrame(
                id = frame.id,
                ok = true,
                data = buildMap {
                    put("data", data)
                    if (pkg.isNotEmpty()) put("package", pkg)
                },
            )
        } catch (t: Throwable) {
            // ActivityNotFoundException (package not installed) and
            // SecurityException (manifest queries block on API 30+) both land here.
            Log.w(TAG, "launch_app failed data=$data package=$pkg: ${t.message}")
            OutboundFrame(frame.id, ok = false, message = "launch failed: ${t.message}")
        }
    }

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

    companion object {
        private const val TAG = "TVBridge"
        @Volatile
        private var instance: TVAccessibilityService? = null

        fun isEnabled(): Boolean = instance != null
        fun get(): TVAccessibilityService? = instance

        // Single process-wide state stream. The service owns writes; the UI
        // observes. When the service unbinds (user disables accessibility),
        // we emit AwaitingAccessibility so the UI snaps back.
        private val stateSink = MutableStateFlow<BridgeState>(BridgeState.AwaitingAccessibility)
        val state: StateFlow<BridgeState> = stateSink.asStateFlow()
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
