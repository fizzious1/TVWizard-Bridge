package ai.djwizard.tvbridge

import android.service.notification.NotificationListenerService
import android.util.Log

// TVNotificationListener exists for exactly one reason: its ComponentName is
// accepted by MediaSessionManager.getActiveSessions(ComponentName), which
// otherwise throws SecurityException. We don't actually read notifications;
// this class is a grant-receipt. The user enables it once in Settings →
// Notifications → Notification access → TVWizard Bridge.
//
// See TVWizard/docs/specs/0001-playback-tools.md and PlaybackController.kt
// for the runtime read path.
class TVNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "notification listener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.i(TAG, "notification listener disconnected")
    }

    companion object {
        private const val TAG = "TVBridge.Notif"
    }
}
