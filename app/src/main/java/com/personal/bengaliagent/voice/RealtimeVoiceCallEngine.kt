package com.personal.bengaliagent.voice

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.personal.bengaliagent.AgentApp
import com.personal.bengaliagent.agent.AgentPlanner
import com.personal.bengaliagent.agent.AndroidActionManager
import com.personal.bengaliagent.domain.model.AgentState
import com.personal.bengaliagent.domain.model.ChatMessage
import com.personal.bengaliagent.domain.model.ProviderType
import com.personal.bengaliagent.network.AiClientRouter
import com.personal.bengaliagent.screen.ScreenCaptureManager
import com.personal.bengaliagent.service.AgentAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Rock-Solid Multi-Agent Real-Time Voice Call Engine:
 * - Connects immediately (< 600ms) using your verified providers (Gemini, Groq, OpenRouter, OpenAI, Grok).
 * - NEVER kicks you out of the call or gets stuck on "Reconnecting...".
 * - Supports Multi-AI Agent Conference Calls: Multiple connected AI agents work in parallel and speak
 *   sequentially with distinct vocal pitch/rate profiles.
 * - Executes Android actions (WhatsApp, YouTube, Flashlight, Settings, Screen Control) mid-call.
 */
class RealtimeVoiceCallEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val router: AiClientRouter,
    private val onCallStateChanged: (state: AgentState, statusDetail: String) -> Unit,
    private val onAudioLevelUpdate: (levelNormalized: Float) -> Unit
) : TextToSpeech.OnInitListener {

    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var speechRecognizer: SpeechRecognizer? = null

    private val isCallActive = AtomicBoolean(false)
    private val isMicMuted = AtomicBoolean(false)
    private val isAiSpeaking = AtomicBoolean(false)
    private var isSpeakerphoneEnabled = true

    private var listenRestartJob: Job? = null
    private var activeTurnJob: Job? = null
    private var speakingWaveJob: Job? = null

    private val recentCallHistory = ArrayList<ChatMessage>()
    private val speechQueue = ConcurrentLinkedQueue<Pair<ProviderType, String>>()

    init {
        tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val bnLocale = Locale("bn", "BD")
            val res = tts?.setLanguage(bnLocale)
            if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.language = Locale.US
            }
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    isAiSpeaking.set(true)
                }

                override fun onDone(utteranceId: String?) {
                    scope.launch(Dispatchers.Main) {
                        playNextQueuedAgentVoiceOrResumeListening()
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    scope.launch(Dispatchers.Main) {
                        playNextQueuedAgentVoiceOrResumeListening()
                    }
                }
            })
            isTtsReady = true
        }
    }

    fun startCall() {
        if (isCallActive.get()) return
        val connectedProviders = router.getConnectedProviders()
        if (connectedProviders.isEmpty()) {
            onCallStateChanged(
                AgentState.ERROR,
                "No AI provider connected. Tap the top-right Settings icon to connect Gemini, Groq, OpenRouter, or OpenAI."
            )
            return
        }

        isCallActive.set(true)
        isMicMuted.set(false)
        speechQueue.clear()

        val activeNames = connectedProviders.joinToString(" + ") { it.displayName }
        onCallStateChanged(AgentState.CALLING, "Connecting to $activeNames...")

        scope.launch(Dispatchers.Main) {
            configureCallAudioRouting()
            delay(450L)
            if (!isCallActive.get()) return@launch

            onCallStateChanged(AgentState.CONNECTED, "Live • $activeNames")
            delay(250L)
            if (!isCallActive.get()) return@launch

            onCallStateChanged(AgentState.LISTENING, "Listening ($activeNames)...")
            startContinuousListening()
        }
    }

    private fun configureCallAudioRouting() {
        try {
            requestAudioFocus()
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = isSpeakerphoneEnabled
        } catch (_: Exception) {
        }
    }

    private fun startContinuousListening() {
        if (!isCallActive.get() || isMicMuted.get() || isAiSpeaking.get()) return
        try {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                onCallStateChanged(
                    AgentState.LISTENING,
                    "Voice recognition service unavailable on device."
                )
                return
            }
            if (speechRecognizer == null) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context.applicationContext).apply {
                    setRecognitionListener(callRecognitionListener)
                }
            }
            val langTag = runCatching {
                kotlinx.coroutines.runBlocking {
                    AgentApp.instance.appPreferences.getVoiceLanguageTag()
                }
            }.getOrDefault("bn-BD")

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, langTag)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            speechRecognizer?.startListening(intent)
        } catch (_: Exception) {
            scheduleListenRestart(500L)
        }
    }

    private fun scheduleListenRestart(delayMs: Long = 300L) {
        listenRestartJob?.cancel()
        listenRestartJob = scope.launch(Dispatchers.Main) {
            delay(delayMs)
            if (isCallActive.get() && !isMicMuted.get() && !isAiSpeaking.get()) {
                startContinuousListening()
            }
        }
    }

    private fun handleUserSpokenTurn(spokenText: String) {
        if (!isCallActive.get() || spokenText.isBlank()) return

        // Stop any ongoing AI speech if user interrupted
        stopAiSpeechOnly()

        activeTurnJob?.cancel()
        activeTurnJob = scope.launch(Dispatchers.Main) {
            onCallStateChanged(AgentState.THINKING, "\"${spokenText.take(42)}\"")

            // 1. Instant Phone Action Execution (WhatsApp, YouTube, Flashlight, Settings, Scroll, Back)
            val directAction = AndroidActionManager.tryExecuteDirectCommand(context, spokenText)
            if (directAction.matchedIntentCommand) {
                onCallStateChanged(AgentState.ACTING, directAction.responseMessage)
                enqueueAndSpeakReplies(
                    listOf(ProviderType.GEMINI to directAction.responseMessage)
                )
                return@launch
            }

            // 2. Build Context (Screen Share frame + Accessibility UI + Recent Call Context)
            val prefs = AgentApp.instance.appPreferences
            val multiAgentMode = prefs.isAutoModelSelectionEnabled()
            val screenObs = AgentAccessibilityService.instance?.observeCurrentScreen()
            val screenshotBase64 = if (ScreenCaptureManager.isSharingActive()) {
                ScreenCaptureManager.captureFreshFrameWithRetry()
            } else {
                null
            }

            val contextMsgs = mutableListOf<ChatMessage>()
            if (ScreenCaptureManager.isSharingActive() || screenObs != null) {
                val screenPrompt = AgentPlanner.buildPlannerSystemPrompt(
                    screenObservation = screenObs,
                    codingEnabled = prefs.isAiCodingCapabilityEnabled(),
                    videoEditingEnabled = prefs.isAiVideoEditCapabilityEnabled()
                )
                contextMsgs.add(
                    ChatMessage(
                        id = 0L,
                        role = ChatMessage.Role.SYSTEM,
                        text = if (AgentPlanner.isAutomationIntent(spokenText)) {
                            screenPrompt
                        } else {
                            "Current visible screen UI:\n${screenObs?.toCompactPromptString().orEmpty()}"
                        }
                    )
                )
            }

            val userMsg = ChatMessage(
                id = System.currentTimeMillis(),
                role = ChatMessage.Role.USER,
                text = spokenText
            )
            recentCallHistory.add(userMsg)
            if (recentCallHistory.size > 8) {
                recentCallHistory.removeAt(0)
            }
            contextMsgs.addAll(recentCallHistory)

            // 3. Query Single or Multiple Connected AI Agents in Parallel!
            val agentReplies = router.queryActiveCallAgents(
                messages = contextMsgs,
                base64JpegImage = screenshotBase64,
                multiAgentMode = multiAgentMode
            )

            if (!isCallActive.get()) return@launch

            if (agentReplies.isEmpty()) {
                // Keep call alive! Never kick user out on a single failed turn.
                enqueueAndSpeakReplies(
                    listOf(ProviderType.GEMINI to "Connection was slow. Please say that again.")
                )
                return@launch
            }

            // Check if the primary agent returned a UI automation JSON plan
            val firstReply = agentReplies.first()
            if (AgentPlanner.isAutomationIntent(spokenText) && firstReply.spokenText.contains("\"actions\"")) {
                val plan = AgentPlanner.parseAndValidatePlan(firstReply.spokenText)
                if (plan.actions.isNotEmpty()) {
                    onCallStateChanged(AgentState.ACTING, "Executing screen action...")
                    for (act in plan.actions.take(4)) {
                        AndroidActionManager.executeStructuredToolCall(
                            context,
                            act.tool.name.lowercase(),
                            act.targetLabel.ifBlank { act.packageNameOrAppName }
                        )
                        delay(500L)
                    }
                    val spokenConfirm = plan.directReplyBn.ifBlank {
                        plan.thoughtBn.ifBlank { "Done." }
                    }
                    enqueueAndSpeakReplies(listOf(firstReply.provider to spokenConfirm))
                    return@launch
                }
            }

            // Save first reply in rolling call memory
            recentCallHistory.add(
                ChatMessage(
                    id = System.currentTimeMillis(),
                    role = ChatMessage.Role.ASSISTANT,
                    text = firstReply.spokenText,
                    providerUsed = firstReply.provider.displayName
                )
            )

            // Queue all responding AI agents to speak in the call!
            val spokenPairs = agentReplies.map { reply ->
                val prefix = if (agentReplies.size > 1) "${reply.provider.displayName}: " else ""
                reply.provider to (prefix + reply.spokenText)
            }
            enqueueAndSpeakReplies(spokenPairs)
        }
    }

    private fun enqueueAndSpeakReplies(replies: List<Pair<ProviderType, String>>) {
        if (!isCallActive.get()) return
        try {
            speechRecognizer?.cancel()
        } catch (_: Exception) {
        }
        speechQueue.clear()
        speechQueue.addAll(replies)
        playNextQueuedAgentVoiceOrResumeListening()
    }

    private fun playNextQueuedAgentVoiceOrResumeListening() {
        if (!isCallActive.get()) return
        val next = speechQueue.poll()
        if (next == null) {
            isAiSpeaking.set(false)
            speakingWaveJob?.cancel()
            onAudioLevelUpdate(0.05f)
            if (isMicMuted.get()) {
                onCallStateChanged(AgentState.MUTED, "Microphone Muted")
            } else {
                onCallStateChanged(AgentState.LISTENING, "Listening...")
                scheduleListenRestart(250L)
            }
            return
        }

        val (provider, rawText) = next
        val cleanSpeech = rawText
            .replace(Regex("[*#_`~]"), "")
            .replace(Regex("https?://\\S+"), "")
            .trim()

        isAiSpeaking.set(true)
        onCallStateChanged(AgentState.SPEAKING, "${provider.displayName} Speaking...")

        // Give each AI provider a subtly distinct vocal pitch so multi-agent calls sound like real distinct speakers!
        val pitch = when (provider) {
            ProviderType.GEMINI -> 1.02f
            ProviderType.GROQ -> 0.94f
            ProviderType.OPENROUTER -> 1.08f
            ProviderType.OPENAI -> 0.98f
            ProviderType.GROK -> 0.90f
        }
        tts?.setPitch(pitch)
        tts?.setSpeechRate(0.98f)

        // Automatically detect if the reply contains Bengali script and set locale accordingly
        val hasBengali = cleanSpeech.any { it in '\u0980'..'\u09FF' }
        tts?.language = if (hasBengali) Locale("bn", "BD") else Locale.US

        startSimulatedSpeakingWave()
        tts?.speak(
            cleanSpeech,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "call_utt_${System.currentTimeMillis()}"
        )
    }

    private fun startSimulatedSpeakingWave() {
        speakingWaveJob?.cancel()
        speakingWaveJob = scope.launch(Dispatchers.Main) {
            val levels = floatArrayOf(0.25f, 0.65f, 0.45f, 0.85f, 0.35f, 0.75f, 0.50f)
            var idx = 0
            while (isCallActive.get() && isAiSpeaking.get()) {
                onAudioLevelUpdate(levels[idx % levels.size])
                idx++
                delay(140L)
            }
        }
    }

    private fun stopAiSpeechOnly() {
        speechQueue.clear()
        isAiSpeaking.set(false)
        speakingWaveJob?.cancel()
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
    }

    fun setMuted(muted: Boolean) {
        isMicMuted.set(muted)
        if (!isCallActive.get()) return
        if (muted) {
            listenRestartJob?.cancel()
            try {
                speechRecognizer?.stopListening()
                speechRecognizer?.cancel()
            } catch (_: Exception) {
            }
            onAudioLevelUpdate(0f)
            onCallStateChanged(AgentState.MUTED, "Microphone Muted")
        } else {
            onCallStateChanged(AgentState.LISTENING, "Listening...")
            scheduleListenRestart(150L)
        }
    }

    fun isMuted(): Boolean = isMicMuted.get()

    fun setSpeakerphone(enabled: Boolean) {
        isSpeakerphoneEnabled = enabled
        try {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = enabled
        } catch (_: Exception) {
        }
    }

    fun isSpeakerphoneOn(): Boolean = isSpeakerphoneEnabled

    fun isCallRunning(): Boolean = isCallActive.get()

    fun endCall() {
        if (!isCallActive.getAndSet(false)) return
        listenRestartJob?.cancel()
        activeTurnJob?.cancel()
        speakingWaveJob?.cancel()
        speechQueue.clear()
        isAiSpeaking.set(false)

        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        }
        speechRecognizer = null

        try {
            tts?.stop()
        } catch (_: Exception) {
        }

        abandonAudioFocus()
        onAudioLevelUpdate(0f)
        onCallStateChanged(AgentState.CALL_ENDED, "Call ended")
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
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

    private val callRecognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {
            if (isAiSpeaking.get()) {
                stopAiSpeechOnly()
            }
        }

        override fun onRmsChanged(rmsdB: Float) {
            if (!isAiSpeaking.get() && !isMicMuted.get()) {
                val norm = ((rmsdB + 2f) / 12f).coerceIn(0.05f, 1.0f)
                onAudioLevelUpdate(norm)
            }
        }

        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            onAudioLevelUpdate(0.08f)
        }

        override fun onError(error: Int) {
            if (!isCallActive.get() || isMicMuted.get()) return
            // Keep the call alive continuously! Never drop the call on silence or recognition timeout.
            if (!isAiSpeaking.get()) {
                scheduleListenRestart(350L)
            }
        }

        override fun onResults(results: Bundle?) {
            if (!isCallActive.get()) return
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull()?.trim().orEmpty()
            if (text.isNotEmpty()) {
                handleUserSpokenTurn(text)
            } else if (!isMicMuted.get() && !isAiSpeaking.get()) {
                scheduleListenRestart(300L)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
