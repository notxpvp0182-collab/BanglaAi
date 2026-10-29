package com.personal.bengaliagent.ui.media

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.personal.bengaliagent.AgentApp
import com.personal.bengaliagent.databinding.ActivityMediaVideoStudioBinding
import com.personal.bengaliagent.media.VideoEditorProcessor
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MediaVideoStudioActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMediaVideoStudioBinding
    private var selectedVideoUri: Uri? = null
    private var activeEditJob: Job? = null

    private val pickVideoLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            selectedVideoUri = uri
            loadSelectedVideoDetails(uri)
        }
    }

    private val createOutputDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("video/mp4")
    ) { destUri ->
        if (destUri != null) {
            runVideoExport(destUri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMediaVideoStudioBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnPickVideo.setOnClickListener {
            pickVideoLauncher.launch("video/*")
        }

        binding.btnPlayExternal.setOnClickListener {
            val uri = selectedVideoUri
            if (uri == null) {
                Toast.makeText(this, "Please select a video first.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching { startActivity(viewIntent) }.onFailure {
                Toast.makeText(this, "No compatible video player found.", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnExportEdited.setOnClickListener {
            if (selectedVideoUri == null) {
                Toast.makeText(this, "Please select a video first.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val ext = if (binding.cbExtractAudioOnly.isChecked) "m4a" else "mp4"
            createOutputDocumentLauncher.launch("bengali_agent_edited_${System.currentTimeMillis()}.$ext")
        }

        binding.btnCancelEdit.setOnClickListener {
            activeEditJob?.cancel()
            binding.tvEditStatus.text = "Video export cancelled by user."
        }

        binding.btnCloseMediaStudio.setOnClickListener { finish() }
    }

    private fun loadSelectedVideoDetails(uri: Uri) {
        binding.tvVideoMetadata.text = "Reading video metadata..."
        lifecycleScope.launch {
            val meta = VideoEditorProcessor.inspectVideo(this@MediaVideoStudioActivity, uri)
            val preview = VideoEditorProcessor.extractPreviewFrame(this@MediaVideoStudioActivity, uri)
            if (preview != null) {
                binding.ivVideoPreview.setImageBitmap(preview)
            }
            if (meta != null) {
                val sec = meta.durationMs / 1000.0
                binding.tvVideoMetadata.text =
                    "Resolution: ${meta.width}x${meta.height} | Duration: ${"%.1f".format(sec)}s | Rotation: ${meta.rotationDegrees}° | Audio: ${if (meta.hasAudio) "Yes" else "No"}"
                binding.etStartSec.setText("0")
                binding.etEndSec.setText((meta.durationMs / 1000L).coerceAtLeast(1L).toString())
            } else {
                binding.tvVideoMetadata.text = "Could not read video metadata."
            }
        }
    }

    private fun runVideoExport(destUri: Uri) {
        val srcUri = selectedVideoUri ?: return
        val startSec = binding.etStartSec.text?.toString()?.toDoubleOrNull() ?: 0.0
        val endSec = binding.etEndSec.text?.toString()?.toDoubleOrNull() ?: 10.0

        val config = VideoEditorProcessor.EditConfig(
            startMs = (startSec * 1000).toLong().coerceAtLeast(0L),
            endMs = (endSec * 1000).toLong().coerceAtLeast(500L),
            muteAudio = binding.cbMuteAudio.isChecked,
            audioOnly = binding.cbExtractAudioOnly.isChecked,
            extraRotationDegrees = if (binding.cbRotate90.isChecked) 90 else 0
        )

        activeEditJob?.cancel()
        activeEditJob = lifecycleScope.launch {
            binding.tvEditStatus.text = "Processing video (0%)..."
            val outcome = VideoEditorProcessor.processVideoNonDestructive(
                context = this@MediaVideoStudioActivity,
                sourceUri = srcUri,
                destUri = destUri,
                config = config,
                isCancelled = { activeEditJob?.isCancelled == true },
                onProgressPct = { pct ->
                    runOnUiThread {
                        binding.tvEditStatus.text = "Processing: $pct%"
                    }
                }
            )
            binding.tvEditStatus.text = outcome.messageBn
            AgentApp.instance.memoryRepository.recordTaskOutcome(
                goal = "Video Edit (${startSec}s - ${endSec}s)",
                status = if (outcome.success) "COMPLETED" else "FAILED",
                outcome = outcome.messageBn,
                steps = 1
            )
        }
    }
}
