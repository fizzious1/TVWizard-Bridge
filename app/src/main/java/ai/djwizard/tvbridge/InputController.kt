package ai.djwizard.tvbridge

import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

// InputController implements Spec 0007 — `tv_type`.
//
// The relay calls this op `input` (see tools_type.go:opInput) — `type`
// would have collided with Kotlin's reserved word and the relay's wire
// vocabulary already names the surface `input` to leave room for future
// cmds (backspace, select_all). The op + key constants here mirror
// keyInputJSON / paramInputText / inputCmdType verbatim.
//
// Strategy: walk the accessibility tree for the focused EDITABLE node
// and dispatch ACTION_SET_TEXT. ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE is
// the standard arg key — works on stock EditText and most custom views.
// Custom views that reject SET_TEXT return false; we surface that as
// input_set_text_rejected so Claude can fall back to per-character entry
// via the on-screen keyboard.
internal object InputController {

    private const val TAG = "TVBridge.Input"

    fun handle(rootInActiveWindow: AccessibilityNodeInfo?, frame: InboundFrame): OutboundFrame {
        val cmd = frame.params[PARAM_CMD].orEmpty()
        if (cmd != CMD_TYPE) {
            return OutboundFrame(frame.id, ok = false, message = "unsupported input cmd: $cmd")
        }
        val text = frame.params[PARAM_TEXT].orEmpty()
        if (text.isEmpty()) {
            return OutboundFrame(frame.id, ok = false, message = "text is required")
        }
        val root = rootInActiveWindow
            ?: return OutboundFrame(frame.id, ok = false, message = ERR_ACCESSIBILITY_NOT_GRANTED)

        val target = findEditableFocused(root)
            ?: return OutboundFrame(frame.id, ok = false, message = ERR_INPUT_NO_EDITABLE_FOCUS)

        return try {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            val cls = target.className?.toString().orEmpty()
            target.recycle()
            if (!ok) {
                Log.w(TAG, "ACTION_SET_TEXT rejected by node class=$cls")
                return OutboundFrame(frame.id, ok = false, message = ERR_INPUT_SET_TEXT_REJECTED)
            }
            val obj = JSONObject()
            obj.put("chars", text.length)
            obj.put("method", "set_text")
            if (cls.isNotEmpty()) obj.put("node_class", cls)
            Log.i(TAG, "type chars=${text.length} class=$cls ok=true")
            OutboundFrame(frame.id, ok = true, data = mapOf(KEY_INPUT_JSON to obj.toString()))
        } catch (t: Throwable) {
            Log.w(TAG, "type failed: ${t.message}")
            try { target.recycle() } catch (_: Throwable) {}
            OutboundFrame(frame.id, ok = false, message = ERR_INPUT_SET_TEXT_REJECTED)
        }
    }

    // findEditableFocused mirrors handleObserve's preference order:
    // FOCUS_INPUT first (the actively-typing field), then
    // FOCUS_ACCESSIBILITY (a11y selection). If the focused node isn't
    // editable, we descend its subtree because some custom keyboards put
    // the EditText one level below the focusable wrapper. Caller is
    // responsible for recycling the returned node.
    private fun findEditableFocused(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            ?: return null
        if (focused.isEditable) return focused
        // Descend looking for an editable child. Recycle anything we
        // touch except the eventual return value.
        val descendant = findEditableDescendant(focused)
        focused.recycle()
        return descendant
    }

    private fun findEditableDescendant(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (child.isEditable) return child
            val nested = findEditableDescendant(child)
            if (nested != null) {
                child.recycle()
                return nested
            }
            child.recycle()
        }
        return null
    }
}
