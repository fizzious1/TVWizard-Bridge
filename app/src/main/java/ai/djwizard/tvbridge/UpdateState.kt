package ai.djwizard.tvbridge

// Observable state of the one-tap self-updater, rendered by MainActivity.
//
// Flow:
//   Idle(availableVersion?)  (passive manifest probe may fill availableVersion)
//   → Checking               (user pressed the button; fetching manifest)
//   → UpToDate | Downloading(percent) → Verifying → AwaitingConfirm
//   → Success                (rarely observed — a successful self-update kills
//                             this process before the callback lands)
//   Error(message) from any step; the downloaded APK is deleted on the way.
sealed class UpdateState {
    data class Idle(val availableVersion: String? = null) : UpdateState()
    data object Checking : UpdateState()
    data object UpToDate : UpdateState()
    data class Downloading(val percent: Int) : UpdateState()
    data object Verifying : UpdateState()
    data object AwaitingConfirm : UpdateState()
    data object Success : UpdateState()
    data class Error(val message: String) : UpdateState()
}
