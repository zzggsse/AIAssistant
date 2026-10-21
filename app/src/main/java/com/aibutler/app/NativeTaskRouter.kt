package com.aibutler.app

/**
 * 单步系统任务只在本地做确定性路由，不交给模型猜工具。
 * 复杂/组合任务仍交给普通任务循环，避免误吞用户后续要求。
 */
object NativeTaskRouter {
    data class AlarmRequest(val hour: Int, val minute: Int, val label: String = "")
    data class NoteRequest(val title: String, val content: String)

    fun parseAlarmRequest(raw: String): AlarmRequest? {
        val text = raw.trim()
        if (text.isBlank() || !text.contains("闹钟")) return null

        // 查询、取消、关闭闹钟不是“新建设置”，不能走创建路径。
        if (Regex("取消|删除|关闭|关掉|停止|查看|查询|看看|有没有|还剩").containsMatchIn(text)) {
            return null
        }
        // 组合指令交给完整任务引擎处理，避免本地捷径只做一半。
        if (Regex("然后|接着|之后|并且|同时|再帮|顺便").containsMatchIn(text)) return null

        val createsAlarm = Regex(
            "(?:设|设置|设定|定|创建|新建|添加|加).{0,12}(?:闹钟|闹铃)|" +
                "(?:闹钟|闹铃).{0,6}(?:设|设置|定)"
        ).containsMatchIn(text)
        if (!createsAlarm) return null

        val time = TaskGoalVerifier.parseTime(text) ?: return null
        val label = Regex("(?:备注|标签|名字|名称)(?:叫|是|为|[:：])?\\s*([^，。,.!！?？]{1,30})")
            .find(text)
            ?.groupValues
            ?.get(1)
            ?.trim()
            .orEmpty()
        return AlarmRequest(time.hour, time.minute, label)
    }

    fun parseNoteRequest(raw: String): NoteRequest? {
        val text = raw.trim()
        if (text.isBlank() || !Regex("便签|备忘|记一下|记个").containsMatchIn(text)) return null
        if (Regex("取消|删除|修改|编辑|查看|查询|看看|搜索").containsMatchIn(text)) return null
        if (Regex("然后|接着|之后|并且|同时|再帮|顺便").containsMatchIn(text)) return null

        val content = Regex(
            "(?:便签|备忘)[^:：,，。.]{0,12}(?:写入|输入|添加|加|记录|记|写)\\s*[:：,，]?\\s*(.+)"
        ).find(text)?.groupValues?.get(1)?.trim()
            ?: Regex(
            "(?:记一下|记个)\\s*(?:便签|备忘)?\\s*[:：,，]?\\s*(.+)"
        ).find(text)?.groupValues?.get(1)?.trim().orEmpty()

        if (content.isBlank() || content.length > 500) return null
        val cleanContent = content
            .removePrefix("内容是")
            .removePrefix("内容为")
            .removePrefix("内容")
            .removePrefix("正文是")
            .removePrefix("正文为")
            .removePrefix("正文")
            .trim()
        return NoteRequest(title = cleanContent.take(20), content = cleanContent)
    }
}
