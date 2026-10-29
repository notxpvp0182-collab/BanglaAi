package com.personal.bengaliagent.ui.setup

import android.Manifest
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

    private fun refreshPermissionDashboard() {
        val micGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        binding.tvMicStatus.text = if (micGranted) {
            "1. Microphone Permission: Granted"
        } else {
            "1. Microphone Permission: Not Granted (Optional)"
        }

        val notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        binding.tvNotifStatus.text = if (notifGranted) {
            "2. Notification Permission: Granted"
        } else {
            "2. Notification Permission: Not Granted (Optional)"
        }

        val accEnabled = AgentAccessibilityService.isServiceEnabled()
        binding.tvAccessibilityStatus.text = if (accEnabled) {
            "3. Accessibility Service: Active"
        } else {
            "3. Accessibility Service: Disabled (Optional)"
        }

        val overlayGranted = Settings.canDrawOverlays(this)
        binding.tvOverlayStatus.text = if (overlayGranted) {
            "4. Overlay Permission: Granted"
        } else {
            "4. Overlay Permission: Disabled (Optional)"
        }
    }
}
