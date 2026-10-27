package com.aibutler.app

import android.content.Context
import com.google.gson.JsonObject
import java.io.File

/**
 * 评测数据落盘：把视觉脑每一轮的「输入→模型回答→执行结果→页面变化」写成 JSONL。
 *
 * 目的：改 prompt / 换模型 / 调参前后跑同一批真实任务，用同一份数据对比
 * 误点率、协议违规率、每任务轮数，不再凭感觉调优。
 *
 * 文件位置：filesDir/eval/eval_log.jsonl（应用私有目录，卸载即清）。
 * 超过 8MB 轮转为 .old（只留一代，不会无限膨胀）。
 * 开关：Prefs.evalLog()，默认开；任何异常都吞掉，绝不影响任务主流程。
 */
object EvalLogger {

    private const val DIR = "eval"
    private const val FILE = "eval_log.jsonl"
    private const val MAX_BYTES = 8L * 1024 * 1024

    /** 追加一条评测记录（自动补时间戳）；任何异常都吞掉 */
    fun log(context: Context, entry: JsonObject) {
        try {
            val dir = File(context.filesDir, DIR).apply { mkdirs() }
            val file = File(dir, FILE)
            if (file.length() > MAX_BYTES) {
                val old = File(dir, "$FILE.old")
                old.delete()
                // rename 失败不能静默：否则当前文件无限增长，8MB 上限失效（体检 P3-8）
                if (!file.renameTo(old)) {
                    android.util.Log.w("AIButler", "评测日志轮转失败，继续写入当前文件")
                }
            }
            entry.addProperty("ts", System.currentTimeMillis())
            file.appendText(entry.toString() + "\n")
        } catch (_: Exception) {
        }
    }

    /** 当前日志文件路径（导出/查看用）；不存在返回 null */
    fun currentFile(context: Context): File? =
        File(File(context.filesDir, DIR), FILE).takeIf { it.exists() }
}
