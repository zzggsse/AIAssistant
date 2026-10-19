package com.aibutler.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AI 的手：所有能替用户执行的手机操作。
 */
object DeviceTools {

    const val NO_A11Y =
        "无障碍服务没有开启，无法操作屏幕。请告诉用户：打开本App的设置页 → 点「开启无障碍·自动执行」。"

    private fun commonSubstringLength(left: String, right: String): Int {
        if (left.isBlank() || right.isBlank()) return 0
        var previous = IntArray(right.length + 1)
        var current = IntArray(right.length + 1)
        var best = 0
        for (i in 1..left.length) {
            for (j in 1..right.length) {
                current[j] = if (left[i - 1] == right[j - 1]) previous[j - 1] + 1 else 0
                best = maxOf(best, current[j])
            }
            previous = current.copyOf()
            current.fill(0)
        }
        return best
    }

    /** 从任务句里按手机上真实安装的应用名做通用猜测，不依赖某个 App 的硬编码映射 */
    fun guessApp(a: Activity, text: String, contextText: String = ""): Pair<String, String>? {
        val pm = a.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val normalizedText = text.lowercase().replace("\\s+".toRegex(), "")
        data class Candidate(val label: String, val pkg: String, val score: Int)
        val candidates = pm.queryIntentActivities(intent, 0).mapNotNull { ri ->
            val label = try { ri.loadLabel(pm).toString() } catch (_: Exception) { null } ?: return@mapNotNull null
            val pkg = ri.activityInfo.packageName
            if (pkg == a.packageName || label.length < 2) return@mapNotNull null
            val normalizedLabel = label.lowercase().replace("\\s+".toRegex(), "")
            val shared = commonSubstringLength(normalizedText, normalizedLabel)
            val hasChinese = normalizedLabel.any { it.code > 0x2E7F }
            val meaningfulShared = if (hasChinese) shared >= 2 else shared >= 4
            val score = when {
                normalizedLabel == normalizedText -> 100
                normalizedText.contains(normalizedLabel) -> 80 + normalizedLabel.length
                normalizedLabel.contains(normalizedText) && normalizedText.length >= 2 -> 60 + shared * 2
                meaningfulShared -> shared * 8 - normalizedLabel.length
                else -> 0
            }
            if (score <= 0) null else Candidate(label, pkg, score)
        }.sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.label.length })
        val primary = candidates.firstOrNull() ?: return null
        if (contextText.isBlank()) return primary.label to primary.pkg

        val normalizedContext = contextText.lowercase().replace("\\s+".toRegex(), "")
        val contextScore = fun(candidate: Candidate): Int =
            when {
                normalizedContext.contains(candidate.label.lowercase().replace("\\s+".toRegex(), "")) -> 100
                normalizedContext.contains(candidate.pkg) -> 100
                else -> 0
            }

        val isAmbiguousVariant = candidates.any { other ->
            other.pkg != primary.pkg &&
                (primary.label.contains(other.label) || other.label.contains(primary.label))
        }
        if (!isAmbiguousVariant) return primary.label to primary.pkg

        val best = candidates
            .filter { candidate ->
                primary.score - candidate.score <= 8 &&
                    (primary.label.contains(candidate.label) || candidate.label.contains(primary.label))
            }
            .maxWithOrNull(
                compareBy<Candidate> { contextScore(it) }
                    .thenBy { it.score }
                    .thenBy { it.label.length }
            ) ?: primary
        return best.label to best.pkg
    }

    fun listApps(a: Activity): String {
        val pm = a.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val lines = pm.queryIntentActivities(intent, 0).mapNotNull { ri ->
            val label = try { ri.loadLabel(pm).toString() } catch (e: Exception) { null }
            if (label.isNullOrBlank()) null else "$label(${ri.activityInfo.packageName})"
        }.distinct().sorted()
        return "共${lines.size}个应用:\n" + lines.joinToString("\n")
    }

    /**
     * 冷启动打开App：CLEAR_TASK 清掉残留任务栈。
     * 背景（真机实测）：上一轮任务可能把目标App留在任意深层页面（如拍摄页），
     * 普通启动只会把旧任务带到前台，下一轮开局就面对错乱状态。
     */
    fun openAppFresh(a: Activity, packageName: String, waitMs: Long = 2500L): String {
        if (packageName.isBlank()) return "package 不能为空"
        val pm = a.packageManager
        val launch = pm.getLaunchIntentForPackage(packageName)
            ?: return "手机上没有包名为 $packageName 的应用"
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        a.startActivity(launch)
        if (waitMs > 0) Thread.sleep(waitMs)
        return "已冷启动 $packageName（已清除残留页面）"
    }

    fun openApp(a: Activity, name: String, waitMs: Long = 1500L): String {
        if (name.isBlank()) return "app_name 不能为空"
        val pm = a.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val q = name.lowercase().trim()
        data class Cand(val label: String, val pkg: String, val score: Int)
        val cands = pm.queryIntentActivities(intent, 0).mapNotNull { ri ->
            val label = try { ri.loadLabel(pm).toString() } catch (e: Exception) { null }
                ?: return@mapNotNull null
            val l = label.lowercase()
            val score = when {
                l == q -> 3
                l.startsWith(q) -> 2
                l.contains(q) || q.contains(l) -> 1
                else -> 0
            }
            if (score > 0) Cand(label, ri.activityInfo.packageName, score) else null
        }.sortedByDescending { it.score }
        val best = cands.firstOrNull() ?: return "手机上没找到名为「$name」的应用。可先调用 list_installed_apps 查看应用列表。"
        val launch = pm.getLaunchIntentForPackage(best.pkg) ?: return "无法启动「${best.label}」"
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        a.startActivity(launch)
        if (waitMs > 0) Thread.sleep(waitMs)
        return "已打开「${best.label}」，现在可以调用 get_screen_state 查看屏幕"
    }

    fun lookupContact(a: Activity, nameOrNumber: String): String {
        val raw = nameOrNumber.trim()
        if (raw.isEmpty() || raw.all { it.isDigit() || it == '+' || it == '-' || it == ' ' }) return raw
        return try {
            if (a.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                raw
            } else {
                a.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                    "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                    arrayOf("%$raw%"), null
                )?.use { c -> if (c.moveToFirst()) c.getString(0) else raw } ?: raw
            }
        } catch (e: Exception) { raw }
    }

    fun setAlarm(a: Activity, hour: Int, minute: Int, label: String): String {
        val i = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            a.startActivity(i)
            "已设置 %02d:%02d 的闹钟%s".format(hour, minute, if (label.isNotBlank()) "（$label）" else "")
        } catch (e: Exception) {
            "设置闹钟失败：${e.message}"
        }
    }

    fun sendSms(a: Activity, contact: String, message: String): String {
        val number = lookupContact(a, contact)
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(number)}")).apply {
            putExtra("sms_body", message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            a.startActivity(i)
            "已打开短信应用并填好收件人与内容，请用户检查后点击发送"
        } catch (e: Exception) {
            "发短信失败：${e.message}"
        }
    }

    fun makeCall(a: Activity, contact: String): String {
        val number = lookupContact(a, contact)
        val i = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            a.startActivity(i)
            "已打开拨号盘并填好号码，请用户点击拨出"
        } catch (e: Exception) {
            "打开拨号失败：${e.message}"
        }
    }

    fun openUrl(a: Activity, rawUrl: String): String {
        val url = if (rawUrl.startsWith("http")) rawUrl else "https://$rawUrl"
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            a.startActivity(i)
            "已打开链接：$url"
        } catch (e: Exception) {
            "打开链接失败：${e.message}"
        }
    }

    fun readNotifications(limit: Int): String {
        val list = NotificationCatcher.snapshot(limit.coerceIn(1, 30))
        if (list.isEmpty())
            return "最近没有捕获到通知。可能原因：没开「通知读取」权限，或确实没有新消息。"
        val fmt = SimpleDateFormat("HH:mm", Locale.CHINA)
        return "最近的通知（新→旧）:\n" + list.reversed().joinToString("\n") {
            "${fmt.format(Date(it.time))} [${it.app}] ${it.title}：${it.text}"
        }
    }

    // ---------- 联网 ----------

    private fun httpGet(url: String, timeout: Int = 12000): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = timeout
            conn.readTimeout = timeout
            conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36")
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
            conn.setRequestProperty("Accept-Encoding", "identity")
            if (conn.responseCode !in 200..299) throw RuntimeException("HTTP ${conn.responseCode}")
            val sb = StringBuilder()
            InputStreamReader(conn.inputStream, Charsets.UTF_8).use { r ->
                val buf = CharArray(4096)
                while (true) {
                    val n = r.read(buf)
                    if (n < 0 || sb.length > 300_000) break
                    sb.append(buf, 0, n)
                }
            }
            return sb.toString()
        } finally {
            conn.disconnect()
        }
    }

    private fun stripHtml(html: String): String {
        var s = html
        s = Regex("(?is)<(script|style).*?</\\1>").replace(s, " ")
        s = Regex("<[^>]+>").replace(s, " ")
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        s = Regex("&#(\\d+);").replace(s) { m -> m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: "" }
        s = Regex("\\s+").replace(s, " ").trim()
        return s
    }

    fun webSearch(query: String): String {
        if (query.isBlank()) return "query 不能为空"
        val q = URLEncoder.encode(query, "UTF-8")
        // 1) Bing
        try {
            val html = httpGet("https://www.bing.com/search?q=$q&setlang=zh-CN&count=10")
            val blocks = Regex("(?is)<li class=\"b_algo\".*?</li>").findAll(html).take(8).toList()
            val out = blocks.mapIndexedNotNull { i, m ->
                val b = m.value
                val title = Regex("(?is)<h2[^>]*>.*?<a[^>]*>(.*?)</a>").find(b)
                    ?.groupValues?.get(1)?.let { stripHtml(it) } ?: return@mapIndexedNotNull null
                val url = Regex("href=\"(http[^\"]+)\"").find(b)?.groupValues?.get(1) ?: ""
                val snippet = Regex("(?is)<p[^>]*>(.*?)</p>").find(b)
                    ?.groupValues?.get(1)?.let { stripHtml(it) } ?: ""
                "${i + 1}. $title\n   $snippet\n   $url"
            }
            if (out.isNotEmpty()) return "「$query」的搜索结果:\n" + out.joinToString("\n\n")
        } catch (e: Exception) { }
        // 2) DuckDuckGo 兜底
        try {
            val html = httpGet("https://html.duckduckgo.com/html/?q=$q")
            val out = Regex("(?is)class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>")
                .findAll(html).take(8).mapIndexed { i, m ->
                    val href = m.groupValues[1]
                    val real = Regex("uddg=([^&]+)").find(href)?.groupValues?.get(1)
                        ?.let { URLDecoder.decode(it, "UTF-8") } ?: href
                    "${i + 1}. ${stripHtml(m.groupValues[2])}\n   $real"
                }.toList()
            if (out.isNotEmpty()) return "「$query」的搜索结果:\n" + out.joinToString("\n\n")
        } catch (e: Exception) { }
        return "联网搜索失败，建议用 open_url 打开 https://www.bing.com/search?q=$q 给用户看"
    }

    fun readWebPage(rawUrl: String): String {
        return try {
            val url = if (rawUrl.startsWith("http")) rawUrl else "https://$rawUrl"
            "网页内容（已提取正文）:\n" + stripHtml(httpGet(url)).take(3500)
        } catch (e: Exception) {
            "读取网页失败：${e.message}"
        }
    }
}
