package com.personal.bengaliagent.ui.main

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.personal.bengaliagent.AgentApp
import com.personal.bengaliagent.agent.ActionExecutor
import com.personal.bengaliagent.agent.AgentPlanner
import com.personal.bengaliagent.agent.PlannedAction
import com.personal.bengaliagent.databinding.ActivityMainBinding
import com.personal.bengaliagent.domain.model.AgentState
import com.personal.bengaliagent.domain.model.ChatMessage
import com.personal.bengaliagent.research.WebResearchClient
import com.personal.bengaliagent.screen.ScreenCaptureManager
import com.personal.bengaliagent.service.AgentAccessibilityService
import com.personal.bengaliagent.service.AgentForegroundService
import com.personal.bengaliagent.service.AgentSessionBus
import com.personal.bengaliagent.ui.settings.ProviderSettingsActivity
import com.personal.bengaliagent.ui.setup.SetupWizardActivity
import com.personal.bengaliagent.voice.VoiceManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val transcriptAdapter = TranscriptAdapter()
    private val messages = mutableListOf<ChatMessage>()
    private lateinit var voiceManager: VoiceManager
    private lateinit var actionExecutor: ActionExecutor
    private val webResearchClient = WebResearchClient()

    private val memoryRepo by lazy { AgentApp.instance.memoryRepository }
    private val prefs by lazy { AgentApp.instance.appPreferences }

    private var currentState: AgentState = AgentState.READY
    private var isAssistantEnabled: Boolean = false
    private var isMicMuted: Boolean = true
    private var isScreenSharing: Boolean = false
    private var activeTaskJob: Job? = null

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startVoicePushToTalk()
        } else {
            Toast.makeText(this, "Microphone permission is required for voice input.", Toast.LENGTH_SHORT).show()
        }
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val svcIntent = Intent(this, AgentForegroundService::class.java).apply {
                action = AgentForegroundService.ACTION_START_PROJECTION
                putExtra(AgentForegroundService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(AgentForegroundService.EXTRA_PROJECTION_DATA, result.data)
            }
            ContextCompat.startForegroundService(this, svcIntent)
            isScreenSharing = true
            isAssistantEnabled = true
            updateState(AgentState.SCREEN_SHARING)
            appendSystemMessage(
                "Screen sharing active. Open any coding or video editing app and tell me what to do—I will observe your screen and perform the actions directly.",
                persist = false
            )
            refreshStatusUi()
        } else {
            isScreenSharing = false
            appendSystemMessage("Screen sharing permission was declined.", persist = false)
            refreshStatusUi()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.rvTranscript.apply {
            layoutManager = LinearLayoutManager(this@MainActivity).apply {
                stackFromEnd = true
            }
            adapter = transcriptAdapter
        }

        voiceManager = VoiceManager(
            context = this,
            scope = lifecycleScope,
            onTranscriptReady = { spokenText ->
                handleIncomingPrompt(spokenText)
            },
            onStatusUpdate = { statusEn ->
                appendSystemMessage(statusEn, persist = false)
            },
            onListeningStateChanged = { listening ->
                isMicMuted = !listening
                if (listening) {
                    updateState(AgentState.LISTENING)
                } else if (currentState == AgentState.LISTENING) {
                    updateState(AgentState.READY)
                }
                refreshStatusUi()
            }
        )

        actionExecutor = ActionExecutor(
            context = this,
            onConfirmSensitiveAction = { plannedAction ->
                askUserConfirmationForSensitiveAction(plannedAction)
            },
            onStepProgress = { stepNote ->
                runOnUiThread {
                    updateState(AgentState.ACTING)
                    appendSystemMessage(stepNote, persist = false)
                    refreshStatusUi()
                }
            }
        )

        ScreenCaptureManager.onProjectionStoppedListener = {
            runOnUiThread {
                isScreenSharing = false
                if (currentState == AgentState.SCREEN_SHARING) {
                    updateState(AgentState.READY)
                }
                refreshStatusUi()
            }
        }

        observeSessionBusCommands()

        lifecycleScope.launch {
            val completed = prefs.isSetupCompleted.first()
            if (!completed) {
                startActivity(Intent(this@MainActivity, SetupWizardActivity::class.java))
            }
            memoryRepo.applyRetentionPolicy(prefs.getMemoryRetentionDays())
            val savedHistory = memoryRepo.loadRecentMessages(limit = 50)
            if (savedHistory.isNotEmpty()) {
                messages.addAll(savedHistory)
                transcriptAdapter.submitList(messages)
                binding.rvTranscript.scrollToPosition(messages.size - 1)
            } else {
                appendSystemMessage(
                    "Welcome to Bengali AI Agent. Tap the ⚙ Settings icon in the top-right corner to connect Gemini, Groq, or OpenRouter and enable AI Coding & Video Editing screen control.",
                    persist = false
                )
            }
        }

        setupControls()
        refreshStatusUi()
    }

    private fun observeSessionBusCommands() {
        lifecycleScope.launch {
            AgentSessionBus.commands.collect { cmd ->
                when (cmd) {
                    AgentSessionBus.Command.TOGGLE_MUTE -> {
                        binding.btnMicToggle.performClick()
                    }
                    AgentSessionBus.Command.TOGGLE_PAUSE -> {
                        binding.btnPauseResume.performClick()
                    }
                    AgentSessionBus.Command.STOP_CURRENT_TASK -> {
                        stopAllCaptureAndTasks("Current task stopped from control bar.")
                        refreshStatusUi()
                    }
                    AgentSessionBus.Command.DISABLE_SESSION -> {
                        isAssistantEnabled = false
                        stopAllCaptureAndTasks("Assistant session disabled.")
                        refreshStatusUi()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isScreenSharing = ScreenCaptureManager.isSharingActive()
        lifecycleScope.launch {
            voiceManager.setLanguageTag(prefs.getVoiceLanguageTag())
        }
        refreshStatusUi()
    }

    private fun setupControls() {
        binding.btnOpenSettings.setOnClickListener {
            startActivity(Intent(this, ProviderSettingsActivity::class.java))
        }

        binding.btnToggleAssistant.setOnClickListener {
            isAssistantEnabled = !isAssistantEnabled
            if (!isAssistantEnabled) {
                stopAllCaptureAndTasks("Assistant disabled. Microphone and screen capture are off.")
            } else {
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, AgentForegroundService::class.java)
                )
                updateState(if (isNetworkAvailable()) AgentState.READY else AgentState.OFFLINE)
            }
            refreshStatusUi()
        }

        binding.btnPauseResume.setOnClickListener {
            if (currentState == AgentState.PAUSED) {
                updateState(AgentState.READY)
            } else {
                activeTaskJob?.cancel()
                voiceManager.stopListeningImmediately()
                voiceManager.stopSpeaking()
                ScreenCaptureManager.stopProjection()
                isMicMuted = true
                isScreenSharing = false
                updateState(AgentState.PAUSED)
                appendSystemMessage(
                    "Assistant paused. Audio and screen capture have been stopped.",
                    persist = false
                )
            }
            refreshStatusUi()
        }

        binding.btnStopTask.setOnClickListener {
            stopAllCaptureAndTasks("Stopped current task and released active capture.")
            refreshStatusUi()
        }

        binding.btnMicToggle.setOnClickListener {
            if (currentState == AgentState.PAUSED) {
                Toast.makeText(this, "Resume the assistant before enabling the microphone.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!isMicMuted) {
                voiceManager.stopListeningImmediately()
                isMicMuted = true
                updateState(AgentState.MUTED)
                refreshStatusUi()
            } else {
                val granted = ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                if (granted) {
                    startVoicePushToTalk()
                } else {
                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }

        binding.btnScreenToggle.setOnClickListener {
            if (isScreenSharing) {
                ScreenCaptureManager.stopProjection()
                isScreenSharing = false
                updateState(AgentState.READY)
                appendSystemMessage("Screen sharing stopped.", persist = false)
                refreshStatusUi()
            } else {
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjectionLauncher.launch(mpm.createScreenCaptureIntent())
            }
        }

        binding.btnSend.setOnClickListener {
            val prompt = binding.etUserInput.text?.toString()?.trim().orEmpty()
            if (prompt.isEmpty()) return@setOnClickListener
            binding.etUserInput.setText("")
            handleIncomingPrompt(prompt)
        }
    }

    private fun startVoicePushToTalk() {
        voiceManager.stopSpeaking()
        voiceManager.startListening()
    }

    private fun handleIncomingPrompt(prompt: String) {
        if (prompt.equals("stop", ignoreCase = true) ||
            prompt.contains("থামো") ||
            prompt.contains("বন্ধ করো")
        ) {
            stopAllCaptureAndTasks("Task stopped immediately at your request.")
            refreshStatusUi()
            return
        }
        sendUserMessage(prompt)
    }

    private fun sendUserMessage(prompt: String) {
        voiceManager.stopSpeaking()
        val userMsg = ChatMessage(
            id = System.currentTimeMillis(),
            role = ChatMessage.Role.USER,
            text = prompt
        )
        messages.add(userMsg)
        transcriptAdapter.submitList(messages)
        binding.rvTranscript.scrollToPosition(messages.size - 1)

        lifecycleScope.launch {
            memoryRepo.saveChatMessage(userMsg)
        }

        if (!isNetworkAvailable()) {
            updateState(AgentState.OFFLINE)
            appendSystemMessage(
                "You are currently offline. Your message has been saved to local memory.",
                persist = false
            )
            refreshStatusUi()
            return
        }

        activeTaskJob?.cancel()
        activeTaskJob = lifecycleScope.launch {
            updateState(AgentState.THINKING)
            refreshStatusUi()

            val isAutomation = AgentPlanner.isAutomationIntent(prompt)
            val isResearch = !isAutomation && WebResearchClient.isResearchIntent(prompt)

            val screenObs = AgentAccessibilityService.instance?.observeCurrentScreen()
            val screenshotBase64 = if (isScreenSharing && ScreenCaptureManager.isSharingActive()) {
                ScreenCaptureManager.captureThrottledFrameBase64()
            } else {
                null
            }

            val memoryContext = memoryRepo.buildRelevantMemoryContext(prompt)
            val contextMessages = mutableListOf<ChatMessage>()
            if (memoryContext.isNotBlank()) {
                contextMessages.add(
                    ChatMessage(
                        id = 0L,
                        role = ChatMessage.Role.SYSTEM,
                        text = "Use the following retrieved local memory records when answering questions about the past. If a requested past fact is not in these records, state honestly that it is not in memory:\n$memoryContext"
                    )
                )
            }

            var citationsFooter = ""
            if (isResearch) {
                val researchBundle = webResearchClient.performBoundedSearch(prompt)
                contextMessages.add(
                    ChatMessage(
                        id = 0L,
                        role = ChatMessage.Role.SYSTEM,
                        text = researchBundle.toPromptContextString()
                    )
                )
                if (researchBundle.sources.isNotEmpty()) {
                    citationsFooter = "\n\nSources:\n" +
                        researchBundle.sources.joinToString("\n") { "• ${it.title}: ${it.url}" }
                }
            }

            if (isAutomation || isScreenSharing) {
                val codingAllowed = prefs.isAiCodingCapabilityEnabled()
                val videoAllowed = prefs.isAiVideoEditCapabilityEnabled()
                contextMessages.add(
                    ChatMessage(
                        id = 0L,
                        role = ChatMessage.Role.SYSTEM,
                        text = AgentPlanner.buildPlannerSystemPrompt(
                            screenObservation = screenObs,
                            codingEnabled = codingAllowed,
                            videoEditingEnabled = videoAllowed
                        )
                    )
                )
            }

            val recentWindow = messages
                .filter { it.role != ChatMessage.Role.SYSTEM }
                .takeLast(8)
            contextMessages.addAll(recentWindow)

            val routed = AgentApp.instance.aiClientRouter.sendMessageWithFailover(
                messages = contextMessages,
                base64JpegImage = screenshotBase64
            )

            if (!routed.success) {
                appendSystemMessage(routed.replyText, persist = false)
                updateState(AgentState.ERROR)
                refreshStatusUi()
                return@launch
            }

            if (isAutomation) {
                val plan = AgentPlanner.parseAndValidatePlan(routed.replyText)
                if (plan.actions.isNotEmpty()) {
                    updateState(AgentState.ACTING)
                    refreshStatusUi()
                    val stepResults = actionExecutor.executePlan(
                        plan = plan,
                        isCancelled = { activeTaskJob?.isCancelled == true }
                    )
                    val summaryReport = buildString {
                        if (plan.thoughtBn.isNotBlank()) {
                            appendLine("Plan: ${plan.thoughtBn}")
                        }
                        stepResults.forEachIndexed { i, r ->
                            val statusIcon = if (r.verifiedSuccess) "✓" else "✗"
                            appendLine("${i + 1}. [$statusIcon ${r.action.tool.name}] ${r.observationNoteBn}")
                        }
                    }.trim()

                    val allSucceeded = stepResults.isNotEmpty() && stepResults.all { it.verifiedSuccess }
                    memoryRepo.recordTaskOutcome(
                        goal = prompt,
                        status = if (allSucceeded) "COMPLETED" else "FAILED",
                        outcome = summaryReport,
                        steps = stepResults.size
                    )

                    val assistantMsg = ChatMessage(
                        id = System.currentTimeMillis(),
                        role = ChatMessage.Role.ASSISTANT,
                        text = summaryReport,
                        providerUsed = routed.providerUsed?.displayName
                    )
                    messages.add(assistantMsg)
                    memoryRepo.saveChatMessage(assistantMsg)
                    if (prefs.isTtsAutoSpeakEnabled()) {
                        voiceManager.speak(summaryReport)
                    }
                    updateState(AgentState.READY)
                    transcriptAdapter.submitList(messages)
                    binding.rvTranscript.scrollToPosition(messages.size - 1)
                    refreshStatusUi()
                    return@launch
                }
            }

            val baseReply = if (isAutomation) {
                AgentPlanner.parseAndValidatePlan(routed.replyText)
                    .directReplyBn.ifBlank { routed.replyText }
            } else {
                routed.replyText
            }
            val finalReply = if (citationsFooter.isNotBlank() && !baseReply.contains("http")) {
                baseReply + citationsFooter
            } else {
                baseReply
            }

            val assistantMsg = ChatMessage(
                id = System.currentTimeMillis(),
                role = ChatMessage.Role.ASSISTANT,
                text = finalReply,
                providerUsed = routed.providerUsed?.displayName
            )
            messages.add(assistantMsg)
            memoryRepo.saveChatMessage(assistantMsg)
            updateState(AgentState.READY)

            if (prefs.isTtsAutoSpeakEnabled()) {
                voiceManager.speak(baseReply)
            }
            transcriptAdapter.submitList(messages)
            binding.rvTranscript.scrollToPosition(messages.size - 1)
            refreshStatusUi()
        }
    }

    private suspend fun askUserConfirmationForSensitiveAction(action: PlannedAction): Boolean =
        suspendCoroutine { cont ->
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle("Confirm Sensitive Action (${action.riskLevel.name})")
                    .setMessage(
                        "The assistant is requesting permission to execute:\n\n" +
                            "Action: ${action.tool.name}\n" +
                            "Target: ${action.targetLabel.ifBlank { action.textToType }}\n" +
                            "Reason: ${action.reasoningBn}\n\n" +
                            "Do you approve this action?"
                    )
                    .setCancelable(false)
                    .setPositiveButton("Approve") { _, _ -> cont.resume(true) }
                    .setNegativeButton("Cancel") { _, _ -> cont.resume(false) }
                    .show()
            }
        }

    private fun stopAllCaptureAndTasks(reasonEn: String) {
        activeTaskJob?.cancel()
        activeTaskJob = null
        voiceManager.stopListeningImmediately()
        voiceManager.stopSpeaking()
        ScreenCaptureManager.stopProjection()
        isMicMuted = true
        isScreenSharing = false
        stopService(Intent(this, AgentForegroundService::class.java))
        updateState(AgentState.READY)
        appendSystemMessage(reasonEn, persist = false)
    }

    private fun updateState(newState: AgentState) {
        currentState = newState
    }

    private fun refreshStatusUi() {
        binding.tvAgentState.text = currentState.labelEn
        binding.btnToggleAssistant.text =
            if (isAssistantEnabled) "Active" else "Start"
        binding.btnPauseResume.text =
            if (currentState == AgentState.PAUSED) "Resume" else "Pause"
        binding.btnScreenToggle.text =
            if (isScreenSharing) "Screen: On" else "Screen: Off"
        binding.btnMicToggle.text = if (isMicMuted) "🎤 Mic" else "🔴 Live"

        val micStatus = if (isMicMuted) "Mic Off" else "Mic Live"
        val screenStatus = if (isScreenSharing) "Screen Live" else "Screen Off"
        binding.tvPrivacyIndicator.text = "$micStatus | $screenStatus"
    }

    private fun appendSystemMessage(text: String, persist: Boolean) {
        val sysMsg = ChatMessage(
            id = System.currentTimeMillis(),
            role = ChatMessage.Role.SYSTEM,
            text = text
        )
        messages.add(sysMsg)
        transcriptAdapter.submitList(messages)
        binding.rvTranscript.scrollToPosition(messages.size - 1)
        if (persist) {
            lifecycleScope.launch { memoryRepo.saveChatMessage(sysMsg) }
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun onDestroy() {
        voiceManager.releaseAll()
        super.onDestroy()
    }
}
