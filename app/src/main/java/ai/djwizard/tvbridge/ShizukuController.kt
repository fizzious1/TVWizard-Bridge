package ai.djwizard.tvbridge

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import rikka.shizuku.Shizuku

// ShizukuController owns the Shizuku binder lifecycle and the bound privileged
// UserService. When ready, sendKeyEvent() injects a real key event via
// `input keyevent` running as shell uid — the only path that drives d-pad/OK
// inside fullscreen players on Android <13, where GLOBAL_ACTION_DPAD_* (API
// 33+) does not exist and the accessibility scroll fallback finds no node.
//
// Degrades silently: when Shizuku isn't installed, isn't started, or hasn't
// been granted, isReady() stays false and the dispatch path falls back to the
// existing accessibility behavior. init() is idempotent and safe to call from
// both the AccessibilityService and MainActivity.
object ShizukuController {

    private const val TAG = "ShizukuController"
    const val PERMISSION_REQUEST_CODE = 4216

    @Volatile
    private var userService: IUserService? = null

    @Volatile
    private var initialized = false

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(BuildConfig.APPLICATION_ID, ShizukuUserService::class.java.name),
    )
        .daemon(false)
        .processNameSuffix("keyinjector")
        .debuggable(false)
        .version(1)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            userService = if (binder != null && binder.pingBinder()) {
                IUserService.Stub.asInterface(binder)
            } else {
                Log.w(TAG, "user service binder invalid")
                null
            }
            Log.i(TAG, "user service connected ready=${userService != null}")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            userService = null
            Log.i(TAG, "user service disconnected")
        }
    }

    private val binderReceived = Shizuku.OnBinderReceivedListener { onBinderReceived() }
    private val binderDead = Shizuku.OnBinderDeadListener { userService = null }
    private val permResult = Shizuku.OnRequestPermissionResultListener { code, grant ->
        if (code == PERMISSION_REQUEST_CODE && grant == PackageManager.PERMISSION_GRANTED) bind()
    }

    fun init() {
        if (initialized) return
        initialized = true
        // Sticky variant fires immediately if the binder already arrived (e.g.
        // ShizukuProvider initialized it before we registered).
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permResult)
    }

    private fun onBinderReceived() {
        try {
            if (Shizuku.isPreV11()) {
                Log.w(TAG, "Shizuku pre-v11 unsupported")
                return
            }
            if (hasPermission()) bind() else Log.i(TAG, "Shizuku up, permission not granted")
        } catch (t: Throwable) {
            Log.w(TAG, "onBinderReceived: ${t.message}")
        }
    }

    fun isAvailable(): Boolean = try {
        Shizuku.pingBinder()
    } catch (t: Throwable) {
        false
    }

    fun hasPermission(): Boolean = try {
        !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }

    // requestPermission must be triggered from an interactive (Activity)
    // context — MainActivity calls it when Shizuku is up but not yet granted.
    fun requestPermission() {
        try {
            if (Shizuku.isPreV11() || Shizuku.shouldShowRequestPermissionRationale()) return
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        } catch (t: Throwable) {
            Log.w(TAG, "requestPermission: ${t.message}")
        }
    }

    private fun bind() {
        if (userService != null) return
        try {
            Shizuku.bindUserService(userServiceArgs, connection)
        } catch (t: Throwable) {
            Log.w(TAG, "bindUserService: ${t.message}")
        }
    }

    fun isReady(): Boolean = userService != null

    // Injects a key event; returns true only when the shell process exits 0.
    // On any binder error, drops the cached service so the next call can rebind.
    fun sendKeyEvent(keyCode: Int): Boolean {
        val svc = userService ?: return false
        return try {
            svc.sendKeyEvent(keyCode) == 0
        } catch (t: Throwable) {
            Log.w(TAG, "sendKeyEvent $keyCode: ${t.message}")
            userService = null
            false
        }
    }
}
