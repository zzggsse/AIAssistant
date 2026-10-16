package com.aibutler.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

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
        holder.title.setTextColor(
            holder.root.context.getColor(if (active) R.color.colorPrimary else R.color.colorTextPrimary)
        )
        holder.title.text = conversation.title.ifBlank { "新对话" }
        holder.time.text = formatTime(conversation.updatedAt)
        holder.root.setOnClickListener { onConversationClick(conversation) }
        holder.delete.setOnClickListener { onDeleteClick(conversation) }
    }

    private fun formatTime(time: Long): String {
        val calendar = Calendar.getInstance().apply { timeInMillis = time }
        val now = Calendar.getInstance()
        val sameYear = calendar.get(Calendar.YEAR) == now.get(Calendar.YEAR)
        val dayOfYear = calendar.get(Calendar.DAY_OF_YEAR)
        val today = now.get(Calendar.DAY_OF_YEAR)

        return when {
            sameYear && dayOfYear == today ->
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(time))
            sameYear && dayOfYear == today - 1 -> "昨天"
            sameYear ->
                SimpleDateFormat("M月d日", Locale.CHINESE).format(Date(time))
            else ->
                SimpleDateFormat("yyyy/M/d", Locale.getDefault()).format(Date(time))
        }
    }

    class Holder(root: View) : RecyclerView.ViewHolder(root) {
        val root: View = root
        val title: TextView = root.findViewById(R.id.tvConversationTitle)
        val time: TextView = root.findViewById(R.id.tvConversationTime)
        val delete: ImageView = root.findViewById(R.id.btnDeleteConversation)
    }
}
