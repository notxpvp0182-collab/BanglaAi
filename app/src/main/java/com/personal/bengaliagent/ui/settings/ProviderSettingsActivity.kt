package com.personal.bengaliagent.ui.settings

import android.os.Bundle
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.personal.bengaliagent.AgentApp
import com.personal.bengaliagent.R
import com.personal.bengaliagent.databinding.ActivityProviderSettingsBinding
import com.personal.bengaliagent.domain.model.ProviderType
import com.personal.bengaliagent.network.LlmProviderClient
import kotlinx.coroutines.launch

class ProviderSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProviderSettingsBinding
    private val client = LlmProviderClient()
    private val keyStore by lazy { AgentApp.instance.secureKeyStore }
    private val prefs by lazy { AgentApp.instance.appPreferences }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProviderSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRoutingControls()
        setupProviderCard(
            provider = ProviderType.GEMINI,
            etKey = binding.etGeminiKey,
            cbShow = binding.cbShowGeminiKey,
            btnDelete = binding.btnDeleteGeminiKey,
            spinnerModels = binding.spinnerGeminiModels,
            btnFetch = binding.btnFetchGeminiModels,
            btnTest = binding.btnTestGemini,
            tvStatus = binding.tvGeminiStatus
        )
        setupProviderCard(
            provider = ProviderType.GROQ,
            etKey = binding.etGroqKey,
            cbShow = binding.cbShowGroqKey,
            btnDelete = binding.btnDeleteGroqKey,
            spinnerModels = binding.spinnerGroqModels,
            btnFetch = binding.btnFetchGroqModels,
            btnTest = binding.btnTestGroq,
            tvStatus = binding.tvGroqStatus
        )
        setupProviderCard(
            provider = ProviderType.OPENROUTER,
            etKey = binding.etOpenRouterKey,
            cbShow = binding.cbShowOpenRouterKey,
            btnDelete = binding.btnDeleteOpenRouterKey,
            spinnerModels = binding.spinnerOpenRouterModels,
            btnFetch = binding.btnFetchOpenRouterModels,
            btnTest = binding.btnTestOpenRouter,
            tvStatus = binding.tvOpenRouterStatus
        )

        binding.btnCloseSettings.setOnClickListener { finish() }
    }

    private fun setupRoutingControls() {
        val providers = ProviderType.entries.toList()
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            providers.map { it.displayName }
        )
        binding.spinnerPreferredProvider.adapter = adapter

        lifecycleScope.launch {
            binding.cbCloudConsent.isChecked = prefs.isCloudSharingConsented()
            val preferred = prefs.getPreferredProvider()
            binding.spinnerPreferredProvider.setSelection(providers.indexOf(preferred).coerceAtLeast(0))
            val failover = prefs.getFailoverOrder().joinToString(",") { it.id }
            binding.etFailoverOrder.setText(failover)
        }

        binding.cbCloudConsent.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch { prefs.setCloudSharingConsented(isChecked) }
        }

        binding.btnSaveRouting.setOnClickListener {
            lifecycleScope.launch {
                val selectedIdx = binding.spinnerPreferredProvider.selectedItemPosition
                val preferred = providers.getOrElse(selectedIdx) { ProviderType.GEMINI }
                prefs.setPreferredProvider(preferred)

                val rawOrder = binding.etFailoverOrder.text?.toString().orEmpty()
                val parsedOrder = rawOrder.split(",")
                    .mapNotNull { id -> ProviderType.entries.firstOrNull { it.id == id.trim().lowercase() } }
                    .distinct()
                if (parsedOrder.isNotEmpty()) {
                    prefs.setFailoverOrder(parsedOrder)
                }
                Toast.makeText(
                    this@ProviderSettingsActivity,
                    "প্রোভাইডার রাউটিং সংরক্ষিত হয়েছে",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun setupProviderCard(
        provider: ProviderType,
        etKey: EditText,
        cbShow: CheckBox,
        btnDelete: View,
        spinnerModels: Spinner,
        btnFetch: View,
        btnTest: View,
        tvStatus: TextView
    ) {
        val existingKey = keyStore.getApiKey(provider)
        if (!existingKey.isNullOrBlank()) {
            etKey.setText(existingKey)
        }

        val isVerified = keyStore.isProviderVerified(provider)
        updateStatusText(
            tvStatus,
            isVerified,
            if (isVerified) "অবস্থা: সংযুক্ত ও যাচাইকৃত" else "অবস্থা: যাচাই করা হয়নি"
        )

        cbShow.setOnCheckedChangeListener { _, isChecked ->
            etKey.transformationMethod = if (isChecked) {
                HideReturnsTransformationMethod.getInstance()
            } else {
                PasswordTransformationMethod.getInstance()
            }
            etKey.setSelection(etKey.text?.length ?: 0)
        }

        lifecycleScope.launch {
            val savedModel = prefs.getSelectedModel(provider)
            val initialList = (listOf(savedModel) + provider.curatedFallbackModels).distinct()
            populateModelSpinner(provider, spinnerModels, initialList, savedModel)
        }

        btnDelete.setOnClickListener {
            keyStore.deleteApiKey(provider)
            etKey.setText("")
            updateStatusText(tvStatus, false, "অবস্থা: API Key মুছে ফেলা হয়েছে।")
        }

        btnFetch.setOnClickListener {
            val rawKey = etKey.text?.toString()?.trim().orEmpty()
            tvStatus.text = "মডেল তালিকা খোঁজা হচ্ছে..."
            lifecycleScope.launch {
                val result = client.fetchAvailableModels(provider, rawKey)
                populateModelSpinner(
                    provider = provider,
                    spinner = spinnerModels,
                    models = result.models,
                    selectedModel = result.recommendedModel
                )
                tvStatus.text = result.statusNote
            }
        }

        btnTest.setOnClickListener {
            val rawKey = etKey.text?.toString()?.trim().orEmpty()
            val selectedModel = spinnerModels.selectedItem?.toString()
                ?: provider.defaultRecommendedModel

            tvStatus.text = "সংযোগ পরীক্ষা করা হচ্ছে (${provider.displayName})..."
            lifecycleScope.launch {
                val testResult = client.testConnection(provider, rawKey, selectedModel)
                if (testResult.success) {
                    keyStore.saveApiKey(provider, rawKey)
                    keyStore.setProviderVerified(provider, true)
                    prefs.setSelectedModel(provider, selectedModel)
                    updateStatusText(tvStatus, true, testResult.userMessageBn)
                } else {
                    keyStore.setProviderVerified(provider, false)
                    updateStatusText(tvStatus, false, testResult.userMessageBn)
                }
            }
        }
    }

    private fun populateModelSpinner(
        provider: ProviderType,
        spinner: Spinner,
        models: List<String>,
        selectedModel: String
    ) {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            models
        )
        spinner.adapter = adapter
        val index = models.indexOf(selectedModel).coerceAtLeast(0)
        spinner.setSelection(index)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                val chosen = models.getOrNull(position) ?: return
                lifecycleScope.launch {
                    prefs.setSelectedModel(provider, chosen)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun updateStatusText(tv: TextView, success: Boolean, message: String) {
        tv.text = message
        val colorRes = if (success) R.color.accent_green else R.color.accent_amber
        tv.setTextColor(ContextCompat.getColor(this, colorRes))
    }
}
