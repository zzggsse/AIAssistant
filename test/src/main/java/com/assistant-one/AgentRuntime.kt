package com.assistant.one

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException

data class AgentConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val task: String,
    val maxSteps: Int = 30
)

object AgentRuntime {
    private val service = AtomicReference<AssistantOneAccessibilityService?>(null)
    private val runningJob = AtomicReference<Job?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile var running: Boolean = false
        private set

    @Volatile var lastStatus: String = "待开始"

    private var statusListener: ((String) -> Unit)? = null

    fun onServiceConnected(value: AssistantOneAccessibilityService) = service.set(value)

    fun onServiceDisconnected(value: AssistantOneAccessibilityService) {
        service.compareAndSet(value, null)
    }

    fun observeStatus(listener: (String) -> Unit) {
        statusListener = listener
        listener(lastStatus)
    }

    fun removeStatusListener() {
        statusListener = null
    }

    fun start(config: AgentConfig) {
        if (running) return
        running = true
        val job = scope.launch { runTask(config) }
        runningJob.set(job)
    }

    suspend fun stop() {
        running = false
        runningJob.getAndSet(null)?.cancelAndJoin()
        status("已停止")
    }

    private suspend fun runTask(config: AgentConfig) {
        val accessibilityService = service.get()
        if (accessibilityService == null) {
            status("请先在系统设置里开启「助手1 · 无障碍执行」")
            running = false
            return
        }

        status("开始：${config.task}")
        val messages = JSONArray().put(
            JSONObject().put("role", "system").put("content", systemPrompt())
        ).put(
            JSONObject().put("role", "user").put(
                "content",
                screenContent(accessibilityService, config.task)
            )
        )

        var step = 1
        while (running && step <= config.maxSteps && accessibilityService == service.get()) {
            status("第 $step/${config.maxSteps} 步：正在请模型决策")
            val raw = withContext(Dispatchers.IO) {
                callOpenAiCompatible(config, messages)
            }
            if (raw.isNullOrBlank()) {
                status("模型没有返回动作，任务已停止")
                break
            }

            val action = ActionParser.parse(raw)
            if (action == null) {
                status("无法解析模型输出：$raw")
                break
            }

            messages.put(JSONObject().put("role", "assistant").put("content", raw))
            status(actionText(action))

            if (action.isFinish) {
                status("完成：${action.message ?: "任务结束"}")
                break
            }

            val execution = execute(accessibilityService, action)
            if (execution != null) {
                status("执行失败：$execution")
                break
            }

            delay(if (action.action == "Wait") (action.durationSeconds * 1000).toLong() else 650)
            messages.put(JSONObject().put("role", "user").put("content", screenContent(accessibilityService)))
            step += 1
            if (step > config.maxSteps) status("已达最大步数，任务停止")
        }
        running = false
        runningJob.set(null)
    }

