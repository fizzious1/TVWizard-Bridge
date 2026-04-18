package ai.djwizard.tvbridge

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import ai.djwizard.tvbridge.databinding.ActivityMainBinding

// MainActivity is a thin status screen. Everything operational — the
// WebSocket, the key dispatch — lives in TVAccessibilityService, which
// stays alive even when nothing is in the foreground.
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.openAccessibilitySettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    private fun refreshUi() {
        val token = BuildConfig.BRIDGE_TOKEN
        val a11y = TVAccessibilityService.isEnabled()
        val tokenPresent = token.isNotBlank()

        binding.titleText.text = when {
            !tokenPresent -> getString(R.string.title_no_token)
            !a11y -> getString(R.string.title_offline)
            else -> getString(R.string.title_online, BuildConfig.DEVICE_ID)
        }

        binding.statusText.text = buildString {
            append("relay: ").append(BuildConfig.RELAY_URL).append('\n')
            append("device_id: ").append(BuildConfig.DEVICE_ID).append('\n')
            append("token: ").append(if (tokenPresent) "present" else "MISSING")
        }

        binding.accessibilityText.text =
            if (a11y) getString(R.string.accessibility_ok)
            else getString(R.string.enable_accessibility)

        binding.openAccessibilitySettings.visibility =
            if (a11y) android.view.View.GONE else android.view.View.VISIBLE
    }
}
