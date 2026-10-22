package com.aibutler.app

object TaskControlBus {
    @Volatile
    var taskRunning = false

    @Volatile
    var pauseRequested = false

    @Volatile
    var pauseHandler: (() -> Unit)? = null

    fun requestPause() {
        if (taskRunning) {
            pauseRequested = true
            pauseHandler?.invoke()
        }
    }

    /** 页面销毁时调用：断开对 Activity（tts/adapter/列表）的引用，防止旧界面被全局单例拽住无法回收 */
    fun release() {
        pauseHandler = null
    }
}
