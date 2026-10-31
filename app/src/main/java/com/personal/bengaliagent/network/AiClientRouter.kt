package com.personal.bengaliagent.network

import com.personal.bengaliagent.data.prefs.AppPreferences
import com.personal.bengaliagent.data.security.CredentialRole
import com.personal.bengaliagent.data.security.SecureKeyStore
import com.personal.bengaliagent.domain.model.ChatMessage
import com.personal.bengaliagent.domain.model.ErrorCategory
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

    var lastLatencyMs: Long = 350L
        private set

    data class RoutedResponse(
        val success: Boolean,
        val replyText: String,
        val providerUsed: ProviderType?
    )

    data class AgentVoiceReply(
        val provider: ProviderType,
        val modelId: String,
        val spokenText: String,
        val latencyMs: Long
    )

    fun networkQualityBadge(): String {
        return when {
            lastLatencyMs <= 900L -> "Excellent (${lastLatencyMs}ms)"
            lastLatencyMs <= 2000L -> "Good (${lastLatencyMs}ms)"
            else -> "Weak (${lastLatencyMs}ms)"
        }
    }

    suspend fun connectAndVerifyGemini(rawKey: String): ProviderTestResult =
        connectAndVerifyProvider(ProviderType.GEMINI, rawKey, CredentialRole.PRIMARY_VOICE)

    suspend fun connectAndVerifyProvider(
        provider: ProviderType,
        rawKey: String,
        role: CredentialRole = CredentialRole.PRIMARY_VOICE
    ): ProviderTestResult {
        val trimmed = rawKey.trim()
        val result = client.discoverAndVerifyProvider(provider, trimmed)
        if (result.success && !result.verifiedModel.isNullOrBlank()) {
            keyStore.saveApiKey(provider, trimmed, role)
            keyStore.setProviderVerified(provider, true)
            preferences.setVerifiedModel(provider, result.verifiedModel)
            preferences.setLiveVoiceModel(provider, result.liveVoiceModel ?: result.verifiedModel)
            preferences.setPreferredProvider(provider)
            preferences.setLastConnectionMessage(result.userMessage)
            lastLatencyMs = result.latencyMs

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
        } else if (role == CredentialRole.PRIMARY_VOICE) {
            keyStore.setProviderVerified(provider, false)
            preferences.setLastConnectionMessage(result.userMessage)
        }
        return result
    }

    suspend fun disconnectProvider(provider: ProviderType) {
        keyStore.deleteApiKey(provider, null)
        preferences.setVerifiedModel(provider, null)
        preferences.setLiveVoiceModel(provider, null)
    }

    fun getConnectedProviders(): List<ProviderType> {
        return ProviderType.entries.filter { p ->
            keyStore.getKeysForRoleWithBackup(p, CredentialRole.PRIMARY_VOICE).isNotEmpty()
        }
    }

    /**
     * Queries active AI agents using role-specific API keys (Voice / Vision / Intelligence)
     * with automatic rotation to the Backup API key if a primary key fails or hits quota.
     */
    suspend fun queryActiveCallAgents(
        messages: List<ChatMessage>,
        base64JpegImage: String? = null,
        multiAgentMode: Boolean = false
    ): List<AgentVoiceReply> = coroutineScope {
        val connected = getConnectedProviders()
        if (connected.isEmpty()) return@coroutineScope emptyList()

        val preferred = preferences.getPreferredProvider()
        val ordered = (listOf(preferred) + connected).distinct().filter { connected.contains(it) }

        val targetAgents = if (multiAgentMode && ordered.size > 1) {
            ordered.take(2)
        } else {
            ordered.take(1)
        }

        val roleNeeded = if (!base64JpegImage.isNullOrBlank()) {
            CredentialRole.VISION
        } else {
            CredentialRole.PRIMARY_VOICE
        }

        val deferredReplies = targetAgents.map { provider ->
            async {
                executeWithRoleKeyRotation(provider, roleNeeded, messages, base64JpegImage)
            }
        }

        val results = deferredReplies.awaitAll().filterNotNull()
        if (results.isNotEmpty()) {
            lastLatencyMs = results.first().latencyMs
            return@coroutineScope results
        }

        // Failover to any remaining connected provider
        for (fallbackProvider in ordered.drop(targetAgents.size)) {
            val reply = executeWithRoleKeyRotation(
                fallbackProvider,
                roleNeeded,
                messages,
                base64JpegImage
            )
            if (reply != null) {
                lastLatencyMs = reply.latencyMs
                return@coroutineScope listOf(reply)
            }
        }

        emptyList()
    }

    private suspend fun executeWithRoleKeyRotation(
        provider: ProviderType,
        role: CredentialRole,
        messages: List<ChatMessage>,
        base64JpegImage: String?
    ): AgentVoiceReply? {
        val keys = keyStore.getKeysForRoleWithBackup(provider, role)
        if (keys.isEmpty()) return null

        val modelId = preferences.getVerifiedModel(provider)
            ?: preferences.getLiveVoiceModel(provider)
            ?: defaultModelFor(provider)

        for (apiKey in keys) {
            val startMs = System.currentTimeMillis()
            val resp = client.sendChatWithBoundedRetry(
                provider = provider,
                apiKey = apiKey,
                modelName = modelId,
                messages = messages,
                maxRetries = 0,
                base64JpegImage = base64JpegImage
            )
            val elapsed = System.currentTimeMillis() - startMs
            if (resp.isSuccess && resp.replyText.isNotBlank()) {
                return AgentVoiceReply(
                    provider = provider,
                    modelId = modelId.substringAfter(":"),
                    spokenText = resp.replyText,
                    latencyMs = elapsed
                )
            }
            // If key failed with quota or auth error, loop rotates immediately to Backup Key!
            if (resp.errorCategory != ErrorCategory.QUOTA_OR_RATE_LIMIT &&
                resp.errorCategory != ErrorCategory.INVALID_KEY &&
                resp.errorCategory != ErrorCategory.NETWORK_ERROR
            ) {
                break
            }
        }
        return null
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
