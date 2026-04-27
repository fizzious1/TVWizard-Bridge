package ai.djwizard.tvbridge

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

// PlaybackController implements Spec 0001.
//
// Primary path: MediaSessionManager.getActiveSessions(componentName) —
// where componentName is THIS bridge's AccessibilityService. From Android
// 14+ an enabled AccessibilityService can read active media sessions
// without holding BIND_NOTIFICATION_LISTENER_SERVICE; on older Android-TV
// builds the call may return an empty list or throw SecurityException.
// Both paths degrade to the accessibility-tree fallback.
//
// Fallback path: scrape any visible "MM:SS" / "HH:MM:SS" timecode from
// the accessibility tree, dispatch KEYCODE_MEDIA_PLAY_PAUSE for pause/
// resume. Best-effort; we mark `source: "accessibility"` so callers know
// the position is approximate.
//
// Wire contract pinned in /docs/specs/0001-playback-tools.md and the
// relay's tools_playback.go.
internal object PlaybackController {

    private const val TAG = "TVBridge.Playback"

    // Match HH:MM:SS or MM:SS — lazy parser used only by the accessibility
    // fallback. Anchored loosely so we tolerate "1:23 / 45:00" by matching
    // the first occurrence.
    private val TIMECODE_RE = Regex("""(\d{1,2}):(\d{2})(?::(\d{2}))?""")

    fun handle(
        context: Context,
        component: ComponentName,
        rootInActiveWindow: AccessibilityNodeInfo?,
        frame: InboundFrame,
    ): OutboundFrame {
        val cmd = frame.params[PARAM_CMD].orEmpty()
        val controller = activeController(context, component)
        return when (cmd) {
            CMD_GET -> handleGet(frame, controller, rootInActiveWindow)
            CMD_SEEK -> handleSeek(frame, controller)
            CMD_PAUSE -> handleTransport(frame, controller, pause = true)
            CMD_RESUME -> handleTransport(frame, controller, pause = false)
            else -> OutboundFrame(frame.id, ok = false, message = "unsupported playback cmd: $cmd")
        }
    }

    private fun activeController(context: Context, component: ComponentName): MediaController? {
        return try {
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
                ?: return null
            // Throws SecurityException on devices that still gate this on
            // BIND_NOTIFICATION_LISTENER_SERVICE despite the API-34 contract.
            msm.getActiveSessions(component).firstOrNull()
        } catch (t: Throwable) {
            Log.w(TAG, "getActiveSessions failed: ${t.message}")
            null
        }
    }

    private fun handleGet(
        frame: InboundFrame,
        controller: MediaController?,
        root: AccessibilityNodeInfo?,
    ): OutboundFrame {
        if (controller != null) {
            return mediaSessionSnapshot(frame.id, controller)
        }
        // Fallback: walk the accessibility tree for a visible timecode.
        if (root != null) {
            val scraped = scrapeTimecode(root)
            if (scraped != null) {
                val obj = JSONObject()
                obj.put("position_ms", scraped)
                obj.put("duration_ms", 0)
                obj.put("state", PLAYBACK_STATE_UNKNOWN)
                obj.put("source", PLAYBACK_SOURCE_ACCESSIBILITY)
                root.packageName?.toString()?.takeIf { it.isNotEmpty() }?.let { obj.put("app", it) }
                return OutboundFrame(frame.id, ok = true, data = mapOf(KEY_PLAYBACK_JSON to obj.toString()))
            }
        }
        return OutboundFrame(frame.id, ok = false, message = ERR_PLAYBACK_NO_SESSION)
    }

    private fun mediaSessionSnapshot(id: String, controller: MediaController): OutboundFrame {
        val obj = JSONObject()
        val md = controller.metadata
        val title = md?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
            ?: md?.getString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        if (!title.isNullOrEmpty()) obj.put("title", title)
        controller.packageName?.let { obj.put("app", it) }
        val duration = md?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        obj.put("duration_ms", duration)
        val ps = controller.playbackState
        obj.put("position_ms", ps?.position ?: 0L)
        obj.put("state", mapState(ps?.state))
        obj.put("source", PLAYBACK_SOURCE_MEDIA_SESSION)
        return OutboundFrame(id, ok = true, data = mapOf(KEY_PLAYBACK_JSON to obj.toString()))
    }

