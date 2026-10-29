package com.personal.bengaliagent.domain.model

enum class ProviderType(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val curatedFallbackModels: List<String>,
    val defaultRecommendedModel: String
) {
    GEMINI(
        id = "gemini",
        displayName = "Google Gemini",
        baseUrl = "https://generativelanguage.googleapis.com/v1beta",
        curatedFallbackModels = listOf(
            "gemini-2.5-flash",
            "gemini-2.5-pro",
            "gemini-2.0-flash"
        ),
        defaultRecommendedModel = "gemini-2.5-flash"
    ),
    GROQ(
        id = "groq",
        displayName = "Groq Cloud",
        baseUrl = "https://api.groq.com/openai/v1",
        curatedFallbackModels = listOf(
            "llama-3.3-70b-versatile",
            "llama-3.1-8b-instant",
            "mixtral-8x7b-32768"
        ),
        defaultRecommendedModel = "llama-3.3-70b-versatile"
    ),
    OPENROUTER(
        id = "openrouter",
        displayName = "OpenRouter",
        baseUrl = "https://openrouter.ai/api/v1",
        curatedFallbackModels = listOf(
            "google/gemini-2.5-flash",
            "meta-llama/llama-3.3-70b-instruct",
            "anthropic/claude-3.5-haiku"
        ),
        defaultRecommendedModel = "google/gemini-2.5-flash"
    );

    companion object {
        fun fromId(id: String): ProviderType =
            entries.firstOrNull { it.id == id } ?: GEMINI
    }
}
