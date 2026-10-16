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
        return runCatching {
            file(context).readLines().mapNotNull { line ->
                if (line.isBlank()) null else gson.fromJson(line, ChatAdapter.Msg::class.java)
            }
        }.getOrDefault(emptyList())
    }

    fun clear(context: Context) {
        runCatching { file(context).writeText("") }
    }
}
