package com.aibutler.app

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager

/**
 * 透明亮屏页：熄屏时由 Agent 启动它，把屏幕点亮（锁屏界面上方显示）。
 * 真正的解锁由无障碍服务点 PIN 数字键盘完成。
 */
class WakeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        Handler(Looper.getMainLooper()).postDelayed({ finish() }, 2000)
    }
}
