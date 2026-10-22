package com.aibutler.app

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AgentExperience(
    val title: String,
    val content: String,
    val keywords: List<String> = emptyList(),
    val packageNames: List<String> = emptyList(),
    val success: Boolean,
    val updatedAt: Long = System.currentTimeMillis()
)

object AgentExperienceStore {
    private val gson = Gson()
    private val type = object : TypeToken<MutableList<AgentExperience>>() {}.type
    // Agent 工作线程入库与 UI 线程增删改并发：load→改→save 必须整段互斥，否则后写者覆盖前者丢经验
    private val lock = Any()

    private fun file(context: Context) = java.io.File(context.filesDir, "agent_experience.json")

    private fun load(context: Context): MutableList<AgentExperience> {
        val target = file(context)
        if (!target.exists()) return mutableListOf()
        val loaded = try {
            gson.fromJson<MutableList<AgentExperience>>(target.readText(), type) ?: mutableListOf()
        } catch (_: Exception) {
            mutableListOf()
        }
        val clean = loaded.filterNot { isUnusableModelTranscript(it.content) }
        if (clean.size != loaded.size) save(context, clean)
        return clean.toMutableList()
    }

    private fun save(context: Context, items: List<AgentExperience>) {
        AtomicFiles.write(file(context), gson.toJson(items))
    }

    fun relevant(context: Context, task: String, targetPackage: String? = null, limit: Int = 3): String = synchronized(lock) {
        val items = load(context)
        if (items.isEmpty()) return ""
        val normalizedTask = task.lowercase()
        val ranked: List<Pair<AgentExperience, Double>> = items.map { item ->
            val haystack = (item.title + " " + item.content + " " + item.keywords.joinToString(" ")).lowercase()
            val keywordScore: Int = item.keywords.fold(0) { score, keyword ->
                score + if (keyword.isNotBlank() && normalizedTask.contains(keyword.lowercase())) 4 else 0
            }
            val textScore = normalizedTask.windowed(2, 1, partialWindows = true)
                .count { it.length == 2 && haystack.contains(it) }
            val packageScore = if (targetPackage != null && item.packageNames.contains(targetPackage)) 10 else 0
            val recency = ((item.updatedAt - System.currentTimeMillis()) / 86_400_000.0).coerceAtLeast(-30.0) / 30.0
            Pair(item, keywordScore * 3.0 + textScore * 0.1 + packageScore + recency)
        }
            .filter { (item, score) ->
                // 纯字面模糊相似（无关键词/包名佐证）不足为凭，防止跨应用、跨任务串味
                if (item.success) score >= 2.0 else score >= 8.0
            }
            .sortedByDescending { it.second }
            .take(limit)
        if (ranked.isEmpty()) return ""

        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
        return "\n【相关历史结果摘要；不是当前页面的操作指南，按钮和路径必须重新读屏确认】\n" + ranked.joinToString("\n") { entry ->
            val item = entry.first
            val body = if (item.success) item.content else item.content.take(100)
            "· ${item.title}（${if (item.success) "成功经验" else "失败教训，仅用于避免重蹈"}，${fmt.format(Date(item.updatedAt))}）：$body"
        }
    }

    fun all(context: Context): List<AgentExperience> = synchronized(lock) { load(context).sortedByDescending { it.updatedAt } }

    fun upsert(context: Context, experience: AgentExperience): Unit = synchronized(lock) {
        if (isUnusableModelTranscript(experience.content)) return
        val items = load(context)
        val normalizedTitle = experience.title.trim().lowercase().replace(Regex("\\s+"), "")
        val existingIndex = items.indexOfFirst { item ->
            val sameTitle = item.title.trim().lowercase().replace(Regex("\\s+"), "") == normalizedTitle
            val sameTask = experience.keywords.intersect(item.keywords.toSet()).isNotEmpty() &&
                experience.packageNames.intersect(item.packageNames.toSet()).isNotEmpty()
            sameTitle || sameTask
        }
        if (existingIndex >= 0) {
            val old = items[existingIndex]
            items[existingIndex] = experience.copy(
                success = experience.success || old.success,
                updatedAt = System.currentTimeMillis()
            )
        } else {
            items.add(experience)
        }

        while (items.size > 80) {
            val oldestFailure = items
                .withIndex()
                .filter { !it.value.success }
                .minByOrNull { it.value.updatedAt }
            val victim = oldestFailure
                ?: items.withIndex().minByOrNull { it.value.updatedAt }
            items.removeAt(victim!!.index)
        }
        save(context, items)
    }

    private fun isUnusableModelTranscript(content: String): Boolean {
        val normalized = content.trim()
        return normalized.startsWith("用户要求") ||
            normalized.contains("（上一任务的参考结果）") ||
            normalized.contains("</think>", true) ||
            normalized.contains("<answer>", true) ||
            // 只认协议动作原文格式；宽松的 do\s*\($ 会误杀含 "do (something)" 的正常英文经验
            Regex("do\\s*\\(\\s*action\\s*=", RegexOption.IGNORE_CASE).containsMatchIn(normalized)
    }

    fun remove(context: Context, target: AgentExperience): Boolean = synchronized(lock) {
        val items = load(context)
        val index = items.indexOfFirst { it == target }
        if (index < 0) return false
        items.removeAt(index)
        save(context, items)
        return true
    }

    /** 只改标题和详细内容；关键词、包名、成功标记、原记录时间保持不变 */
    fun update(context: Context, original: AgentExperience, newTitle: String, newContent: String): Boolean = synchronized(lock) {
        val items = load(context)
        val index = items.indexOfFirst { it == original }
        if (index < 0) return false
        items[index] = original.copy(title = newTitle, content = newContent)
        save(context, items)
        return true
    }

    fun clear(context: Context): Unit = synchronized(lock) {
        save(context, emptyList())
    }
}
