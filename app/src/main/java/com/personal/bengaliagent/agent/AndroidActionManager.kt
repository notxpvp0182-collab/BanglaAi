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
 * Full Phone & Screen Action Execution Engine with complete Bengali Unicode normalization.
 * Executes app launches, taps, long-presses, swipes, scrolling, typing, flashlight, and system actions.
 */
object AndroidActionManager {

    data class LocalActionOutcome(
        val matchedIntentCommand: Boolean,
        val executedSuccess: Boolean,
        val responseMessage: String
    )

    private fun normalizeBn(input: String): String {
        return input
            .replace("\u09AF\u09BC", "\u09DF") // Normalize য় to য়
            .replace("\u09A1\u09BC", "\u09DC") // Normalize ড় to ড়
            .replace("\u09A2\u09BC", "\u09DD") // Normalize ঢ় to ঢ়
            .lowercase()
            .trim()
    }

    fun executeStructuredToolCall(
        context: Context,
        actionType: String,
        target: String,
        x: Int = -1,
        y: Int = -1
    ): LocalActionOutcome {
        val normAction = actionType.trim().lowercase()
        val normTarget = target.trim()
        val acc = AgentAccessibilityService.instance

        return when (normAction) {
            "open_app", "launch_app" -> executeTargetLaunch(context, normTarget)
            "open_settings" -> executeTargetLaunch(context, normTarget.ifBlank { "settings" })
            "turn_on_flashlight", "flashlight_on" -> setFlashlight(context, true)
            "turn_off_flashlight", "flashlight_off" -> setFlashlight(context, false)
            "go_back", "press_back" -> {
                val ok = acc?.pressBack() == true
                LocalActionOutcome(
                    true,
                    ok,
                    if (ok) "ঠিক আছে জান, ব্যাকে যাচ্ছি।" else "অ্যাক্সেসিবিলিটি সার্ভিসটি সেটিংস থেকে অন করে দাও।"
                )
            }
            "go_home", "press_home" -> {
                val ok = acc?.pressHome() == true
                LocalActionOutcome(
                    true,
                    ok,
                    if (ok) "হোম স্ক্রিনে যাচ্ছি।" else "অ্যাক্সেসিবিলিটি সার্ভিসটি অন নেই।"
                )
            }
            "press_recents", "recent_apps" -> {
                val ok = acc?.pressRecents() == true
                LocalActionOutcome(
                    true,
                    ok,
                    if (ok) "রিসেন্ট অ্যাপস দেখাচ্ছি।" else "অ্যাক্সেসিবিলিটি সার্ভিসটি অন নেই।"
                )
            }
            "open_notifications" -> {
                val ok = acc?.openNotifications() == true
                LocalActionOutcome(
                    true,
                    ok,
                    if (ok) "নোটিফিকেশন প্যানেল খুলেছি।" else "অ্যাক্সেসিবিলিটি সার্ভিসটি অন নেই।"
                )
            }
            "scroll_down", "swipe_up" -> {
                val ok = acc?.scrollScreen(forward = true) == true
                LocalActionOutcome(
                    true,
                    ok,
                    if (ok) "নিচে স্ক্রল করে দিয়েছি।" else "স্ক্রল করতে গেলে অ্যাক্সেসিবিলিটি পারমিশন অন থাকতে হবে।"
                )
            }
            "scroll_up", "swipe_down" -> {
                val ok = acc?.scrollScreen(forward = false) == true
                LocalActionOutcome(
                    true,
                    ok,
                    if (ok) "উপরে স্ক্রল করে দিয়েছি।" else "অ্যাক্সেসিবিলিটি সার্ভিসটি অন নেই।"
                )
            }
            "swipe_left" -> {
                val ok = acc?.swipeHorizontal(left = true) == true
                LocalActionOutcome(true, ok, if (ok) "বামে সোয়াইপ করেছি।" else "সোয়াইপ করা যায়নি।")
            }
            "swipe_right" -> {
                val ok = acc?.swipeHorizontal(left = false) == true
                LocalActionOutcome(true, ok, if (ok) "ডানে সোয়াইপ করেছি।" else "সোয়াইপ করা যায়নি।")
            }
            "tap_element", "tap", "click" -> {
                if (acc == null) {
                    return LocalActionOutcome(true, false, "স্ক্রিনে টাচ করার জন্য সেটিংস থেকে অ্যাক্সেসিবিলিটি সার্ভিসটি অন করে দাও।")
                }
                val ok = when {
                    x >= 0 && y >= 0 -> acc.dispatchTouchFireAndForget(x, y, 90L)
                    normTarget.isNotBlank() -> acc.tapElementByLabel(normTarget) || tapPositionKeyword(acc, normTarget)
                    else -> {
                        val (w, h) = acc.getScreenDimensions()
                        acc.dispatchTouchFireAndForget(w / 2, h / 2, 90L)
                    }
                }
                LocalActionOutcome(
                    true,
                    ok,
                    if (ok) "টাচ করে দিয়েছি জান।" else "'$normTarget' খুঁজে পাইনি।"
                )
            }
            "long_press" -> {
                if (acc == null) {
                    return LocalActionOutcome(true, false, "অ্যাক্সেসিবিলিটি সার্ভিসটি অন নেই।")
                }
                val ok = if (x >= 0 && y >= 0) {
                    acc.dispatchTouchFireAndForget(x, y, 850L)
                } else {
                    acc.longPressElementByLabel(normTarget)
                }
                LocalActionOutcome(
                    true,
                    ok,
                    if (ok) "লং প্রেস করে দিয়েছি।" else "লং প্রেস করা যায়নি।"
                )
            }
            "type_text", "type" -> {
                if (acc == null) {
                    return LocalActionOutcome(true, false, "অ্যাক্সেসিবিলিটি সার্ভিসটি অন নেই।")
                }
                val res = acc.typeTextIntoFocusedOrEditableField(normTarget)
                LocalActionOutcome(true, res.success, if (res.success) "লিখে দিয়েছি জান।" else res.messageBn)
            }
            "take_screenshot" -> {
                val ok = acc?.takeScreenshot() == true
                LocalActionOutcome(true, ok, if (ok) "স্ক্রিনশট নিয়েছি।" else "স্ক্রিনশট নেওয়া যায়নি।")
            }
            else -> tryExecuteDirectCommand(context, "$actionType $target")
        }
    }

