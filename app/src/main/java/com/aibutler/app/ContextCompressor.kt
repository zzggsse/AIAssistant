package com.aibutler.app

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * 按字符预算压缩 LLM 对话历史。
 *
 * 核心规则：
 * - 第一条用户消息（原始指令）永远完整保留
 * - 最近一条用户消息也完整保留
 * - 其余执行消息按字符预算从最老的开始裁
 * - 工具结果三层截断：最近 3 条完整 / 再往前 5 条留头尾 / 更早的留前 80 字
 */
object ContextCompressor {

    private const val SYSTEM_ROLE = "system"
    private const val USER_ROLE = "user"
    private const val TOOL_ROLE = "tool"

    /** 默认字符预算：中文约 1.5~2 字符/token，15000 字符 ≈ 8000-10000 token */
    const val DEFAULT_BUDGET = 15000

    /** 保留最近完整工具结果条数 */
    private const val TOOL_FULL_KEEP = 3

    /** 保留头尾截断工具结果条数 */
    private const val TOOL_PARTIAL_KEEP = 5

    /** 中期截断：头部长度 */
    private const val TOOL_PARTIAL_HEAD = 500

    /** 中期截断：尾部长度（结论通常在末尾） */
    private const val TOOL_PARTIAL_TAIL = 100

    /** 远期截断长度 */
    private const val TOOL_FAR_CUT = 80

    /**
     * 压缩历史到预算内。
     * 返回压缩后的 JsonArray；如果总量已在预算内则不做裁剪（只做工具结果分层截断）。
     */
    fun compress(history: JsonArray, budgetChars: Int = DEFAULT_BUDGET): JsonArray {
        val total = estimateChars(history)
        if (total <= budgetChars) return tieredToolTruncate(history)

        // 1. 收集所有用户消息的下标和长度
        val userMsgIndices = ArrayList<Int>()
        var runningTotal = 0
        for (i in 0 until history.size()) {
            val o = history.get(i).asJsonObject
            runningTotal += msgChars(o)
            if (o.get("role")?.asString == USER_ROLE) userMsgIndices.add(i)
        }

        // 2. 必须保留的用户消息：第一条 + 最后一条
        val pinnedIndices = mutableSetOf(0) // system prompt
        userMsgIndices.firstOrNull()?.let { pinnedIndices.add(it) }
        userMsgIndices.lastOrNull()?.let { pinnedIndices.add(it) }

        // 3. 划分「原子轮」：assistant(tool_calls) 与它后面的全部 tool 结果必须同进同退——
        //    如果压缩断点落在这对消息中间，发给接口就违反「tool_calls 必须紧跟对应 tool 结果」
        //    的协议约束，直接 400（长任务历史膨胀时高概率触发，曾是 1214 报错的根因之一）
        val rounds = ArrayList<List<Int>>()
        var idx = 0
        while (idx < history.size()) {
            val o = history.get(idx).asJsonObject
            val group = ArrayList<Int>()
            group.add(idx)
            if (o.get("role")?.asString == "assistant" && o.has("tool_calls")) {
                var j = idx + 1
                while (j < history.size() &&
                    history.get(j).asJsonObject.get("role")?.asString == TOOL_ROLE
                ) {
                    group.add(j)
                    j++
                }
            }
            rounds.add(group)
            idx += group.size
        }

        // 4. 从尾部往前按「轮」收集直到用掉预算的 70%（留 30% 给 pinned + tool截断余量）
        val keepFromEnd = mutableSetOf<Int>()
        var budget = (budgetChars * 0.7).toInt()
        for (r in rounds.size - 1 downTo 0) {
            val round = rounds[r]
            if (round.any { it in pinnedIndices }) continue
            val chars = round.sumOf { msgChars(history.get(it).asJsonObject) }
            if (budget - chars < 0 && keepFromEnd.isNotEmpty()) break
            keepFromEnd.addAll(round)
            budget -= chars
        }

        // 5. 构建压缩后的历史
        val result = JsonArray()
        for (i in 0 until history.size()) {
            val keep = i in pinnedIndices || i in keepFromEnd
            if (keep) result.add(history.get(i).deepCopy())
        }

        return tieredToolTruncate(result)
    }

    /** 计算一条消息的字符量（content + tool_calls arguments） */
    private fun msgChars(o: JsonObject): Int {
        var chars = 0
        o.get("content")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.let { chars += it.asString.length }
        o.getAsJsonArray("tool_calls")?.forEach { tc ->
            tc.asJsonObject.getAsJsonObject("function")?.get("arguments")
                ?.takeIf { !it.isJsonNull && it.isJsonPrimitive }
                ?.let { chars += it.asString.length }
        }
        return chars
    }

    private fun estimateChars(history: JsonArray): Int {
        var total = 0
        for (i in 0 until history.size()) total += msgChars(history.get(i).asJsonObject)
        return total
    }

    /** 工具结果三层截断：从最新往前数 */
    private fun tieredToolTruncate(history: JsonArray): JsonArray {
        var toolSeen = 0
        for (i in history.size() - 1 downTo 0) {
            val o = history.get(i).asJsonObject
            if (o.get("role")?.asString != TOOL_ROLE) continue
            toolSeen++
            val c = o.get("content") ?: continue
            if (c.isJsonNull || !c.isJsonPrimitive) continue
            val text = c.asString
            val truncated = when {
                toolSeen <= TOOL_FULL_KEEP -> text
                toolSeen <= TOOL_FULL_KEEP + TOOL_PARTIAL_KEEP ->
                    if (text.length <= TOOL_PARTIAL_HEAD + TOOL_PARTIAL_TAIL) text
                    else text.take(TOOL_PARTIAL_HEAD) + "\n…（中间已省略）…\n" + text.takeLast(TOOL_PARTIAL_TAIL)
                else ->
                    if (text.length <= TOOL_FAR_CUT) text
                    else "[旧工具结果已省略] " + text.take(TOOL_FAR_CUT)
            }
            if (truncated != text) o.addProperty("content", truncated)
        }
        return history
    }
}
