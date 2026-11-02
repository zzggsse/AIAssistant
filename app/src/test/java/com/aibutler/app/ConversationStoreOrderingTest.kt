package com.aibutler.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationStoreOrderingTest {

    @Test
    fun `排序时间取最后一条消息的发送时间`() {
        val messages = listOf(
            ChatAdapter.Msg("user", "第一条", sentAt = 100L),
            ChatAdapter.Msg("assistant", "回复", sentAt = 220L)
        )

        assertEquals(220L, ConversationStore.lastMessageAt(messages, 999L))
    }

    @Test
    fun `旧消息没有发送时间时保留原会话时间`() {
        val messages = listOf(
            ChatAdapter.Msg("user", "旧消息", sentAt = 0L)
        )

        assertEquals(888L, ConversationStore.lastMessageAt(messages, 888L))
    }
}
