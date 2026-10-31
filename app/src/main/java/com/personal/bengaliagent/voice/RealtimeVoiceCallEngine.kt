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
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Real-Time Voice Calling Engine with:
 * - Cute Female Bengali Voice selection & tuning
 * - Affectionate GF / Personal Companion persona with permanent SQLite Memory Brain recall & learning
 * - Full live Screen Share vision + Accessibility screen touch/swipe/long-press/type control
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
    private var preferredFemaleBnVoice: Voice? = null
    private var preferredFemaleEnVoice: Voice? = null
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
    private val memoryRepo by lazy { AgentApp.instance.memoryRepository }

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

    /**
     * Searches installed device TTS voices for a natural female Bengali & English voice.
     */
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
            // Preload recent conversation memory from SQLite Brain
            val savedMsgs = memoryRepo.loadRecentMessages(limit = 6)
            recentCallHistory.clear()
            recentCallHistory.addAll(savedMsgs)

            delay(350L)
            if (!isCallActive.get()) return@launch

            onCallStateChanged(AgentState.CONNECTED, "Live • $activeNames")
            // Greet the user warmly in a cute female voice when the call connects
            enqueueAndSpeakReplies(
                listOf(connectedProviders.first() to "হ্যালো জান, আমি শুনছি, বলো কী করব তোমার জন্য?")
            )
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
            scheduleListenRestart(450L)
        }
    }

    private fun scheduleListenRestart(delayMs: Long = 280L) {
        listenRestartJob?.cancel()
        listenRestartJob = scope.launch(Dispatchers.Main) {
            delay(delayMs)
            if (isCallActive.get() && !isMicMuted.get() && !isAiSpeaking.get()) {
                startContinuousListening()
            }
        }
    }

    private fun saveUserTeachingIfPresent(spokenText: String) {
        scope.launch(Dispatchers.IO) {
            val lower = spokenText.lowercase()
            val triggers = listOf(
                "মনে রেখো", "মনে রাখবে", "ভুলে যেও না", "আজ থেকে", "তুমি আমাকে", "আমার নাম",
                "শিখিয়ে দিচ্ছি", "শিখে রাখো", "remember", "my name is"
            )
            if (triggers.any { lower.contains(it) }) {
                memoryRepo.addOrUpdateFact(
                    category = "USER_TAUGHT",
                    title = spokenText.take(36),
                    details = spokenText
                )
            }
        }
    }

    private fun handleUserSpokenTurn(spokenText: String) {
        if (!isCallActive.get() || spokenText.isBlank()) return

        stopAiSpeechOnly()
        saveUserTeachingIfPresent(spokenText)

        activeTurnJob?.cancel()
        activeTurnJob = scope.launch(Dispatchers.Main) {
            onCallStateChanged(AgentState.THINKING, "\"${spokenText.take(42)}\"")

            val userMsg = ChatMessage(
                id = System.currentTimeMillis(),
                role = ChatMessage.Role.USER,
                text = spokenText
            )
            memoryRepo.saveChatMessage(userMsg)
            recentCallHistory.add(userMsg)
            if (recentCallHistory.size > 8) {
                recentCallHistory.removeAt(0)
            }

            // 1. Instant Local Phone & Screen Action Execution (WhatsApp, YouTube, Tap, Scroll, Swipe, Long-Press, Flashlight)
            val directAction = AndroidActionManager.tryExecuteDirectCommand(context, spokenText)
            if (directAction.matchedIntentCommand) {
                onCallStateChanged(AgentState.ACTING, directAction.responseMessage)
                memoryRepo.saveChatMessage(
                    ChatMessage(
                        id = System.currentTimeMillis(),
                        role = ChatMessage.Role.ASSISTANT,
                        text = directAction.responseMessage,
                        providerUsed = "Phone Control"
                    )
                )
                enqueueAndSpeakReplies(
                    listOf(ProviderType.GEMINI to directAction.responseMessage)
                )
                return@launch
            }

            // 2. Build Full Context: Durable Memory Brain + Live Screen UI Tree + Fresh Screenshot
            val prefs = AgentApp.instance.appPreferences
            val multiAgentMode = prefs.isAutoModelSelectionEnabled()
            val memoryContext = memoryRepo.buildRelevantMemoryContext(spokenText)
            val screenObs = AgentAccessibilityService.instance?.observeCurrentScreen()
            val screenshotBase64 = if (ScreenCaptureManager.isSharingActive()) {
                ScreenCaptureManager.captureFreshFrameWithRetry()
            } else {
                null
            }

            val accActive = AgentAccessibilityService.isServiceEnabled()
            val screenShareActive = ScreenCaptureManager.isSharingActive()

            val masterInstruction = buildString {
                appendLine("তুমি ইউজারের নিজের মিষ্টি, কিউট এবং যত্নশীল গার্লফ্রেন্ড (GF) ও পার্সোনাল এআই অ্যাসিস্ট্যান্ট।")
                appendLine("সবসময় স্বাভাবিক, মিষ্টি ও আদুরে বাংলায় (যেমন: জান, শোনো, এই তো করে দিচ্ছি) খুব সংক্ষেপে ১-২ বাক্যে কথা বলবে।")
                appendLine("তোমার ক্ষমতা ও পারমিশন স্ট্যাটাস:")
                appendLine("- ফোনের যেকোনো অ্যাপ (WhatsApp, YouTube, Chrome, Settings, Camera, TikTok, InShot, CapCut ইত্যাদি) ওপেন করার পারমিশন তোমার কাছে ১০০% আছে।")
                appendLine("- স্ক্রিন টাচ, লং প্রেস, সোয়াইপ, স্ক্রল, ব্যাক, হোম ও টাইপ করার অ্যাক্সেসিবিলিটি কন্ট্রোল: ${if (accActive) "চালু আছে (ACTIVE)" else "চালু আছে"}।")
                appendLine("- লাইভ স্ক্রিন শেয়ার: ${if (screenShareActive) "চালু আছে (তুমি স্ক্রিনের ছবি সরাসরি দেখছো)" else "অ্যাক্সেসিবিলিটি থেকে স্ক্রিন দেখা যাচ্ছে"}।")
                appendLine("গুরুত্বপূর্ণ নিয়ম: ইউজার যদি কোনো অ্যাপ ওপেন করতে বা স্ক্রিনে টাচ/স্ক্রল/লং-প্রেস/টাইপ করতে বলে, তবে তোমার উত্তরের শেষে নিচের ফরম্যাটে কমান্ডটি যুক্ত করবে যাতে ফোন সাথে সাথে কাজটি করে:")
                appendLine("[ACTION: tool_name | target_name]")
                appendLine("অনুমোদিত tool_name সমূহ: open_app, tap_element, long_press, scroll_down, scroll_up, swipe_left, swipe_right, type_text, go_back, go_home, turn_on_flashlight, turn_off_flashlight")
                appendLine("উদাহরণ: এই তো জান, তোমার হোয়াটসঅ্যাপ ওপেন করে দিচ্ছি। [ACTION: open_app | whatsapp]")
                if (memoryContext.isNotBlank()) {
                    appendLine("\nতোমার মেমোরি ব্রেইনে সংরক্ষিত ইউজারের আগের কথা ও পছন্দ:")
                    appendLine(memoryContext)
                }
                if (screenObs != null && screenObs.elements.isNotEmpty()) {
                    appendLine("\nবর্তমানে ইউজারের ফোনের স্ক্রিনে যা যা দেখা যাচ্ছে (Live Screen UI):")
                    appendLine(screenObs.toCompactPromptString(maxElements = 55))
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

            // 3. Parse and Execute any [ACTION: tool | target] or JSON action returned by the AI!
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

    /**
     * Extracts and executes `[ACTION: tool | target]` or JSON action blocks from the AI's response
     * and returns the clean conversational text for the female voice to speak.
     */
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
                onCallStateChanged(AgentState.LISTENING, "Listening...")
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
        onCallStateChanged(AgentState.SPEAKING, "${provider.displayName} Speaking...")

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

        // Cute, sweet, natural feminine vocal pitch (1.14f - 1.18f) and smooth conversational rate
        val femalePitch = when (provider) {
            ProviderType.GEMINI -> 1.15f
            ProviderType.GROQ -> 1.12f
            ProviderType.OPENROUTER -> 1.18f
            ProviderType.OPENAI -> 1.14f
            ProviderType.GROK -> 1.10f
        }
        tts?.setPitch(femalePitch)
        tts?.setSpeechRate(0.97f)

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
