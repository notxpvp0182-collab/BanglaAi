package com.personal.bengaliagent.agent

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.personal.bengaliagent.domain.model.RiskLevel
import com.personal.bengaliagent.service.AgentAccessibilityService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

class ActionExecutor(
    private val context: Context,
    private val onConfirmSensitiveAction: suspend (PlannedAction) -> Boolean,
    private val onStepProgress: (String) -> Unit
) {

    suspend fun executePlan(
        plan: ActionPlan,
        isCancelled: () -> Boolean
    ): List<StepExecutionResult> {
        val results = mutableListOf<StepExecutionResult>()

        for ((idx, action) in plan.actions.take(AgentPlanner.MAX_STEPS_PER_TASK).withIndex()) {
            if (isCancelled()) {
                results.add(
                    StepExecutionResult(
                        action = action,
                        executed = false,
                        verifiedSuccess = false,
                        observationNoteBn = "Task cancelled by user."
                    )
                )
                break
            }

            if (action.tool == ToolType.FINAL_ANSWER) {
                results.add(
                    StepExecutionResult(
                        action = action,
                        executed = true,
                        verifiedSuccess = true,
                        observationNoteBn = plan.directReplyBn.ifBlank { action.reasoningBn }
                    )
                )
                break
            }

            if (action.riskLevel == RiskLevel.SENSITIVE || action.riskLevel == RiskLevel.DESTRUCTIVE) {
                val approved = onConfirmSensitiveAction(action)
                if (!approved) {
                    results.add(
                        StepExecutionResult(
                            action = action,
                            executed = false,
                            verifiedSuccess = false,
                            observationNoteBn = "User declined sensitive action (${action.tool.name})."
                        )
                    )
                    break
                }
            }

            onStepProgress("Step ${idx + 1}: ${action.tool.name} (${action.targetLabel.ifBlank { action.packageNameOrAppName }})")

            try {
                val stepResult = executeSingleStep(action)
                results.add(stepResult)
                if (!stepResult.verifiedSuccess) {
                    break
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                results.add(
                    StepExecutionResult(
                        action = action,
                        executed = false,
                        verifiedSuccess = false,
                        observationNoteBn = "Step failed with error: ${e.message}"
                    )
                )
                break
            }
        }
        return results
    }

    private suspend fun executeSingleStep(action: PlannedAction): StepExecutionResult {
        val acc = AgentAccessibilityService.instance

        when (action.tool) {
            ToolType.OPEN_APP -> {
                val query = action.packageNameOrAppName.ifBlank { action.targetLabel }
                val launched = launchInstalledApp(query)
                if (!launched) {
                    return StepExecutionResult(
                        action = action,
                        executed = false,
                        verifiedSuccess = false,
                        observationNoteBn = "Could not find an installed app matching '$query'."
                    )
                }
                delay(1200)
                val postObs = acc?.observeCurrentScreen()
                val verified = postObs != null && postObs.packageName != context.packageName
                return StepExecutionResult(
                    action = action,
                    executed = true,
                    verifiedSuccess = true,
                    observationNoteBn = if (verified) {
                        "Opened '$query' and verified active package (${postObs?.packageName})."
                    } else {
                        "Sent launch intent for '$query'."
                    }
                )
            }

            ToolType.READ_SCREEN -> {
                if (acc == null) return missingAccessibilityResult(action)
                val obs = acc.observeCurrentScreen()
                return if (obs != null && obs.elements.isNotEmpty()) {
                    val sampleLabels = obs.elements.take(8).joinToString(", ") { it.displayLabel() }
                    StepExecutionResult(
                        action = action,
                        executed = true,
                        verifiedSuccess = true,
                        observationNoteBn = "Screen inspected (${obs.packageName}): $sampleLabels"
                    )
                } else {
                    StepExecutionResult(
                        action = action,
                        executed = true,
                        verifiedSuccess = false,
                        observationNoteBn = "No readable UI elements found on the current screen."
                    )
                }
            }

            ToolType.TAP_ELEMENT -> {
                if (acc == null) return missingAccessibilityResult(action)
                val beforeObs = acc.observeCurrentScreen()
                if (beforeObs?.isProtectedOrPermissionScreen == true) {
                    return StepExecutionResult(
                        action = action,
                        executed = false,
                        verifiedSuccess = false,
                        observationNoteBn = "Automated tapping is blocked on protected system or permission screens."
                    )
                }
                val tapped = acc.tapElementByLabel(action.targetLabel)
                if (!tapped) {
                    return StepExecutionResult(
                        action = action,
                        executed = false,
                        verifiedSuccess = false,
                        observationNoteBn = "Element '${action.targetLabel}' was not found on the current screen."
                    )
                }
                delay(700)
                val afterObs = acc.observeCurrentScreen()
                return StepExecutionResult(
                    action = action,
                    executed = true,
                    verifiedSuccess = true,
                    observationNoteBn = "Tapped '${action.targetLabel}' (Active package: ${afterObs?.packageName ?: "unknown"})."
                )
            }

            ToolType.TAP_COORDINATES -> {
                if (acc == null) return missingAccessibilityResult(action)
                val ok = acc.performTapCoordinates(action.x, action.y)
                delay(600)
                return StepExecutionResult(
                    action = action,
                    executed = ok,
                    verifiedSuccess = ok,
                    observationNoteBn = if (ok) {
                        "Tapped coordinates (${action.x}, ${action.y})."
                    } else {
                        "Coordinate tap failed."
                    }
                )
            }

            ToolType.LONG_PRESS -> {
                if (acc == null) return missingAccessibilityResult(action)
                val ok = if (action.targetLabel.isNotBlank()) {
                    acc.longPressElementByLabel(action.targetLabel)
                } else {
                    acc.performTapCoordinates(action.x, action.y, durationMs = 750L)
                }
                delay(600)
                return StepExecutionResult(
                    action = action,
                    executed = ok,
                    verifiedSuccess = ok,
                    observationNoteBn = if (ok) "Long-press completed." else "Long-press failed."
                )
            }

            ToolType.TYPE_TEXT -> {
                if (acc == null) return missingAccessibilityResult(action)
                val res = acc.typeTextIntoFocusedOrEditableField(action.textToType)
                delay(500)
                return StepExecutionResult(
                    action = action,
                    executed = res.success,
                    verifiedSuccess = res.success,
                    observationNoteBn = res.messageBn
                )
            }

            ToolType.SELECT_COPY_TEXT -> {
                if (acc == null) return missingAccessibilityResult(action)
                val ok = acc.selectAndCopyTextByLabel(action.targetLabel)
                return StepExecutionResult(
                    action = action,
                    executed = ok,
                    verifiedSuccess = ok,
                    observationNoteBn = if (ok) {
                        "Copied '${action.targetLabel}' to clipboard."
                    } else {
                        "Could not copy the requested text."
                    }
                )
            }

            ToolType.SCROLL_DOWN -> {
                if (acc == null) return missingAccessibilityResult(action)
                val ok = acc.scrollScreen(forward = true) ||
                    acc.performSwipe(500, 1400, 500, 450)
                delay(600)
                return StepExecutionResult(
                    action = action,
                    executed = ok,
                    verifiedSuccess = ok,
                    observationNoteBn = if (ok) "Scrolled down." else "Scroll down failed."
                )
            }

            ToolType.SCROLL_UP -> {
                if (acc == null) return missingAccessibilityResult(action)
                val ok = acc.scrollScreen(forward = false) ||
                    acc.performSwipe(500, 450, 500, 1400)
                delay(600)
                return StepExecutionResult(
                    action = action,
                    executed = ok,
                    verifiedSuccess = ok,
                    observationNoteBn = if (ok) "Scrolled up." else "Scroll up failed."
                )
            }

            ToolType.SWIPE -> {
                if (acc == null) return missingAccessibilityResult(action)
                val ok = acc.performSwipe(action.x, action.y, action.endX, action.endY)
                delay(600)
                return StepExecutionResult(
                    action = action,
                    executed = ok,
                    verifiedSuccess = ok,
                    observationNoteBn = if (ok) "Swipe gesture completed." else "Swipe gesture failed."
                )
            }

            ToolType.PRESS_BACK -> {
                if (acc == null) return missingAccessibilityResult(action)
                val ok = acc.pressBack()
                delay(500)
                return StepExecutionResult(
                    action = action,
                    executed = ok,
                    verifiedSuccess = ok,
                    observationNoteBn = if (ok) "Pressed Back." else "Back action failed."
                )
            }

            ToolType.PRESS_HOME -> {
                if (acc == null) return missingAccessibilityResult(action)
                val ok = acc.pressHome()
                delay(500)
                return StepExecutionResult(
                    action = action,
                    executed = ok,
                    verifiedSuccess = ok,
                    observationNoteBn = if (ok) "Navigated to Home screen." else "Home action failed."
                )
            }

            ToolType.PRESS_RECENTS -> {
                if (acc == null) return missingAccessibilityResult(action)
                val ok = acc.pressRecents()
                delay(500)
                return StepExecutionResult(
                    action = action,
                    executed = ok,
                    verifiedSuccess = ok,
                    observationNoteBn = if (ok) "Opened Recent Apps." else "Recent Apps action failed."
                )
            }

            ToolType.WAIT_AND_OBSERVE -> {
                delay(1500)
                val obs = acc?.observeCurrentScreen()
                return StepExecutionResult(
                    action = action,
                    executed = true,
                    verifiedSuccess = true,
                    observationNoteBn = "Waited 1.5s and observed screen (${obs?.packageName ?: "unknown"})."
                )
            }

            ToolType.FINAL_ANSWER -> {
                return StepExecutionResult(
                    action = action,
                    executed = true,
                    verifiedSuccess = true,
                    observationNoteBn = action.reasoningBn
                )
            }
        }
    }

    private fun missingAccessibilityResult(action: PlannedAction): StepExecutionResult {
        return StepExecutionResult(
            action = action,
            executed = false,
            verifiedSuccess = false,
            observationNoteBn = "Accessibility Service is not enabled. Open Permissions Setup to enable Bengali AI Accessibility Assistant in Android Settings."
        )
    }

    private fun launchInstalledApp(appNameOrPkg: String): Boolean {
        val clean = appNameOrPkg.trim()
        if (clean.isEmpty()) return false
        val pm = context.packageManager

        val directIntent = pm.getLaunchIntentForPackage(clean)
        if (directIntent != null) {
            directIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(directIntent)
            return true
        }

        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = pm.queryIntentActivities(mainIntent, PackageManager.MATCH_ALL)
        val matched = resolveInfos.firstOrNull { info ->
            val label = info.loadLabel(pm).toString()
            val pkg = info.activityInfo.packageName
            label.contains(clean, ignoreCase = true) || pkg.contains(clean, ignoreCase = true)
        } ?: return false

        val launchIntent = pm.getLaunchIntentForPackage(matched.activityInfo.packageName)
            ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
        return true
    }
}
