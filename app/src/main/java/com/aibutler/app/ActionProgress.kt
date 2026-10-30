package com.aibutler.app

/**
 * 动作空转判定：只有"同样的动作 + 页面毫无变化"才算空转。
 * 刷视频流时同方向连续滑动每次都会换新视频（页面变化），是有效进展而非空转。
 */
object ActionProgress {
    fun repeatedSwipeIdle(previousSig: String, currentSig: String, pageChanged: Boolean): Boolean =
        currentSig == previousSig && !pageChanged
}
