package com.aibutler.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** 轻量前台服务：agent 执行期间保活，防止 ColorOS 等系统在后台冻结进程 */
class AgentKeepAliveService : Service() {
    companion object {
        fun start(context: Context) {
            val intent = Intent(context, AgentKeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AgentKeepAliveService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            // START_STICKY 系统重启（任务中进程被杀后拉起）：任务早已死掉，唯一能关掉
            // "正在执行任务…"通知的路径本来是用户重开 App；这里直接退场，避免僵尸前台服务（体检第五轮 C5）
            stopSelf()
            return START_NOT_STICKY
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val chId = "agent_alive"
        nm.createNotificationChannel(
            NotificationChannel(chId, "AI执行任务", NotificationManager.IMPORTANCE_LOW)
        )
        val notif = Notification.Builder(this, chId)
            .setSmallIcon(R.drawable.ic_assistant)
            .setContentTitle("AI小助手")
            .setContentText("正在执行任务…")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(2002, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(2002, notif)
        }
        return START_STICKY
    }
}
