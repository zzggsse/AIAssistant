package com.aibutler.app

import android.content.Context
import com.google.gson.Gson
import java.io.File

/**
 * 旧版聊天历史（chat_history.jsonl）。
 * v1.12.0 起历史统一存 ConversationStore（conversations.json），本类只保留一次性迁移用的 load；
 * append/clear 自迁移后全项目零调用，已删除（体检第六轮 P3-7）。
 */
object ChatHistoryStore {
    private val gson = Gson()

    private fun file(context: Context) = File(context.filesDir, "chat_history.jsonl")

    fun load(context: Context): List<ChatAdapter.Msg> {
        // jsonl 逐行解析、单行容错：一行损坏只丢该行，不再让整份历史清零
        val lines = runCatching { file(context).readLines() }.getOrDefault(emptyList())
        return lines.mapNotNull { line ->
            if (line.isBlank()) null
            else runCatching { gson.fromJson(line, ChatAdapter.Msg::class.java) }.getOrNull()
        }
    }

    /** 迁移完成后清理旧文件（调用方须先确认新存储已成功落盘） */
    fun deleteLegacyFile(context: Context) {
        runCatching { file(context).delete() }
    }
}
