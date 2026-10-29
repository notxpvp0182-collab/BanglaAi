package com.personal.bengaliagent.data.repository

import com.personal.bengaliagent.data.db.AgentDao
import com.personal.bengaliagent.data.db.MemoryFactEntity
import com.personal.bengaliagent.data.db.SummaryEntity
import com.personal.bengaliagent.data.db.TaskHistoryEntity
import com.personal.bengaliagent.data.db.TranscriptEntity
import com.personal.bengaliagent.data.security.KeyValidator
import com.personal.bengaliagent.domain.model.ChatMessage
import org.json.JSONArray
import org.json.JSONObject

class MemoryRepository(private val dao: AgentDao) {

    /**
     * Sanitizes sensitive secrets (API keys, passwords, OTPs, card numbers) so they are
     * never persisted in long-term SQLite memory.
     */
    fun sanitizeForStorage(rawText: String): String {
        var cleaned = KeyValidator.redactSecrets(rawText)
        // Redact explicit password/PIN/OTP patterns
        cleaned = cleaned.replace(
            Regex("(?i)(password|passcode|pin|otp|পাসওয়ার্ড|পিন|ওটিপি)\\s*[:=isহলো]*\\s*\\S+"),
            "$1: [REDACTED]"
        )
        return cleaned.trim()
    }

    suspend fun saveChatMessage(msg: ChatMessage) {
        val safeText = sanitizeForStorage(msg.text)
        dao.insertTranscript(
            TranscriptEntity(
                role = msg.role.name,
                content = safeText,
                providerUsed = msg.providerUsed,
                timestampMs = msg.timestampMs
            )
        )
        // Automatically extract explicit user memory commands:
        // e.g. "মনে রেখো ..." or "Remember that ..."
        if (msg.role == ChatMessage.Role.USER) {
            extractExplicitUserFactIfPresent(safeText)
        }
    }

    private suspend fun extractExplicitUserFactIfPresent(userText: String) {
        val triggers = listOf("মনে রেখো", "মনে রাখবে", "remember that", "remember:")
        val matchedTrigger = triggers.firstOrNull { userText.contains(it, ignoreCase = true) } ?: return
        val factBody = userText.substringAfter(matchedTrigger, userText).trim()
            .removePrefix(":").removePrefix("-").trim()
        if (factBody.length >= 4 && !factBody.contains("[REDACTED]")) {
            dao.insertMemoryFact(
                MemoryFactEntity(
                    category = "PREFERENCE",
                    title = factBody.take(40),
                    details = factBody,
                    isUserConfirmed = true
                )
            )
        }
    }

    suspend fun loadRecentMessages(limit: Int = 50): List<ChatMessage> {
        return dao.getRecentTranscripts(limit).reversed().map { entity ->
            val role = runCatching { ChatMessage.Role.valueOf(entity.role) }
                .getOrDefault(ChatMessage.Role.SYSTEM)
            ChatMessage(
                id = entity.id,
                role = role,
                text = entity.content,
                providerUsed = entity.providerUsed,
                timestampMs = entity.timestampMs
            )
        }
    }

    suspend fun addOrUpdateFact(
        id: Long = 0,
        category: String,
        title: String,
        details: String
    ): Long {
        val safeTitle = sanitizeForStorage(title)
        val safeDetails = sanitizeForStorage(details)
        return if (id == 0L) {
            dao.insertMemoryFact(
                MemoryFactEntity(
                    category = category,
                    title = safeTitle,
                    details = safeDetails,
                    isUserConfirmed = true,
                    updatedAtMs = System.currentTimeMillis()
                )
            )
        } else {
            val updated = MemoryFactEntity(
                id = id,
                category = category,
                title = safeTitle,
                details = safeDetails,
                isUserConfirmed = true,
                updatedAtMs = System.currentTimeMillis()
            )
            dao.updateMemoryFact(updated)
            id
        }
    }

    suspend fun getAllFacts(): List<MemoryFactEntity> = dao.getAllMemoryFacts()

    suspend fun searchFacts(query: String): List<MemoryFactEntity> {
        if (query.isBlank()) return dao.getAllMemoryFacts()
        return dao.searchMemoryFacts(query.trim())
    }

    suspend fun deleteFact(id: Long) = dao.deleteMemoryFactById(id)

