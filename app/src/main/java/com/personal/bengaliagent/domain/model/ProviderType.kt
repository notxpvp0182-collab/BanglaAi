package com.personal.bengaliagent.domain.model

enum class ProviderType(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val realtimeWsUrl: String = ""
) {
    GEMINI(
        id = "gemini",
        displayName = "Google Gemini",
        baseUrl = "https://generativelanguage.googleapis.com/v1beta",
        realtimeWsUrl = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
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
    ),
    OPENAI(
        id = "openai",
        displayName = "OpenAI",
        baseUrl = "https://api.openai.com/v1"
    ),
    GROK(
        id = "grok",
        displayName = "xAI Grok",
        baseUrl = "https://api.x.ai/v1",
        realtimeWsUrl = "wss://api.x.ai/v1/realtime"
    );

    companion object {
        fun fromId(id: String): ProviderType =
            entries.firstOrNull { it.id.equals(id.trim(), ignoreCase = true) } ?: GEMINI
    }
}
