package com.personal.bengaliagent

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentCallbacks2
import android.os.Build
import com.personal.bengaliagent.data.db.AgentDatabase
import com.personal.bengaliagent.data.prefs.AppPreferences
import com.personal.bengaliagent.data.repository.MemoryRepository
import com.personal.bengaliagent.data.security.SecureKeyStore
import com.personal.bengaliagent.network.AiClientRouter
import com.personal.bengaliagent.screen.ScreenCaptureManager

class AgentApp : Application() {

    lateinit var secureKeyStore: SecureKeyStore
        private set

    lateinit var appPreferences: AppPreferences
        private set

    lateinit var database: AgentDatabase
        private set

    lateinit var memoryRepository: MemoryRepository
        private set

    lateinit var aiClientRouter: AiClientRouter
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        secureKeyStore = SecureKeyStore(this)
        appPreferences = AppPreferences(this)
        database = AgentDatabase.getInstance(this)
        memoryRepository = MemoryRepository(database.agentDao())
        aiClientRouter = AiClientRouter(secureKeyStore, appPreferences)
        createNotificationChannels()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Release cached frame buffers immediately on 2 GB RAM devices under memory pressure
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        ) {
            if (!ScreenCaptureManager.isSharingActive()) {
                ScreenCaptureManager.stopProjection()
            }
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ACTIVE_SESSION,
                "Active AI Session",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when Bengali AI Agent is actively listening or viewing the screen"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ACTIVE_SESSION = "agent_active_session_channel"
        lateinit var instance: AgentApp
            private set
    }
}
