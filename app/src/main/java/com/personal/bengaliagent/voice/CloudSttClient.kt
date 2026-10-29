package com.personal.bengaliagent.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * Optional fallback Cloud Speech-to-Text using Groq Whisper API when a device lacks
 * offline/system Bengali SpeechRecognizer support and the user explicitly enables Cloud STT.
 */
class CloudSttClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()
) {

    data class SttResult(
        val success: Boolean,
        val text: String,
        val errorMessageBn: String = ""
    )

    suspend fun transcribePcmWithGroqWhisper(
        groqApiKey: String,
        pcmSamples: ShortArray,
        sampleRate: Int = 16000,
        languageIso: String = "bn"
    ): SttResult = withContext(Dispatchers.IO) {
        if (groqApiKey.isBlank()) {
            return@withContext SttResult(
                false,
                "",
                "ক্লাউড ভয়েস রিকগনিশনের জন্য Groq API Key প্রয়োজন।"
            )
        }
        val wavBytes = encodePcmToWavBytes(pcmSamples, sampleRate)
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                "speech.wav",
                wavBytes.toRequestBody("audio/wav".toMediaType())
            )
            .addFormDataPart("model", "whisper-large-v3")
            .addFormDataPart("language", languageIso)
            .build()

        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/audio/transcriptions")
            .addHeader("Authorization", "Bearer ${groqApiKey.trim()}")
            .post(body)
            .build()

        try {
            httpClient.newCall(request).execute().use { resp ->
                val respStr = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext SttResult(
                        false,
                        "",
                        "ক্লাউড STT ব্যর্থ হয়েছে (HTTP ${resp.code})।"
                    )
                }
                val text = JSONObject(respStr).optString("text", "").trim()
                if (text.isEmpty()) {
                    SttResult(false, "", "কোনো কথা বোঝা যায়নি।")
                } else {
                    SttResult(true, text)
                }
            }
        } catch (e: Exception) {
            SttResult(false, "", "ক্লাউড STT নেটওয়ার্ক ত্রুটি।")
        }
    }

    private fun encodePcmToWavBytes(pcm: ShortArray, sampleRate: Int): ByteArray {
        val byteRate = sampleRate * 2
        val dataSize = pcm.size * 2
        val out = ByteArrayOutputStream(44 + dataSize)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + dataSize)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16) // PCM chunk size
        header.putShort(1) // AudioFormat = 1 (PCM)
        header.putShort(1) // Mono
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(2) // BlockAlign
        header.putShort(16) // BitsPerSample
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataSize)
        out.write(header.array())

        val sampleBuf = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
        for (s in pcm) {
            sampleBuf.putShort(s)
        }
        out.write(sampleBuf.array())
        return out.toByteArray()
    }
}
