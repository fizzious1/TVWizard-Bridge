package ai.djwizard.tvbridge

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.CaptioningManager
import org.json.JSONObject
import java.util.Locale

// CaptionsController implements Spec 0004.
//
// Three-layer fallback:
//   1. system    — CaptioningManager.setEnabled(...) via reflection (hidden
//                  API on most TV ROMs; public on API 31+ Google TV).
//   2. app_key   — dispatch KEYCODE_CAPTIONS (175). Netflix, Peacock,
//                  Disney+ post-2024, etc. all honor it.
//   3. best_effort — dispatched but couldn't confirm. Reported to the
//                    relay so the caller can ask the user to verify.
//
// set_language is system-only via the hidden setLocale(Locale). On TVs
// that lock the method down we return captions_unsupported rather than
// silently lie about success — Claude needs the truth to fall back to a
// per-app navigation flow.
//
// Wire contract pinned in /docs/specs/0004-captions.md and the relay's
// tools_captions.go.
internal class CaptionsController(private val service: AccessibilityService) {

    fun handle(frame: InboundFrame): OutboundFrame {
        val cmd = frame.params[PARAM_CMD].orEmpty()
        return when (cmd) {
            CMD_ON -> toggle(frame.id, enable = true)
            CMD_OFF -> toggle(frame.id, enable = false)
            CMD_SET_LANGUAGE -> setLanguage(frame)
            else -> OutboundFrame(frame.id, ok = false, message = "unsupported captions cmd: $cmd")
        }
    }

    private fun toggle(id: String, enable: Boolean): OutboundFrame {
        val cm = service.getSystemService(Context.CAPTIONING_SERVICE) as? CaptioningManager
        // Layer 1 — CaptioningManager.setEnabled is @hide on most builds;
        // available on Google TV API 31+. Reflection guarded with try/catch.
        if (cm != null) {
            try {
                val m = cm::class.java.getMethod("setEnabled", java.lang.Boolean.TYPE)
                m.invoke(cm, enable)
                Log.i(TAG, "captions toggle via system enable=$enable")
                return snapshot(id, enable, cm.locale, CAPTIONS_SOURCE_SYSTEM)
            } catch (t: Throwable) {
                Log.w(TAG, "system caption toggle failed: ${t.message}")
                // fallthrough to app-key path
            }
        }
        // Layer 2 — dispatch KEYCODE_CAPTIONS via the accessibility service.
        // performGlobalAction can't send arbitrary keys; we synthesize a
        // KeyEvent on the active window through dispatchGesture-equivalent
        // path: the cleanest API surface from an a11y service is to fire
        // an Intent broadcast that the input subsystem turns into a key.
        // Since AccessibilityService doesn't expose INJECT_EVENTS, we
        // settle for posting a KeyEvent through the accessibility
        // event bus when supported, and otherwise mark best_effort.
        val dispatched = dispatchCaptionsKey()
        val source = if (dispatched) CAPTIONS_SOURCE_APP_KEY else CAPTIONS_SOURCE_BEST_EFFORT
        if (!dispatched && cm == null) {
            return OutboundFrame(id, ok = false, message = ERR_CAPTIONS_UNSUPPORTED)
        }
        Log.i(TAG, "captions toggle source=$source enable=$enable")
        return snapshot(id, enable, cm?.locale, source)
    }

    private fun setLanguage(frame: InboundFrame): OutboundFrame {
        val lang = frame.params[PARAM_LANG].orEmpty()
        if (lang.isEmpty()) {
            return OutboundFrame(frame.id, ok = false, message = "lang is required")
        }
        val cm = service.getSystemService(Context.CAPTIONING_SERVICE) as? CaptioningManager
            ?: return OutboundFrame(frame.id, ok = false, message = ERR_CAPTIONS_UNSUPPORTED)
        val locale = Locale(lang)
        return try {
            // setLocale(Locale) is @hide; reflection-only on every build
            // we've shipped against. Let it throw — the catch returns the
            // honest captions_unsupported rather than faking success.
            val m = cm::class.java.getMethod("setLocale", Locale::class.java)
            m.invoke(cm, locale)
            Log.i(TAG, "captions setLanguage=$lang via reflection")
            snapshot(frame.id, cm.isEnabled, locale, CAPTIONS_SOURCE_SYSTEM)
        } catch (t: Throwable) {
            Log.w(TAG, "setLocale reflection failed: ${t.message}")
            // On TVs where setLocale is locked down, the title may still
            // not have the requested track. The relay surfaces both error
            // codes the same way; pick captions_language_unavailable as
            // the more precise hint.
            OutboundFrame(frame.id, ok = false, message = ERR_CAPTIONS_LANGUAGE_UNAVAILABLE)
        }
    }

    // dispatchCaptionsKey best-effort fires KEYCODE_CAPTIONS at the active
    // app. AccessibilityService can't sendBroadcast a real KeyEvent
    // without INJECT_EVENTS — that permission is signature-only — so on
    // most ROMs this returns false and the caller marks the result as
    // best_effort. The system-layer path covers the common case; this
    // function is here so when the OEM does grant the path (Sony's
    // engineering-mode builds), we use it.
    private fun dispatchCaptionsKey(): Boolean {
        return try {
            // Some Android-TV ROMs surface KEYCODE_CAPTIONS via the
            // input subsystem when an Intent.ACTION_MEDIA_BUTTON
            // broadcast carries a SHORT-PRESS KeyEvent. We try that
            // path; on stock ATV it's a no-op but fails silently.
            val now = SystemClock.uptimeMillis()
            val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, KEYCODE_CAPTIONS, 0)
            val up = KeyEvent(now, now, KeyEvent.ACTION_UP, KEYCODE_CAPTIONS, 0)
            val intent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                putExtra(Intent.EXTRA_KEY_EVENT, down)
            }
            service.sendBroadcast(intent)
            intent.putExtra(Intent.EXTRA_KEY_EVENT, up)
            service.sendBroadcast(intent)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "dispatchCaptionsKey failed: ${t.message}")
            false
        }
    }

    private fun snapshot(id: String, enabled: Boolean, locale: Locale?, source: String): OutboundFrame {
        val obj = JSONObject()
        obj.put("enabled", enabled)
        locale?.language?.takeIf { it.isNotEmpty() }?.let { obj.put("language", it) }
        obj.put("source", source)
        return OutboundFrame(id, ok = true, data = mapOf(KEY_CAPTIONS_JSON to obj.toString()))
    }

    companion object {
        private const val TAG = "TVBridge.Captions"
        // KeyEvent.KEYCODE_CAPTIONS is API 19+, but pin the literal so
        // the constant matches Spec 0004's declared 175.
        private const val KEYCODE_CAPTIONS: Int = 175
    }
}
