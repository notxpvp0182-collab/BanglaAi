package com.personal.bengaliagent.ui.settings

import android.content.Intent
import android.os.Bundle
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
import org.json.JSONArray

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
            btnRetry = binding.btnRetryGemini,
            btnInspectCatalog = binding.btnInspectGeminiModels
        )

        setupProviderBlock(
            provider = ProviderType.GROK,
            etKey = binding.etGroqKey,
            cbShow = binding.cbShowGroqKey,
            tvBadge = binding.tvGroqBadge,
            tvStatus = binding.tvGroqStatus,
            btnConnect = binding.btnConnectGroq,
            btnDisconnect = binding.btnDisconnectGroq,
            btnRetry = null,
            btnInspectCatalog = binding.btnInspectGrokModels
        )

        binding.btnCloseSettings.setOnClickListener { finish() }
    }

    private fun setupCapabilityTogglesAndNavigation() {
        lifecycleScope.launch {
            binding.cbAutoModelSelection.isChecked = prefs.isAutoModelSelectionEnabled()
            binding.cbAiCodingCapability.isChecked = prefs.isAiCodingCapabilityEnabled()
            binding.cbAiVideoCapability.isChecked = prefs.isAiVideoEditCapabilityEnabled()
            binding.cbCloudConsent.isChecked = prefs.isCloudSharingConsented()
        }

        binding.cbAutoModelSelection.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch { prefs.setAutoModelSelectionEnabled(isChecked) }
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
        btnRetry: View?,
        btnInspectCatalog: View
    ) {
        val existingKey = keyStore.getApiKey(provider)
        if (!existingKey.isNullOrBlank()) {
            etKey.setText(existingKey)
        }

        lifecycleScope.launch {
            val isVerified = keyStore.isProviderVerified(provider)
            val activeModel = prefs.getVerifiedModel(provider)
            val voiceModel = prefs.getLiveVoiceModel(provider)
            if (isVerified && !activeModel.isNullOrBlank()) {
                renderConnectionState(
                    tvBadge,
                    tvStatus,
                    state = ConnectionState.CONNECTED,
                    detail = "Active: $activeModel | Voice Call: ${voiceModel ?: activeModel}"
                )
            } else {
                renderConnectionState(
                    tvBadge,
                    tvStatus,
                    state = ConnectionState.DISCONNECTED,
                    detail = "Enter your ${provider.displayName} API key and tap Connect."
                )
            }
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
                    detail = "API key cannot be empty."
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
                        detail = "Authenticating & discovering ${provider.displayName} models..."
                    )

                    val result = withTimeoutOrNull(15_000L) {
                        router.connectAndVerifyProvider(provider, rawKey)
                    }

                    if (result == null) {
                        btnRetry?.visibility = View.VISIBLE
                        renderConnectionState(
                            tvBadge,
                            tvStatus,
                            state = ConnectionState.NETWORK_ERROR,
                            detail = "Connection timed out after 15 seconds. Tap Retry."
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
                        detail = "Error: ${e.localizedMessage ?: e.javaClass.simpleName}"
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

        btnInspectCatalog.setOnClickListener {
            showDiscoveredModelCatalogDialog(provider, tvStatus)
        }

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

    private fun showDiscoveredModelCatalogDialog(provider: ProviderType, tvStatus: TextView) {
        lifecycleScope.launch {
            val cachedJson = prefs.getCachedModelsJson(provider)
            if (cachedJson.isNullOrBlank()) {
                Toast.makeText(
                    this@ProviderSettingsActivity,
                    "Connect ${provider.displayName} first to load its live model catalog.",
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            val arr = runCatching { JSONArray(cachedJson) }.getOrNull() ?: return@launch
            if (arr.length() == 0) return@launch

            val labels = Array(arr.length()) { i ->
                val obj = arr.getJSONObject(i)
                val id = obj.optString("id")
                val speed = obj.optString("speedTier", "Fast")
                val live = if (obj.optBoolean("supportsLiveAudio")) "Live Audio ✓" else "Text/Vision"
                val vision = if (obj.optBoolean("supportsVision")) "Vision ✓" else ""
                "$id\n[$speed • $live • $vision]"
            }

            AlertDialog.Builder(this@ProviderSettingsActivity)
                .setTitle("${provider.displayName} — Discovered Models")
                .setItems(labels) { _, which ->
                    val chosenObj = arr.getJSONObject(which)
                    val chosenId = chosenObj.optString("id")
                    val supportsLive = chosenObj.optBoolean("supportsLiveAudio", false)
                    lifecycleScope.launch {
                        prefs.setAutoModelSelectionEnabled(false)
                        binding.cbAutoModelSelection.isChecked = false
                        prefs.setVerifiedModel(provider, chosenId)
                        if (supportsLive) {
                            prefs.setLiveVoiceModel(provider, chosenId)
                        }
                        prefs.setPreferredProvider(provider)
                        val voiceModel = prefs.getLiveVoiceModel(provider) ?: chosenId
                        tvStatus.text = "Selected: $chosenId | Voice Call: $voiceModel"
                    }
                }
                .setNegativeButton("Close", null)
                .show()
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
