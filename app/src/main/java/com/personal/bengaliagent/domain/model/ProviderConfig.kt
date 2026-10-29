package com.personal.bengaliagent.domain.model

data class DiscoveredModel(
    val id: String,
    val displayName: String,
    val supportedGenerationMethods: List<String>,
    val inputTokenLimit: Int = 0,
    val outputTokenLimit: Int = 0
)

data class ProviderTestResult(
    val success: Boolean,
    val provider: ProviderType,
    val latencyMs: Long,
    val verifiedModel: String?,
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
