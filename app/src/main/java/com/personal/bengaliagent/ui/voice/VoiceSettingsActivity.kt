package com.personal.bengaliagent.ui.voice

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.personal.bengaliagent.AgentApp
import com.personal.bengaliagent.databinding.ActivityVoiceSettingsBinding
import com.personal.bengaliagent.domain.model.FemaleVoicePersona
import com.personal.bengaliagent.domain.model.PersonalityMode
import com.personal.bengaliagent.domain.model.ProviderType
import com.personal.bengaliagent.voice.CloudSttClient
import com.personal.bengaliagent.voice.GeminiTtsClient
import com.personal.bengaliagent.voice.SpeakerVerifier
import com.personal.bengaliagent.voice.VoiceManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class VoiceSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVoiceSettingsBinding
    private lateinit var voiceManager: VoiceManager
    private val prefs by lazy { AgentApp.instance.appPreferences }
    private val keyStore by lazy { AgentApp.instance.secureKeyStore }
    private val cloudSttClient = CloudSttClient()
    private val geminiTtsClient = GeminiTtsClient()

    private val micPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, "Microphone permission is required.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVoiceSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        voiceManager = VoiceManager(
            context = this,
            scope = lifecycleScope,
            onTranscriptReady = {},
            onStatusUpdate = {},
            onListeningStateChanged = {}
        )

        setupFemaleVoiceLibraryAndPersonality()
        setupCloudSttTest()
        setupSpeakerVerificationControls()

        binding.btnCloseVoiceSettings.setOnClickListener { finish() }
    }

    private fun setupFemaleVoiceLibraryAndPersonality() {
        val personas = FemaleVoicePersona.entries
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            personas.map { "${it.displayName} — ${it.styleDescription}" }
        )
        binding.spinnerVoices.adapter = adapter

        lifecycleScope.launch {
            val lang = prefs.getVoiceLanguageTag()
            if (lang.startsWith("en")) {
                binding.rbEnglish.isChecked = true
            } else {
                binding.rbBengali.isChecked = true
            }
            binding.cbAutoSpeak.isChecked = prefs.isTtsAutoSpeakEnabled()

            val currentVoice = prefs.getPreferredFemaleVoice()
            val currentMode = prefs.getPersonalityMode()
            val idx = personas.indexOf(currentVoice).coerceAtLeast(0)
            binding.spinnerVoices.setSelection(idx)

            delay(400)
            binding.tvTtsEngineStatus.text =
                "Selected Voice: ${currentVoice.voiceId} | Personality: ${currentMode.displayName}"
        }

        binding.spinnerVoices.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                val chosen = personas.getOrElse(position) { FemaleVoicePersona.AOEDE }
                lifecycleScope.launch {
                    prefs.setPreferredFemaleVoice(chosen)
                    val mode = prefs.getPersonalityMode()
                    binding.tvTtsEngineStatus.text =
                        "Selected Voice: ${chosen.voiceId} | Personality: ${mode.displayName}"
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.rgLanguage.setOnCheckedChangeListener { _, checkedId ->
            val tag = if (checkedId == binding.rbEnglish.id) "en-US" else "bn-BD"
            lifecycleScope.launch { prefs.setVoiceLanguageTag(tag) }
        }

        binding.cbAutoSpeak.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch { prefs.setTtsAutoSpeakEnabled(isChecked) }
        }

        binding.btnTestTts.text = "Preview Female Voice & Choose Personality"
        binding.btnTestTts.setOnClickListener {
            lifecycleScope.launch {
                val chosenVoice = prefs.getPreferredFemaleVoice()
                val sampleText = "হ্যালো জান, আমি সুমু। আমার ${chosenVoice.voiceId} কণ্ঠটি তোমার কেমন লাগছে?"
                val geminiKey = keyStore.getApiKey(ProviderType.GEMINI).orEmpty()
                val playedCloud = if (geminiKey.isNotBlank()) {
                    geminiTtsClient.synthesizeAndPlayIfSupported(geminiKey, sampleText, chosenVoice)
                } else {
                    false
                }
                if (!playedCloud) {
                    voiceManager.speak(sampleText)
                }
                showPersonalityModePickerDialog()
            }
        }
    }

    private fun showPersonalityModePickerDialog() {
        val modes = PersonalityMode.entries
        val labels = modes.map { it.displayName }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Select Sumu AI Personality Mode")
            .setItems(labels) { _, which ->
                val chosenMode = modes[which]
                lifecycleScope.launch {
                    prefs.setPersonalityMode(chosenMode)
                    val voice = prefs.getPreferredFemaleVoice()
                    binding.tvTtsEngineStatus.text =
                        "Selected Voice: ${voice.voiceId} | Personality: ${chosenMode.displayName}"
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun setupCloudSttTest() {
        binding.btnTestCloudStt.setOnClickListener {
            if (!ensureMicPermission()) return@setOnClickListener
            val groqKey = keyStore.getApiKey(ProviderType.GROQ).orEmpty()
            if (groqKey.isBlank()) {
                binding.tvCloudSttResult.text =
                    "System SpeechRecognizer is active. Optional Groq Whisper key not set."
                return@setOnClickListener
            }
            binding.tvCloudSttResult.text = "Speak now for 3 seconds..."
            voiceManager.captureShortPcmSample(durationMs = 3000) { samples ->
                if (samples == null) {
                    binding.tvCloudSttResult.text = "Audio capture failed."
                    return@captureShortPcmSample
                }
                lifecycleScope.launch {
                    binding.tvCloudSttResult.text = "Transcribing audio..."
                    val res = cloudSttClient.transcribePcmWithGroqWhisper(groqKey, samples)
                    samples.fill(0)
                    binding.tvCloudSttResult.text = if (res.success) {
                        "Transcribed: ${res.text}"
                    } else {
                        res.errorMessageBn
                    }
                }
            }
        }
    }

    private fun setupSpeakerVerificationControls() {
        lifecycleScope.launch {
            binding.cbEnableSpeakerMatch.isChecked = prefs.isSpeakerDetectionEnabled()
            refreshEnrollmentStatus()
        }

        binding.cbEnableSpeakerMatch.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch { prefs.setSpeakerDetectionEnabled(isChecked) }
        }

        binding.btnEnrollVoice.setOnClickListener {
            if (!ensureMicPermission()) return@setOnClickListener
            binding.tvSpeakerEnrollStatus.text =
                "Recording 2.5 seconds of speech... Please speak naturally."
            voiceManager.captureShortPcmSample(durationMs = 2500) { samples ->
                if (samples == null) {
                    binding.tvSpeakerEnrollStatus.text = "Could not capture audio."
                    return@captureShortPcmSample
                }
                val embedding = SpeakerVerifier.extractEmbedding(samples, zeroOutBuffer = true)
                if (embedding == null) {
                    binding.tvSpeakerEnrollStatus.text =
                        "Insufficient voice energy. Speak clearly and try again."
                } else {
                    lifecycleScope.launch {
                        prefs.setEnrolledSpeakerEmbedding(
                            SpeakerVerifier.serializeEmbedding(embedding)
                        )
                        prefs.setSpeakerDetectionEnabled(true)
                        binding.cbEnableSpeakerMatch.isChecked = true
                        refreshEnrollmentStatus("Voice enrolled locally.")
                    }
                }
            }
        }

        binding.btnTestVoiceMatch.setOnClickListener {
            if (!ensureMicPermission()) return@setOnClickListener
            lifecycleScope.launch {
                val enrolledStr = prefs.getEnrolledSpeakerEmbedding()
                val enrolledVec = SpeakerVerifier.deserializeEmbedding(enrolledStr)
                if (enrolledVec == null) {
                    binding.tvSpeakerEnrollStatus.text = "Tap 'Enroll My Voice' first."
                    return@launch
                }
                binding.tvSpeakerEnrollStatus.text = "Testing voice match... Speak for 2.5s."
                voiceManager.captureShortPcmSample(durationMs = 2500) { samples ->
                    if (samples == null) {
                        binding.tvSpeakerEnrollStatus.text = "Audio capture failed."
                        return@captureShortPcmSample
                    }
                    val candidateVec = SpeakerVerifier.extractEmbedding(samples, zeroOutBuffer = true)
                    val outcome = SpeakerVerifier.verifyAgainstEnrolled(enrolledVec, candidateVec)
                    val scorePct = (outcome.similarityScore * 100).toInt()
                    when (outcome.status) {
                        SpeakerVerifier.MatchStatus.MATCHED -> {
                            binding.tvSpeakerEnrollStatus.text =
                                "Voice Matched! (Similarity: $scorePct%)"
                        }
                        else -> {
                            binding.tvSpeakerEnrollStatus.text =
                                "${outcome.warningMessageBn.orEmpty()} ($scorePct%)"
                        }
                    }
                }
            }
        }

        binding.btnForgetVoice.setOnClickListener {
            lifecycleScope.launch {
                prefs.setEnrolledSpeakerEmbedding(null)
                prefs.setSpeakerDetectionEnabled(false)
                binding.cbEnableSpeakerMatch.isChecked = false
                refreshEnrollmentStatus("Stored voice profile deleted.")
            }
        }
    }

    private suspend fun refreshEnrollmentStatus(customPrefix: String? = null) {
        val enrolled = prefs.getEnrolledSpeakerEmbedding()
        val hasEnrollment = !enrolled.isNullOrBlank()
        val baseStatus = if (hasEnrollment) {
            "Voice Status: Enrolled (On-device 16-D profile active)"
        } else {
            "Voice Status: Not Enrolled"
        }
        binding.tvSpeakerEnrollStatus.text = if (customPrefix != null) {
            "$customPrefix\n$baseStatus"
        } else {
            baseStatus
        }
    }

    private fun ensureMicPermission(): Boolean {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
        return granted
    }

    override fun onDestroy() {
        voiceManager.releaseAll()
        super.onDestroy()
    }
}
