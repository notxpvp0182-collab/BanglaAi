package com.personal.bengaliagent.domain.model

data class DiscoveredModel(
    val id: String,
    val displayName: String,
    val provider: ProviderType = ProviderType.GEMINI,
    val supportedGenerationMethods: List<String> = emptyList(),
    val supportsLiveAudio: Boolean = false,
    val supportsVision: Boolean = false,
    val supportsTools: Boolean = true,
    val speedTier: String = "Fast",
    val inputTokenLimit: Int = 0,
    val outputTokenLimit: Int = 0
) {
    fun summaryBadge(): String {
        val caps = buildList {
            if (supportsLiveAudio) add("Live Audio")
            if (supportsVision) add("Vision")
            if (supportsTools) add("Tools")
        }.joinToString(" • ")
        return "${provider.displayName} • $speedTier • $caps"
    }
}

data class TaskModelAssignment(
    val voiceCallModelId: String,
    val reasoningModelId: String,
    val screenVisionModelId: String,
    val generalTaskModelId: String
)

data class ProviderTestResult(
    val success: Boolean,
    val provider: ProviderType,
    val latencyMs: Long,
    val verifiedModel: String?,
    val liveVoiceModel: String? = null,
    val discoveredModels: List<DiscoveredModel> = emptyList(),
    val userMessage: String,
    val errorCategory: ErrorCategory = ErrorCategory.NONE
)

enum class ErrorCategory {
    NONE,
    INVALID_KEY,
    PERMISSION_DENIED,
    QUOTA_OR_RATE_LIMIT,
    NO_ELIGIBLE_MODELS,
    UNSUPPORTED_MODEL,
    NETWORK_ERROR,
    SERVER_ERROR
}
