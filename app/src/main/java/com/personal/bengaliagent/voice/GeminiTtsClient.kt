package com.personal.bengaliagent.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
import com.personal.bengaliagent.domain.model.FemaleVoicePersona
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Synthesizes natural, expressive female speech using Gemini's TTS models:
 * Primary: gemini-3.8-flash-tts -> Fallback: gemini-3.8-flash-lite-tts -> gemini-2.5-flash-preview-tts
 * Supports official Female Voice Library personas: Aoede, Callirrhoe, Despina, Autonoe, Achernar, Erinome, Vindemiatrix.
 */
class GeminiTtsClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
) {

    private val ttsModelsPriority = listOf(
        "gemini-3.8-flash-tts",
        "gemini-3.8-flash-lite-tts",
        "gemini-2.5-flash-preview-tts"
    )

    suspend fun synthesizeAndPlayIfSupported(
        apiKey: String,
        text: String,
        voicePersona: FemaleVoicePersona
    ): Boolean = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isEmpty() || text.isBlank()) return@withContext false

        val payload = JSONObject()
            .put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", text))
                    )
                )
            )
            .put(
                "generationConfig",
                JSONObject()
                    .put("responseModalities", JSONArray().put("AUDIO"))
                    .put(
                        "speechConfig",
                        JSONObject().put(
                            "voiceConfig",
                            JSONObject().put(
                                "prebuiltVoiceConfig",
                                JSONObject().put("voiceName", voicePersona.voiceId)
                            )
                        )
                    )
            )

        val mediaType = "application/json; charset=utf-8".toMediaType()

        for (ttsModel in ttsModelsPriority) {
            val url =
                "https://generativelanguage.googleapis.com/v1beta/models/$ttsModel:generateContent?key=$cleanKey"
            val req = Request.Builder()
                .url(url)
                .addHeader("x-goog-api-key", cleanKey)
                .post(payload.toString().toRequestBody(mediaType))
                .build()

            try {
                val pcmBytes = httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val body = resp.body?.string().orEmpty()
                    extractInlineAudioBytes(body)
                }
                if (pcmBytes != null && pcmBytes.size > 256) {
                    playPcm24kHzBlocking(pcmBytes)
                    return@withContext true
                }
            } catch (_: Exception) {
            }
        }
        false
    }

    private fun extractInlineAudioBytes(rawJson: String): ByteArray? {
        return try {
            val root = JSONObject(rawJson)
            val candidates = root.optJSONArray("candidates") ?: return null
            if (candidates.length() == 0) return null
            val parts = candidates.getJSONObject(0)
                .optJSONObject("content")
                ?.optJSONArray("parts") ?: return null
            for (i in 0 until parts.length()) {
                val inlineData = parts.getJSONObject(i).optJSONObject("inlineData") ?: continue
                val dataB64 = inlineData.optString("data", "")
                if (dataB64.isNotEmpty()) {
                    return Base64.decode(dataB64, Base64.DEFAULT)
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun playPcm24kHzBlocking(pcmBytes: ByteArray) {
        val sampleRate = 24000
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        val track = AudioTrack.Builder()
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

        try {
            track.play()
            track.write(pcmBytes, 0, pcmBytes.size)
            track.stop()
        } catch (_: Exception) {
        } finally {
            try {
                track.release()
            } catch (_: Exception) {
            }
        }
    }
}
