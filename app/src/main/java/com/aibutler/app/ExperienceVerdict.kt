package com.aibutler.app

/**
 * 经验库成败裁决：机械证据优先，模型二次判断只在没有机械证据时生效。
 * true = 强制成功；false = 强制失败；null = 交给经验总结模型独立判定。
 */
object ExperienceVerdict {
    fun resolve(targetNeverReached: Boolean, verifiedByCheck: Boolean): Boolean? = when {
        targetNeverReached -> false
        verifiedByCheck -> true
        else -> null
    }
}
