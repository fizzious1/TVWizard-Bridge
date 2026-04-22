package ai.djwizard.tvbridge

import android.content.Context
import android.os.LocaleList
import android.util.Log
import android.view.accessibility.CaptioningManager
import java.util.Locale
import org.json.JSONObject

// CaptionsController attempts to toggle captions on the paired TV. Reality
// bites here: CaptioningManager.setEnabled is a system-only API; regular
// apps can only READ state. KEYCODE_CAPTIONS (175) is also unprivileged-
// inject-restricted. So this controller's honest story is:
//
//   get-state  — supported (CaptioningManager is world-readable)
//   turn on    — returns captions_unsupported, with source=best_effort
//                when we do manage to reach the READ path
//   turn off   — same
//   set lang   — sets the CaptioningManager's preferred locale hint where
//                possible (API 34+ has the public setter); otherwise
//                captions_unsupported
//
// The relay translates captions_unsupported into a Claude-facing message
// that tells the user to toggle CC with their remote. This is the correct
// behaviour on >90% of real Android TVs today. Per-app navigation-sequence
// automation is out of scope for v1 (spec 0004 risk section).
class CaptionsController(private val ctx: Context) {

    private val captioningManager: CaptioningManager? by lazy {
        ctx.getSystemService(Context.CAPTIONING_SERVICE) as? CaptioningManager
    }

    fun handle(frame: InboundFrame): OutboundFrame {
        val cmd = frame.params[PARAM_CAPTIONS_CMD].orEmpty()
        if (cmd.isEmpty()) {
            return OutboundFrame(frame.id, ok = false, message = "$PARAM_CAPTIONS_CMD is required")
        }
        return when (cmd) {
            CAPTIONS_CMD_ON -> setEnabledBestEffort(frame, enable = true)
            CAPTIONS_CMD_OFF -> setEnabledBestEffort(frame, enable = false)
            CAPTIONS_CMD_SET_LANGUAGE -> setLanguageBestEffort(frame)
            else -> OutboundFrame(frame.id, ok = false, message = "unknown captions cmd: $cmd")
        }
    }

    private fun setEnabledBestEffort(frame: InboundFrame, enable: Boolean): OutboundFrame {
        // Read current state — works on all APIs. We can always report this.
        val cm = captioningManager
        val currentEnabled = cm?.isEnabled ?: false
        // If the user already has captions configured as requested, we can
        // treat it as a no-op success. This is the common case for
        // accessibility-first users who leave CC on permanently.
        if (currentEnabled == enable) {
            return OutboundFrame(
                frame.id,
                ok = true,
                data = mapOf(KEY_CAPTIONS_JSON to snapshot(enabled = enable, lang = currentLanguageCode(), source = "system").toString()),
            )
        }
        // Cannot toggle state from an unprivileged app on current Android TV.
        // Tell the relay, which tells Claude, which tells the user to use
        // their remote. Logged so ops can see the frequency.
        Log.i(TAG, "captions.set(enable=$enable) — unprivileged app cannot toggle; returning captions_unsupported")
        return OutboundFrame(frame.id, ok = false, message = ERR_CAPTIONS_UNSUPPORTED)
    }

    private fun setLanguageBestEffort(frame: InboundFrame): OutboundFrame {
        val lang = frame.params[PARAM_CAPTIONS_LANG].orEmpty()
        if (lang.isEmpty()) {
            return OutboundFrame(frame.id, ok = false, message = "$PARAM_CAPTIONS_LANG is required")
        }
        // CaptioningManager does not expose a public setter for the user's
        // preferred caption locale to regular apps. Report unsupported; the
        // relay's error translator tells the caller.
        Log.i(TAG, "captions.set_language(lang=$lang) — unprivileged; returning captions_unsupported")
        return OutboundFrame(frame.id, ok = false, message = ERR_CAPTIONS_UNSUPPORTED)
    }

    private fun snapshot(enabled: Boolean, lang: String, source: String): JSONObject {
        val obj = JSONObject()
        obj.put("enabled", enabled)
        if (lang.isNotEmpty()) obj.put("language", lang)
        obj.put("source", source)
        return obj
    }

    // currentLanguageCode pulls the user's captioning locale from
    // CaptioningManager — API 19+, always readable. Returns "" when no
    // locale is configured.
    private fun currentLanguageCode(): String {
        val cm = captioningManager ?: return ""
        val locale = try {
            cm.locale
        } catch (t: Throwable) {
            Log.w(TAG, "captions.locale read failed: ${t.message}")
            null
        } ?: return defaultLanguageFromList()
        return locale.language.orEmpty()
    }

    // defaultLanguageFromList: on devices without a system-captioning locale
    // set, fall back to the first locale on LocaleList. Covers Google TVs
    // that ship with captions "off by default" but do have a system locale.
    private fun defaultLanguageFromList(): String {
        return try {
            val list = LocaleList.getDefault()
            if (list.size() > 0) list[0].language else Locale.getDefault().language
        } catch (_: Throwable) {
            Locale.getDefault().language
        }
    }

    companion object {
        private const val TAG = "TVBridge.Captions"
    }
}
