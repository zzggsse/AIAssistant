package com.aibutler.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ChatAdapter(private val context: android.content.Context) : RecyclerView.Adapter<ChatAdapter.VHolder>() {

    /** 一个执行轮次：轮首是 think/vision，其余是该轮的工具执行记录 */
    internal data class RoundRef(
        /** 0 表示任务开始前的准备段（任务规划、权限提示等），正式轮次从 1 开始 */
        val roundNo: Int,
        val title: String,
        val startIdx: Int,
        /** roundExpanded 标记存放的消息下标 */
        val flagIdx: Int,
        val msgs: List<Int>
    )

    /** 一个任务段：从用户指令开始，到最终回复/暂停/出错提示为止 */
    internal data class TaskGroup(
        val userIdx: Int,
        val rounds: List<RoundRef>,
        val tailIdx: List<Int>
    ) {
        val endIdx: Int get() = tailIdx.lastOrNull() ?: userIdx
    }

    data class Msg(
        val role: String,
        var text: String,
        var collapsed: Boolean = false,
        val imagePath: String? = null,
        var thinkText: String? = null,
        var likeCount: Int = 0,
        var liked: Boolean = false,
        /** vision 专用：所属任务是否正在执行（true=进行中）。任务结束或重新载入会话时置 false */
        var running: Boolean = false,
        /** vision 专用：任务结束时的状态文案（✅ 已完成任务 / ⏸️ 任务已暂停 / ⛔ 任务已停止） */
        var statusNote: String? = null,
        /** 第一层折叠：该任务段整体收起（存放在任务的用户消息上） */
        var taskCollapsed: Boolean = false,
        /** 第二层折叠：该轮是否展开；旧数据缺字段时 Gson 会填 false=收起 */
        var roundExpanded: Boolean = false
    )

    companion object {
        const val TYPE_USER = 0
        const val TYPE_ASSISTANT = 1
        const val TYPE_SYSTEM = 2
        const val TYPE_THINK = 3
        const val TYPE_VISION = 4
        const val TYPE_TASK_HEADER = 5
        const val TYPE_ROUND_HEADER = 6
        const val TYPE_ROUND_DETAIL = 7
        /** 思考过程单条最长展示字符数，避免超长推理把列表撑爆（只截断展示，不影响发给模型的内容） */
        private const val MAX_THINK_CHARS = 4000
        /** 视觉消息图片按目标边长降采样的上限（原始截图 2000+px，直接解码既慢又吃内存） */
        private const val VISION_MAX_DIM = 1080
        /** 从截图说明里抠出"第N轮"，折叠状态行上让用户知道这是哪一轮的截图 */
        private val VISION_ROUND = Regex("第\\d+轮")
        /** 任务终态提示前缀：这些消息永远显示在任务卡之外，任务收起后也能看到结果和原因 */
        private val TERMINAL_SYSTEM_PREFIXES = listOf("⏸️", "❌", "⏹", "🛑")
        // 界面被系统销毁重建后聊天内容仍在（干活线程与新界面共享同一份列表）
        val sharedItems = ArrayList<Msg>()
        private const val SAVE_DELAY_MS = 220L

        /**
         * 把平铺消息切成任务段：每条用户指令一段，段内每个 think/vision 开启新一轮，
         * 最终回复/暂停/出错等终态消息归入段尾（收起任务时也保持可见）。
         * 纯函数，渲染与单元测试共用。
         */
        @JvmStatic
        internal fun buildTaskGroups(items: List<Msg>): List<TaskGroup> {
            val groups = ArrayList<TaskGroup>()
            var i = 0
            while (i < items.size) {
                if (items[i].role != "user") {
                    i++
                    continue
                }
                val userIdx = i
                i++
                val rounds = ArrayList<RoundRef>()
                var curStart = -1
                val curMsgs = ArrayList<Int>()
                var realCount = 0
                val tail = ArrayList<Int>()
                var sealed = false

                fun flushRound() {
                    if (curStart < 0 && curMsgs.isEmpty()) return
                    val no = if (curStart >= 0) ++realCount else 0
                    val flagIdx = if (curStart >= 0) curStart else curMsgs.first()
                    rounds.add(
                        RoundRef(no, roundTitle(items, curStart, curMsgs), curStart, flagIdx, curMsgs.toList())
                    )
                    curStart = -1
                    curMsgs.clear()
                }

                while (i < items.size && items[i].role != "user") {
                    val m = items[i]
                    val terminal = m.role == "assistant" ||
                        (m.role == "system" && TERMINAL_SYSTEM_PREFIXES.any { m.text.startsWith(it) })
                    if (terminal || sealed) {
                        flushRound()
                        sealed = true
                        tail.add(i)
                    } else if (m.role == "think" || m.role == "vision") {
                        flushRound()
                        curStart = i
                        curMsgs.add(i)
                    } else {
                        curMsgs.add(i)
                    }
                    i++
                }
                flushRound()
                if (realCount > 0) groups.add(TaskGroup(userIdx, rounds, tail))
            }
            return groups
        }

        /** 折叠后仍应显示的真实消息下标；任务卡/轮次头是虚拟条目，由渲染层额外插入 */
        @JvmStatic
        internal fun visibleMessageIndices(items: List<Msg>, groups: List<TaskGroup>): List<Int> {
            val visible = ArrayList<Int>()
            var g = 0
            var i = 0
            while (i < items.size) {
                val grp = groups.getOrNull(g)
                if (grp != null && i == grp.userIdx) {
                    visible.add(i)
                    if (!items[i].taskCollapsed) {
                        val tailSet = grp.tailIdx.toHashSet()
                        for (k in grp.userIdx + 1..grp.endIdx) {
                            if (k !in tailSet) visible.add(k)
                        }
                    }
                    for (t in grp.tailIdx) visible.add(t)
                    g++
                    i = grp.endIdx + 1
                } else {
                    visible.add(i)
                    i++
                }
            }
            return visible
        }

        /** 轮次标题：优先取工具名，其次轮首思考/截图的第一行 */
        private fun roundTitle(items: List<Msg>, startIdx: Int, msgs: List<Int>): String {
            if (startIdx < 0) {
                val first = items.getOrNull(msgs.firstOrNull() ?: -1)?.text
                    ?.lineSequence()?.firstOrNull { it.isNotBlank() } ?: "准备"
                return first.take(14)
            }
            val toolName = msgs.asSequence().map { items[it] }
                .firstOrNull { it.role == "system" && it.text.startsWith("🔧 正在执行：") }
                ?.let { m ->
                    m.text.removePrefix("🔧 正在执行：").trim()
                        .substringBefore(" ").substringBefore("{").trim()
                }?.takeIf { it.isNotBlank() }
            if (toolName != null) return toolName.take(14)
            val line = items[startIdx].text.lineSequence().firstOrNull { it.isNotBlank() } ?: "执行动作"
            return line.take(14) + if (line.length > 14) "…" else ""
        }

        /**
         * 两段式解码：先 inJustDecodeBounds 读尺寸，按目标边长算 inSampleSize（2的幂）再真解码。
         * 聊天绑定与放大查看共用；主线程直接调用，靠降采样控制耗时和内存。
         */
        @JvmStatic
        internal fun decodeSampled(path: String, maxDim: Int): android.graphics.Bitmap? {
            val bounds = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0) return null
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

        /**
         * 一段时间里截图和思考合并存成一张卡。加载旧会话时拆回独立文字卡，
         * 让截图折叠头只负责截图，文字思考可被总开关统一收起。
         */
        @JvmStatic
        internal fun splitLegacyVisionThinking(source: List<Msg>): ArrayList<Msg> {
            val result = ArrayList<Msg>(source.size)
            for (item in source) {
                result.add(item)
                item.thinkText?.takeIf { it.isNotBlank() }?.let { thought ->
                    result.add(Msg("think", thought, collapsed = false))
                    item.thinkText = null
                }
            }
            return result
        }

        /** 只收起独立文字思考；截图卡片本身保持默认折叠，不在这里强改。 */
        @JvmStatic
        internal fun collapseAllThinkingItems(target: MutableList<Msg>): Boolean {
            var changed = false
            for (item in target) {
                if (item.role == "think" && !item.collapsed) {
                    item.collapsed = true
                    changed = true
                }
            }
            return changed
        }
    }

    var onItemsChanged: (() -> Unit)? = null

    /** 点击轮次里的截图时回调（路径 + 当前已解码的小图），由界面层弹出放大查看 */
    var onZoomImage: ((path: String, preview: android.graphics.Bitmap?) -> Unit)? = null

    /** 显示条目：真实消息，或任务卡/轮次头/轮次详情这类由分组派生的虚拟条目 */
    private sealed class Entry {
        data class Item(val idx: Int) : Entry()
        data class TaskHeader(val group: TaskGroup) : Entry()
        data class RoundHeader(val group: TaskGroup, val round: RoundRef) : Entry()
        data class RoundDetail(val group: TaskGroup, val round: RoundRef) : Entry()
    }

    private var entriesCache: List<Entry> = emptyList()

    /**
     * 折叠只是“少画几条”：按任务/轮次的折叠状态把平铺消息映射成实际要显示的条目。
     * 每次数据变化后重建，消息量在几百条级别，扫描开销可以忽略。
     */
    private fun rebuildEntries(): List<Entry> {
        val groups = buildTaskGroups(items)
        val entries = ArrayList<Entry>()
        var g = 0
        var i = 0
        while (i < items.size) {
            val grp = groups.getOrNull(g)
            if (grp != null && i == grp.userIdx) {
                entries.add(Entry.Item(i))
                entries.add(Entry.TaskHeader(grp))
                if (!items[i].taskCollapsed) {
                    for (r in grp.rounds) {
                        entries.add(Entry.RoundHeader(grp, r))
                        if (items[r.flagIdx].roundExpanded) entries.add(Entry.RoundDetail(grp, r))
                    }
                }
                for (t in grp.tailIdx) entries.add(Entry.Item(t))
                g++
                i = grp.endIdx + 1
            } else {
                entries.add(Entry.Item(i))
                i++
            }
        }
        return entries
    }

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
     * 视觉任务里思考紧跟最新截图：这时并入同一张截图卡片，展开/收起只用一次点击。
     */
    fun addThink(text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        val shown = if (body.length > MAX_THINK_CHARS) body.take(MAX_THINK_CHARS) + "\n…（已截断）" else body
        // 文字思考默认可见，截图另有折叠头；两者分开后，长任务里也不会靠截图才能看推理。
        val msg = Msg("think", shown, collapsed = false)
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

    fun collapseAllThinking(): Boolean {
        val changed = collapseAllThinkingItems(items)
        if (changed) {
            scheduleSave()
            notifyDataSetChanged()
            onItemsChanged?.invoke()
        }
        return changed
    }

    /** 一键收起所有已展开的任务段 */
    fun collapseAllTasks(): Boolean {
        var changed = false
        for (g in buildTaskGroups(items)) {
            if (!items[g.userIdx].taskCollapsed) {
                items[g.userIdx].taskCollapsed = true
                changed = true
            }
        }
        if (changed) {
            scheduleSave()
            notifyDataSetChanged()
            onItemsChanged?.invoke()
        }
        return changed
    }

    fun hasExpandedTasks(): Boolean =
        buildTaskGroups(items).any { !items[it.userIdx].taskCollapsed }

    /** 任务收尾时调用：把刚结束的任务折成任务卡；单步任务没有多轮过程，不折 */
    fun settleLastTask(): Boolean {
        val group = buildTaskGroups(items).lastOrNull() ?: return false
        val user = items[group.userIdx]
        if (user.taskCollapsed) return false
        user.taskCollapsed = true
        scheduleSave()
        notifyDataSetChanged()
        onItemsChanged?.invoke()
        return true
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
        val normalizedItems = splitLegacyVisionThinking(newItems)
        items.clear()
        items.addAll(normalizedItems)
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

    override fun getItemViewType(position: Int): Int {
        if (position >= entriesCache.size) entriesCache = rebuildEntries()
        return when (val e = entriesCache[position]) {
            is Entry.Item -> when (items[e.idx].role) {
                "user" -> TYPE_USER
                "assistant" -> TYPE_ASSISTANT
                "think" -> TYPE_THINK
                "vision" -> TYPE_VISION
                else -> TYPE_SYSTEM
            }
            is Entry.TaskHeader -> TYPE_TASK_HEADER
            is Entry.RoundHeader -> TYPE_ROUND_HEADER
            is Entry.RoundDetail -> TYPE_ROUND_DETAIL
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VHolder {
        val layout = when (viewType) {
            TYPE_USER -> R.layout.item_chat_user
            TYPE_ASSISTANT -> R.layout.item_chat_assistant
            TYPE_THINK -> R.layout.item_chat_think
            TYPE_VISION -> R.layout.item_chat_vision
            TYPE_TASK_HEADER -> R.layout.item_chat_task_header
            TYPE_ROUND_HEADER -> R.layout.item_chat_round_header
            TYPE_ROUND_DETAIL -> R.layout.item_chat_round_detail
            else -> R.layout.item_chat_system
        }
        val root = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return VHolder(root, viewType)
    }

    override fun getItemCount(): Int {
        entriesCache = rebuildEntries()
        return entriesCache.size
    }

    override fun onBindViewHolder(holder: VHolder, position: Int) {
        if (position >= entriesCache.size) entriesCache = rebuildEntries()
        when (val e = entriesCache[position]) {
            is Entry.TaskHeader -> bindTaskHeader(holder, e.group)
            is Entry.RoundHeader -> bindRoundHeader(holder, e.round)
            is Entry.RoundDetail -> bindRoundDetail(holder, e.round)
            is Entry.Item -> bindMessage(holder, position, e.idx)
        }
    }

    private fun bindMessage(holder: VHolder, position: Int, idx: Int) {
        val msg = items[idx]
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
            holder.tvVisionHeader?.text = if (msg.running) "🔄 任务进行中…$round（点开查看截图）"
            else "${msg.statusNote ?: "✅ 已完成任务"}$round（点开查看截图）"
            val expanded = !msg.collapsed
            holder.visionBody?.visibility = if (expanded) View.VISIBLE else View.GONE
            holder.tvVisionCaption?.text = msg.text
            holder.ivVisionArrow?.rotation = if (msg.collapsed) -90f else 0f
            holder.ivVisionArrow?.contentDescription =
                if (msg.collapsed) "展开屏幕截图" else "收起屏幕截图"
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

    private fun bindTaskHeader(holder: VHolder, group: TaskGroup) {
        val user = items[group.userIdx]
        val firstLine = user.text.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: "任务"
        val title = firstLine.take(12) + if (firstLine.length > 12) "…" else ""
        holder.tvTaskTitle?.text = "任务：$title"
        holder.tvTaskStatus?.text = taskStatusText(group)
        val collapsed = user.taskCollapsed
        holder.ivTaskArrow?.rotation = if (collapsed) -90f else 0f
        holder.ivTaskArrow?.contentDescription = if (collapsed) "展开任务过程" else "收起任务过程"
        holder.taskHeader?.setOnClickListener {
            user.taskCollapsed = !user.taskCollapsed
            notifyDataSetChanged()
            onItemsChanged?.invoke()
        }
    }

    private fun taskStatusText(group: TaskGroup): String {
        if (group.tailIdx.isEmpty()) return "进行中…"
        for (t in group.tailIdx.asReversed()) {
            val m = items[t]
            if (m.role == "assistant") return "✅ 已完成"
            if (m.role == "system") {
                when {
                    m.text.startsWith("⏸️") -> return "⏸️ 已暂停"
                    m.text.startsWith("❌") -> return "❌ 出错"
                    m.text.startsWith("⏹") || m.text.startsWith("🛑") -> return "⛔ 已停止"
                }
            }
        }
        return "已结束"
    }

    private fun bindRoundHeader(holder: VHolder, round: RoundRef) {
        val label = if (round.roundNo > 0) "第${round.roundNo}轮" else "准备"
        holder.tvRoundTitle?.text = "$label · ${round.title}"
        val expanded = items[round.flagIdx].roundExpanded
        holder.ivRoundArrow?.rotation = if (expanded) 0f else -90f
        holder.ivRoundArrow?.contentDescription = if (expanded) "收起这一轮" else "展开这一轮"
        holder.roundHeader?.setOnClickListener {
            val flag = items[round.flagIdx]
            flag.roundExpanded = !flag.roundExpanded
            notifyDataSetChanged()
            onItemsChanged?.invoke()
        }
    }

    /** 第三层：思考 + 截图合并成一张卡，截图点击放大查看 */
    private fun bindRoundDetail(holder: VHolder, round: RoundRef) {
        val roundMsgs = round.msgs.map { items[it] }
        val thinkText = roundMsgs.filter { it.role == "think" }.joinToString("\n\n") { it.text }
        val vision = roundMsgs.firstOrNull { it.role == "vision" && it.imagePath != null }
        val toolsText = roundMsgs.filter { it.role == "system" }.joinToString("\n") { it.text }

        val hasThink = thinkText.isNotBlank()
        holder.tvRoundThink?.text = thinkText
        holder.tvRoundThink?.visibility = if (hasThink) View.VISIBLE else View.GONE
        holder.tvRoundThinkLabel?.visibility = if (hasThink) View.VISIBLE else View.GONE

        val path = vision?.imagePath
        if (path == null) {
            holder.detailImagePath = null
            holder.ivRoundImage?.setImageDrawable(null)
        } else if (holder.detailImagePath != path) {
            // 流式刷新会反复重绑同一轮：图片路径没变就不重新解码
            holder.detailImagePath = path
            holder.ivRoundImage?.setImageBitmap(decodeSampled(path, VISION_MAX_DIM))
        }
        holder.ivRoundImage?.visibility = if (path == null) View.GONE else View.VISIBLE
        holder.ivRoundImage?.setOnClickListener {
            val p = path ?: return@setOnClickListener
            val preview = (holder.ivRoundImage?.drawable as? BitmapDrawable)?.bitmap
            onZoomImage?.invoke(p, preview)
        }
        holder.tvRoundCaption?.text = vision?.text.orEmpty()
        holder.tvRoundCaption?.visibility = if (vision == null) View.GONE else View.VISIBLE
        holder.tvRoundTools?.text = toolsText
        holder.tvRoundTools?.visibility = if (toolsText.isBlank()) View.GONE else View.VISIBLE
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
        val tvTaskTitle: TextView? = root.findViewById(R.id.tvTaskTitle)
        val tvTaskStatus: TextView? = root.findViewById(R.id.tvTaskStatus)
        val ivTaskArrow: android.widget.ImageView? = root.findViewById(R.id.ivTaskArrow)
        val taskHeader: View? = root.findViewById(R.id.taskHeader)
        val tvRoundTitle: TextView? = root.findViewById(R.id.tvRoundTitle)
        val ivRoundArrow: android.widget.ImageView? = root.findViewById(R.id.ivRoundArrow)
        val roundHeader: View? = root.findViewById(R.id.roundHeader)
        val tvRoundThinkLabel: TextView? = root.findViewById(R.id.tvRoundThinkLabel)
        val tvRoundThink: TextView? = root.findViewById(R.id.tvRoundThink)
        val ivRoundImage: android.widget.ImageView? = root.findViewById(R.id.ivRoundImage)
        val tvRoundCaption: TextView? = root.findViewById(R.id.tvRoundCaption)
        val tvRoundTools: TextView? = root.findViewById(R.id.tvRoundTools)

        /** 当前绑定的截图路径，避免流式刷新时重复解码同一张图 */
        var detailImagePath: String? = null

        init {
            tv?.setLinkTextColor(ColorStateList.valueOf(root.context.getColor(R.color.colorTextSecondary)))
        }
    }
}
