package com.aibutler.app

import java.util.Locale
import kotlin.math.abs

/** 闹钟列表的无障碍读屏规则；把时间/开关判断和控件遍历分开，便于回归测试。 */
internal object AlarmUiRules {
    const val MAX_LOAD_ATTEMPTS = 30
    const val MAX_EDITOR_WAIT_ATTEMPTS = 15

    /** 读屏不可用时的视觉定位结果；坐标必须是真实屏幕像素。 */
    data class VisualTarget(
        val rowText: String,
        val rowY: Int,
        val switchX: Int,
        val switchY: Int,
        val enabled: Boolean,
    )

    fun timeVariants(hour: Int, minute: Int): Set<String> {
        val paddedMinute = "%02d".format(minute)
        return setOf(
            "%02d:%s".format(hour, paddedMinute),
            "%d:%s".format(hour, paddedMinute),
        )
    }

    fun isTimeRow(label: String, hour: Int, minute: Int): Boolean {
        val own = label.trim()
        if (own.isEmpty()) return false
        return timeVariants(hour, minute).any { time ->
            own.contains(time, ignoreCase = true)
        }
    }

    fun isTargetRow(label: String, hour: Int, minute: Int, targetLabel: String): Boolean {
        if (!isTimeRow(label, hour, minute)) return false
        return targetLabel.isBlank() || label.contains(targetLabel.trim(), ignoreCase = true)
    }

    /** ROM 可能把时间、备注和开关拆成兄弟节点；整行文本必须同时命中时间和备注。 */
    fun matchesRow(rowText: String, hour: Int, minute: Int, targetLabel: String): Boolean {
        return isTargetRow(rowText, hour, minute, targetLabel)
    }

    /** 只接受“时间+标签同行、开关在同一行右侧”的视觉结果，避免模型误报时点错其他控件。 */
    fun parseVisualTarget(
        raw: String,
        hour: Int,
        minute: Int,
        targetLabel: String,
        screenWidth: Int,
        screenHeight: Int,
    ): VisualTarget? {
        if (screenWidth <= 0 || screenHeight <= 0) return null
        val json = try {
            com.google.gson.JsonParser.parseString(raw.trim()).asJsonObject
        } catch (_: Exception) {
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            try {
                com.google.gson.JsonParser.parseString(raw.substring(start, end + 1)).asJsonObject
            } catch (_: Exception) {
                return null
            }
        }
        if (json.get("found")?.takeIf { !it.isJsonNull }?.asBoolean != true) return null
        val rowText = json.get("row_text")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
        if (!matchesRow(rowText, hour, minute, targetLabel)) return null
        val rowY = json.get("row_y")?.takeIf { !it.isJsonNull }?.asInt ?: return null
        val switchX = json.get("switch_x")?.takeIf { !it.isJsonNull }?.asInt ?: return null
        val switchY = json.get("switch_y")?.takeIf { !it.isJsonNull }?.asInt ?: return null
        val enabled = json.get("enabled")?.takeIf { !it.isJsonNull }?.asBoolean ?: return null
        val inScreen = rowY in 0 until screenHeight &&
            switchX in 0 until screenWidth &&
            switchY in 0 until screenHeight
        val sameRow = abs(rowY - switchY) <= screenHeight * 0.04
        val rightSide = switchX >= screenWidth * 0.50
        return if (inScreen && sameRow && rightSide) {
            VisualTarget(rowText, rowY, switchX, switchY, enabled)
        } else null
    }

    fun isAllDisabledNotice(labels: Collection<String>): Boolean {
        return labels.any {
            val normalized = it.trim().lowercase(Locale.CHINA)
            normalized.contains("所有闹钟已关闭") ||
                normalized.contains("闹钟已全部关闭") ||
                normalized.contains("no alarms")
        }
    }

    fun isAddAlarmControl(label: String): Boolean {
        val normalized = label.trim().lowercase(Locale.CHINA)
        return listOf("添加闹钟", "新增闹钟", "新建闹钟", "add alarm", "new alarm", "create alarm")
            .any(normalized::contains)
    }

    fun isSaveAlarmControl(label: String): Boolean {
        val normalized = label.trim().lowercase(Locale.CHINA)
        return listOf("完成", "保存", "确定", "添加", "新增", "done", "save", "ok")
            .any(normalized::contains)
    }

    fun isAlarmLabelField(ownText: String, hint: String): Boolean {
        val normalized = "$ownText $hint".trim().lowercase(Locale.CHINA)
        return listOf("闹钟名称", "闹钟标签", "备注", "标签", "名称", "label")
            .any(normalized::contains)
    }

    /** 时间选择器当前值可能是“7时”“07分”，也可能只有纯数字。 */
    fun pickerValue(label: String): Int? {
        val normalized = label.trim()
        val match = Regex("(\\d{1,2})(?:\\s*(?:时|分|点)|$)").find(normalized)
            ?: return null
        return match.groupValues[1].toIntOrNull()?.takeIf { it in 0..99 }
    }

    fun shorterPickerDirection(current: Int, target: Int, range: Int): Int {
        require(range > 0)
        val forward = (target - current + range) % range
        val backward = (current - target + range) % range
        return if (forward <= backward) 1 else -1
    }
}
