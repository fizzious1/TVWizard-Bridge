package ai.djwizard.tvbridge

import android.content.Context
import android.util.Log

// PlaybackController is the bridge-side owner of the MediaSessionManager
// round-trip plus the accessibility-service fallback. Spec 0001 step 1
// (this file) is the skeleton — every command returns
// ERR_PLAYBACK_NOT_IMPLEMENTED. Step 2 wires MediaSession; step 3 adds the
// accessibility fallback for apps that don't publish a session.
//
// Shape note: commands take the InboundFrame + the Service's Context and
// return an OutboundFrame. Keeping state out of the controller means the
// TVAccessibilityService can own the one MediaController lifecycle (step 2
// addition) without us having to share `this` between threads.
class PlaybackController(private val ctx: Context) {

    fun handle(frame: InboundFrame): OutboundFrame {
        val cmd = frame.params[PARAM_PLAYBACK_CMD].orEmpty()
        if (cmd.isEmpty()) {
            return OutboundFrame(frame.id, ok = false, message = "$PARAM_PLAYBACK_CMD is required")
        }
        return when (cmd) {
            PLAYBACK_CMD_GET -> get(frame)
            PLAYBACK_CMD_SEEK -> seek(frame)
            PLAYBACK_CMD_PAUSE -> pause(frame)
            PLAYBACK_CMD_RESUME -> resume(frame)
            else -> OutboundFrame(frame.id, ok = false, message = "unknown playback cmd: $cmd")
        }
    }

    // Step 2 will replace this with MediaSessionManager.getActiveSessions +
    // the currently-foregrounded-package filter. Step 3 adds the on-screen
    // timecode fallback via rootInActiveWindow.
    private fun get(frame: InboundFrame): OutboundFrame {
        Log.i(TAG, "playback.get — skeleton, returning not_implemented")
        return notImplemented(frame)
    }

    // Step 2 will route seek/pause/resume through MediaController.transportControls
    // on the session returned by get(). Step 3 falls back to KEYCODE_MEDIA_* or
    // DPAD_LEFT/RIGHT * N for apps that don't expose a session.
    private fun seek(frame: InboundFrame): OutboundFrame {
        // Validate inputs even in the skeleton so mis-shaped frames surface
        // early; tests against the relay already cover this path.
        val pos = frame.params[PARAM_PLAYBACK_POSITION_MS]
        val delta = frame.params[PARAM_PLAYBACK_DELTA_MS]
        if (pos.isNullOrEmpty() && delta.isNullOrEmpty()) {
            return OutboundFrame(frame.id, ok = false, message = "seek requires $PARAM_PLAYBACK_POSITION_MS or $PARAM_PLAYBACK_DELTA_MS")
        }
        Log.i(TAG, "playback.seek pos=$pos delta=$delta — skeleton, returning not_implemented")
        return notImplemented(frame)
    }

    private fun pause(frame: InboundFrame): OutboundFrame {
        Log.i(TAG, "playback.pause — skeleton, returning not_implemented")
        return notImplemented(frame)
    }

    private fun resume(frame: InboundFrame): OutboundFrame {
        Log.i(TAG, "playback.resume — skeleton, returning not_implemented")
        return notImplemented(frame)
    }

    private fun notImplemented(frame: InboundFrame): OutboundFrame =
        OutboundFrame(frame.id, ok = false, message = ERR_PLAYBACK_NOT_IMPLEMENTED)

    companion object {
        private const val TAG = "TVBridge.Playback"
    }
}
