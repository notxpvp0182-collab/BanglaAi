package com.personal.bengaliagent.network

import com.personal.bengaliagent.data.prefs.AppPreferences
import com.personal.bengaliagent.data.security.SecureKeyStore
import com.personal.bengaliagent.domain.model.ChatMessage
import com.personal.bengaliagent.domain.model.ProviderType

class AiClientRouter(
    private val keyStore: SecureKeyStore,
    private val preferences: AppPreferences,
    private val client: LlmProviderClient = LlmProviderClient()
) {

    data class RoutedResponse(
        val success: Boolean,
        val replyText: String,
        val providerUsed: ProviderType?,
        val modelUsed: String?
    )

    suspend fun sendMessageWithFailover(
        messages: List<ChatMessage>,
        base64JpegImage: String? = null
    ): RoutedResponse {
        if (!preferences.isCloudSharingConsented()) {
            return RoutedResponse(
                success = false,
                replyText = "ক্লাউড এআই ডেটা শেয়ারিং বন্ধ করা আছে। সেটিংস থেকে এটি চালু করুন।",
                providerUsed = null,
                modelUsed = null
            )
        }

        val preferred = preferences.getPreferredProvider()
        val failoverList = preferences.getFailoverOrder()
        val orderedCandidates = (listOf(preferred) + failoverList).distinct()

        val configuredCandidates = orderedCandidates.filter { provider ->
            !keyStore.getApiKey(provider).isNullOrBlank()
        }

        if (configuredCandidates.isEmpty()) {
            return RoutedResponse(
                success = false,
                replyText = "কোনো এআই প্রোভাইডার (Gemini, Groq, বা OpenRouter) সেটআপ করা নেই। অনুগ্রহ করে 'API ও সেটিংস' থেকে অন্তত একটি API Key যোগ ও যাচাই করুন।",
                providerUsed = null,
                modelUsed = null
            )
        }

        val errors = mutableListOf<String>()
        for (provider in configuredCandidates) {
            val apiKey = keyStore.getApiKey(provider) ?: continue
            val model = preferences.getSelectedModel(provider)
            val response = client.sendChatWithBoundedRetry(
                provider = provider,
                apiKey = apiKey,
                modelName = model,
                messages = messages,
                maxRetries = 1,
                base64JpegImage = base64JpegImage
            )
            if (response.isSuccess) {
                return RoutedResponse(
                    success = true,
                    replyText = response.replyText,
                    providerUsed = provider,
                    modelUsed = model
                )
            } else {
                errors.add("${provider.displayName}: ${response.errorMessageBn}")
            }
        }

        return RoutedResponse(
            success = false,
            replyText = "সবগুলো কনফিগার করা প্রোভাইডার ব্যর্থ হয়েছে:\n" + errors.joinToString("\n"),
            providerUsed = null,
            modelUsed = null
        )
    }
}
