package com.personal.bengaliagent.ui.main

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.personal.bengaliagent.AgentApp
import com.personal.bengaliagent.R
import com.personal.bengaliagent.databinding.ActivityMainBinding
import com.personal.bengaliagent.domain.model.AgentState
import com.personal.bengaliagent.domain.model.ProviderType
import com.personal.bengaliagent.screen.ScreenCaptureManager
import com.personal.bengaliagent.service.AgentForegroundService
import com.personal.bengaliagent.service.AgentSessionBus
import com.personal.bengaliagent.ui.settings.ProviderSettingsActivity
import com.personal.bengaliagent.ui.setup.SetupWizardActivity
import com.personal.bengaliagent.voice.RealtimeVoiceCallEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var callEngine: RealtimeVoiceCallEngine

    private val prefs by lazy { AgentApp.instance.appPreferences }
    private val keyStore by lazy { AgentApp.instance.secureKeyStore }
    private val router by lazy { AgentApp.instance.aiClientRouter }

    private var currentState: AgentState = AgentState.READY
    private var callTimerJob: Job? = null
    private var callStartTimestampMs: Long = 0L
    private var ambientOrbAnimator: ObjectAnimator? = null
    private var callingPulseAnimator: ObjectAnimator? = null

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            initiateRealTimeCall()
        } else {
            Toast.makeText(
                this,
                "Microphone permission is required for real-time AI calling.",
                Toast.LENGTH_SHORT
            ).show()
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
            refreshScreenShareUi(true)
        } else {
            refreshScreenShareUi(false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        callEngine = RealtimeVoiceCallEngine(
            context = this,
            scope = lifecycleScope,
            router = router,
            onCallStateChanged = { state, detail ->
                handleEngineStateTransition(state, detail)
            },
            onAudioLevelUpdate = { level ->
                animateActiveCallOrbWithAudioLevel(level)
            }
        )

        ScreenCaptureManager.onProjectionStoppedListener = {
            runOnUiThread { refreshScreenShareUi(false) }
        }

        observeSessionBusCommands()

        lifecycleScope.launch {
            val completed = prefs.isSetupCompleted.first()
            if (!completed) {
                startActivity(Intent(this@MainActivity, SetupWizardActivity::class.java))
            }
        }

        setupCallControls()
        startAmbientHomeOrbBreathing()
    }

    override fun onResume() {
        super.onResume()
        refreshHomeProviderSummary()
        refreshScreenShareUi(ScreenCaptureManager.isSharingActive())
    }

    private fun refreshHomeProviderSummary() {
        lifecycleScope.launch {
            val connectedList = router.getConnectedProviders()
            if (connectedList.isNotEmpty()) {
                val badgeText = if (connectedList.size > 1) {
                    "Multi-AI Active: " + connectedList.joinToString(" + ") { it.displayName }
                } else {
                    val single = connectedList.first()
                    val m = prefs.getVerifiedModel(single) ?: "Ready"
                    "${single.displayName} • $m"
                }
                binding.tvSelectedProviderModel.text = badgeText
                binding.tvAgentState.text = "Connected • Ready (${connectedList.size} AI)"
                binding.tvAgentState.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_green))
                binding.tvHomeConnectionSubtitle.text =
                    "Tap Call AI to talk with your active AI agent(s) in real time"
            } else {
                binding.tvSelectedProviderModel.text = "No Provider Connected • Tap Settings"
                binding.tvAgentState.text = "Disconnected"
                binding.tvAgentState.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_amber))
                binding.tvHomeConnectionSubtitle.text =
                    "Open Settings in the top-right corner to connect Gemini, Groq, OpenRouter, or OpenAI"
            }
        }
    }

    private fun setupCallControls() {
        binding.btnOpenSettings.setOnClickListener {
            startActivity(Intent(this, ProviderSettingsActivity::class.java))
        }

        binding.btnStartAiCall.setOnClickListener {
            checkMicAndStartCall()
        }

        binding.flHomeOrbContainer.setOnClickListener {
            checkMicAndStartCall()
        }

        binding.btnCancelCalling.setOnClickListener {
            endActiveCall()
        }

        binding.btnMicToggle.setOnClickListener {
            val newMuted = !callEngine.isMuted()
            callEngine.setMuted(newMuted)
            updateMuteControlUi(newMuted)
        }

        binding.btnScreenToggle.setOnClickListener {
            if (ScreenCaptureManager.isSharingActive()) {
                ScreenCaptureManager.stopProjection()
                refreshScreenShareUi(false)
            } else {
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjectionLauncher.launch(mpm.createScreenCaptureIntent())
            }
        }

        binding.btnSpeakerToggle.setOnClickListener {
            val newSpeaker = !callEngine.isSpeakerphoneOn()
            callEngine.setSpeakerphone(newSpeaker)
            updateSpeakerControlUi(newSpeaker)
        }

        binding.btnStopTask.setOnClickListener {
            endActiveCall()
        }
    }

    private fun checkMicAndStartCall() {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            initiateRealTimeCall()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun initiateRealTimeCall() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, AgentForegroundService::class.java)
        )
        showCallingTransitionScreen()
        callEngine.startCall()
    }

    private fun endActiveCall() {
        callEngine.endCall()
        stopCallDurationTimer()
        ScreenCaptureManager.stopProjection()
        stopService(Intent(this, AgentForegroundService::class.java))
        showHomeIdleScreen()
    }

    private fun handleEngineStateTransition(state: AgentState, detail: String) {
        currentState = state
        when (state) {
            AgentState.CALLING,
            AgentState.CONNECTING,
            AgentState.RECONNECTING -> {
                showCallingTransitionScreen()
                binding.tvCallingStatusLabel.text = state.labelEn
                binding.tvCallingModelInfo.text = detail
            }
            AgentState.CONNECTED -> {
                showActiveCallScreen()
                binding.tvActiveCallQuality.text = detail
                binding.tvActiveCallState.text = "Connected"
                startCallDurationTimer()
            }
            AgentState.LISTENING,
            AgentState.THINKING,
            AgentState.SPEAKING,
            AgentState.ACTING,
            AgentState.MUTED -> {
                if (binding.layoutActiveCall.visibility != View.VISIBLE) {
                    showActiveCallScreen()
                    startCallDurationTimer()
                }
                binding.tvActiveCallState.text = detail.ifBlank { state.labelEn }
                val colorRes = when (state) {
                    AgentState.SPEAKING -> R.color.accent_green
                    AgentState.ACTING, AgentState.THINKING -> R.color.accent_glow
                    AgentState.MUTED -> R.color.accent_amber
                    else -> R.color.accent_cyan
                }
                binding.tvActiveCallState.setTextColor(ContextCompat.getColor(this, colorRes))
            }
            AgentState.CALL_ENDED -> {
                stopCallDurationTimer()
                showHomeIdleScreen()
                binding.tvHomeConnectionSubtitle.text = "Call ended"
            }
            AgentState.ERROR,
            AgentState.OFFLINE -> {
                stopCallDurationTimer()
                showHomeIdleScreen()
                binding.tvAgentState.text = "Connection Failed"
                binding.tvAgentState.setTextColor(ContextCompat.getColor(this, R.color.accent_red_bright))
                binding.tvHomeConnectionSubtitle.text = detail
            }
            else -> {
                showHomeIdleScreen()
            }
        }
    }

    private fun showHomeIdleScreen() {
        callingPulseAnimator?.cancel()
        binding.layoutCallingTransition.visibility = View.GONE
        binding.layoutActiveCall.visibility = View.GONE
        binding.layoutHomeIdle.visibility = View.VISIBLE
        startAmbientHomeOrbBreathing()
        refreshHomeProviderSummary()
    }

    private fun showCallingTransitionScreen() {
        ambientOrbAnimator?.cancel()
        binding.layoutHomeIdle.visibility = View.GONE
        binding.layoutActiveCall.visibility = View.GONE
        binding.layoutCallingTransition.visibility = View.VISIBLE

        callingPulseAnimator?.cancel()
        callingPulseAnimator = ObjectAnimator.ofPropertyValuesHolder(
            binding.viewCallingPulseRing,
            PropertyValuesHolder.ofFloat("scaleX", 0.85f, 1.25f, 0.85f),
            PropertyValuesHolder.ofFloat("scaleY", 0.85f, 1.25f, 0.85f),
            PropertyValuesHolder.ofFloat("alpha", 0.9f, 0.3f, 0.9f)
        ).apply {
            duration = 1400L
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun showActiveCallScreen() {
        ambientOrbAnimator?.cancel()
        callingPulseAnimator?.cancel()
        binding.layoutHomeIdle.visibility = View.GONE
        binding.layoutCallingTransition.visibility = View.GONE
        binding.layoutActiveCall.visibility = View.VISIBLE
        updateMuteControlUi(callEngine.isMuted())
        updateSpeakerControlUi(callEngine.isSpeakerphoneOn())
        refreshScreenShareUi(ScreenCaptureManager.isSharingActive())
    }

    private fun startCallDurationTimer() {
        if (callTimerJob?.isActive == true) return
        callStartTimestampMs = System.currentTimeMillis()
        callTimerJob = lifecycleScope.launch {
            while (isActive && callEngine.isCallRunning()) {
                val elapsedSec = ((System.currentTimeMillis() - callStartTimestampMs) / 1000L).coerceAtLeast(0L)
                val mins = elapsedSec / 60L
                val secs = elapsedSec % 60L
                binding.tvCallDurationTimer.text =
                    String.format(Locale.US, "%02d:%02d", mins, secs)
                delay(1000L)
            }
        }
    }

    private fun stopCallDurationTimer() {
        callTimerJob?.cancel()
        callTimerJob = null
        binding.tvCallDurationTimer.text = "00:00"
    }

    private fun animateActiveCallOrbWithAudioLevel(level: Float) {
        if (binding.layoutActiveCall.visibility != View.VISIBLE) return
        val outerScale = 1.0f + (level * 0.45f)
        val coreScale = 1.0f + (level * 0.22f)
        binding.viewActiveOrbOuter.scaleX = outerScale
        binding.viewActiveOrbOuter.scaleY = outerScale
        binding.viewActiveOrbCore.scaleX = coreScale
        binding.viewActiveOrbCore.scaleY = coreScale
    }

    private fun startAmbientHomeOrbBreathing() {
        ambientOrbAnimator?.cancel()
        ambientOrbAnimator = ObjectAnimator.ofPropertyValuesHolder(
            binding.viewHomeOrbOuter,
            PropertyValuesHolder.ofFloat("scaleX", 0.95f, 1.08f, 0.95f),
            PropertyValuesHolder.ofFloat("scaleY", 0.95f, 1.08f, 0.95f)
        ).apply {
            duration = 2800L
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun updateMuteControlUi(muted: Boolean) {
        if (muted) {
            binding.btnMicToggle.setBackgroundResource(R.drawable.bg_circle_call_end)
            binding.btnMicToggle.setImageResource(R.drawable.ic_mic_off)
            binding.tvMicToggleLabel.text = "Unmute"
        } else {
            binding.btnMicToggle.setBackgroundResource(R.drawable.bg_circle_control_idle)
            binding.btnMicToggle.setImageResource(R.drawable.ic_mic_on)
            binding.tvMicToggleLabel.text = "Mute"
        }
    }

    private fun updateSpeakerControlUi(speakerOn: Boolean) {
        if (speakerOn) {
            binding.btnSpeakerToggle.setBackgroundResource(R.drawable.bg_circle_control_active)
            binding.tvSpeakerToggleLabel.text = "Speaker On"
        } else {
            binding.btnSpeakerToggle.setBackgroundResource(R.drawable.bg_circle_control_idle)
            binding.tvSpeakerToggleLabel.text = "Earpiece"
        }
    }

    private fun refreshScreenShareUi(sharing: Boolean) {
        if (sharing) {
            binding.btnScreenToggle.setBackgroundResource(R.drawable.bg_circle_control_active)
            binding.tvScreenToggleLabel.text = "Stop Share"
            binding.tvInCallScreenShareBadge.visibility = View.VISIBLE
        } else {
            binding.btnScreenToggle.setBackgroundResource(R.drawable.bg_circle_control_idle)
            binding.tvScreenToggleLabel.text = "Screen"
            binding.tvInCallScreenShareBadge.visibility = View.GONE
        }
    }

    private fun observeSessionBusCommands() {
        lifecycleScope.launch {
            AgentSessionBus.commands.collect { cmd ->
                when (cmd) {
                    AgentSessionBus.Command.TOGGLE_MUTE -> {
                        binding.btnMicToggle.performClick()
                    }
                    AgentSessionBus.Command.TOGGLE_PAUSE -> {
                        binding.btnMicToggle.performClick()
                    }
                    AgentSessionBus.Command.STOP_CURRENT_TASK,
                    AgentSessionBus.Command.DISABLE_SESSION -> {
                        endActiveCall()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        ambientOrbAnimator?.cancel()
        callingPulseAnimator?.cancel()
        callEngine.endCall()
        super.onDestroy()
    }
}
