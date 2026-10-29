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
import com.personal.bengaliagent.ui.coding.CodingProjectActivity
import com.personal.bengaliagent.ui.media.MediaVideoStudioActivity
import com.personal.bengaliagent.ui.memory.MemorySettingsActivity
import com.personal.bengaliagent.ui.settings.ProviderSettingsActivity
import com.personal.bengaliagent.ui.setup.SetupWizardActivity
import com.personal.bengaliagent.ui.voice.VoiceSettingsActivity
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
            Toast.makeText(this, "ভয়েস ইনপুটের জন্য মাইক্রোফোন অনুমতি প্রয়োজন", Toast.LENGTH_SHORT).show()
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
                "স্ক্রিন শেয়ারিং চালু হয়েছে। স্ক্রিন ফ্রেম শুধুমাত্র প্রয়োজনে এবং কম রেজোলিউশনে পর্যবেক্ষণ করা হবে। যেকোনো সময় 'থামুন (Stop)' চাপলে এটি বন্ধ হবে।",
                persist = false
            )
            refreshStatusUi()
        } else {
            isScreenSharing = false
            appendSystemMessage("স্ক্রিন শেয়ারিং অনুমতি বাতিল করা হয়েছে।", persist = false)
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
            onStatusUpdate = { statusBn ->
                appendSystemMessage(statusBn, persist = false)
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
                    "স্বাগতম! আমি আপনার বাংলা এআই সহকারী। আপনি কথা বলে বা লিখে প্রশ্ন করতে পারেন, অনলাইনে তথ্য খুঁজতে পারেন এবং অনুমোদিত ফোন অ্যাকশন চালাতে পারেন।",
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
                        stopAllCaptureAndTasks("কন্ট্রোল বার থেকে চলমান কাজ থামানো হয়েছে।")
                        refreshStatusUi()
                    }
                    AgentSessionBus.Command.DISABLE_SESSION -> {
                        isAssistantEnabled = false
                        stopAllCaptureAndTasks("সেশন সম্পূর্ণ বন্ধ করা হয়েছে।")
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

        binding.btnSetupWizard.setOnClickListener {
            startActivity(Intent(this, SetupWizardActivity::class.java))
        }

        binding.btnMemoryBrain.setOnClickListener {
            startActivity(Intent(this, MemorySettingsActivity::class.java))
        }

        binding.btnVoiceSettings.setOnClickListener {
            startActivity(Intent(this, VoiceSettingsActivity::class.java))
        }

        binding.btnMediaStudio.setOnClickListener {
            startActivity(Intent(this, MediaVideoStudioActivity::class.java))
        }

        binding.btnCodingStudio.setOnClickListener {
            startActivity(Intent(this, CodingProjectActivity::class.java))
        }

        binding.btnToggleAssistant.setOnClickListener {
            isAssistantEnabled = !isAssistantEnabled
            if (!isAssistantEnabled) {
                stopAllCaptureAndTasks("সহকারী বন্ধ করা হয়েছে। মাইক্রোফোন ও স্ক্রিন ক্যাপচার সম্পূর্ণ বন্ধ।")
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
                    "এজেন্ট বিরতি (Paused) মোডে গেছে। অডিও এবং স্ক্রিন ক্যাপচার বন্ধ করা হয়েছে।",
                    persist = false
                )
            }
            refreshStatusUi()
        }

        binding.btnStopTask.setOnClickListener {
            stopAllCaptureAndTasks("ব্যবহারকারী কাজটি থামিয়ে দিয়েছেন (Stopped)।")
            refreshStatusUi()
        }

        binding.btnMicToggle.setOnClickListener {
            if (currentState == AgentState.PAUSED) {
                Toast.makeText(this, "প্রথমে বিরতি (Pause) আনপজ করুন", Toast.LENGTH_SHORT).show()
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
                appendSystemMessage("স্ক্রিন শেয়ারিং বন্ধ করা হয়েছে।", persist = false)
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
            stopAllCaptureAndTasks("আপনার নির্দেশে চলমান কাজ তাৎক্ষণিক বন্ধ করা হয়েছে।")
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
                "আপনি বর্তমানে অফলাইনে আছেন। তবে আপনার বার্তাটি লোকাল মেমোরিতে সংরক্ষিত হয়েছে।",
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
                        text = "নিচের সংরক্ষিত মেমোরি থেকে প্রাসঙ্গিক তথ্য ব্যবহার করে উত্তর দাও। তথ্য না থাকলে বানিয়ে বলবে না:\n$memoryContext"
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
                    citationsFooter = "\n\nঅনলাইন সূত্র (Sources):\n" +
                        researchBundle.sources.joinToString("\n") { "• ${it.title}: ${it.url}" }
                }
            }

            if (isAutomation || isScreenSharing) {
                contextMessages.add(
                    ChatMessage(
                        id = 0L,
                        role = ChatMessage.Role.SYSTEM,
                        text = AgentPlanner.buildPlannerSystemPrompt(screenObs)
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
                            appendLine("পরিকল্পনা: ${plan.thoughtBn}")
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
                        providerUsed = "${routed.providerUsed?.displayName} (${routed.modelUsed})"
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
                providerUsed = "${routed.providerUsed?.displayName} (${routed.modelUsed})"
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
                    .setTitle("সংবেদনশীল কাজের অনুমোদন প্রয়োজন (${action.riskLevel.name})")
                    .setMessage(
                        "এজেন্ট নিচের কাজটি করতে চাচ্ছে:\n\n" +
                            "টুল: ${action.tool.name}\n" +
                            "লক্ষ্য: ${action.targetLabel.ifBlank { action.textToType }}\n" +
                            "কারণ: ${action.reasoningBn}\n\n" +
                            "আপনি কি এটি অনুমোদন করছেন?"
                    )
                    .setCancelable(false)
                    .setPositiveButton("হ্যাঁ, অনুমোদন করুন") { _, _ -> cont.resume(true) }
                    .setNegativeButton("না, বাতিল করুন") { _, _ -> cont.resume(false) }
                    .show()
            }
        }

    private fun stopAllCaptureAndTasks(reasonBn: String) {
        activeTaskJob?.cancel()
        activeTaskJob = null
        voiceManager.stopListeningImmediately()
        voiceManager.stopSpeaking()
        ScreenCaptureManager.stopProjection()
        isMicMuted = true
        isScreenSharing = false
        stopService(Intent(this, AgentForegroundService::class.java))
        updateState(AgentState.READY)
        appendSystemMessage(reasonBn, persist = false)
    }

    private fun updateState(newState: AgentState) {
        currentState = newState
    }

    private fun refreshStatusUi() {
        binding.tvAgentState.text = currentState.labelBn
        binding.btnToggleAssistant.text = if (isAssistantEnabled) "সহকারী: চালু" else "সহকারী: বন্ধ"
        binding.btnPauseResume.text =
            if (currentState == AgentState.PAUSED) "পুনরায় শুরু (Resume)" else "বিরতি (Pause)"
        binding.btnScreenToggle.text =
            if (isScreenSharing) "স্ক্রিন ভিউ: চালু" else "স্ক্রিন ভিউ: বন্ধ"
        binding.btnMicToggle.text = if (isMicMuted) "🔇" else "🎤"

        val micStatus = if (isMicMuted) "মাইক বন্ধ" else "মাইক চালু"
        val screenStatus = if (isScreenSharing) "🟢 স্ক্রিন চালু" else "স্ক্রিন বন্ধ"
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
