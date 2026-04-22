package ai.djwizard.tvbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import org.json.JSONObject

// PlaybackController owns the MediaSession round-trip (spec 0001 step 2) plus
// a thin accessibility fallback for pause/resume (step 3). Get/seek fallback
// is explicitly not implemented — on-screen timecode scraping is high-
// complexity low-value, returning ERR_PLAYBACK_NO_SESSION in those cases
// pushes Claude to surface "nothing is playing" rather than invent state.
//
// Wire contract: TVWizard/docs/specs/0001-playback-tools.md.
class PlaybackController(private val ctx: Context) {

    private val sessionManager: MediaSessionManager? by lazy {
        ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
    }

    // Pre-built once: the ComponentName of our NotificationListenerService.
    // MediaSessionManager.getActiveSessions requires this.
    private val listenerComponent: ComponentName by lazy {
        ComponentName(ctx, TVNotificationListener::class.java)
    }

    fun handle(frame: InboundFrame): OutboundFrame {
        val cmd = frame.params[PARAM_PLAYBACK_CMD].orEmpty()
        if (cmd.isEmpty()) {
            return OutboundFrame(frame.id, ok = false, message = "$PARAM_PLAYBACK_CMD is required")
        }
        return when (cmd) {
            PLAYBACK_CMD_GET -> get(frame)
            PLAYBACK_CMD_SEEK -> seek(frame)
            PLAYBACK_CMD_PAUSE -> pauseOrResume(frame, pausing = true)
            PLAYBACK_CMD_RESUME -> pauseOrResume(frame, pausing = false)
            else -> OutboundFrame(frame.id, ok = false, message = "unknown playback cmd: $cmd")
        }
    }

    // ── get ──────────────────────────────────────────────────────────────────

    private fun get(frame: InboundFrame): OutboundFrame {
        if (!isNotificationListenerEnabled()) {
            postNotificationListenerNotification()
            return OutboundFrame(frame.id, ok = false, message = ERR_PLAYBACK_NOTIFICATION_LISTENER_NOT_GRANTED)
        }
        val controller = activeController()
            ?: return OutboundFrame(frame.id, ok = false, message = ERR_PLAYBACK_NO_SESSION)
        val snap = snapshotOf(controller, source = SOURCE_MEDIA_SESSION)
        return OutboundFrame(
            id = frame.id,
            ok = true,
            data = mapOf(KEY_PLAYBACK_JSON to snap.toString()),
        )
    }

    // ── seek ─────────────────────────────────────────────────────────────────

    private fun seek(frame: InboundFrame): OutboundFrame {
        val posStr = frame.params[PARAM_PLAYBACK_POSITION_MS]
        val deltaStr = frame.params[PARAM_PLAYBACK_DELTA_MS]
        if (posStr.isNullOrEmpty() && deltaStr.isNullOrEmpty()) {
            return OutboundFrame(frame.id, ok = false, message = "seek requires $PARAM_PLAYBACK_POSITION_MS or $PARAM_PLAYBACK_DELTA_MS")
        }
        if (!isNotificationListenerEnabled()) {
            postNotificationListenerNotification()
            return OutboundFrame(frame.id, ok = false, message = ERR_PLAYBACK_NOTIFICATION_LISTENER_NOT_GRANTED)
        }
        val controller = activeController()
            ?: return OutboundFrame(frame.id, ok = false, message = ERR_PLAYBACK_NO_SESSION)

        // Per-session seek support: PlaybackState advertises ACTION_SEEK_TO
        // when the app supports it (Netflix, YouTube yes; fuboTV, live TV no).
        val state = controller.playbackState
        val actions = state?.actions ?: 0L
        if ((actions and PlaybackState.ACTION_SEEK_TO) == 0L) {
            return OutboundFrame(frame.id, ok = false, message = ERR_PLAYBACK_SEEK_UNSUPPORTED)
        }

        val duration = controller.metadata
            ?.getLong(MediaMetadata.METADATA_KEY_DURATION)
            ?.takeIf { it > 0 }
            ?: Long.MAX_VALUE

        val target: Long = when {
            posStr != null && posStr.isNotEmpty() ->
                posStr.toLongOrNull()?.coerceAtLeast(0)?.coerceAtMost(duration)
                    ?: return OutboundFrame(frame.id, ok = false, message = "invalid $PARAM_PLAYBACK_POSITION_MS: $posStr")
            else -> {
                val delta = deltaStr?.toLongOrNull()
                    ?: return OutboundFrame(frame.id, ok = false, message = "invalid $PARAM_PLAYBACK_DELTA_MS: $deltaStr")
                val current = state?.position ?: 0L
                (current + delta).coerceAtLeast(0).coerceAtMost(duration)
            }
        }

        controller.transportControls.seekTo(target)
        // The seek is async — reading back state immediately returns the old
        // position on some apps. We echo the *requested* target so Claude
        // reports a sensible number; real position refreshes on next get().
        val snap = snapshotOf(controller, source = SOURCE_MEDIA_SESSION)
        snap.put("position_ms", target)
        Log.i(TAG, "seek target=$target pos_in=$posStr delta_in=$deltaStr app=${controller.packageName}")
        return OutboundFrame(
            id = frame.id,
            ok = true,
            data = mapOf(KEY_PLAYBACK_JSON to snap.toString()),
        )
    }

