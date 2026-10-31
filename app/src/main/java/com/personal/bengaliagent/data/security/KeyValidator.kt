package com.personal.bengaliagent.data.security

import com.personal.bengaliagent.domain.model.ProviderType

object KeyValidator {

    data class ValidationResult(
        val isValidFormat: Boolean,
        val reason: String
    )

    fun validateFormat(
        @Suppress("UNUSED_PARAMETER") provider: ProviderType,
        rawKey: String
    ): ValidationResult {
        val trimmed = rawKey.trim()
            .removeSurrounding("\"")
            .removeSurrounding("'")
            .trim()

        if (trimmed.isEmpty()) {
            return ValidationResult(false, "API key cannot be empty.")
        }
        if (trimmed.contains(" ") || trimmed.contains("\n") || trimmed.contains("\t")) {
            return ValidationResult(false, "API key must not contain spaces or line breaks.")
        }
        if (trimmed.length < 8) {
            return ValidationResult(false, "API key is too short.")
        }
        return ValidationResult(true, "Credential string ready for server authentication.")
    }

    fun redactSecrets(input: String): String {
        return input
            .replace(Regex("AIza[0-9A-Za-z\\-_]{12,}"), "AIza***REDACTED***")
            .replace(Regex("AQ\\.[0-9A-Za-z\\-_\\.]{12,}"), "AQ.***REDACTED***")
            .replace(Regex("xai-[0-9A-Za-z\\-_]{12,}"), "xai-***REDACTED***")
            .replace(Regex("gsk_[0-9A-Za-z]{12,}"), "gsk_***REDACTED***")
            .replace(Regex("sk-or-[0-9A-Za-z\\-_]{12,}"), "sk-or-***REDACTED***")
    }
}
