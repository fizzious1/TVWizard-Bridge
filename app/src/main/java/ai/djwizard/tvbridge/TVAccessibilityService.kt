package ai.djwizard.tvbridge

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
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
    private lateinit var playback: PlaybackController
    private lateinit var volume: VolumeController

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
        playback = PlaybackController(applicationContext)
        volume = VolumeController(applicationContext)
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
            OP_LIST_APPS -> handleListApps(frame)
            OP_OBSERVE -> handleObserve(frame)
            OP_PLAYBACK -> playback.handle(frame)
            OP_VOLUME -> volume.handle(frame)
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

    // handleListApps enumerates launchable apps on the TV. Uses
    // getInstalledApplications + getLeanbackLaunchIntentForPackage rather
    // than pm.queryIntentActivities because the latter's default filter
    // flags drop many LEANBACK activities. Requires QUERY_ALL_PACKAGES on
    // Android 11+ to see past the package-visibility sandbox.
    private fun handleListApps(frame: InboundFrame): OutboundFrame {
        val pm = packageManager
        val installed = pm.getInstalledApplications(0)
        val rows = mutableListOf<Triple<String, String, Boolean>>() // pkg, label, leanback
        for (app in installed) {
            if (!app.enabled) continue
            val leanbackIntent = pm.getLeanbackLaunchIntentForPackage(app.packageName)
            val mobileIntent = pm.getLaunchIntentForPackage(app.packageName)
            if (leanbackIntent == null && mobileIntent == null) continue
            val label = pm.getApplicationLabel(app).toString()
            rows += Triple(app.packageName, label, leanbackIntent != null)
        }
        rows.sortWith(compareByDescending<Triple<String, String, Boolean>> { it.third }.thenBy { it.second.lowercase() })

        val arr = JSONArray()
        for ((pkg, label, leanback) in rows) {
            val entry = org.json.JSONObject()
            entry.put("package", pkg)
            entry.put("label", label)
            entry.put("leanback", leanback)
            arr.put(entry)
        }
        Log.i(TAG, "list_apps returning ${rows.size} apps (${rows.count { it.third }} leanback)")
        return OutboundFrame(
            id = frame.id,
            ok = true,
            data = mapOf("apps_json" to arr.toString()),
        )
    }

    private fun handleLaunchApp(frame: InboundFrame): OutboundFrame {
        val data = frame.params["data"].orEmpty()
        if (data.isEmpty()) return OutboundFrame(frame.id, ok = false, message = "data is required")
        val pkg = frame.params["package"].orEmpty()

        // URI_INTENT_SCHEME lets parseUri honor the full `intent://host/path#Intent;
        // key=value;...;end` form that JustWatch's Android-TV deep links use — the
        // `S.source=30` extra on Netflix, `package=com.hulu.livingroomplus` + custom
        // action on Hulu, launchFlags, everything. For a plain https:// or custom-
        // scheme URI, parseUri falls back to an ACTION_VIEW intent — same shape as
        // the old Intent(ACTION_VIEW, Uri.parse(...)) path it replaces.
        val intent = try {
            Intent.parseUri(data, Intent.URI_INTENT_SCHEME)
        } catch (t: Throwable) {
            return OutboundFrame(frame.id, ok = false, message = "invalid data URI: ${t.message}")
        }
        // Required when launching from a background service (AccessibilityService).
        // OR'd on top of any launchFlags= the intent URI already carried.
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Caller-supplied package wins over any package=... the intent URI parsed —
        // the MCP caller has the final say on which app handles the intent.
        if (pkg.isNotEmpty()) intent.setPackage(pkg)

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

    // handleObserve returns a flattened, capped snapshot of what's on the
    // active window right now — enough for Claude to decide the next key
    // press or deep link without re-serializing the whole AccessibilityNode
    // tree (which can run into megabytes on streaming apps).
    //
    // Wire contract pinned in TVWizard/docs/adr/0003-observe-wire-contract.md.
    // Snapshot shape mirrors the relay's observeSnapshot struct — keep in
    // sync on both sides or the relay will reject the payload as "malformed
    // observe payload".
    private fun handleObserve(frame: InboundFrame): OutboundFrame {
        val root = rootInActiveWindow
        if (root == null) {
            // The AccessibilityService is bound but can't read the active
            // window. Most common cause is Android 13+ "restricted settings"
            // blocking sideloaded accessibility services from seeing window
            // content until the user explicitly allows it. Nudge the user
            // with a TV-facing notification and surface the exact error code
            // the relay handler is matching on.
            postAccessibilityNotification()
            return OutboundFrame(frame.id, ok = false, message = ERR_ACCESSIBILITY_NOT_GRANTED)
        }
        return try {
            val snapshot = buildObserveSnapshot(root)
            OutboundFrame(
                id = frame.id,
                ok = true,
                data = mapOf("observe_json" to snapshot),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "observe failed: ${t.message}")
            OutboundFrame(frame.id, ok = false, message = "observe failed: ${t.message}")
        } finally {
            root.recycle()
        }
    }

    // NodeSnap is a POJO copy of the bits of an AccessibilityNodeInfo we
    // actually ship. Using it frees us from holding onto live Node refs
    // across the tree walk — every Node we touch is recycled immediately.
    private data class NodeSnap(val text: String, val desc: String, val clickable: Boolean)

    // maxVisibleNodes caps the snapshot per ADR-0003. 50 is enough for any
    // reasonable streaming-app screen; truncating at that point keeps Claude
    // within a sane token budget.
    private val maxVisibleNodes: Int = 50

    private fun buildObserveSnapshot(root: AccessibilityNodeInfo): String {
        val obj = org.json.JSONObject()
        obj.put("package", root.packageName?.toString() ?: "")
        // activity is best-effort — AccessibilityNodeInfo.className on the
        // root usually carries the top Activity's class name. Devices that
        // don't surface it leave this as "".
        obj.put("activity", root.className?.toString() ?: "")

        val focusedObj = org.json.JSONObject()
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null) {
            val t = focused.text?.toString().orEmpty()
            val d = focused.contentDescription?.toString().orEmpty()
            if (t.isNotEmpty()) focusedObj.put("text", t)
            if (d.isNotEmpty()) focusedObj.put("content_description", d)
            focused.recycle()
        }
        obj.put("focused", focusedObj)

        val collected = mutableListOf<NodeSnap>()
        // Collect one extra so we know whether truncation is needed.
        collectVisible(root, collected, maxVisibleNodes + 1)

        val truncated = collected.size > maxVisibleNodes
        val emit = if (truncated) collected.subList(0, maxVisibleNodes) else collected
        val arr = JSONArray()
        for (snap in emit) {
            val n = org.json.JSONObject()
            if (snap.text.isNotEmpty()) n.put("text", snap.text)
            if (snap.desc.isNotEmpty()) n.put("content_description", snap.desc)
            n.put("clickable", snap.clickable)
            arr.put(n)
        }
        if (truncated) {
            val marker = org.json.JSONObject()
            marker.put("text", "… (${collected.size - maxVisibleNodes} more)")
            marker.put("clickable", false)
            arr.put(marker)
        }
        obj.put("visible", arr)
        return obj.toString()
    }

    // collectVisible DFS-walks the accessibility tree and copies nodes with
    // user-visible text or content description into `out`. Each Node it
    // touches is recycled on the way out. Stops growing `out` once `cap` is
    // hit — we still descend to ensure recycling, but don't emit more rows.
    private fun collectVisible(node: AccessibilityNodeInfo, out: MutableList<NodeSnap>, cap: Int) {
        if (out.size < cap) {
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            if (text.isNotEmpty() || desc.isNotEmpty()) {
                out.add(NodeSnap(text, desc, node.isClickable))
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                collectVisible(child, out, cap)
            } finally {
                child.recycle()
            }
        }
    }

    // notificationChannelId is a module-level constant because posting more
    // than one variant confuses the settings UI.
    private val notificationChannelId: String = "tvwizard_setup"
    private val accessibilitySetupNotificationId: Int = 1001

    // postAccessibilityNotification is the ADR-0003 "auto-surface a setup
    // prompt on the TV" flow. In practice this fires when the service is
    // bound but rootInActiveWindow returned null — most commonly Android
    // 13's restricted-settings gate on sideloaded accessibility services.
    private fun postAccessibilityNotification() {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    notificationChannelId,
                    "TVWizard setup",
                    NotificationManager.IMPORTANCE_HIGH,
                )
                nm.createNotificationChannel(channel)
            }
            val settings = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pending = PendingIntent.getActivity(
                this,
                0,
                settings,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = Notification.Builder(this, notificationChannelId)
                .setSmallIcon(R.drawable.bridge_banner)
                .setContentTitle("Finish TVWizard setup")
                .setContentText("Tap to let TVWizard read the screen.")
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
            nm.notify(accessibilitySetupNotificationId, notification)
            Log.i(TAG, "posted accessibility-not-granted notification")
        } catch (t: Throwable) {
            // Notifications can fail if the channel post-permission is
            // revoked (Android 13+ POST_NOTIFICATIONS). Not fatal — we still
            // return the accessibility_not_granted error to the relay.
            Log.w(TAG, "could not post setup notification: ${t.message}")
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
            is KeyAction.Dpad -> dispatchDpad(key, action.direction)
            KeyAction.Select -> dispatchSelect(key)
            KeyAction.Unsupported -> {
                Log.w(TAG, "dispatchKey key=$key unsupported")
                false
            }
        }
    }

    // dispatchDpad: API-33+ has proper GLOBAL_ACTION_DPAD_* that fire real
    // key events into the focused window. Below that, the best an
    // AccessibilityService can do without INJECT_EVENTS is to find the
    // focused node's nearest scrollable ancestor and ask it to scroll in
    // the requested direction. This covers list/grid navigation in apps
    // that expose scrollable AccessibilityNodeInfos (YouTube, most TV
    // launchers); it won't work in apps that handle DPAD purely via
    // onKeyDown without exposing scroll actions.
    private fun dispatchDpad(key: String, dir: DpadDirection): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val global = when (dir) {
                DpadDirection.UP -> AccessibilityService.GLOBAL_ACTION_DPAD_UP
                DpadDirection.DOWN -> AccessibilityService.GLOBAL_ACTION_DPAD_DOWN
                DpadDirection.LEFT -> AccessibilityService.GLOBAL_ACTION_DPAD_LEFT
                DpadDirection.RIGHT -> AccessibilityService.GLOBAL_ACTION_DPAD_RIGHT
            }
            if (performGlobalAction(global)) {
                Log.i(TAG, "dispatchKey key=$key global-dpad ok=true")
                return true
            }
        }
        // Pre-API-33 fallback: walk up from the focused node to a scrollable
        // ancestor and fire a directional scroll.
        val root = rootInActiveWindow ?: run {
            Log.w(TAG, "dispatchKey key=$key no root window")
            return false
        }
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        val scrollable = findScrollableAncestor(focused)
            ?: findFirstScrollable(root)
        if (scrollable == null) {
            Log.w(TAG, "dispatchKey key=$key no scrollable node found")
            return false
        }
        val scrollAction = when (dir) {
            DpadDirection.UP -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            DpadDirection.DOWN -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            DpadDirection.LEFT -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            DpadDirection.RIGHT -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        }
        val ok = scrollable.performAction(scrollAction)
        Log.i(TAG, "dispatchKey key=$key scroll=$scrollAction ok=$ok scrollable=${scrollable.className}")
        return ok
    }

    private fun dispatchSelect(key: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val ok = performGlobalAction(AccessibilityService.GLOBAL_ACTION_DPAD_CENTER)
            if (ok) {
                Log.i(TAG, "dispatchKey key=$key dpad-center ok=true")
                return true
            }
        }
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            ?: return false
        val ok = focused.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Log.i(TAG, "dispatchKey key=$key click ok=$ok target=${focused.className}")
        return ok
    }

    private fun findScrollableAncestor(start: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var cur = start
        while (cur != null) {
            if (cur.isScrollable) return cur
            cur = cur.parent
        }
        return null
    }

    private fun findFirstScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Breadth-ish depth-first walk; most TV UIs have a scrollable near
        // the top of the tree, so this terminates fast.
        if (root.isScrollable) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            findFirstScrollable(child)?.let { return it }
        }
        return null
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