    // ── pause / resume ──────────────────────────────────────────────────────

    private fun pauseOrResume(frame: InboundFrame, pausing: Boolean): OutboundFrame {
        // Try MediaSession first. If no session + notification listener isn't
        // granted, fall back to a global KEYCODE_MEDIA_PLAY_PAUSE via the
        // accessibility service. This is step-3 fallback behaviour — covers
        // live-TV apps that don't publish MediaSession.
        if (isNotificationListenerEnabled()) {
            val controller = activeController()
            if (controller != null) {
                if (pausing) controller.transportControls.pause()
                else controller.transportControls.play()
                val snap = snapshotOf(controller, source = SOURCE_MEDIA_SESSION)
                // Don't trust the just-toggled state — override with intent.
                snap.put("state", if (pausing) STATE_PAUSED else STATE_PLAYING)
                Log.i(TAG, "${if (pausing) "pause" else "resume"} via media_session app=${controller.packageName}")
                return OutboundFrame(frame.id, ok = true, data = mapOf(KEY_PLAYBACK_JSON to snap.toString()))
            }
        }
        // Fallback path: media-session unavailable. Dispatch a media
        // play/pause key via AudioManager.dispatchMediaKeyEvent — the
        // canonical way to route a media key to whatever app is holding
        // audio focus without requiring INJECT_EVENTS. KEYCODE_MEDIA_PLAY_PAUSE
        // is a toggle on most apps, so we can't guarantee the requested
        // direction — we mark source:accessibility and let the caller
        // reconcile via a follow-up tv_get_playback.
        if (!dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)) {
            return OutboundFrame(frame.id, ok = false, message = ERR_PLAYBACK_NO_SESSION)
        }
        val snap = JSONObject()
        snap.put("state", if (pausing) STATE_PAUSED else STATE_PLAYING)
        snap.put("source", SOURCE_ACCESSIBILITY)
        snap.put("position_ms", 0)
        snap.put("duration_ms", 0)
        Log.i(TAG, "${if (pausing) "pause" else "resume"} via accessibility (keycode)")
        return OutboundFrame(frame.id, ok = true, data = mapOf(KEY_PLAYBACK_JSON to snap.toString()))
    }

    // ── session helpers ─────────────────────────────────────────────────────

    // activeController finds a MediaController whose package matches the
    // foreground app. Falls back to the first active session if we can't
    // identify the foreground or no session matches it.
    private fun activeController(): MediaController? {
        val manager = sessionManager ?: return null
        val sessions = try {
            manager.getActiveSessions(listenerComponent)
        } catch (se: SecurityException) {
            Log.w(TAG, "getActiveSessions denied: ${se.message}")
            return null
        }
        if (sessions.isEmpty()) return null
        val foreground = foregroundPackage()
        return sessions.firstOrNull { it.packageName == foreground } ?: sessions.first()
    }

    private fun foregroundPackage(): String? {
        val service = TVAccessibilityService.get() ?: return null
        val root = service.rootInActiveWindow ?: return null
        return try {
            root.packageName?.toString()
        } finally {
            root.recycle()
        }
    }

    private fun snapshotOf(controller: MediaController, source: String): JSONObject {
        val obj = JSONObject()
        val metadata = controller.metadata
        val state = controller.playbackState

        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        if (title.isNotEmpty()) obj.put("title", title)
        val pkg = controller.packageName ?: ""
        if (pkg.isNotEmpty()) obj.put("app", pkg)
        obj.put("position_ms", state?.position ?: 0L)
        obj.put("duration_ms", metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L)
        obj.put("state", mapState(state?.state))
        obj.put("source", source)
        return obj
    }

    private fun mapState(state: Int?): String = when (state) {
        PlaybackState.STATE_PLAYING -> STATE_PLAYING
        PlaybackState.STATE_PAUSED -> STATE_PAUSED
        PlaybackState.STATE_BUFFERING,
        PlaybackState.STATE_CONNECTING,
        PlaybackState.STATE_REWINDING,
        PlaybackState.STATE_FAST_FORWARDING -> STATE_BUFFERING
        PlaybackState.STATE_STOPPED,
        PlaybackState.STATE_ERROR,
        PlaybackState.STATE_NONE -> STATE_STOPPED
        null -> STATE_UNKNOWN
        else -> STATE_UNKNOWN
    }

    // dispatchMediaKey routes a KEYCODE_MEDIA_* event to whichever app
    // currently holds audio focus. Returns false only if the system
    // AudioManager can't be obtained (shouldn't happen in practice).
    private fun dispatchMediaKey(keyCode: Int): Boolean {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        val now = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
        return true
    }

    // ── notification-listener permission plumbing ───────────────────────────

    // isNotificationListenerEnabled reads Settings.Secure's flat list of
    // enabled listener components and checks if ours is in there. This is
    // the documented way to detect access without actually trying (which
    // would leak a SecurityException to logs).
    private fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(
            ctx.contentResolver,
            ENABLED_NOTIFICATION_LISTENERS_KEY,
        ) ?: return false
        val target = listenerComponent.flattenToString()
        return flat.split(":").any { it == target }
    }

    private fun postNotificationListenerNotification() {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_SETUP,
                    "TVWizard setup",
                    NotificationManager.IMPORTANCE_HIGH,
                )
                nm.createNotificationChannel(channel)
            }
            val settings = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pending = PendingIntent.getActivity(
                ctx,
                0,
                settings,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = Notification.Builder(ctx, CHANNEL_SETUP)
                .setSmallIcon(R.drawable.bridge_banner)
                .setContentTitle("One more setup step")
                .setContentText("Tap to let TVWizard read playback state.")
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIF_ID_LISTENER_SETUP, notification)
            Log.i(TAG, "posted notification-listener-not-granted notification")
        } catch (t: Throwable) {
            Log.w(TAG, "could not post notification-listener setup notification: ${t.message}")
        }
    }

    companion object {
        private const val TAG = "TVBridge.Playback"
        private const val ENABLED_NOTIFICATION_LISTENERS_KEY = "enabled_notification_listeners"
        private const val CHANNEL_SETUP = "tvwizard_setup"
        private const val NOTIF_ID_LISTENER_SETUP = 1002

        const val SOURCE_MEDIA_SESSION = "media_session"
        const val SOURCE_ACCESSIBILITY = "accessibility"

        const val STATE_PLAYING = "playing"
        const val STATE_PAUSED = "paused"
        const val STATE_BUFFERING = "buffering"
        const val STATE_STOPPED = "stopped"
        const val STATE_UNKNOWN = "unknown"
    }
}
