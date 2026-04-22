package ai.djwizard.tvbridge

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

// InputController dispatches text into the focused input field via
// AccessibilityNodeInfo.ACTION_SET_TEXT. Spec 0007.
//
// No fallback layer in v1 — if ACTION_SET_TEXT is rejected, we return
// input_set_text_rejected and let the caller fall back to per-character
// on-screen-keyboard navigation (tv_observe + tv_send_key).
class InputController(private val ctx: Context) {

    fun handle(frame: InboundFrame): OutboundFrame {
        val cmd = frame.params[PARAM_INPUT_CMD].orEmpty()
        if (cmd.isEmpty()) {
            return OutboundFrame(frame.id, ok = false, message = "$PARAM_INPUT_CMD is required")
        }
        return when (cmd) {
            INPUT_CMD_TYPE -> type(frame)
            else -> OutboundFrame(frame.id, ok = false, message = "unknown input cmd: $cmd")
        }
    }

    private fun type(frame: InboundFrame): OutboundFrame {
        val text = frame.params[PARAM_INPUT_TEXT].orEmpty()
        if (text.isEmpty()) {
            return OutboundFrame(frame.id, ok = false, message = "$PARAM_INPUT_TEXT is required")
        }
        val service = TVAccessibilityService.get()
            ?: return OutboundFrame(frame.id, ok = false, message = "accessibility service not bound")
        val root = service.rootInActiveWindow
            ?: return OutboundFrame(frame.id, ok = false, message = ERR_INPUT_NO_EDITABLE_FOCUS)

        // Search for the focused node — FOCUS_INPUT is what text fields set
        // when the user navigated into them. FOCUS_ACCESSIBILITY is a
        // broader fallback for apps that don't request input focus properly.
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)

        try {
            if (focused == null) {
                Log.i(TAG, "type: no focused node in active window")
                return OutboundFrame(frame.id, ok = false, message = ERR_INPUT_NO_EDITABLE_FOCUS)
            }
            if (!focused.isEditable) {
                Log.i(TAG, "type: focused node is not editable (class=${focused.className})")
                return OutboundFrame(frame.id, ok = false, message = ERR_INPUT_NO_EDITABLE_FOCUS)
            }

            val args = Bundle()
            args.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text,
            )
            val ok = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (!ok) {
                Log.w(TAG, "type: ACTION_SET_TEXT rejected by ${focused.className}")
                return OutboundFrame(frame.id, ok = false, message = ERR_INPUT_SET_TEXT_REJECTED)
            }

            val snap = JSONObject()
            snap.put("chars", text.length)
            snap.put("method", "set_text")
            Log.i(TAG, "type: wrote ${text.length} chars via ACTION_SET_TEXT into ${focused.className}")
            return OutboundFrame(
                id = frame.id,
                ok = true,
                data = mapOf(KEY_INPUT_JSON to snap.toString()),
            )
        } finally {
            focused?.recycle()
            root.recycle()
        }
    }

    companion object {
        private const val TAG = "TVBridge.Input"
    }
}
