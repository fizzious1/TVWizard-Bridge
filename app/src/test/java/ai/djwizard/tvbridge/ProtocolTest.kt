package ai.djwizard.tvbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

// ProtocolTest pins the Workstream-F op + cmd vocabulary against the
// relay-side spec strings. The relay's tools_{playback,volume,captions,
// type}.go pattern-match these literals — a typo here is wire-protocol
// drift that won't fail compilation but will silently break MCP calls.
//
// We don't unit-test the AccessibilityService handlers (framework-bound,
// instrumented-test territory) — only the constants the wire contract
// depends on.
class ProtocolTest {

    @Test
    fun `op constants match relay vocabulary`() {
        assertEquals("playback", OP_PLAYBACK)
        assertEquals("volume", OP_VOLUME)
        assertEquals("captions", OP_CAPTIONS)
        // The relay names this op "input", not "type", because the wire
        // surface reserves room for future cmds (backspace, select_all).
        assertEquals("input", OP_INPUT)
    }

    @Test
    fun `cmd vocabulary covers every relay-issued cmd`() {
        // Playback
        assertEquals("get", CMD_GET)
        assertEquals("seek", CMD_SEEK)
        assertEquals("pause", CMD_PAUSE)
        assertEquals("resume", CMD_RESUME)
        // Volume
        assertEquals("set", CMD_SET)
        assertEquals("mute", CMD_MUTE)
        assertEquals("unmute", CMD_UNMUTE)
        // Captions
        assertEquals("on", CMD_ON)
        assertEquals("off", CMD_OFF)
        assertEquals("set_language", CMD_SET_LANGUAGE)
        // Input
        assertEquals("type", CMD_TYPE)
    }

    @Test
    fun `data keys match relay-side res_Data lookups`() {
        assertEquals("playback_json", KEY_PLAYBACK_JSON)
        assertEquals("volume_json", KEY_VOLUME_JSON)
        assertEquals("captions_json", KEY_CAPTIONS_JSON)
        // input_json — NOT type_json. Mirrors tools_type.go:keyInputJSON.
        assertEquals("input_json", KEY_INPUT_JSON)
    }

    @Test
    fun `error codes match relay translateError switch arms`() {
        assertEquals("playback_no_session", ERR_PLAYBACK_NO_SESSION)
        assertEquals("playback_seek_unsupported", ERR_PLAYBACK_SEEK_UNSUPPORTED)
        assertEquals("playback_notification_listener_not_granted", ERR_PLAYBACK_NOTIFICATION_LISTENER_NOT_GRANTED)
        assertEquals("volume_not_available", ERR_VOLUME_NOT_AVAILABLE)
        assertEquals("captions_unsupported", ERR_CAPTIONS_UNSUPPORTED)
        assertEquals("captions_language_unavailable", ERR_CAPTIONS_LANGUAGE_UNAVAILABLE)
        assertEquals("input_no_editable_focus", ERR_INPUT_NO_EDITABLE_FOCUS)
        assertEquals("input_set_text_rejected", ERR_INPUT_SET_TEXT_REJECTED)
    }

    @Test
    fun `param keys match relay param constants`() {
        assertEquals("cmd", PARAM_CMD)
        assertEquals("level", PARAM_LEVEL)
        assertEquals("position_ms", PARAM_POSITION_MS)
        assertEquals("delta_ms", PARAM_DELTA_MS)
        assertEquals("lang", PARAM_LANG)
        assertEquals("text", PARAM_TEXT)
    }

    @Test
    fun `inbound and outbound frames round-trip every op + cmd combo`() {
        val combos = listOf(
            OP_PLAYBACK to listOf(CMD_GET, CMD_SEEK, CMD_PAUSE, CMD_RESUME),
            OP_VOLUME to listOf(CMD_GET, CMD_SET, CMD_MUTE, CMD_UNMUTE),
            OP_CAPTIONS to listOf(CMD_ON, CMD_OFF, CMD_SET_LANGUAGE),
            OP_INPUT to listOf(CMD_TYPE),
        )
        for ((op, cmds) in combos) {
            for (cmd in cmds) {
                val inbound = InboundFrame("id-$op-$cmd", op, mapOf(PARAM_CMD to cmd))
                assertEquals(op, inbound.op)
                assertEquals(cmd, inbound.params[PARAM_CMD])
                val outbound = OutboundFrame(inbound.id, ok = true, data = mapOf("k" to "v"))
                assertEquals(inbound.id, outbound.id)
                assertTrue(outbound.ok)
                assertEquals("v", outbound.data["k"])
            }
        }
    }

    @Test
    fun `error frames carry the bridge-side code in message`() {
        val codes = listOf(
            ERR_ACCESSIBILITY_NOT_GRANTED,
            ERR_PLAYBACK_NO_SESSION,
            ERR_PLAYBACK_SEEK_UNSUPPORTED,
            ERR_VOLUME_NOT_AVAILABLE,
            ERR_CAPTIONS_UNSUPPORTED,
            ERR_CAPTIONS_LANGUAGE_UNAVAILABLE,
            ERR_INPUT_NO_EDITABLE_FOCUS,
            ERR_INPUT_SET_TEXT_REJECTED,
        )
        for (code in codes) {
            val out = OutboundFrame("x", ok = false, message = code)
            assertFalse(out.ok)
            assertEquals(code, out.message)
            assertNotNull(out.data)
        }
    }

    @Test
    fun `captions source enum values are stable`() {
        assertEquals("system", CAPTIONS_SOURCE_SYSTEM)
        assertEquals("app_key", CAPTIONS_SOURCE_APP_KEY)
        assertEquals("best_effort", CAPTIONS_SOURCE_BEST_EFFORT)
    }

    @Test
    fun `playback state and source enum values are stable`() {
        assertEquals("playing", PLAYBACK_STATE_PLAYING)
        assertEquals("paused", PLAYBACK_STATE_PAUSED)
        assertEquals("buffering", PLAYBACK_STATE_BUFFERING)
        assertEquals("stopped", PLAYBACK_STATE_STOPPED)
        assertEquals("unknown", PLAYBACK_STATE_UNKNOWN)
        assertEquals("media_session", PLAYBACK_SOURCE_MEDIA_SESSION)
        assertEquals("accessibility", PLAYBACK_SOURCE_ACCESSIBILITY)
    }
}
