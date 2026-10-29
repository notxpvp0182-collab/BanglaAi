package com.personal.bengaliagent.network

import com.personal.bengaliagent.data.security.KeyValidator
import com.personal.bengaliagent.domain.model.ChatMessage
import com.personal.bengaliagent.domain.model.DiscoveredModel
import com.personal.bengaliagent.domain.model.ErrorCategory
import com.personal.bengaliagent.domain.model.ProviderTestResult
import com.personal.bengaliagent.domain.model.ProviderType
import kotlinx.coroutines.CancellationException
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

/**
 * Unified Multi-Provider Client supporting Google Gemini, Groq Cloud, and OpenRouter
 * with automatic live model discovery and runtime verification.
 */
class LlmProviderClient(
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val baseUrlOverride: Map<ProviderType, String> = emptyMap()
) {

    private fun baseUrl(provider: ProviderType): String =
        baseUrlOverride[provider] ?: provider.baseUrl

    data class DiscoveryResponse(
        val eligibleModels: List<DiscoveredModel>,
        val errorCategory: ErrorCategory = ErrorCategory.NONE,
        val errorMessage: String = ""
    )

    suspend fun discoverEligibleGeminiModels(apiKey: String): DiscoveryResponse =
        discoverEligibleModels(ProviderType.GEMINI, apiKey)

    suspend fun discoverEligibleModels(
        provider: ProviderType,
        apiKey: String
    ): DiscoveryResponse = withContext(Dispatchers.IO) {
        val trimmedKey = apiKey.trim()
        val validation = KeyValidator.validateFormat(provider, trimmedKey)
        if (!validation.isValidFormat) {
            return@withContext DiscoveryResponse(
                eligibleModels = emptyList(),
                errorCategory = ErrorCategory.INVALID_KEY,
                errorMessage = validation.reason
            )
        }

        val url = "${baseUrl(provider)}/models"
        val reqBuilder = Request.Builder()
            .url(url)
            .addHeader("Accept", "application/json")
            .get()

        if (provider == ProviderType.GEMINI) {
            reqBuilder.addHeader("x-goog-api-key", trimmedKey)
            if (trimmedKey.startsWith("AQ.")) {
                reqBuilder.addHeader("Authorization", "Bearer $trimmedKey")
            }
        } else {
            reqBuilder.addHeader("Authorization", "Bearer $trimmedKey")
        }

        try {
            httpClient.newCall(reqBuilder.build()).execute().use { response ->
                val rawBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val err = classifyHttpError(provider, response.code, rawBody)
                    return@withContext DiscoveryResponse(
                        eligibleModels = emptyList(),
                        errorCategory = err.errorCategory,
                        errorMessage = err.errorMessage
                    )
                }

                val ranked = if (provider == ProviderType.GEMINI) {
                    parseAndRankGeminiModels(rawBody)
                } else {
                    parseAndRankOpenAiCompatibleModels(provider, rawBody)
                }

                if (ranked.isEmpty()) {
                    return@withContext DiscoveryResponse(
                        eligibleModels = emptyList(),
                        errorCategory = ErrorCategory.NO_ELIGIBLE_MODELS,
                        errorMessage = "No compatible text-generation models found for this ${provider.displayName} key."
                    )
                }

                DiscoveryResponse(eligibleModels = ranked)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            DiscoveryResponse(
                eligibleModels = emptyList(),
                errorCategory = ErrorCategory.NETWORK_ERROR,
                errorMessage = "Network error (${e.javaClass.simpleName}): Please check internet or VPN."
            )
        }
    }

    fun parseAndRankGeminiModels(rawJson: String): List<DiscoveredModel> {
        return try {
            val root = JSONObject(rawJson)
            val modelsArr = root.optJSONArray("models") ?: return emptyList()
            val candidates = mutableListOf<DiscoveredModel>()

            val excludedKeywords = listOf(
                "embedding", "imagen", "veo", "aqa", "attribution", "tts",
                "audio", "robotics", "learnlm", "bison", "gecko", "vision-only"
            )

            for (i in 0 until modelsArr.length()) {
                val obj = modelsArr.optJSONObject(i) ?: continue
                val rawName = obj.optString("name", "").trim()
                val id = rawName.removePrefix("models/")
                if (!id.startsWith("gemini", ignoreCase = true)) continue

                val lowerId = id.lowercase()
                if (excludedKeywords.any { lowerId.contains(it) }) continue

                val methodsArr = obj.optJSONArray("supportedGenerationMethods") ?: continue
                val methods = mutableListOf<String>()
                for (m in 0 until methodsArr.length()) {
                    methods.add(methodsArr.optString(m, ""))
                }
                if (!methods.contains("generateContent")) continue

                candidates.add(
                    DiscoveredModel(
                        id = id,
                        displayName = obj.optString("displayName", id),
                        supportedGenerationMethods = methods,
                        inputTokenLimit = obj.optInt("inputTokenLimit", 0),
                        outputTokenLimit = obj.optInt("outputTokenLimit", 0)
                    )
                )
            }

            candidates.sortedByDescending { scoreGeminiCandidate(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseAndRankOpenAiCompatibleModels(
        provider: ProviderType,
        rawJson: String
    ): List<DiscoveredModel> {
        return try {
            val root = JSONObject(rawJson)
            val dataArr = root.optJSONArray("data") ?: return emptyList()
            val candidates = mutableListOf<DiscoveredModel>()
            val excluded = listOf("whisper", "tts", "embed", "guard", "moderation", "audio")

            for (i in 0 until dataArr.length()) {
                val obj = dataArr.optJSONObject(i) ?: continue
                val id = obj.optString("id", "").trim()
                if (id.isBlank()) continue
                val lower = id.lowercase()
                if (excluded.any { lower.contains(it) }) continue

                candidates.add(
                    DiscoveredModel(
                        id = id,
                        displayName = id,
                        supportedGenerationMethods = listOf("chat.completions")
                    )
                )
            }

            candidates.sortedByDescending { model ->
                val id = model.id.lowercase()
                when (provider) {
                    ProviderType.GROQ -> when {
                        id.contains("llama-3.3-70b") -> 1000
                        id.contains("llama-3.1-8b") -> 900
                        id.contains("mixtral") -> 800
                        id.contains("gemma") -> 700
                        else -> 500
                    }
                    ProviderType.OPENROUTER -> when {
                        id.contains("gemini-2.5-flash") || id.contains("gemini-2.0-flash") -> 1000
                        id.contains("llama-3.3-70b") -> 950
                        id.contains("claude-3.5-haiku") || id.contains("claude-3.7") -> 900
                        id.contains("gpt-4o-mini") -> 850
                        else -> 500
                    }
                    else -> 500
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun scoreGeminiCandidate(model: DiscoveredModel): Int {
        val id = model.id.lowercase()
        var score = 0
        val isPreviewOrExp = id.contains("preview") ||
            id.contains("exp") ||
            id.contains("experimental") ||
            id.contains("alpha") ||
            id.contains("beta")

        if (!isPreviewOrExp) score += 1000

        when {
            id.contains("flash") && !id.contains("lite") && !id.contains("8b") -> score += 300
            id.contains("pro") -> score += 200
            id.contains("flash-lite") || id.contains("8b") -> score += 100
        }

        val versionMatch = Regex("gemini-(\\d+)\\.(\\d+)").find(id)
        if (versionMatch != null) {
            val major = versionMatch.groupValues[1].toIntOrNull() ?: 1
            val minor = versionMatch.groupValues[2].toIntOrNull() ?: 0
            score += (major * 100) + (minor * 20)
        }
        if (model.inputTokenLimit >= 100_000) score += 25
        return score
    }

    suspend fun discoverAndVerifyGemini(apiKey: String): ProviderTestResult =
        discoverAndVerifyProvider(ProviderType.GEMINI, apiKey)

    suspend fun discoverAndVerifyProvider(
        provider: ProviderType,
        apiKey: String
    ): ProviderTestResult = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        val trimmedKey = apiKey.trim()
        val discovery = discoverEligibleModels(provider, trimmedKey)

        if (discovery.eligibleModels.isEmpty()) {
            val elapsed = System.currentTimeMillis() - startMs
            return@withContext ProviderTestResult(
                success = false,
                provider = provider,
                latencyMs = elapsed,
                verifiedModel = null,
                userMessage = discovery.errorMessage.ifBlank {
                    "No eligible models found for ${provider.displayName}."
                },
                errorCategory = discovery.errorCategory
            )
        }

        var lastErrorResult: RawChatResponse? = null
        val maxCandidatesToTry = discovery.eligibleModels.take(3)

        for (candidate in maxCandidatesToTry) {
            try {
                val verifyResult = executeChatRequest(
                    provider = provider,
                    apiKey = trimmedKey,
                    modelName = candidate.id,
                    messages = listOf(ChatMessage(1L, ChatMessage.Role.USER, "Reply OK")),
                    maxTokens = 10,
                    base64JpegImage = null
                )
                if (verifyResult.isSuccess) {
                    val elapsed = System.currentTimeMillis() - startMs
                    return@withContext ProviderTestResult(
                        success = true,
                        provider = provider,
                        latencyMs = elapsed,
                        verifiedModel = candidate.id,
                        userMessage = "Connected to ${provider.displayName} (${elapsed}ms)",
                        errorCategory = ErrorCategory.NONE
                    )
                }

                lastErrorResult = verifyResult
                if (verifyResult.errorCategory == ErrorCategory.INVALID_KEY ||
                    verifyResult.errorCategory == ErrorCategory.PERMISSION_DENIED ||
                    verifyResult.errorCategory == ErrorCategory.QUOTA_OR_RATE_LIMIT
                ) {
                    break
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                lastErrorResult = RawChatResponse(
                    isSuccess = false,
                    errorCategory = ErrorCategory.NETWORK_ERROR,
                    errorMessage = "Network error (${e.javaClass.simpleName}) while verifying ${provider.displayName}."
                )
                break
            }
        }

        val elapsed = System.currentTimeMillis() - startMs
        ProviderTestResult(
            success = false,
            provider = provider,
            latencyMs = elapsed,
            verifiedModel = null,
            userMessage = lastErrorResult?.errorMessage
                ?: "Could not verify any model on ${provider.displayName}.",
            errorCategory = lastErrorResult?.errorCategory ?: ErrorCategory.UNSUPPORTED_MODEL
        )
    }

    data class RawChatResponse(
        val isSuccess: Boolean,
        val replyText: String = "",
        val errorCategory: ErrorCategory = ErrorCategory.NONE,
        val errorMessage: String = ""
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
            errorMessage = "Unexpected network error."
        )
        while (attempt <= maxRetries) {
            try {
                val result = executeChatRequest(
                    provider = provider,
                    apiKey = apiKey.trim(),
                    modelName = modelName.trim(),
                    messages = messages,
                    maxTokens = 700,
                    base64JpegImage = base64JpegImage
                )
                if (result.isSuccess) return@withContext result
                lastResponse = result
                if (result.errorCategory == ErrorCategory.INVALID_KEY ||
                    result.errorCategory == ErrorCategory.PERMISSION_DENIED ||
                    result.errorCategory == ErrorCategory.UNSUPPORTED_MODEL ||
                    result.errorCategory == ErrorCategory.QUOTA_OR_RATE_LIMIT
                ) {
                    return@withContext result
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                lastResponse = RawChatResponse(
                    isSuccess = false,
                    errorCategory = ErrorCategory.NETWORK_ERROR,
                    errorMessage = "Network timeout or connection lost (${e.javaClass.simpleName})."
                )
            }
            attempt++
            if (attempt <= maxRetries) {
                delay(500L * attempt)
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
                val url = "${baseUrl(provider)}/models/$cleanModel:generateContent"

                val systemParts = JSONArray()
                systemParts.put(
                    JSONObject().put(
                        "text",
                        "You are Bengali AI Agent, an honest, capable personal Android assistant with expert coding and UI screen automation skills. Reply naturally in Bengali or English matching the user."
                    )
                )

                val contentsArray = JSONArray()
                val nonSystemMessages = mutableListOf<ChatMessage>()
                for (msg in messages) {
                    if (msg.role == ChatMessage.Role.SYSTEM) {
                        systemParts.put(JSONObject().put("text", msg.text))
                    } else {
                        nonSystemMessages.add(msg)
                    }
                }
                if (nonSystemMessages.isEmpty()) {
                    nonSystemMessages.add(ChatMessage(1L, ChatMessage.Role.USER, "Hello"))
                }

                for ((idx, msg) in nonSystemMessages.withIndex()) {
                    val role = if (msg.role == ChatMessage.Role.ASSISTANT) "model" else "user"
                    val partsArr = JSONArray().put(JSONObject().put("text", msg.text))
                    if (idx == nonSystemMessages.lastIndex && !base64JpegImage.isNullOrBlank()) {
                        val inlineData = JSONObject()
                            .put("mimeType", "image/jpeg")
                            .put("data", base64JpegImage)
                        partsArr.put(JSONObject().put("inlineData", inlineData))
                    }
                    contentsArray.put(
                        JSONObject()
                            .put("role", role)
                            .put("parts", partsArr)
                    )
                }

                val payload = JSONObject()
                    .put("systemInstruction", JSONObject().put("parts", systemParts))
                    .put("contents", contentsArray)
                    .put(
                        "generationConfig",
                        JSONObject()
                            .put("maxOutputTokens", maxTokens)
                            .put("temperature", 0.35)
                    )

                val builder = Request.Builder()
                    .url(url)
                    .addHeader("x-goog-api-key", apiKey)
                    .addHeader("Content-Type", "application/json")
                if (apiKey.startsWith("AQ.")) {
                    builder.addHeader("Authorization", "Bearer $apiKey")
                }
                builder.post(payload.toString().toRequestBody(jsonMediaType)).build()
            }

            ProviderType.GROQ, ProviderType.OPENROUTER -> {
                val url = "${baseUrl(provider)}/chat/completions"
                val msgArray = JSONArray()
                msgArray.put(
                    JSONObject()
                        .put("role", "system")
                        .put(
                            "content",
                            "You are Bengali AI Agent, an honest, capable personal Android assistant with expert coding and UI screen automation skills. Reply naturally in Bengali or English matching the user."
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
                    .put("temperature", 0.35)

                Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
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
                    errorMessage = "Received an empty response from ${provider.displayName}."
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
                    rawBody.contains("invalid api key", ignoreCase = true) ||
                    rawBody.contains("key not valid", ignoreCase = true)
                ) {
                    RawChatResponse(
                        false,
                        errorCategory = ErrorCategory.INVALID_KEY,
                        errorMessage = "${provider.displayName}: Invalid API key. Please check your key ( note: Google AI Studio keys start with 'AIza' )."
                    )
                } else {
                    RawChatResponse(
                        false,
                        errorCategory = ErrorCategory.UNSUPPORTED_MODEL,
                        errorMessage = "${provider.displayName}: Request or model not supported (HTTP 400)."
                    )
                }
            }
            401 -> RawChatResponse(
                false,
                errorCategory = ErrorCategory.INVALID_KEY,
                errorMessage = "${provider.displayName}: Authentication failed (HTTP 401). Note: If using Gemini, make sure you use a Google AI Studio key starting with 'AIza', not an OAuth 'AQ.' cookie token."
            )
            403 -> RawChatResponse(
                false,
                errorCategory = ErrorCategory.PERMISSION_DENIED,
                errorMessage = "${provider.displayName}: Access denied (HTTP 403). Your API key may lack permission or region access."
            )
            404 -> RawChatResponse(
                false,
                errorCategory = ErrorCategory.UNSUPPORTED_MODEL,
                errorMessage = "${provider.displayName}: Model endpoint unavailable (HTTP 404)."
            )
            429 -> RawChatResponse(
                false,
                errorCategory = ErrorCategory.QUOTA_OR_RATE_LIMIT,
                errorMessage = "${provider.displayName}: Quota or rate limit exceeded (HTTP 429)."
            )
            in 500..599 -> RawChatResponse(
                false,
                errorCategory = ErrorCategory.SERVER_ERROR,
                errorMessage = "${provider.displayName} server error (HTTP $code). Try again shortly."
            )
            else -> RawChatResponse(
                false,
                errorCategory = ErrorCategory.SERVER_ERROR,
                errorMessage = "${provider.displayName} error (HTTP $code): $safeSnippet"
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
                val sb = StringBuilder()
                for (i in 0 until parts.length()) {
                    sb.append(parts.getJSONObject(i).optString("text", ""))
                }
                sb.toString()
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

    companion object {
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
