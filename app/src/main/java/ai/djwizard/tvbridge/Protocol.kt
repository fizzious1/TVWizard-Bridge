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
const val OP_PLAYBACK = "playback"
const val OP_VOLUME = "volume"

// Playback op commands — the single "playback" op switches on `cmd` to keep
// the bridge-side when() stable as we add more states. See
// TVWizard/docs/specs/0001-playback-tools.md for the wire contract.
const val PLAYBACK_CMD_GET = "get"
const val PLAYBACK_CMD_SEEK = "seek"
const val PLAYBACK_CMD_PAUSE = "pause"
const val PLAYBACK_CMD_RESUME = "resume"

const val PARAM_PLAYBACK_CMD = "cmd"
const val PARAM_PLAYBACK_POSITION_MS = "position_ms"
const val PARAM_PLAYBACK_DELTA_MS = "delta_ms"

const val KEY_PLAYBACK_JSON = "playback_json"

// Volume op commands + params. See TVWizard/docs/specs/0002-volume-absolute.md.
const val VOLUME_CMD_GET = "get"
const val VOLUME_CMD_SET = "set"
const val VOLUME_CMD_MUTE = "mute"
const val VOLUME_CMD_UNMUTE = "unmute"

const val PARAM_VOLUME_CMD = "cmd"
const val PARAM_VOLUME_LEVEL = "level"

const val KEY_VOLUME_JSON = "volume_json"

// ERR_VOLUME_NOT_AVAILABLE — AudioManager null or maxVolume=0 (phantom
// audio stacks on headless Chromecast clones). Mirror in tools_volume.go.
const val ERR_VOLUME_NOT_AVAILABLE = "volume_not_available"

// ERR_ACCESSIBILITY_NOT_GRANTED is the exact string the relay's
// makeObserveHandler matches to tell Claude "a setup prompt was just
// posted on the TV". Do NOT change without updating the relay in lockstep.
const val ERR_ACCESSIBILITY_NOT_GRANTED = "accessibility_not_granted"

// Playback-specific error codes. Mirror tools_playback.go in the relay.
// Unknown codes pass through verbatim on the relay side — but every code we
// DO emit must be present here, or a rename breaks silently.
const val ERR_PLAYBACK_NO_SESSION = "playback_no_session"
const val ERR_PLAYBACK_SEEK_UNSUPPORTED = "playback_seek_unsupported"
const val ERR_PLAYBACK_NOTIFICATION_LISTENER_NOT_GRANTED = "playback_notification_listener_not_granted"

// ERR_PLAYBACK_NOT_IMPLEMENTED is emitted by the skeleton PlaybackController
// until spec 0001 step 2 (MediaSession) and step 3 (accessibility fallback)
// land. The relay passes it through verbatim; Claude sees
// "playback_not_implemented" and can tell the user "that feature isn't
// wired up on your bridge yet — update the app."
const val ERR_PLAYBACK_NOT_IMPLEMENTED = "playback_not_implemented"

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
