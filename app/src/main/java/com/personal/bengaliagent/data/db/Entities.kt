package com.personal.bengaliagent.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "transcript_entries",
    indices = [Index(value = ["timestampMs"])]
)
data class TranscriptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String,
    val content: String,
    val providerUsed: String? = null,
    val timestampMs: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "memory_facts",
    indices = [Index(value = ["category"]), Index(value = ["updatedAtMs"])]
)
data class MemoryFactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val category: String, // PREFERENCE, FACT, PROJECT_STATE, DECISION
    val title: String,
    val details: String,
    val isUserConfirmed: Boolean = true,
    val updatedAtMs: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "conversation_summaries",
    indices = [Index(value = ["createdAtMs"])]
)
data class SummaryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val summaryText: String,
    val startTimestampMs: Long,
    val endTimestampMs: Long,
    val createdAtMs: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "task_history",
    indices = [Index(value = ["status"]), Index(value = ["updatedAtMs"])]
)
data class TaskHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userGoal: String,
    val status: String, // COMPLETED, FAILED, CANCELLED, AWAITING_CONFIRMATION
    val outcomeSummary: String,
    val stepsExecuted: Int = 0,
    val updatedAtMs: Long = System.currentTimeMillis()
)
