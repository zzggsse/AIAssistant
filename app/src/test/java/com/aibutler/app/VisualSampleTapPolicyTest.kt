package com.aibutler.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class VisualSampleTapPolicyTest {
    @Test
    fun 点选区域_优先选包含触点的最小可见控件() {
        val targets = listOf(
            VisualSampleTapTarget(0, 0, 1080, 1200),
            VisualSampleTapTarget(420, 520, 660, 620),
            VisualSampleTapTarget(440, 540, 640, 600)
        )
        assertEquals(2, VisualSampleTapPolicy.smallestContaining(targets, 500, 560))
        assertEquals(1, VisualSampleTapPolicy.smallestContaining(targets, 430, 530))
        assertNull(VisualSampleTapPolicy.smallestContaining(targets, 1200, 1300))
    }

    @Test
    fun 点选区域_纯图像兜底按触点裁剪并限制在屏幕内() {
        assertEquals(
            VisualSampleTapTarget(450, 300, 630, 480),
            VisualSampleTapPolicy.fallbackCrop(540, 390, 1080, 2400, 180)
        )
        assertEquals(
            VisualSampleTapTarget(0, 0, 180, 180),
            VisualSampleTapPolicy.fallbackCrop(10, 10, 1080, 2400, 180)
        )
        assertNull(VisualSampleTapPolicy.fallbackCrop(-1, 100, 1080, 2400, 180))
        assertFalse(VisualSampleTapTarget(0, 0, 100, 100).contains(100, 100))
    }
}
