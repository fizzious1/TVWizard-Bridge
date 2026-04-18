package ai.djwizard.tvbridge

// Observable state the accessibility service emits for the UI to render.
//
// Flow:
//   AwaitingAccessibility (service disabled)
//   → NeedsPairing       (service enabled, no token)
//   → Pairing(code)      (we have a /pair/init token, waiting for claim)
//   → Connecting         (retrying WebSocket)
//   → Online             (WebSocket open, commands flow)
sealed class BridgeState {
    data object AwaitingAccessibility : BridgeState()
    data object NeedsPairing : BridgeState()
    data class Pairing(val code: String) : BridgeState()
    data object Connecting : BridgeState()
    data object Online : BridgeState()
    data class Error(val message: String) : BridgeState()
}
