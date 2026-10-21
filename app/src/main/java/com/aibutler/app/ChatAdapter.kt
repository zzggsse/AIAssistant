package com.aibutler.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ChatAdapter(private val context: android.content.Context) : RecyclerView.Adapter<ChatAdapter.VHolder>() {

    data class Msg(
        val role: String,
        var text: String,
        var collapsed: Boolean = false,
        val imagePath: String? = null,
        var likeCount: Int = 0,
        var liked: Boolean = false
    )

    companion object {
        const val TYPE_USER = 0
        const val TYPE_ASSISTANT = 1
        const val TYPE_SYSTEM = 2
        const val TYPE_THINK = 3
        const val TYPE_VISION = 4
        /** 思考过程单条最长展示字符数，避免超长推理把列表撑爆（只截断展示，不影响发给模型的内容） */
        private const val MAX_THINK_CHARS = 4000
        // 界面被系统销毁重建后聊天内容仍在（干活线程与新界面共享同一份列表）
        val sharedItems = ArrayList<Msg>()
        private const val SAVE_DELAY_MS = 220L
    }

    var onItemsChanged: (() -> Unit)? = null

    val items: ArrayList<Msg> get() = sharedItems

    private val saveHandler = Handler(Looper.getMainLooper())
    private val saveRunnable = Runnable {
        ConversationStore.saveCurrent(context, items)
        onItemsChanged?.invoke()
    }

    val conversationId: String
        get() = ConversationStore.currentId(context)

    fun add(m: Msg) {
        items.add(m)
        scheduleSave()
        notifyDataSetChanged()
        onItemsChanged?.invoke()
    }

    /**
     * 追加一条默认收起的"思考过程"。
     * 同时写入本机聊天历史，手机端重开 App 后仍能看到；只用于展示，不会回传给模型接口。
     */
    fun addThink(text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        val shown = if (body.length > MAX_THINK_CHARS) body.take(MAX_THINK_CHARS) + "\n…（已截断）" else body
        val msg = Msg("think", shown, collapsed = true)
        items.add(msg)
        scheduleSave()
        notifyDataSetChanged()
        onItemsChanged?.invoke()
    }

    /**
     * 保留真实发给模型的截图，用户能在手机端核对AI这一轮到底看到了什么。
     */
    fun addVision(text: String, imagePath: String) {
        val msg = Msg("vision", text, imagePath = imagePath)
        items.add(msg)
        scheduleSave()
        notifyDataSetChanged()
        onItemsChanged?.invoke()
    }

    fun loadPersisted() {
        replaceItems(ConversationStore.loadCurrent(context))
    }

    fun saveCurrent() {
        cancelSave()
        ConversationStore.saveCurrent(context, items)
    }

    fun newConversation(): Boolean {
        cancelSave()
        if (items.isNotEmpty()) {
            ConversationStore.saveCurrent(context, items)
        }
        val state = ConversationStore.startNew(context)
        replaceItems(state.messages)
        return true
    }

    fun switchTo(conversationId: String): Boolean {
        if (conversationId != this.conversationId && items.isNotEmpty()) {
            saveCurrent()
        } else {
            cancelSave()
        }
        if (!ConversationStore.switchTo(context, conversationId)) return false
        replaceItems(ConversationStore.loadCurrent(context))
        return true
    }

    fun deleteConversation(conversationId: String) {
        if (items.isNotEmpty()) {
            saveCurrent()
        } else {
            cancelSave()
        }
        ConversationStore.delete(context, conversationId)
        replaceItems(ConversationStore.loadCurrent(context))
    }

    fun clearAllConversations() {
        cancelSave()
        ConversationStore.clearAll(context)
        replaceItems(ConversationStore.loadCurrent(context))
    }

    private fun replaceItems(newItems: List<Msg>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
        onItemsChanged?.invoke()
    }

    private fun scheduleSave() {
        saveHandler.removeCallbacks(saveRunnable)
        saveHandler.postDelayed(saveRunnable, SAVE_DELAY_MS)
    }

    private fun cancelSave() {
        saveHandler.removeCallbacks(saveRunnable)
    }

    override fun getItemViewType(position: Int): Int = when (items[position].role) {
        "user" -> TYPE_USER
        "assistant" -> TYPE_ASSISTANT
        "think" -> TYPE_THINK
        "vision" -> TYPE_VISION
        else -> TYPE_SYSTEM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VHolder {
        val layout = when (viewType) {
            TYPE_USER -> R.layout.item_chat_user
            TYPE_ASSISTANT -> R.layout.item_chat_assistant
            TYPE_THINK -> R.layout.item_chat_think
            TYPE_VISION -> R.layout.item_chat_vision
            else -> R.layout.item_chat_system
        }
        val root = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return VHolder(root, viewType)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VHolder, position: Int) {
        val msg = items[position]
        if (holder.viewType == TYPE_THINK) {
            val body = holder.tvThinkBody ?: return
            body.text = msg.text
            body.visibility = if (msg.collapsed) View.GONE else View.VISIBLE
            holder.ivThinkArrow?.rotation = if (msg.collapsed) -90f else 0f
            holder.ivThinkArrow?.contentDescription =
                if (msg.collapsed) "展开思考过程" else "收起思考过程"
            val toggle = View.OnClickListener {
                msg.collapsed = !msg.collapsed
                notifyItemChanged(position)
            }
            holder.thinkHeader?.setOnClickListener(toggle)
            holder.tvThinkHeader?.setOnClickListener(toggle)
        } else {
            holder.tv?.text = msg.text
        }
        if (holder.viewType == TYPE_ASSISTANT) {
            holder.likeBurst?.let { likeView ->
                likeView.setLikeState(msg.likeCount, msg.liked)
                likeView.onLikeChanged = { count, liked ->
                    msg.likeCount = count
                    msg.liked = liked
                    ConversationStore.saveCurrent(context, items)
                }
            }
        }
        if (holder.viewType == TYPE_VISION) {
            holder.ivVision?.setImageBitmap(msg.imagePath?.let { path ->
                runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
            })
        }
    }

    class VHolder(root: android.view.View, val viewType: Int) : RecyclerView.ViewHolder(root) {
        val tv: TextView? = root.findViewById(R.id.tvMsg)
        val tvThinkHeader: TextView? = root.findViewById(R.id.tvThinkHeader)
        val thinkHeader: View? = root.findViewById(R.id.thinkHeader)
        val tvThinkBody: TextView? = root.findViewById(R.id.tvThinkBody)
        val ivThinkArrow: android.widget.ImageView? = root.findViewById(R.id.ivThinkArrow)
        val ivVision: android.widget.ImageView? = root.findViewById(R.id.ivVision)
        val likeBurst: LikeBurstView? = root.findViewById(R.id.likeBurst)

        init {
            tv?.setLinkTextColor(ColorStateList.valueOf(root.context.getColor(R.color.colorTextSecondary)))
        }
    }
}
