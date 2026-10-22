package com.aibutler.app

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Note(val title: String, val content: String, val time: Long)

/**
 * 本地便签：AI 可以帮你记事 / 查便签。存在应用私有目录的 notes.json。
 */
object NotesStore {
    private val gson = Gson()
    private val type = object : TypeToken<MutableList<Note>>() {}.type
    // Agent 工作线程与 UI 线程并发读写：load→改→save 必须整段互斥，否则后写者覆盖前者丢便签
    private val lock = Any()

    private fun file(c: Context) = java.io.File(c.filesDir, "notes.json")

    private fun load(c: Context): MutableList<Note> {
        val f = file(c)
        if (!f.exists()) return mutableListOf()
        return try {
            gson.fromJson<MutableList<Note>>(f.readText(), type) ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun save(c: Context, notes: List<Note>) {
        AtomicFiles.write(file(c), gson.toJson(notes))
    }

    fun create(c: Context, title: String, content: String): String = synchronized(lock) {
        val notes = load(c)
        // time 兼作便签 id：同毫秒连记两条会撞 id（update/delete 会误改对象），挤 +1ms 保证唯一
        var now = System.currentTimeMillis()
        while (notes.any { it.time == now }) now++
        notes.add(0, Note(title.ifBlank { "便签" }, content, now))
        save(c, notes)
        "已记下：「${title.ifBlank { "便签" }}」"
    }

    fun read(c: Context, keyword: String): String = synchronized(lock) {
        val notes = load(c)
        if (notes.isEmpty()) return "（便签是空的）"
        val list = if (keyword.isBlank()) notes
        else notes.filter { it.title.contains(keyword) || it.content.contains(keyword) }
        if (list.isEmpty()) return "没有找到包含「$keyword」的便签"
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
        list.take(30).mapIndexed { i, n ->
            "${i + 1}. 【${n.title}】${n.content} (${fmt.format(Date(n.time))}) [id=${n.time}]"
        }.joinToString("\n")
    }

    fun update(c: Context, id: Long, newTitle: String, newContent: String): String = synchronized(lock) {
        val notes = load(c)
        val idx = notes.indexOfFirst { it.time == id }
        if (idx < 0) return "没有找到编号为 $id 的便签，请先 read_notes 确认编号"
        notes[idx] = notes[idx].copy(
            title = newTitle.ifBlank { notes[idx].title },
            content = newContent.ifBlank { notes[idx].content },
            time = System.currentTimeMillis()
        )
        save(c, notes)
        "已更新便签：「${notes[idx].title}」"
    }

    fun delete(c: Context, id: Long): String = synchronized(lock) {
        val notes = load(c)
        val target = notes.firstOrNull { it.time == id }
            ?: return "没有找到编号为 $id 的便签，请先 read_notes 确认编号"
        save(c, notes.filterNot { it.time == id })
        "已删除便签：「${target.title}」"
    }
}
