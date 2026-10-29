package com.personal.bengaliagent.ui.memory

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.personal.bengaliagent.data.db.MemoryFactEntity
import com.personal.bengaliagent.databinding.ItemMemoryFactBinding

class MemoryAdapter(
    private val onEditClicked: (MemoryFactEntity) -> Unit,
    private val onDeleteClicked: (MemoryFactEntity) -> Unit
) : RecyclerView.Adapter<MemoryAdapter.FactViewHolder>() {

    private val items = ArrayList<MemoryFactEntity>()

    fun submitList(newItems: List<MemoryFactEntity>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FactViewHolder {
        val binding = ItemMemoryFactBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return FactViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FactViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class FactViewHolder(
        private val binding: ItemMemoryFactBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: MemoryFactEntity) {
            binding.tvFactCategory.text = item.category
            binding.tvFactTitle.text = item.title
            binding.tvFactDetails.text = item.details
            binding.btnEditFact.setOnClickListener { onEditClicked(item) }
            binding.btnDeleteFact.setOnClickListener { onDeleteClicked(item) }
        }
    }
}
