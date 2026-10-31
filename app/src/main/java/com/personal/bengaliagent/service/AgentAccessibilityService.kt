package com.personal.bengaliagent.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.personal.bengaliagent.agent.ScreenObservation
import com.personal.bengaliagent.agent.UiElementSnapshot
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class AgentAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    fun getScreenDimensions(): Pair<Int, Int> {
        return try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            metrics.widthPixels.coerceAtLeast(720) to metrics.heightPixels.coerceAtLeast(1280)
        } catch (_: Exception) {
            1080 to 2400
        }
    }

    fun observeCurrentScreen(): ScreenObservation? {
        val root = rootInActiveWindow ?: return null
        val pkg = root.packageName?.toString().orEmpty()
        val elements = mutableListOf<UiElementSnapshot>()
        traverseNode(root, elements, maxNodes = 90)
        return ScreenObservation(
            packageName = pkg,
            elements = elements,
            isProtectedOrPermissionScreen = false
        )
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

    /**
     * Taps an element by its visible label or falls back to dispatching a real touch gesture
     * at the element's center coordinates so even non-standard UI buttons respond.
     */
    fun tapElementByLabel(label: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val matched = findNodeByLabel(root, label) ?: return false

        var clickableNode: AccessibilityNodeInfo? = matched
        while (clickableNode != null && !clickableNode.isClickable) {
            clickableNode = clickableNode.parent
        }
        if (clickableNode != null && clickableNode.isClickable) {
            if (clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
        }

        val rect = Rect()
        matched.getBoundsInScreen(rect)
        if (rect.width() > 0 && rect.height() > 0) {
            return dispatchTouchFireAndForget(rect.centerX(), rect.centerY(), 90L)
        }
        return false
    }

    fun longPressElementByLabel(label: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val matched = findNodeByLabel(root, label)
        if (matched != null) {
            if (matched.isLongClickable && matched.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
                return true
            }
            val rect = Rect()
            matched.getBoundsInScreen(rect)
            if (rect.width() > 0 && rect.height() > 0) {
                return dispatchTouchFireAndForget(rect.centerX(), rect.centerY(), 850L)
            }
        }
        val (w, h) = getScreenDimensions()
        return dispatchTouchFireAndForget(w / 2, h / 2, 850L)
    }

    fun dispatchTouchFireAndForget(x: Int, y: Int, durationMs: Long = 90L): Boolean {
        if (x < 0 || y < 0) return false
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    fun dispatchSwipeFireAndForget(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMs: Long = 320L
    ): Boolean {
        val path = Path().apply {
            moveTo(startX.toFloat(), startY.toFloat())
            lineTo(endX.toFloat(), endY.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    suspend fun performTapCoordinates(x: Int, y: Int, durationMs: Long = 90L): Boolean {
        if (x < 0 || y < 0) return false
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
        durationMs: Long = 320L
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
            "No active window found."
        )

        val targetNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findFirstEditableNode(root)
            ?: return TypeTextResult(
                false,
                false,
                "No text input box found on this screen."
            )

        if (targetNode.isPassword) {
            return TypeTextResult(
                success = false,
                blockedByPasswordField = true,
                messageBn = "Password field detected. Please type your password privately."
            )
        }

        targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
        }
        val ok = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (ok) {
            return TypeTextResult(true, false, "Typed '$text'.")
        }

        // Fallback: copy to clipboard and paste into field
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("agent_type", text))
        val pasted = targetNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        return if (pasted) {
            TypeTextResult(true, false, "Pasted '$text'.")
        } else {
            TypeTextResult(false, false, "Could not type into the current field.")
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
        val root = rootInActiveWindow
        val scrollable = if (root != null) findFirstScrollableNode(root) else null
        if (scrollable != null) {
            val action = if (forward) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }
            if (scrollable.performAction(action)) return true
        }
        val (w, h) = getScreenDimensions()
        return if (forward) {
            dispatchSwipeFireAndForget(w / 2, (h * 0.75).toInt(), w / 2, (h * 0.25).toInt(), 320L)
        } else {
            dispatchSwipeFireAndForget(w / 2, (h * 0.25).toInt(), w / 2, (h * 0.75).toInt(), 320L)
        }
    }

    fun swipeHorizontal(left: Boolean): Boolean {
        val (w, h) = getScreenDimensions()
        return if (left) {
            dispatchSwipeFireAndForget((w * 0.85).toInt(), h / 2, (w * 0.15).toInt(), h / 2, 300L)
        } else {
            dispatchSwipeFireAndForget((w * 0.15).toInt(), h / 2, (w * 0.85).toInt(), h / 2, 300L)
        }
    }

    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun pressRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun openNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    fun openQuickSettings(): Boolean = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
    fun takeScreenshot(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
    } else false

    private fun normalizeBengaliText(s: String): String =
        s.replace("\u09AF\u09BC", "\u09DF").lowercase().trim()

    private fun findNodeByLabel(
        node: AccessibilityNodeInfo?,
        label: String
    ): AccessibilityNodeInfo? {
        if (node == null || label.isBlank()) return null
        val normLabel = normalizeBengaliText(label)
        val text = normalizeBengaliText(node.text?.toString().orEmpty())
        val desc = normalizeBengaliText(node.contentDescription?.toString().orEmpty())
        val idName = node.viewIdResourceName.orEmpty().lowercase()

        if ((text.isNotEmpty() && text.contains(normLabel)) ||
            (desc.isNotEmpty() && desc.contains(normLabel)) ||
            (idName.isNotEmpty() && idName.contains(normLabel))
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
