package com.aibutler.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatAdapterGroupingTest {

    private fun groupLegacy(source: List<ChatAdapter.Msg>): List<ChatAdapter.Msg> {
        val method = ChatAdapter::class.java.getDeclaredMethod(
            "groupLegacyVisionThinking",
            List::class.java
        )
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(null, source) as List<ChatAdapter.Msg>
    }

    @Test
    fun `legacy vision and thinking merge into one card`() {
        val legacy = listOf(
            ChatAdapter.Msg("vision", "round 1", imagePath = "/tmp/a.jpg"),
            ChatAdapter.Msg("think", "open search first"),
            ChatAdapter.Msg("think", "then input text")
        )

        val merged = groupLegacy(legacy)

        assertEquals(1, merged.size)
        assertEquals("open search first\n\nthen input text", merged[0].thinkText)
    }

    @Test
    fun `normal thinking stays separate without vision`() {
        val legacy = listOf(
            ChatAdapter.Msg("user", "check weather"),
            ChatAdapter.Msg("think", "detect intent")
        )

        val merged = groupLegacy(legacy)

        assertEquals(2, merged.size)
        assertNull(merged[1].thinkText)
    }
}
