package com.personal.bengaliagent.service

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import com.personal.bengaliagent.R

/**
 * Shows a compact top floating control bar ONLY if the user granted SYSTEM_ALERT_WINDOW
 * and started an active assistant session.
 */
class FloatingControlBarManager(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null

    fun showIfPermitted(statusTextBn: String = "● এআই সক্রিয়") {
        if (!Settings.canDrawOverlays(context)) return
        if (overlayView != null) {
            updateStatus(statusTextBn)
            return
        }

        val view = LayoutInflater.from(context)
            .inflate(R.layout.view_floating_control_bar, null)

        view.findViewById<TextView>(R.id.tvOverlayStatusDot)?.text = statusTextBn
        view.findViewById<Button>(R.id.btnOverlayMute)?.setOnClickListener {
            AgentSessionBus.dispatch(AgentSessionBus.Command.TOGGLE_MUTE)
        }
        view.findViewById<Button>(R.id.btnOverlayPause)?.setOnClickListener {
            AgentSessionBus.dispatch(AgentSessionBus.Command.TOGGLE_PAUSE)
        }
        view.findViewById<Button>(R.id.btnOverlayStop)?.setOnClickListener {
            AgentSessionBus.dispatch(AgentSessionBus.Command.STOP_CURRENT_TASK)
        }
        view.findViewById<Button>(R.id.btnOverlayDisable)?.setOnClickListener {
            AgentSessionBus.dispatch(AgentSessionBus.Command.DISABLE_SESSION)
        }

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 24
        }

        try {
            windowManager.addView(view, params)
            overlayView = view
        } catch (_: Exception) {
        }
    }

    fun updateStatus(statusTextBn: String) {
        overlayView?.findViewById<TextView>(R.id.tvOverlayStatusDot)?.text = statusTextBn
    }

    fun hide() {
        val v = overlayView ?: return
        try {
            windowManager.removeView(v)
        } catch (_: Exception) {
        }
        overlayView = null
    }
}
