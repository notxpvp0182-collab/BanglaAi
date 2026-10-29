package com.personal.bengaliagent.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.personal.bengaliagent.AgentApp
import com.personal.bengaliagent.R
import com.personal.bengaliagent.screen.ScreenCaptureManager
import com.personal.bengaliagent.ui.main.MainActivity

/**
 * Foreground service for user-started sessions with ongoing notification controls:
 * Mute/Unmute, Pause/Resume, Stop Task, and Disable Session.
 * Also starts MediaProjection when the user explicitly grants consent.
 */
class AgentForegroundService : Service() {

    private lateinit var floatingBar: FloatingControlBarManager

    override fun onCreate() {
        super.onCreate()
        floatingBar = FloatingControlBarManager(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_MUTE_TOGGLE -> {
                AgentSessionBus.dispatch(AgentSessionBus.Command.TOGGLE_MUTE)
            }
            ACTION_PAUSE_TOGGLE -> {
                AgentSessionBus.dispatch(AgentSessionBus.Command.TOGGLE_PAUSE)
            }
            ACTION_STOP_TASK -> {
                ScreenCaptureManager.stopProjection()
                AgentSessionBus.dispatch(AgentSessionBus.Command.STOP_CURRENT_TASK)
            }
            ACTION_DISABLE_SESSION -> {
                ScreenCaptureManager.stopProjection()
                floatingBar.hide()
                AgentSessionBus.dispatch(AgentSessionBus.Command.DISABLE_SESSION)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START_PROJECTION -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val projData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_PROJECTION_DATA)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        buildNotification(isScreenSharing = true),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    )
                } else {
                    startForeground(NOTIFICATION_ID, buildNotification(isScreenSharing = true))
                }
                if (projData != null) {
                    ScreenCaptureManager.startProjection(this, resultCode, projData)
                }
                floatingBar.showIfPermitted("● স্ক্রিন শেয়ার চালু")
            }
            else -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        buildNotification(isScreenSharing = ScreenCaptureManager.isSharingActive()),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    )
                } else {
                    startForeground(
                        NOTIFICATION_ID,
                        buildNotification(isScreenSharing = ScreenCaptureManager.isSharingActive())
                    )
                }
                floatingBar.showIfPermitted("● এআই সক্রিয়")
            }
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(isScreenSharing: Boolean): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingOpen = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val statusText = if (isScreenSharing) {
            "সক্রিয়: স্ক্রিন শেয়ারিং চালু আছে"
        } else {
            "সক্রিয় সেশন: কথা বলুন বা নির্দেশ দিন"
        }

        return NotificationCompat.Builder(this, AgentApp.CHANNEL_ACTIVE_SESSION)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(statusText)
            .setOngoing(true)
            .setContentIntent(pendingOpen)
            .addAction(0, "মাইক (Mute)", actionPendingIntent(ACTION_MUTE_TOGGLE, 10))
            .addAction(0, "বিরতি (Pause)", actionPendingIntent(ACTION_PAUSE_TOGGLE, 11))
            .addAction(0, "থামুন (Stop)", actionPendingIntent(ACTION_STOP_TASK, 12))
            .addAction(0, "বন্ধ (Disable)", actionPendingIntent(ACTION_DISABLE_SESSION, 13))
            .build()
    }

    private fun actionPendingIntent(action: String, reqCode: Int): PendingIntent {
        val intent = Intent(this, AgentForegroundService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            this,
            reqCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    override fun onDestroy() {
        floatingBar.hide()
        ScreenCaptureManager.stopProjection()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val NOTIFICATION_ID = 1001
        const val ACTION_MUTE_TOGGLE = "com.personal.bengaliagent.ACTION_MUTE_TOGGLE"
        const val ACTION_PAUSE_TOGGLE = "com.personal.bengaliagent.ACTION_PAUSE_TOGGLE"
        const val ACTION_STOP_TASK = "com.personal.bengaliagent.ACTION_STOP_TASK"
        const val ACTION_DISABLE_SESSION = "com.personal.bengaliagent.ACTION_DISABLE_SESSION"
        const val ACTION_START_PROJECTION = "com.personal.bengaliagent.ACTION_START_PROJECTION"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_PROJECTION_DATA = "extra_projection_data"
    }
}
