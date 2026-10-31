package com.personal.bengaliagent.domain.model

enum class AgentState(val labelBn: String, val labelEn: String) {
    READY("Idle • Ready", "Idle • Ready"),
    LISTENING("Listening...", "Listening..."),
    PROCESSING("Processing Speech...", "Processing Speech..."),
    THINKING("Thinking...", "Thinking..."),
    ACTING("Executing Action...", "Executing Action..."),
    SPEAKING("Speaking...", "Speaking..."),
    PAUSED("Paused", "Paused"),
    MUTED("Mic Muted", "Mic Muted"),
    SCREEN_SHARING("Screen Sharing Active", "Screen Sharing Active"),
    OFFLINE("Disconnected • Offline", "Disconnected • Offline"),
    ERROR("Error • Attention Required", "Error • Attention Required")
}

enum class ConnectionState(val displayLabel: String) {
    INITIALIZING("Initializing..."),
    CONNECTING("Connecting..."),
    CONNECTED("Connected"),
    RECONNECTING("Reconnecting..."),
    DISCONNECTED("Disconnected"),
    AUTHENTICATION_ERROR("Authentication Error"),
    NETWORK_ERROR("Network Error")
}
