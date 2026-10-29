package com.personal.bengaliagent.domain.model

data class ProviderTestResult(
    val success: Boolean,
    val provider: ProviderType,
    val latencyMs: Long,
    val verifiedModel: String?,
    val userMessageBn: String,
    val errorCategory: ErrorCategory = ErrorCategory.NONE
)

enum class ErrorCategory {
    NONE,
    INVALID_KEY,
    QUOTA_OR_RATE_LIMIT,
    UNSUPPORTED_MODEL,
    NETWORK_ERROR,
    SERVER_ERROR
}

data class ModelListResult(
    val models: List<String>,
    val isFromLiveApi: Boolean,
    val recommendedModel: String,
    val statusNote: String
)
