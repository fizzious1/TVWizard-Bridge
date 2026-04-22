package ai.djwizard.tvbridge

import android.content.Context
import android.media.AudioManager
import android.util.Log
import org.json.JSONObject

// VolumeController wraps AudioManager's STREAM_MUSIC API into the four
// commands the bridge protocol speaks — get/set/mute/unmute. The 0..100
// abstraction is the bridge's job: the MCP caller thinks in percentages;
// this class maps to whatever maxVolume the underlying device reports
// (commonly 15 on a Chromecast/Google TV, 30 on Sony Bravia).
//
// Wire contract: TVWizard/docs/specs/0002-volume-absolute.md.
class VolumeController(private val ctx: Context) {

    private val audio: AudioManager? by lazy {
        ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    fun handle(frame: InboundFrame): OutboundFrame {
        val cmd = frame.params[PARAM_VOLUME_CMD].orEmpty()
        if (cmd.isEmpty()) {
            return OutboundFrame(frame.id, ok = false, message = "$PARAM_VOLUME_CMD is required")
        }
        val am = audio ?: return OutboundFrame(frame.id, ok = false, message = ERR_VOLUME_NOT_AVAILABLE)
        return when (cmd) {
            VOLUME_CMD_GET -> OutboundFrame(frame.id, ok = true, data = mapOf(KEY_VOLUME_JSON to snapshot(am).toString()))
            VOLUME_CMD_SET -> set(frame, am)
            VOLUME_CMD_MUTE -> {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
                OutboundFrame(frame.id, ok = true, data = mapOf(KEY_VOLUME_JSON to snapshot(am).toString()))
            }
            VOLUME_CMD_UNMUTE -> {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                OutboundFrame(frame.id, ok = true, data = mapOf(KEY_VOLUME_JSON to snapshot(am).toString()))
            }
            else -> OutboundFrame(frame.id, ok = false, message = "unknown volume cmd: $cmd")
        }
    }

    private fun set(frame: InboundFrame, am: AudioManager): OutboundFrame {
        val levelStr = frame.params[PARAM_VOLUME_LEVEL]
            ?: return OutboundFrame(frame.id, ok = false, message = "$PARAM_VOLUME_LEVEL is required")
        val level = levelStr.toIntOrNull()
            ?: return OutboundFrame(frame.id, ok = false, message = "invalid $PARAM_VOLUME_LEVEL: $levelStr")
        val clamped = level.coerceIn(0, 100)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return OutboundFrame(frame.id, ok = false, message = ERR_VOLUME_NOT_AVAILABLE)
        // Rounded scaling: percent -> raw. +max/2 prevents 99/100 rounding
        // down to the same raw step as 93/100.
        val raw = (clamped * max + max / 2) / 100
        // FLAG_SHOW_UI shows the TV's native volume bar, giving the user
        // visual feedback when the AI changes volume — important for
        // accessibility users who need to confirm the change happened.
        am.setStreamVolume(AudioManager.STREAM_MUSIC, raw, AudioManager.FLAG_SHOW_UI)
        Log.i(TAG, "set level=$clamped raw=$raw max=$max")
        return OutboundFrame(frame.id, ok = true, data = mapOf(KEY_VOLUME_JSON to snapshot(am).toString()))
    }

    private fun snapshot(am: AudioManager): JSONObject {
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val raw = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val level = if (max == 0) 0 else (raw * 100 + max / 2) / max
        val muted = try {
            am.isStreamMute(AudioManager.STREAM_MUSIC)
        } catch (_: Throwable) {
            // API 23 added isStreamMute; should always exist for us
            // (minSdk=23) but guard against OEM bugs.
            false
        }
        val obj = JSONObject()
        obj.put("level", level)
        obj.put("muted", muted)
        obj.put("max_raw", max)
        return obj
    }

    companion object {
        private const val TAG = "TVBridge.Volume"
    }
}
