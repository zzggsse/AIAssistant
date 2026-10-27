package com.aibutler.app

/**
 * 解析任务结果评审模型的结论。模型必须输出机器可读的 SUCCESS 字段；
 * 解析不到就返回 null，由调用方按“证据不足不写入经验”兜底。
 */
object TaskOutcomeJudge {
    fun parse(raw: String): Boolean? {
        val compact = raw.replace(Regex("\\s+"), "")
        val trueMatch = Regex("""["']?SUCCESS["']?\s*[:=]\s*["']?(true|是|成功)""", RegexOption.IGNORE_CASE)
        val falseMatch = Regex("""["']?SUCCESS["']?\s*[:=]\s*["']?(false|否|失败)""", RegexOption.IGNORE_CASE)
        return when {
            trueMatch.containsMatchIn(compact) && !falseMatch.containsMatchIn(compact) -> true
            falseMatch.containsMatchIn(compact) && !trueMatch.containsMatchIn(compact) -> false
            else -> null
        }
    }
}
