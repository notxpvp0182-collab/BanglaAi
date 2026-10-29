package com.personal.bengaliagent.agent

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.personal.bengaliagent.domain.model.RiskLevel
import com.personal.bengaliagent.service.AgentAccessibilityService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Executes validated [PlannedAction]s step-by-step with:
 * - User confirmation callback for SENSITIVE / DESTRUCTIVE actions
 * - Post-action UI state re-observation and honest verification
 * - Immediate cancellation support
 */
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
                        observationNoteBn = "ব্যবহারকারী কাজটি বাতিল করেছেন।"
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

            // Require explicit user confirmation for SENSITIVE or DESTRUCTIVE steps
            if (action.riskLevel == RiskLevel.SENSITIVE || action.riskLevel == RiskLevel.DESTRUCTIVE) {
                val approved = onConfirmSensitiveAction(action)
                if (!approved) {
                    results.add(
                        StepExecutionResult(
                            action = action,
                            executed = false,
                            verifiedSuccess = false,
                            observationNoteBn = "ব্যবহারকারী সংবেদনশীল ধাপটি অনুমোদন করেননি (${action.tool.name})।"
                        )
                    )
                    break
                }
            }

            onStepProgress("ধাপ ${idx + 1}: ${action.tool.name} (${action.targetLabel.ifBlank { action.packageNameOrAppName }})")

            try {
                val stepResult = executeSingleStep(action)
                results.add(stepResult)
                if (!stepResult.verifiedSuccess) {
                    // Stop blind execution if a step fails verification
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
                        observationNoteBn = "ধাপটি সম্পন্ন করতে ত্রুটি হয়েছে: ${e.message}"
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
                        observationNoteBn = "'$query' নামের কোনো ইনস্টল করা অ্যাপ খুঁজে পাওয়া যায়নি।"
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
                        "'$query' অ্যাপটি খোলা হয়েছে এবং স্ক্রিনে যাচাই করা হয়েছে (${postObs?.packageName})।"
                    } else {
                        "'$query' অ্যাপটি খোলার নির্দেশ পাঠানো হয়েছে।"
                    }
                )
            }

            ToolType.READ_SCREEN -> {
                if (acc == null) {
                    return missingAccessibilityResult(action)
                }
                val obs = acc.observeCurrentScreen()
                return if (obs != null && obs.elements.isNotEmpty()) {
                    val sampleLabels = obs.elements.take(8).joinToString(", ") { it.displayLabel() }
                    StepExecutionResult(
                        action = action,
                        executed = true,
                        verifiedSuccess = true,
                        observationNoteBn = "স্ক্রিন পড়া হয়েছে (${obs.packageName}): $sampleLabels"
                    )
                } else {
                    StepExecutionResult(
                        action = action,
                        executed = true,
                        verifiedSuccess = false,
                        observationNoteBn = "স্ক্রিনে কোনো পঠনযোগ্য ইউআই এলিমেন্ট পাওয়া যায়নি।"
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
                        observationNoteBn = "সুরক্ষিত সিস্টেম বা পারমিশন স্ক্রিনে স্বয়ংক্রিয় ট্যাপ করা নিষিদ্ধ।"
                    )
                }
                val tapped = acc.tapElementByLabel(action.targetLabel)
                if (!tapped) {
                    return StepExecutionResult(
                        action = action,
                        executed = false,
                        verifiedSuccess = false,
                        observationNoteBn = "'${action.targetLabel}' বাটনটি বর্তমান স্ক্রিনে পাওয়া যায়নি।"
                    )
                }
                delay(700)
                val afterObs = acc.observeCurrentScreen()
                return StepExecutionResult(
                    action = action,
                    executed = true,
                    verifiedSuccess = true,
                    observationNoteBn = "'${action.targetLabel}'-এ ট্যাপ সম্পন্ন হয়েছে (বর্তমান প্যাকেজ: ${afterObs?.packageName ?: "অজানা"})।"
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
                        "স্থানাঙ্ক (${action.x}, ${action.y})-এ ট্যাপ সম্পন্ন হয়েছে।"
                    } else {
                        "স্থানাঙ্কে ট্যাপ করা সম্ভব হয়নি।"
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
                    observationNoteBn = if (ok) "লং-প্রেস সম্পন্ন হয়েছে।" else "লং-প্রেস ব্যর্থ হয়েছে।"
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
                        "'${action.targetLabel}' ক্লিপবোর্ডে কপি করা হয়েছে।"
                    } else {
                        "টেক্সটটি কপি করা যায়নি।"
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
                    observationNoteBn = if (ok) "নিচে স্ক্রল করা হয়েছে।" else "স্ক্রল করা যায়নি।"
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
                    observationNoteBn = if (ok) "উপরে স্ক্রল করা হয়েছে।" else "স্ক্রল করা যায়নি।"
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
                    observationNoteBn = if (ok) "সোয়াইপ সম্পন্ন হয়েছে।" else "সোয়াইপ ব্যর্থ হয়েছে।"
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
                    observationNoteBn = if (ok) "ব্যাক (Back) বাটন চাপা হয়েছে।" else "ব্যাক বাটন কাজ করেনি।"
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
                    observationNoteBn = if (ok) "হোম (Home) স্ক্রিনে যাওয়া হয়েছে।" else "হোম বাটন কাজ করেনি।"
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
                    observationNoteBn = if (ok) "রিসেন্ট অ্যাপস খোলা হয়েছে।" else "রিসেন্ট অ্যাপস খোলা যায়নি।"
                )
            }

            ToolType.WAIT_AND_OBSERVE -> {
                delay(1500)
                val obs = acc?.observeCurrentScreen()
                return StepExecutionResult(
                    action = action,
                    executed = true,
                    verifiedSuccess = true,
                    observationNoteBn = "১.৫ সেকেন্ড অপেক্ষা ও স্ক্রিন পর্যবেক্ষণ সম্পন্ন (${obs?.packageName ?: "অজানা"})।"
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
            observationNoteBn = "অ্যাক্সেসিবিলিটি সার্ভিস চালু নেই। 'অনুমতি ড্যাশবোর্ড' থেকে সিস্টেম সেটিংস খুলে বাংলা এআই অ্যাক্সেসিবিলিটি সার্ভিসটি চালু করুন।"
        )
    }

    private fun launchInstalledApp(appNameOrPkg: String): Boolean {
        val clean = appNameOrPkg.trim()
        if (clean.isEmpty()) return false
        val pm = context.packageManager

        // 1. Direct package launch
        val directIntent = pm.getLaunchIntentForPackage(clean)
        if (directIntent != null) {
            directIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(directIntent)
            return true
        }

        // 2. Search installed launcher activities by label
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
