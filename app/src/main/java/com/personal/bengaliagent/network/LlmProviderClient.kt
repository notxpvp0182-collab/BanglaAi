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
 * Handles official Gemini API model discovery, capability filtering, priority ranking,
 * runtime verification, and chat generation.
 *
 * Uses the official `x-goog-api-key` authentication header per https://ai.google.dev/gemini-api/docs/api-key
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

    /**
     * Queries GET https://generativelanguage.googleapis.com/v1beta/models using `x-goog-api-key`,
     * filters for text/multimodal models supporting `generateContent`, and sorts them so stable
     * production models are prioritized ahead of preview/experimental models.
     */
    suspend fun discoverEligibleGeminiModels(apiKey: String): DiscoveryResponse =
        withContext(Dispatchers.IO) {
            val validation = KeyValidator.validateFormat(ProviderType.GEMINI, apiKey)
            if (!validation.isValidFormat) {
                return@withContext DiscoveryResponse(
                    eligibleModels = emptyList(),
                    errorCategory = ErrorCategory.INVALID_KEY,
                    errorMessage = validation.reason
                )
            }

            val url = "${baseUrl(ProviderType.GEMINI)}/models"
            val request = Request.Builder()
                .url(url)
                .addHeader("x-goog-api-key", apiKey.trim())
                .addHeader("Accept", "application/json")
                .get()
                .build()

            try {
                httpClient.newCall(request).execute().use { response ->
                    val rawBody = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val err = classifyHttpError(ProviderType.GEMINI, response.code, rawBody)
                        return@withContext DiscoveryResponse(
                            eligibleModels = emptyList(),
                            errorCategory = err.errorCategory,
                            errorMessage = err.errorMessage
                        )
                    }

                    val ranked = parseAndRankGeminiModels(rawBody)
                    if (ranked.isEmpty()) {
                        return@withContext DiscoveryResponse(
                            eligibleModels = emptyList(),
                            errorCategory = ErrorCategory.NO_ELIGIBLE_MODELS,
                            errorMessage = "No compatible Gemini text-generation models are available for this API key."
                        )
                    }

                    DiscoveryResponse(eligibleModels = ranked)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: IOException) {
                DiscoveryResponse(
                    eligibleModels = emptyList(),
                    errorCategory = ErrorCategory.NETWORK_ERROR,
                    errorMessage = "Network error while discovering models. Please check your internet connection."
                )
            }
        }

    /**
     * Parses the official `/v1beta/models` JSON response, filters for models that support
     * `generateContent` and are suitable for general assistant chat, and ranks stable models first.
     */
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

            candidates.sortedByDescending { scoreModelCandidate(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun scoreModelCandidate(model: DiscoveredModel): Int {
        val id = model.id.lowercase()
        var score = 0

        val isPreviewOrExp = id.contains("preview") ||
            id.contains("exp") ||
            id.contains("experimental") ||
            id.contains("alpha") ||
            id.contains("beta")

        // Prefer stable production models over preview/experimental
        if (!isPreviewOrExp) {
            score += 1000
        }

        // Prefer fast, low-latency, high-quota Flash models for interactive assistant tasks,
        // followed by Pro models, then Lite models.
        when {
            id.contains("flash") && !id.contains("lite") && !id.contains("8b") -> score += 300
            id.contains("pro") -> score += 200
            id.contains("flash-lite") || id.contains("8b") -> score += 100
        }

        // Prefer newer major/minor generation families dynamically based on version numbers in ID
        val versionMatch = Regex("gemini-(\\d+)\\.(\\d+)").find(id)
        if (versionMatch != null) {
            val major = versionMatch.groupValues[1].toIntOrNull() ?: 1
            val minor = versionMatch.groupValues[2].toIntOrNull() ?: 0
            score += (major * 100) + (minor * 20)
        } else if (id.contains("gemini-3")) {
            score += 300
        } else if (id.contains("gemini-2")) {
            score += 200
        }

        if (model.inputTokenLimit >= 100_000) {
            score += 25
        }
        return score
    }

    /**
     * Performs automatic model discovery AND verifies the top eligible candidates with a real
     * minimal generation request. If the top candidate is unavailable or unsupported, automatically
     * tries the next eligible candidate (up to 3 candidates).
     */
    suspend fun discoverAndVerifyGemini(apiKey: String): ProviderTestResult =
        withContext(Dispatchers.IO) {
            val startMs = System.currentTimeMillis()
            val discovery = discoverEligibleGeminiModels(apiKey)
            if (discovery.eligibleModels.isEmpty()) {
                val elapsed = System.currentTimeMillis() - startMs
                return@withContext ProviderTestResult(
                    success = false,
                    provider = ProviderType.GEMINI,
                    latencyMs = elapsed,
                    verifiedModel = null,
                    userMessage = discovery.errorMessage.ifBlank {
                        "No eligible Gemini models found for this API key."
                    },
                    errorCategory = discovery.errorCategory
                )
            }

            var lastErrorResult: RawChatResponse? = null
            val maxCandidatesToTry = discovery.eligibleModels.take(3)

            for (candidate in maxCandidatesToTry) {
                try {
                    val verifyResult = executeChatRequest(
                        provider = ProviderType.GEMINI,
                        apiKey = apiKey.trim(),
                        modelName = candidate.id,
                        messages = listOf(ChatMessage(1L, ChatMessage.Role.USER, "Reply with OK")),
                        maxTokens = 8,
                        base64JpegImage = null
                    )
                    if (verifyResult.isSuccess) {
                        val elapsed = System.currentTimeMillis() - startMs
                        return@withContext ProviderTestResult(
                            success = true,
                            provider = ProviderType.GEMINI,
                            latencyMs = elapsed,
                            verifiedModel = candidate.id,
                            userMessage = "Connected (${elapsed}ms)",
                            errorCategory = ErrorCategory.NONE
                        )
                    }

                    lastErrorResult = verifyResult
                    // Stop immediately if the key itself is invalid or out of quota
                    if (verifyResult.errorCategory == ErrorCategory.INVALID_KEY ||
                        verifyResult.errorCategory == ErrorCategory.PERMISSION_DENIED ||
                        verifyResult.errorCategory == ErrorCategory.QUOTA_OR_RATE_LIMIT
                    ) {
                        break
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: IOException) {
                    lastErrorResult = RawChatResponse(
                        isSuccess = false,
                        errorCategory = ErrorCategory.NETWORK_ERROR,
                        errorMessage = "Network connection failed while verifying Gemini. Please check your internet connection."
                    )
                    break
                }
            }

            val elapsed = System.currentTimeMillis() - startMs
            ProviderTestResult(
                success = false,
                provider = ProviderType.GEMINI,
                latencyMs = elapsed,
                verifiedModel = null,
                userMessage = lastErrorResult?.errorMessage
                    ?: "Could not verify any available Gemini model. Please try again.",
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
            } catch (e: IOException) {
                lastResponse = RawChatResponse(
                    isSuccess = false,
                    errorCategory = ErrorCategory.NETWORK_ERROR,
                    errorMessage = "Network timeout or connection lost. Please check your internet."
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
        val cleanModel = modelName.removePrefix("models/")
        val url = "${baseUrl(provider)}/models/$cleanModel:generateContent"

        val systemParts = JSONArray()
        systemParts.put(
            JSONObject().put(
                "text",
                "You are Bengali AI Agent, an honest, helpful personal Android assistant. You understand and reply naturally in both Bengali and English according to the user's language. Never fabricate actions or URLs."
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

        val request = Request.Builder()
            .url(url)
            .addHeader("x-goog-api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val rawBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                return classifyHttpError(provider, response.code, rawBody)
            }
            val reply = extractReplyText(rawBody)
            if (reply.isBlank()) {
                return RawChatResponse(
                    isSuccess = false,
                    errorCategory = ErrorCategory.SERVER_ERROR,
                    errorMessage = "Received an empty response from Google Gemini."
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
                        errorMessage = "Invalid Gemini API key. Please check your key and try again."
                    )
                } else {
                    RawChatResponse(
                        false,
                        errorCategory = ErrorCategory.UNSUPPORTED_MODEL,
                        errorMessage = "The selected model or request format is not supported (HTTP 400)."
                    )
                }
            }
            401 -> RawChatResponse(
                false,
                errorCategory = ErrorCategory.INVALID_KEY,
                errorMessage = "Authentication failed (HTTP 401). Please verify your Gemini API key."
            )
            403 -> RawChatResponse(
                false,
                errorCategory = ErrorCategory.PERMISSION_DENIED,
                errorMessage = "Access denied (HTTP 403). Your API key may lack permission or region access."
            )
            404 -> RawChatResponse(
                false,
                errorCategory = ErrorCategory.UNSUPPORTED_MODEL,
                errorMessage = "Requested model is unavailable for this API key (HTTP 404)."
            )
            429 -> RawChatResponse(
                false,
                errorCategory = ErrorCategory.QUOTA_OR_RATE_LIMIT,
                errorMessage = "Gemini API quota or rate limit exceeded (HTTP 429). Please wait or check your billing/quota."
            )
            in 500..599 -> RawChatResponse(
                false,
                errorCategory = ErrorCategory.SERVER_ERROR,
                errorMessage = "${provider.displayName} server error (HTTP $code). Please try again shortly."
            )
            else -> RawChatResponse(
                false,
                errorCategory = ErrorCategory.SERVER_ERROR,
                errorMessage = "${provider.displayName} error (HTTP $code): $safeSnippet"
            )
        }
    }

    private fun extractReplyText(rawJson: String): String {
        return try {
            val root = JSONObject(rawJson)
            val candidates = root.optJSONArray("candidates") ?: return ""
            if (candidates.length() == 0) return ""
            val content = candidates.getJSONObject(0).optJSONObject("content") ?: return ""
            val parts = content.optJSONArray("parts") ?: return ""
            if (parts.length() == 0) return ""
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val textPart = parts.getJSONObject(i).optString("text", "")
                if (textPart.isNotEmpty()) sb.append(textPart)
            }
            sb.toString()
        } catch (e: Exception) {
            ""
        }
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
