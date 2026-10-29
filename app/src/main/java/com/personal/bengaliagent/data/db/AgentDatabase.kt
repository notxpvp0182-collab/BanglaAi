package com.personal.bengaliagent.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        TranscriptEntity::class,
        MemoryFactEntity::class,
        SummaryEntity::class,
        TaskHistoryEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AgentDatabase : RoomDatabase() {

    abstract fun agentDao(): AgentDao

    companion object {
        @Volatile
        private var INSTANCE: AgentDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Safe no-op/forward-compatible index creation for future schema upgrades
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_memory_facts_updatedAtMs ON memory_facts(updatedAtMs)"
                )
            }
        }

        fun getInstance(context: Context): AgentDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AgentDatabase::class.java,
                    "bengali_agent_brain.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
