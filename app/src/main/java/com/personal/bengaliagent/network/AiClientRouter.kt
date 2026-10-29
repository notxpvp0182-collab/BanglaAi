package com.personal.bengaliagent.network

import com.personal.bengaliagent.data.prefs.AppPreferences
import com.personal.bengaliagent.data.security.SecureKeyStore
import com.personal.bengaliagent.domain.model.ChatMessage
import com.personal.bengaliagent.domain.model.ErrorCategory
import com.personal.bengaliagent.domain.model.ProviderTestResult
import com.personal.bengaliagent.domain.model.ProviderType

/**
 * Coordinates automatic Gemini discovery, key persistence, and chat requests.
 * Keeps the verified model ID strictly as an internal implementation detail.
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

    suspend fun connectAndVerifyGemini(rawKey: String): ProviderTestResult {
        val trimmed = rawKey.trim()
        val result = client.discoverAndVerifyGemini(trimmed)
        if (result.success && !result.verifiedModel.isNullOrBlank()) {
            keyStore.saveApiKey(ProviderType.GEMINI, trimmed)
            keyStore.setProviderVerified(ProviderType.GEMINI, true)
            preferences.setVerifiedModel(ProviderType.GEMINI, result.verifiedModel)
            preferences.setLastConnectionMessage("Connected to Google Gemini")
        } else {
            keyStore.setProviderVerified(ProviderType.GEMINI, false)
            preferences.setLastConnectionMessage(result.userMessage)
        }
        return result
    }

    suspend fun disconnectGemini() {
        keyStore.deleteApiKey(ProviderType.GEMINI)
        preferences.setVerifiedModel(ProviderType.GEMINI, null)
        preferences.setLastConnectionMessage("Disconnected")
    }

    suspend fun sendMessageWithFailover(
        messages: List<ChatMessage>,
        base64JpegImage: String? = null
    ): RoutedResponse {
        if (!preferences.isCloudSharingConsented()) {
            return RoutedResponse(
                success = false,
                replyText = "Cloud AI data sharing is currently disabled. Please enable it in AI Connection settings.",
                providerUsed = null
            )
        }

        val apiKey = keyStore.getApiKey(ProviderType.GEMINI)
        if (apiKey.isNullOrBlank()) {
            return RoutedResponse(
                success = false,
                replyText = "Google Gemini is not connected yet. Open 'AI Connection' to enter your Gemini API key.",
                providerUsed = null
            )
        }

        var modelId = preferences.getVerifiedModel(ProviderType.GEMINI)
        if (modelId.isNullOrBlank()) {
            val discovery = client.discoverAndVerifyGemini(apiKey)
            if (!discovery.success || discovery.verifiedModel.isNullOrBlank()) {
                return RoutedResponse(
                    success = false,
                    replyText = discovery.userMessage,
                    providerUsed = null
                )
            }
            modelId = discovery.verifiedModel
            preferences.setVerifiedModel(ProviderType.GEMINI, modelId)
        }

        var response = client.sendChatWithBoundedRetry(
            provider = ProviderType.GEMINI,
            apiKey = apiKey,
            modelName = modelId,
            messages = messages,
            maxRetries = 1,
            base64JpegImage = base64JpegImage
        )

        // If a previously cached model was retired/unavailable, automatically re-discover once
        if (!response.isSuccess && response.errorCategory == ErrorCategory.UNSUPPORTED_MODEL) {
            val rediscovery = client.discoverAndVerifyGemini(apiKey)
            if (rediscovery.success && !rediscovery.verifiedModel.isNullOrBlank()) {
                modelId = rediscovery.verifiedModel
                preferences.setVerifiedModel(ProviderType.GEMINI, modelId)
                response = client.sendChatWithBoundedRetry(
                    provider = ProviderType.GEMINI,
                    apiKey = apiKey,
                    modelName = modelId,
                    messages = messages,
                    maxRetries = 0,
                    base64JpegImage = base64JpegImage
                )
            }
        }

        return if (response.isSuccess) {
            RoutedResponse(
                success = true,
                replyText = response.replyText,
                providerUsed = ProviderType.GEMINI
            )
        } else {
            RoutedResponse(
                success = false,
                replyText = response.errorMessage,
                providerUsed = null
            )
        }
    }
}
