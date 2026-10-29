package com.personal.bengaliagent.agent

import android.graphics.Rect
import com.personal.bengaliagent.domain.model.RiskLevel

enum class ToolType {
    OPEN_APP,
    READ_SCREEN,
    TAP_ELEMENT,
    TAP_COORDINATES,
    LONG_PRESS,
    TYPE_TEXT,
    SELECT_COPY_TEXT,
    SCROLL_DOWN,
    SCROLL_UP,
    SWIPE,
    PRESS_BACK,
    PRESS_HOME,
    PRESS_RECENTS,
    WAIT_AND_OBSERVE,
    FINAL_ANSWER;

    companion object {
        fun fromString(raw: String): ToolType? =
            entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
    }
}

data class PlannedAction(
    val tool: ToolType,
    val targetLabel: String = "",
    val textToType: String = "",
    val packageNameOrAppName: String = "",
    val x: Int = -1,
    val y: Int = -1,
    val endX: Int = -1,
    val endY: Int = -1,
    val reasoningBn: String = "",
    val riskLevel: RiskLevel = RiskLevel.LOW_RISK
)

data class ActionPlan(
    val thoughtBn: String,
    val actions: List<PlannedAction>,
    val directReplyBn: String = ""
)

data class UiElementSnapshot(
    val index: Int,
    val text: String,
    val contentDescription: String,
    val viewIdResourceName: String,
    val className: String,
    val isClickable: Boolean,
    val isLongClickable: Boolean,
    val isEditable: Boolean,
    val isPassword: Boolean,
    val isScrollable: Boolean,
    val bounds: Rect
) {
    fun centerX(): Int = bounds.centerX()
    fun centerY(): Int = bounds.centerY()

    fun displayLabel(): String {
        return when {
            text.isNotBlank() -> text
            contentDescription.isNotBlank() -> contentDescription
            viewIdResourceName.isNotBlank() -> viewIdResourceName.substringAfterLast("/")
            else -> className.substringAfterLast(".")
        }
    }
}

data class ScreenObservation(
    val packageName: String,
    val elements: List<UiElementSnapshot>,
    val isProtectedOrPermissionScreen: Boolean,
    val timestampMs: Long = System.currentTimeMillis()
) {
    fun toCompactPromptString(maxElements: Int = 40): String {
        val sb = StringBuilder()
        sb.appendLine("Current Package: $packageName")
        if (isProtectedOrPermissionScreen) {
            sb.appendLine("WARNING: Protected system/permission/security screen detected. Automation is restricted.")
        }
        sb.appendLine("<untrusted_screen_content>")
        elements.take(maxElements).forEach { el ->
            val flags = buildList {
                if (el.isClickable) add("CLICKABLE")
                if (el.isLongClickable) add("LONG_CLICK")
                if (el.isEditable) add("EDITABLE")
                if (el.isPassword) add("PASSWORD_FIELD")
                if (el.isScrollable) add("SCROLLABLE")
            }.joinToString(",")
            sb.appendLine(
                "[${el.index}] label=\"${el.displayLabel().take(60)}\" flags=[$flags] bounds=(${el.bounds.left},${el.bounds.top},${el.bounds.right},${el.bounds.bottom})"
            )
        }
        sb.appendLine("</untrusted_screen_content>")
        return sb.toString()
    }
}

data class StepExecutionResult(
    val action: PlannedAction,
    val executed: Boolean,
    val verifiedSuccess: Boolean,
    val observationNoteBn: String
)
