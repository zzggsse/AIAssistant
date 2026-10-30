package com.aibutler.app

/**
 * 任务级外跳陷阱坐标守卫：某坐标实测会跳出目标应用后，本任务内拒绝再点同一位置。
 * 使用模型输出的 0-999 协议坐标并按 15 单位分桶；同一个按钮再次出现时位置基本不变，
 * 不依赖按钮文字，文案换了也能拦住。
 */
object JumpTrapGuard {
    const val BUCKET = 15

    fun bucketOf(x: Int, y: Int): String = "${x / BUCKET}_${y / BUCKET}"

    fun bucketFromAnswer(answer: String): String? {
        val m = Regex("""Tap[^\]]*element=\[(-?\d+)\s*,\s*(-?\d+)\]""").find(answer)
            ?: return null
        val x = m.groupValues[1].toIntOrNull() ?: return null
        val y = m.groupValues[2].toIntOrNull() ?: return null
        return bucketOf(x, y)
    }

    fun isBlocked(bucket: String?, blocked: Set<String>): Boolean =
        bucket != null && bucket in blocked
}
