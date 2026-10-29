package com.personal.bengaliagent.network

import com.personal.bengaliagent.data.prefs.AppPreferences
import com.personal.bengaliagent.data.security.SecureKeyStore
import com.personal.bengaliagent.domain.model.ChatMessage
import com.personal.bengaliagent.domain.model.ErrorCategory
import com.personal.bengaliagent.domain.model.ProviderTestResult
import com.personal.bengaliagent.domain.model.ProviderType

/**
 * Routes requests across configured providers (Gemini, Groq, OpenRouter) with automatic
 * model discovery and seamless failover.
 */
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
            preferences.setPreferredProvider(provider)
            preferences.setLastConnectionMessage("Connected to ${provider.displayName}")
        } else {
            keyStore.setProviderVerified(provider, false)
            preferences.setLastConnectionMessage(result.userMessage)
        }
        return result
    }

    suspend fun disconnectProvider(provider: ProviderType) {
        keyStore.deleteApiKey(provider)
        preferences.setVerifiedModel(provider, null)
    }

    suspend fun disconnectGemini() = disconnectProvider(ProviderType.GEMINI)

    suspend fun sendMessageWithFailover(
        messages: List<ChatMessage>,
        base64JpegImage: String? = null
    ): RoutedResponse {
        if (!preferences.isCloudSharingConsented()) {
            return RoutedResponse(
                success = false,
                replyText = "Cloud AI data sharing is disabled. Please enable it in Settings.",
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
                replyText = "No AI provider is connected yet. Tap the ⚙ Settings icon at the top-right to connect Google Gemini, Groq, or OpenRouter.",
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

            var response = client.sendChatWithBoundedRetry(
                provider = provider,
                apiKey = apiKey,
                modelName = modelId,
                messages = messages,
                maxRetries = 1,
                base64JpegImage = base64JpegImage
            )

            if (!response.isSuccess && response.errorCategory == ErrorCategory.UNSUPPORTED_MODEL) {
                val rediscovery = client.discoverAndVerifyProvider(provider, apiKey)
                if (rediscovery.success && !rediscovery.verifiedModel.isNullOrBlank()) {
                    modelId = rediscovery.verifiedModel
                    preferences.setVerifiedModel(provider, modelId)
                    response = client.sendChatWithBoundedRetry(
                        provider = provider,
                        apiKey = apiKey,
                        modelName = modelId,
                        messages = messages,
                        maxRetries = 0,
                        base64JpegImage = base64JpegImage
                    )
                }
            }

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
