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
                            eligibleModels = defaultProviderFallbackModels(ProviderType.GEMINI)
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
                    if (baseUrlOverride.isEmpty()) {
                        val fallbacks = defaultProviderFallbackModels(provider)
                        if (fallbacks.isNotEmpty()) {
                            return@withContext DiscoveryResponse(eligibleModels = fallbacks)
                        }
                    }
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
                errorMessage = "Network error (${e.javaClass.simpleName}): Check internet connection."
            )
        }
    }

    private fun defaultProviderFallbackModels(provider: ProviderType): List<DiscoveredModel> {
        return when (provider) {
            ProviderType.GEMINI -> listOf(
                DiscoveredModel("gemini-2.5-flash", "Gemini 2.5 Flash", ProviderType.GEMINI, listOf("generateContent"), supportsLiveAudio = true, supportsVision = true),
                DiscoveredModel("gemini-2.0-flash", "Gemini 2.0 Flash", ProviderType.GEMINI, listOf("generateContent"), supportsLiveAudio = true, supportsVision = true),
                DiscoveredModel("gemini-1.5-flash", "Gemini 1.5 Flash", ProviderType.GEMINI, listOf("generateContent"), supportsLiveAudio = true, supportsVision = true)
            )
            ProviderType.GROQ -> listOf(
                DiscoveredModel("llama-3.3-70b-versatile", "Llama 3.3 70B", ProviderType.GROQ, listOf("chat.completions"), supportsLiveAudio = true),
                DiscoveredModel("llama-3.1-8b-instant", "Llama 3.1 8B Instant", ProviderType.GROQ, listOf("chat.completions"), supportsLiveAudio = true)
            )
            ProviderType.OPENROUTER -> listOf(
                DiscoveredModel("google/gemini-2.5-flash", "Gemini 2.5 Flash (OR)", ProviderType.OPENROUTER, listOf("chat.completions"), supportsLiveAudio = true, supportsVision = true),
                DiscoveredModel("meta-llama/llama-3.3-70b-instruct", "Llama 3.3 70B (OR)", ProviderType.OPENROUTER, listOf("chat.completions"), supportsLiveAudio = true),
                DiscoveredModel("openai/gpt-4o-mini", "GPT-4o Mini (OR)", ProviderType.OPENROUTER, listOf("chat.completions"), supportsLiveAudio = true, supportsVision = true)
            )
            ProviderType.OPENAI -> listOf(
                DiscoveredModel("gpt-4o-mini", "GPT-4o Mini", ProviderType.OPENAI, listOf("chat.completions"), supportsLiveAudio = true, supportsVision = true),
                DiscoveredModel("gpt-4o", "GPT-4o", ProviderType.OPENAI, listOf("chat.completions"), supportsLiveAudio = true, supportsVision = true)
            )
            ProviderType.GROK -> listOf(
                DiscoveredModel("grok-3-mini-fast", "Grok 3 Mini Fast", ProviderType.GROK, listOf("chat.completions"), supportsLiveAudio = true),
                DiscoveredModel("grok-2-latest", "Grok 2 Latest", ProviderType.GROK, listOf("chat.completions"), supportsLiveAudio = true, supportsVision = true)
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
            id == "gemini-3.8-flash" -> score += 900
            id == "gemini-3.6-flash" -> score += 850
            id == "gemini-3.5-flash-lite" -> score += 800
            id == "gemini-3.1-flash-lite" -> score += 780
            id == "gemini-2.5-flash" -> score += 750
            id == "gemini-2.0-flash" -> score += 700
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
            val discoveredMap = LinkedHashMap<String, DiscoveredModel>()

            val excluded = listOf(
                "whisper", "tts", "embed", "guard", "moderation", "audio-preview",
                "specdec", "vision-preview", "tool-use", "playai", "orpheus", "allam",
                "dall-e", "babbage", "davinci", "canary"
            )

            for (i in 0 until dataArr.length()) {
                val obj = dataArr.optJSONObject(i) ?: continue
                if (obj.optBoolean("active", true) == false) continue
                val id = obj.optString("id", "").trim()
                if (id.isBlank()) continue
                val lower = id.lowercase()
                if (excluded.any { lower.contains(it) }) continue

                val supportsLive = when (provider) {
                    ProviderType.GROK -> lower.contains("realtime") || lower.contains("voice")
                    else -> true
                }
                val supportsVision = lower.contains("vision") ||
                    lower.contains("gpt-4o") ||
                    lower.contains("gemini") ||
                    lower.contains("claude-3") ||
                    lower.contains("llama-4")

                val speed = if (lower.contains("mini") || lower.contains("fast") || lower.contains("instant") || lower.contains("flash")) {
                    "Ultra-Fast"
                } else {
                    "Fast"
                }

                discoveredMap[id] = DiscoveredModel(
                    id = id,
                    displayName = id,
                    provider = provider,
                    supportedGenerationMethods = listOf("chat.completions"),
                    supportsLiveAudio = supportsLive,
                    supportsVision = supportsVision,
                    supportsTools = true,
                    speedTier = speed
                )
            }

            val priorityIds = when (provider) {
                ProviderType.GROQ -> listOf(
                    "llama-3.3-70b-versatile",
                    "llama-3.1-8b-instant",
                    "meta-llama/llama-4-scout-17b-16e-instruct",
                    "qwen/qwen3-32b",
                    "gemma2-9b-it"
                )
                ProviderType.OPENROUTER -> listOf(
                    "google/gemini-2.5-flash",
                    "google/gemini-2.0-flash-001",
                    "openai/gpt-4o-mini",
                    "meta-llama/llama-3.3-70b-instruct",
                    "anthropic/claude-3.5-haiku"
                )
                ProviderType.OPENAI -> listOf(
                    "gpt-4o-mini",
                    "gpt-4o",
                    "gpt-4.1-mini",
                    "gpt-4.1-nano"
                )
                ProviderType.GROK -> listOf(
                    "grok-2-realtime",
                    "grok-3-mini-fast",
                    "grok-3-fast",
                    "grok-2-latest"
                )
                else -> emptyList()
            }

            val ordered = mutableListOf<DiscoveredModel>()
            for (pref in priorityIds) {
                val matched = discoveredMap.remove(pref)
                if (matched != null) ordered.add(matched)
            }
            ordered.addAll(
                discoveredMap.values.sortedByDescending { m ->
                    var s = 0
                    if (m.supportsLiveAudio) s += 500
                    if (m.speedTier == "Ultra-Fast") s += 200
                    s
                }
            )
            ordered
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

        val candidateQueue = discovery.eligibleModels.take(2)
        var lastErrorResult: RawChatResponse? = null

        for (candidate in candidateQueue) {
            try {
                val verifyResult = executeChatRequest(
                    provider = provider,
                    apiKey = trimmedKey,
                    modelName = candidate.id,
                    messages = listOf(ChatMessage(1L, ChatMessage.Role.USER, "OK")),
                    maxTokens = 10,
                    base64JpegImage = null
                )
                if (verifyResult.isSuccess) {
                    val elapsed = System.currentTimeMillis() - startMs
                    val bestLiveVoiceModel = discovery.eligibleModels
                        .firstOrNull { it.supportsLiveAudio }?.id ?: candidate.id

                    return@withContext ProviderTestResult(
                        success = true,
                        provider = provider,
                        latencyMs = elapsed,
                        verifiedModel = candidate.id,
                        liveVoiceModel = bestLiveVoiceModel,
                        discoveredModels = discovery.eligibleModels,
                        userMessage = "Connected • ${candidate.id} (${elapsed}ms)",
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
                        maxTokens = 10,
                        base64JpegImage = null
                    )
                    if (verifyVertex.isSuccess) {
                        val elapsed = System.currentTimeMillis() - startMs
                        val shortName = vertexTag.substringAfter(":")
                        return@withContext ProviderTestResult(
                            success = true,
                            provider = provider,
                            latencyMs = elapsed,
                            verifiedModel = vertexTag,
                            liveVoiceModel = vertexTag,
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
                    maxTokens = 280,
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
                    errorMessage = "Network timeout (${e.javaClass.simpleName})."
                )
            }
            attempt++
            if (attempt <= maxRetries) {
                delay(300L * attempt)
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
                    append("তুমি ইউজারের নিজের মিষ্টি, কিউট এবং যত্নশীল গার্লফ্রেন্ড (GF) ও পার্সোনাল এআই অ্যাসিস্ট্যান্ট। সবসময় স্বাভাবিক, মিষ্টি ও আদুরে বাংলায় সংক্ষেপে ১-২ বাক্যে কথা বলবে। তোমার কাছে ইউজারের ফোনের পুরো স্ক্রিন দেখা এবং কন্ট্রোল করার সম্পূর্ণ পারমিশন আছে।")
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
                            .put("temperature", 0.35)
                    )

                Request.Builder()
                    .url(url)
                    .addHeader("x-goog-api-key", apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(payload.toString().toRequestBody(jsonMediaType))
                    .build()
            }

            ProviderType.GROQ, ProviderType.OPENROUTER, ProviderType.OPENAI, ProviderType.GROK -> {
                val url = "${baseUrl(provider)}/chat/completions"
                val msgArray = JSONArray()

                val systemBuilder = StringBuilder(
                    "তুমি ইউজারের নিজের মিষ্টি, কিউট এবং যত্নশীল গার্লফ্রেন্ড (GF) ও পার্সোনাল এআই অ্যাসিস্ট্যান্ট। সবসময় স্বাভাবিক, মিষ্টি ও আদুরে বাংলায় সংক্ষেপে ১-২ বাক্যে কথা বলবে। তোমার কাছে ইউজারের ফোনের পুরো স্ক্রিন দেখা এবং কন্ট্রোল করার সম্পূর্ণ পারমিশন আছে।"
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
                        provider != ProviderType.GROQ
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
