package com.aibutler.app

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
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

    /** 按应用名从真实启动器列表解析包名；启动和任务期防误跳共用这一份逻辑。 */
    fun resolveApp(a: Activity, name: String): Pair<String, String>? {
        if (name.isBlank()) return null
        val pm = a.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val q = name.lowercase().trim()
        data class Cand(val label: String, val pkg: String, val score: Int)
        return pm.queryIntentActivities(intent, 0).mapNotNull { ri ->
            val label = try { ri.loadLabel(pm).toString() } catch (_: Exception) { null }
                ?: return@mapNotNull null
            val l = label.lowercase()
            val score = when {
                l == q -> 3
                l.startsWith(q) -> 2
                l.contains(q) || q.contains(l) -> 1
                else -> 0
            }
            if (score > 0) Cand(label, ri.activityInfo.packageName, score) else null
        // 平分时短名优先：查「淘」要命中「淘宝」而不是「淘宝人生」，与 guessApp 的方向保持一致（体检 P1-4）。
        // maxWithOrNull 取比较器最大值，thenByDescending(长度) 才会让短名胜出
        }.maxWithOrNull(compareBy<Cand> { it.score }.thenByDescending { it.label.length })
            ?.let { it.label to it.pkg }
    }

    fun openApp(a: Activity, name: String, waitMs: Long = 1500L): String {
        if (name.isBlank()) return "app_name 不能为空"
        val best = resolveApp(a, name)
            ?: return "手机上没找到名为「$name」的应用。可先调用 list_installed_apps 查看应用列表。"
        val launch = a.packageManager.getLaunchIntentForPackage(best.second)
            ?: return "无法启动「${best.first}」"
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        a.startActivity(launch)
        if (waitMs > 0) Thread.sleep(waitMs)
        return "已打开「${best.first}」，现在可以调用 get_screen_state 查看屏幕"
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
        // hour/minute 来自模型输出，越界值（如 25:70）在不同 ROM 上行为不定，入口先收进合法范围
        val hour = hour.coerceIn(0, 23)
        val minute = minute.coerceIn(0, 59)
        val i = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val alarmPackage = runCatching {
            a.packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
        }.getOrNull()
        return try {
            a.startActivity(i)
            val opened = showAlarmScreen(a, alarmPackage)
            "已设置 %02d:%02d 的闹钟%s%s".format(
                hour,
                minute,
                if (label.isNotBlank()) "（$label）" else "",
                if (opened) "，并已打开时钟应用" else ""
            )
        } catch (e: Exception) {
            "设置闹钟失败：${e.message}"
        }
    }

    fun cancelAlarm(a: Activity, hour: Int?, minute: Int?, label: String): String {
        return try {
            // 系统没有直接"按时间取消"的公开 intent，只能跳转闹钟列表让用户手动删。
            // 如果给出了时间或备注，把信息一并告诉用户便于手动找到。
            val i = Intent(AlarmClock.ACTION_SHOW_ALARMS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            a.startActivity(i)
            val desc = buildString {
                if (hour != null && minute != null) append(" %02d:%02d".format(hour, minute))
                if (label.isNotBlank()) append("（$label）")
            }
            "已打开闹钟列表。系统不允许第三方 App 直接删除闹钟，请你手动找到$desc 的闹钟并删除。"
        } catch (e: Exception) {
            "取消闹钟失败：${e.message}"
        }
    }

    fun findContact(a: Activity, nameOrKeyword: String): String {
        if (a.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
            return "缺少通讯录权限，请用户在系统设置中允许"
        if (nameOrKeyword.isBlank()) return "关键词不能为空"
        return try {
            val cursor = a.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.TYPE
                ),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$nameOrKeyword%"), null
            )
            cursor?.use { c ->
                if (!c.moveToFirst()) return "通讯录里没有找到包含「$nameOrKeyword」的联系人"
                val results = StringBuilder()
                var count = 0
                // moveToFirst 已定位第一条，用 do-while 从第一条开始读，避免漏掉第一个匹配
                do {
                    val name = c.getString(0) ?: "?"
                    val number = c.getString(1) ?: "?"
                    results.append("$name: $number\n")
                    count++
                } while (c.moveToNext() && count < 10)
                "找到的联系人：\n${results.toString().trim()}"
            } ?: "读取通讯录失败"
        } catch (e: Exception) {
            "读取通讯录失败：${e.message}"
        }
    }

    /** 找一个可见且可写的日历；很多机型没有 ID=1 的日历，写死会导致建日程静默失败 */
    private fun writableCalendarId(cr: android.content.ContentResolver): Long? =
        cr.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID),
            "${CalendarContract.Calendars.VISIBLE}=1 AND " +
                "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL}>=${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}",
            null, null
        )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }

    fun createCalendarEvent(a: Activity, title: String, beginMillis: Long, endMillis: Long, desc: String): String {
        if (title.isBlank()) return "日程标题不能为空"
        return try {
            val cr = a.contentResolver
            val calId = writableCalendarId(cr) ?: return "手机上没有可写的日历，无法建日程"
            val values = android.content.ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calId)
                put(CalendarContract.Events.TITLE, title)
                if (desc.isNotBlank()) put(CalendarContract.Events.DESCRIPTION, desc)
                put(CalendarContract.Events.DTSTART, beginMillis)
                put(CalendarContract.Events.DTEND, endMillis)
                put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
            }
            val uri = cr.insert(CalendarContract.Events.CONTENT_URI, values)
            if (uri != null) "已创建日程「$title」（${SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(beginMillis))}）"
            else "创建日程失败：系统返回了空结果"
        } catch (e: SecurityException) {
            "缺少日历写入权限。请在系统设置里给本 App 开「日历」权限后重试"
        } catch (e: Exception) {
            "创建日程失败：${e.message}"
        }
    }

    fun copyToClipboard(a: Activity, text: String): String {
        if (text.isBlank()) return "要复制的内容不能为空"
        return try {
            val cm = a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("AI小助手", text))
            "已复制到剪贴板：「${text.take(60)}${if (text.length > 60) "…" else ""}」"
        } catch (e: Exception) {
            "复制失败：${e.message}"
        }
    }

    fun readClipboard(a: Activity): String {
        return try {
            val cm = a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val item = cm.primaryClip?.getItemAt(0)
            val text = item?.coerceToText(a)?.toString()
            if (text.isNullOrBlank()) "（剪贴板是空的）"
            else "剪贴板内容：「${text.take(300)}${if (text.length > 300) "…" else ""}」"
        } catch (e: Exception) {
            "读取剪贴板失败：${e.message}"
        }
    }

    /**
     * SET_ALARM + SKIP_UI 在部分系统上只静默写入，不会把时钟应用带到前台。
     * 设置完成后再显式打开闹钟列表；即使用户马上看见界面，也不会产生第二个闹钟。
     */
    private fun showAlarmScreen(a: Activity, alarmPackage: String?): Boolean {
        val clockPackage = findClockPackage(a) ?: alarmPackage
        val candidates = listOfNotNull(clockPackage, alarmPackage).distinct()

        // 某些 ROM 会忽略无包名的 SHOW_ALARMS；先对真实时钟包显式发送。
        for (pkg in candidates) {
            val explicit = Intent(AlarmClock.ACTION_SHOW_ALARMS)
                .setPackage(pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { a.startActivity(explicit) }.isSuccess) return true
        }

        val genericShow = Intent(AlarmClock.ACTION_SHOW_ALARMS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { a.startActivity(genericShow) }.isSuccess) return true

        // 最后一层：直接启动时钟应用的 Launcher Activity，保证用户至少能看到目标 App。
        for (pkg in candidates) {
            val launch = runCatching { a.packageManager.getLaunchIntentForPackage(pkg) }
                .getOrNull() ?: continue
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { a.startActivity(launch) }.isSuccess) return true
        }
        return false
    }

    /** 从真实启动器列表里找出时钟应用，避免写死某个厂商包名。 */
    private fun findClockPackage(a: Activity): String? {
        val pm = a.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        data class Candidate(val pkg: String, val label: String, val score: Int)
        return pm.queryIntentActivities(launcher, 0).mapNotNull { ri ->
            val pkg = ri.activityInfo.packageName
            if (pkg == a.packageName) return@mapNotNull null
            val label = runCatching { ri.loadLabel(pm).toString() }.getOrDefault("")
            val normalizedPkg = pkg.lowercase()
            val normalizedLabel = label.lowercase()
            val score = when {
                normalizedPkg.contains("alarmclock") || normalizedPkg.contains("deskclock") -> 100
                normalizedLabel == "时钟" || normalizedLabel == "闹钟" -> 100
                normalizedLabel.contains("时钟") || normalizedLabel.contains("闹钟") -> 80
                normalizedPkg.contains("clock") -> 60
                normalizedLabel.contains("clock") -> 50
                else -> 0
            }
            if (score <= 0) null else Candidate(pkg, label, score)
        }.maxByOrNull { it.score }?.pkg
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
        // 1) DuckDuckGo Lite（结构简单稳定，专为程序化访问设计）
        try {
            val html = httpGet("https://lite.duckduckgo.com/lite/?q=$q", timeout = 10000)
            val out = Regex("(?is)<a[^>]*class=\"result-link\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>").findAll(html)
                .take(8)
                .mapIndexedNotNull { i, m ->
                    val href = m.groupValues[1]
                    val real = Regex("uddg=([^&]+)").find(href)?.groupValues?.get(1)
                        ?.let { URLDecoder.decode(it, "UTF-8") } ?: href
                    val title = stripHtml(m.groupValues[2])
                    if (title.isBlank()) return@mapIndexedNotNull null
                    "${i + 1}. $title\n   $real"
                }.toList()
            if (out.isNotEmpty()) return "「$query」的搜索结果:\n" + out.joinToString("\n\n")
        } catch (_: Exception) { }
        // 2) Bing（改版后可能失效，仅作备用）
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
        // 3) Jina Reader 搜索（通用，返回干净的文本结果）
        try {
            val result = httpGet("https://s.jina.ai/$q", timeout = 15000)
            if (result.isNotBlank() && result.length > 50) {
                return "「$query」的搜索结果:\n" + result.take(3000)
            }
        } catch (e: Exception) { }
        // 4) DuckDuckGo HTML 兜底
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
        val url = if (rawUrl.startsWith("http")) rawUrl else "https://$rawUrl"
        // 先试直接 HTTP GET + strip HTML（对静态页面足够）
        try {
            val content = stripHtml(httpGet(url, timeout = 10000))
            if (content.length > 200) {
                return "网页内容（已提取正文）:\n" + content.take(3500)
            }
        } catch (_: Exception) { }
        // 内容太少或失败 → 用 Jina Reader（能处理 JS 渲染页）
        try {
            val readerUrl = "https://r.jina.ai/$url"
            val content = httpGet(readerUrl, timeout = 20000)
            if (content.isNotBlank() && content.length > 100) {
                return "网页内容（Jina Reader 提取）:\n" + content.take(3500)
            }
        } catch (_: Exception) { }
        return "读取网页失败：$url 可能需要登录、被反爬、或内容完全由 JS 渲染且 Reader 也不可用。建议用 open_url 打开让用户自己看"
        }
}
