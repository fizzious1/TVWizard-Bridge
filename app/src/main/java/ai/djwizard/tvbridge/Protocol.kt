package ai.djwizard.tvbridge

// Wire protocol matches internal/bridge/registry.go in the TVWizard relay.
//
//   relay -> bridge:  { "id": "...", "op": "key", "params": { "key": "UP" } }
//   bridge -> relay:  { "id": "...", "ok": true,  "message": "",  "data": { ... } }
//
// The bridge acks every frame so the relay's Send() can resolve its waiters
// without hanging.

const val OP_KEY = "key"
const val OP_LAUNCH_APP = "launch_app"
const val OP_LIST_APPS = "list_apps"
const val OP_OBSERVE = "observe"

// Workstream-F additions. Each op multiplexes a `cmd` param the way the
// existing dispatch pattern does for KEY (which uses `key`). Constants here
// are pinned to the relay-side handlers in
// /internal/mcp/tools_{playback,volume,captions,type}.go — change in
// lockstep or the relay will reject the payload.
const val OP_PLAYBACK = "playback"
const val OP_VOLUME = "volume"
const val OP_CAPTIONS = "captions"
// Relay calls this op "input" (see internal/mcp/tools_type.go: opInput).
// Spec 0007 reserves room for future cmds (backspace, select_all); v1 only
// implements `type`.
const val OP_INPUT = "input"

// Param keys — names mirror the relay-side `paramX` constants verbatim.
const val PARAM_CMD = "cmd"
const val PARAM_LEVEL = "level"
const val PARAM_POSITION_MS = "position_ms"
const val PARAM_DELTA_MS = "delta_ms"
const val PARAM_LANG = "lang"
const val PARAM_TEXT = "text"

// Cmd values per op. Relay-side:
//   tools_playback.go: playbackCmdGet/Seek/Pause/Resume
//   tools_volume.go:   volumeCmdGet/Set/Mute/Unmute
//   tools_captions.go: captionsCmdOn/Off/SetLanguage
//   tools_type.go:     inputCmdType
const val CMD_GET = "get"
const val CMD_SET = "set"
const val CMD_SEEK = "seek"
const val CMD_PAUSE = "pause"
const val CMD_RESUME = "resume"
const val CMD_MUTE = "mute"
const val CMD_UNMUTE = "unmute"
const val CMD_ON = "on"
const val CMD_OFF = "off"
const val CMD_SET_LANGUAGE = "set_language"
const val CMD_TYPE = "type"

// Outbound data keys. Each one is what the relay's `res.Data[…]` lookup
// expects — keyPlaybackJSON, keyVolumeJSON, keyCaptionsJSON, keyInputJSON.
// NOTE: tv_type's data key is `input_json` (NOT `type_json`) because the
// relay calls the op "input" — see tools_type.go:keyInputJSON.
const val KEY_OBSERVE_JSON = "observe_json"
const val KEY_APPS_JSON = "apps_json"
const val KEY_PLAYBACK_JSON = "playback_json"
const val KEY_VOLUME_JSON = "volume_json"
const val KEY_CAPTIONS_JSON = "captions_json"
const val KEY_INPUT_JSON = "input_json"

// ERR_ACCESSIBILITY_NOT_GRANTED is the exact string the relay's
// makeObserveHandler matches to tell Claude "a setup prompt was just
// posted on the TV". Do NOT change without updating the relay in lockstep.
const val ERR_ACCESSIBILITY_NOT_GRANTED = "accessibility_not_granted"

// Workstream-F bridge-side error codes. Each one is matched verbatim by
// the relay's translateXError() switch; changing a string here without
// updating the relay reverts that error to a verbatim passthrough — not
// fatal but breaks the human-readable hint Claude surfaces to the user.
const val ERR_PLAYBACK_NO_SESSION = "playback_no_session"
const val ERR_PLAYBACK_SEEK_UNSUPPORTED = "playback_seek_unsupported"
const val ERR_PLAYBACK_NOTIFICATION_LISTENER_NOT_GRANTED = "playback_notification_listener_not_granted"
const val ERR_VOLUME_NOT_AVAILABLE = "volume_not_available"
const val ERR_CAPTIONS_UNSUPPORTED = "captions_unsupported"
const val ERR_CAPTIONS_LANGUAGE_UNAVAILABLE = "captions_language_unavailable"
const val ERR_INPUT_NO_EDITABLE_FOCUS = "input_no_editable_focus"
const val ERR_INPUT_SET_TEXT_REJECTED = "input_set_text_rejected"

// Captions source values — populated into captions_json.source so Claude
// knows whether the toggle landed at the OS level or just dispatched a key.
const val CAPTIONS_SOURCE_SYSTEM = "system"
const val CAPTIONS_SOURCE_APP_KEY = "app_key"
const val CAPTIONS_SOURCE_BEST_EFFORT = "best_effort"

// Playback state + source enums — strings the relay's PlaybackSnapshot
// fields expect. Mirror tools_playback.go:State/Source comments.
const val PLAYBACK_STATE_PLAYING = "playing"
const val PLAYBACK_STATE_PAUSED = "paused"
const val PLAYBACK_STATE_BUFFERING = "buffering"
const val PLAYBACK_STATE_STOPPED = "stopped"
const val PLAYBACK_STATE_UNKNOWN = "unknown"
const val PLAYBACK_SOURCE_MEDIA_SESSION = "media_session"
const val PLAYBACK_SOURCE_ACCESSIBILITY = "accessibility"

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
