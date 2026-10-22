package com.aibutler.app

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.Locale

data class LearningStep(
    val packageName: String,
    val sourceFingerprint: String,
    val actionType: String,
    val actionLabel: String = "",
    val resourceId: String = "",
    val className: String = "",
    val actionX: Int = -1,
    val actionY: Int = -1,
    val targetFingerprint: String = ""
)

data class LearningTask(
    val id: String,
    val name: String,
    val taskText: String,
    val targetAppName: String,
    val targetPackage: String,
    val steps: List<LearningStep>,
    val createdAt: Long,
    val lastUsedAt: Long = 0,
    val successCount: Int = 0,
    val highPriority: Boolean = true
)

data class LearningReplayResult(
    val success: Boolean,
    val stepsExecuted: Int,
    val message: String,
    val cancelled: Boolean = false
)

object LearningTaskStore {
    private const val TAG = "LearningTask"
    private val gson = Gson()
    private val type = object : TypeToken<MutableList<LearningTask>>() {}.type

    private fun file(context: Context) = java.io.File(context.filesDir, "learning_tasks.json")

    fun all(context: Context): List<LearningTask> = synchronized(this) {
        load(context).sortedByDescending { if (it.lastUsedAt == 0L) it.createdAt else it.lastUsedAt }
    }

    fun save(context: Context, task: LearningTask) {
        synchronized(this) {
            val items = load(context).filter { it.id != task.id }.toMutableList()
            items.add(task)
            while (items.size > 30) items.removeAt(0)
            AtomicFiles.write(file(context), gson.toJson(items))
        }
    }

    fun markUsed(context: Context, taskId: String) {
        synchronized(this) {
            val items = load(context).toMutableList()
            val index = items.indexOfFirst { it.id == taskId }
            if (index >= 0) {
                items[index] = items[index].copy(
                    lastUsedAt = System.currentTimeMillis(),
                    successCount = items[index].successCount + 1
                )
                AtomicFiles.write(file(context), gson.toJson(items))
            }
        }
    }

    fun delete(context: Context, taskId: String) {
        synchronized(this) {
            val items = load(context).filter { it.id != taskId }
            AtomicFiles.write(file(context), gson.toJson(items))
        }
    }

    fun findMatch(context: Context, request: String): LearningTask? {
        val normalized = normalize(request)
        if (normalized.isBlank()) return null
        return all(context)
            .map { task ->
                val taskName = normalize(task.name)
                val taskText = normalize(task.taskText)
                var score = 0
                val kwHits = task.keywords().count { it.length >= 2 && normalized.contains(it) }
                if (taskName == normalized || taskText == normalized) score += 12
                if (taskName.length >= 3 && normalized.contains(taskName)) score += 7
                if (taskText.length >= 3 && normalized.contains(taskText)) score += 6
                score += kwHits * 2
                if (task.targetAppName.isNotBlank() && normalized.contains(normalize(task.targetAppName))) score += 2
                // load() 把所有任务都标成 highPriority，所以这里必须先有真实匹配分量才加优先分，
                // 否则零相关的聊天输入也会靠 +8 过线，弹出回放确认打断用户
                if (task.highPriority && score > 0) {
                    score += 8
                    score += minOf(task.successCount, 5)
                }
                task to score
            }
            .filter { it.second >= 4 }
            .maxByOrNull { it.second }
            ?.first
    }

