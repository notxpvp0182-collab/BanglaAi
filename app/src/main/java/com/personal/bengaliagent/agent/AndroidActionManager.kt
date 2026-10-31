package com.personal.bengaliagent.agent

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings

/**
 * Modular, deterministic Android Action Execution Manager.
 * Understands natural-language commands in Bengali and English (e.g., "WhatsApp ওপেন করো",
 * "Open YouTube", "Settings ওপেন করো", "Open Chrome") and executes them directly via Android APIs.
 */
object AndroidActionManager {

    data class LocalActionOutcome(
        val matchedIntentCommand: Boolean,
        val executedSuccess: Boolean,
        val responseMessage: String
    )

    private val openVerbs = listOf(
        "open", "launch", "start", "go to",
        "ওপেন", "খোলো", "খুলো", "চালু করো", "বের করো", "যান", "যাও"
    )

    /**
     * Checks if the user prompt is a direct Android app/system launch command.
     * If so, executes it immediately and returns the verified result.
     */
    fun tryExecuteDirectCommand(context: Context, rawPrompt: String): LocalActionOutcome {
        val trimmed = rawPrompt.trim()
        val lower = trimmed.lowercase()

        // Ensure it's a short direct command (<= 60 chars) containing an open/launch verb
        if (trimmed.length > 65 || openVerbs.none { lower.contains(it) }) {
            return LocalActionOutcome(false, false, "")
        }

        // Extract target name by stripping open verbs and polite fillers
        var target = lower
        val stripWords = listOf(
            "please", "can you", "now", "app", "application",
            "ওপেন করো", "ওপেন কর", "ওপেন", "খোলো", "খুলো", "চালু করো", "করো", "দাও", "অ্যাপ", "একটু", "প্লিজ",
            "open", "launch", "start", "go to"
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

    fun executeTargetLaunch(context: Context, rawTarget: String): LocalActionOutcome {
        val target = rawTarget.trim().lowercase()

        // 1. Android System Settings
        if (target.contains("setting") || target.contains("সেটিংস") || target.contains("সেটিং")) {
            return try {
                val intent = Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                LocalActionOutcome(
                    matchedIntentCommand = true,
                    executedSuccess = true,
                    responseMessage = "Opened Android Settings."
                )
            } catch (e: Exception) {
                LocalActionOutcome(
                    matchedIntentCommand = true,
                    executedSuccess = false,
                    responseMessage = "Failed to open Android Settings: ${e.message}"
                )
            }
        }

        // 2. Wi-Fi Settings
        if (target.contains("wifi") || target.contains("ওয়াইফাই")) {
            return try {
                val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                LocalActionOutcome(true, true, "Opened Wi-Fi Settings.")
            } catch (e: Exception) {
                LocalActionOutcome(true, false, "Could not open Wi-Fi Settings.")
            }
        }

        // 3. WhatsApp
        if (target.contains("whatsapp") || target.contains("হোয়াটসঅ্যাপ") || target.contains("হোয়াটসঅ্যাপ")) {
            val launched = launchByPackageList(
                context,
                listOf("com.whatsapp", "com.whatsapp.w4b")
            )
            return if (launched) {
                LocalActionOutcome(true, true, "Opened WhatsApp.")
            } else {
                LocalActionOutcome(
                    true,
                    false,
                    "WhatsApp is not installed on this device."
                )
            }
        }

        // 4. YouTube (with browser fallback if app not installed)
        if (target.contains("youtube") || target.contains("ইউটিউব")) {
            val launchedApp = launchByPackageList(context, listOf("com.google.android.youtube"))
            if (launchedApp) {
                return LocalActionOutcome(true, true, "Opened YouTube.")
            }
            return try {
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://m.youtube.com")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(webIntent)
                LocalActionOutcome(
                    true,
                    true,
                    "YouTube app was not found, so I opened YouTube in your browser."
                )
            } catch (e: Exception) {
                LocalActionOutcome(true, false, "Could not open YouTube.")
            }
        }

        // 5. Chrome / Browser
        if (target.contains("chrome") || target.contains("ক্রোম") || target.contains("browser") || target.contains("ব্রাউজার")) {
            val launchedChrome = launchByPackageList(
                context,
                listOf("com.android.chrome", "com.brave.browser", "org.mozilla.firefox")
            )
            if (launchedChrome) {
                return LocalActionOutcome(true, true, "Opened browser.")
            }
            return try {
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(webIntent)
                LocalActionOutcome(true, true, "Opened default web browser.")
            } catch (e: Exception) {
                LocalActionOutcome(true, false, "No web browser could be launched.")
            }
        }

        // 6. Camera
        if (target.contains("camera") || target.contains("ক্যামেরা")) {
            return try {
                val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                LocalActionOutcome(true, true, "Opened Camera.")
            } catch (e: Exception) {
                LocalActionOutcome(true, false, "Could not open Camera.")
            }
        }

        // 7. Alarm / Clock
        if (target.contains("alarm") || target.contains("clock") || target.contains("অ্যালার্ম") || target.contains("ঘড়ি")) {
            return try {
                val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                LocalActionOutcome(true, true, "Opened Clock / Alarms.")
            } catch (e: Exception) {
                LocalActionOutcome(true, false, "Could not open Clock app.")
            }
        }

        // 8. Generic Installed App Search by Label or Package Name
        val genericLaunched = launchInstalledAppByName(context, target)
        return if (genericLaunched != null) {
            LocalActionOutcome(true, true, "Opened $genericLaunched.")
        } else {
            LocalActionOutcome(
                matchedIntentCommand = true,
                executedSuccess = false,
                responseMessage = "Could not find an installed application matching '$rawTarget' on your phone."
            )
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