    private fun handleSeek(frame: InboundFrame, controller: MediaController?): OutboundFrame {
        if (controller == null) {
            return OutboundFrame(frame.id, ok = false, message = ERR_PLAYBACK_NO_SESSION)
        }
        val posStr = frame.params[PARAM_POSITION_MS]
        val deltaStr = frame.params[PARAM_DELTA_MS]
        val target: Long = when {
            !posStr.isNullOrEmpty() -> posStr.toLongOrNull()
                ?: return OutboundFrame(frame.id, ok = false, message = "position_ms must be an integer")
            !deltaStr.isNullOrEmpty() -> {
                val delta = deltaStr.toLongOrNull()
                    ?: return OutboundFrame(frame.id, ok = false, message = "delta_ms must be an integer")
                val cur = controller.playbackState?.position ?: 0L
                (cur + delta).coerceAtLeast(0L)
            }
            else -> return OutboundFrame(frame.id, ok = false, message = "seek requires position_ms or delta_ms")
        }
        // Apps that reject seeking (live TV) typically expose
        // PlaybackState.actions without ACTION_SEEK_TO. We could check
        // here and return playback_seek_unsupported up front — but the
        // contract is best-effort, and not every app populates actions
        // honestly. Dispatch and let the controller absorb it.
        return try {
            controller.transportControls.seekTo(target)
            mediaSessionSnapshot(frame.id, controller)
        } catch (t: Throwable) {
            Log.w(TAG, "seekTo failed: ${t.message}")
            OutboundFrame(frame.id, ok = false, message = ERR_PLAYBACK_SEEK_UNSUPPORTED)
        }
    }

    private fun handleTransport(frame: InboundFrame, controller: MediaController?, pause: Boolean): OutboundFrame {
        if (controller == null) {
            return OutboundFrame(frame.id, ok = false, message = ERR_PLAYBACK_NO_SESSION)
        }
        return try {
            if (pause) controller.transportControls.pause() else controller.transportControls.play()
            mediaSessionSnapshot(frame.id, controller)
        } catch (t: Throwable) {
            Log.w(TAG, "transport failed: ${t.message}")
            OutboundFrame(frame.id, ok = false, message = "transport_failed: ${t.message}")
        }
    }

    private fun mapState(state: Int?): String = when (state) {
        PlaybackState.STATE_PLAYING, PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING -> PLAYBACK_STATE_PLAYING
        PlaybackState.STATE_PAUSED -> PLAYBACK_STATE_PAUSED
        PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> PLAYBACK_STATE_BUFFERING
        PlaybackState.STATE_STOPPED, PlaybackState.STATE_NONE -> PLAYBACK_STATE_STOPPED
        null -> PLAYBACK_STATE_UNKNOWN
        else -> PLAYBACK_STATE_UNKNOWN
    }

    // scrapeTimecode walks the visible accessibility tree looking for the
    // first MM:SS / HH:MM:SS substring. Returns its position in ms, or
    // null if nothing matched. Best-effort fallback for apps that don't
    // publish a MediaSession (Paramount+ on ATV<2025).
    private fun scrapeTimecode(node: AccessibilityNodeInfo): Long? {
        val text = node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        for (s in arrayOf(text, desc)) {
            if (s.isEmpty()) continue
            val m = TIMECODE_RE.find(s) ?: continue
            val h = if (m.groupValues[3].isNotEmpty()) m.groupValues[1].toInt() else 0
            val mm = if (m.groupValues[3].isNotEmpty()) m.groupValues[2].toInt() else m.groupValues[1].toInt()
            val ss = if (m.groupValues[3].isNotEmpty()) m.groupValues[3].toInt() else m.groupValues[2].toInt()
            return ((h * 3600L) + (mm * 60L) + ss) * 1000L
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                scrapeTimecode(child)?.let { return it }
            } finally {
                child.recycle()
            }
        }
        return null
    }

    // Suppress "unused" for SDK int — keeps the build target floor obvious.
    @Suppress("unused")
    private val sdkFloor = Build.VERSION_CODES.M
}