    suspend fun recordRollingSummary(summaryText: String, startMs: Long, endMs: Long) {
        dao.insertSummary(
            SummaryEntity(
                summaryText = sanitizeForStorage(summaryText),
                startTimestampMs = startMs,
                endTimestampMs = endMs
            )
        )
    }

    suspend fun recordTaskOutcome(
        goal: String,
        status: String,
        outcome: String,
        steps: Int
    ) {
        dao.insertTaskHistory(
            TaskHistoryEntity(
                userGoal = sanitizeForStorage(goal),
                status = status,
                outcomeSummary = sanitizeForStorage(outcome),
                stepsExecuted = steps
            )
        )
    }

    /**
     * Builds a compact, relevant memory snippet to ground the AI's response
     * without uploading the entire database.
     */
    suspend fun buildRelevantMemoryContext(userQuery: String): String {
        val keywords = userQuery
            .split(" ", ",", "?", "।", "-")
            .map { it.trim() }
            .filter { it.length >= 3 }
            .take(5)

        val matchedFacts = LinkedHashSet<MemoryFactEntity>()
        for (kw in keywords) {
            matchedFacts.addAll(dao.searchMemoryFacts(kw, limit = 5))
        }
        if (matchedFacts.isEmpty()) {
            matchedFacts.addAll(dao.getAllMemoryFacts().take(5))
        }

        val matchedTranscripts = LinkedHashSet<TranscriptEntity>()
        for (kw in keywords) {
            matchedTranscripts.addAll(dao.searchTranscripts(kw, limit = 4))
        }

        val recentSummaries = dao.getRecentSummaries(2)
        val recentTasks = dao.getRecentTasks(3)

        val sb = StringBuilder()
        if (matchedFacts.isNotEmpty()) {
            sb.appendLine("[সংরক্ষিত তথ্য ও পছন্দ (Saved User Facts)]:")
            matchedFacts.take(6).forEach {
                sb.appendLine("- (${it.category}) ${it.title}: ${it.details}")
            }
        }
        if (recentSummaries.isNotEmpty()) {
            sb.appendLine("[পূর্ববর্তী কথোপকথনের সারাংশ (Summaries)]:")
            recentSummaries.forEach {
                sb.appendLine("- ${it.summaryText}")
            }
        }
        if (recentTasks.isNotEmpty()) {
            sb.appendLine("[সাম্প্রতিক কাজের ইতিহাস (Recent Tasks)]:")
            recentTasks.forEach {
                sb.appendLine("- ${it.userGoal} -> ${it.status}: ${it.outcomeSummary}")
            }
        }
        if (matchedTranscripts.isNotEmpty()) {
            sb.appendLine("[প্রাসঙ্গিক পুরনো বার্তা (Relevant Past Transcripts)]:")
            matchedTranscripts.take(4).forEach {
                sb.appendLine("- ${it.role}: ${it.content.take(120)}")
            }
        }
        return sb.toString().trim()
    }

    suspend fun applyRetentionPolicy(retentionDays: Int) {
        if (retentionDays <= 0) return
        val cutoff = System.currentTimeMillis() - (retentionDays * 24L * 60L * 60L * 1000L)
        dao.deleteTranscriptsOlderThan(cutoff)
    }

    suspend fun clearAllMemory() {
        dao.clearAllTranscripts()
        dao.clearAllMemoryFacts()
        dao.clearAllSummaries()
        dao.clearAllTaskHistory()
    }

    suspend fun exportMemoryAsJson(): String {
        val root = JSONObject()
        val factsArr = JSONArray()
        for (f in dao.getAllMemoryFacts()) {
            factsArr.put(
                JSONObject()
                    .put("id", f.id)
                    .put("category", f.category)
                    .put("title", f.title)
                    .put("details", f.details)
                    .put("updatedAtMs", f.updatedAtMs)
            )
        }
        val tasksArr = JSONArray()
        for (t in dao.getRecentTasks(100)) {
            tasksArr.put(
                JSONObject()
                    .put("goal", t.userGoal)
                    .put("status", t.status)
                    .put("outcome", t.outcomeSummary)
                    .put("updatedAtMs", t.updatedAtMs)
            )
        }
        root.put("exportedAtMs", System.currentTimeMillis())
        root.put("facts", factsArr)
        root.put("tasks", tasksArr)
        return root.toString(2)
    }
}
