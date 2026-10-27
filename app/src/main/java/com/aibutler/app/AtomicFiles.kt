package com.aibutler.app

import java.io.File
import java.io.FileOutputStream

/**
 * JSON 记忆文件的统一保存入口：先写临时文件，再原子改名覆盖。
 *
 * 背景：便签/行为经验/路径记忆/视觉样本/学习任务都是"load → 改 → 整文件重写"，
 * 旧的 writeText 直写在写入中途崩溃/被杀/并发写时会留下半截 JSON，
 * 下次读取把它当损坏文件吞掉异常返回空列表，随后一次保存就把旧数据整体覆盖——
 * 表现为"便签自己消失了、AI 经验越用越少"。先写 .tmp 再 rename 后，
 * 任何时刻磁盘上的正式文件要么是旧完整版，要么是新完整版。
 *
 * 临时文件用 createTempFile 生成唯一名（固定 `原名.tmp` 在两个线程并发写同一文件时
 * 会互踩 tmp，rename 出去的可能是对方半截内容），写入后 fd.sync() 确保断电时
 * 新旧版本至少有一个完整落盘。
 */
object AtomicFiles {

    fun write(file: File, content: String) {
        val tmp = File.createTempFile(file.name, ".tmp", file.parentFile)
        try {
            FileOutputStream(tmp).use { fos ->
                fos.write(content.toByteArray())
                fos.fd.sync()
            }
            if (tmp.renameTo(file)) {
                // .recover 只是同一份新内容的副本：正式文件落位后立即清掉，避免残留堆积
                File(file.parentFile, file.name + ".recover").delete()
                return
            }
            // 个别文件系统 rename 不覆盖已存在文件：退化为"删除后改名"（窗口极小）
            // 先留 .recover 副本：若删除后兜底写入又失败，新旧全无 = 整个存储清零（体检 P3-6）
            if (file.exists()) {
                try {
                    val recover = File(file.parentFile, file.name + ".recover")
                    recover.writeText(content)
                } catch (_: Exception) { }
                file.delete()
            }
            if (tmp.renameTo(file)) {
                File(file.parentFile, file.name + ".recover").delete()
                return
            }
            // 仍失败：最后兜底直接写，至少不丢数据
            file.writeText(content)
            File(file.parentFile, file.name + ".recover").delete()
            tmp.delete()
        } catch (e: Exception) {
            try { tmp.delete() } catch (_: Exception) { }
            throw e
        }
    }
}
