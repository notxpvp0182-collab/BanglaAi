package com.personal.bengaliagent

import com.personal.bengaliagent.data.db.AgentDao
import com.personal.bengaliagent.data.db.MemoryFactEntity
import com.personal.bengaliagent.data.db.SummaryEntity
import com.personal.bengaliagent.data.db.TaskHistoryEntity
import com.personal.bengaliagent.data.db.TranscriptEntity
import com.personal.bengaliagent.data.repository.MemoryRepository
import com.personal.bengaliagent.domain.model.ChatMessage
import com.personal.bengaliagent.voice.SpeakerVerifier
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class Milestone3And4UnitTest {

    private class FakeInMemoryAgentDao : AgentDao {
        val transcripts = mutableListOf<TranscriptEntity>()
        val facts = mutableListOf<MemoryFactEntity>()
        val summaries = mutableListOf<SummaryEntity>()
        val tasks = mutableListOf<TaskHistoryEntity>()
        private var idSeq = 1L

        override suspend fun insertTranscript(entry: TranscriptEntity): Long {
            val assigned = entry.copy(id = idSeq++)
            transcripts.add(assigned)
            return assigned.id
        }

        override suspend fun getRecentTranscripts(limit: Int): List<TranscriptEntity> =
            transcripts.sortedByDescending { it.timestampMs }.take(limit)

        override suspend fun searchTranscripts(keyword: String, limit: Int): List<TranscriptEntity> =
            transcripts.filter { it.content.contains(keyword, ignoreCase = true) }.take(limit)

        override suspend fun deleteTranscriptsOlderThan(cutoffMs: Long): Int {
            val before = transcripts.size
            transcripts.removeAll { it.timestampMs < cutoffMs }
            return before - transcripts.size
        }

        override suspend fun clearAllTranscripts() = transcripts.clear()

        override suspend fun insertMemoryFact(fact: MemoryFactEntity): Long {
            val assigned = fact.copy(id = idSeq++)
            facts.add(assigned)
            return assigned.id
        }

        override suspend fun updateMemoryFact(fact: MemoryFactEntity) {
            val idx = facts.indexOfFirst { it.id == fact.id }
            if (idx >= 0) facts[idx] = fact
        }

        override suspend fun getAllMemoryFacts(): List<MemoryFactEntity> =
            facts.sortedByDescending { it.updatedAtMs }

        override suspend fun searchMemoryFacts(query: String, limit: Int): List<MemoryFactEntity> =
            facts.filter {
                it.title.contains(query, ignoreCase = true) ||
                    it.details.contains(query, ignoreCase = true)
            }.take(limit)

        override suspend fun deleteMemoryFactById(id: Long) {
            facts.removeAll { it.id == id }
        }

        override suspend fun clearAllMemoryFacts() = facts.clear()

        override suspend fun insertSummary(summary: SummaryEntity): Long {
            val assigned = summary.copy(id = idSeq++)
            summaries.add(assigned)
            return assigned.id
        }

        override suspend fun getRecentSummaries(limit: Int): List<SummaryEntity> =
            summaries.takeLast(limit)

        override suspend fun clearAllSummaries() = summaries.clear()

        override suspend fun insertTaskHistory(task: TaskHistoryEntity): Long {
            val assigned = task.copy(id = idSeq++)
            tasks.add(assigned)
            return assigned.id
        }

        override suspend fun getRecentTasks(limit: Int): List<TaskHistoryEntity> =
            tasks.takeLast(limit)

        override suspend fun clearAllTaskHistory() = tasks.clear()
    }

    @Test
    fun `speaker verifier extracts 16D embedding, zeros raw buffer, and distinguishes voices`() {
        val sampleRate = 16000
        // Synthetic Speaker A (fundamental ~130Hz + harmonics)
        val pcmSpeakerA = ShortArray(sampleRate) { i ->
            val t = i.toDouble() / sampleRate
            val wave = 0.5 * sin(2 * PI * 130 * t) + 0.3 * sin(2 * PI * 260 * t)
            (wave * 18000).toInt().toShort()
        }
        // Synthetic Speaker B (fundamental ~290Hz + higher formant)
        val pcmSpeakerB = ShortArray(sampleRate) { i ->
            val t = i.toDouble() / sampleRate
            val wave = 0.5 * sin(2 * PI * 290 * t) + 0.4 * sin(2 * PI * 1450 * t)
            (wave * 18000).toInt().toShort()
        }

        val embA = SpeakerVerifier.extractEmbedding(pcmSpeakerA, sampleRate, zeroOutBuffer = true)
        assertNotNull(embA)
        // Verify raw PCM buffer was zeroed out for privacy
        assertTrue(pcmSpeakerA.all { it == 0.toShort() })

        val embB = SpeakerVerifier.extractEmbedding(pcmSpeakerB, sampleRate, zeroOutBuffer = true)
        assertNotNull(embB)

        val selfOutcome = SpeakerVerifier.verifyAgainstEnrolled(embA, embA)
        assertEquals(SpeakerVerifier.MatchStatus.MATCHED, selfOutcome.status)

        val diffOutcome = SpeakerVerifier.verifyAgainstEnrolled(embA, embB)
        assertTrue(diffOutcome.status != SpeakerVerifier.MatchStatus.MATCHED)
        assertTrue(
            diffOutcome.warningMessageBn?.contains("আমি তোমার কণ্ঠ নিশ্চিত করতে পারছি না") == true
        )
    }

    @Test
    fun `memory repository extracts explicit Bengali facts, redacts secrets, and supports edit and delete`() = runTest {
        val dao = FakeInMemoryAgentDao()
        val repo = MemoryRepository(dao)

        // Save user message with explicit Bengali memory trigger
        repo.saveChatMessage(
            ChatMessage(
                id = 1L,
                role = ChatMessage.Role.USER,
                text = "মনে রেখো আমার প্রিয় রঙ সবুজ এবং আমি ঢাকায় থাকি।"
            )
        )

        val facts = repo.getAllFacts()
        assertEquals(1, facts.size)
        assertTrue(facts[0].details.contains("সবুজ"))

        // Verify password/OTP redaction before storage
        val sanitized = repo.sanitizeForStorage("আমার পাসওয়ার্ড হলো secret12345 এবং key AIzaSyA123456789012345678901234567890")
        assertFalse(sanitized.contains("secret12345"))
        assertTrue(sanitized.contains("[REDACTED]"))
        assertTrue(sanitized.contains("***REDACTED***"))

        // Edit fact
        val factId = facts[0].id
        repo.addOrUpdateFact(
            id = factId,
            category = "PREFERENCE",
            title = "প্রিয় রঙ",
            details = "আমার প্রিয় রঙ নীল"
        )
        val updated = repo.searchFacts("নীল")
        assertEquals(1, updated.size)

        // Build context for recall
        val contextSnippet = repo.buildRelevantMemoryContext("আমার প্রিয় রঙ কী?")
        assertTrue(contextSnippet.contains("নীল"))

        // Delete fact
        repo.deleteFact(factId)
        assertTrue(repo.getAllFacts().isEmpty())
    }
}
