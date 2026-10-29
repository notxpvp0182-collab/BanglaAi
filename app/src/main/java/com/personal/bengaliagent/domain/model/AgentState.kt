package com.personal.bengaliagent.domain.model

enum class AgentState(val labelBn: String, val labelEn: String) {
    READY("Ready", "Ready"),
    LISTENING("Listening...", "Listening..."),
    THINKING("Thinking...", "Thinking..."),
    ACTING("Acting...", "Acting..."),
    PAUSED("Paused", "Paused"),
    MUTED("Mic Muted", "Mic Muted"),
    SCREEN_SHARING("Screen Sharing Active", "Screen Sharing Active"),
    OFFLINE("Offline", "Offline"),
    ERROR("Attention Required", "Attention Required")
}
