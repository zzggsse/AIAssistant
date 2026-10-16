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
}
