package com.personal.bengaliagent.data.security

import com.personal.bengaliagent.domain.model.ProviderType

object KeyValidator {

    data class ValidationResult(
        val isValidFormat: Boolean,
        val reason: String
    )

    fun validateFormat(provider: ProviderType, rawKey: String): ValidationResult {
        val trimmed = rawKey.trim()
        if (trimmed.isEmpty()) {
            return ValidationResult(false, "API key cannot be empty. Please enter your Gemini API key.")
        }
        if (trimmed.contains(" ") || trimmed.contains("\n") || trimmed.contains("\t")) {
            return ValidationResult(false, "API key must not contain spaces or line breaks.")
        }
        return when (provider) {
            ProviderType.GEMINI -> {
                if (trimmed.length >= 20) {
                    ValidationResult(true, "Key format is valid.")
                } else {
                    ValidationResult(false, "Invalid Gemini API key format. Keys must be at least 20 characters.")
                }
            }
            ProviderType.GROQ -> {
                if (trimmed.startsWith("gsk_") && trimmed.length >= 30) {
                    ValidationResult(true, "Key format is valid.")
                } else {
                    ValidationResult(false, "Groq API keys typically start with 'gsk_'.")
                }
            }
            ProviderType.OPENROUTER -> {
                if (trimmed.startsWith("sk-or-") && trimmed.length >= 25) {
                    ValidationResult(true, "Key format is valid.")
                } else {
                    ValidationResult(false, "OpenRouter API keys typically start with 'sk-or-'.")
                }
            }
        }
    }

    fun redactSecrets(input: String): String {
        return input
            .replace(Regex("AIza[0-9A-Za-z\\-_]{16,}"), "AIza***REDACTED***")
            .replace(Regex("gsk_[0-9A-Za-z]{16,}"), "gsk_***REDACTED***")
            .replace(Regex("sk-or-[0-9A-Za-z\\-_]{16,}"), "sk-or-***REDACTED***")
    }
}
