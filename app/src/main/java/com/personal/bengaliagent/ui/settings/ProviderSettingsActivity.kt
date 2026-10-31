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
import com.personal.bengaliagent.domain.model.ConnectionState
import com.personal.bengaliagent.domain.model.ErrorCategory
import com.personal.bengaliagent.domain.model.ProviderType
import com.personal.bengaliagent.ui.memory.MemorySettingsActivity
import com.personal.bengaliagent.ui.setup.SetupWizardActivity
import com.personal.bengaliagent.ui.voice.VoiceSettingsActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
            renderConnectionState(
                tvBadge,
                tvStatus,
                state = ConnectionState.CONNECTED,
                detail = "Connected and verified (${provider.displayName})"
            )
        } else {
            renderConnectionState(
                tvBadge,
                tvStatus,
                state = ConnectionState.DISCONNECTED,
                detail = "Enter your ${provider.displayName} API key and tap Connect."
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
            if (rawKey.isEmpty()) {
                renderConnectionState(
                    tvBadge,
                    tvStatus,
                    state = ConnectionState.AUTHENTICATION_ERROR,
                    detail = "API key cannot be empty. Please enter your ${provider.displayName} key."
                )
                return@OnClickListener
            }

            connectJob = lifecycleScope.launch {
                try {
                    btnConnect.isEnabled = false
                    btnRetry?.isEnabled = false
                    if (provider == ProviderType.GEMINI) {
                        binding.progressConnecting.visibility = View.VISIBLE
                    }
                    renderConnectionState(
                        tvBadge,
                        tvStatus,
                        state = if (btnRetry?.visibility == View.VISIBLE) {
                            ConnectionState.RECONNECTING
                        } else {
                            ConnectionState.CONNECTING
                        },
                        detail = "Verifying ${provider.displayName} API key and model availability..."
                    )

                    // Hard 15-second timeout prevents infinite "Connecting..." hangs
                    val result = withTimeoutOrNull(15_000L) {
                        router.connectAndVerifyProvider(provider, rawKey)
                    }

                    if (result == null) {
                        btnRetry?.visibility = View.VISIBLE
                        renderConnectionState(
                            tvBadge,
                            tvStatus,
                            state = ConnectionState.NETWORK_ERROR,
                            detail = "Connection timed out after 15 seconds. Please check your internet connection and tap Retry."
                        )
                    } else if (result.success) {
                        btnRetry?.visibility = View.GONE
                        renderConnectionState(
                            tvBadge,
                            tvStatus,
                            state = ConnectionState.CONNECTED,
                            detail = result.userMessage
                        )
                    } else {
                        btnRetry?.visibility = View.VISIBLE
                        val errState = when (result.errorCategory) {
                            ErrorCategory.INVALID_KEY,
                            ErrorCategory.PERMISSION_DENIED -> ConnectionState.AUTHENTICATION_ERROR
                            else -> ConnectionState.NETWORK_ERROR
                        }
                        renderConnectionState(
                            tvBadge,
                            tvStatus,
                            state = errState,
                            detail = result.userMessage
                        )
                    }
                } catch (e: Exception) {
                    btnRetry?.visibility = View.VISIBLE
                    renderConnectionState(
                        tvBadge,
                        tvStatus,
                        state = ConnectionState.NETWORK_ERROR,
                        detail = "Connection error: ${e.localizedMessage ?: e.javaClass.simpleName}"
                    )
                } finally {
                    btnConnect.isEnabled = true
                    btnRetry?.isEnabled = true
                    if (provider == ProviderType.GEMINI) {
                        binding.progressConnecting.visibility = View.GONE
                    }
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
                if (provider == ProviderType.GEMINI) {
                    binding.progressConnecting.visibility = View.GONE
                }
                renderConnectionState(
                    tvBadge,
                    tvStatus,
                    state = ConnectionState.DISCONNECTED,
                    detail = "Disconnected from ${provider.displayName}."
                )
            }
        }
    }

    private fun renderConnectionState(
        tvBadge: TextView,
        tvStatus: TextView,
        state: ConnectionState,
        detail: String
    ) {
        tvBadge.text = state.displayLabel
        val colorRes = when (state) {
            ConnectionState.CONNECTED -> R.color.accent_green
            ConnectionState.INITIALIZING,
            ConnectionState.CONNECTING,
            ConnectionState.RECONNECTING -> R.color.accent_cyan
            ConnectionState.DISCONNECTED -> R.color.text_secondary
            ConnectionState.AUTHENTICATION_ERROR,
            ConnectionState.NETWORK_ERROR -> R.color.accent_red_bright
        }
        tvBadge.setTextColor(ContextCompat.getColor(this, colorRes))
        tvStatus.text = detail
        tvStatus.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    override fun onDestroy() {
        connectJob?.cancel()
        super.onDestroy()
    }
}
