package com.aibutler.app

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskControlBusTest {

    @After
    fun reset() {
        TaskControlBus.taskRunning = false
        TaskControlBus.pauseRequested = false
        TaskControlBus.pauseHandler = null
    }

    @Test
    fun 悬浮窗停止立即回调并要求回助手() {
        TaskControlBus.taskRunning = true
        var callback: Boolean? = null
        TaskControlBus.pauseHandler = { returnToAssistant -> callback = returnToAssistant }

        TaskControlBus.requestPause(returnToAssistant = true)

        assertTrue(TaskControlBus.pauseRequested)
        assertEquals(true, callback)
    }

    @Test
    fun 任务未运行时不误发暂停() {
        var called = false
        TaskControlBus.pauseHandler = { called = true }

        TaskControlBus.requestPause(returnToAssistant = true)

        assertFalse(TaskControlBus.pauseRequested)
        assertFalse(called)
    }
}
