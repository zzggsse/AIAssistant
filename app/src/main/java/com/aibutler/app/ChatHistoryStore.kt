package com.aibutler.app

import android.content.Context
import com.google.gson.Gson
import java.io.File

object ChatHistoryStore {
    private val gson = Gson()

    private fun file(context: Context) = File(context.filesDir, "chat_history.jsonl")

    fun append(context: Context, message: ChatAdapter.Msg) {
        runCatching {
            file(context).appendText(gson.toJson(message) + "\n")
        }
    }

    fun load(context: Context): List<ChatAdapter.Msg> {
        // jsonl 逐行解析、单行容错：一行损坏只丢该行，不再让整份历史清零
        val lines = runCatching { file(context).readLines() }.getOrDefault(emptyList())
        return lines.mapNotNull { line ->
            if (line.isBlank()) null
            else runCatching { gson.fromJson(line, ChatAdapter.Msg::class.java) }.getOrNull()
        }
    }

    fun clear(context: Context) {
        runCatching { file(context).writeText("") }
    }
}
