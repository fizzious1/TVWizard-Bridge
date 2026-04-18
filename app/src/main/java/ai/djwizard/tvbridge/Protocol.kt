package ai.djwizard.tvbridge

// Wire protocol matches internal/bridge/registry.go in the TVWizard relay.
//
//   relay -> bridge:  { "id": "...", "op": "key", "params": { "key": "UP" } }
//   bridge -> relay:  { "id": "...", "ok": true,  "message": "",  "data": { ... } }
//
// The only op the v0.2 relay sends today is "key". The bridge acks every frame
// so the relay's Send() can resolve its waiters without hanging.

const val OP_KEY = "key"
const val OP_LAUNCH_APP = "launch_app"
const val OP_LIST_APPS = "list_apps"
const val OP_OBSERVE = "observe"

// ERR_ACCESSIBILITY_NOT_GRANTED is the exact string the relay's
// makeObserveHandler matches to tell Claude "a setup prompt was just
// posted on the TV". Do NOT change without updating the relay in lockstep.
const val ERR_ACCESSIBILITY_NOT_GRANTED = "accessibility_not_granted"

const val KEY_POWER = "POWER"
const val KEY_HOME = "HOME"
const val KEY_BACK = "BACK"
const val KEY_UP = "UP"
const val KEY_DOWN = "DOWN"
const val KEY_LEFT = "LEFT"
const val KEY_RIGHT = "RIGHT"
const val KEY_OK = "OK"
const val KEY_VOL_UP = "VOL_UP"
const val KEY_VOL_DOWN = "VOL_DOWN"

data class InboundFrame(
    val id: String,
    val op: String,
    val params: Map<String, String>,
)

data class OutboundFrame(
    val id: String,
    val ok: Boolean,
    val message: String = "",
    val data: Map<String, String> = emptyMap(),
)
