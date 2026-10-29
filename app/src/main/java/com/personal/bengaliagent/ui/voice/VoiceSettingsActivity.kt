package com.personal.bengaliagent.ui.voice

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.personal.bengaliagent.AgentApp
import com.personal.bengaliagent.databinding.ActivityVoiceSettingsBinding
import com.personal.bengaliagent.domain.model.ProviderType
import com.personal.bengaliagent.voice.CloudSttClient
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

        setupLanguageAndTtsControls()
        setupCloudSttTest()
        setupSpeakerVerificationControls()

        binding.btnCloseVoiceSettings.setOnClickListener { finish() }
    }

    private fun setupLanguageAndTtsControls() {
        lifecycleScope.launch {
            val lang = prefs.getVoiceLanguageTag()
            if (lang.startsWith("en")) {
                binding.rbEnglish.isChecked = true
            } else {
                binding.rbBengali.isChecked = true
            }
            binding.cbAutoSpeak.isChecked = prefs.isTtsAutoSpeakEnabled()

            delay(500)
            binding.tvTtsEngineStatus.text = voiceManager.getTtsStatusDescription()
            val voices = voiceManager.getAvailableVoicesForBengaliAndEnglish()
            if (voices.isNotEmpty()) {
                val adapter = ArrayAdapter(
                    this@VoiceSettingsActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    voices
                )
                binding.spinnerVoices.adapter = adapter
                binding.spinnerVoices.onItemSelectedListener =
                    object : AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(
                            parent: AdapterView<*>?,
                            view: View?,
                            position: Int,
                            id: Long
                        ) {
                            val selected = voices.getOrNull(position) ?: return
                            voiceManager.selectVoiceByName(selected)
                            binding.tvTtsEngineStatus.text = voiceManager.getTtsStatusDescription()
                        }

                        override fun onNothingSelected(parent: AdapterView<*>?) {}
                    }
            }
        }

        binding.rgLanguage.setOnCheckedChangeListener { _, checkedId ->
            val tag = if (checkedId == binding.rbEnglish.id) "en-US" else "bn-BD"
            lifecycleScope.launch { prefs.setVoiceLanguageTag(tag) }
        }

        binding.cbAutoSpeak.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch { prefs.setTtsAutoSpeakEnabled(isChecked) }
        }

        binding.btnTestTts.setOnClickListener {
            voiceManager.speak("Hello, I am your personal Bengali AI Agent.")
        }
    }

    private fun setupCloudSttTest() {
        binding.btnTestCloudStt.setOnClickListener {
            if (!ensureMicPermission()) return@setOnClickListener
            val groqKey = keyStore.getApiKey(ProviderType.GROQ).orEmpty()
            if (groqKey.isBlank()) {
                binding.tvCloudSttResult.text =
                    "System SpeechRecognizer is active by default. Cloud Whisper STT is optional."
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
                    binding.tvSpeakerEnrollStatus.text = "Could not capture audio from microphone."
                    return@captureShortPcmSample
                }
                val embedding = SpeakerVerifier.extractEmbedding(samples, zeroOutBuffer = true)
                if (embedding == null) {
                    binding.tvSpeakerEnrollStatus.text =
                        "Insufficient voice energy detected. Please speak clearly and try again."
                } else {
                    lifecycleScope.launch {
                        prefs.setEnrolledSpeakerEmbedding(
                            SpeakerVerifier.serializeEmbedding(embedding)
                        )
                        prefs.setSpeakerDetectionEnabled(true)
                        binding.cbEnableSpeakerMatch.isChecked = true
                        refreshEnrollmentStatus("Voice enrolled locally (raw audio zeroed and discarded).")
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
                    binding.tvSpeakerEnrollStatus.text = "Please tap 'Enroll My Voice' first."
                    return@launch
                }
                binding.tvSpeakerEnrollStatus.text = "Testing voice match... Speak for 2.5 seconds."
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
                        SpeakerVerifier.MatchStatus.UNCERTAIN,
                        SpeakerVerifier.MatchStatus.DIFFERENT_SPEAKER -> {
                            val warn = outcome.warningMessageBn.orEmpty()
                            binding.tvSpeakerEnrollStatus.text = "$warn (Similarity: $scorePct%)"
                            voiceManager.speak("Could not verify your voice. Please confirm manually.")
                        }
                        SpeakerVerifier.MatchStatus.NOT_ENROLLED -> {
                            binding.tvSpeakerEnrollStatus.text = "Voice Status: Not Enrolled"
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
