package ai.djwizard.tvbridge

import android.util.Log
import kotlin.system.exitProcess

// ShizukuUserService runs inside a process the Shizuku server spawns with the
// ADB shell uid (2000). From there, `input keyevent` injects real key events
// into whatever app is in the foreground — including fullscreen video players,
// where the AccessibilityService d-pad path fails on Android <13. Shizuku
// instantiates this via its no-arg constructor in the privileged process.
class ShizukuUserService : IUserService.Stub() {

    override fun destroy() {
        // Reserved teardown path — the Shizuku server calls this to stop the
        // user-service process. Exit so the process doesn't linger.
        exitProcess(0)
    }

    override fun exit() {
        destroy()
    }

    override fun sendKeyEvent(keyCode: Int): Int {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("input", "keyevent", keyCode.toString()))
            val code = process.waitFor()
            if (code != 0) Log.w(TAG, "input keyevent $keyCode exited $code")
            code
        } catch (t: Throwable) {
            Log.w(TAG, "input keyevent $keyCode failed: ${t.message}")
            -1
        }
    }

    private companion object {
        const val TAG = "ShizukuUserService"
    }
}
