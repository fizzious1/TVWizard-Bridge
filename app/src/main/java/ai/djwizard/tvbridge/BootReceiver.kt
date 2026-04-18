package ai.djwizard.tvbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

// BOOT_COMPLETED is already enough for us: the system auto-restarts any
// accessibility service the user has enabled, and that service owns the
// WebSocket. This receiver only exists as belt-and-braces — it touches
// ConfigStore so the encrypted prefs are warmed up and logs a one-liner
// we can search for when a TV reboots.
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                val paired = ConfigStore(context).isPaired()
                Log.i(TAG, "boot received action=${intent.action} paired=$paired")
            }
        }
    }

    companion object {
        private const val TAG = "TVBridgeBoot"
    }
}
