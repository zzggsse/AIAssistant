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
        var liked: Boolean = false,
        /** vision 专用：所属任务是否正在执行（true=进行中）。任务结束或重新载入会话时置 false */
        var running: Boolean = false,
        /** vision 专用：任务结束时的状态文案（✅ 已完成任务 / ⏸️ 任务已暂停 / ⛔ 任务已停止） */
        var statusNote: String? = null
    )

    companion object {
        const val TYPE_USER = 0
        const val TYPE_ASSISTANT = 1
        const val TYPE_SYSTEM = 2
        const val TYPE_THINK = 3
        const val TYPE_VISION = 4
        /** 思考过程单条最长展示字符数，避免超长推理把列表撑爆（只截断展示，不影响发给模型的内容） */
        private const val MAX_THINK_CHARS = 4000
        /** 视觉消息图片按目标边长降采样的上限（原始截图 2000+px，直接解码既慢又吃内存） */
        private const val VISION_MAX_DIM = 1080
        /** 从截图说明里抠出"第N轮"，折叠状态行上让用户知道这是哪一轮的截图 */
        private val VISION_ROUND = Regex("第\\d+轮")
        // 界面被系统销毁重建后聊天内容仍在（干活线程与新界面共享同一份列表）
        val sharedItems = ArrayList<Msg>()
        private const val SAVE_DELAY_MS = 220L
    }

    var onItemsChanged: (() -> Unit)? = null

    val items: ArrayList<Msg> get() = sharedItems

    private val saveHandler = Handler(Looper.getMainLooper())
    /** 会话保存/点赞落盘都丢到这个单线程执行器，主线程只负责刷 UI（conversations.json 大了以后 ANR 就出在这里） */
    private val ioExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val saveRunnable = Runnable {
        // 拷贝快照再入队：items 是界面与干活线程共享的列表，直接引用可能在保存中途被增删
        val snapshot = ArrayList(items)
        ioExecutor.execute {
            ConversationStore.saveCurrent(context, snapshot)
            saveHandler.post { onItemsChanged?.invoke() }
        }
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
     * 截图默认收起，只显示一行"任务进行中…"状态（对话里连续几十轮截图时不再把列表撑爆），
     * 点状态行才展开看到截图；任务结束后由 settleVision 统一改成终态文案。
     */
    fun addVision(text: String, imagePath: String) {
        val msg = Msg("vision", text, collapsed = true, imagePath = imagePath, running = true)
        items.add(msg)
        scheduleSave()
        notifyDataSetChanged()
        onItemsChanged?.invoke()
    }

    /**
     * 任务结束（完成/暂停/停止/异常）时由任务线程调用：把本任务所有"进行中"的截图状态行改成终态。
     * statusNote 例如"✅ 已完成任务"、"⏸️ 任务已暂停"、"⛔ 任务已停止"。
     */
    fun settleVision(statusNote: String) {
        var changed = false
        for (m in items) {
            if (m.role == "vision" && m.running) {
                m.running = false
                m.statusNote = statusNote
                changed = true
            }
        }
        if (changed) {
            scheduleSave()
            notifyDataSetChanged()
            onItemsChanged?.invoke()
        }
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
        // 重新载入的会话里截图一律折叠收起：running 是"任务正在跑"的瞬时状态，落盘恢复后不可能还在跑
        // （App 半路被杀的残留也一并纠正）；旧版本存的截图没有折叠概念，统一收起避免历史会话一打开全是图
        for (m in items) {
            if (m.role == "vision") {
                m.running = false
                m.collapsed = true
            }
        }
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
                    val snapshot = ArrayList(items)
                    ioExecutor.execute { ConversationStore.saveCurrent(context, snapshot) }
                }
            }
        }
        if (holder.viewType == TYPE_VISION) {
            val round = VISION_ROUND.find(msg.text)?.value?.let { " · $it" } ?: ""
            holder.tvVisionHeader?.text = if (msg.running) "🔄 任务进行中…$round（点开查看截图思考过程）"
            else "${msg.statusNote ?: "✅ 已完成任务"}$round（点开查看截图思考过程）"
            val expanded = !msg.collapsed
            holder.visionBody?.visibility = if (expanded) View.VISIBLE else View.GONE
            holder.tvVisionCaption?.text = msg.text
            holder.ivVisionArrow?.rotation = if (msg.collapsed) -90f else 0f
            holder.ivVisionArrow?.contentDescription =
                if (msg.collapsed) "展开截图思考过程" else "收起截图思考过程"
            val toggle = View.OnClickListener {
                msg.collapsed = !msg.collapsed
                notifyItemChanged(position)
            }
            holder.visionHeader?.setOnClickListener(toggle)
            holder.tvVisionHeader?.setOnClickListener(toggle)
            // 只在展开时才解码截图；折叠态把旧图清掉，几十轮历史截图不再常驻内存
            if (expanded) {
                holder.ivVision?.setImageBitmap(msg.imagePath?.let { path ->
                    decodeSampled(path, VISION_MAX_DIM)
                })
            } else {
                holder.ivVision?.setImageDrawable(null)
            }
        }
    }

    /**
     * 两段式解码：先 inJustDecodeBounds 读尺寸，按目标边长算 inSampleSize（2的幂）再真解码。
     * 绑定 ViewHolder 时在主线程调用，整图解码是滑动卡顿和 OOM 的主要来源。
     */
    private fun decodeSampled(path: String, maxDim: Int): android.graphics.Bitmap? {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var inSampleSize = 1
        if (bounds.outHeight > maxDim || bounds.outWidth > maxDim) {
            val halfH = bounds.outHeight / 2
            val halfW = bounds.outWidth / 2
            while (halfH / inSampleSize >= maxDim && halfW / inSampleSize >= maxDim) {
                inSampleSize *= 2
            }
        }
        return runCatching {
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { this.inSampleSize = inSampleSize })
        }.getOrNull()
    }

    class VHolder(root: android.view.View, val viewType: Int) : RecyclerView.ViewHolder(root) {
        val tv: TextView? = root.findViewById(R.id.tvMsg)
        val tvThinkHeader: TextView? = root.findViewById(R.id.tvThinkHeader)
        val thinkHeader: View? = root.findViewById(R.id.thinkHeader)
        val tvThinkBody: TextView? = root.findViewById(R.id.tvThinkBody)
        val ivThinkArrow: android.widget.ImageView? = root.findViewById(R.id.ivThinkArrow)
        val tvVisionHeader: TextView? = root.findViewById(R.id.tvVisionHeader)
        val visionHeader: View? = root.findViewById(R.id.visionHeader)
        val visionBody: View? = root.findViewById(R.id.visionBody)
        val tvVisionCaption: TextView? = root.findViewById(R.id.tvVisionCaption)
        val ivVisionArrow: android.widget.ImageView? = root.findViewById(R.id.ivVisionArrow)
        val ivVision: android.widget.ImageView? = root.findViewById(R.id.ivVision)
        val likeBurst: LikeBurstView? = root.findViewById(R.id.likeBurst)

        init {
            tv?.setLinkTextColor(ColorStateList.valueOf(root.context.getColor(R.color.colorTextSecondary)))
        }
    }
}
