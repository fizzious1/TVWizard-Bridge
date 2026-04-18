package ai.djwizard.tvbridge

import android.accessibilityservice.AccessibilityService

sealed class KeyAction {
    data class Global(val globalAction: Int) : KeyAction()
    data class Volume(val direction: Int) : KeyAction()
    data object Unsupported : KeyAction()
}

// Maps the relay's key-name alphabet to an action the TVAccessibilityService can
// perform. The DPAD global actions were added in API 33 (Android 13); our
// emulator runs android-34 and the Play Store minimum for any Google TV today
// is 12+, so we gate individual calls inside the service rather than here.
fun mapKey(key: String): KeyAction = when (key) {
    KEY_HOME -> KeyAction.Global(AccessibilityService.GLOBAL_ACTION_HOME)
    KEY_BACK -> KeyAction.Global(AccessibilityService.GLOBAL_ACTION_BACK)
    KEY_UP -> KeyAction.Global(AccessibilityService.GLOBAL_ACTION_DPAD_UP)
    KEY_DOWN -> KeyAction.Global(AccessibilityService.GLOBAL_ACTION_DPAD_DOWN)
    KEY_LEFT -> KeyAction.Global(AccessibilityService.GLOBAL_ACTION_DPAD_LEFT)
    KEY_RIGHT -> KeyAction.Global(AccessibilityService.GLOBAL_ACTION_DPAD_RIGHT)
    KEY_OK -> KeyAction.Global(AccessibilityService.GLOBAL_ACTION_DPAD_CENTER)
    KEY_POWER -> KeyAction.Global(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)
    KEY_VOL_UP -> KeyAction.Volume(+1)
    KEY_VOL_DOWN -> KeyAction.Volume(-1)
    else -> KeyAction.Unsupported
}
