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

    /**
     * 解析 200 响应体为 JsonObject。
     * 网关偶发返回 HTML 错误页/空页，裸 parse 会抛 JsonSyntaxException 打穿上层，
     * 这里统一转成带响应片段的 LlmException，报错信息可读。
     */
    private fun parseResponseObject(text: String): JsonObject = try {
        JsonParser.parseString(text).asJsonObject
    } catch (e: Exception) {
        throw LlmException("接口返回不是合法 JSON：${text.take(200)}")
    }

    // 国内可直连的模型服务域名；不在此列的域名连不上时提示可能需要梯子
    private val domesticHosts = listOf(
        "open.bigmodel.cn", "api.deepseek.com", "api.moonshot.cn",
        "dashscope.aliyuncs.com", "api.siliconflow.cn"
    )

    /** 连接类失败转可读提示：国外端点明确告诉用户可切国内厂商，而不是裸 IOException */
    private fun friendlyConnectError(e: Exception, url: String): LlmException {
        val host = runCatching { URL(url).host }.getOrNull().orEmpty()
        val domestic = domesticHosts.any { host == it || host.endsWith(".$it") }
        return if (domestic || host.isBlank()) {
            LlmException("连不上模型服务${if (host.isBlank()) "" else "（$host）"}，请检查网络后重试")
        } else {
            LlmException("连不上 $host：该厂商可能需要梯子。可在设置切到「智谱 GLM / DeepSeek / 通义」等国内厂商直连，同一把智谱 Key 就能跑 glm-4.6")
        }
    }

    // ========== 流式响应（SSE） ==========

    /**
     * 流式聊天：与 [chat] 参数一致，但加了 stream=true，每个 content delta 通过 [onDelta] 回调。
     * 返回值和 [chat] 一样是完整的 [Result]，内容已全部拼接好。
     * 网络错误、不支持 stream 的厂商会自动降级调 [chat]（不发 stream=true 重试一次）。
     */
    fun chatStream(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: JsonArray,
        tools: JsonArray,
        fastMode: Boolean = false,
        onDelta: ((String) -> Unit)? = null
    ): Result {
        val body = JsonObject().apply {
            addProperty("model", model)
            add("messages", messages)
            add("tools", tools)
            addProperty("temperature", 0.3)
            addProperty("stream", true)
            if (fastMode) add("thinking", JsonObject().apply { addProperty("type", "disabled") })
        }

        val url = baseUrl.trimEnd('/') + "/chat/completions"
        var conn: HttpURLConnection? = null
        try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 120000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "text/event-stream")
            if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.outputStream.use {
                it.write(body.toString().toByteArray(StandardCharsets.UTF_8))
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                val errText = conn.errorStream
                    ?.let { InputStreamReader(it, StandardCharsets.UTF_8).readText() } ?: ""
                // 某些厂商不支持 stream，降级为非流式重试一次
                if (code == 400 && (errText.contains("stream") || errText.contains("流式"))) {
                    android.util.Log.w("AIButler", "chatStream: 厂商不支持stream，降级为非流式")
                    return chat(baseUrl, apiKey, model, messages, tools, fastMode)
                }
                throw LlmException("HTTP $code：${errText.take(500)}")
            }

            val reader = java.io.BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.UTF_8))
            val contentBuilder = StringBuilder()
            data class ToolAccumulator(var id: String = "", var name: String = "", var args: StringBuilder = StringBuilder())
            val toolAccum = LinkedHashMap<Int, ToolAccumulator>()
            var finishReason = ""
            var sawDone = false

            while (true) {
                val line = reader.readLine() ?: break
                // SSE 规范里冒号后的空格是可选的：data:{...} 与 data: {...} 都合法，
                // 只认带空格的形式会把无空格厂商的所有 delta 丢掉、每次流式都降级重发（体检 P3-5）
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") { sawDone = true; break }
                try {
                    val chunk = JsonParser.parseString(data).asJsonObject
                    val choice = chunk.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject ?: continue
                    choice.get("finish_reason")?.takeIf { !it.isJsonNull }?.asString?.let { finishReason = it }
                    val delta = choice.getAsJsonObject("delta") ?: continue

                    val contentDelta = delta.get("content")?.takeIf { !it.isJsonNull }?.asString
                    // isNullOrEmpty 而不是 isNullOrBlank：纯空格/换行的 token 也是排版的一部分
                    if (!contentDelta.isNullOrEmpty()) {
                        contentBuilder.append(contentDelta)
                        onDelta?.invoke(contentDelta)
                    }

                    delta.getAsJsonArray("tool_calls")?.forEach { tc ->
                        val o = tc.asJsonObject
                        val index = o.get("index")?.asInt ?: 0
                        val acc = toolAccum.getOrPut(index) { ToolAccumulator() }
                        o.get("id")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }?.let { acc.id = it }
                        o.getAsJsonObject("function")?.let { fn ->
                            fn.get("name")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }?.let { acc.name = it }
                            fn.get("arguments")?.takeIf { !it.isJsonNull }?.asString?.let { acc.args.append(it) }
                        }
                    }
                } catch (_: Exception) {
                    // 跳过格式异常的 chunk，继续读下一行
                }
            }

            // 服务端中途断流（既无 [DONE] 也无 finish_reason）时内容往往是半截：
            // 工具参数 JSON 不完整会让下游解析失败，空响应也没有意义——按失败抛出，
            // 上层（MainActivity 的 STREAM_FALLBACK）会自动降级非流式重试
            val brokenArgs = toolAccum.values.any { acc ->
                acc.name.isNotBlank() && acc.args.isNotBlank() && runCatching {
                    JsonParser.parseString(acc.args.toString())
                }.isFailure
            }
            if (brokenArgs || (!sawDone && finishReason.isBlank() &&
                    contentBuilder.isBlank() && toolAccum.isEmpty())
            ) {
                throw LlmException("流式响应提前中断，内容可能不完整，自动改用非流式重试")
            }

            val toolCalls = toolAccum.values
                .filter { it.name.isNotBlank() }
                .map { ToolCall(it.id.ifBlank { "call_${System.nanoTime()}" }, it.name, it.args.toString().ifBlank { "{}" }) }

            val rawMessage = JsonObject().apply {
                addProperty("role", "assistant")
                addProperty("content", contentBuilder.toString())
                if (toolCalls.isNotEmpty()) {
                    add("tool_calls", JsonArray().apply {
                        toolCalls.forEach { tc ->
                            add(JsonObject().apply {
                                addProperty("id", tc.id)
                                addProperty("type", "function")
                                add("function", JsonObject().apply {
                                    addProperty("name", tc.name)
                                    addProperty("arguments", tc.arguments)
                                })
                            })
                        }
                    })
                }
            }

            return Result(contentBuilder.toString(), toolCalls, rawMessage)
        } catch (e: java.net.SocketTimeoutException) {
            throw LlmException("流式请求超时，请检查网络或稍后重试")
        } catch (e: java.io.IOException) {
            throw friendlyConnectError(e, url)
        } finally {
            conn?.disconnect()
        }
    }

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
                // 只精确匹配这两个码：contains("121") 太宽，普通 400 文案带个 121 数字就会白白重试 4 轮
                if (code == 400 && (text.contains("1211") || text.contains("1214")) && attempt < 4) {
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

                val root = parseResponseObject(text)
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
        } catch (e: java.io.IOException) {
            throw friendlyConnectError(e, url)
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
            val root = parseResponseObject(text)
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
            // 判断质量来自思考过程：1024 时复杂页面会想一半被截断，
            // 2048 给足观察推理空间，动作本身只占很少 token。
            addProperty("max_tokens", 2048)
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
                val root = parseResponseObject(text)
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
            } catch (e: java.io.IOException) {
                throw friendlyConnectError(e, url)
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
                val root = parseResponseObject(text)
                val choice = root.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject
                    ?: throw LlmException("视觉响应里没有 choices")
                return choice.getAsJsonObject("message")?.get("content")
                    ?.takeIf { !it.isJsonNull }?.asString ?: ""
            } catch (e: java.net.SocketTimeoutException) {
                // 截图是大请求，视觉模型偶发响应慢：与 chat/chatAutoGLM 一致地自动重试
                if (attempt < 3) {
                    android.util.Log.w("AIButler", "视觉请求超时，第${attempt}次，重试中…")
                    Thread.sleep(1200L * attempt)
                    continue
                }
                throw LlmException("视觉请求超时（已重试${attempt - 1}次），请稍后再试")
            } catch (e: java.io.IOException) {
                throw friendlyConnectError(e, url)
            } finally {
                conn?.disconnect()
            }
        }
    }
}
