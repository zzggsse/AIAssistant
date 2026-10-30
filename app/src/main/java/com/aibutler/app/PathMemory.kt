package com.aibutler.app

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.security.MessageDigest

/** 一次「在某个页面上执行了某个动作」的记录 */
data class PageStep(
    val sourceFingerprint: String,
    val actionType: String,
    val actionLabel: String,
    val actionX: Int,
    val actionY: Int,
    val targetFingerprint: String
)

/** 一条完整任务路径：从打开应用到完成目标的结构化步骤链 */
data class RecordedPath(
    val taskKeywords: List<String>,
    val targetPackage: String,
    val targetAppName: String,
    val steps: List<PageStep>,
    val successCount: Int = 0,
    val lastUsedAt: Long = 0
)

/** 重放结果 */
data class ReplayResult(
    val success: Boolean,
    val stepsExecuted: Int,
    val failedAtIndex: Int,
    val message: String
)

/**
 * 路径记忆库：记录成功任务的「页面指纹 → 动作 → 下一页指纹」结构化链路。
 * 下次遇到相同任务时，优先走缓存路径回放（不调 LLM），失败则降级到 LLM 模式。
 */
object PathMemoryStore {
    private const val TAG = "PathMemory"
    const val REPLAY_TOTAL_BUDGET_MS = 180_000L
    private val gson = Gson()
    private val type = object : TypeToken<MutableList<RecordedPath>>() {}.type

    private fun file(context: Context) = java.io.File(context.filesDir, "path_memory.json")

    private fun load(context: Context): MutableList<RecordedPath> {
        val target = file(context)
        if (!target.exists()) return mutableListOf()
        return try {
            gson.fromJson<MutableList<RecordedPath>>(target.readText(), type) ?: mutableListOf()
        } catch (e: Exception) {
            Log.w(TAG, "加载路径记忆失败", e)
            mutableListOf()
        }
    }

    private fun save(context: Context, items: List<RecordedPath>) {
        try {
            AtomicFiles.write(file(context), gson.toJson(items))
        } catch (e: Exception) {
            Log.w(TAG, "保存路径记忆失败", e)
        }
    }

    /** 保存或更新一条成功路径；如果同关键词+同包名已有路径则覆盖（保留 successCount 取较大值） */
    fun upsert(context: Context, path: RecordedPath) = synchronized(this) {
        if (path.steps.isNotEmpty()) {
            val items = load(context)
            val existingIdx = items.indexOfFirst {
                it.targetPackage == path.targetPackage &&
                    it.taskKeywords.intersect(path.taskKeywords.toSet()).isNotEmpty()
            }
            if (existingIdx >= 0) {
                val old = items[existingIdx]
                items[existingIdx] = path.copy(
                    successCount = maxOf(old.successCount, path.successCount),
                    lastUsedAt = System.currentTimeMillis()
                )
            } else {
                items.add(path.copy(lastUsedAt = System.currentTimeMillis()))
            }
            // 最多存 30 条，超出时删掉最旧的
            while (items.size > 30) items.removeAt(0)
            save(context, items)
            Log.i(TAG, "已保存路径：${path.targetAppName} ${path.steps.size}步")
        }
    }

    /** 根据任务文本匹配已记录的路径 */
    fun findMatch(context: Context, taskText: String, targetPackage: String? = null): RecordedPath? = synchronized(this) {
        val items = load(context)
        if (items.isEmpty()) return null
        val normalized = taskText.lowercase()
        var best: RecordedPath? = null
        var bestScore = 0
        for (item in items) {
            if (item.successCount < 1) continue
            val kwScore = item.taskKeywords.count { kw ->
                kw.isNotBlank() && normalized.contains(kw.lowercase())
            }
            val pkgBonus = if (targetPackage != null && item.targetPackage == targetPackage) 2 else 0
            val appBonus = if (item.targetAppName.isNotBlank() &&
                normalized.contains(item.targetAppName.lowercase())
            ) 2 else 0
            val score = kwScore + pkgBonus + appBonus
            val reliableSingleKeyword = score == 1 && item.successCount >= 2 &&
                targetPackage != null && item.targetPackage == targetPackage
            if ((score >= 2 || reliableSingleKeyword) && score > bestScore) {
                best = item
                bestScore = score
            }
        }
        if (best != null) Log.i(TAG, "命中缓存路径：${best.targetAppName} ${best.steps.size}步 (score=$bestScore)")
        best
    }

