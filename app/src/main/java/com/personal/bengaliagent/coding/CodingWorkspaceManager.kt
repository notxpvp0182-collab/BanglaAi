package com.personal.bengaliagent.coding

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object CodingWorkspaceManager {

    private val destructiveShellPatterns = listOf(
        "rm -rf", "rm -r /", "mkfs", "dd if=", ":(){", "chmod -R 777 /",
        "chown -R", "format ", "> /dev/sda", "su -c", "sudo "
    )

    data class CommandSafetyCheck(
        val command: String,
        val isDestructiveOrHighRisk: Boolean,
        val warningBn: String
    )

    fun evaluateShellCommandSafety(rawCommand: String): CommandSafetyCheck {
        val trimmed = rawCommand.trim()
        val isDangerous = destructiveShellPatterns.any {
            trimmed.contains(it, ignoreCase = true)
        }
        return if (isDangerous) {
            CommandSafetyCheck(
                command = trimmed,
                isDestructiveOrHighRisk = true,
                warningBn = "High-risk shell command detected! This command may delete files or modify system permissions. Verify carefully before approving."
            )
        } else {
            CommandSafetyCheck(
                command = trimmed,
                isDestructiveOrHighRisk = false,
                warningBn = "Please review the exact command below before copying it to Termux."
            )
        }
    }

    suspend fun writeSourceFileToSafTree(
        context: Context,
        treeUri: Uri,
        fileName: String,
        codeContent: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val rootDir = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext false
            val cleanName = fileName.trim().replace("..", "_")
            val existing = rootDir.findFile(cleanName)
            val targetDoc = existing ?: rootDir.createFile("text/plain", cleanName)
                ?: return@withContext false

            context.contentResolver.openOutputStream(targetDoc.uri, "wt")?.use { out ->
                out.write(codeContent.toByteArray(Charsets.UTF_8))
            } ?: return@withContext false
            true
        } catch (e: Exception) {
            false
        }
    }

    fun isTermuxInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo("com.termux", PackageManager.GET_ACTIVITIES)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun openTermuxApp(context: Context): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage("com.termux") ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return true
    }
}
