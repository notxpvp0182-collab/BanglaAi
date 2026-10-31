package com.personal.bengaliagent.domain.model

enum class AgentState(val labelBn: String, val labelEn: String) {
    READY("Idle", "Idle"),
    CALLING("Calling...", "Calling..."),
    CONNECTING("Connecting...", "Connecting..."),
    CONNECTED("Connected", "Connected"),
    LISTENING("Listening...", "Listening..."),
    PROCESSING("Processing...", "Processing..."),
    THINKING("Thinking...", "Thinking..."),
    ACTING("Executing Action...", "Executing Action..."),
    SPEAKING("Speaking...", "Speaking..."),
    RECONNECTING("Reconnecting...", "Reconnecting..."),
    PAUSED("Muted", "Muted"),
    MUTED("Mic Muted", "Mic Muted"),
    SCREEN_SHARING("Screen Sharing Active", "Screen Sharing Active"),
    CALL_ENDED("Call Ended", "Call Ended"),
    OFFLINE("Connection Failed", "Connection Failed"),
    ERROR("Connection Failed", "Connection Failed")
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
