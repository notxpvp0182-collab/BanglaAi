package com.personal.bengaliagent.voice

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

class VoiceManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onTranscriptReady: (text: String) -> Unit,
    private val onStatusUpdate: (statusEn: String) -> Unit,
    private val onListeningStateChanged: (isListening: Boolean) -> Unit,
    private val onSpeakingStateChanged: ((isSpeaking: Boolean) -> Unit)? = null,
    private val onAudioRmsChanged: ((rmsNormalized: Float) -> Unit)? = null
) : TextToSpeech.OnInitListener {

    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var isTtsSpeaking = false
    private var speechRecognizer: SpeechRecognizer? = null
    private var continuousSessionActive = false
    private var currentLanguageTag = "bn-BD"
    private var lastRecognizedText = ""
    private var lastRecognizedTimeMs = 0L
    private var pcmRecordJob: Job? = null
    private var restartListenJob: Job? = null

    init {
        tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val bengaliLocale = Locale("bn", "BD")
            val result = tts?.setLanguage(bengaliLocale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.language = Locale.US
            }
            tts?.setSpeechRate(0.96f)
            tts?.setPitch(1.02f)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    isTtsSpeaking = true
                    scope.launch(Dispatchers.Main) {
                        onSpeakingStateChanged?.invoke(true)
                    }
                }

                override fun onDone(utteranceId: String?) {
                    isTtsSpeaking = false
                    abandonAudioFocus()
                    scope.launch(Dispatchers.Main) {
                        onSpeakingStateChanged?.invoke(false)
                        if (continuousSessionActive) {
                            scheduleListeningRestart(280L)
                        }
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    isTtsSpeaking = false
                    abandonAudioFocus()
                    scope.launch(Dispatchers.Main) {
                        onSpeakingStateChanged?.invoke(false)
                        if (continuousSessionActive) {
                            scheduleListeningRestart(280L)
                        }
                    }
                }
            })
            isTtsReady = true
        } else {
            isTtsReady = false
        }
    }

    fun isMicUnmuted(): Boolean = continuousSessionActive
    fun isCurrentlySpeaking(): Boolean = isTtsSpeaking

    fun getTtsStatusDescription(): String {
        val engine = tts?.defaultEngine ?: "System Default"
        val activeVoice = tts?.voice?.name ?: "Default Voice"
        return "TTS Engine: $engine | Active Voice: $activeVoice"
    }

    fun getAvailableVoicesForBengaliAndEnglish(): List<String> {
        if (!isTtsReady) return emptyList()
        val voices: Set<Voice> = tts?.voices ?: return emptyList()
        return voices
            .filter {
                val lang = it.locale.language.lowercase()
                lang == "bn" || lang == "en"
            }
            .map { "${it.name} (${it.locale.displayName})" }
            .sorted()
    }

    fun selectVoiceByName(voiceLabel: String): Boolean {
        val rawName = voiceLabel.substringBefore(" (").trim()
        val match = tts?.voices?.firstOrNull { it.name == rawName } ?: return false
        tts?.voice = match
        return true
    }

    fun speak(text: String) {
        if (!isTtsReady || text.isBlank()) return
        try {
            speechRecognizer?.cancel()
        } catch (_: Exception) {
        }
        requestTransientAudioFocus()
        isTtsSpeaking = true
        onSpeakingStateChanged?.invoke(true)
        // Strip markdown asterisks/hashes so TTS sounds natural
        val naturalSpeechText = text
            .replace(Regex("[*#_`~]"), "")
            .replace(Regex("https?://\\S+"), "")
            .trim()
        tts?.speak(
            naturalSpeechText,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "agent_utt_${System.currentTimeMillis()}"
        )
    }

    fun stopSpeaking() {
        if (isTtsSpeaking) {
            isTtsSpeaking = false
            onSpeakingStateChanged?.invoke(false)
        }
        tts?.stop()
        abandonAudioFocus()
    }

    fun setLanguageTag(tag: String) {
        currentLanguageTag = tag
    }

    fun setContinuousMode(enabled: Boolean) {
        continuousSessionActive = enabled
    }

    fun startListening() {
        stopSpeaking()
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onStatusUpdate("System SpeechRecognizer is unavailable on this device.")
            continuousSessionActive = false
            onListeningStateChanged(false)
            return
        }

        continuousSessionActive = true
        onListeningStateChanged(true)
        startRecognizerInternal()
    }

    private fun startRecognizerInternal() {
        if (!continuousSessionActive || isTtsSpeaking) return
        try {
            if (speechRecognizer == null) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(recognitionListener)
                }
            }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLanguageTag)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            speechRecognizer?.startListening(intent)
        } catch (_: Exception) {
        }
    }

    private fun scheduleListeningRestart(delayMs: Long = 350L) {
        restartListenJob?.cancel()
        restartListenJob = scope.launch(Dispatchers.Main) {
            delay(delayMs)
            if (continuousSessionActive && !isTtsSpeaking) {
                startRecognizerInternal()
            }
        }
    }

    fun stopListeningImmediately() {
        continuousSessionActive = false
        restartListenJob?.cancel()
        restartListenJob = null
        pcmRecordJob?.cancel()
        pcmRecordJob = null
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
        } catch (_: Exception) {
        }
        onAudioRmsChanged?.invoke(0f)
        onListeningStateChanged(false)
    }

    private fun requestTransientAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .build()
            audioFocusRequest = req
            audioManager.requestAudioFocus(req)
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        }
    }

    @SuppressLint("MissingPermission")
    fun captureShortPcmSample(
        durationMs: Int = 2500,
        onSamplesCaptured: (ShortArray?) -> Unit
    ) {
        stopListeningImmediately()
        val sampleRate = 16000
        val totalSamples = (sampleRate * durationMs) / 1000
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(2048)

        pcmRecordJob = scope.launch(Dispatchers.IO) {
            var recorder: AudioRecord? = null
            try {
                recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf
                )
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    scope.launch(Dispatchers.Main) { onSamplesCaptured(null) }
                    return@launch
                }
                val captured = ShortArray(totalSamples)
                var readOffset = 0
                recorder.startRecording()
                while (readOffset < totalSamples) {
                    val toRead = (totalSamples - readOffset).coerceAtMost(1024)
                    val read = recorder.read(captured, readOffset, toRead)
                    if (read <= 0) break
                    readOffset += read
                }
                recorder.stop()
                scope.launch(Dispatchers.Main) { onSamplesCaptured(captured) }
            } catch (e: Exception) {
                scope.launch(Dispatchers.Main) { onSamplesCaptured(null) }
            } finally {
                try {
                    recorder?.release()
                } catch (_: Exception) {
                }
            }
        }
    }

    fun releaseAll() {
        stopListeningImmediately()
        stopSpeaking()
        try {
            speechRecognizer?.destroy()
            speechRecognizer = null
            tts?.shutdown()
            tts = null
        } catch (_: Exception) {
        }
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {
            val normalized = ((rmsdB + 2f) / 12f).coerceIn(0.05f, 1.0f)
            onAudioRmsChanged?.invoke(normalized)
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            onAudioRmsChanged?.invoke(0.1f)
        }

        override fun onError(error: Int) {
            onAudioRmsChanged?.invoke(0f)
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                continuousSessionActive = false
                onListeningStateChanged(false)
                onStatusUpdate("Microphone permission is required.")
                return
            }
            if (continuousSessionActive) {
                scheduleListeningRestart(400L)
            } else {
                onListeningStateChanged(false)
            }
        }

        override fun onResults(results: Bundle?) {
            onAudioRmsChanged?.invoke(0f)
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull()?.trim().orEmpty()
            val now = System.currentTimeMillis()
            // Prevent duplicate identical recognition triggers within 1.5s
            if (text.isNotEmpty() && (text != lastRecognizedText || now - lastRecognizedTimeMs > 1500L)) {
                lastRecognizedText = text
                lastRecognizedTimeMs = now
                onTranscriptReady(text)
            }
            if (continuousSessionActive && !isTtsSpeaking) {
                scheduleListeningRestart(350L)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
