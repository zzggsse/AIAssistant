package com.aibutler.app

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** 一条已验证或失败过的退出路线；actions 里只保存可读按钮文字，Back 表示系统返回键 */
data class ExitRoute(
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val actions: List<String> = emptyList(),
    val updatedAt: Long = 0
)

data class ExitCandidate(
    val label: String,
    val normalized: String,
    val score: Int
)

data class ExitRecoveryResult(
    val recovered: Boolean,
    val actions: List<String>,
    val detail: String
)

/**
 * 退出恢复记忆：误点奖励入口被拉出目标应用后，记住哪条按钮路线能把手机带回目标应用。
 *
 * key 是「目标应用 → 外跳到的应用 → 误点入口」，不含具体业务 App 的包名；
 * actions 不保存坐标，只保存可重试的按钮文字或 Back，因此页面小改版时仍能按语义重新定位。
 */
object ExitMemory {
    private const val TAG = "ExitMemory"
    private val gson = Gson()
    private val type = object : TypeToken<MutableMap<String, ExitRoute>>() {}.type

    private fun file(context: Context) = java.io.File(context.filesDir, "exit_memory.json")

    private fun load(context: Context): MutableMap<String, ExitRoute> {
        val target = file(context)
        if (!target.exists()) return mutableMapOf()
        return try {
            gson.fromJson<MutableMap<String, ExitRoute>>(target.readText(), type) ?: mutableMapOf()
        } catch (e: Exception) {
            Log.w(TAG, "加载退出恢复记忆失败", e)
            mutableMapOf()
        }
    }

    private fun save(context: Context, items: Map<String, ExitRoute>) {
        try {
            AtomicFiles.write(file(context), gson.toJson(items))
        } catch (e: Exception) {
            Log.w(TAG, "保存退出恢复记忆失败", e)
        }
    }

    fun storageKey(targetPackage: String, wrongPackage: String, trapLabel: String): String =
        listOf(targetPackage, wrongPackage, normalizeButton(trapLabel)).joinToString("|")

    /** 入口倒计时和奖励数字会变，剥离数字后才能把同一次误点归并成同一条经验 */
    fun normalizeButton(label: String): String =
        label
            .replace(Regex("\\s*\\{.*$"), "")
            .replace(Regex("\\[[^]]*]$"), "")
            .replace(Regex("\\s+"), "")
            .replace(Regex("[0-9０-９]+"), "")
            .take(32)

    /** 从通用读屏清单提取可点击按钮；只认语义明确的退出控件，避免点广告主的引流按钮 */
    fun visibleButtons(screenDump: String): List<ExitCandidate> {
        val out = LinkedHashMap<String, ExitCandidate>()
        val lineRegex = Regex("(?:^|\\n)可点击:\\s*(.+?)\\s*\\[(-?\\d+),(-?\\d+)\\]")
        for (match in lineRegex.findAll(screenDump)) {
            val raw = match.groupValues[1]
                .replace(Regex("\\s*\\{.*$"), "")
                .trim()
            if (raw.isBlank()) continue
            val norm = normalizeButton(raw)
            if (norm.isBlank()) continue
            val score = scoreButton(raw)
            if (score <= 0) continue
            val old = out[norm]
            if (old == null || score > old.score) out[norm] = ExitCandidate(raw, norm, score)
        }
        return out.values.sortedByDescending { it.score }
    }

    /** 通用语义打分：正分才允许系统代点；负分是广告转化或继续观看类控件，永远不当作退出 */
    fun scoreButton(label: String): Int {
        val text = label.replace(Regex("\\s+"), "")
        if (Regex(
                "继续观看|继续浏览|继续玩|再看|去下载|下载|安装|试玩|了解详情|查看详情|立即抢购|立即领取|去体验|打开|更多|分享|邀请|注册|登录",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(text)
        ) return -1
        return when {
            Regex("放弃奖励|放弃|不再观看|不感兴趣|残忍离开|残忍退出|坚持退出|退出|返回").containsMatchIn(text) -> 500
            Regex("以后再说|暂不|下次再说|跳过|关闭|取消|知道了|我知道了").containsMatchIn(text) -> 400
            else -> 0
        }
    }

    /** 优先回放本机验证过的路线；验证路线失效后再按当前屏幕重新排序 */
    fun orderedCandidates(
        screenDump: String,
        remembered: List<String> = emptyList()
    ): List<ExitCandidate> {
        val rememberedCandidates = remembered
            .filter { it.isNotBlank() }
            .map { ExitCandidate(it, normalizeButton(it), 1000) }
            .sortedBy { remembered.indexOf(it.label) }
        return rememberedCandidates + visibleButtons(screenDump).filter { candidate ->
            rememberedCandidates.none { it.normalized == candidate.normalized }
        }
    }

    fun routeFor(context: Context, targetPackage: String, wrongPackage: String, trapLabel: String): List<String> {
        val route = load(context)[storageKey(targetPackage, wrongPackage, trapLabel)] ?: return emptyList()
        return if (route.successCount > route.failureCount) route.actions else emptyList()
    }

    fun markSuccess(
        context: Context,
        targetPackage: String,
        wrongPackage: String,
        trapLabel: String,
        actions: List<String>
    ) {
        val validActions = actions.filter { it == "Back" || it.isNotBlank() }.take(5)
        if (targetPackage.isBlank() || wrongPackage.isBlank() || validActions.isEmpty()) return
        val items = load(context)
        val key = storageKey(targetPackage, wrongPackage, trapLabel)
        val old = items[key] ?: ExitRoute()
        items[key] = ExitRoute(
            successCount = old.successCount + 1,
            failureCount = old.failureCount,
            actions = validActions,
            updatedAt = System.currentTimeMillis()
        )
        while (items.size > 120) {
            val oldest = items.minByOrNull { it.value.updatedAt }?.key ?: break
            items.remove(oldest)
        }
        save(context, items)
        Log.i(TAG, "已记住有效退出路线：$key → ${validActions.joinToString(" → ")}")
    }

    fun markFailure(context: Context, targetPackage: String, wrongPackage: String, trapLabel: String) {
        val items = load(context)
        val key = storageKey(targetPackage, wrongPackage, trapLabel)
        val old = items[key] ?: return
        items[key] = old.copy(failureCount = old.failureCount + 1, updatedAt = System.currentTimeMillis())
        save(context, items)
    }

    fun clear(context: Context) = save(context, emptyMap())
}
