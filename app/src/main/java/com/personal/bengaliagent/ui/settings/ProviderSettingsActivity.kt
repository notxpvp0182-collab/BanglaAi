package com.personal.bengaliagent.ui.settings

import android.content.Intent
import android.os.Bundle
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.personal.bengaliagent.AgentApp
import com.personal.bengaliagent.R
import com.personal.bengaliagent.databinding.ActivityProviderSettingsBinding
import com.personal.bengaliagent.domain.model.ProviderType
import com.personal.bengaliagent.ui.memory.MemorySettingsActivity
import com.personal.bengaliagent.ui.setup.SetupWizardActivity
import com.personal.bengaliagent.ui.voice.VoiceSettingsActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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

        setupCapabilityTogglesAndNavigation()

        setupProviderBlock(
            provider = ProviderType.GEMINI,
            etKey = binding.etGeminiKey,
            cbShow = binding.cbShowGeminiKey,
            tvBadge = binding.tvConnectionBadge,
            tvStatus = binding.tvGeminiStatus,
            btnConnect = binding.btnConnectGemini,
            btnDisconnect = binding.btnDisconnectGemini,
            btnRetry = binding.btnRetryGemini
        )

        setupProviderBlock(
            provider = ProviderType.GROQ,
            etKey = binding.etGroqKey,
            cbShow = binding.cbShowGroqKey,
            tvBadge = binding.tvGroqBadge,
            tvStatus = binding.tvGroqStatus,
            btnConnect = binding.btnConnectGroq,
            btnDisconnect = binding.btnDisconnectGroq,
            btnRetry = null
        )

        setupProviderBlock(
            provider = ProviderType.OPENROUTER,
            etKey = binding.etOpenRouterKey,
            cbShow = binding.cbShowOpenRouterKey,
            tvBadge = binding.tvOpenRouterBadge,
            tvStatus = binding.tvOpenRouterStatus,
            btnConnect = binding.btnConnectOpenRouter,
            btnDisconnect = binding.btnDisconnectOpenRouter,
            btnRetry = null
        )

        binding.btnCloseSettings.setOnClickListener { finish() }
    }

    private fun setupCapabilityTogglesAndNavigation() {
        lifecycleScope.launch {
            binding.cbAiCodingCapability.isChecked = prefs.isAiCodingCapabilityEnabled()
            binding.cbAiVideoCapability.isChecked = prefs.isAiVideoEditCapabilityEnabled()
            binding.cbCloudConsent.isChecked = prefs.isCloudSharingConsented()
        }

        binding.cbAiCodingCapability.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch { prefs.setAiCodingCapabilityEnabled(isChecked) }
        }

        binding.cbAiVideoCapability.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch { prefs.setAiVideoEditCapabilityEnabled(isChecked) }
        }

        binding.cbCloudConsent.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch { prefs.setCloudSharingConsented(isChecked) }
        }

        binding.btnOpenPermissionsHub.setOnClickListener {
            startActivity(Intent(this, SetupWizardActivity::class.java))
        }

        binding.btnOpenVoiceHub.setOnClickListener {
            startActivity(Intent(this, VoiceSettingsActivity::class.java))
        }

        binding.btnOpenMemoryHub.setOnClickListener {
            startActivity(Intent(this, MemorySettingsActivity::class.java))
        }
    }

    private fun setupProviderBlock(
        provider: ProviderType,
        etKey: EditText,
        cbShow: CheckBox,
        tvBadge: TextView,
        tvStatus: TextView,
        btnConnect: View,
        btnDisconnect: View,
        btnRetry: View?
    ) {
        val existingKey = keyStore.getApiKey(provider)
        if (!existingKey.isNullOrBlank()) {
            etKey.setText(existingKey)
        }

        val isVerified = keyStore.isProviderVerified(provider)
        if (isVerified) {
            renderBadgeAndStatus(
                tvBadge,
                tvStatus,
                badgeText = "Connected",
                badgeColorRes = R.color.accent_green,
                statusText = "Connected and verified (${provider.displayName})",
                statusColorRes = R.color.accent_green
            )
        } else {
            renderBadgeAndStatus(
                tvBadge,
                tvStatus,
                badgeText = "Not Connected",
                badgeColorRes = R.color.text_secondary,
                statusText = "Status: Enter your ${provider.displayName} API key and tap Connect.",
                statusColorRes = R.color.text_secondary
            )
        }

        cbShow.setOnCheckedChangeListener { _, isChecked ->
            etKey.transformationMethod = if (isChecked) {
                HideReturnsTransformationMethod.getInstance()
            } else {
                PasswordTransformationMethod.getInstance()
            }
            etKey.setSelection(etKey.text?.length ?: 0)
        }

        val connectAction = View.OnClickListener {
            if (connectJob?.isActive == true) return@OnClickListener
            val rawKey = etKey.text?.toString()?.trim().orEmpty()

            connectJob = lifecycleScope.launch {
                btnConnect.isEnabled = false
                btnRetry?.isEnabled = false
                if (provider == ProviderType.GEMINI) {
                    binding.progressConnecting.visibility = View.VISIBLE
                }
                renderBadgeAndStatus(
                    tvBadge,
                    tvStatus,
                    badgeText = "Connecting...",
                    badgeColorRes = R.color.accent_amber,
                    statusText = "Discovering available models & verifying ${provider.displayName}...",
                    statusColorRes = R.color.text_secondary
                )

                val result = router.connectAndVerifyProvider(provider, rawKey)
                btnConnect.isEnabled = true
                btnRetry?.isEnabled = true
                if (provider == ProviderType.GEMINI) {
                    binding.progressConnecting.visibility = View.GONE
                }

                if (result.success) {
                    btnRetry?.visibility = View.GONE
                    renderBadgeAndStatus(
                        tvBadge,
                        tvStatus,
                        badgeText = "Connected",
                        badgeColorRes = R.color.accent_green,
                        statusText = result.userMessage,
                        statusColorRes = R.color.accent_green
                    )
                } else {
                    btnRetry?.visibility = View.VISIBLE
                    renderBadgeAndStatus(
                        tvBadge,
                        tvStatus,
                        badgeText = "Connection Failed",
                        badgeColorRes = R.color.accent_red,
                        statusText = result.userMessage,
                        statusColorRes = R.color.accent_amber
                    )
                }
            }
        }

        btnConnect.setOnClickListener(connectAction)
        btnRetry?.setOnClickListener(connectAction)

        btnDisconnect.setOnClickListener {
            connectJob?.cancel()
            lifecycleScope.launch {
                router.disconnectProvider(provider)
                etKey.setText("")
                btnRetry?.visibility = View.GONE
                renderBadgeAndStatus(
                    tvBadge,
                    tvStatus,
                    badgeText = "Not Connected",
                    badgeColorRes = R.color.text_secondary,
                    statusText = "Disconnected from ${provider.displayName}.",
                    statusColorRes = R.color.text_secondary
                )
            }
        }
    }

    private fun renderBadgeAndStatus(
        tvBadge: TextView,
        tvStatus: TextView,
        badgeText: String,
        badgeColorRes: Int,
        statusText: String,
        statusColorRes: Int
    ) {
        tvBadge.text = badgeText
        tvBadge.setTextColor(ContextCompat.getColor(this, badgeColorRes))
        tvStatus.text = statusText
        tvStatus.setTextColor(ContextCompat.getColor(this, statusColorRes))
    }

    override fun onDestroy() {
        connectJob?.cancel()
        super.onDestroy()
    }
}
