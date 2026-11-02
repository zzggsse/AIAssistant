package com.aibutler.app

import android.content.Context
import com.google.gson.Gson
import java.io.File

object ConversationStore {

    data class Conversation(
        val id: String,
        var title: String,
        var updatedAt: Long,
        val messages: MutableList<ChatAdapter.Msg>
    )

    private data class Snapshot(
        var currentId: String,
        var conversations: MutableList<Conversation>
    )

    private val gson = Gson()

    @Volatile
    private var cached: Snapshot? = null

    private val lock = Any()

    fun list(context: Context): List<Conversation> = synchronized(lock) {
        snapshot(context).conversations.sortedByDescending { it.updatedAt }
    }

    fun currentId(context: Context): String = synchronized(lock) {
        snapshot(context).currentId
    }

    fun loadCurrent(context: Context): List<ChatAdapter.Msg> = synchronized(lock) {
        snapshot(context).conversations
            .firstOrNull { it.id == snapshot(context).currentId }
            ?.messages
            .orEmpty()
            .map { it.copy() }
    }

    fun saveCurrent(context: Context, messages: List<ChatAdapter.Msg>): Conversation = synchronized(lock) {
        val state = snapshot(context)
        val current = state.conversations.firstOrNull { it.id == state.currentId }
            ?: newConversation().also {
                state.conversations.add(it)
                state.currentId = it.id
            }
        current.messages.clear()
        current.messages.addAll(messages.map { it.copy() })
        current.title = titleFor(messages)
        current.updatedAt = lastMessageAt(messages, current.updatedAt)
        persist(context, state)
        current
    }

    fun startNew(context: Context): Conversation = synchronized(lock) {
        val state = snapshot(context)
        val current = state.conversations.firstOrNull { it.id == state.currentId }
        if (current != null && current.messages.isEmpty()) {
            persist(context, state)
            return current
        }
        val conversation = newConversation()
        state.conversations.add(conversation)
        state.currentId = conversation.id
        persist(context, state)
        conversation
    }

    fun switchTo(context: Context, conversationId: String): Boolean = synchronized(lock) {
        val state = snapshot(context)
        if (state.conversations.none { it.id == conversationId }) return false
        state.currentId = conversationId
        persist(context, state)
        true
    }

    fun delete(context: Context, conversationId: String) = synchronized(lock) {
        val state = snapshot(context)
        state.conversations.removeAll { it.id == conversationId }
        if (state.conversations.isEmpty()) {
            val conversation = newConversation()
            state.conversations.add(conversation)
            state.currentId = conversation.id
        } else if (state.currentId == conversationId) {
            state.currentId = state.conversations.sortedByDescending { it.updatedAt }.first().id
        }
        persist(context, state)
    }

    fun clearAll(context: Context) = synchronized(lock) {
        val conversation = newConversation()
        cached = Snapshot(conversation.id, mutableListOf(conversation))
        persist(context, cached!!)
    }

    private fun snapshot(context: Context): Snapshot {
        cached?.let { return it }
        val file = file(context)
        val loaded = runCatching {
            if (file.exists()) gson.fromJson(file.readText(), Snapshot::class.java) else null
        }.getOrNull()

        val state = if (loaded?.conversations != null && loaded.conversations.isNotEmpty()) {
            loaded
        } else {
            val legacy = ChatHistoryStore.load(context)
            val conversation = newConversation().apply {
                if (legacy.isNotEmpty()) {
                    messages.addAll(legacy)
                    title = titleFor(legacy)
                }
            }
            Snapshot(conversation.id, mutableListOf(conversation))
        }

        if (state.conversations.none { it.id == state.currentId }) {
            state.currentId = state.conversations.first().id
        }
        state.conversations.forEach { conversation ->
            if (conversation.messages.isEmpty()) conversation.title = "新对话"
        }
        cached = state
        val persistSucceeded = persist(context, state)
        // 旧 chat_history.jsonl 已并入 conversations.json：迁移源清理，不再永久占盘。
        // 只有本次新存储真实写入成功才删；失败时保留旧文件，下一次启动还能迁移。
        if (persistSucceeded) ChatHistoryStore.deleteLegacyFile(context)
        return state
    }

    /** 会话数量上限：conversations.json 是全量重写式持久化，无上限会无限膨胀
     *  （PathMemory/TaskCardMemory/ExitMemory 都有 30/120 条上限，体检第六轮 P3-4） */
    private const val MAX_CONVERSATIONS = 50

    private fun persist(context: Context, state: Snapshot): Boolean {
        cached = state
        trimToCap(state)
        return runCatching {
            AtomicFiles.write(file(context), gson.toJson(state))
        }.onFailure { e ->
            // 写盘失败（磁盘满等）不能静默：用户以为已保存，实际一重启就丢
            CrashLogger.log("ConversationStore", "会话持久化失败：${e.message}", e)
        }.isSuccess
    }

    /** 超限时按 updatedAt 留最近 N 个；当前会话若掉出保留集，挤掉保留集里最旧的一个（当前会话永不丢） */
    private fun trimToCap(state: Snapshot) {
        if (state.conversations.size <= MAX_CONVERSATIONS) return
        val keep = state.conversations.sortedByDescending { it.updatedAt }
            .take(MAX_CONVERSATIONS)
            .toMutableList()
        if (keep.none { it.id == state.currentId }) {
            val current = state.conversations.firstOrNull { it.id == state.currentId }
            if (current != null) keep[keep.size - 1] = current
        }
        state.conversations.clear()
        state.conversations.addAll(keep)
    }

    private fun newConversation() = Conversation(
        id = System.currentTimeMillis().toString(36) + "_" +
                java.util.UUID.randomUUID().toString().take(8),
        title = "新对话",
        updatedAt = System.currentTimeMillis(),
        messages = mutableListOf()
    )

    private fun titleFor(messages: List<ChatAdapter.Msg>): String {
        val first = messages.firstOrNull { it.role == "user" && it.text.isNotBlank() }?.text
            ?: return "新对话"
        val line = first.lineSequence().firstOrNull { it.isNotBlank() } ?: return "新对话"
        val clean = line.trim().replace(Regex("\\s+"), " ")
        return clean.take(24) + if (clean.length > 24) "…" else ""
    }

    internal fun lastMessageAt(messages: List<ChatAdapter.Msg>, fallback: Long): Long =
        messages.maxOfOrNull { it.sentAt }?.takeIf { it > 0 } ?: fallback

    private fun file(context: Context) = File(context.filesDir, "conversations.json")
}
