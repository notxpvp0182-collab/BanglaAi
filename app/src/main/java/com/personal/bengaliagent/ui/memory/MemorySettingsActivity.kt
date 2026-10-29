package com.personal.bengaliagent.ui.memory

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.personal.bengaliagent.AgentApp
import com.personal.bengaliagent.data.db.MemoryFactEntity
import com.personal.bengaliagent.databinding.ActivityMemorySettingsBinding
import kotlinx.coroutines.launch

class MemorySettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMemorySettingsBinding
    private val memoryRepo by lazy { AgentApp.instance.memoryRepository }
    private lateinit var adapter: MemoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMemorySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = MemoryAdapter(
            onEditClicked = { showAddOrEditDialog(it) },
            onDeleteClicked = { fact ->
                lifecycleScope.launch {
                    memoryRepo.deleteFact(fact.id)
                    loadFacts()
                }
            }
        )

        binding.rvMemoryFacts.layoutManager = LinearLayoutManager(this)
        binding.rvMemoryFacts.adapter = adapter

        binding.btnSearchMemory.setOnClickListener {
            val q = binding.etSearchMemory.text?.toString().orEmpty()
            loadFacts(q)
        }

        binding.btnAddMemory.setOnClickListener {
            showAddOrEditDialog(null)
        }

        binding.btnExportMemory.setOnClickListener {
            lifecycleScope.launch {
                val json = memoryRepo.exportMemoryAsJson()
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("agent_memory_export", json))
                Toast.makeText(
                    this@MemorySettingsActivity,
                    "Memory JSON copied to clipboard (${json.length} bytes)",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        binding.btnClearAllMemory.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear All Memory?")
                .setMessage("This will permanently delete all saved facts, conversation transcripts, and task history from local storage.")
                .setPositiveButton("Clear All") { _, _ ->
                    lifecycleScope.launch {
                        memoryRepo.clearAllMemory()
                        loadFacts()
                        Toast.makeText(
                            this@MemorySettingsActivity,
                            "All local memory cleared.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.btnCloseMemory.setOnClickListener { finish() }

        loadFacts()
    }

    private fun loadFacts(query: String = "") {
        lifecycleScope.launch {
            val list = memoryRepo.searchFacts(query)
            adapter.submitList(list)
        }
    }

    private fun showAddOrEditDialog(existing: MemoryFactEntity?) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 10)
        }
        val etTitle = EditText(this).apply {
            hint = "Title (e.g., Favorite Language / Project Goal)"
            setText(existing?.title.orEmpty())
        }
        val etDetails = EditText(this).apply {
            hint = "Details..."
            setText(existing?.details.orEmpty())
        }
        container.addView(etTitle)
        container.addView(etDetails)

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Add Memory Fact" else "Edit Memory Fact")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val title = etTitle.text?.toString()?.trim().orEmpty()
                val details = etDetails.text?.toString()?.trim().orEmpty()
                if (title.isNotEmpty() && details.isNotEmpty()) {
                    lifecycleScope.launch {
                        memoryRepo.addOrUpdateFact(
                            id = existing?.id ?: 0L,
                            category = existing?.category ?: "FACT",
                            title = title,
                            details = details
                        )
                        loadFacts()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
