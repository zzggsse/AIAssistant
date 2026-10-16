package com.aibutler.app

import java.net.URI

/**
 * 接口地址明文流量守卫：API Key 会以 Authorization 头发给接口地址，
 * 明文 http 传外网等于把 Key 在网络上裸传。只放行：
 * - 所有 https 地址
 * - 本机/局域网的 http 地址（Ollama 本地模型需要，如 http://192.168.x.x:11434）
 */
object NetGuard {

    /** 返回 null = 允许保存/请求；否则返回直接给用户看的拒绝原因 */
    fun cleartextProblem(rawUrl: String): String? {
        val url = rawUrl.trim()
        if (url.isBlank()) return "接口地址不能为空"
        val scheme = try {
            URI(url).scheme?.lowercase()
        } catch (_: Exception) {
            null
        } ?: return "接口地址要以 http(s):// 开头"
        if (scheme != "http" && scheme != "https") return "接口地址要以 http(s):// 开头"
        if (scheme == "https") return null

        val host = try {
            URI(url).host?.lowercase()?.trim()?.removeSuffix(".")
        } catch (_: Exception) {
            null
        }
        if (host.isNullOrBlank()) return "接口地址缺少主机名，无法判断是否安全"
        if (isLocalOrLanHost(host)) return null
        return "外网地址必须用 https://（明文 http 会让 API Key 在网络上裸传）。" +
            "本机/局域网地址（localhost、192.168.x.x、10.x.x.x 等）可以继续用 http://"
    }

    /** 是否本机/局域网地址：localhost、单标签主机名（如 mypc）、.local、私网 IPv4 段 */
    fun isLocalOrLanHost(host: String): Boolean {
        val h = host.lowercase().removeSuffix(".")
        if (h.isEmpty() || h == "localhost" || h == "::1" || h.endsWith(".local")) return true
        if (!h.contains('.')) return true   // 单标签主机名只在局域网能解析
        val parts = h.split(".")
        if (parts.size != 4) return false   // IPv6 等其他格式不在此判断
        val o = IntArray(4)
        for (i in 0..3) {
            o[i] = parts[i].toIntOrNull() ?: return false
            if (o[i] !in 0..255) return false
        }
        return o[0] == 127 || o[0] == 10 ||                    // 回环 / 10.0.0.0/8
            (o[0] == 192 && o[1] == 168) ||                    // 192.168.0.0/16
            (o[0] == 172 && o[1] in 16..31) ||                 // 172.16.0.0/12
            (o[0] == 169 && o[1] == 254)                       // 链路本地
    }
}
