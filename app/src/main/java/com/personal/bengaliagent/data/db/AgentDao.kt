package com.personal.bengaliagent.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface AgentDao {

    // --- Transcript ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTranscript(entry: TranscriptEntity): Long

    @Query("SELECT * FROM transcript_entries ORDER BY timestampMs DESC LIMIT :limit")
    suspend fun getRecentTranscripts(limit: Int): List<TranscriptEntity>

    @Query("SELECT * FROM transcript_entries WHERE content LIKE '%' || :keyword || '%' ORDER BY timestampMs DESC LIMIT :limit")
    suspend fun searchTranscripts(keyword: String, limit: Int = 20): List<TranscriptEntity>

    @Query("DELETE FROM transcript_entries WHERE timestampMs < :cutoffMs")
    suspend fun deleteTranscriptsOlderThan(cutoffMs: Long): Int

    @Query("DELETE FROM transcript_entries")
    suspend fun clearAllTranscripts()

    // --- Durable Facts / Preferences / Project State ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemoryFact(fact: MemoryFactEntity): Long

    @Update
    suspend fun updateMemoryFact(fact: MemoryFactEntity)

    @Query("SELECT * FROM memory_facts ORDER BY updatedAtMs DESC")
    suspend fun getAllMemoryFacts(): List<MemoryFactEntity>

    @Query(
        """
        SELECT * FROM memory_facts 
        WHERE title LIKE '%' || :query || '%' 
           OR details LIKE '%' || :query || '%' 
           OR category LIKE '%' || :query || '%'
        ORDER BY updatedAtMs DESC
        LIMIT :limit
        """
    )
    suspend fun searchMemoryFacts(query: String, limit: Int = 15): List<MemoryFactEntity>

    @Query("DELETE FROM memory_facts WHERE id = :id")
    suspend fun deleteMemoryFactById(id: Long)

    @Query("DELETE FROM memory_facts")
    suspend fun clearAllMemoryFacts()

    // --- Rolling Summaries ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSummary(summary: SummaryEntity): Long

    @Query("SELECT * FROM conversation_summaries ORDER BY createdAtMs DESC LIMIT :limit")
    suspend fun getRecentSummaries(limit: Int = 5): List<SummaryEntity>

    @Query("DELETE FROM conversation_summaries")
    suspend fun clearAllSummaries()

    // --- Task History ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTaskHistory(task: TaskHistoryEntity): Long

    @Query("SELECT * FROM task_history ORDER BY updatedAtMs DESC LIMIT :limit")
    suspend fun getRecentTasks(limit: Int = 30): List<TaskHistoryEntity>

    @Query("DELETE FROM task_history")
    suspend fun clearAllTaskHistory()
}
