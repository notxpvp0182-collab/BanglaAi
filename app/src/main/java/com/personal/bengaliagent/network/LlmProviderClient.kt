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
import java.util.concurrent.TimeUnit

/**
 * Multi-Provider Client supporting:
 * 1. Google Gemini (Both new 'AQ.' Auth/Vertex Express keys and 'AIza' keys — NEVER sending Bearer header which triggers 401).
 * 2. Groq Cloud (Prioritizing active production chat models).
 * 3. OpenRouter.
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
        val trimmedKey = cleanApiKey(apiKey)
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
            // IMPORTANT: Never send Authorization: Bearer with AQ. or AIza keys!
            reqBuilder.addHeader("x-goog-api-key", trimmedKey)
        } else {
            reqBuilder.addHeader("Authorization", "Bearer $trimmedKey")
        }

        try {
            httpClient.newCall(reqBuilder.build()).execute().use { response ->
                val rawBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    // For Gemini AQ. keys (such as Vertex Express keys where GET /models is blocked),
                    // provide standard fallback candidates so discoverAndVerifyProvider can test them directly!
                    if (provider == ProviderType.GEMINI && baseUrlOverride.isEmpty()) {
                        return@withContext DiscoveryResponse(
                            eligibleModels = defaultGeminiFallbackCandidates()
                        )
                    }
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
                errorMessage = "Network error (${e.javaClass.simpleName}): Please check your internet connection."
            )
        }
    }

    private fun defaultGeminiFallbackCandidates(): List<DiscoveredModel> = listOf(
        DiscoveredModel("gemini-2.5-flash", "Gemini 2.5 Flash", listOf("generateContent")),
        DiscoveredModel("gemini-2.0-flash", "Gemini 2.0 Flash", listOf("generateContent")),
        DiscoveredModel("gemini-2.5-pro", "Gemini 2.5 Pro", listOf("generateContent")),
        DiscoveredModel("gemini-1.5-flash", "Gemini 1.5 Flash", listOf("generateContent"))
    )

    fun parseAndRankGeminiModels(rawJson: String): List<DiscoveredModel> {
        return try {
            val root = JSONObject(rawJson)
            val modelsArr = root.optJSONArray("models") ?: return emptyList()
            val candidates = mutableListOf<DiscoveredModel>()

            val excludedKeywords = listOf(
                "embedding", "imagen", "veo", "aqa", "attribution", "tts",
                "audio", "robotics", "learnlm", "bison", "gecko", "vision-only", "thinking"
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
            val discoveredIds = mutableSetOf<String>()

            val excluded = listOf(
                "whisper", "tts", "embed", "guard", "moderation", "audio",
                "specdec", "vision-preview", "tool-use", "playai", "orpheus", "allam"
            )

            for (i in 0 until dataArr.length()) {
                val obj = dataArr.optJSONObject(i) ?: continue
                if (obj.optBoolean("active", true) == false) continue
                val id = obj.optString("id", "").trim()
                if (id.isBlank()) continue
                val lower = id.lowercase()
                if (excluded.any { lower.contains(it) }) continue
                discoveredIds.add(id)
            }

            val priorityOrder = if (provider == ProviderType.GROQ) {
                listOf(
                    "llama-3.3-70b-versatile",
                    "llama-3.1-8b-instant",
                    "meta-llama/llama-4-scout-17b-16e-instruct",
                    "meta-llama/llama-4-maverick-17b-128e-instruct",
                    "qwen/qwen3-32b",
                    "gemma2-9b-it",
                    "mixtral-8x7b-32768"
                )
            } else {
                listOf(
                    "google/gemini-2.5-flash",
                    "google/gemini-2.0-flash-001",
                    "meta-llama/llama-3.3-70b-instruct",
                    "openai/gpt-4o-mini",
                    "anthropic/claude-3.5-haiku"
                )
            }

            val ordered = mutableListOf<DiscoveredModel>()
            for (pref in priorityOrder) {
                if (discoveredIds.contains(pref)) {
                    ordered.add(DiscoveredModel(pref, pref, listOf("chat.completions")))
                    discoveredIds.remove(pref)
                }
            }
            for (rem in discoveredIds) {
                ordered.add(DiscoveredModel(rem, rem, listOf("chat.completions")))
            }
            ordered
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
            id == "gemini-2.5-flash" -> score += 600
            id == "gemini-2.0-flash" -> score += 550
            id == "gemini-2.5-pro" -> score += 500
            id == "gemini-1.5-flash" -> score += 450
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
        val trimmedKey = cleanApiKey(apiKey)
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
        val maxCandidatesToTry = discovery.eligibleModels.take(5)

        // 1. Try standard endpoint candidates
        for (candidate in maxCandidatesToTry) {
            try {
                val verifyResult = executeChatRequest(
                    provider = provider,
                    apiKey = trimmedKey,
                    modelName = candidate.id,
                    messages = listOf(ChatMessage(1L, ChatMessage.Role.USER, "Hi")),
                    maxTokens = 16,
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
                if (verifyResult.errorCategory == ErrorCategory.QUOTA_OR_RATE_LIMIT) {
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
            }
        }

        // 2. Special handling for Google 'AQ.' keys created via Vertex AI Express Mode:
        // If AI Studio endpoint returned 401/403/400 for an AQ. key, test Vertex AI Express Mode endpoints!
        if (provider == ProviderType.GEMINI &&
            trimmedKey.startsWith("AQ.") &&
            baseUrlOverride.isEmpty()
        ) {
            val vertexExpressModels = listOf(
                "vertex-v1:gemini-2.5-flash",
                "vertex-v1:gemini-2.0-flash",
                "vertex-v1beta1:gemini-2.5-flash",
                "vertex-v1beta1:gemini-2.0-flash"
            )
            for (vertexModelTag in vertexExpressModels) {
                try {
                    val verifyVertex = executeChatRequest(
                        provider = ProviderType.GEMINI,
                        apiKey = trimmedKey,
                        modelName = vertexModelTag,
                        messages = listOf(ChatMessage(1L, ChatMessage.Role.USER, "Hi")),
                        maxTokens = 16,
                        base64JpegImage = null
                    )
                    if (verifyVertex.isSuccess) {
                        val elapsed = System.currentTimeMillis() - startMs
                        return@withContext ProviderTestResult(
                            success = true,
                            provider = ProviderType.GEMINI,
                            latencyMs = elapsed,
                            verifiedModel = vertexModelTag,
                            userMessage = "Connected to Google Gemini Vertex Express (${elapsed}ms)",
                            errorCategory = ErrorCategory.NONE
                        )
                    }
                    lastErrorResult = verifyVertex
                } catch (_: Exception) {
                }
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
        val cleanedKey = cleanApiKey(apiKey)
        var lastResponse = RawChatResponse(
            isSuccess = false,
            errorCategory = ErrorCategory.NETWORK_ERROR,
            errorMessage = "Unexpected network error."
        )
        while (attempt <= maxRetries) {
            try {
                val result = executeChatRequest(
                    provider = provider,
                    apiKey = cleanedKey,
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
                val url = when {
                    modelName.startsWith("vertex-v1:") -> {
                        val m = modelName.removePrefix("vertex-v1:")
                        "https://aiplatform.googleapis.com/v1/publishers/google/models/$m:generateContent?key=$apiKey"
                    }
                    modelName.startsWith("vertex-v1beta1:") -> {
                        val m = modelName.removePrefix("vertex-v1beta1:")
                        "https://aiplatform.googleapis.com/v1beta1/publishers/google/models/$m:generateContent?key=$apiKey"
                    }
                    else -> {
                        val cleanModel = modelName.removePrefix("models/")
                        "${baseUrl(provider)}/models/$cleanModel:generateContent"
                    }
                }

                val systemPrompt = buildString {
                    append("You are Bengali AI Agent, an honest, capable personal Android assistant with expert coding and UI screen automation skills. Reply naturally in Bengali or English matching the user.")
                    for (msg in messages) {
                        if (msg.role == ChatMessage.Role.SYSTEM && msg.text.isNotBlank()) {
                            append("\n\n").append(msg.text)
                        }
                    }
                }

                val contentsArray = JSONArray()
                val nonSystemMessages = messages.filter { it.role != ChatMessage.Role.SYSTEM }
                    .ifEmpty { listOf(ChatMessage(1L, ChatMessage.Role.USER, "Hello")) }

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
                    .put(
                        "systemInstruction",
                        JSONObject()
                            .put("role", "system")
                            .put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
                    )
                    .put("contents", contentsArray)
                    .put(
                        "generationConfig",
                        JSONObject()
                            .put("maxOutputTokens", maxTokens)
                            .put("temperature", 0.35)
                    )

                // IMPORTANT: Authenticate with x-goog-api-key (NEVER Authorization: Bearer)
                Request.Builder()
                    .url(url)
                    .addHeader("x-goog-api-key", apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(payload.toString().toRequestBody(jsonMediaType))
                    .build()
            }

            ProviderType.GROQ, ProviderType.OPENROUTER -> {
                val url = "${baseUrl(provider)}/chat/completions"
                val msgArray = JSONArray()

                val systemBuilder = StringBuilder(
                    "You are Bengali AI Agent, an honest, capable personal Android assistant with expert coding and UI screen automation skills. Reply naturally in Bengali or English matching the user."
                )
                for (msg in messages) {
                    if (msg.role == ChatMessage.Role.SYSTEM && msg.text.isNotBlank()) {
                        systemBuilder.append("\n\n").append(msg.text)
                    }
                }
                msgArray.put(
                    JSONObject()
                        .put("role", "system")
                        .put("content", systemBuilder.toString())
                )

                val userAndAssistantMsgs = messages.filter { it.role != ChatMessage.Role.SYSTEM }
                    .ifEmpty { listOf(ChatMessage(1L, ChatMessage.Role.USER, "Hello")) }

                for ((idx, msg) in userAndAssistantMsgs.withIndex()) {
                    val role = if (msg.role == ChatMessage.Role.ASSISTANT) "assistant" else "user"
                    if (idx == userAndAssistantMsgs.lastIndex &&
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

    private fun extractServerErrorDetail(rawBody: String): String {
        return try {
            val root = JSONObject(rawBody)
            val errObj = root.optJSONObject("error")
            val msg = errObj?.optString("message", "") ?: root.optString("message", "")
            KeyValidator.redactSecrets(msg).take(160)
        } catch (_: Exception) {
            KeyValidator.redactSecrets(rawBody).take(120)
        }
    }

    private fun classifyHttpError(
        provider: ProviderType,
        code: Int,
        rawBody: String
    ): RawChatResponse {
        val serverDetail = extractServerErrorDetail(rawBody)
        return when (code) {
            400 -> {
                if (rawBody.contains("API_KEY_INVALID", ignoreCase = true) ||
                    rawBody.contains("invalid api key", ignoreCase = true) ||
                    rawBody.contains("key not valid", ignoreCase = true)
                ) {
                    RawChatResponse(
                        false,
                        errorCategory = ErrorCategory.INVALID_KEY,
                        errorMessage = "${provider.displayName} (400): Invalid API key. $serverDetail"
                    )
                } else {
                    RawChatResponse(
                        false,
                        errorCategory = ErrorCategory.UNSUPPORTED_MODEL,
                        errorMessage = "${provider.displayName} (400): $serverDetail"
                    )
                }
            }
            401 -> RawChatResponse(
                false,
                errorCategory = ErrorCategory.INVALID_KEY,
                errorMessage = "${provider.displayName} (401): $serverDetail"
            )
            403 -> RawChatResponse(
                false,
                errorCategory = ErrorCategory.PERMISSION_DENIED,
                errorMessage = "${provider.displayName} (403): $serverDetail"
            )
            404 -> RawChatResponse(
                false,
                errorCategory = ErrorCategory.UNSUPPORTED_MODEL,
                errorMessage = "${provider.displayName} (404): $serverDetail"
            )
            429 -> RawChatResponse(
                false,
                errorCategory = ErrorCategory.QUOTA_OR_RATE_LIMIT,
                errorMessage = "${provider.displayName} (429 Quota/Rate Limit): $serverDetail"
            )
            in 500..599 -> RawChatResponse(
                false,
                errorCategory = ErrorCategory.SERVER_ERROR,
                errorMessage = "${provider.displayName} server error ($code): $serverDetail"
            )
            else -> RawChatResponse(
                false,
                errorCategory = ErrorCategory.SERVER_ERROR,
                errorMessage = "${provider.displayName} error ($code): $serverDetail"
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

    private fun cleanApiKey(raw: String): String =
        raw.trim().removeSurrounding("\"").removeSurrounding("'").trim()

    companion object {
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
