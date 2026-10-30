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
}
