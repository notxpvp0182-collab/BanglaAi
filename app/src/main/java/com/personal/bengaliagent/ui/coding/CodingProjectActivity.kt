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
            binding.tvSelectedFolder.text = "নির্বাচিত ফোল্ডার: $uri"
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
            binding.tvCodingStatus.text = "এআই দিয়ে কোড তৈরি করা হচ্ছে..."
            lifecycleScope.launch {
                val requestPrompt =
                    "Write clean, complete source code for file '$fileName'. Context/request: $promptOrNotes. Return only code."
                val routed = AgentApp.instance.aiClientRouter.sendMessageWithFailover(
                    listOf(ChatMessage(1L, ChatMessage.Role.USER, requestPrompt))
                )
                if (routed.success) {
                    binding.etCodeContent.setText(routed.replyText)
                    binding.tvCodingStatus.text =
                        "কোড তৈরি হয়েছে (${routed.providerUsed?.displayName})। সংরক্ষণ করার আগে যাচাই করুন।"
                } else {
                    binding.tvCodingStatus.text = routed.replyText
                }
            }
        }

        binding.btnSaveCodeFile.setOnClickListener {
            val treeUri = selectedTreeUri
            if (treeUri == null) {
                Toast.makeText(this, "প্রথমে প্রজেক্ট ফোল্ডার নির্বাচন করুন", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val fileName = binding.etFileName.text?.toString()?.trim().orEmpty()
            val code = binding.etCodeContent.text?.toString().orEmpty()
            if (fileName.isEmpty()) {
                Toast.makeText(this, "ফাইলের নাম লিখুন", Toast.LENGTH_SHORT).show()
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
                    "'$fileName' সফলভাবে ফোল্ডারে সংরক্ষিত হয়েছে।"
                } else {
                    "ফাইল সংরক্ষণ ব্যর্থ হয়েছে।"
                }
            }
        }

        binding.btnReviewAndRunTermux.setOnClickListener {
            val rawCmd = binding.etShellCommand.text?.toString()?.trim().orEmpty()
            if (rawCmd.isEmpty()) return@setOnClickListener

            val check = CodingWorkspaceManager.evaluateShellCommandSafety(rawCmd)
            AlertDialog.Builder(this)
                .setTitle(
                    if (check.isDestructiveOrHighRisk) "⚠️ ঝুঁকিপূর্ণ কমান্ড সতর্কতা!" else "শেল কমান্ড অনুমোদন"
                )
                .setMessage("${check.warningBn}\n\nকমান্ড:\n$rawCmd")
                .setPositiveButton("অনুমোদন ও কপি করুন") { _, _ ->
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("termux_cmd", rawCmd))
                    val opened = CodingWorkspaceManager.openTermuxApp(this)
                    binding.tvCodingStatus.text = if (opened) {
                        "কমান্ড ক্লিপবোর্ডে কপি করা হয়েছে এবং Termux খোলা হয়েছে।"
                    } else {
                        "কমান্ড ক্লিপবোর্ডে কপি করা হয়েছে (ফোনে Termux ইনস্টল করা নেই)।"
                    }
                }
                .setNegativeButton("বাতিল", null)
                .show()
        }

        binding.btnCloseCodingStudio.setOnClickListener { finish() }
    }
}
