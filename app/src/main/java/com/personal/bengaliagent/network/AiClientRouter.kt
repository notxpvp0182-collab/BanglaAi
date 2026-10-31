package com.personal.bengaliagent.network

import com.personal.bengaliagent.data.prefs.AppPreferences
import com.personal.bengaliagent.data.security.SecureKeyStore
import com.personal.bengaliagent.domain.model.ChatMessage
import com.personal.bengaliagent.domain.model.ProviderTestResult
import com.personal.bengaliagent.domain.model.ProviderType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

    data class AgentVoiceReply(
        val provider: ProviderType,
        val modelId: String,
        val spokenText: String
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

    fun getConnectedProviders(): List<ProviderType> {
        return ProviderType.entries.filter { p ->
            !keyStore.getApiKey(p).isNullOrBlank() && keyStore.isProviderVerified(p)
        }.ifEmpty {
            ProviderType.entries.filter { p -> !keyStore.getApiKey(p).isNullOrBlank() }
        }
    }

    /**
     * Queries either the primary connected AI agent OR multiple connected AI agents in parallel
     * when Multi-AI Agent Mode is enabled!
     */
    suspend fun queryActiveCallAgents(
        messages: List<ChatMessage>,
        base64JpegImage: String? = null,
        multiAgentMode: Boolean = true
    ): List<AgentVoiceReply> = coroutineScope {
        val connected = getConnectedProviders()
        if (connected.isEmpty()) return@coroutineScope emptyList()

        val preferred = preferences.getPreferredProvider()
        val ordered = (listOf(preferred) + connected).distinct().filter { connected.contains(it) }

        val targetAgents = if (multiAgentMode && ordered.size > 1) {
            ordered.take(3) // Up to 3 AI agents speaking & working together simultaneously
        } else {
            ordered.take(1)
        }

        val deferredReplies = targetAgents.map { provider ->
            async {
                val apiKey = keyStore.getApiKey(provider) ?: return@async null
                val modelId = preferences.getVerifiedModel(provider)
                    ?: preferences.getLiveVoiceModel(provider)
                    ?: defaultModelFor(provider)

                val resp = client.sendChatWithBoundedRetry(
                    provider = provider,
                    apiKey = apiKey,
                    modelName = modelId,
                    messages = messages,
                    maxRetries = 1,
                    base64JpegImage = base64JpegImage
                )
                if (resp.isSuccess && resp.replyText.isNotBlank()) {
                    AgentVoiceReply(
                        provider = provider,
                        modelId = modelId.substringAfter(":"),
                        spokenText = resp.replyText
                    )
                } else {
                    null
                }
            }
        }

        val results = deferredReplies.awaitAll().filterNotNull()
        if (results.isNotEmpty()) return@coroutineScope results

        // Fallback: if the primary failed and we weren't in multi-agent mode, try remaining connected providers
        for (fallbackProvider in ordered.drop(targetAgents.size)) {
            val apiKey = keyStore.getApiKey(fallbackProvider) ?: continue
            val modelId = preferences.getVerifiedModel(fallbackProvider) ?: defaultModelFor(fallbackProvider)
            val resp = client.sendChatWithBoundedRetry(
                provider = fallbackProvider,
                apiKey = apiKey,
                modelName = modelId,
                messages = messages,
                maxRetries = 0,
                base64JpegImage = base64JpegImage
            )
            if (resp.isSuccess && resp.replyText.isNotBlank()) {
                return@coroutineScope listOf(
                    AgentVoiceReply(fallbackProvider, modelId, resp.replyText)
                )
            }
        }

        emptyList()
    }

    suspend fun sendMessageWithFailover(
        messages: List<ChatMessage>,
        base64JpegImage: String? = null
    ): RoutedResponse {
        val replies = queryActiveCallAgents(messages, base64JpegImage, multiAgentMode = false)
        val first = replies.firstOrNull()
        return if (first != null) {
            RoutedResponse(true, first.spokenText, first.provider)
        } else {
            RoutedResponse(
                false,
                "No AI provider responded. Check your API keys in Settings.",
                null
            )
        }
    }

    private fun defaultModelFor(provider: ProviderType): String = when (provider) {
        ProviderType.GEMINI -> "gemini-2.5-flash"
        ProviderType.GROQ -> "llama-3.3-70b-versatile"
        ProviderType.OPENROUTER -> "google/gemini-2.5-flash"
        ProviderType.OPENAI -> "gpt-4o-mini"
        ProviderType.GROK -> "grok-3-mini-fast"
    }
}