    private fun execute(service: AssistantOneAccessibilityService, action: ParsedAction): String? {
        if (action.action == "Tap" && action.message != null) {
            return "涉及确认的动作已阻止，请手动处理：${action.message}"
        }
        return when (action.action) {
            "Launch" -> if (service.launchApp(action.app.orEmpty())) null else "找不到应用：${action.app}"
            "Tap" -> if (service.tap(action.elementX ?: -1, action.elementY ?: -1)) null else "点击手势失败"
            "Type", "Type_Name" -> if (service.typeText(action.text.orEmpty())) null else "找不到输入框"
            "Swipe" -> if (service.swipe(action.startX ?: -1, action.startY ?: -1, action.endX ?: -1, action.endY ?: -1)) null else "滑动手势失败"
            "Back" -> if (service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) null else "返回失败"
            "Home" -> if (service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)) null else "回桌面失败"
            "Double Tap" -> if (service.doubleTap(action.elementX ?: -1, action.elementY ?: -1)) null else "双击失败"
            "Long Press" -> if (service.longPress(action.elementX ?: -1, action.elementY ?: -1)) null else "长按失败"
            "Wait" -> null
            "Take_over" -> "模型请求人工接管：${action.message ?: "请完成后重新下发任务"}"
            else -> "暂不支持的动作：${action.action}"
        }
    }

    private suspend fun screenContent(service: AssistantOneAccessibilityService, task: String? = null): JSONArray {
        val bitmap = try {
            service.screenshot()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        val app = runCatching { service.currentApp() }.getOrDefault("unknown")
        val text = StringBuilder().append("{\"current_app\":\"").append(app).append("\"}")
        if (!task.isNullOrBlank()) text.append("\n用户任务：").append(task)
        if (bitmap == null) text.append("\n当前系统暂不能通过无障碍截图")

        val content = JSONArray()
        if (bitmap != null) {
            content.put(
                JSONObject().put(
                    "type", "image_url"
                ).put(
                    "image_url",
                    JSONObject().put("url", "data:image/jpeg;base64,${encode(bitmap)}")
                )
            )
        }
        content.put(JSONObject().put("type", "text").put("text", text.toString()))
        return content
    }

    private fun encode(bitmap: Bitmap): String {
        val longest = maxOf(bitmap.width, bitmap.height)
        val scaled = if (longest > 1280) {
            val ratio = 1280f / longest
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * ratio).toInt().coerceAtLeast(1),
                (bitmap.height * ratio).toInt().coerceAtLeast(1),
                true
            )
        } else bitmap
        val output = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 82, output)
        val encoded = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
        if (scaled !== bitmap) scaled.recycle()
        return encoded
    }

    private fun actionText(action: ParsedAction): String = when (action.action) {
        "Launch" -> "启动应用：${action.app}"
        "Tap" -> "点击：[${action.elementX}, ${action.elementY}]"
        "Type", "Type_Name" -> "输入：${action.text}"
        "Swipe" -> "滑动：[${action.startX}, ${action.startY}] → [${action.endX}, ${action.endY}]"
        "Back" -> "返回"
        "Home" -> "回到桌面"
        "Double Tap" -> "双击：[${action.elementX}, ${action.elementY}]"
        "Long Press" -> "长按：[${action.elementX}, ${action.elementY}]"
        "Wait" -> "等待 ${action.durationSeconds} 秒"
        else -> action.action
    }

    private fun status(message: String) {
        lastStatus = message
        statusListener?.invoke(message)
    }

    private fun callOpenAiCompatible(config: AgentConfig, messages: JSONArray): String? {
        val normalizedBase = config.baseUrl.trimEnd('/')
        val endpoint = if (normalizedBase.endsWith("/chat/completions")) {
            normalizedBase
        } else if (normalizedBase.endsWith("/v4")) {
            "$normalizedBase/chat/completions"
        } else {
            "$normalizedBase/chat/completions"
        }
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 180_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            val body = JSONObject()
                .put("model", config.model)
                .put("messages", messages)
                .put("temperature", 0)
                .put("max_tokens", 512)
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            if (connection.responseCode !in 200..299) {
                val error = runCatching { connection.errorStream?.bufferedReader()?.readText() }.getOrNull()
                status("接口错误 ${connection.responseCode}：${error?.take(500)}")
                return null
            }
            val response = JSONObject(connection.inputStream.bufferedReader().readText())
            response.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
        } catch (exception: Exception) {
            status("请求失败：${exception.message}")
            null
        } finally {
            connection.disconnect()
        }
    }
}

private fun systemPrompt(): String {
    return """
        你是Android手机自动化助手。根据当前截图和用户任务，只输出一个Python风格函数调用，不要输出Markdown或JSON。
        坐标范围为0到999，左上角是[0,0]，右下角是[999,999]。
        可用动作：
        do(action="Launch", app="应用名")
        do(action="Tap", element=[x,y])
        do(action="Type", text="要输入的内容")
        do(action="Swipe", start=[x,y], end=[x,y])
        do(action="Back")
        do(action="Home")
        do(action="Long Press", element=[x,y])
        do(action="Double Tap", element=[x,y])
        do(action="Wait", duration="1 seconds")
        do(action="Take_over", message="需要用户接管")
        finish(message="任务结果")
        规则：先确认当前应用是否正确；界面未变化时先等待或返回；找不到目标可滑动；涉及付款、转账、验证码、登录确认时必须请求用户接管；完成任务或无法继续时立即finish。
    """.trimIndent()
}
