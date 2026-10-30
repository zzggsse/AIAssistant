package com.aibutler.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmUiRulesTest {
    @Test
    fun `waits for slow alarm list loading`() {
        // 实测 OPPO 时钟主列表约 4 秒才显示；30 次 300ms 等待覆盖慢 ROM 和首启动。
        assertTrue(AlarmUiRules.MAX_LOAD_ATTEMPTS >= 30)
    }

    @Test
    fun `recognizes common clock formats`() {
        assertEquals(setOf("07:30", "7:30"), AlarmUiRules.timeVariants(7, 30))
        assertTrue(AlarmUiRules.isTargetRow("07:30", 7, 30, ""))
        assertTrue(AlarmUiRules.isTargetRow("7:30 响一次 | AI测试", 7, 30, "AI测试"))
        assertFalse(AlarmUiRules.isTargetRow("所有闹钟已关闭", 7, 30, "AI测试"))
    }

    @Test
    fun `locates time first when rom splits row text`() {
        // OPPO/ColorOS 实测：07:30 和“响一次 | AI测试”是两个节点，
        // 时间锚点必须允许备注不在同一个节点里，否则闹钟会静默关闭。
        assertTrue(AlarmUiRules.isTimeRow("07:30", 7, 30))
        assertFalse(AlarmUiRules.isTimeRow("响一次 | AI测试", 7, 30))
    }

    @Test
    fun `requires label when target asks for one`() {
        assertTrue(AlarmUiRules.isTargetRow("07:30 响一次 | AI测试", 7, 30, "AI测试"))
        assertFalse(AlarmUiRules.isTargetRow("07:30 响一次", 7, 30, "AI测试"))
    }

    @Test
    fun `requires both time and label for split row nodes`() {
        assertTrue(
            AlarmUiRules.matchesRow(
                "07:30 响一次 | AI测试",
                7,
                30,
                "AI测试",
            ),
        )
        assertFalse(AlarmUiRules.matchesRow("响一次 | AI测试", 7, 30, "AI测试"))
        assertFalse(AlarmUiRules.matchesRow("07:30 响一次", 7, 30, "AI测试"))
    }

    @Test
    fun `accepts only safe same-row visual alarm switch`() {
        val raw = """
            {"found":true,"row_text":"07:30 响一次 | AI测试","row_y":700,
             "switch_x":1100,"switch_y":705,"enabled":false}
        """.trimIndent()
        val target = AlarmUiRules.parseVisualTarget(raw, 7, 30, "AI测试", 1240, 2772)
        requireNotNull(target)
        assertEquals(705, target.switchY)
        assertFalse(target.enabled)
    }

    @Test
    fun `rejects visual switch outside target row or screen`() {
        val offRow = """
            {"found":true,"row_text":"07:30 AI测试","row_y":700,
             "switch_x":1100,"switch_y":1400,"enabled":false}
        """.trimIndent()
        assertNull(AlarmUiRules.parseVisualTarget(offRow, 7, 30, "AI测试", 1240, 2772))

        val leftSide = """
            {"found":true,"row_text":"07:30 AI测试","row_y":700,
             "switch_x":300,"switch_y":705,"enabled":false}
        """.trimIndent()
        assertNull(AlarmUiRules.parseVisualTarget(leftSide, 7, 30, "AI测试", 1240, 2772))

        val wrongLabel = """
            {"found":true,"row_text":"07:30 响一次","row_y":700,
             "switch_x":1100,"switch_y":705,"enabled":false}
        """.trimIndent()
        assertNull(AlarmUiRules.parseVisualTarget(wrongLabel, 7, 30, "AI测试", 1240, 2772))
    }

    @Test
    fun `recognizes disabled alarm list notice`() {
        assertTrue(AlarmUiRules.isAllDisabledNotice(listOf("所有闹钟已关闭")))
        assertTrue(AlarmUiRules.isAllDisabledNotice(listOf("No alarms")))
        assertFalse(AlarmUiRules.isAllDisabledNotice(listOf("07:30", "AI测试")))
    }

    @Test
    fun `recognizes generic alarm creation controls`() {
        assertTrue(AlarmUiRules.isAddAlarmControl("添加闹钟"))
        assertTrue(AlarmUiRules.isAddAlarmControl("New alarm"))
        assertFalse(AlarmUiRules.isAddAlarmControl("闹钟"))
        assertTrue(AlarmUiRules.isSaveAlarmControl("完成"))
        assertTrue(AlarmUiRules.isSaveAlarmControl("Save"))
        assertFalse(AlarmUiRules.isSaveAlarmControl("取消"))
    }

    @Test
    fun `reads localized clock picker values and chooses shortest wrap path`() {
        assertEquals(19, AlarmUiRules.pickerValue("19时"))
        assertEquals(52, AlarmUiRules.pickerValue("52分"))
        assertEquals(7, AlarmUiRules.pickerValue("07"))
        assertNull(AlarmUiRules.pickerValue("闹钟名称"))
        assertEquals(1, AlarmUiRules.shorterPickerDirection(19, 7, 24))
        assertEquals(-1, AlarmUiRules.shorterPickerDirection(8, 7, 24))
        assertEquals(-1, AlarmUiRules.shorterPickerDirection(52, 30, 60))
    }

    @Test
    fun `matches label fields across common clock wording`() {
        assertTrue(AlarmUiRules.isAlarmLabelField("闹钟名称", "闹钟名称"))
        assertTrue(AlarmUiRules.isAlarmLabelField("", "标签"))
        assertTrue(AlarmUiRules.isAlarmLabelField("", "Label"))
        assertFalse(AlarmUiRules.isAlarmLabelField("铃声", "默认铃声"))
    }
}
