package ai.djwizard.tvbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log

// UpdateReceiver handles PackageInstaller status callbacks for the self-update
// session committed by UpdateManager. Manifest-declared and non-exported: the
// only sender is the system delivering our own PendingIntent.
//
// STATUS_PENDING_USER_ACTION carries the system confirm dialog — starting it
// is the "one tap" of the one-tap updater. STATUS_SUCCESS is rarely observed:
// a successful self-update kills this process as the package is replaced.
class UpdateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != UpdateManager.ACTION_INSTALL_STATUS) return

        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm == null) {
                    UpdateManager.state.value =
                        UpdateState.Error("installer did not provide a confirmation dialog")
                    return
                }
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(confirm)
            }

            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "update installed")
                UpdateManager.state.value = UpdateState.Success
            }

            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                // The user dismissed the confirm dialog — a decision, not an
                // error. Back to idle; the passive probe re-hints next launch.
                Log.i(TAG, "update canceled by user")
                UpdateManager.state.value = UpdateState.Idle()
            }

            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    ?: "installer status $status"
                Log.w(TAG, "install failed: $msg")
                UpdateManager.state.value = UpdateState.Error(msg)
            }
        }
    }

    private companion object {
        const val TAG = "TVBridgeUpdate"
    }
}
