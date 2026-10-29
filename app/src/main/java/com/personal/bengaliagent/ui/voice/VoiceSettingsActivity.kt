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
            Toast.makeText(this, "মাইক্রোফোন অনুমতি প্রয়োজন", Toast.LENGTH_SHORT).show()
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

            // Wait briefly for TTS engine initialization
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
            voiceManager.speak("নমস্কার, আমি আপনার ব্যক্তিগত বাংলা এআই সহকারী।")
        }
    }

    private fun setupCloudSttTest() {
        binding.btnTestCloudStt.setOnClickListener {
            if (!ensureMicPermission()) return@setOnClickListener
            val groqKey = keyStore.getApiKey(ProviderType.GROQ).orEmpty()
            if (groqKey.isBlank()) {
                binding.tvCloudSttResult.text =
                    "প্রথমে 'API ও সেটিংস' থেকে Groq API Key যোগ করুন।"
                return@setOnClickListener
            }
            binding.tvCloudSttResult.text = "৩ সেকেন্ডের জন্য বাংলায় কিছু বলুন..."
            voiceManager.captureShortPcmSample(durationMs = 3000) { samples ->
                if (samples == null) {
                    binding.tvCloudSttResult.text = "অডিও ক্যাপচার ব্যর্থ হয়েছে।"
                    return@captureShortPcmSample
                }
                lifecycleScope.launch {
                    binding.tvCloudSttResult.text = "Groq Whisper-এ ট্রান্সক্রিপশন চলছে..."
                    val res = cloudSttClient.transcribePcmWithGroqWhisper(groqKey, samples)
                    samples.fill(0)
                    binding.tvCloudSttResult.text = if (res.success) {
                        "শনাক্তকৃত টেক্সট: ${res.text}"
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
                "নিবন্ধন চলছে... স্বাভাবিক কণ্ঠে ২.৫ সেকেন্ড কথা বলুন (যেমন: 'আমি আমার কণ্ঠ নিবন্ধন করছি')..."
            voiceManager.captureShortPcmSample(durationMs = 2500) { samples ->
                if (samples == null) {
                    binding.tvSpeakerEnrollStatus.text = "মাইক্রোফোন থেকে অডিও পাওয়া যায়নি।"
                    return@captureShortPcmSample
                }
                val embedding = SpeakerVerifier.extractEmbedding(samples, zeroOutBuffer = true)
                if (embedding == null) {
                    binding.tvSpeakerEnrollStatus.text =
                        "পর্যাপ্ত কণ্ঠস্বর পাওয়া যায়নি। একটু স্পষ্টভাবে আবার চেষ্টা করুন।"
                } else {
                    lifecycleScope.launch {
                        prefs.setEnrolledSpeakerEmbedding(
                            SpeakerVerifier.serializeEmbedding(embedding)
                        )
                        prefs.setSpeakerDetectionEnabled(true)
                        binding.cbEnableSpeakerMatch.isChecked = true
                        refreshEnrollmentStatus("কণ্ঠ সফলভাবে নিবন্ধিত হয়েছে (মূল অডিও মুছে ফেলা হয়েছে)।")
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
                    binding.tvSpeakerEnrollStatus.text = "প্রথমে 'Enroll My Voice' বাটনে চাপুন।"
                    return@launch
                }
                binding.tvSpeakerEnrollStatus.text = "পরীক্ষা চলছে... ২.৫ সেকেন্ড কথা বলুন..."
                voiceManager.captureShortPcmSample(durationMs = 2500) { samples ->
                    if (samples == null) {
                        binding.tvSpeakerEnrollStatus.text = "অডিও ক্যাপচার ব্যর্থ হয়েছে।"
                        return@captureShortPcmSample
                    }
                    val candidateVec = SpeakerVerifier.extractEmbedding(samples, zeroOutBuffer = true)
                    val outcome = SpeakerVerifier.verifyAgainstEnrolled(enrolledVec, candidateVec)
                    val scorePct = (outcome.similarityScore * 100).toInt()
                    when (outcome.status) {
                        SpeakerVerifier.MatchStatus.MATCHED -> {
                            binding.tvSpeakerEnrollStatus.text =
                                "কণ্ঠ মিলেছে! (সাদৃশ্য: $scorePct%)"
                        }
                        SpeakerVerifier.MatchStatus.UNCERTAIN,
                        SpeakerVerifier.MatchStatus.DIFFERENT_SPEAKER -> {
                            val warn = outcome.warningMessageBn.orEmpty()
                            binding.tvSpeakerEnrollStatus.text = "$warn (সাদৃশ্য: $scorePct%)"
                            voiceManager.speak("আমি তোমার কণ্ঠ নিশ্চিত করতে পারছি না—দয়া করে নিজে নিশ্চিত করো।")
                        }
                        SpeakerVerifier.MatchStatus.NOT_ENROLLED -> {
                            binding.tvSpeakerEnrollStatus.text = "কোনো কণ্ঠ নিবন্ধিত নেই।"
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
                refreshEnrollmentStatus("সংরক্ষিত কণ্ঠ প্রোফাইল সম্পূর্ণ মুছে ফেলা হয়েছে।")
            }
        }
    }

    private suspend fun refreshEnrollmentStatus(customPrefix: String? = null) {
        val enrolled = prefs.getEnrolledSpeakerEmbedding()
        val hasEnrollment = !enrolled.isNullOrBlank()
        val baseStatus = if (hasEnrollment) {
            "কণ্ঠের অবস্থা: নিবন্ধিত (On-device 16-D প্রোফাইল সক্রিয়)"
        } else {
            "কণ্ঠের অবস্থা: নিবন্ধিত নয়"
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
