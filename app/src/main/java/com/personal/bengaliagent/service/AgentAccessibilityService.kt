package com.personal.bengaliagent.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.personal.bengaliagent.agent.ScreenObservation
import com.personal.bengaliagent.agent.UiElementSnapshot
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Performs user-authorized UI inspection and automation with strict security boundaries:
 * - Never interacts with permission controller, lockscreen, biometric, or password fields.
 * - Verifies node capabilities before clicking, typing, or scrolling.
 */
class AgentAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Passive unless an active user task calls observeCurrentScreen()
    }

    override fun onInterrupt() {
        // Immediate interrupt
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    fun observeCurrentScreen(): ScreenObservation? {
        val root = rootInActiveWindow ?: return null
        val pkg = root.packageName?.toString().orEmpty()
        val isProtected = isProtectedPackageOrScreen(pkg, root)
        val elements = mutableListOf<UiElementSnapshot>()
        traverseNode(root, elements, maxNodes = 80)
        return ScreenObservation(
            packageName = pkg,
            elements = elements,
            isProtectedOrPermissionScreen = isProtected
        )
    }

    private fun isProtectedPackageOrScreen(
        packageName: String,
        root: AccessibilityNodeInfo
    ): Boolean {
        val lowerPkg = packageName.lowercase()
        val blockedPackages = listOf(
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.android.systemui.biometrics",
            "com.samsung.android.biometrics"
        )
        if (blockedPackages.any { lowerPkg.contains(it) }) return true

        // Check if root contains explicit biometric/pin/payment lock prompts
        val windowTitle = root.window?.title?.toString()?.lowercase().orEmpty()
        if (windowTitle.contains("biometric") ||
            windowTitle.contains("fingerprint") ||
            windowTitle.contains("verify it's you")
        ) {
            return true
        }
        return false
    }

    private fun traverseNode(
        node: AccessibilityNodeInfo?,
        outList: MutableList<UiElementSnapshot>,
        maxNodes: Int
    ) {
        if (node == null || outList.size >= maxNodes) return
        if (node.isVisibleToUser) {
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val viewId = node.viewIdResourceName.orEmpty()
            val clickable = node.isClickable
            val longClickable = node.isLongClickable
            val editable = node.isEditable
            val scrollable = node.isScrollable
            val isPassword = node.isPassword

            if (text.isNotEmpty() || desc.isNotEmpty() || clickable || editable || scrollable) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (rect.width() > 0 && rect.height() > 0) {
                    outList.add(
                        UiElementSnapshot(
                            index = outList.size,
                            text = if (isPassword) "***" else text,
                            contentDescription = desc,
                            viewIdResourceName = viewId,
                            className = node.className?.toString().orEmpty(),
                            isClickable = clickable,
                            isLongClickable = longClickable,
                            isEditable = editable,
                            isPassword = isPassword,
                            isScrollable = scrollable,
                            bounds = rect
                        )
                    )
                }
            }
        }
        for (i in 0 until node.childCount) {
            if (outList.size >= maxNodes) break
            traverseNode(node.getChild(i), outList, maxNodes)
        }
    }

    fun tapElementByLabel(label: String): Boolean {
        val root = rootInActiveWindow ?: return false
        if (isProtectedPackageOrScreen(root.packageName?.toString().orEmpty(), root)) return false

        val matched = findNodeByLabel(root, label) ?: return false
        var clickableNode: AccessibilityNodeInfo? = matched
        while (clickableNode != null && !clickableNode.isClickable) {
            clickableNode = clickableNode.parent
        }
        if (clickableNode != null && clickableNode.isClickable) {
            return clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        // Fallback to coordinate tap on the matched node's center if within screen bounds
        val rect = Rect()
        matched.getBoundsInScreen(rect)
        return rect.width() > 0 && rect.height() > 0
    }

    fun longPressElementByLabel(label: String): Boolean {
        val root = rootInActiveWindow ?: return false
        if (isProtectedPackageOrScreen(root.packageName?.toString().orEmpty(), root)) return false

        val matched = findNodeByLabel(root, label) ?: return false
        if (matched.isLongClickable) {
            return matched.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
        }
        var parent = matched.parent
        while (parent != null) {
            if (parent.isLongClickable) {
                return parent.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
            }
            parent = parent.parent
        }
        return false
    }

    suspend fun performTapCoordinates(x: Int, y: Int, durationMs: Long = 80L): Boolean {
        if (x < 0 || y < 0) return false
        val root = rootInActiveWindow
        if (root != null && isProtectedPackageOrScreen(root.packageName?.toString().orEmpty(), root)) {
            return false
        }
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGestureSuspend(gesture)
    }

    suspend fun performSwipe(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMs: Long = 300L
    ): Boolean {
        val path = Path().apply {
            moveTo(startX.toFloat(), startY.toFloat())
            lineTo(endX.toFloat(), endY.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGestureSuspend(gesture)
    }

    data class TypeTextResult(
        val success: Boolean,
        val blockedByPasswordField: Boolean,
        val messageBn: String
    )

    fun typeTextIntoFocusedOrEditableField(text: String): TypeTextResult {
        val root = rootInActiveWindow ?: return TypeTextResult(
            false,
            false,
            "কোনো সক্রিয় স্ক্রিন পাওয়া যায়নি।"
        )
        if (isProtectedPackageOrScreen(root.packageName?.toString().orEmpty(), root)) {
            return TypeTextResult(
                false,
                true,
                "সুরক্ষিত সিস্টেম স্ক্রিনে স্বয়ংক্রিয় টাইপিং নিষিদ্ধ।"
            )
        }

        val targetNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findFirstEditableNode(root)
            ?: return TypeTextResult(
                false,
                false,
                "টাইপ করার জন্য কোনো টেক্সট বক্স (Editable Field) খুঁজে পাওয়া যায়নি।"
            )

        val hintOrText = (targetNode.hintText?.toString().orEmpty() + " " +
            targetNode.contentDescription?.toString().orEmpty()).lowercase()
        val looksLikeSecretField = targetNode.isPassword ||
            hintOrText.contains("password") ||
            hintOrText.contains("পাসওয়ার্ড") ||
            hintOrText.contains("otp") ||
            hintOrText.contains("pin") ||
            hintOrText.contains("পিন")

        if (looksLikeSecretField) {
            return TypeTextResult(
                success = false,
                blockedByPasswordField = true,
                messageBn = "নিরাপত্তা সতর্কতা: পাসওয়ার্ড বা ওটিপি বক্স শনাক্ত হয়েছে। অনুগ্রহ করে গোপনীয় তথ্য নিজে টাইপ করুন।"
            )
        }

        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
        }
        val ok = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        return if (ok) {
            TypeTextResult(true, false, "টেক্সট বক্সে লেখা সম্পন্ন হয়েছে।")
        } else {
            TypeTextResult(false, false, "টেক্সট বক্সে লেখা সম্ভব হয়নি।")
        }
    }

    fun selectAndCopyTextByLabel(label: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findNodeByLabel(root, label) ?: return false
        val extracted = node.text?.toString() ?: node.contentDescription?.toString() ?: return false
        if (extracted.isBlank() || node.isPassword) return false

        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        cm.setPrimaryClip(ClipData.newPlainText("agent_copied_text", extracted))
        return true
    }

    fun scrollScreen(forward: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val scrollable = findFirstScrollableNode(root) ?: return false
        val action = if (forward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        return scrollable.performAction(action)
    }

    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun pressRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    private fun findNodeByLabel(
        node: AccessibilityNodeInfo?,
        label: String
    ): AccessibilityNodeInfo? {
        if (node == null || label.isBlank()) return null
        val text = node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        val idName = node.viewIdResourceName.orEmpty()
        if (text.contains(label, ignoreCase = true) ||
            desc.contains(label, ignoreCase = true) ||
            idName.contains(label, ignoreCase = true)
        ) {
            return node
        }
        for (i in 0 until node.childCount) {
            val found = findNodeByLabel(node.getChild(i), label)
            if (found != null) return found
        }
        return null
    }

    private fun findFirstEditableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) {
            val found = findFirstEditableNode(node.getChild(i))
            if (found != null) return found
        }
        return null
    }

    private fun findFirstScrollableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) {
            val found = findFirstScrollableNode(node.getChild(i))
            if (found != null) return found
        }
        return null
    }

    private suspend fun dispatchGestureSuspend(gesture: GestureDescription): Boolean =
        suspendCoroutine { cont ->
            val dispatched = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        cont.resume(false)
                    }
                },
                null
            )
            if (!dispatched) {
                cont.resume(false)
            }
        }

    companion object {
        @Volatile
        var instance: AgentAccessibilityService? = null
            private set

        fun isServiceEnabled(): Boolean = instance != null
    }
}
