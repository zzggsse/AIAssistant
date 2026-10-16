package com.aibutler.app

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

class LlmException(msg: String) : Exception(msg)

/**
 * 任意 OpenAI 兼容接口客户端（/chat/completions，非流式，支持 function calling）。
 */
object LlmClient {

    data class ToolCall(val id: String, val name: String, val arguments: String)

    data class Result(val content: String, val toolCalls: List<ToolCall>, val rawMessage: JsonObject)

    fun chat(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: JsonArray,
        tools: JsonArray,
        fastMode: Boolean = false
    ): Result {
        val body = JsonObject().apply {
            addProperty("model", model)
            add("messages", messages)
            add("tools", tools)
            addProperty("temperature", 0.3)
            if (fastMode) add("thinking", JsonObject().apply { addProperty("type", "disabled") })
        }

        val url = baseUrl.trimEnd('/') + "/chat/completions"
        var conn: HttpURLConnection? = null
        try {
            var attempt = 0
            var withThinking = fastMode
            while (true) {
                attempt++
                conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 15000
                conn.readTimeout = 45000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
                val code: Int
                val text: String
                try {
                    conn.outputStream.use {
                        it.write(body.toString().toByteArray(StandardCharsets.UTF_8))
                    }
                    code = conn.responseCode
                    text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                        ?.let { InputStreamReader(it, StandardCharsets.UTF_8).readText() } ?: ""
                } catch (e: java.net.SocketTimeoutException) {
                    android.util.Log.w("AIButler", "LLM 请求超时，第${attempt}次，重试中…")
                    conn.disconnect()
                    conn = null
                    if (attempt < 2) {
                        Thread.sleep(1200L)
                        continue
                    }
                    throw LlmException("请求超时（已重试${attempt - 1}次），请检查网络或稍后重试")
                }
                conn.disconnect()
                conn = null

                // 400 且疑似 thinking 参数不被支持（中英文提示都兼容）→ 去掉后重试
                if (code == 400 && withThinking) {
                    val lowered = text.lowercase()
                    if (lowered.contains("thinking") || text.contains("思考") || lowered.contains("unknown field") || text.contains("未知参数")) {
                        withThinking = false
                        body.remove("thinking")
                        continue
                    }
                }
                // 1211/1214 = 网关渠道临时不可用，等几秒重试
                if (code == 400 && text.contains("121") && attempt < 4) {
                    Thread.sleep(1500L * attempt)
                    continue
                }
                // 限流/服务端临时错误 → 短暂等待后重试
                if ((code == 429 || code in 500..599) && attempt < 3) {
                    Thread.sleep(1200L * attempt)
                    continue
                }
                if (code !in 200..299) {
                    // 打印消息结构，定位 1214 类错误
                    val struct = StringBuilder()
                    for (m in messages) {
                        val o = m.asJsonObject
                        val role = o.get("role")?.asString ?: "?"
                        val c = o.get("content")
                        val clen = when { c == null || c.isJsonNull -> -1; c.isJsonPrimitive -> c.asString.length; else -> -2 }
                        val calls = o.getAsJsonArray("tool_calls")
                        struct.append(role)
                        if (calls != null) struct.append("(calls=").append(calls.size()).append(")")
                        struct.append(":").append(clen).append(" ")
                    }
                    android.util.Log.i("AIButler", "HTTP $code 失败时的消息结构: $struct")
                    android.util.Log.i("AIButler", "响应体: ${text.take(300)}")
                    throw LlmException("HTTP $code：${text.take(500)}")
                }

                val root = JsonParser.parseString(text).asJsonObject
                val choice = root.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject
                    ?: throw LlmException("接口返回里没有 choices：${text.take(200)}")
                val msg = choice.getAsJsonObject("message")
                val content = msg?.get("content")?.takeIf { !it.isJsonNull }?.asString ?: ""

                val calls = ArrayList<ToolCall>()
                msg?.getAsJsonArray("tool_calls")?.forEach { t ->
                    val o = t.asJsonObject
                    val fn = o.getAsJsonObject("function")
                    val id = o.get("id")?.takeIf { !it.isJsonNull }?.asString ?: ("call_${calls.size}_${System.currentTimeMillis()}")
                    val name = fn?.get("name")?.takeIf { !it.isJsonNull }?.asString ?: ""
                    val args = fn?.get("arguments")?.takeIf { !it.isJsonNull }?.asString ?: "{}"
                    if (name.isNotBlank()) calls.add(ToolCall(id, name, args))
                }
                return Result(content, calls, msg ?: JsonObject().apply { addProperty("role", "assistant") })
            }
        } finally {
            conn?.disconnect()
        }
    }

    data class AutoGlmReply(val content: String, val reasoning: String = "")

