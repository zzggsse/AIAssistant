package com.aibutler.app

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 通知捕获：读取微信/QQ/短信等新消息通知，AI 就能知道"某某给我发了什么"，
 * 从而帮你回复。需要在系统设置里授予"通知使用权"。
 */
class NotificationCatcher : NotificationListenerService() {

    companion object {
        data class Notif(val app: String, val title: String, val text: String, val time: Long)

        private val gson = Gson()
        private val type = object : TypeToken<MutableList<Notif>>() {}.type
        private const val MAX_CACHED = 100

        private val recent = ArrayDeque<Notif>()
        private var loaded = false

        private fun loadFromDisk(context: Context): MutableList<Notif> {
            val f = java.io.File(context.filesDir, "notifications.json")
            if (!f.exists()) return mutableListOf()
            return try { gson.fromJson(f.readText(), type) ?: mutableListOf() } catch (_: Exception) { mutableListOf() }
        }

        private fun persist(context: Context) {
            try {
                val f = java.io.File(context.filesDir, "notifications.json")
                AtomicFiles.write(f, gson.toJson(recent.toList()))
            } catch (_: Exception) { }
        }

        fun init(context: Context) {
            if (loaded) return
            synchronized(recent) {
                if (loaded) return
                recent.addAll(loadFromDisk(context))
                loaded = true
            }
        }

        fun snapshot(limit: Int): List<Notif> = synchronized(recent) { recent.takeLast(limit) }

        fun add(context: Context, notif: Notif) {
            synchronized(recent) {
                recent.addLast(notif)
                while (recent.size > MAX_CACHED) recent.removeFirst()
            }
            persist(context)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val ex = sbn.notification?.extras ?: return
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = (ex.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: "")
            .ifBlank { ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: "" }
        if (title.isBlank() && text.isBlank()) return
        if (sbn.packageName == packageName) return

        val app = try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(sbn.packageName, 0)
            ).toString()
        } catch (e: Exception) {
            sbn.packageName
        }

        synchronized(recent) {
            val notif = Notif(app, title, text, System.currentTimeMillis())
            recent.addLast(notif)
            while (recent.size > MAX_CACHED) recent.removeFirst()
        }
        persist(this)
    }
}