    private fun tapPositionKeyword(acc: AgentAccessibilityService, target: String): Boolean {
        val norm = normalizeBn(target)
        val (w, h) = acc.getScreenDimensions()
        return when {
            norm.contains("মাঝখানে") || norm.contains("center") || norm.contains("middle") ->
                acc.dispatchTouchFireAndForget(w / 2, h / 2, 90L)
            norm.contains("উপরে") || norm.contains("top") ->
                acc.dispatchTouchFireAndForget(w / 2, (h * 0.2).toInt(), 90L)
            norm.contains("নিচে") || norm.contains("bottom") ->
                acc.dispatchTouchFireAndForget(w / 2, (h * 0.82).toInt(), 90L)
            norm.contains("ডানে") || norm.contains("right") ->
                acc.dispatchTouchFireAndForget((w * 0.8).toInt(), h / 2, 90L)
            norm.contains("বামে") || norm.contains("left") ->
                acc.dispatchTouchFireAndForget((w * 0.2).toInt(), h / 2, 90L)
            else -> false
        }
    }

    fun tryExecuteDirectCommand(context: Context, rawPrompt: String): LocalActionOutcome {
        val norm = normalizeBn(rawPrompt)

        // 1. Flashlight
        if (norm.contains("flashlight") || norm.contains("torch") || norm.contains("ফ্ল্যাশলাইট") || norm.contains("টর্চ") || norm.contains("লাইট জ্বালাও")) {
            val turnOff = norm.contains("off") || norm.contains("বন্ধ")
            return setFlashlight(context, !turnOff)
        }

        // 2. Direct Screen Navigation & Gestures in Bengali & English
        if (norm == "go back" || norm.contains("ব্যাকে যাও") || norm.contains("পিছনে যাও") || norm.contains("ব্যাক করো")) {
            return executeStructuredToolCall(context, "go_back", "")
        }
        if (norm == "go home" || norm.contains("হোমে যাও") || norm.contains("হোম স্ক্রিনে যাও")) {
            return executeStructuredToolCall(context, "go_home", "")
        }
        if (norm.contains("scroll down") || norm.contains("নিচে স্ক্রল") || norm.contains("নিচে নামো") || norm.contains("নিচে যাও")) {
            return executeStructuredToolCall(context, "scroll_down", "")
        }
        if (norm.contains("scroll up") || norm.contains("উপরে স্ক্রল") || norm.contains("উপরে উঠো") || norm.contains("উপরে যাও")) {
            return executeStructuredToolCall(context, "scroll_up", "")
        }
        if (norm.contains("swipe left") || norm.contains("বামে সরাও") || norm.contains("বামে ঘুরাও") || norm.contains("বামে সোয়াইপ")) {
            return executeStructuredToolCall(context, "swipe_left", "")
        }
        if (norm.contains("swipe right") || norm.contains("ডানে সরাও") || norm.contains("ডানে ঘুরাও") || norm.contains("ডানে সোয়াইপ")) {
            return executeStructuredToolCall(context, "swipe_right", "")
        }
        if (norm.contains("লং প্রেস") || norm.contains("long press") || norm.contains("চেপে ধরো")) {
            val target = norm
                .replace("লং প্রেস করো", "")
                .replace("লং প্রেস", "")
                .replace("long press", "")
                .replace("চেপে ধরো", "")
                .trim()
            return executeStructuredToolCall(context, "long_press", target)
        }
        if (norm.contains("স্ক্রিনশট নাও") || norm.contains("take screenshot")) {
            return executeStructuredToolCall(context, "take_screenshot", "")
        }

        // 3. Direct Tap Command ("... এ ট্যাপ করো" / "... এ ক্লিক করো" / "tap on ...")
        val tapVerbs = listOf("ট্যাপ করো", "ট্যাপ কর", "ক্লিক করো", "ক্লিক কর", "টাচ করো", "চাপ দাও", "tap on", "click on", "tap ")
        if (tapVerbs.any { norm.contains(it) }) {
            var tapTarget = norm
            for (v in tapVerbs + listOf("বাটনে", "উপর", "এখানে", "একটু", "প্লিজ")) {
                tapTarget = tapTarget.replace(v, " ")
            }
            tapTarget = tapTarget.trim()
            return executeStructuredToolCall(context, "tap_element", tapTarget)
        }

        // 4. App Launch Commands in Bengali & English
        val openVerbs = listOf(
            "open", "launch", "start", "go to",
            "ওপেন", "খোলো", "খুলো", "চালু", "বের করো", "ঢুকো", "ঢোকো"
        )
        if (norm.length <= 80 && openVerbs.any { norm.contains(it) }) {
            var target = norm
            val stripWords = listOf(
                "please", "can you", "now", "hey", "my", "the", "app", "application",
                "আমার", "তোমার", "ফোনের", "একটু", "এখন", "তাড়াতাড়ি", "প্লিজ", "জান", "বেবি",
                "ওপেন করো", "ওপেন কর", "ওপেন দাও", "ওপেন", "খোলো", "খুলো", "চালু করো", "চালু কর",
                "চালু", "বের করো", "ঢুকো", "ঢোকো", "করো", "দাও", "অ্যাপস", "অ্যাপ", "এ",
                "open", "launch", "start", "go to"
            )
            for (w in stripWords) {
                target = target.replace(w, " ")
            }
            target = target.replace(Regex("[^a-z0-9\\u0980-\\u09FF ]"), " ").trim()
            if (target.isNotEmpty()) {
                return executeTargetLaunch(context, target)
            }
        }

        return LocalActionOutcome(false, false, "")
    }

