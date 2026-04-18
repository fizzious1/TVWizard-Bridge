package ai.djwizard.tvbridge

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

// One small wrapper around EncryptedSharedPreferences. The bridge only ever
// needs to persist (token, deviceId) across restarts — three fields, no
// migrations, no encryption key lifecycle to worry about (AndroidX handles it
// via the Android Keystore).
class ConfigStore(context: Context) {

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            PREF_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    // Token precedence: prefs (set after in-app pairing) > BuildConfig (set at
    // build time with -PbridgeToken=…, useful for emulator smoke testing).
    var token: String
        get() = prefs.getString(KEY_TOKEN, null).takeUnless { it.isNullOrBlank() }
            ?: BuildConfig.BRIDGE_TOKEN
        set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    var deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, null).takeUnless { it.isNullOrBlank() }
            ?: BuildConfig.DEVICE_ID
        set(value) = prefs.edit().putString(KEY_DEVICE_ID, value).apply()

    // The 6-digit code from /pair/init. Kept so a device that reboots mid-
    // pairing can still display the code the user may already be reading.
    var pendingPairCode: String?
        get() = prefs.getString(KEY_PAIR_CODE, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().apply {
            if (value.isNullOrBlank()) remove(KEY_PAIR_CODE) else putString(KEY_PAIR_CODE, value)
        }.apply()

    fun isPaired(): Boolean =
        !prefs.getString(KEY_TOKEN, null).isNullOrBlank() ||
        BuildConfig.BRIDGE_TOKEN.isNotBlank()

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREF_FILE = "tvbridge.prefs.v1"
        private const val KEY_TOKEN = "token"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_PAIR_CODE = "pair_code"
    }
}
