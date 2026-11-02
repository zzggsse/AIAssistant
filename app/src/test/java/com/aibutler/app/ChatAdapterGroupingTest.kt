package com.aibutler.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatAdapterGroupingTest {

    @Test
    fun `legacy merged vision thinking splits into independent cards`() {
        val legacy = listOf(
            ChatAdapter.Msg(
                "vision",
                "round 1",
                imagePath = "/tmp/a.jpg",
                thinkText = "open search first"
            )
        )

        val split = ChatAdapter.splitLegacyVisionThinking(legacy)

        assertEquals(2, split.size)
        assertEquals("vision", split[0].role)
        assertNull(split[0].thinkText)
        assertEquals("think", split[1].role)
        assertEquals("open search first", split[1].text)
        assertFalse(split[1].collapsed)
    }

    @Test
    fun `normal thinking stays separate without vision`() {
        val legacy = listOf(
            ChatAdapter.Msg("user", "check weather"),
            ChatAdapter.Msg("think", "detect intent")
        )

        val split = ChatAdapter.splitLegacyVisionThinking(legacy)

        assertEquals(2, split.size)
        assertNull(split[1].thinkText)
    }

    @Test
    fun `collapse all thinking leaves vision fold state unchanged`() {
        val items = mutableListOf(
            ChatAdapter.Msg("think", "first reason"),
            ChatAdapter.Msg("vision", "round 1", collapsed = true, imagePath = "/tmp/a.jpg"),
            ChatAdapter.Msg("think", "second reason", collapsed = true)
        )

        assertTrue(ChatAdapter.collapseAllThinkingItems(items))

        assertTrue(items[0].collapsed)
        assertTrue(items[1].collapsed)
        assertTrue(items[2].collapsed)
    }

    @Test
    fun `collapse all returns false when every thinking card is hidden`() {
        val items = mutableListOf(
            ChatAdapter.Msg("think", "first reason", collapsed = true),
            ChatAdapter.Msg("vision", "round 1", collapsed = true, imagePath = "/tmp/a.jpg")
        )

        assertFalse(ChatAdapter.collapseAllThinkingItems(items))
    }

    @Test
    fun `multi round task builds group with prepare round and terminal tail`() {
        val items = mutableListOf(
            ChatAdapter.Msg("user", "设8点闹钟"),
            ChatAdapter.Msg("system", "📋 任务规划：screen_task · 时钟"),
            ChatAdapter.Msg("think", "打开时钟"),
            ChatAdapter.Msg("system", "🔧 正在执行：open_app {\"app\":\"时钟\"}"),
            ChatAdapter.Msg("system", "已打开 时钟"),
            ChatAdapter.Msg("think", "点击加号"),
            ChatAdapter.Msg("system", "🔧 正在执行：click {}"),
            ChatAdapter.Msg("assistant", "✅ 已设置 8:00 的闹钟")
        )

        val groups = ChatAdapter.buildTaskGroups(items)

        assertEquals(1, groups.size)
        val group = groups[0]
        assertEquals(0, group.userIdx)
        assertEquals(3, group.rounds.size)
        assertEquals(0, group.rounds[0].roundNo)
        assertEquals(1, group.rounds[1].roundNo)
        assertEquals("open_app", group.rounds[1].title)
        assertEquals(2, group.rounds[2].roundNo)
        assertEquals(listOf(7), group.tailIdx)
    }

    @Test
    fun `collapsed task keeps user message and final reply visible`() {
        val items = mutableListOf(
            ChatAdapter.Msg("user", "设8点闹钟"),
            ChatAdapter.Msg("think", "打开时钟"),
            ChatAdapter.Msg("system", "🔧 正在执行：open_app {}"),
            ChatAdapter.Msg("think", "点击加号"),
            ChatAdapter.Msg("system", "🔧 正在执行：click {}"),
            ChatAdapter.Msg("assistant", "✅ 已设置 8:00 的闹钟")
        )
        items[0].taskCollapsed = true

        val visible = ChatAdapter.visibleMessageIndices(items, ChatAdapter.buildTaskGroups(items))

        assertEquals(listOf(0, 5), visible)
    }

    @Test
    fun `expanded task shows every round message`() {
        val items = mutableListOf(
            ChatAdapter.Msg("user", "设8点闹钟"),
            ChatAdapter.Msg("think", "打开时钟"),
            ChatAdapter.Msg("system", "🔧 正在执行：open_app {}"),
            ChatAdapter.Msg("assistant", "✅ 已设置 8:00 的闹钟")
        )

        val visible = ChatAdapter.visibleMessageIndices(items, ChatAdapter.buildTaskGroups(items))

        assertEquals(listOf(0, 1, 2, 3), visible)
    }

    @Test
    fun `error note stays visible in collapsed task`() {
        val items = mutableListOf(
            ChatAdapter.Msg("user", "打开淘宝搜索耳机"),
            ChatAdapter.Msg("think", "点击搜索框"),
            ChatAdapter.Msg("system", "🔧 正在执行：click {}"),
            ChatAdapter.Msg("system", "❌ 出错了：页面超时"),
            ChatAdapter.Msg("assistant", "❌ 没能完成任务")
        )
        items[0].taskCollapsed = true

        val visible = ChatAdapter.visibleMessageIndices(items, ChatAdapter.buildTaskGroups(items))

        assertEquals(listOf(0, 3, 4), visible)
    }

    @Test
    fun `single step task produces no group`() {
        val items = mutableListOf(
            ChatAdapter.Msg("user", "设8点闹钟"),
            ChatAdapter.Msg("system", "⏰ 正在设置 08:00 的闹钟…"),
            ChatAdapter.Msg("assistant", "✅ 已设置 8:00 的闹钟，请确认闹钟已开启")
        )

        assertTrue(ChatAdapter.buildTaskGroups(items).isEmpty())
    }
}
