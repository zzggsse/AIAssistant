package com.aibutler.app

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本地崩溃/错误日志：安装 UncaughtExceptionHandler 把未捕获异常写入文件，
 * 运行时的关键失败也通过 [log] 记录。设置页开发者模式可导出/清空。
 */
object CrashLogger {
    private const val TAG = "CrashLogger"
    private const val MAX_BYTES = 256 * 1024L
    private const val MAX_FILES = 3

    private var appContext: Context? = null
    private var installed = false

    fun install(context: Context) {
        appContext = context.applicationContext
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            log("CRASH", "thread=${thread.name}", throwable)
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun log(tag: String, message: String = "", throwable: Throwable? = null) {
        val ctx = appContext ?: return
        try {
            val dir = logDir(ctx)
            dir.mkdirs()
            val file = File(dir, "error_log.txt")
            rotateIfNeeded(file)
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val sb = StringBuilder()
            sb.append("[$ts][$tag]")
            if (message.isNotBlank()) sb.append(" $message")
            if (throwable != null) {
                sb.append('\n').append(throwable.javaClass.name).append(": ").append(throwable.message)
                for (frame in throwable.stackTrace.take(15)) {
                    sb.append("\n  at ").append(frame)
                }
            }
            sb.append('\n').append("---").append('\n')
            file.appendText(sb.toString())
        } catch (_: Exception) { }
    }

    fun logFile(context: Context): File = File(logDir(context), "error_log.txt")

    fun clear(context: Context) {
        try { logFile(context).delete() } catch (_: Exception) { }
    }

    fun deviceInfo(): String {
        return "device=${Build.MANUFACTURER} ${Build.MODEL}  android=${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"
    }

    private fun logDir(context: Context): File = File(context.filesDir, "logs")

    private fun rotateIfNeeded(current: File) {
        if (current.exists() && current.length() > MAX_BYTES) {
            // 保留最近 MAX_FILES-1 个历史日志，当前转存为带时间戳的归档
            val dir = current.parentFile ?: return
            val archives = dir.listFiles { f -> f.name.startsWith("error_log_") }?.sorted()?.toMutableList() ?: mutableListOf()
            // delete 失败必须把元素从列表剔除/退出循环，否则 archives.size 永远不变 → 死循环，
            // 且这里跑在崩溃处理路径上，会把崩溃处理本身挂死（体检 P1-6）
            while (archives.size >= MAX_FILES) {
                if (!archives.removeAt(0).delete()) break
            }
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val archived = current.renameTo(File(dir, "error_log_$ts.txt"))
            if (!archived) {
                // 改名失败（文件被占用等）就截断重建：否则超限文件永远轮转不出去
                current.writeText("")
            }
        }
    }
}
