package com.personal.bengaliagent.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Base64
import com.personal.bengaliagent.agent.AndroidActionManager
import com.personal.bengaliagent.domain.model.AgentState
import com.personal.bengaliagent.domain.model.ProviderType
import com.personal.bengaliagent.network.AiClientRouter
import com.personal.bengaliagent.screen.ScreenCaptureManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * Real-Time Native Audio Voice Calling Engine:
 * - Connects directly to Google Gemini Live API (BidiGenerateContent WebSocket) or xAI Grok Realtime API.
 * - Captures 16kHz 16-bit mono PCM audio from the microphone with hardware AcousticEchoCanceler.
 * - Streams real-time audio chunks and optional live Screen Share JPEG frames over WebSocket.
 * - Plays incoming 24kHz 16-bit mono PCM audio responses directly through low-latency AudioTrack.
 * - Executes Android actions (WhatsApp, YouTube, Flashlight, Settings, Accessibility gestures) via real-time tool calling.
 */
class RealtimeVoiceCallEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val router: AiClientRouter,
    private val onCallStateChanged: (state: AgentState, statusDetail: String) -> Unit,
    private val onAudioLevelUpdate: (levelNormalized: Float) -> Unit
) {

    private val wsHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // Keep WebSocket open indefinitely during call
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var webSocket: WebSocket? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null

    private var micCaptureJob: Job? = null
    private var screenStreamJob: Job? = null
    private var handshakeTimeoutJob: Job? = null

    private val isCallActive = AtomicBoolean(false)
    private val isHandshakeComplete = AtomicBoolean(false)
    private val isMicMuted = AtomicBoolean(false)
    private var isSpeakerphoneEnabled = true

    private var activeProvider: ProviderType = ProviderType.GEMINI
    private var activeModelId: String = "gemini-2.0-flash-exp"

    fun startCall() {
        if (isCallActive.get()) return
        isCallActive.set(true)
        isHandshakeComplete.set(false)
        isMicMuted.set(false)

        onCallStateChanged(AgentState.CALLING, "Initializing real-time voice channel...")

        scope.launch(Dispatchers.IO) {
            val candidates = router.resolveLiveCallCandidates()
            if (candidates.isEmpty()) {
                isCallActive.set(false)
                scope.launch(Dispatchers.Main) {
                    onCallStateChanged(
                        AgentState.ERROR,
                        "No API key configured. Tap Settings to connect Google Gemini or xAI Grok."
                    )
                }
                return@launch
            }

            // Build flat list of (Provider, ApiKey, ModelId) for fast fallback
            val attemptQueue = mutableListOf<Triple<ProviderType, String, String>>()
            for (cfg in candidates) {
                attemptQueue.add(Triple(cfg.provider, cfg.apiKey, cfg.liveModelId))
                for (fb in cfg.fallbackModels) {
                    attemptQueue.add(Triple(cfg.provider, cfg.apiKey, fb))
                }
            }

            connectWithCandidateIndex(attemptQueue, 0)
        }
    }

    private fun connectWithCandidateIndex(
        queue: List<Triple<ProviderType, String, String>>,
        index: Int
    ) {
        if (!isCallActive.get()) return
        if (index >= queue.size) {
            isCallActive.set(false)
            scope.launch(Dispatchers.Main) {
                onCallStateChanged(
                    AgentState.ERROR,
                    "Connection failed: Could not establish real-time audio session. Verify your API key and network."
                )
            }
            return
        }

        val (provider, apiKey, modelId) = queue[index]
        activeProvider = provider
        activeModelId = modelId

        scope.launch(Dispatchers.Main) {
            val state = if (index == 0) AgentState.CONNECTING else AgentState.RECONNECTING
            onCallStateChanged(state, "Connecting to ${provider.displayName} ($modelId)...")
        }

        val req = if (provider == ProviderType.GEMINI) {
            val url = "${provider.realtimeWsUrl}?key=$apiKey"
            Request.Builder()
                .url(url)
                .addHeader("x-goog-api-key", apiKey)
                .build()
        } else {
            Request.Builder()
                .url(provider.realtimeWsUrl)
                .addHeader("Authorization", "Bearer $apiKey")
                .build()
        }

        // 5-second fast handshake timeout per candidate
        handshakeTimeoutJob?.cancel()
        handshakeTimeoutJob = scope.launch(Dispatchers.IO) {
            delay(5500L)
            if (isCallActive.get() && !isHandshakeComplete.get()) {
                try {
                    webSocket?.cancel()
                } catch (_: Exception) {
                }
                connectWithCandidateIndex(queue, index + 1)
            }
        }

        webSocket = wsHttpClient.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!isCallActive.get()) {
                    webSocket.close(1000, "Call ended")
                    return
                }
                if (provider == ProviderType.GEMINI) {
                    sendGeminiLiveSetup(webSocket, modelId)
                } else {
                    sendGrokRealtimeSetup(webSocket)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingServerJson(webSocket, provider, text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                handleIncomingServerJson(webSocket, provider, bytes.utf8())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!isCallActive.get()) return
                if (!isHandshakeComplete.get()) {
                    handshakeTimeoutJob?.cancel()
                    connectWithCandidateIndex(queue, index + 1)
                } else {
                    endCallInternal(
                        AgentState.ERROR,
                        "Call disconnected: ${t.localizedMessage ?: "Network lost"}"
                    )
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!isCallActive.get()) return
                if (!isHandshakeComplete.get() && code != 1000) {
                    handshakeTimeoutJob?.cancel()
                    connectWithCandidateIndex(queue, index + 1)
                } else if (isCallActive.get()) {
                    endCallInternal(AgentState.CALL_ENDED, "Call ended.")
                }
            }
        })
    }

    private fun sendGeminiLiveSetup(ws: WebSocket, modelId: String) {
        val cleanModel = modelId.removePrefix("models/").removePrefix("vertex-v1:")
        val toolDecl = JSONObject()
            .put("name", "execute_phone_action")
            .put(
                "description",
                "Executes a real Android phone action such as opening an app (WhatsApp, YouTube, Chrome, Settings), toggling flashlight, or navigating the screen."
            )
            .put(
                "parameters",
                JSONObject()
                    .put("type", "OBJECT")
                    .put(
                        "properties",
                        JSONObject()
                            .put(
                                "action_type",
                                JSONObject()
                                    .put("type", "STRING")
                                    .put(
                                        "description",
                                        "One of: open_app, open_settings, turn_on_flashlight, turn_off_flashlight, go_back, go_home, scroll_down, scroll_up, tap_element"
                                    )
                            )
                            .put(
                                "target",
                                JSONObject()
                                    .put("type", "STRING")
                                    .put("description", "App name, setting name, or UI element label")
                            )
                    )
                    .put("required", JSONArray().put("action_type"))
            )

        val setupObj = JSONObject().put(
            "setup",
            JSONObject()
                .put("model", "models/$cleanModel")
                .put(
                    "generationConfig",
                    JSONObject().put("responseModalities", JSONArray().put("AUDIO"))
                )
                .put(
                    "systemInstruction",
                    JSONObject().put(
                        "parts",
                        JSONArray().put(
                            JSONObject().put(
                                "text",
                                "You are Bengali AI Agent on a real-time phone call. Speak naturally in Bengali or English matching the user. Keep every response short, direct, and action-first (1 sentence). When asked to open an app, control flashlight, or navigate, call execute_phone_action immediately and say a short confirmation like 'Opening WhatsApp'."
                            )
                        )
                    )
                )
                .put(
                    "tools",
                    JSONArray().put(
                        JSONObject().put("functionDeclarations", JSONArray().put(toolDecl))
                    )
                )
        )
        ws.send(setupObj.toString())
    }

    private fun sendGrokRealtimeSetup(ws: WebSocket) {
        val sessionUpdate = JSONObject()
            .put("type", "session.update")
            .put(
                "session",
                JSONObject()
                    .put("voice", "Ara")
                    .put(
                        "instructions",
                        "You are Bengali AI Agent on a real-time voice call. Speak naturally in Bengali or English matching the user. Keep responses very short, direct, and action-first."
                    )
                    .put("input_audio_format", "pcm16")
                    .put("output_audio_format", "pcm16")
                    .put(
                        "turn_detection",
                        JSONObject().put("type", "server_vad")
                    )
            )
        ws.send(sessionUpdate.toString())
    }

    private fun handleIncomingServerJson(
        ws: WebSocket,
        provider: ProviderType,
        rawText: String
    ) {
        try {
            val root = JSONObject(rawText)

            if (provider == ProviderType.GEMINI) {
                if (root.has("setupComplete")) {
                    onHandshakeSucceeded()
                    return
                }

                // Handle real-time tool / function calls from Gemini Live
                val toolCall = root.optJSONObject("toolCall")
                if (toolCall != null) {
                    val funcCalls = toolCall.optJSONArray("functionCalls")
                    if (funcCalls != null && funcCalls.length() > 0) {
                        val responsesArr = JSONArray()
                        for (i in 0 until funcCalls.length()) {
                            val fc = funcCalls.getJSONObject(i)
                            val callId = fc.optString("id", "")
                            val name = fc.optString("name", "")
                            val args = fc.optJSONObject("args") ?: JSONObject()
                            val actionType = args.optString("action_type", "open_app")
                            val target = args.optString("target", "")

                            scope.launch(Dispatchers.Main) {
                                onCallStateChanged(AgentState.ACTING, "Executing: $actionType $target")
                            }

                            val outcome = AndroidActionManager.executeStructuredToolCall(
                                context,
                                actionType,
                                target
                            )
                            responsesArr.put(
                                JSONObject()
                                    .put("id", callId)
                                    .put("name", name)
                                    .put(
                                        "response",
                                        JSONObject().put("result", outcome.responseMessage)
                                    )
                            )
                        }
                        val toolRespMsg = JSONObject().put(
                            "toolResponse",
                            JSONObject().put("functionResponses", responsesArr)
                        )
                        ws.send(toolRespMsg.toString())
                    }
                    return
                }

                val serverContent = root.optJSONObject("serverContent") ?: return
                if (serverContent.optBoolean("interrupted", false)) {
                    flushPlaybackBuffer()
                    scope.launch(Dispatchers.Main) {
                        onCallStateChanged(
                            if (isMicMuted.get()) AgentState.MUTED else AgentState.LISTENING,
                            "Listening..."
                        )
                    }
                    return
                }

                val modelTurn = serverContent.optJSONObject("modelTurn")
                val parts = modelTurn?.optJSONArray("parts")
                if (parts != null) {
                    for (i in 0 until parts.length()) {
                        val part = parts.getJSONObject(i)
                        val inlineData = part.optJSONObject("inlineData")
                        if (inlineData != null) {
                            val mime = inlineData.optString("mimeType", "")
                            val base64Audio = inlineData.optString("data", "")
                            if (mime.startsWith("audio/pcm") && base64Audio.isNotEmpty()) {
                                val pcmBytes = Base64.decode(base64Audio, Base64.DEFAULT)
                                playIncomingPcm24kHz(pcmBytes)
                            }
                        }
                    }
                }

                if (serverContent.optBoolean("turnComplete", false)) {
                    scope.launch(Dispatchers.Main) {
                        onAudioLevelUpdate(0.05f)
                        onCallStateChanged(
                            if (isMicMuted.get()) AgentState.MUTED else AgentState.LISTENING,
                            "Listening..."
                        )
                    }
                }
            } else {
                // xAI Grok Realtime protocol events
                val eventType = root.optString("type", "")
                when (eventType) {
                    "session.created", "session.updated", "conversation.created" -> {
                        if (!isHandshakeComplete.get()) {
                            onHandshakeSucceeded()
                        }
                    }
                    "response.output_audio.delta", "response.audio.delta" -> {
                        val deltaB64 = root.optString("delta", "")
                        if (deltaB64.isNotEmpty()) {
                            val pcmBytes = Base64.decode(deltaB64, Base64.DEFAULT)
                            playIncomingPcm24kHz(pcmBytes)
                        }
                    }
                    "input_audio_buffer.speech_started" -> {
                        flushPlaybackBuffer()
                        scope.launch(Dispatchers.Main) {
                            onCallStateChanged(AgentState.LISTENING, "Listening...")
                        }
                    }
                    "response.done" -> {
                        scope.launch(Dispatchers.Main) {
                            onAudioLevelUpdate(0.05f)
                            onCallStateChanged(
                                if (isMicMuted.get()) AgentState.MUTED else AgentState.LISTENING,
                                "Listening..."
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun onHandshakeSucceeded() {
        if (isHandshakeComplete.getAndSet(true)) return
        handshakeTimeoutJob?.cancel()

        configureCallAudioRouting()
        startAudioTrackPlayback()
        startMicrophonePcmStreaming()
        startScreenShareFrameStreamingLoop()

        scope.launch(Dispatchers.Main) {
            onCallStateChanged(
                AgentState.CONNECTED,
                "${activeProvider.displayName} • $activeModelId"
            )
            delay(400L)
            if (isCallActive.get()) {
                onCallStateChanged(
                    if (isMicMuted.get()) AgentState.MUTED else AgentState.LISTENING,
                    "Listening..."
                )
            }
        }
    }

    private fun configureCallAudioRouting() {
        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = isSpeakerphoneEnabled
        } catch (_: Exception) {
        }
    }

    private fun startAudioTrackPlayback() {
        val sampleRate = 24000 // Gemini Live & xAI Realtime output 24kHz PCM16
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .apply { play() }
    }

    private fun playIncomingPcm24kHz(pcmBytes: ByteArray) {
        val track = audioTrack ?: return
        try {
            scope.launch(Dispatchers.Main) {
                onCallStateChanged(AgentState.SPEAKING, "AI Speaking...")
            }
            // Compute RMS amplitude of AI voice for the Audio-Reactive Orb
            val rms = computePcmBytesRms(pcmBytes)
            scope.launch(Dispatchers.Main) {
                onAudioLevelUpdate(rms)
            }
            track.write(pcmBytes, 0, pcmBytes.size)
        } catch (_: Exception) {
        }
    }

    private fun flushPlaybackBuffer() {
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.play()
        } catch (_: Exception) {
        }
    }

    @SuppressLint("MissingPermission")
    private fun startMicrophonePcmStreaming() {
        val sampleRate = 16000
        val chunkSizeSamples = 1600 // 100ms chunks at 16kHz
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(chunkSizeSamples * 2)

        micCaptureJob?.cancel()
        micCaptureJob = scope.launch(Dispatchers.IO) {
            try {
                val recorder = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 2
                )
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    return@launch
                }
                audioRecord = recorder

                // Attach hardware Acoustic Echo Canceler & Noise Suppressor if supported
                if (AcousticEchoCanceler.isAvailable()) {
                    echoCanceler = AcousticEchoCanceler.create(recorder.audioSessionId)?.apply {
                        enabled = true
                    }
                }
                if (NoiseSuppressor.isAvailable()) {
                    noiseSuppressor = NoiseSuppressor.create(recorder.audioSessionId)?.apply {
                        enabled = true
                    }
                }

                val shortBuffer = ShortArray(chunkSizeSamples)
                val byteBuffer = ByteArray(chunkSizeSamples * 2)
                recorder.startRecording()

                while (isActive && isCallActive.get()) {
                    if (isMicMuted.get()) {
                        delay(80L)
                        continue
                    }

                    val shortsRead = recorder.read(shortBuffer, 0, chunkSizeSamples)
                    if (shortsRead > 0) {
                        var sumSq = 0.0
                        val bb = ByteBuffer.wrap(byteBuffer).order(ByteOrder.LITTLE_ENDIAN)
                        for (i in 0 until shortsRead) {
                            val s = shortBuffer[i]
                            bb.putShort(s)
                            val norm = s / 32768.0
                            sumSq += norm * norm
                        }
                        val rms = sqrt(sumSq / shortsRead).toFloat().coerceIn(0f, 1f)
                        scope.launch(Dispatchers.Main) {
                            onAudioLevelUpdate((rms * 3.5f).coerceIn(0.05f, 1.0f))
                        }

                        val b64 = Base64.encodeToString(
                            byteBuffer,
                            0,
                            shortsRead * 2,
                            Base64.NO_WRAP
                        )
                        sendPcmChunkOverWebSocket(b64)
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun sendPcmChunkOverWebSocket(base64Pcm: String) {
        val ws = webSocket ?: return
        if (!isHandshakeComplete.get()) return
        if (activeProvider == ProviderType.GEMINI) {
            val chunkObj = JSONObject().put(
                "realtimeInput",
                JSONObject().put(
                    "mediaChunks",
                    JSONArray().put(
                        JSONObject()
                            .put("mimeType", "audio/pcm;rate=16000")
                            .put("data", base64Pcm)
                    )
                )
            )
            ws.send(chunkObj.toString())
        } else {
            val appendObj = JSONObject()
                .put("type", "input_audio_buffer.append")
                .put("audio", base64Pcm)
            ws.send(appendObj.toString())
        }
    }

    /**
     * When Screen Share is enabled during the active call, streams optimized JPEG frames
     * over the live WebSocket so the AI can see and answer questions about the screen in real time.
     */
    private fun startScreenShareFrameStreamingLoop() {
        screenStreamJob?.cancel()
        screenStreamJob = scope.launch(Dispatchers.IO) {
            while (isActive && isCallActive.get()) {
                delay(2500L)
                if (!isHandshakeComplete.get() || !ScreenCaptureManager.isSharingActive()) continue
                val frameB64 = ScreenCaptureManager.captureThrottledFrameBase64() ?: continue
                val ws = webSocket ?: continue

                if (activeProvider == ProviderType.GEMINI) {
                    val imgChunk = JSONObject().put(
                        "realtimeInput",
                        JSONObject().put(
                            "mediaChunks",
                            JSONArray().put(
                                JSONObject()
                                    .put("mimeType", "image/jpeg")
                                    .put("data", frameB64)
                            )
                        )
                    )
                    ws.send(imgChunk.toString())
                }
            }
        }
    }

    fun setMuted(muted: Boolean) {
        isMicMuted.set(muted)
        if (isCallActive.get() && isHandshakeComplete.get()) {
            onCallStateChanged(
                if (muted) AgentState.MUTED else AgentState.LISTENING,
                if (muted) "Microphone Muted" else "Listening..."
            )
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
        endCallInternal(AgentState.CALL_ENDED, "Call ended")
    }

    private fun endCallInternal(finalState: AgentState, message: String) {
        if (!isCallActive.getAndSet(false)) return
        isHandshakeComplete.set(false)
        handshakeTimeoutJob?.cancel()
        micCaptureJob?.cancel()
        screenStreamJob?.cancel()

        try {
            webSocket?.close(1000, "User ended call")
        } catch (_: Exception) {
        }
        webSocket = null

        try {
            echoCanceler?.release()
            noiseSuppressor?.release()
        } catch (_: Exception) {
        }
        echoCanceler = null
        noiseSuppressor = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {
        }
        audioTrack = null

        try {
            audioManager.mode = AudioManager.MODE_NORMAL
        } catch (_: Exception) {
        }

        scope.launch(Dispatchers.Main) {
            onAudioLevelUpdate(0f)
            onCallStateChanged(finalState, message)
        }
    }

    private fun computePcmBytesRms(bytes: ByteArray): Float {
        if (bytes.size < 4) return 0.1f
        val shortCount = bytes.size / 2
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var sumSq = 0.0
        for (i in 0 until shortCount) {
            val s = bb.short / 32768.0
            sumSq += s * s
        }
        return (sqrt(sumSq / shortCount).toFloat() * 3.2f).coerceIn(0.08f, 1.0f)
    }
}