    fun replay(
        activity: android.app.Activity,
        task: LearningTask,
        cancelCheck: () -> Boolean
    ): LearningReplayResult {
        val service = AssistantAccessibilityService.instance
            ?: return LearningReplayResult(false, 0, "无障碍服务未连接")
        if (task.steps.isEmpty()) return LearningReplayResult(false, 0, "这个任务还没有记录到步骤")

        if (task.targetPackage.isNotBlank() && service.currentPackage() != task.targetPackage) {
            openLearningTarget(activity, task)
            // 起步前必须确认到了目标应用：不然在错误页面上按文字自适应，
            // 同名按钮（如"搜索"）会被乱点（体检 P2-2）
            var waited = 0
            while (service.currentPackage() != task.targetPackage && waited < 3000) {
                Thread.sleep(200)
                waited += 200
            }
            if (service.currentPackage() != task.targetPackage) {
                return LearningReplayResult(false, 0, "没能打开目标应用，已停止回放；请确认应用已安装后重试")
            }
        }

        task.steps.forEachIndexed { index, step ->
            if (cancelCheck()) {
                return LearningReplayResult(false, index, "已停止", cancelled = true)
            }
            val before = service.currentPageFingerprint()
            if (step.packageName.isNotBlank() && before.second != step.packageName) {
                Thread.sleep(450)
            }

            when (step.actionType) {
                "tap" -> {
                    if ((step.actionX < 0 || step.actionY < 0) && step.actionLabel.isNotBlank()) {
                        val adapted = service.clickText(step.actionLabel)
                        if (!adapted.startsWith("已点击")) {
                            return LearningReplayResult(
                                false,
                                index,
                                "第${index + 1}步没有找到「${step.actionLabel}」"
                            )
                        }
                        AgentEngine.waitForActionSettle("Tap", before.first)
                        return@forEachIndexed
                    }
                    if (before.first != step.sourceFingerprint) {
                        val adapted = if (step.actionLabel.isNotBlank()) {
                            service.clickText(step.actionLabel)
                        } else {
                            ""
                        }
                        if (!adapted.startsWith("已点击")) {
                            return LearningReplayResult(
                                false,
                                index,
                                "第${index + 1}步页面变化了，没有找到「${step.actionLabel.ifBlank { "原目标" }}」"
                            )
                        }
                        AgentEngine.waitForActionSettle("Tap", before.first)
                        return@forEachIndexed
                    }
                    val size = service.realScreenSize()
                    val x = (step.actionX.coerceIn(0, 999) / 999.0 * size.first).toFloat()
                    val y = (step.actionY.coerceIn(0, 999) / 999.0 * size.second).toFloat()
                    if (!service.tap(x, y)) {
                        return LearningReplayResult(false, index, "第${index + 1}步点击失败")
                    }
                    AgentEngine.waitForActionSettle("Tap", before.first)
                    val after = service.currentPageFingerprint()
                    if (step.targetFingerprint.isNotBlank() &&
                        after.first != step.targetFingerprint &&
                        after.first == before.first
                    ) {
                        return LearningReplayResult(
                            false,
                            index,
                            "第${index + 1}步点击后页面没有按预期变化"
                        )
                    }
                }
                "input_text" -> {
                    val text = step.actionLabel
                    if (text.isBlank() || service.inputText(text).startsWith("当前屏幕没有输入框")) {
                        return LearningReplayResult(false, index, "第${index + 1}步没有找到输入框")
                    }
                    AgentEngine.waitForActionSettle("Type", before.first)
                }
                "back" -> {
                    if (!service.pressBack()) return LearningReplayResult(false, index, "第${index + 1}步返回失败")
                    AgentEngine.waitForActionSettle("Back", before.first)
                }
                "swipe_down" -> {
                    if (!service.scroll(true)) return LearningReplayResult(false, index, "第${index + 1}步滑动失败")
                    // 与 tap/back 一致地等页面稳定（体检 P2-12）
                    AgentEngine.waitForActionSettle("Swipe", before.first)
                }
                "swipe_up" -> {
                    if (!service.scroll(false)) return LearningReplayResult(false, index, "第${index + 1}步滑动失败")
                    AgentEngine.waitForActionSettle("Swipe", before.first)
                }
                else -> return LearningReplayResult(false, index, "第${index + 1}步动作不支持")
            }
        }

        return LearningReplayResult(true, task.steps.size, "任务回放完成")
    }

    private fun openLearningTarget(activity: android.app.Activity, task: LearningTask) {
        val launch = activity.packageManager.getLaunchIntentForPackage(task.targetPackage)
        if (launch != null) {
            launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            activity.startActivity(launch)
            Thread.sleep(900L)

            // 包名直达失败时通常还停留在桌面；再给一次机会，避免标签模糊匹配引入误启动。
            val deadline = System.currentTimeMillis() + 1800L
            while (System.currentTimeMillis() < deadline) {
                if (AssistantAccessibilityService.instance?.currentPackage() == task.targetPackage) return
                Thread.sleep(150L)
            }
        }

        if (task.targetAppName.isNotBlank()) {
            DeviceTools.openApp(activity, task.targetAppName, waitMs = 900L)
        }
    }

    fun appLabel(context: Context, packageName: String): String {
        return try {
            context.packageManager.getApplicationLabel(
                context.packageManager.getApplicationInfo(packageName, 0)
            ).toString()
        } catch (e: Exception) {
            packageName.substringAfterLast('.').ifBlank { "未知应用" }
        }
    }

    fun describeStep(step: LearningStep): String {
        return when (step.actionType) {
            "tap" -> "点击 ${step.actionLabel.ifBlank { step.resourceId.ifBlank { "屏幕目标" } }}"
            "input_text" -> "输入「${step.actionLabel.take(30)}」"
            "back" -> "按返回"
            "swipe_down" -> "向下滑动"
            "swipe_up" -> "向上滑动"
            else -> step.actionType
        }
    }

    private fun load(context: Context): MutableList<LearningTask> {
        val target = file(context)
        if (!target.exists()) return mutableListOf()
        return try {
            (gson.fromJson<MutableList<LearningTask>>(target.readText(), type) ?: mutableListOf())
                .map { it.copy(highPriority = true) }
                .toMutableList()
        } catch (e: Exception) {
            Log.w(TAG, "读取录制任务失败", e)
            mutableListOf()
        }
    }

    private fun normalize(value: String): String {
        return value.lowercase(Locale.CHINA).replace(Regex("\\s+"), "")
    }

    private fun LearningTask.keywords(): List<String> {
        val source = listOf(name, taskText).joinToString(" ")
        return source.split(Regex("[\\s,.，。：:；;、?？!！]+"))
            .map { it.trim().lowercase(Locale.CHINA) }
            .filter { it.isNotBlank() }
            .distinct()
    }
}
