package com.personal.bengaliagent.voice

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Handles Bengali (bn-BD) and English STT + TTS, push-to-talk vs explicit continuous listening,
 * immediate mute/stop resource release, and short PCM capture for speaker enrollment/verification.
 */
class VoiceManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onTranscriptReady: (text: String) -> Unit,
    private val onStatusUpdate: (statusBn: String) -> Unit,
    private val onListeningStateChanged: (isListening: Boolean) -> Unit
) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var speechRecognizer: SpeechRecognizer? = null
    private var isCurrentlyListening = false
    private var continuousModeEnabled = false
    private var currentLanguageTag = "bn-BD"
    private var pcmRecordJob: Job? = null

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
            isTtsReady = true
        } else {
            isTtsReady = false
        }
    }

    fun getTtsStatusDescription(): String {
        val engine = tts?.defaultEngine ?: "অজানা (Unknown)"
        val activeVoice = tts?.voice?.name ?: "ডিফল্ট ভয়েস"
        return "TTS ইঞ্জিন: $engine | সক্রিয় কণ্ঠ: $activeVoice"
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
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "agent_utt_${System.currentTimeMillis()}")
    }

    fun stopSpeaking() {
        tts?.stop()
    }

    fun setLanguageTag(tag: String) {
        currentLanguageTag = tag
    }

    fun setContinuousMode(enabled: Boolean) {
        continuousModeEnabled = enabled
    }

    fun startListening() {
        stopSpeaking()
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onStatusUpdate(
                "এই ফোনে সিস্টেম SpeechRecognizer নেই। 'ভয়েস সেটিংস' থেকে ক্লাউড STT ব্যবহার করতে পারেন।"
            )
            onListeningStateChanged(false)
            return
        }

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

        isCurrentlyListening = true
        onListeningStateChanged(true)
        speechRecognizer?.startListening(intent)
    }

    /**
     * Immediately stops microphone capture. Does not merely hide an icon.
     */
    fun stopListeningImmediately() {
        continuousModeEnabled = false
        isCurrentlyListening = false
        pcmRecordJob?.cancel()
        pcmRecordJob = null
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
        } catch (_: Exception) {
        }
        onListeningStateChanged(false)
    }

    /**
     * Records a short 2.5-second 16kHz mono PCM clip on-device for speaker enrollment/testing
     * or Cloud STT fallback, then invokes [onSamplesCaptured].
     */
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
        override fun onReadyForSpeech(params: Bundle?) {
            onStatusUpdate("শুনছি ($currentLanguageTag)... কথা বলুন।")
        }

        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            isCurrentlyListening = false
            onListeningStateChanged(false)
        }

        override fun onError(error: Int) {
            isCurrentlyListening = false
            onListeningStateChanged(false)
            val msgBn = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> "কোনো কথা বোঝা যায়নি। আবার চেষ্টা করুন।"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "কোনো শব্দ পাওয়া যায়নি (Timeout)।"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "মাইক্রোফোন অনুমতি নেই।"
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                    "ভয়েস রিকগনিশন নেটওয়ার্ক ত্রুটি।"
                else -> "ভয়েস রিকগনিশন ত্রুটি (কোড: $error)। বাংলা প্যাক না থাকলে ক্লাউড STT ব্যবহার করুন।"
            }
            onStatusUpdate(msgBn)
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull()?.trim().orEmpty()
            if (text.isNotEmpty()) {
                onTranscriptReady(text)
            }
            if (continuousModeEnabled) {
                startListening()
            } else {
                isCurrentlyListening = false
                onListeningStateChanged(false)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
