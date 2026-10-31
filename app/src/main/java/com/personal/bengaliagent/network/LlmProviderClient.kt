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
 * Dynamic Model Discovery & Fast Connection Engine for Google Gemini and xAI Grok.
 * - Accepts any credential string without legacy prefix restrictions.
 * - Discovers available models dynamically from GET /v1beta/models (Gemini) and GET /v1/models (xAI Grok).
 * - Evaluates Live Audio (bidiGenerateContent / Realtime), Vision, and Tool capabilities.
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

        val url = if (provider == ProviderType.GEMINI) {
            "${baseUrl(provider)}/models?key=$trimmedKey"
        } else {
            "${baseUrl(provider)}/models"
        }

        val reqBuilder = Request.Builder()
            .url(url)
            .addHeader("Accept", "application/json")
            .get()

        if (provider == ProviderType.GEMINI) {
            reqBuilder.addHeader("x-goog-api-key", trimmedKey)
        } else {
            reqBuilder.addHeader("Authorization", "Bearer $trimmedKey")
        }

        try {
            httpClient.newCall(reqBuilder.build()).execute().use { response ->
                val rawBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
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
                        errorMessage = "No compatible models found for this ${provider.displayName} key."
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
                errorMessage = "Network error (${e.javaClass.simpleName}): Please check internet connection."
            )
        }
    }

    private fun defaultGeminiFallbackCandidates(): List<DiscoveredModel> = listOf(
        DiscoveredModel(
            id = "gemini-2.0-flash-exp",
            displayName = "Gemini 2.0 Flash Live",
            provider = ProviderType.GEMINI,
            supportedGenerationMethods = listOf("generateContent", "bidiGenerateContent"),
            supportsLiveAudio = true,
            supportsVision = true,
            supportsTools = true,
            speedTier = "Ultra-Fast"
        ),
        DiscoveredModel(
            id = "gemini-2.0-flash",
            displayName = "Gemini 2.0 Flash",
            provider = ProviderType.GEMINI,
            supportedGenerationMethods = listOf("generateContent", "bidiGenerateContent"),
            supportsLiveAudio = true,
            supportsVision = true,
            supportsTools = true,
            speedTier = "Ultra-Fast"
        ),
        DiscoveredModel(
            id = "gemini-2.5-flash",
            displayName = "Gemini 2.5 Flash",
            provider = ProviderType.GEMINI,
            supportedGenerationMethods = listOf("generateContent", "bidiGenerateContent"),
            supportsLiveAudio = true,
            supportsVision = true,
            supportsTools = true,
            speedTier = "Ultra-Fast"
        ),
        DiscoveredModel(
            id = "gemini-3.5-flash-lite",
            displayName = "Gemini 3.5 Flash-Lite",
            provider = ProviderType.GEMINI,
            supportedGenerationMethods = listOf("generateContent"),
            supportsLiveAudio = false,
            supportsVision = true,
            supportsTools = true,
            speedTier = "Ultra-Fast"
        )
    )

    fun parseAndRankGeminiModels(rawJson: String): List<DiscoveredModel> {
        return try {
            val root = JSONObject(rawJson)
            val modelsArr = root.optJSONArray("models") ?: return emptyList()
            val candidates = mutableListOf<DiscoveredModel>()

            val excludedKeywords = listOf(
                "embedding", "imagen", "veo", "aqa", "attribution", "tts",
                "robotics", "learnlm", "bison", "gecko", "vision-only"
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
                if (!methods.contains("generateContent") && !methods.contains("bidiGenerateContent")) {
                    continue
                }

                val supportsBidi = methods.contains("bidiGenerateContent") ||
                    lowerId.contains("live") ||
                    lowerId.contains("native-audio") ||
                    lowerId == "gemini-2.0-flash" ||
                    lowerId == "gemini-2.0-flash-exp" ||
                    lowerId.startsWith("gemini-2.5-flash")

                val speed = when {
                    lowerId.contains("lite") || lowerId.contains("flash") -> "Ultra-Fast"
                    lowerId.contains("pro") -> "Reasoning"
                    else -> "Fast"
                }

                candidates.add(
                    DiscoveredModel(
                        id = id,
                        displayName = obj.optString("displayName", id),
                        provider = ProviderType.GEMINI,
                        supportedGenerationMethods = methods,
                        supportsLiveAudio = supportsBidi,
                        supportsVision = true,
                        supportsTools = true,
                        speedTier = speed,
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
            id == "gemini-3.5-flash-lite" -> score += 750
            id == "gemini-2.5-flash" -> score += 700
            id == "gemini-2.0-flash" -> score += 650
            id.contains("flash") && !id.contains("lite") && !id.contains("8b") -> score += 400
            id.contains("flash-lite") -> score += 350
            id.contains("pro") -> score += 300
        }

        val versionMatch = Regex("gemini-(\\d+)\\.(\\d+)").find(id)
        if (versionMatch != null) {
            val major = versionMatch.groupValues[1].toIntOrNull() ?: 1
            val minor = versionMatch.groupValues[2].toIntOrNull() ?: 0
            score += (major * 100) + (minor * 20)
        }
        if (model.supportsLiveAudio) score += 40
        if (model.inputTokenLimit >= 100_000) score += 25
        return score
    }

    private fun parseAndRankOpenAiCompatibleModels(
        provider: ProviderType,
        rawJson: String
    ): List<DiscoveredModel> {
        return try {
            val root = JSONObject(rawJson)
            val dataArr = root.optJSONArray("data") ?: return emptyList()
            val discovered = mutableListOf<DiscoveredModel>()

            val excluded = listOf("whisper", "tts", "embed", "guard", "moderation")

            for (i in 0 until dataArr.length()) {
                val obj = dataArr.optJSONObject(i) ?: continue
                if (obj.optBoolean("active", true) == false) continue
                val id = obj.optString("id", "").trim()
                if (id.isBlank()) continue
                val lower = id.lowercase()
                if (excluded.any { lower.contains(it) }) continue

                val supportsLive = provider == ProviderType.GROK && (
                    lower.contains("realtime") ||
                        lower.contains("voice") ||
                        lower.startsWith("grok-2") ||
                        lower.startsWith("grok-3") ||
                        lower.startsWith("grok-4")
                    )
                val supportsVision = lower.contains("vision") ||
                    lower.startsWith("grok-2-vision") ||
                    lower.startsWith("grok-3") ||
                    lower.startsWith("grok-4") ||
                    lower.contains("gemini")

                val speed = if (lower.contains("mini") || lower.contains("fast") || lower.contains("instant")) {
                    "Ultra-Fast"
                } else {
                    "Fast"
                }

                discovered.add(
                    DiscoveredModel(
                        id = id,
                        displayName = id,
                        provider = provider,
                        supportedGenerationMethods = listOf("chat.completions"),
                        supportsLiveAudio = supportsLive,
                        supportsVision = supportsVision,
                        supportsTools = true,
                        speedTier = speed
                    )
                )
            }

            discovered.sortedByDescending { m ->
                val id = m.id.lowercase()
                var s = 0
                if (m.supportsLiveAudio) s += 500
                if (id.contains("grok-3") || id.contains("grok-4") || id.contains("llama-3.3-70b")) s += 400
                if (id.contains("fast") || id.contains("mini") || id.contains("flash")) s += 200
                s
            }
        } catch (e: Exception) {
            emptyList()
        }
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

        val candidateQueue = discovery.eligibleModels.take(4)
        var lastErrorResult: RawChatResponse? = null

        for (candidate in candidateQueue) {
            try {
                val verifyResult = executeChatRequest(
                    provider = provider,
                    apiKey = trimmedKey,
                    modelName = candidate.id,
                    messages = listOf(ChatMessage(1L, ChatMessage.Role.USER, "OK")),
                    maxTokens = 8,
                    base64JpegImage = null
                )
                if (verifyResult.isSuccess) {
                    val elapsed = System.currentTimeMillis() - startMs
                    val bestLiveVoiceModel = discovery.eligibleModels
                        .firstOrNull { it.supportsLiveAudio }?.id
                        ?: if (provider == ProviderType.GEMINI) "gemini-2.0-flash-exp" else candidate.id

                    return@withContext ProviderTestResult(
                        success = true,
                        provider = provider,
                        latencyMs = elapsed,
                        verifiedModel = candidate.id,
                        liveVoiceModel = bestLiveVoiceModel,
                        discoveredModels = discovery.eligibleModels,
                        userMessage = "Connected • Active: ${candidate.id} | Voice: $bestLiveVoiceModel (${elapsed}ms)",
                        errorCategory = ErrorCategory.NONE
                    )
                }
                lastErrorResult = verifyResult
                if (verifyResult.errorCategory == ErrorCategory.INVALID_KEY ||
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
            }
        }

        // Vertex AI Express Mode fallback for AQ. keys
        if (provider == ProviderType.GEMINI && baseUrlOverride.isEmpty()) {
            val vertexModels = listOf(
                "vertex-v1:gemini-2.5-flash",
                "vertex-v1:gemini-2.0-flash"
            )
            for (vertexTag in vertexModels) {
                try {
                    val verifyVertex = executeChatRequest(
                        provider = ProviderType.GEMINI,
                        apiKey = trimmedKey,
                        modelName = vertexTag,
                        messages = listOf(ChatMessage(1L, ChatMessage.Role.USER, "OK")),
                        maxTokens = 8,
                        base64JpegImage = null
                    )
                    if (verifyVertex.isSuccess) {
                        val elapsed = System.currentTimeMillis() - startMs
                        val shortName = vertexTag.substringAfter(":")
                        return@withContext ProviderTestResult(
                            success = true,
                            provider = ProviderType.GEMINI,
                            latencyMs = elapsed,
                            verifiedModel = vertexTag,
                            liveVoiceModel = shortName,
                            discoveredModels = discovery.eligibleModels,
                            userMessage = "Connected • $shortName (${elapsed}ms)",
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
                    maxTokens = 300,
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
                delay(350L * attempt)
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
                    else -> {
                        val cleanModel = modelName.removePrefix("models/")
                        if (baseUrlOverride.containsKey(ProviderType.GEMINI)) {
                            "${baseUrl(provider)}/models/$cleanModel:generateContent"
                        } else {
                            "${baseUrl(provider)}/models/$cleanModel:generateContent?key=$apiKey"
                        }
                    }
                }

                val systemPrompt = buildString {
                    append("You are Bengali AI Agent on a real-time voice call. Give very short, direct, natural spoken answers in Bengali or English matching the user. No long explanations.")
                    for (msg in messages) {
                        if (msg.role == ChatMessage.Role.SYSTEM && msg.text.isNotBlank()) {
                            append("\n").append(msg.text)
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
                            .put("temperature", 0.3)
                    )

                Request.Builder()
                    .url(url)
                    .addHeader("x-goog-api-key", apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(payload.toString().toRequestBody(jsonMediaType))
                    .build()
            }

            ProviderType.GROK, ProviderType.GROQ, ProviderType.OPENROUTER -> {
                val url = "${baseUrl(provider)}/chat/completions"
                val msgArray = JSONArray()

                val systemBuilder = StringBuilder(
                    "You are Bengali AI Agent on a real-time voice call. Give very short, direct, natural spoken answers in Bengali or English matching the user. No long explanations."
                )
                for (msg in messages) {
                    if (msg.role == ChatMessage.Role.SYSTEM && msg.text.isNotBlank()) {
                        systemBuilder.append("\n").append(msg.text)
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
                        (provider == ProviderType.GROK || provider == ProviderType.OPENROUTER)
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
                    errorMessage = "Empty response from ${provider.displayName}."
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
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
