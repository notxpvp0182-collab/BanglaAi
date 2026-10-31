package com.personal.bengaliagent.agent

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import com.personal.bengaliagent.service.AgentAccessibilityService

/**
 * Executes real Android phone actions with short, direct voice confirmations:
 * - "Opening WhatsApp."
 * - "Opening YouTube."
 * - "Turning on flashlight."
 * - "Opening Wi-Fi settings."
 * - "Going back." / "Scrolling down."
 */
object AndroidActionManager {

    data class LocalActionOutcome(
        val matchedIntentCommand: Boolean,
        val executedSuccess: Boolean,
        val responseMessage: String
    )

    fun executeStructuredToolCall(
        context: Context,
        actionType: String,
        target: String
    ): LocalActionOutcome {
        val normAction = actionType.trim().lowercase()
        val normTarget = target.trim()
        return when (normAction) {
            "open_app", "launch_app" -> executeTargetLaunch(context, normTarget)
            "open_settings" -> executeTargetLaunch(context, normTarget.ifBlank { "settings" })
            "turn_on_flashlight", "flashlight_on" -> setFlashlight(context, true)
            "turn_off_flashlight", "flashlight_off" -> setFlashlight(context, false)
            "go_back", "press_back" -> {
                val ok = AgentAccessibilityService.instance?.pressBack() == true
                LocalActionOutcome(
                    true,
                    ok,
                    if (ok) "Going back." else "Enable Accessibility Service to use Back navigation."
                )
            }
            "go_home", "press_home" -> {
                val ok = AgentAccessibilityService.instance?.pressHome() == true
                LocalActionOutcome(
                    true,
                    ok,
                    if (ok) "Going to Home screen." else "Enable Accessibility Service to go Home."
                )
            }
            "scroll_down" -> {
                val ok = AgentAccessibilityService.instance?.scrollScreen(forward = true) == true
                LocalActionOutcome(
                    true,
                    ok,
                    if (ok) "Scrolling down." else "Could not scroll down on this screen."
                )
            }
            "scroll_up" -> {
                val ok = AgentAccessibilityService.instance?.scrollScreen(forward = false) == true
                LocalActionOutcome(
                    true,
                    ok,
                    if (ok) "Scrolling up." else "Could not scroll up on this screen."
                )
            }
            "tap_element" -> {
                val ok = AgentAccessibilityService.instance?.tapElementByLabel(normTarget) == true
                LocalActionOutcome(
                    true,
                    ok,
                    if (ok) "Tapped $normTarget." else "Could not find '$normTarget' on screen."
                )
            }
            else -> tryExecuteDirectCommand(context, "$actionType $target")
        }
    }

    fun tryExecuteDirectCommand(context: Context, rawPrompt: String): LocalActionOutcome {
        val trimmed = rawPrompt.trim()
        val lower = trimmed.lowercase()

        // Flashlight commands
        if (lower.contains("flashlight") || lower.contains("torch") || lower.contains("ফ্ল্যাশলাইট") || lower.contains("টর্চ")) {
            val turnOff = lower.contains("off") || lower.contains("বন্ধ")
            return setFlashlight(context, !turnOff)
        }

        // Back / Scroll commands
        if (lower == "go back" || lower == "ব্যাকে যাও" || lower == "পিছনে যাও") {
            return executeStructuredToolCall(context, "go_back", "")
        }
        if (lower == "scroll down" || lower == "নিচে স্ক্রল করো") {
            return executeStructuredToolCall(context, "scroll_down", "")
        }
        if (lower == "scroll up" || lower == "উপরে স্ক্রল করো") {
            return executeStructuredToolCall(context, "scroll_up", "")
        }

        val openVerbs = listOf(
            "open", "launch", "start", "turn on", "go to",
            "ওপেন", "খোলো", "খুলো", "চালু করো", "বের করো", "যান", "যাও"
        )
        if (trimmed.length > 70 || openVerbs.none { lower.contains(it) }) {
            return LocalActionOutcome(false, false, "")
        }

        var target = lower
        val stripWords = listOf(
            "please", "can you", "now", "hey", "app", "application", "the",
            "ওপেন করো", "ওপেন কর", "ওপেন", "খোলো", "খুলো", "চালু করো", "করো", "দাও", "অ্যাপ", "একটু", "প্লিজ",
            "open", "launch", "start", "turn on", "go to"
        )
        for (w in stripWords) {
            target = target.replace(w, " ")
        }
        target = target.replace(Regex("[^a-z0-9\\u0980-\\u09FF ]"), " ").trim()
        if (target.isEmpty()) {
            return LocalActionOutcome(false, false, "")
        }

        return executeTargetLaunch(context, target)
    }

