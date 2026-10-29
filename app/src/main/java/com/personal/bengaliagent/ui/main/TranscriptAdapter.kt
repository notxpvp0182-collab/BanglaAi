package com.personal.bengaliagent.ui.main

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.personal.bengaliagent.R
import com.personal.bengaliagent.databinding.ItemChatMessageBinding
import com.personal.bengaliagent.domain.model.ChatMessage

class TranscriptAdapter : RecyclerView.Adapter<TranscriptAdapter.MessageViewHolder>() {

    private val items = ArrayList<ChatMessage>()

    fun submitList(newItems: List<ChatMessage>) {
        items.clear()
        val bounded = if (newItems.size > 100) newItems.takeLast(100) else newItems
        items.addAll(bounded)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val binding = ItemChatMessageBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return MessageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class MessageViewHolder(
        private val binding: ItemChatMessageBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(msg: ChatMessage) {
            val ctx = binding.root.context
            when (msg.role) {
                ChatMessage.Role.USER -> {
                    binding.tvSenderHeader.text = "You"
                    binding.tvSenderHeader.setTextColor(
                        ContextCompat.getColor(ctx, R.color.text_primary)
                    )
                    binding.layoutBubbleContainer.setBackgroundResource(R.drawable.bg_chat_user)
                }
                ChatMessage.Role.ASSISTANT -> {
                    val badge = if (!msg.providerUsed.isNullOrBlank()) " • ${msg.providerUsed}" else ""
                    binding.tvSenderHeader.text = "Bengali AI Agent$badge"
                    binding.tvSenderHeader.setTextColor(
                        ContextCompat.getColor(ctx, R.color.accent_cyan)
                    )
                    binding.layoutBubbleContainer.setBackgroundResource(R.drawable.bg_chat_assistant)
                }
                ChatMessage.Role.SYSTEM -> {
                    binding.tvSenderHeader.text = "System Status"
                    binding.tvSenderHeader.setTextColor(
                        ContextCompat.getColor(ctx, R.color.accent_amber)
                    )
                    binding.layoutBubbleContainer.setBackgroundResource(R.drawable.bg_chat_assistant)
                }
            }
            binding.tvMessageBody.text = msg.text
        }
    }
}
