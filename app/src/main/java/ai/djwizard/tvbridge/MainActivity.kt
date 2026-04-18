package ai.djwizard.tvbridge

import android.content.Intent
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
// TVAccessibilityService.state. It never talks to the network directly —
// everything network-side lives in the service, which stays alive after the
// activity goes away.
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var config: ConfigStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        config = ConfigStore(applicationContext)

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

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                TVAccessibilityService.state.collect { render(it) }
            }
        }
    }

    private fun render(state: BridgeState) {
        when (state) {
            BridgeState.AwaitingAccessibility -> {
                binding.titleText.text = getString(R.string.title_awaiting_accessibility)
                binding.statusText.text = getString(R.string.status_awaiting_accessibility)
                binding.pairCodeText.visibility = View.GONE
                binding.pairInstructions.visibility = View.GONE
                binding.primaryButton.visibility = View.VISIBLE
                binding.primaryButton.text = getString(R.string.btn_open_accessibility)
                binding.resetButton.visibility = View.GONE
            }

            BridgeState.NeedsPairing,
            BridgeState.Connecting -> {
                binding.titleText.text = getString(R.string.title_connecting)
                binding.statusText.text = getString(R.string.status_connecting)
                binding.pairCodeText.visibility = View.GONE
                binding.pairInstructions.visibility = View.GONE
                binding.primaryButton.visibility = View.GONE
                binding.resetButton.visibility = View.VISIBLE
            }

            is BridgeState.Pairing -> {
                binding.titleText.text = getString(R.string.title_pairing)
                binding.pairCodeText.text = state.code
                binding.pairCodeText.visibility = View.VISIBLE
                binding.pairInstructions.visibility = View.VISIBLE
                binding.pairInstructions.text = getString(R.string.pair_instructions)
                    .replace("{{code}}", state.code)
                binding.statusText.text = ""
                binding.primaryButton.visibility = View.GONE
                binding.resetButton.visibility = View.VISIBLE
            }

            BridgeState.Online -> {
                binding.titleText.text = getString(R.string.title_online)
                binding.statusText.text = getString(R.string.status_online)
                binding.pairCodeText.visibility = View.GONE
                binding.pairInstructions.visibility = View.GONE
                binding.primaryButton.visibility = View.GONE
                binding.resetButton.visibility = View.VISIBLE
            }

            is BridgeState.Error -> {
                binding.titleText.text = getString(R.string.title_error)
                binding.statusText.text = state.message
                binding.pairCodeText.visibility = View.GONE
                binding.pairInstructions.visibility = View.GONE
                binding.primaryButton.visibility = View.VISIBLE
                binding.resetButton.visibility = View.VISIBLE
            }
        }
    }
}
