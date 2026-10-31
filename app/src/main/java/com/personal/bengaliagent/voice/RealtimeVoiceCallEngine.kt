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
import android.speech.tts.Voice
import com.personal.bengaliagent.AgentApp
import com.personal.bengaliagent.agent.AndroidActionManager
import com.personal.bengaliagent.data.security.CredentialRole
import com.personal.bengaliagent.domain.model.AgentState
import com.personal.bengaliagent.domain.model.ChatMessage
import com.personal.bengaliagent.domain.model.FemaleVoicePersona
import com.personal.bengaliagent.domain.model.PersonalityMode
import com.personal.bengaliagent.domain.model.ProviderType
import com.personal.bengaliagent.network.AiClientRouter
import com.personal.bengaliagent.screen.ScreenCaptureManager
import com.personal.bengaliagent.service.AgentAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

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
    private var preferredFemaleBnVoice: Voice? = null
    private var preferredFemaleEnVoice: Voice? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private val geminiTtsClient = GeminiTtsClient()

    private val isCallActive = AtomicBoolean(false)
    private val isMicMuted = AtomicBoolean(false)
    private val isAiSpeaking = AtomicBoolean(false)
    private var isSpeakerphoneEnabled = true

    private var listenRestartJob: Job? = null
    private var activeTurnJob: Job? = null
    private var speakingWaveJob: Job? = null

    private val recentCallHistory = ArrayList<ChatMessage>()
    private val speechQueue = ConcurrentLinkedQueue<Pair<ProviderType, String>>()
    private val memoryRepo by lazy { AgentApp.instance.memoryRepository }
    private val prefs by lazy { AgentApp.instance.appPreferences }

    init {
        tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val bnLocale = Locale("bn", "BD")
            val res = tts?.setLanguage(bnLocale)
            if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.language = Locale("bn", "IN")
            }
            selectBestCuteFemaleVoices()

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

    private fun selectBestCuteFemaleVoices() {
        val voices = tts?.voices ?: return
        val bnVoices = voices.filter { it.locale.language.equals("bn", ignoreCase = true) }
        preferredFemaleBnVoice = bnVoices.firstOrNull { v ->
            val n = v.name.lowercase()
            n.contains("female") || n.contains("ban") || n.contains("sfg") ||
                n.contains("wavenet-a") || n.contains("wavenet-c") || !n.contains("male")
        } ?: bnVoices.firstOrNull()

        val enVoices = voices.filter { it.locale.language.equals("en", ignoreCase = true) }
        preferredFemaleEnVoice = enVoices.firstOrNull { v ->
            val n = v.name.lowercase()
            n.contains("female") || n.contains("sfg") || n.contains("iob") || n.contains("tpf")
        } ?: enVoices.firstOrNull()

        preferredFemaleBnVoice?.let { tts?.voice = it }
    }

    fun startCall() {
        if (isCallActive.get()) return
        val connectedProviders = router.getConnectedProviders()
        if (connectedProviders.isEmpty()) {
            onCallStateChanged(
                AgentState.ERROR,
                "No AI provider connected. Tap Settings to connect Gemini or Grok."
            )
            return
        }

        isCallActive.set(true)
        isMicMuted.set(false)
        speechQueue.clear()

        val activeNames = connectedProviders.joinToString(" + ") { it.displayName }
        onCallStateChanged(AgentState.CALLING, "Calling Sumu AI ($activeNames)...")

        scope.launch(Dispatchers.Main) {
            configureCallAudioRouting()
            val savedMsgs = memoryRepo.loadRecentMessages(limit = 6)
            recentCallHistory.clear()
            recentCallHistory.addAll(savedMsgs)

            val mode = prefs.getPersonalityMode()
            delay(320L)
            if (!isCallActive.get()) return@launch

            onCallStateChanged(AgentState.CONNECTED, "${router.networkQualityBadge()} • ${mode.badgeLabel}")
            val greeting = when (mode) {
                PersonalityMode.GF -> "হ্যালো জান, আমি সুমু। বলো কী করব তোমার জন্য?"
                PersonalityMode.BEST_FRIEND -> "হেই দোস্ত! বল কী খবর?"
                PersonalityMode.PROFESSIONAL -> "Sumu AI connected. How may I assist you?"
                PersonalityMode.SILENT_ASSISTANT -> "প্রস্তুত।"
                else -> "হ্যালো, আমি সুমু। বলো কীভাবে সাহায্য করতে পারি?"
            }
            enqueueAndSpeakReplies(listOf(connectedProviders.first() to greeting))
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
                onCallStateChanged(AgentState.LISTENING, "Voice recognition unavailable on device.")
                return
            }
            if (speechRecognizer == null) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context.applicationContext).apply {
                    setRecognitionListener(callRecognitionListener)
                }
            }
            val langTag = runCatching {
                kotlinx.coroutines.runBlocking { prefs.getVoiceLanguageTag() }
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
            scheduleListenRestart(450L)
        }
    }

    private fun scheduleListenRestart(delayMs: Long = 260L) {
        listenRestartJob?.cancel()
        listenRestartJob = scope.launch(Dispatchers.Main) {
            delay(delayMs)
            if (isCallActive.get() && !isMicMuted.get() && !isAiSpeaking.get()) {
                startContinuousListening()
            }
        }
    }

    /**
     * Extracts user preferences (such as favorite voice Aoede/Callirrhoe/Despina, name, or facts)
     * and persists them in SQLite Memory Brain across restarts.
     */
    private suspend fun extractAndPersistMemories(spokenText: String) {
        val lower = spokenText.lowercase()

        // Check if user mentioned a favorite female voice persona
        for (persona in FemaleVoicePersona.entries) {
            if (lower.contains(persona.voiceId.lowercase())) {
                prefs.setPreferredFemaleVoice(persona)
                memoryRepo.addOrUpdateFact(
                    category = "PREFERENCE",
                    title = "Favorite Voice (পছন্দের ভয়েস)",
                    details = "ইউজারের পছন্দের voice হলো ${persona.voiceId}"
                )
            }
        }

        val triggers = listOf(
            "মনে রেখো", "মনে রাখবে", "ভুলে যেও না", "আজ থেকে", "তুমি আমাকে", "আমার নাম",
            "পছন্দের", "ভালোবাসি", "শিখিয়ে দিচ্ছি", "শিখে রাখো", "remember", "my favorite", "my name"
        )
        if (triggers.any { lower.contains(it) }) {
            memoryRepo.addOrUpdateFact(
                category = "USER_MEMORY",
                title = spokenText.take(38),
                details = spokenText
            )
        }
    }

    private fun handleUserSpokenTurn(spokenText: String) {
        if (!isCallActive.get() || spokenText.isBlank()) return
        stopAiSpeechOnly()

        activeTurnJob?.cancel()
        activeTurnJob = scope.launch(Dispatchers.Main) {
            onCallStateChanged(AgentState.THINKING, "\"${spokenText.take(40)}\"")

            val userMsg = ChatMessage(
                id = System.currentTimeMillis(),
                role = ChatMessage.Role.USER,
                text = spokenText
            )
            memoryRepo.saveChatMessage(userMsg)
            extractAndPersistMemories(spokenText)

            recentCallHistory.add(userMsg)
            if (recentCallHistory.size > 8) {
                recentCallHistory.removeAt(0)
            }

            // 1. Check for Dynamic Voice Personality Mode Switching
            val newMode = PersonalityMode.detectVoiceModeSwitch(spokenText)
            if (newMode != null) {
                prefs.setPersonalityMode(newMode)
                memoryRepo.addOrUpdateFact(
                    category = "PREFERENCE",
                    title = "Personality Mode",
                    details = "Active personality mode: ${newMode.displayName}"
                )
                val confirmText = when (newMode) {
                    PersonalityMode.GF -> "ঠিক আছে জান, এখন থেকে আমি তোমার GF মোডেই কথা বলব।"
                    PersonalityMode.PROFESSIONAL -> "Switched to Professional Mode."
                    PersonalityMode.BEST_FRIEND -> "ওকে দোস্ত! এখন থেকে একদম ফ্রেন্ডলি কথা হবে।"
                    PersonalityMode.SILENT_ASSISTANT -> "সাইলেন্ট মোড চালু।"
                    else -> "ঠিক আছে, এখন থেকে ${newMode.displayName}-এ কথা বলব।"
                }
                enqueueAndSpeakReplies(listOf(ProviderType.GEMINI to confirmText))
                return@launch
            }

            // 2. Instant Android Phone & Screen Control Execution
            val directAction = AndroidActionManager.tryExecuteDirectCommand(context, spokenText)
            if (directAction.matchedIntentCommand) {
                onCallStateChanged(AgentState.ACTING, directAction.responseMessage)
                memoryRepo.saveChatMessage(
                    ChatMessage(
                        id = System.currentTimeMillis(),
                        role = ChatMessage.Role.ASSISTANT,
                        text = directAction.responseMessage,
                        providerUsed = "Sumu Action"
                    )
                )
                enqueueAndSpeakReplies(
                    listOf(ProviderType.GEMINI to directAction.responseMessage)
                )
                return@launch
            }

            // 3. Build Context with Active Personality Mode + Persistent Memory + Live Screen
            val activeMode = prefs.getPersonalityMode()
            val customPrompt = prefs.getCustomPersonalityPrompt()
            val favoriteVoice = prefs.getPreferredFemaleVoice()
            val multiAgentMode = prefs.isAutoModelSelectionEnabled()
            val memoryContext = memoryRepo.buildRelevantMemoryContext(spokenText)
            val screenObs = AgentAccessibilityService.instance?.observeCurrentScreen()
            val screenshotBase64 = if (ScreenCaptureManager.isSharingActive()) {
                ScreenCaptureManager.captureFreshFrameWithRetry()
            } else {
                null
            }

            val masterInstruction = buildString {
                appendLine(activeMode.systemInstruction)
                if (activeMode == PersonalityMode.CUSTOM && customPrompt.isNotBlank()) {
                    appendLine("ইউজারের কাস্টম পার্সোনালিটি নির্দেশনা: $customPrompt")
                }
                appendLine("তোমার নাম Sumu AI (সুমু) এবং তোমার বর্তমান কণ্ঠ (Voice) হলো: ${favoriteVoice.voiceId}।")
                appendLine("তোমার কাছে ইউজারের ফোনের যেকোনো অ্যাপ ওপেন করা এবং পুরো স্ক্রিন কন্ট্রোল করার (টাচ, লং প্রেস, স্ক্রল, সোয়াইপ, ব্যাক, হোম, টাইপ) সম্পূর্ণ পারমিশন আছে।")
                appendLine("ইউজার কোনো কাজ বা স্ক্রিন অ্যাকশন করতে বললে উত্তরের শেষে লিখবে: [ACTION: tool_name | target_name]")
                appendLine("tool_name হতে পারে: open_app, tap_element, long_press, scroll_down, scroll_up, swipe_left, swipe_right, type_text, go_back, go_home, turn_on_flashlight, turn_off_flashlight")
                if (memoryContext.isNotBlank()) {
                    appendLine("\nতোমার দীর্ঘমেয়াদী মেমোরি (Saved Long-Term Memory):")
                    appendLine(memoryContext)
                }
                if (screenObs != null && screenObs.elements.isNotEmpty()) {
                    appendLine("\nবর্তমানে ফোনের স্ক্রিনে যা যা দেখা যাচ্ছে (Live Screen UI):")
                    appendLine(screenObs.toCompactPromptString(maxElements = 50))
                }
            }

            val contextMsgs = mutableListOf<ChatMessage>()
            contextMsgs.add(
                ChatMessage(
                    id = 0L,
                    role = ChatMessage.Role.SYSTEM,
                    text = masterInstruction
                )
            )
            contextMsgs.addAll(recentCallHistory)

            val agentReplies = router.queryActiveCallAgents(
                messages = contextMsgs,
                base64JpegImage = screenshotBase64,
                multiAgentMode = multiAgentMode
            )

            if (!isCallActive.get()) return@launch

            if (agentReplies.isEmpty()) {
                enqueueAndSpeakReplies(
                    listOf(ProviderType.GEMINI to "জান, নেটওয়ার্কটা একটু স্লো করছে, আরেকবার বলো তো?")
                )
                return@launch
            }

            val spokenPairs = mutableListOf<Pair<ProviderType, String>>()
            for ((idx, reply) in agentReplies.withIndex()) {
                val cleanSpoken = executeEmbeddedAiActionIfPresent(reply.spokenText)
                if (idx == 0) {
                    val assistantMsg = ChatMessage(
                        id = System.currentTimeMillis(),
                        role = ChatMessage.Role.ASSISTANT,
                        text = cleanSpoken,
                        providerUsed = reply.provider.displayName
                    )
                    recentCallHistory.add(assistantMsg)
                    memoryRepo.saveChatMessage(assistantMsg)
                }
                val prefix = if (agentReplies.size > 1) "${reply.provider.displayName}: " else ""
                spokenPairs.add(reply.provider to (prefix + cleanSpoken))
            }

            enqueueAndSpeakReplies(spokenPairs)
        }
    }

    private fun executeEmbeddedAiActionIfPresent(rawReply: String): String {
        var spoken = rawReply
        val actionRegex = Regex("\\[ACTION:\\s*([^|\\]]+)\\s*(?:\\|\\s*([^\\]]*))?\\]", RegexOption.IGNORE_CASE)
        val match = actionRegex.find(rawReply)
        if (match != null) {
            val toolName = match.groupValues[1].trim()
            val targetName = match.groupValues.getOrNull(2)?.trim().orEmpty()
            spoken = rawReply.replace(match.value, "").trim()
            scope.launch(Dispatchers.Main) {
                onCallStateChanged(AgentState.ACTING, "Executing: $toolName $targetName")
                AndroidActionManager.executeStructuredToolCall(context, toolName, targetName)
            }
        } else if (rawReply.contains("\"actions\"") && rawReply.contains("{")) {
            try {
                val jsonStr = rawReply.substring(rawReply.indexOf('{'), rawReply.lastIndexOf('}') + 1)
                val root = JSONObject(jsonStr)
                val arr = root.optJSONArray("actions")
                if (arr != null && arr.length() > 0) {
                    val first = arr.getJSONObject(0)
                    val tool = first.optString("tool", "").lowercase()
                    val target = first.optString("targetLabel", "").ifBlank {
                        first.optString("packageNameOrAppName", "").ifBlank {
                            first.optString("textToType", "")
                        }
                    }
                    val x = first.optInt("x", -1)
                    val y = first.optInt("y", -1)
                    scope.launch(Dispatchers.Main) {
                        AndroidActionManager.executeStructuredToolCall(context, tool, target, x, y)
                    }
                    spoken = root.optString("directReplyBn", "").ifBlank {
                        root.optString("thoughtBn", "করে দিয়েছি জান।")
                    }
                }
            } catch (_: Exception) {
            }
        }
        return spoken.ifBlank { "এই তো করে দিয়েছি জান।" }
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
                onCallStateChanged(AgentState.LISTENING, "Listening • ${router.networkQualityBadge()}")
                scheduleListenRestart(220L)
            }
            return
        }

        val (provider, rawText) = next
        val cleanSpeech = rawText
            .replace(Regex("[*#_`~]"), "")
            .replace(Regex("https?://\\S+"), "")
            .trim()

        isAiSpeaking.set(true)
        scope.launch(Dispatchers.Main) {
            val persona = prefs.getPreferredFemaleVoice()
            onCallStateChanged(AgentState.SPEAKING, "Sumu (${persona.voiceId}) Speaking...")
            startSimulatedSpeakingWave()

            // If network is fast, try Gemini Cloud TTS first when speaking short lines, else use instant tuned female TTS
            val geminiVoiceKey = AgentApp.instance.secureKeyStore
                .getKeysForRoleWithBackup(ProviderType.GEMINI, CredentialRole.PRIMARY_VOICE)
                .firstOrNull()

            if (!geminiVoiceKey.isNullOrBlank() && router.lastLatencyMs < 1100L && cleanSpeech.length <= 140) {
                val playedCloud = geminiTtsClient.synthesizeAndPlayIfSupported(
                    apiKey = geminiVoiceKey,
                    text = cleanSpeech,
                    voicePersona = persona
                )
                if (playedCloud) {
                    playNextQueuedAgentVoiceOrResumeListening()
                    return@launch
                }
            }

            val hasBengali = cleanSpeech.any { it in '\u0980'..'\u09FF' }
            if (hasBengali) {
                if (preferredFemaleBnVoice != null) {
                    tts?.voice = preferredFemaleBnVoice
                } else {
                    tts?.language = Locale("bn", "BD")
                }
            } else {
                if (preferredFemaleEnVoice != null) {
                    tts?.voice = preferredFemaleEnVoice
                } else {
                    tts?.language = Locale.US
                }
            }

            tts?.setPitch(persona.pitchMultiplier)
            tts?.setSpeechRate(persona.rateMultiplier)
            tts?.speak(
                cleanSpeech,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "call_utt_${System.currentTimeMillis()}"
            )
        }
    }

    private fun startSimulatedSpeakingWave() {
        speakingWaveJob?.cancel()
        speakingWaveJob = scope.launch(Dispatchers.Main) {
            val levels = floatArrayOf(0.28f, 0.68f, 0.48f, 0.88f, 0.38f, 0.78f, 0.52f)
            var idx = 0
            while (isCallActive.get() && isAiSpeaking.get()) {
                onAudioLevelUpdate(levels[idx % levels.size])
                idx++
                delay(130L)
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
                scheduleListenRestart(280L)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
