package com.personal.bengaliagent.network

import com.personal.bengaliagent.data.prefs.AppPreferences
import com.personal.bengaliagent.data.security.SecureKeyStore
import com.personal.bengaliagent.domain.model.ChatMessage
import com.personal.bengaliagent.domain.model.ErrorCategory
import com.personal.bengaliagent.domain.model.ProviderTestResult
import com.personal.bengaliagent.domain.model.ProviderType
import org.json.JSONArray
import org.json.JSONObject

class AiClientRouter(
    private val keyStore: SecureKeyStore,
    private val preferences: AppPreferences,
    private val client: LlmProviderClient = LlmProviderClient()
) {

    data class RoutedResponse(
        val success: Boolean,
        val replyText: String,
        val providerUsed: ProviderType?
    )

    data class LiveCallEndpointConfig(
        val provider: ProviderType,
        val apiKey: String,
        val liveModelId: String,
        val fallbackModels: List<String>
    )

    suspend fun connectAndVerifyGemini(rawKey: String): ProviderTestResult =
        connectAndVerifyProvider(ProviderType.GEMINI, rawKey)

    suspend fun connectAndVerifyProvider(
        provider: ProviderType,
        rawKey: String
    ): ProviderTestResult {
        val trimmed = rawKey.trim()
        val result = client.discoverAndVerifyProvider(provider, trimmed)
        if (result.success && !result.verifiedModel.isNullOrBlank()) {
            keyStore.saveApiKey(provider, trimmed)
            keyStore.setProviderVerified(provider, true)
            preferences.setVerifiedModel(provider, result.verifiedModel)
            preferences.setLiveVoiceModel(provider, result.liveVoiceModel ?: result.verifiedModel)
            preferences.setPreferredProvider(provider)
            preferences.setLastConnectionMessage(result.userMessage)

            // Cache discovered model catalog JSON for instant startup
            val arr = JSONArray()
            result.discoveredModels.forEach { m ->
                arr.put(
                    JSONObject()
                        .put("id", m.id)
                        .put("displayName", m.displayName)
                        .put("supportsLiveAudio", m.supportsLiveAudio)
                        .put("supportsVision", m.supportsVision)
                        .put("supportsTools", m.supportsTools)
                        .put("speedTier", m.speedTier)
                )
            }
            preferences.setCachedModelsJson(provider, arr.toString())
        } else {
            keyStore.setProviderVerified(provider, false)
            preferences.setLastConnectionMessage(result.userMessage)
        }
        return result
    }

    suspend fun disconnectProvider(provider: ProviderType) {
        keyStore.deleteApiKey(provider)
        preferences.setVerifiedModel(provider, null)
        preferences.setLiveVoiceModel(provider, null)
    }

    suspend fun disconnectGemini() = disconnectProvider(ProviderType.GEMINI)

    /**
     * Resolves the prioritized list of real-time voice call endpoints (Primary -> Secondary -> Provider Failover).
     */
    suspend fun resolveLiveCallCandidates(): List<LiveCallEndpointConfig> {
        val preferred = preferences.getPreferredProvider()
        val providerOrder = listOf(preferred, ProviderType.GEMINI, ProviderType.GROK).distinct()
        val configs = mutableListOf<LiveCallEndpointConfig>()

        for (p in providerOrder) {
            if (p != ProviderType.GEMINI && p != ProviderType.GROK) continue
            val key = keyStore.getApiKey(p)?.takeIf { it.isNotBlank() } ?: continue
            val savedLiveModel = preferences.getLiveVoiceModel(p)
            if (p == ProviderType.GEMINI) {
                val primary = savedLiveModel?.removePrefix("vertex-v1:")
                    ?: "gemini-2.0-flash-exp"
                val fallbacks = listOf(
                    primary,
                    "gemini-2.0-flash-exp",
                    "gemini-2.0-flash",
                    "gemini-2.5-flash"
                ).distinct()
                configs.add(
                    LiveCallEndpointConfig(
                        provider = ProviderType.GEMINI,
                        apiKey = key,
                        liveModelId = fallbacks.first(),
                        fallbackModels = fallbacks.drop(1)
                    )
                )
            } else if (p == ProviderType.GROK) {
                val primary = savedLiveModel ?: "grok-2-public"
                configs.add(
                    LiveCallEndpointConfig(
                        provider = ProviderType.GROK,
                        apiKey = key,
                        liveModelId = primary,
                        fallbackModels = listOf("grok-2-latest", "grok-beta")
                    )
                )
            }
        }
        return configs
    }

    suspend fun sendMessageWithFailover(
        messages: List<ChatMessage>,
        base64JpegImage: String? = null
    ): RoutedResponse {
        if (!preferences.isCloudSharingConsented()) {
            return RoutedResponse(
                success = false,
                replyText = "Cloud AI data sharing is disabled in Settings.",
                providerUsed = null
            )
        }

        val preferred = preferences.getPreferredProvider()
        val orderedProviders = (listOf(preferred) + ProviderType.entries).distinct()
        val configuredProviders = orderedProviders.filter {
            !keyStore.getApiKey(it).isNullOrBlank()
        }

        if (configuredProviders.isEmpty()) {
            return RoutedResponse(
                success = false,
                replyText = "No AI provider connected. Open Settings to connect Google Gemini or xAI Grok.",
                providerUsed = null
            )
        }

        val errors = mutableListOf<String>()
        for (provider in configuredProviders) {
            val apiKey = keyStore.getApiKey(provider) ?: continue
            var modelId = preferences.getVerifiedModel(provider)

            if (modelId.isNullOrBlank()) {
                val discovery = client.discoverAndVerifyProvider(provider, apiKey)
                if (discovery.success && !discovery.verifiedModel.isNullOrBlank()) {
                    modelId = discovery.verifiedModel
                    preferences.setVerifiedModel(provider, modelId)
                } else {
                    errors.add("${provider.displayName}: ${discovery.userMessage}")
                    continue
                }
            }

            val response = client.sendChatWithBoundedRetry(
                provider = provider,
                apiKey = apiKey,
                modelName = modelId,
                messages = messages,
                maxRetries = 1,
                base64JpegImage = base64JpegImage
            )

            if (response.isSuccess) {
                return RoutedResponse(
                    success = true,
                    replyText = response.replyText,
                    providerUsed = provider
                )
            } else {
                errors.add(response.errorMessage)
            }
        }

        return RoutedResponse(
            success = false,
            replyText = errors.joinToString("\n"),
            providerUsed = null
        )
    }
}
