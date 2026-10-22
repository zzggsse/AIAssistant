package com.aibutler.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

class ConversationHistoryAdapter(
    private val onConversationClick: (ConversationStore.Conversation) -> Unit,
    private val onDeleteClick: (ConversationStore.Conversation) -> Unit
) : RecyclerView.Adapter<ConversationHistoryAdapter.Holder>() {

    private val items = ArrayList<ConversationStore.Conversation>()
    private var activeId: String = ""

    fun submit(conversations: List<ConversationStore.Conversation>, currentId: String) {
        items.clear()
        items.addAll(conversations)
        activeId = currentId
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_conversation, parent, false)
        return Holder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val conversation = items[position]
        val active = conversation.id == activeId

        holder.root.isActivated = active
        holder.root.setBackgroundResource(
            if (active) R.drawable.bg_history_selected else R.drawable.bg_history_item
        )
        holder.title.text = conversation.title.ifBlank { "新对话" }
        holder.title.setTextColor(
            ContextCompat.getColor(
                holder.root.context,
                if (active) R.color.colorPrimaryDark else R.color.colorTextPrimary
            )
        )
        holder.root.setOnClickListener { onConversationClick(conversation) }
        holder.delete.setOnClickListener { onDeleteClick(conversation) }
    }

    class Holder(root: View) : RecyclerView.ViewHolder(root) {
        val root: View = root
        val title: TextView = root.findViewById(R.id.tvConversationTitle)
        val delete: ImageView = root.findViewById(R.id.btnDeleteConversation)
    }
}
