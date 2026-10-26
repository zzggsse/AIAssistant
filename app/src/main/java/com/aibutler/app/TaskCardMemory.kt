package com.aibutler.app

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** 一张任务卡的累计战绩 */
data class CardStat(
    val good: Int = 0,
    val bad: Int = 0,
    val lastOutcome: String = "",   // "real"=领到过奖励  "wall"=广告墙/外跳坑
    val updatedAt: Long = 0
)

/**
 * 任务卡记忆（第三层记忆：PathMemory=整条路径回放，ExperienceStore=任务级摘要，本层=任务卡粒度）。
 *
 * 记住每个App任务中心里，哪张任务卡是真领到过奖励的、哪张点了全是广告墙/外跳坑。
 * 记账是事件驱动的：
 *  - 点击任务卡后 6 轮内出现「已成功领取/已到账」类标记 → good
 *  - 点击后 6 轮内页面冻结在假倒计时 → bad
 *  - 点击后 6 轮内被拉出目标App（第三方跳转）→ bad
 * 下次进入同类任务页时，把「优先点哪张、别碰哪张」注入给模型。
 */
object TaskCardMemory {
    private const val TAG = "TaskCardMemory"
    private val gson = Gson()
    private val type = object : TypeToken<MutableMap<String, CardStat>>() {}.type
    // Agent 工作线程记账与 UI 线程读写并发：load→改→save 必须整段互斥，否则后写者覆盖前者丢数据
    private val lock = Any()

    private fun file(context: Context) = java.io.File(context.filesDir, "task_card_memory.json")

    private fun load(context: Context): MutableMap<String, CardStat> {
        val target = file(context)
        if (!target.exists()) return mutableMapOf()
        return try {
            gson.fromJson<MutableMap<String, CardStat>>(target.readText(), type) ?: mutableMapOf()
        } catch (e: Exception) {
            Log.w(TAG, "加载任务卡记忆失败", e)
            mutableMapOf()
        }
    }

    private fun save(context: Context, items: Map<String, CardStat>) {
        try {
            AtomicFiles.write(file(context), gson.toJson(items))
        } catch (e: Exception) {
            Log.w(TAG, "保存任务卡记忆失败", e)
        }
    }

    /** 无障碍树里的无意义占位标签，永不记账/不建议 */
    private val junkLabel = Regex("无文字|action_bar|item_|neo_|_view|_btn|_layout|^view|^btn", RegexOption.IGNORE_CASE)
    private val cjkChar = Regex("[\\u4e00-\\u9fa5]")

    /**
     * 标签是否可靠：≥3字（「去观看」「去签到」这种3字真任务卡不能误杀）+ 含中文（排除英文控件名碎片）+ 非占位符
     */
    fun isReliableLabel(norm: String): Boolean =
        norm.length >= 3 && !junkLabel.containsMatchIn(norm) && cjkChar.containsMatchIn(norm)

    /**
     * 标签规范化：去空白、去数字、截断。
     * 去数字是关键——「19s后领取观看奖励」「5s后领取观看奖励」是同一张卡的倒计时变体，
     * 不合并的话每条都凑不够2次证据，永远学不会。副作用是「领5金币/领10金币」会合并，可接受。
     */
    fun normalizeLabel(label: String): String =
        label.replace(Regex("\\s+"), "").replace(Regex("[0-9０-９]+"), "").take(24)

    // ========== 记账 ==========

    fun mark(context: Context, pkg: String, label: String, good: Boolean, outcome: String = if (good) "real" else "wall"): Unit = synchronized(lock) {
        val norm = normalizeLabel(label)
        if (pkg.isBlank() || !isReliableLabel(norm)) return
        val items = load(context)
        val key = "$pkg|$norm"
        val old = items[key] ?: CardStat()
        items[key] = CardStat(
            good = old.good + if (good) 1 else 0,
            bad = old.bad + if (good) 0 else 1,
            lastOutcome = outcome,
            updatedAt = System.currentTimeMillis()
        )
        // 上限 120 条，超出删最旧
        while (items.size > 120) {
            val oldest = items.minByOrNull { it.value.updatedAt }?.key ?: break
            items.remove(oldest)
        }
        save(context, items)
        Log.i(TAG, "记账 ${if (good) "真奖励" else "广告墙"}：$norm（good=${items[key]!!.good} bad=${items[key]!!.bad}）")
    }

    // ========== 判定与建议（纯逻辑，可单测） ==========

    /** 单张卡的判定：null=证据不足不下结论；good=优先点；wall=别碰；外跳一次即判 wall */
    fun verdictOf(stat: CardStat): String? = when {
        stat.bad >= 2 && stat.good == 0 -> "wall"
        stat.lastOutcome == "jump" && stat.good == 0 -> "wall"
        stat.good >= 1 && stat.good > stat.bad -> "good"
        else -> null
    }

    /** 从屏幕关键元素里提取可点击卡片标签（规范化后，剔除占位标签） */
    fun visibleLabels(screenDump: String): Set<String> =
        Regex("可点击:\\s*([^\\[\\n]{2,40}?)\\s*\\[\\d+,\\d+\\]").findAll(screenDump)
            .map { normalizeLabel(it.groupValues[1]) }
            .filter { isReliableLabel(it) }
            .toSet()

    /** 在 labels 里找与记忆 key 匹配的可见标签（双向包含，容忍「去观看」vs「去观看得金币」） */
    fun matchLabel(visible: Set<String>, remembered: String): String? =
        visible.firstOrNull { it.contains(remembered) || remembered.contains(it) }

    /**
     * 核心建议生成：根据记忆库+当前页面可见卡，输出给模型的建议文本（空串=无可说）。
     * 每张卡只说一次；good 优先列出。
     */
    fun adviseFrom(stats: Map<String, CardStat>, pkg: String, screenDump: String): String {
        if (stats.isEmpty() || pkg.isBlank()) return ""
        val visible = visibleLabels(screenDump)
        if (visible.isEmpty()) return ""
        val goods = LinkedHashSet<String>()
        val walls = LinkedHashSet<String>()
        for ((key, stat) in stats) {
            if (!key.startsWith("$pkg|")) continue
            val remembered = key.substringAfter('|')
            val hit = matchLabel(visible, remembered) ?: continue
            when (verdictOf(stat)) {
                "good" -> goods.add(hit)
                "wall" -> walls.add(hit)
            }
        }
        if (goods.isEmpty() && walls.isEmpty()) return ""
        val sb = StringBuilder("\n【任务卡记忆（本机历史实测，供参考；按钮仍需按当前截图确认位置）】")
        if (goods.isNotEmpty()) sb.append("\n✅ 这些卡以前真领到过奖励，优先考虑：${goods.take(4).joinToString("、")}")
        if (walls.isNotEmpty()) sb.append("\n🚫 这些卡最近点过去全是广告墙或跳出App，不要再点：${walls.take(4).joinToString("、")}")
        return sb.toString()
    }

    fun advise(context: Context, pkg: String, screenDump: String): String =
        synchronized(lock) { adviseFrom(load(context), pkg, screenDump) }

    fun clear(context: Context): Unit = synchronized(lock) { save(context, emptyMap()) }
}
