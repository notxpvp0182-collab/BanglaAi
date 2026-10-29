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
                    "মেমোরি JSON ক্লিপবোর্ডে কপি করা হয়েছে (${json.length} bytes)",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        binding.btnClearAllMemory.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("সব মেমোরি মুছে ফেলবেন?")
                .setMessage("এটি আপনার সংরক্ষিত সব তথ্য, চ্যাট ট্রান্সক্রিপ্ট এবং কাজের ইতিহাস স্থায়ীভাবে মুছে ফেলবে।")
                .setPositiveButton("হ্যাঁ, মুছুন") { _, _ ->
                    lifecycleScope.launch {
                        memoryRepo.clearAllMemory()
                        loadFacts()
                        Toast.makeText(
                            this@MemorySettingsActivity,
                            "সব মেমোরি মুছে ফেলা হয়েছে",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                .setNegativeButton("বাতিল", null)
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
            hint = "শিরোনাম (যেমন: প্রিয় খাবার / প্রজেক্টের নাম)"
            setText(existing?.title.orEmpty())
        }
        val etDetails = EditText(this).apply {
            hint = "বিস্তারিত তথ্য..."
            setText(existing?.details.orEmpty())
        }
        container.addView(etTitle)
        container.addView(etDetails)

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "নতুন মেমোরি যোগ করুন" else "মেমোরি সংশোধন করুন")
            .setView(container)
            .setPositiveButton("সংরক্ষণ") { _, _ ->
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
            .setNegativeButton("বাতিল", null)
            .show()
    }
}
