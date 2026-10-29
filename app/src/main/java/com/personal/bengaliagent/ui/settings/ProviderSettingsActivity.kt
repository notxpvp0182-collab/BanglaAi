package com.personal.bengaliagent.ui.settings

import android.os.Bundle
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.personal.bengaliagent.AgentApp
import com.personal.bengaliagent.R
import com.personal.bengaliagent.databinding.ActivityProviderSettingsBinding
import com.personal.bengaliagent.domain.model.ProviderType
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Clean, single-step AI Connection screen for Google Gemini.
 * Automatically discovers eligible models and verifies connectivity without exposing a manual model picker.
 */
class ProviderSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProviderSettingsBinding
    private val keyStore by lazy { AgentApp.instance.secureKeyStore }
    private val prefs by lazy { AgentApp.instance.appPreferences }
    private val router by lazy { AgentApp.instance.aiClientRouter }
    private var connectJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProviderSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val existingKey = keyStore.getApiKey(ProviderType.GEMINI)
        if (!existingKey.isNullOrBlank()) {
            binding.etGeminiKey.setText(existingKey)
        }

        lifecycleScope.launch {
            binding.cbCloudConsent.isChecked = prefs.isCloudSharingConsented()
            val isVerified = keyStore.isProviderVerified(ProviderType.GEMINI)
            val lastMsg = prefs.getLastConnectionMessage()
            if (isVerified) {
                renderConnectedState(lastMsg)
            } else {
                renderDisconnectedState(lastMsg)
            }
        }

        binding.cbShowGeminiKey.setOnCheckedChangeListener { _, isChecked ->
            binding.etGeminiKey.transformationMethod = if (isChecked) {
                HideReturnsTransformationMethod.getInstance()
            } else {
                PasswordTransformationMethod.getInstance()
            }
            binding.etGeminiKey.setSelection(binding.etGeminiKey.text?.length ?: 0)
        }

        binding.cbCloudConsent.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch { prefs.setCloudSharingConsented(isChecked) }
        }

        binding.btnConnectGemini.setOnClickListener {
            triggerGeminiConnection()
        }

        binding.btnRetryGemini.setOnClickListener {
            triggerGeminiConnection()
        }

        binding.btnDisconnectGemini.setOnClickListener {
            connectJob?.cancel()
            lifecycleScope.launch {
                router.disconnectGemini()
                binding.etGeminiKey.setText("")
                binding.btnRetryGemini.visibility = View.GONE
                renderDisconnectedState("Disconnected from Google Gemini.")
            }
        }

        binding.btnCloseSettings.setOnClickListener { finish() }
    }

    private fun triggerGeminiConnection() {
        if (connectJob?.isActive == true) return
        val rawKey = binding.etGeminiKey.text?.toString()?.trim().orEmpty()

        connectJob = lifecycleScope.launch {
            renderConnectingState()
            val result = router.connectAndVerifyGemini(rawKey)
            if (result.success) {
                binding.btnRetryGemini.visibility = View.GONE
                renderConnectedState("Connected to Google Gemini (${result.latencyMs}ms)")
            } else {
                binding.btnRetryGemini.visibility = View.VISIBLE
                renderErrorState(result.userMessage)
            }
        }
    }

    private fun renderConnectingState() {
        binding.btnConnectGemini.isEnabled = false
        binding.btnRetryGemini.isEnabled = false
        binding.progressConnecting.visibility = View.VISIBLE
        binding.tvConnectionBadge.text = "Connecting..."
        binding.tvConnectionBadge.setTextColor(ContextCompat.getColor(this, R.color.accent_amber))
        binding.tvGeminiStatus.text = "Discovering available Gemini models and verifying connection..."
        binding.tvGeminiStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
    }

    private fun renderConnectedState(message: String) {
        binding.btnConnectGemini.isEnabled = true
        binding.btnRetryGemini.isEnabled = true
        binding.progressConnecting.visibility = View.GONE
        binding.tvConnectionBadge.text = "Connected"
        binding.tvConnectionBadge.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
        binding.tvGeminiStatus.text = message
        binding.tvGeminiStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
    }

    private fun renderDisconnectedState(message: String) {
        binding.btnConnectGemini.isEnabled = true
        binding.btnRetryGemini.isEnabled = true
        binding.progressConnecting.visibility = View.GONE
        binding.tvConnectionBadge.text = "Not Connected"
        binding.tvConnectionBadge.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        binding.tvGeminiStatus.text = message
        binding.tvGeminiStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
    }

    private fun renderErrorState(errorMessage: String) {
        binding.btnConnectGemini.isEnabled = true
        binding.btnRetryGemini.isEnabled = true
        binding.progressConnecting.visibility = View.GONE
        binding.tvConnectionBadge.text = "Connection Failed"
        binding.tvConnectionBadge.setTextColor(ContextCompat.getColor(this, R.color.accent_red))
        binding.tvGeminiStatus.text = errorMessage
        binding.tvGeminiStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_amber))
    }

    override fun onDestroy() {
        connectJob?.cancel()
        super.onDestroy()
    }
}
