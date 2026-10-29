package com.personal.bengaliagent.ui.setup

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.personal.bengaliagent.AgentApp
import com.personal.bengaliagent.R
import com.personal.bengaliagent.databinding.ActivitySetupWizardBinding
import com.personal.bengaliagent.service.AgentAccessibilityService
import com.personal.bengaliagent.ui.settings.ProviderSettingsActivity
import kotlinx.coroutines.launch

class SetupWizardActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupWizardBinding

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshPermissionDashboard() }

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshPermissionDashboard() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupWizardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnGrantMic.setOnClickListener {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        binding.btnGrantNotif.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        binding.btnOpenAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.btnOpenOverlay.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        binding.btnOpenAppDetails.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        binding.btnConfigureProviders.setOnClickListener {
            startActivity(Intent(this, ProviderSettingsActivity::class.java))
        }

        binding.btnFinishSetup.setOnClickListener {
            lifecycleScope.launch {
                AgentApp.instance.appPreferences.setSetupCompleted(true)
                finish()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionDashboard()
    }

    /**
     * Checks both the live service instance AND Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
     * so the user immediately sees an accurate ACTIVE / INACTIVE status upon returning from Settings.
     */
    private fun isAccessibilityEnabledInSystem(): Boolean {
        if (AgentAccessibilityService.isServiceEnabled()) return true
        val expectedComponent = ComponentName(this, AgentAccessibilityService::class.java)
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.split(':').any {
            ComponentName.unflattenFromString(it)?.equals(expectedComponent) == true
        }
    }

    private fun refreshPermissionDashboard() {
        val green = ContextCompat.getColor(this, R.color.accent_green)
        val red = ContextCompat.getColor(this, R.color.accent_red_bright)

        val micGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        binding.tvMicStatus.text = if (micGranted) {
            "1. Microphone Permission: ● ACTIVE (Granted)"
        } else {
            "1. Microphone Permission: ○ INACTIVE (Tap below to grant)"
        }
        binding.tvMicStatus.setTextColor(if (micGranted) green else red)

        val notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        binding.tvNotifStatus.text = if (notifGranted) {
            "2. Notification Permission: ● ACTIVE (Granted)"
        } else {
            "2. Notification Permission: ○ INACTIVE (Tap below to grant)"
        }
        binding.tvNotifStatus.setTextColor(if (notifGranted) green else red)

        val accEnabled = isAccessibilityEnabledInSystem()
        binding.tvAccessibilityStatus.text = if (accEnabled) {
            "3. Accessibility Service: ● ACTIVE (Enabled)"
        } else {
            "3. Accessibility Service: ○ INACTIVE (Tap below to enable)"
        }
        binding.tvAccessibilityStatus.setTextColor(if (accEnabled) green else red)

        val overlayGranted = Settings.canDrawOverlays(this)
        binding.tvOverlayStatus.text = if (overlayGranted) {
            "4. Floating Overlay Bar: ● ACTIVE (Granted)"
        } else {
            "4. Floating Overlay Bar: ○ INACTIVE (Optional)"
        }
        binding.tvOverlayStatus.setTextColor(if (overlayGranted) green else red)
    }
}