    private fun setFlashlight(context: Context, enable: Boolean): LocalActionOutcome {
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cm.cameraIdList.firstOrNull()
                ?: return LocalActionOutcome(true, false, "No camera flash found on this device.")
            cm.setTorchMode(cameraId, enable)
            LocalActionOutcome(
                true,
                true,
                if (enable) "Turning on flashlight." else "Turning off flashlight."
            )
        } catch (e: Exception) {
            LocalActionOutcome(true, false, "Could not control flashlight.")
        }
    }

    fun executeTargetLaunch(context: Context, rawTarget: String): LocalActionOutcome {
        val target = rawTarget.trim().lowercase()

        if (target.contains("wifi") || target.contains("wi-fi") || target.contains("ওয়াইফাই")) {
            return try {
                val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                LocalActionOutcome(true, true, "Opening Wi-Fi settings.")
            } catch (e: Exception) {
                LocalActionOutcome(true, false, "Could not open Wi-Fi settings.")
            }
        }

        if (target.contains("setting") || target.contains("সেটিংস") || target.contains("সেটিং")) {
            return try {
                val intent = Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                LocalActionOutcome(true, true, "Opening Settings.")
            } catch (e: Exception) {
                LocalActionOutcome(true, false, "Could not open Settings.")
            }
        }

        if (target.contains("whatsapp") || target.contains("হোয়াটসঅ্যাপ")) {
            val launched = launchByPackageList(context, listOf("com.whatsapp", "com.whatsapp.w4b"))
            return if (launched) {
                LocalActionOutcome(true, true, "Opening WhatsApp.")
            } else {
                LocalActionOutcome(true, false, "WhatsApp is not installed.")
            }
        }

        if (target.contains("youtube") || target.contains("ইউটিউব")) {
            val launchedApp = launchByPackageList(context, listOf("com.google.android.youtube"))
            if (launchedApp) {
                return LocalActionOutcome(true, true, "Opening YouTube.")
            }
            return try {
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://m.youtube.com")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(webIntent)
                LocalActionOutcome(true, true, "Opening YouTube.")
            } catch (e: Exception) {
                LocalActionOutcome(true, false, "Could not open YouTube.")
            }
        }

        if (target.contains("chrome") || target.contains("ক্রোম") || target.contains("browser") || target.contains("ব্রাউজার")) {
            val launchedChrome = launchByPackageList(
                context,
                listOf("com.android.chrome", "com.brave.browser", "org.mozilla.firefox")
            )
            if (launchedChrome) {
                return LocalActionOutcome(true, true, "Opening Chrome.")
            }
            return try {
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(webIntent)
                LocalActionOutcome(true, true, "Opening browser.")
            } catch (e: Exception) {
                LocalActionOutcome(true, false, "Could not open browser.")
            }
        }

        if (target.contains("camera") || target.contains("ক্যামেরা")) {
            return try {
                val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                LocalActionOutcome(true, true, "Opening Camera.")
            } catch (e: Exception) {
                LocalActionOutcome(true, false, "Could not open Camera.")
            }
        }

        if (target.contains("alarm") || target.contains("clock") || target.contains("অ্যালার্ম") || target.contains("ঘড়ি")) {
            return try {
                val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                LocalActionOutcome(true, true, "Opening Clock.")
            } catch (e: Exception) {
                LocalActionOutcome(true, false, "Could not open Clock.")
            }
        }

        val genericLaunched = launchInstalledAppByName(context, target)
        return if (genericLaunched != null) {
            LocalActionOutcome(true, true, "Opening $genericLaunched.")
        } else {
            LocalActionOutcome(true, false, "Could not find $rawTarget on your phone.")
        }
    }

    private fun launchByPackageList(context: Context, packages: List<String>): Boolean {
        val pm = context.packageManager
        for (pkg in packages) {
            val intent = pm.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                return try {
                    context.startActivity(intent)
                    true
                } catch (_: Exception) {
                    false
                }
            }
        }
        return false
    }

    fun launchInstalledAppByName(context: Context, query: String): String? {
        val clean = query.trim()
        if (clean.isEmpty()) return null
        val pm = context.packageManager

        val directIntent = pm.getLaunchIntentForPackage(clean)
        if (directIntent != null) {
            directIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(directIntent)
            return clean
        }

        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = pm.queryIntentActivities(mainIntent, PackageManager.MATCH_ALL)
        val matched = resolveInfos.firstOrNull { info ->
            val label = info.loadLabel(pm).toString()
            val pkg = info.activityInfo.packageName
            label.contains(clean, ignoreCase = true) || pkg.contains(clean, ignoreCase = true)
        } ?: return null

        val labelName = matched.loadLabel(pm).toString()
        val launchIntent = pm.getLaunchIntentForPackage(matched.activityInfo.packageName)
            ?: return null
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
        return labelName
    }
}