    /** 拉取厂商的可用模型列表（OpenAI 兼容 GET /models）；失败抛 LlmException */
    fun listModels(baseUrl: String, apiKey: String): List<String> {
        val url = baseUrl.trimEnd('/') + "/models"
        var conn: HttpURLConnection? = null
        try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 8000
            conn.readTimeout = 10000
            if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.let { InputStreamReader(it, StandardCharsets.UTF_8).readText() } ?: ""
            if (code !in 200..299) throw LlmException("HTTP $code：${text.take(200)}")
            val root = JsonParser.parseString(text).asJsonObject
            val data = root.getAsJsonArray("data")
                ?: throw LlmException("响应里没有 models 数据")
            val ids = ArrayList<String>()
            for (item in data) {
                val o = item.asJsonObject
                o.get("id")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }?.let { ids.add(it) }
            }
            return ids.distinct().sorted()
        } catch (e: LlmException) {
            throw e
        } catch (e: java.net.SocketTimeoutException) {
            throw LlmException("接口响应超时（网络慢，或该厂商不支持模型列表接口）")
        } catch (e: Exception) {
            throw LlmException("无法连接模型列表接口：${e.message ?: "网络错误"}")
        } finally {
            conn?.disconnect()
        }
    }

    /** AutoGLM-Phone 专用手机脑：官方协议（截图+屏幕信息→think+动作），走通用端点 */
    fun chatAutoGLM(apiKey: String, messages: JsonArray): AutoGlmReply {
        val body = JsonObject().apply {
            addProperty("model", "autoglm-phone")
            add("messages", messages)
            // 192 太小：思考 + 动作会被截断，模型为了保住动作只能丢掉思考。
            // 这里留够空间，思考过程才有内容可看。
            addProperty("max_tokens", 1024)
            addProperty("temperature", 0.0)
            addProperty("top_p", 0.85)
            addProperty("frequency_penalty", 0.2)
        }
        val url = "https://open.bigmodel.cn/api/paas/v4/chat/completions"
        var attempt = 0
        while (true) {
            attempt++
            var conn: HttpURLConnection? = null
            try {
                conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 15000
                conn.readTimeout = 90000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
                conn.outputStream.use {
                    it.write(body.toString().toByteArray(StandardCharsets.UTF_8))
                }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.let { InputStreamReader(it, StandardCharsets.UTF_8).readText() } ?: ""
                if ((code == 429 || code in 500..599) && attempt < 3) {
                    Thread.sleep(2000L * attempt)
                    continue
                }
                if (code !in 200..299) {
                    android.util.Log.e("AIButler", "AutoGLM HTTP $code：${text.take(1000)}")
                    val hint = if (code == 401 || code == 403)
                        "（Key 无效：手机脑走智谱 open.bigmodel.cn 接口，请到设置选厂商「智谱 GLM」并填智谱的 API Key）"
                    else if (code == 429) "（限流，稍后会自动重试）"
                    else ""
                    throw LlmException("AutoGLM HTTP $code：${text.take(300)}$hint")
                }
                val root = JsonParser.parseString(text).asJsonObject
                val message = root.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject
                    ?.getAsJsonObject("message")
                val content = message?.get("content")?.takeIf { !it.isJsonNull }?.asString ?: ""
                // 有的模型把推理放在独立字段里，而不是 <think> 标签里
                val reasoning = listOf("reasoning_content", "reasoning").firstNotNullOfOrNull { k ->
                    message?.get(k)?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }
                } ?: ""
                return AutoGlmReply(content, reasoning)
            } catch (e: java.net.SocketTimeoutException) {
                // 视觉大模型偶发响应慢：先自动重试一次，再失败才报给人看
                if (attempt < 2) {
                    android.util.Log.w("AIButler", "AutoGLM 请求超时，第${attempt}次，重试中…")
                    Thread.sleep(1500L)
                    continue
                }
                throw LlmException("请求超时（已重试1次）：网络较慢或智谱服务繁忙，请稍后再试")
            } finally {
                conn?.disconnect()
            }
        }
    }

    /** 视觉识别：发一张截图给多模态模型，返回分析文字 */
    fun chatVision(baseUrl: String, apiKey: String, model: String, prompt: String, imageBase64: String): String {
        fun buildBody(withThinking: Boolean): JsonObject = JsonObject().apply {
            addProperty("model", model)
            add("messages", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "user")
                    add("content", JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("type", "image_url")
                            add("image_url", JsonObject().apply {
                                addProperty("url", "data:image/jpeg;base64,$imageBase64")
                            })
                        })
                        add(JsonObject().apply {
                            addProperty("type", "text")
                            addProperty("text", prompt)
                        })
                    })
                })
            })
            addProperty("temperature", 0.2)
            if (withThinking) add("thinking", JsonObject().apply { addProperty("type", "disabled") })
        }
        val url = baseUrl.trimEnd('/') + "/chat/completions"
        var attempt = 0
        var withThinking = true
        while (true) {
            attempt++
            var conn: HttpURLConnection? = null
            try {
                conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 15000
            conn.readTimeout = 60000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
                conn.outputStream.use {
                    it.write(buildBody(withThinking).toString().toByteArray(StandardCharsets.UTF_8))
                }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.let { InputStreamReader(it, StandardCharsets.UTF_8).readText() } ?: ""
                if (code == 400 && withThinking) {
                    withThinking = false
                    continue
                }
                if ((code == 429 || code in 500..599) && attempt < 3) {
                    Thread.sleep(2000L * attempt)
                    continue
                }
                if (code !in 200..299) throw LlmException("HTTP $code：${text.take(400)}")
                val root = JsonParser.parseString(text).asJsonObject
                val choice = root.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject
                    ?: throw LlmException("视觉响应里没有 choices")
                return choice.getAsJsonObject("message")?.get("content")
                    ?.takeIf { !it.isJsonNull }?.asString ?: ""
            } finally {
                conn?.disconnect()
            }
        }
    }
}
