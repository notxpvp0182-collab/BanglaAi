package com.personal.bengaliagent.data.security

import com.personal.bengaliagent.domain.model.ProviderType

object KeyValidator {

    data class ValidationResult(
        val isValidFormat: Boolean,
        val reasonBn: String
    )

    fun validateFormat(provider: ProviderType, rawKey: String): ValidationResult {
        val trimmed = rawKey.trim()
        if (trimmed.isEmpty()) {
            return ValidationResult(false, "API Key খালি রাখা যাবে না।")
        }
        if (trimmed.contains(" ") || trimmed.contains("\n")) {
            return ValidationResult(false, "API Key-এর ভেতরে কোনো স্পেস বা নতুন লাইন থাকা যাবে না।")
        }
        return when (provider) {
            ProviderType.GEMINI -> {
                if (trimmed.startsWith("AIza") && trimmed.length >= 30) {
                    ValidationResult(true, "ফরম্যাট সঠিক মনে হচ্ছে।")
                } else {
                    ValidationResult(false, "Gemini API Key সাধারণত 'AIza' দিয়ে শুরু হয় এবং অন্তত ৩০ অক্ষরের হয়।")
                }
            }
            ProviderType.GROQ -> {
                if (trimmed.startsWith("gsk_") && trimmed.length >= 35) {
                    ValidationResult(true, "ফরম্যাট সঠিক মনে হচ্ছে।")
                } else {
                    ValidationResult(false, "Groq API Key সাধারণত 'gsk_' দিয়ে শুরু হয়।")
                }
            }
            ProviderType.OPENROUTER -> {
                if (trimmed.startsWith("sk-or-") && trimmed.length >= 30) {
                    ValidationResult(true, "ফরম্যাট সঠিক মনে হচ্ছে।")
                } else {
                    ValidationResult(false, "OpenRouter API Key সাধারণত 'sk-or-' দিয়ে শুরু হয়।")
                }
            }
        }
    }

    fun redactSecrets(input: String): String {
        return input
            .replace(Regex("AIza[0-9A-Za-z\\-_]{20,}"), "AIza***REDACTED***")
            .replace(Regex("gsk_[0-9A-Za-z]{20,}"), "gsk_***REDACTED***")
            .replace(Regex("sk-or-[0-9A-Za-z\\-_]{20,}"), "sk-or-***REDACTED***")
    }
}
