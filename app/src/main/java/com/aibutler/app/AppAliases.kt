package com.aibutler.app

import java.util.Locale

/**
 * 常见 App 的中英/罗马字叫法。系统启动器只暴露本地化标签，模型对同一 App 的叫法不稳定；
 * 这里只做名称归一，最终仍必须命中设备上真实安装的应用标签。
 */
internal object AppAliases {
    private val labels = mapOf(
        "alipay" to "支付宝",
        "bilibili" to "哔哩哔哩",
        "bili" to "哔哩哔哩",
        "b站" to "哔哩哔哩",
        "douyin" to "抖音",
        "kuaishou" to "快手",
        "taobao" to "淘宝",
        "wechat" to "微信",
        "weixin" to "微信",
    )

    fun canonicalLabel(name: String): String? {
        val key = name.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), "")
        return labels[key]
    }

    /** 任务句里出现明确别名时，它的优先级应高于普通模糊匹配。 */
    fun findInText(text: String): String? {
        val normalized = text.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), "")
        return labels.entries
            .filter { (alias, canonical) -> normalized.contains(alias) || normalized.contains(canonical) }
            .maxByOrNull { (alias, canonical) -> maxOf(alias.length, canonical.length) }
            ?.value
    }

    /**
     * 判断模型请求、用户任务和路由结果是否指向同一个应用。
     * 别名、本地化标签和包名都必须能对上，避免把“任务里提到某个词”扩大成任意启动许可。
     */
    fun isLaunchRequestForTarget(
        requestedName: String,
        userTask: String,
        target: Pair<String, String>
    ): Boolean {
        if (requestedName.isBlank() || target.first.isBlank() || target.second.isBlank()) return false
        val requested = requestedName.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), "")
        val targetLabel = target.first.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), "")
        val requestedCanonical = canonicalLabel(requested) ?: requested
        val targetCanonical = canonicalLabel(targetLabel) ?: targetLabel

        if (requested == target.second.lowercase(Locale.ROOT)) return true
        if (requestedCanonical == targetCanonical) return true
        if (canonicalLabel(userTask) == targetCanonical) return true
        return false
    }
}
