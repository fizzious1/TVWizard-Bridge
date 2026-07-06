package ai.djwizard.tvbridge

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import ai.djwizard.tvbridge.databinding.ActivityMainBinding

// MainActivity renders the bridge's state as published by
// TVAccessibilityService.state. Pairing/command networking lives in the
// service, which stays alive after the activity goes away; the one exception
// is the user-initiated self-updater (UpdateManager), which is activity-driven
// on purpose — it must work even before accessibility is granted, and a
// download that dies with the screen is fine.
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var config: ConfigStore
    private lateinit var updater: UpdateManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        config = ConfigStore(applicationContext)
        updater = UpdateManager(applicationContext)

        binding.primaryButton.setOnClickListener {
            when (TVAccessibilityService.state.value) {
                BridgeState.AwaitingAccessibility,
                is BridgeState.Error -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                else -> { /* primary button hides itself in other states */ }
            }
        }

        binding.resetButton.setOnClickListener {
            config.clear()
            TVAccessibilityService.get()?.beginPairing()
        }

        binding.updateButton.setOnClickListener { startUpdateFlow() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { TVAccessibilityService.state.collect { render(it) } }
                launch { UpdateManager.state.collect { renderUpdate(it) } }
            }
        }

        // Passive staleness hint: one manifest probe per activity creation.
        // Errors are swallowed inside checkQuietly — pairing UX comes first.
        lifecycleScope.launch { updater.checkQuietly() }
    }

    // startUpdateFlow gates on the API 26+ "Install unknown apps" grant before
    // downloading anything: without it the PackageInstaller commit would fail
    // after the download instead of before it.
    private fun startUpdateFlow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !packageManager.canRequestPackageInstalls()
        ) {
            binding.updateStatusText.text = getString(R.string.update_grant_install_permission)
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName"),
                )
            )
            return
        }
        lifecycleScope.launch { updater.checkAndInstall() }
    }

    private fun render(state: BridgeState) {
        when (state) {
            BridgeState.AwaitingAccessibility -> {
                binding.titleText.text = getString(R.string.title_awaiting_accessibility)
                binding.statusText.text = getString(R.string.status_awaiting_accessibility)
                binding.pairBlock.visibility = View.GONE
                binding.primaryButton.visibility = View.VISIBLE
                binding.primaryButton.text = getString(R.string.btn_open_accessibility)
                binding.resetButton.visibility = View.GONE
            }

            BridgeState.NeedsPairing,
            BridgeState.Connecting -> {
                binding.titleText.text = getString(R.string.title_connecting)
                binding.statusText.text = getString(R.string.status_connecting)
                binding.pairBlock.visibility = View.GONE
                binding.primaryButton.visibility = View.GONE
                binding.resetButton.visibility = View.VISIBLE
            }

            is BridgeState.Pairing -> {
                binding.titleText.text = getString(R.string.title_pairing)
                binding.pairCodeText.text = state.code
                binding.pairInstructions.text = getString(R.string.pair_instructions)
                binding.statusText.text = ""
                binding.primaryButton.visibility = View.GONE
                binding.resetButton.visibility = View.VISIBLE

                // Render the QR that opens /claim with the code prefilled.
                // Scaled to match the 260dp ImageView at ~2x density.
                val claimUrl = "https://tv.djwizard.ai/claim?code=${state.code}"
                binding.pairQr.setImageBitmap(QrCode.render(claimUrl, QR_SIZE_PX))
                binding.pairBlock.visibility = View.VISIBLE
            }

            BridgeState.Online -> {
                binding.titleText.text = getString(R.string.title_online)
                binding.statusText.text = getString(R.string.status_online)
                binding.pairBlock.visibility = View.GONE
                binding.primaryButton.visibility = View.GONE
                binding.resetButton.visibility = View.VISIBLE
            }

            is BridgeState.Error -> {
                binding.titleText.text = getString(R.string.title_error)
                binding.statusText.text = state.message
                binding.pairBlock.visibility = View.GONE
                binding.primaryButton.visibility = View.VISIBLE
                binding.resetButton.visibility = View.VISIBLE
            }
        }
    }

    // renderUpdate drives the self-updater's dedicated row (button + status
    // line) — independent of the bridge-state rendering above so an update is
    // reachable from every bridge state.
    private fun renderUpdate(state: UpdateState) {
        binding.updateButton.isEnabled =
            state is UpdateState.Idle || state is UpdateState.UpToDate || state is UpdateState.Error
        binding.updateStatusText.text = when (state) {
            is UpdateState.Idle ->
                state.availableVersion
                    ?.let { getString(R.string.update_available, BuildConfig.VERSION_NAME, it) }
                    ?: getString(R.string.update_idle_version, BuildConfig.VERSION_NAME)
            UpdateState.Checking -> getString(R.string.update_checking)
            UpdateState.UpToDate -> getString(R.string.update_up_to_date, BuildConfig.VERSION_NAME)
            is UpdateState.Downloading -> getString(R.string.update_downloading, state.percent)
            UpdateState.Verifying -> getString(R.string.update_verifying)
            UpdateState.AwaitingConfirm -> getString(R.string.update_awaiting_confirm)
            UpdateState.Success -> getString(R.string.update_success)
            is UpdateState.Error -> getString(R.string.update_error, state.message)
        }
    }

    private companion object {
        // Matches the ImageView's 260dp at ~2x density. Bigger is sharper on
        // high-DPI Google TVs without blowing up memory for the one-shot render.
        const val QR_SIZE_PX = 520
    }
}
