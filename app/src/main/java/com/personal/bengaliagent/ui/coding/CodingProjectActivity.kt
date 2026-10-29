package com.personal.bengaliagent.ui.coding

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.personal.bengaliagent.AgentApp
import com.personal.bengaliagent.coding.CodingWorkspaceManager
import com.personal.bengaliagent.databinding.ActivityCodingProjectBinding
import com.personal.bengaliagent.domain.model.ChatMessage
import kotlinx.coroutines.launch

class CodingProjectActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCodingProjectBinding
    private var selectedTreeUri: Uri? = null

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            selectedTreeUri = uri
            binding.tvSelectedFolder.text = "Selected Folder: $uri"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCodingProjectBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnSelectProjectFolder.setOnClickListener {
            folderPickerLauncher.launch(null)
        }

        binding.btnGenerateCodeAi.setOnClickListener {
            val fileName = binding.etFileName.text?.toString()?.trim().orEmpty()
            val promptOrNotes = binding.etCodeContent.text?.toString()?.trim().orEmpty()
            binding.tvCodingStatus.text = "Generating code with Google Gemini..."
            lifecycleScope.launch {
                val requestPrompt =
                    "Write clean, complete source code for file '$fileName'. Context/request: $promptOrNotes. Return only code."
                val routed = AgentApp.instance.aiClientRouter.sendMessageWithFailover(
                    listOf(ChatMessage(1L, ChatMessage.Role.USER, requestPrompt))
                )
                if (routed.success) {
                    binding.etCodeContent.setText(routed.replyText)
                    binding.tvCodingStatus.text =
                        "Code generated (${routed.providerUsed?.displayName}). Review before saving."
                } else {
                    binding.tvCodingStatus.text = routed.replyText
                }
            }
        }

        binding.btnSaveCodeFile.setOnClickListener {
            val treeUri = selectedTreeUri
            if (treeUri == null) {
                Toast.makeText(this, "Please select a project folder first.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val fileName = binding.etFileName.text?.toString()?.trim().orEmpty()
            val code = binding.etCodeContent.text?.toString().orEmpty()
            if (fileName.isEmpty()) {
                Toast.makeText(this, "Please enter a file name.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lifecycleScope.launch {
                val saved = CodingWorkspaceManager.writeSourceFileToSafTree(
                    this@CodingProjectActivity,
                    treeUri,
                    fileName,
                    code
                )
                binding.tvCodingStatus.text = if (saved) {
                    "Saved '$fileName' to selected project folder."
                } else {
                    "Failed to save file."
                }
            }
        }

        binding.btnReviewAndRunTermux.setOnClickListener {
            val rawCmd = binding.etShellCommand.text?.toString()?.trim().orEmpty()
            if (rawCmd.isEmpty()) return@setOnClickListener

            val check = CodingWorkspaceManager.evaluateShellCommandSafety(rawCmd)
            AlertDialog.Builder(this)
                .setTitle(
                    if (check.isDestructiveOrHighRisk) "High-Risk Command Warning" else "Confirm Shell Command"
                )
                .setMessage("${check.warningBn}\n\nCommand:\n$rawCmd")
                .setPositiveButton("Approve & Copy") { _, _ ->
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("termux_cmd", rawCmd))
                    val opened = CodingWorkspaceManager.openTermuxApp(this)
                    binding.tvCodingStatus.text = if (opened) {
                        "Command copied to clipboard and Termux launched."
                    } else {
                        "Command copied to clipboard (Termux is not installed on this device)."
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.btnCloseCodingStudio.setOnClickListener { finish() }
    }
}
