package com.personal.bengaliagent.domain.model

enum class AgentState(val labelBn: String, val labelEn: String) {
    READY("প্রস্তুত (Ready)", "Ready"),
    LISTENING("শুনছি... (Listening)", "Listening"),
    THINKING("চিন্তা করছি... (Thinking)", "Thinking"),
    ACTING("কাজ চলছে... (Acting)", "Acting"),
    PAUSED("বিরতি (Paused)", "Paused"),
    MUTED("মাইক বন্ধ (Muted)", "Muted"),
    SCREEN_SHARING("স্ক্রিন শেয়ার চালু (Screen Sharing)", "Screen Sharing"),
    OFFLINE("অফলাইন (Offline)", "Offline"),
    ERROR("ত্রুটি (Error)", "Error")
}
