package com.personal.bengaliagent.service

import android.content.Context

/**
 * Disabled on-screen overlay bar per user preference.
 * Session controls are kept strictly inside the notification shade and main app UI.
 */
class FloatingControlBarManager(@Suppress("UNUSED_PARAMETER") private val context: Context) {
    fun showIfPermitted(@Suppress("UNUSED_PARAMETER") statusText: String = "") {
        // Intentionally no-op: user requested controls only in notification shade, not floating on screen.
    }

    fun updateStatus(@Suppress("UNUSED_PARAMETER") statusText: String) {}

    fun hide() {}
}
