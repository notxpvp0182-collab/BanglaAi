package com.personal.bengaliagent.network

import com.personal.bengaliagent.data.security.KeyValidator
import com.personal.bengaliagent.domain.model.ChatMessage
import com.personal.bengaliagent.domain.model.ErrorCategory
import com.personal.bengaliagent.domain.model.ModelListResult
import com.personal.bengaliagent.domain.model.ProviderTestResult
import com.personal.bengaliagent.domain.model.ProviderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class LlmProviderClient(
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val baseUrlOverride: Map<ProviderType, String> = emptyMap()
) {

    private fun baseUrl(provider: ProviderType): String =
        baseUrlOverride[provider] ?: provider.baseUrl

    suspend fun fetchAvailableModels(
        provider: ProviderType,
        apiKey: String
    ): ModelListResult = withContext(Dispatchers.IO) {
        val formatCheck = KeyValidator.validateFormat(provider, apiKey)
        if (!formatCheck.isValidFormat) {
            return@withContext ModelListResult(
                models = provider.curatedFallbackModels,
                isFromLiveApi = false,
                recommendedModel = provider.defaultRecommendedModel,
                statusNote = "ফরম্যাট সঠিক নয়; নির্ধারিত ফলব্যাক মডেল তালিকা দেখানো হচ্ছে।"
            )
        }

        val url = "${baseUrl(provider)}/models"
        val requestBuilder = Request.Builder().url(url).get()
        if (provider == ProviderType.GEMINI) {
            requestBuilder.url("$url?key=${apiKey.trim()}")
        } else {
            requestBuilder.addHeader("Authorization", "Bearer ${apiKey.trim()}")
        }

        try {
            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext ModelListResult(
                        models = provider.curatedFallbackModels,
                        isFromLiveApi = false,
                        recommendedModel = provider.defaultRecommendedModel,
                        statusNote = "মডেল তালিকা আনা যায়নি (HTTP ${response.code}); ফলব্যাক তালিকা ব্যবহৃত হচ্ছে।"
                    )
                }

                val parsedModels = parseModelsJson(provider, bodyStr)
                if (parsedModels.isEmpty()) {
                    return@withContext ModelListResult(
                        models = provider.curatedFallbackModels,
                        isFromLiveApi = false,
                        recommendedModel = provider.defaultRecommendedModel,
                        statusNote = "খালি মডেল তালিকা; ফলব্যাক তালিকা ব্যবহৃত হচ্ছে।"
                    )
                }

                val recommended = selectRecommendedModel(provider, parsedModels)
                ModelListResult(
                    models = parsedModels,
                    isFromLiveApi = true,
                    recommendedModel = recommended,
                    statusNote = "সরাসরি API থেকে ${parsedModels.size}টি মডেল পাওয়া গেছে।"
                )
            }
        } catch (e: IOException) {
            ModelListResult(
                models = provider.curatedFallbackModels,
                isFromLiveApi = false,
                recommendedModel = provider.defaultRecommendedModel,
                statusNote = "নেটওয়ার্ক ত্রুটি; নির্ধারিত ফলব্যাক মডেল তালিকা দেখানো হচ্ছে।"
            )
        }
    }

    suspend fun testConnection(
        provider: ProviderType,
        apiKey: String,
        modelName: String
    ): ProviderTestResult = withContext(Dispatchers.IO) {
        val formatCheck = KeyValidator.validateFormat(provider, apiKey)
        if (!formatCheck.isValidFormat) {
            return@withContext ProviderTestResult(
                success = false,
                provider = provider,
                latencyMs = 0L,
                verifiedModel = null,
                userMessageBn = formatCheck.reasonBn,
                errorCategory = ErrorCategory.INVALID_KEY
            )
        }

        val startMs = System.currentTimeMillis()
        try {
            val response = executeChatRequest(
                provider = provider,
                apiKey = apiKey.trim(),
                modelName = modelName.trim(),
                messages = listOf(
                    ChatMessage(1L, ChatMessage.Role.USER, "Reply with OK")
                ),
                maxTokens = 12,
                base64JpegImage = null
            )
            val elapsed = System.currentTimeMillis() - startMs
            if (response.isSuccess) {
                ProviderTestResult(
                    success = true,
                    provider = provider,
                    latencyMs = elapsed,
                    verifiedModel = modelName,
                    userMessageBn = "সংযোগ সফল হয়েছে! (${provider.displayName} - $modelName, ${elapsed}ms)",
                    errorCategory = ErrorCategory.NONE
                )
            } else {
                ProviderTestResult(
                    success = false,
                    provider = provider,
                    latencyMs = elapsed,
                    verifiedModel = null,
                    userMessageBn = response.errorMessageBn,
                    errorCategory = response.errorCategory
                )
            }
        } catch (e: IOException) {
            val elapsed = System.currentTimeMillis() - startMs
            ProviderTestResult(
                success = false,
                provider = provider,
                latencyMs = elapsed,
                verifiedModel = null,
                userMessageBn = "নেটওয়ার্ক সংযোগ ব্যর্থ হয়েছে: ইন্টারনেট চেক করুন।",
                errorCategory = ErrorCategory.NETWORK_ERROR
            )
        }
    }

    data class RawChatResponse(
        val isSuccess: Boolean,
        val replyText: String = "",
        val errorCategory: ErrorCategory = ErrorCategory.NONE,
        val errorMessageBn: String = ""
    )

    suspend fun sendChatWithBoundedRetry(
        provider: ProviderType,
        apiKey: String,
        modelName: String,
        messages: List<ChatMessage>,
        maxRetries: Int = 1,
        base64JpegImage: String? = null
    ): RawChatResponse = withContext(Dispatchers.IO) {
        var attempt = 0
        var lastResponse = RawChatResponse(
            isSuccess = false,
            errorCategory = ErrorCategory.NETWORK_ERROR,
            errorMessageBn = "অজানা ত্রুটি"
        )
        while (attempt <= maxRetries) {
            try {
                val result = executeChatRequest(
                    provider = provider,
                    apiKey = apiKey,
                    modelName = modelName,
                    messages = messages,
                    maxTokens = 650,
                    base64JpegImage = base64JpegImage
                )
                if (result.isSuccess) return@withContext result
                lastResponse = result
                if (result.errorCategory == ErrorCategory.INVALID_KEY ||
                    result.errorCategory == ErrorCategory.UNSUPPORTED_MODEL
                ) {
                    return@withContext result
                }
            } catch (e: IOException) {
                lastResponse = RawChatResponse(
                    isSuccess = false,
                    errorCategory = ErrorCategory.NETWORK_ERROR,
                    errorMessageBn = "নেটওয়ার্ক টাইমআউট বা সংযোগ বিচ্ছিন্ন।"
                )
            }
            attempt++
            if (attempt <= maxRetries) {
                delay(600L * attempt)
            }
        }
        lastResponse
    }

    private fun executeChatRequest(
        provider: ProviderType,
        apiKey: String,
        modelName: String,
        messages: List<ChatMessage>,
        maxTokens: Int,
        base64JpegImage: String?
    ): RawChatResponse {
        val jsonMediaType = "application/json; charset=utf-8".toMediaType()
        val request: Request = when (provider) {
            ProviderType.GEMINI -> {
                val cleanModel = modelName.removePrefix("models/")
                val url = "${baseUrl(provider)}/models/$cleanModel:generateContent?key=$apiKey"
                val contentsArray = JSONArray()
                for ((idx, msg) in messages.withIndex()) {
                    val role = if (msg.role == ChatMessage.Role.ASSISTANT) "model" else "user"
                    val partsArr = JSONArray().put(JSONObject().put("text", msg.text))
                    // Attach optional downscaled screenshot to the final user message
                    if (idx == messages.lastIndex && !base64JpegImage.isNullOrBlank()) {
                        val inlineData = JSONObject()
                            .put("mime_type", "image/jpeg")
                            .put("data", base64JpegImage)
                        partsArr.put(JSONObject().put("inline_data", inlineData))
                    }
                    contentsArray.put(
                        JSONObject()
                            .put("role", role)
                            .put("parts", partsArr)
                    )
                }
                val payload = JSONObject()
                    .put("contents", contentsArray)
                    .put(
                        "generationConfig",
                        JSONObject().put("maxOutputTokens", maxTokens).put("temperature", 0.3)
                    )
                Request.Builder()
                    .url(url)
                    .post(payload.toString().toRequestBody(jsonMediaType))
                    .build()
            }

            ProviderType.GROQ, ProviderType.OPENROUTER -> {
                val url = "${baseUrl(provider)}/chat/completions"
                val msgArray = JSONArray()
                msgArray.put(
                    JSONObject()
                        .put("role", "system")
                        .put(
                            "content",
                            "তুমি একজন সৎ, সংক্ষিপ্ত এবং সহায়ক বাংলা এআই বন্ধু। কখনো মিথ্যা দাবি করবে না।"
                        )
                )
                for ((idx, msg) in messages.withIndex()) {
                    val role = when (msg.role) {
                        ChatMessage.Role.USER -> "user"
                        ChatMessage.Role.ASSISTANT -> "assistant"
                        ChatMessage.Role.SYSTEM -> "system"
                    }
                    if (idx == messages.lastIndex &&
                        !base64JpegImage.isNullOrBlank() &&
                        provider == ProviderType.OPENROUTER
                    ) {
                        val contentArr = JSONArray()
                            .put(JSONObject().put("type", "text").put("text", msg.text))
                            .put(
                                JSONObject().put("type", "image_url").put(
                                    "image_url",
                                    JSONObject().put("url", "data:image/jpeg;base64,$base64JpegImage")
                                )
                            )
                        msgArray.put(JSONObject().put("role", role).put("content", contentArr))
                    } else {
                        msgArray.put(JSONObject().put("role", role).put("content", msg.text))
                    }
                }
                val payload = JSONObject()
                    .put("model", modelName)
                    .put("messages", msgArray)
                    .put("max_tokens", maxTokens)
                    .put("temperature", 0.3)

                Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .post(payload.toString().toRequestBody(jsonMediaType))
                    .build()
            }
        }

        httpClient.newCall(request).execute().use { response ->
            val rawBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                return classifyHttpError(provider, response.code, rawBody)
            }
            val reply = extractReplyText(provider, rawBody)
            if (reply.isBlank()) {
                return RawChatResponse(
                    isSuccess = false,
                    errorCategory = ErrorCategory.SERVER_ERROR,
                    errorMessageBn = "${provider.displayName} থেকে খালি উত্তর এসেছে।"
                )
            }
            return RawChatResponse(isSuccess = true, replyText = reply.trim())
        }
    }

    private fun classifyHttpError(
        provider: ProviderType,
        code: Int,
        rawBody: String
    ): RawChatResponse {
        val safeSnippet = KeyValidator.redactSecrets(rawBody).take(140)
        return when (code) {
            400 -> {
                if (rawBody.contains("API_KEY_INVALID", ignoreCase = true) ||
                    rawBody.contains("invalid api key", ignoreCase = true)
                ) {
                    RawChatResponse(
                        false,
                        errorCategory = ErrorCategory.INVALID_KEY,
                        errorMessageBn = "${provider.displayName}: API Key সঠিক নয় (400)।"
                    )
                } else {
                    RawChatResponse(
                        false,
                        errorCategory = ErrorCategory.UNSUPPORTED_MODEL,
                        errorMessageBn = "${provider.displayName}: মডেল বা অনুরোধ সমর্থিত নয় (400)।"
                    )
                }
            }
            401, 403 -> RawChatResponse(
                false,
                errorCategory = ErrorCategory.INVALID_KEY,
                errorMessageBn = "${provider.displayName}: অনুমোদন ব্যর্থ হয়েছে। API Key চেক করুন ($code)।"
            )
            404 -> RawChatResponse(
                false,
                errorCategory = ErrorCategory.UNSUPPORTED_MODEL,
                errorMessageBn = "${provider.displayName}: নির্বাচিত মডেলটি পাওয়া যায়নি (404)।"
            )
            429 -> RawChatResponse(
                false,
                errorCategory = ErrorCategory.QUOTA_OR_RATE_LIMIT,
                errorMessageBn = "${provider.displayName}: কোটা বা রেট লিমিট শেষ হয়েছে (429)।"
            )
            in 500..599 -> RawChatResponse(
                false,
                errorCategory = ErrorCategory.SERVER_ERROR,
                errorMessageBn = "${provider.displayName}: সার্ভার সাময়িকভাবে ডাউন ($code)।"
            )
            else -> RawChatResponse(
                false,
                errorCategory = ErrorCategory.SERVER_ERROR,
                errorMessageBn = "${provider.displayName} ত্রুটি ($code): $safeSnippet"
            )
        }
    }

    private fun extractReplyText(provider: ProviderType, rawJson: String): String {
        return try {
            val root = JSONObject(rawJson)
            if (provider == ProviderType.GEMINI) {
                val candidates = root.optJSONArray("candidates") ?: return ""
                if (candidates.length() == 0) return ""
                val content = candidates.getJSONObject(0).optJSONObject("content") ?: return ""
                val parts = content.optJSONArray("parts") ?: return ""
                if (parts.length() == 0) return ""
                parts.getJSONObject(0).optString("text", "")
            } else {
                val choices = root.optJSONArray("choices") ?: return ""
                if (choices.length() == 0) return ""
                val msg = choices.getJSONObject(0).optJSONObject("message") ?: return ""
                msg.optString("content", "")
            }
        } catch (e: Exception) {
            ""
        }
    }

    private fun parseModelsJson(provider: ProviderType, bodyStr: String): List<String> {
        return try {
            val root = JSONObject(bodyStr)
            val result = mutableListOf<String>()
            if (provider == ProviderType.GEMINI) {
                val arr = root.optJSONArray("models") ?: return emptyList()
                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    val name = item.optString("name", "").removePrefix("models/")
                    val methods = item.optJSONArray("supportedGenerationMethods")?.toString().orEmpty()
                    if (name.startsWith("gemini") && methods.contains("generateContent")) {
                        result.add(name)
                    }
                }
            } else {
                val arr = root.optJSONArray("data") ?: return emptyList()
                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    val id = item.optString("id", "")
                    if (id.isNotBlank() &&
                        !id.contains("whisper", ignoreCase = true) &&
                        !id.contains("tts", ignoreCase = true) &&
                        !id.contains("embed", ignoreCase = true) &&
                        !id.contains("guard", ignoreCase = true)
                    ) {
                        result.add(id)
                    }
                }
            }
            result.distinct()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun selectRecommendedModel(provider: ProviderType, available: List<String>): String {
        for (preferred in provider.curatedFallbackModels) {
            if (available.contains(preferred)) return preferred
        }
        return available.firstOrNull() ?: provider.defaultRecommendedModel
    }

    companion object {
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }
}
