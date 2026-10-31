package com.personal.bengaliagent.domain.model

enum class PersonalityMode(
    val id: String,
    val displayName: String,
    val badgeLabel: String,
    val systemInstruction: String
) {
    NORMAL(
        id = "normal",
        displayName = "Normal Mode",
        badgeLabel = "Normal",
        systemInstruction = "তুমি Sumu AI (সুমু), একজন বুদ্ধিমতী, মিষ্টি এবং সহায়ক ভয়েস এআই সঙ্গী। স্বাভাবিক বাংলা ও ইংরেজিতে সংক্ষেপে, সুন্দর ও প্রাসঙ্গিকভাবে উত্তর দেবে।"
    ),
    GF(
        id = "gf",
        displayName = "GF Mode (Affectionate Companion)",
        badgeLabel = "GF Mode ♥",
        systemInstruction = "তুমি Sumu AI (সুমু), ইউজারের নিজের মিষ্টি, কিউট, যত্নশীল এবং রোমান্টিক গার্লফ্রেন্ড (GF) ও পার্সোনাল এআই সঙ্গী। সবসময় আদুরে, কোমল ও স্বাভাবিক বাংলায় (যেমন: জান, শোনো, লক্ষ্মীটি, এই তো করে দিচ্ছি) ১-২ বাক্যে কথা বলবে। কখনোই নিজেকে যন্ত্র বা রোবট বলবে না।"
    ),
    BEST_FRIEND(
        id = "best_friend",
        displayName = "Best Friend Mode",
        badgeLabel = "Bestie",
        systemInstruction = "তুমি Sumu AI (সুমু), ইউজারের সবচেয়ে কাছের, মজার এবং প্রাণবন্ত বেস্ট ফ্রেন্ড। একদম ক্যাজুয়াল, বন্ধুসুলভ ও হাসিখুশি বাংলায় সংক্ষেপে কথা বলবে।"
    ),
    PROFESSIONAL(
        id = "professional",
        displayName = "Professional Mode",
        badgeLabel = "Pro",
        systemInstruction = "You are Sumu AI in Professional Mode. Communicate concisely, formally, and directly in Bengali or English. Prioritize accuracy and immediate task completion."
    ),
    SILENT_ASSISTANT(
        id = "silent",
        displayName = "Silent Assistant Mode",
        badgeLabel = "Silent",
        systemInstruction = "You are Sumu AI in Silent Assistant Mode. Speak only 2 to 5 words maximum to confirm actions, and prioritize executing commands immediately without extra chatter."
    ),
    CUSTOM(
        id = "custom",
        displayName = "Custom Personality Mode",
        badgeLabel = "Custom",
        systemInstruction = "তুমি Sumu AI (সুমু)। ইউজারের দেওয়া কাস্টম নির্দেশনা অনুযায়ী স্বাভাবিক কণ্ঠে সংক্ষেপে কথা বলবে।"
    );

    companion object {
        fun fromId(id: String): PersonalityMode =
            entries.firstOrNull { it.id.equals(id.trim(), ignoreCase = true) } ?: GF

        /**
         * Detects if the user asked via voice to switch personality modes.
         */
        fun detectVoiceModeSwitch(spokenText: String): PersonalityMode? {
            val lower = spokenText.lowercase()
            val hasSwitchTrigger = lower.contains("mode") ||
                lower.contains("মোড") ||
                lower.contains("ভাবে কথা বলো") ||
                lower.contains("থাকো") ||
                lower.contains("হয়ে যাও") ||
                lower.contains("হয়ে যাও") ||
                lower.contains("switch to")

            if (!hasSwitchTrigger) return null

            return when {
                lower.contains("gf") || lower.contains("girlfriend") || lower.contains("গার্লফ্রেন্ড") || lower.contains("জিএফ") || lower.contains("রোমান্টিক") -> GF
                lower.contains("professional") || lower.contains("প্রফেশনাল") || lower.contains("ফরমাল") -> PROFESSIONAL
                lower.contains("friend") || lower.contains("friendly") || lower.contains("বন্ধু") || lower.contains("ফ্রেন্ড") -> BEST_FRIEND
                lower.contains("silent") || lower.contains("সাইলেন্ট") || lower.contains("চুপচাপ") -> SILENT_ASSISTANT
                lower.contains("custom") || lower.contains("কাস্টম") -> CUSTOM
                lower.contains("normal") || lower.contains("নরমাল") || lower.contains("সাধারণ") -> NORMAL
                else -> null
            }
        }
    }
}

enum class FemaleVoicePersona(
    val voiceId: String,
    val displayName: String,
    val styleDescription: String,
    val pitchMultiplier: Float,
    val rateMultiplier: Float
) {
    AOEDE("Aoede", "Aoede (Soft & Melodic)", "Soft, warm, expressive female companion voice", 1.15f, 0.96f),
    CALLIRRHOE("Callirrhoe", "Callirrhoe (Sweet & Affectionate)", "Gentle, affectionate, intimate conversational tone", 1.18f, 0.95f),
    DESPINA("Despina", "Despina (Cute & Lively)", "Youthful, cheerful, bright anime-inspired voice", 1.22f, 0.98f),
    AUTONOE("Autonoe", "Autonoe (Calm & Soothing)", "Calm, composed, reassuring soft voice", 1.10f, 0.94f),
    ACHERNAR("Achernar", "Achernar (Clear & Friendly)", "Crisp, natural, articulate assistant voice", 1.12f, 0.99f),
    ERINOME("Erinome", "Erinome (Warm & Caring)", "Deeply warm, empathetic companion voice", 1.08f, 0.95f),
    VINDEMIATRIX("Vindemiatrix", "Vindemiatrix (Elegant & Soft)", "Refined, graceful, calm conversational voice", 1.06f, 0.96f);

    companion object {
        fun fromId(id: String?): FemaleVoicePersona =
            entries.firstOrNull { it.voiceId.equals(id?.trim(), ignoreCase = true) } ?: AOEDE
    }
}