    private fun setFlashlight(context: Context, enable: Boolean): LocalActionOutcome {
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cm.cameraIdList.firstOrNull()
                ?: return LocalActionOutcome(true, false, "ফোনে ফ্ল্যাশলাইট খুঁজে পাইনি।")
            cm.setTorchMode(cameraId, enable)
            LocalActionOutcome(
                true,
                true,
                if (enable) "এই তো জান, ফ্ল্যাশলাইট জ্বালিয়ে দিয়েছি।" else "ফ্ল্যাশলাইট বন্ধ করে দিয়েছি।"
            )
        } catch (e: Exception) {
            LocalActionOutcome(true, false, "ফ্ল্যাশলাইট চালু করা গেল না।")
        }
    }

    fun executeTargetLaunch(context: Context, rawTarget: String): LocalActionOutcome {
        val target = normalizeBn(rawTarget)

        // WhatsApp (Covers all Bengali & English spellings)
        if (target.contains("whatsapp") || target.contains("whats app") ||
            target.contains("হোয়াটসঅ্যাপ") || target.contains("হোয়াটসঅ্যাপ") ||
            target.contains("ওয়াটসঅ্যাপ") || target.contains("ওয়াটসঅ্যাপ") ||
            target.contains("হোয়াটস") || target.contains("ওয়াটস")
        ) {
            val launched = launchByPackageList(context, listOf("com.whatsapp", "com.whatsapp.w4b"))
            return if (launched) {
                LocalActionOutcome(true, true, "এই তো জান, হোয়াটসঅ্যাপ ওপেন করে দিচ্ছি।")
            } else {
                LocalActionOutcome(true, false, "তোমার ফোনে হোয়াটসঅ্যাপ ইনস্টল করা নেই।")
            }
        }

        // YouTube
        if (target.contains("youtube") || target.contains("you tube") || target.contains("ইউটিউব")) {
            val launchedApp = launchByPackageList(context, listOf("com.google.android.youtube"))
            if (launchedApp) {
                return LocalActionOutcome(true, true, "ইউটিউব ওপেন করে দিচ্ছি জান।")
            }
            return try {
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://m.youtube.com")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(webIntent)
                LocalActionOutcome(true, true, "ইউটিউব ওপেন করে দিয়েছি।")
            } catch (e: Exception) {
                LocalActionOutcome(true, false, "ইউটিউব ওপেন করা গেল না।")
            }
        }

        // Facebook
        if (target.contains("facebook") || target.contains("ফেসবুক") || target.contains("ফেইসবুক")) {
            val launched = launchByPackageList(context, listOf("com.facebook.katana", "com.facebook.lite"))
            if (launched) return LocalActionOutcome(true, true, "ফেসবুক ওপেন করে দিচ্ছি।")
        }

        // Messenger
        if (target.contains("messenger") || target.contains("মেসেঞ্জার")) {
            val launched = launchByPackageList(context, listOf("com.facebook.orca", "com.facebook.mlite"))
            if (launched) return LocalActionOutcome(true, true, "মেসেঞ্জার ওপেন করে দিচ্ছি।")
        }

        // TikTok
        if (target.contains("tiktok") || target.contains("টিকটক")) {
            val launched = launchByPackageList(
                context,
                listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.zhiliaoapp.musically.go")
            )
            if (launched) return LocalActionOutcome(true, true, "টিকটক ওপেন করে দিচ্ছি।")
        }

        // Chrome / Browser
        if (target.contains("chrome") || target.contains("ক্রোম") || target.contains("browser") || target.contains("ব্রাউজার")) {
            val launchedChrome = launchByPackageList(
                context,
                listOf("com.android.chrome", "com.brave.browser", "org.mozilla.firefox")
            )
            if (launchedChrome) {
                return LocalActionOutcome(true, true, "ব্রাউজার ওপেন করে দিচ্ছি।")
            }
            return try {
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(webIntent)
                LocalActionOutcome(true, true, "ব্রাউজার ওপেন করে দিয়েছি।")
            } catch (e: Exception) {
                LocalActionOutcome(true, false, "ব্রাউজার ওপেন করা গেল না।")
            }
        }

        // InShot / CapCut / Video Editors
        if (target.contains("inshot") || target.contains("ইনশট")) {
            if (launchByPackageList(context, listOf("com.camerasideas.instashot"))) {
                return LocalActionOutcome(true, true, "ইনশট ওপেন করে দিচ্ছি।")
            }
        }
        if (target.contains("capcut") || target.contains("ক্যাপকাট")) {
            if (launchByPackageList(context, listOf("com.lemon.lvoverseas"))) {
                return LocalActionOutcome(true, true, "ক্যাপকাট ওপেন করে দিচ্ছি।")
            }
        }

        // Termux
        if (target.contains("termux") || target.contains("টারমাক্স")) {
            if (launchByPackageList(context, listOf("com.termux"))) {
                return LocalActionOutcome(true, true, "টারমাক্স ওপেন করে দিচ্ছি।")
            }
        }

        // Wi-Fi Settings
        if (target.contains("wifi") || target.contains("wi-fi") || target.contains("ওয়াইফাই") || target.contains("ওয়াইফাই")) {
            return try {
                val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                LocalActionOutcome(true, true, "ওয়াইফাই সেটিংস ওপেন করে দিয়েছি।")
            } catch (e: Exception) {
                LocalActionOutcome(true, false, "ওয়াইফাই সেটিংস ওপেন করা গেল না।")
            }
        }

        // Android Settings
        if (target.contains("setting") || target.contains("সেটিংস") || target.contains("সেটিং")) {
            return try {
                val intent = Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                LocalActionOutcome(true, true, "ফোনের সেটিংস ওপেন করে দিয়েছি।")
            } catch (e: Exception) {
                LocalActionOutcome(true, false, "সেটিংস ওপেন করা গেল না।")
            }
        }

        // Camera
        if (target.contains("camera") || target.contains("ক্যামেরা")) {
            return try {
                val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                LocalActionOutcome(true, true, "ক্যামেরা ওপেন করে দিয়েছি।")
            } catch (e: Exception) {
                LocalActionOutcome(true, false, "ক্যামেরা ওপেন করা গেল না।")
            }
        }

        // Play Store
        if (target.contains("play store") || target.contains("প্লে স্টোর")) {
            if (launchByPackageList(context, listOf("com.android.vending"))) {
                return LocalActionOutcome(true, true, "প্লে স্টোর ওপেন করে দিচ্ছি।")
            }
        }

        // Clock / Alarm
        if (target.contains("alarm") || target.contains("clock") || target.contains("অ্যালার্ম") || target.contains("ঘড়ি")) {
            return try {
                val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                LocalActionOutcome(true, true, "অ্যালার্ম ওপেন করে দিয়েছি।")
            } catch (e: Exception) {
                LocalActionOutcome(true, false, "অ্যালার্ম ওপেন করা গেল না।")
            }
        }

        // Generic Installed App Search (Searches all installed launcher apps)
        val genericLaunched = launchInstalledAppByName(context, target)
        return if (genericLaunched != null) {
            LocalActionOutcome(true, true, "$genericLaunched ওপেন করে দিচ্ছি জান।")
        } else {
            LocalActionOutcome(false, false, "")
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
        val clean = normalizeBn(query)
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
            val label = normalizeBn(info.loadLabel(pm).toString())
            val pkg = info.activityInfo.packageName.lowercase()
            label.contains(clean) || clean.contains(label) || pkg.contains(clean)
        } ?: return null

        val labelName = matched.loadLabel(pm).toString()
        val launchIntent = pm.getLaunchIntentForPackage(matched.activityInfo.packageName)
            ?: return null
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
        return labelName
    }
}
