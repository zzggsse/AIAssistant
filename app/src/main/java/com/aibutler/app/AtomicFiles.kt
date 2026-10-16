package com.aibutler.app

import java.io.File

/**
 * JSON 记忆文件的统一保存入口：先写临时文件，再原子改名覆盖。
 *
 * 背景：便签/行为经验/路径记忆/视觉样本/学习任务都是"load → 改 → 整文件重写"，
 * 旧的 writeText 直写在写入中途崩溃/被杀/并发写时会留下半截 JSON，
 * 下次读取把它当损坏文件吞掉异常返回空列表，随后一次保存就把旧数据整体覆盖——
 * 表现为"便签自己消失了、AI 经验越用越少"。先写 .tmp 再 rename 后，
 * 任何时刻磁盘上的正式文件要么是旧完整版，要么是新完整版。
 */
object AtomicFiles {

    fun write(file: File, content: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            tmp.writeText(content)
            if (tmp.renameTo(file)) return
            // 个别文件系统 rename 不覆盖已存在文件：退化为"删除后改名"（窗口极小）
            if (file.exists()) file.delete()
            if (tmp.renameTo(file)) return
            // 仍失败：最后兜底直接写，至少不丢数据
            file.writeText(content)
            tmp.delete()
        } catch (e: Exception) {
            try { tmp.delete() } catch (_: Exception) { }
            throw e
        }
    }
}
