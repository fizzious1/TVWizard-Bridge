package ai.djwizard.tvbridge

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.util.Log
import org.json.JSONObject

// VolumeController implements Spec 0002 — absolute volume + mute over
// AudioManager.STREAM_MUSIC, the canonical media stream on Android TV.
//
// Stateless: every call reads AudioManager fresh, so two parallel `get`
// requests can't race the cached snapshot in the service instance.
//
// Wire contract pinned in /docs/specs/0002-volume-absolute.md and the
// relay's tools_volume.go (keyVolumeJSON, errCodeVolumeNotAvailable).
internal object VolumeController {

    private const val TAG = "TVBridge.Volume"

    fun handle(context: Context, frame: InboundFrame): OutboundFrame {
        val cmd = frame.params[PARAM_CMD].orEmpty()
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return OutboundFrame(frame.id, ok = false, message = ERR_VOLUME_NOT_AVAILABLE)
        return when (cmd) {
            CMD_GET -> snapshotResult(frame.id, am)
            CMD_SET -> setLevel(frame, am)
            CMD_MUTE -> {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
                Log.i(TAG, "mute ok")
                snapshotResult(frame.id, am)
            }
            CMD_UNMUTE -> {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                Log.i(TAG, "unmute ok")
                snapshotResult(frame.id, am)
            }
            else -> OutboundFrame(frame.id, ok = false, message = "unsupported volume cmd: $cmd")
        }
    }

    private fun setLevel(frame: InboundFrame, am: AudioManager): OutboundFrame {
        val raw = frame.params[PARAM_LEVEL].orEmpty()
        val level = raw.toIntOrNull()
            ?: return OutboundFrame(frame.id, ok = false, message = "level must be an integer")
        val clamped = level.coerceIn(0, 100)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return OutboundFrame(frame.id, ok = false, message = ERR_VOLUME_NOT_AVAILABLE)
        // Round-half-up to land on whole AudioManager steps (15 or 30 on
        // most devices). +50 before /100 is the standard rounding trick.
        val scaled = (clamped * max + 50) / 100
        am.setStreamVolume(AudioManager.STREAM_MUSIC, scaled, 0)
        Log.i(TAG, "set level=$clamped scaled=$scaled max=$max")
        return snapshotResult(frame.id, am)
    }

    private fun snapshotResult(id: String, am: AudioManager): OutboundFrame {
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return OutboundFrame(id, ok = false, message = ERR_VOLUME_NOT_AVAILABLE)
        val rawLevel = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val percent = if (max == 0) 0 else (rawLevel * 100 + max / 2) / max
        // isStreamMute landed in API 23 — same as our minSdk, so no guard
        // needed today, but the SDK_INT check makes the dependency loud.
        val muted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) am.isStreamMute(AudioManager.STREAM_MUSIC) else false
        val obj = JSONObject()
        obj.put("level", percent)
        obj.put("muted", muted)
        obj.put("max_raw", max)
        return OutboundFrame(id, ok = true, data = mapOf(KEY_VOLUME_JSON to obj.toString()))
    }
}
