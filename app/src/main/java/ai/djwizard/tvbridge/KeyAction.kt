package ai.djwizard.tvbridge

import android.accessibilityservice.AccessibilityService

// DpadDirection + Select are split out from Global so the service can pick
// between the API-33 performGlobalAction path and a pre-API-33 fallback that
// walks the AccessibilityNodeInfo tree. HOME/BACK/POWER never had that
// problem (API-16+) so they keep the Global branch.
enum class DpadDirection { UP, DOWN, LEFT, RIGHT }

sealed class KeyAction {
    data class Global(val globalAction: Int) : KeyAction()
    data class Volume(val direction: Int) : KeyAction()
    data class Dpad(val direction: DpadDirection) : KeyAction()
    data object Select : KeyAction()
    data object Unsupported : KeyAction()
}

fun mapKey(key: String): KeyAction = when (key) {
    KEY_HOME -> KeyAction.Global(AccessibilityService.GLOBAL_ACTION_HOME)
    KEY_BACK -> KeyAction.Global(AccessibilityService.GLOBAL_ACTION_BACK)
    KEY_POWER -> KeyAction.Global(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)
    KEY_UP -> KeyAction.Dpad(DpadDirection.UP)
    KEY_DOWN -> KeyAction.Dpad(DpadDirection.DOWN)
    KEY_LEFT -> KeyAction.Dpad(DpadDirection.LEFT)
    KEY_RIGHT -> KeyAction.Dpad(DpadDirection.RIGHT)
    KEY_OK -> KeyAction.Select
    KEY_VOL_UP -> KeyAction.Volume(+1)
    KEY_VOL_DOWN -> KeyAction.Volume(-1)
    else -> KeyAction.Unsupported
}
