package com.aibutler.app

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
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

        val recent = ArrayDeque<Notif>()

        fun snapshot(limit: Int): List<Notif> = synchronized(recent) { recent.takeLast(limit) }
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
            recent.addLast(Notif(app, title, text, System.currentTimeMillis()))
            while (recent.size > 50) recent.removeFirst()
        }
    }
}
