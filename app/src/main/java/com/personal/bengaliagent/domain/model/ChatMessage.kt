package com.personal.bengaliagent.domain.model

data class ChatMessage(
    val id: Long,
    val role: Role,
    val text: String,
    val providerUsed: String? = null,
    val timestampMs: Long = System.currentTimeMillis()
) {
    enum class Role {
        USER,
        ASSISTANT,
        SYSTEM
    }
}
