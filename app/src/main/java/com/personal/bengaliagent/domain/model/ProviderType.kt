package com.personal.bengaliagent.domain.model

enum class ProviderType(
    val id: String,
    val displayName: String,
    val baseUrl: String
) {
    GEMINI(
        id = "gemini",
        displayName = "Google Gemini",
        baseUrl = "https://generativelanguage.googleapis.com/v1beta"
    ),
    GROQ(
        id = "groq",
        displayName = "Groq Cloud",
        baseUrl = "https://api.groq.com/openai/v1"
    ),
    OPENROUTER(
        id = "openrouter",
        displayName = "OpenRouter",
        baseUrl = "https://openrouter.ai/api/v1"
    );

    companion object {
        fun fromId(id: String): ProviderType =
            entries.firstOrNull { it.id == id } ?: GEMINI
    }
}