    /** 回放成功后增加成功计数 */
    fun markSuccess(context: Context, path: RecordedPath) = synchronized(this) {
        val items = load(context)
        val idx = items.indexOfFirst { it.targetPackage == path.targetPackage && it.taskKeywords == path.taskKeywords }
        if (idx >= 0) {
            items[idx] = items[idx].copy(successCount = items[idx].successCount + 1, lastUsedAt = System.currentTimeMillis())
            save(context, items)
        }
    }

    /** 清空所有路径记忆 */
    fun clear(context: Context) = synchronized(this) {
        save(context, emptyList())
    }

    /** 从无障碍节点树生成稳定的页面指纹字符串 */
    fun fingerprint(packageName: String?, sb: StringBuilder): String {
        val raw = sb.toString()
        if (raw.isBlank() && packageName.isNullOrBlank()) return ""
        return md5("${packageName ?: ""}|$raw")
    }

    private fun md5(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * 尝试按已录制的路径回放任务（不调 LLM）。
     * 每步先校验当前页面指纹是否匹配，匹配则执行动作并等页面稳定，再校验目标指纹。
     * 任何一步不匹配立即中止，返回失败。
     */
    fun tryReplay(activity: android.app.Activity, path: RecordedPath, cancelCheck: () -> Boolean): ReplayResult {
        val svc = AssistantAccessibilityService.instance
        if (svc == null) return ReplayResult(false, 0, -1, "无障碍服务未开启，无法回放")
        if (path.steps.isEmpty()) return ReplayResult(false, 0, -1, "路径为空")
        val startedAtMs = System.nanoTime() / 1_000_000

        Log.i(TAG, "开始回放路径：${path.targetAppName}，共${path.steps.size}步")

        // 打开目标应用
        if (path.targetAppName.isNotBlank()) {
            DeviceTools.openApp(activity, path.targetAppName, waitMs = 350L)
            waitForPackage(svc, path.targetPackage, 1800L)
            // 等待超时后包名仍不符就放弃：在错误页面上按文字自适应会乱点同名按钮（体检 P2-2）
            if (path.targetPackage.isNotBlank() && svc.currentPackage() != path.targetPackage) {
                Log.w(TAG, "回放放弃：未能在超时内到达目标应用（当前=${svc.currentPackage()}）")
                return ReplayResult(false, 0, -1, "没能打开目标应用（${path.targetAppName}），已放弃回放")
            }
        }

        stepLoop@ for ((idx, step) in path.steps.withIndex()) {
            if (cancelCheck()) return ReplayResult(false, idx, idx, "用户取消")
            if (System.nanoTime() / 1_000_000 - startedAtMs >= REPLAY_TOTAL_BUDGET_MS) {
                return ReplayResult(
                    false,
                    idx,
                    idx,
                    "路径回放超过${REPLAY_TOTAL_BUDGET_MS / 1000}秒总预算，已放弃缓存路径"
                )
            }
            val (curFp, curPkg) = svc.currentPageFingerprint()

            // 指纹校验：优先精确回放；按钮文字仍可见时按文字自适应一次，避免小改版导致整条记忆作废
            if (curFp != step.sourceFingerprint) {
                Log.i(TAG, "回放第${idx + 1}步指纹不匹配：当前=${curFp.take(12)}… 期望=${step.sourceFingerprint.take(12)}…")
                if (step.actionType != "tap" || step.actionLabel.isBlank()) {
                    return ReplayResult(false, idx, idx, "第${idx + 1}步页面指纹不匹配，页面可能已变化")
                }
                val adaptedResult = svc.clickText(step.actionLabel)
                Log.i(TAG, "回放第${idx + 1}步按文字自适应：${step.actionLabel} → $adaptedResult")
                if (!adaptedResult.startsWith("已点击")) {
                    return ReplayResult(false, idx, idx, "第${idx + 1}步页面指纹不匹配，且找不到「${step.actionLabel}」")
                }
                AgentEngine.waitForActionSettle(toSettleAction(step.actionType), curFp)
                if (System.nanoTime() / 1_000_000 - startedAtMs >= REPLAY_TOTAL_BUDGET_MS) {
                    return ReplayResult(
                        false,
                        idx,
                        idx,
                        "路径回放超过${REPLAY_TOTAL_BUDGET_MS / 1000}秒总预算，已放弃缓存路径"
                    )
                }
                val (adaptedFp, _) = svc.currentPageFingerprint()
                if (adaptedFp == curFp) {
                    return ReplayResult(false, idx, idx, "第${idx + 1}步自适应点击后页面没有变化")
                }
                continue@stepLoop
            }

            // 执行动作
            when (step.actionType) {
                "tap" -> {
                    // 防护：坐标无效时走文字自适应，绝不点屏幕外
                    if ((step.actionX < 0 || step.actionY < 0) && step.actionLabel.isNotBlank()) {
                        val adaptedResult = svc.clickText(step.actionLabel)
                        Log.i(TAG, "回放第${idx + 1}步坐标无效，按文字自适应：${step.actionLabel} → $adaptedResult")
                        if (!adaptedResult.startsWith("已点击")) {
                            return ReplayResult(false, idx, idx, "第${idx + 1}步没有找到「${step.actionLabel}」")
                        }
                        AgentEngine.waitForActionSettle(toSettleAction(step.actionType), curFp)
                        continue@stepLoop
                    }
                    val screenWidth = svc.realScreenSize().first.toFloat()
                    val screenHeight = svc.realScreenSize().second.toFloat()
                    val x = (step.actionX.coerceIn(0, 999) / 999.0 * screenWidth).toFloat()
                    val y = (step.actionY.coerceIn(0, 999) / 999.0 * screenHeight).toFloat()
                    if (svc.tap(x, y) != true) {
                        return ReplayResult(false, idx, idx, "第${idx + 1}步点击失败")
                    }
                }
                "back" -> {
                    if (svc.pressBack() != true) {
                        return ReplayResult(false, idx, idx, "第${idx + 1}步按返回失败")
                    }
                }
                "swipe_up" -> {
                    if (svc.scroll(true) != true) return ReplayResult(false, idx, idx, "第${idx + 1}步滑动失败")
                }
                "swipe_down" -> {
                    if (svc.scroll(false) != true) return ReplayResult(false, idx, idx, "第${idx + 1}步滑动失败")
                }
            }

            // 等页面稳定后再校验目标指纹
            AgentEngine.waitForActionSettle(toSettleAction(step.actionType), curFp)
            if (System.nanoTime() / 1_000_000 - startedAtMs >= REPLAY_TOTAL_BUDGET_MS) {
                return ReplayResult(
                    false,
                    idx,
                    idx,
                    "路径回放超过${REPLAY_TOTAL_BUDGET_MS / 1000}秒总预算，已放弃缓存路径"
                )
            }
            if (step.targetFingerprint.isNotBlank()) {
                val (newFp, _) = svc.currentPageFingerprint()
                if (newFp != step.targetFingerprint) {
                    Log.i(TAG, "回放第${idx + 1}步目标指纹不匹配：当前=${newFp.take(12)}… 期望=${step.targetFingerprint.take(12)}…")
                    return ReplayResult(false, idx + 1, idx, "第${idx + 1}步执行后页面未按预期变化")
                }
            }
        }

        Log.i(TAG, "路径回放成功！${path.steps.size}步全部通过")
        return ReplayResult(true, path.steps.size, -1, "路径回放成功")
    }

    private fun toSettleAction(actionType: String): String = when (actionType) {
        "tap" -> "Tap"
        "back" -> "Back"
        "swipe_up", "swipe_down" -> "Swipe"
        else -> actionType
    }

    private fun waitForPackage(
        svc: AssistantAccessibilityService,
        targetPackage: String,
        timeoutMs: Long
    ) {
        if (targetPackage.isBlank()) return
        val start = android.os.SystemClock.elapsedRealtime()
        while (android.os.SystemClock.elapsedRealtime() - start < timeoutMs) {
            if (svc.currentPackage() == targetPackage && svc.pageFingerprint().isNotBlank()) return
            Thread.sleep(80)
        }
    }
}
