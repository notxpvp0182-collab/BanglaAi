package com.personal.bengaliagent.domain.model

enum class RiskLevel {
    LOW_RISK,
    SENSITIVE,
    DESTRUCTIVE
}

object ActionSafetyClassifier {
    private val destructiveKeywords = listOf(
        "delete", "remove", "format", "uninstall", "clear data", "factory reset",
        "মুছে", "ডিলিট", "আনইনস্টল", "ফরম্যাট"
    )

    private val sensitiveKeywords = listOf(
        "send", "pay", "transfer", "purchase", "post", "publish", "password", "pin", "otp",
        "পাঠাও", "পেমেন্ট", "টাকা", "পোস্ট", "পাসওয়ার্ড", "ওটিপি"
    )

    fun classifyAction(actionType: String, description: String): RiskLevel {
        val normalized = "$actionType $description".lowercase()
        if (destructiveKeywords.any { normalized.contains(it) }) {
            return RiskLevel.DESTRUCTIVE
        }
        if (sensitiveKeywords.any { normalized.contains(it) }) {
            return RiskLevel.SENSITIVE
        }
        return RiskLevel.LOW_RISK
    }
}
