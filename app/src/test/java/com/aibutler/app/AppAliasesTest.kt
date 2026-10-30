package com.aibutler.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppAliasesTest {
    @Test
    fun `normalizes common romanized app names only as data`() {
        assertEquals("哔哩哔哩", AppAliases.canonicalLabel("bilibili"))
        assertEquals("哔哩哔哩", AppAliases.canonicalLabel(" Bili "))
        assertEquals("哔哩哔哩", AppAliases.canonicalLabel("B站"))
        assertEquals("淘宝", AppAliases.canonicalLabel("Taobao"))
        assertNull(AppAliases.canonicalLabel("任意未知应用"))
    }

    @Test
    fun `explicit alias inside task wins over unrelated assistant wording`() {
        assertEquals("哔哩哔哩", AppAliases.findInText("打开B站搜索AI助手"))
        assertEquals("淘宝", AppAliases.findInText("打开淘宝搜索保温杯"))
        assertNull(AppAliases.findInText("打开设置"))
    }

    @Test
    fun `alias request matches routed target while unrelated app stays blocked`() {
        val target = "哔哩哔哩" to "tv.example.video"
        assertTrue(
            AppAliases.isLaunchRequestForTarget(
                "bilibili",
                "打开B站搜索AI助手，然后播放第一个视频",
                target
            )
        )
        assertTrue(AppAliases.isLaunchRequestForTarget("B站", "打开B站搜索AI助手", target))
        assertFalse(
            AppAliases.isLaunchRequestForTarget(
                "bilibili",
                "打开淘宝搜索保温杯",
                "淘宝" to "com.example.taobao"
            )
        )
    }
}
