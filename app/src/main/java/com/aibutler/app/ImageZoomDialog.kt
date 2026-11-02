package com.aibutler.app

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.FrameLayout
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.min

/** 聊天里点击截图后的全屏查看：支持双指缩放、拖动、双击放大/还原，点一下关闭 */
object ImageZoomDialog {

    fun show(activity: Activity, path: String, preview: Bitmap? = null) {
        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val iv = ZoomableImageView(activity)
        iv.onSingleTapConfirmed = { dialog.dismiss() }
        val root = FrameLayout(activity)
        root.setBackgroundColor(0xE6000000.toInt())
        root.addView(
            iv,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        dialog.setContentView(root)
        dialog.show()

        iv.setImageBitmap(preview ?: ChatAdapter.decodeSampled(path, 1080))
        // 后台再解一张更清晰的版本；用户已经开始缩放时就不打扰当前视角
        Thread {
            val hiRes = ChatAdapter.decodeSampled(path, 2200)
            if (hiRes != null) {
                Handler(Looper.getMainLooper()).post {
                    if (dialog.isShowing && !iv.userInteracted) iv.setImageBitmap(hiRes)
                }
            }
        }.start()
    }
}

class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    private val zoomMatrix = Matrix()
    private var scaleFactor = 1f
    private var lastX = 0f
    private var lastY = 0f

    /** 用户是否已经手动缩放/拖动过（用于决定是否还替换高清图） */
    var userInteracted = false
        private set

    var onSingleTapConfirmed: (() -> Unit)? = null

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val newScale = (scaleFactor * detector.scaleFactor).coerceIn(1f, 6f)
                val applied = newScale / scaleFactor
                if (applied != 1f) {
                    zoomMatrix.postScale(applied, applied, detector.focusX, detector.focusY)
                    scaleFactor = newScale
                    clampTranslation()
                    imageMatrix = zoomMatrix
                    userInteracted = true
                }
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onSingleTapConfirmed?.invoke()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (scaleFactor > 1f) {
                    applyBaseFit()
                } else {
                    zoomMatrix.postScale(2.5f, 2.5f, e.x, e.y)
                    scaleFactor = 2.5f
                    clampTranslation()
                    imageMatrix = zoomMatrix
                    userInteracted = true
                }
                return true
            }
        }
    )

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        applyBaseFit()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyBaseFit()
    }

    /** MATRIX 模式不自动适配，这里手动按 fit-center 摆好底图 */
    private fun applyBaseFit() {
        val d = drawable ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        if (w <= 0 || h <= 0 || dw <= 0 || dh <= 0) return
        val scale = min(w / dw, h / dh)
        zoomMatrix.reset()
        zoomMatrix.setScale(scale, scale)
        zoomMatrix.postTranslate((w - dw * scale) / 2f, (h - dh * scale) / 2f)
        scaleFactor = 1f
        userInteracted = false
        imageMatrix = zoomMatrix
    }

    private fun clampTranslation() {
        val d = drawable ?: return
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        if (dw <= 0 || dh <= 0) return
        val rect = RectF(0f, 0f, dw, dh)
        zoomMatrix.mapRect(rect)
        val w = width.toFloat()
        val h = height.toFloat()
        val dx = when {
            rect.width() <= w -> (w - rect.width()) / 2f - rect.left
            rect.left > 0f -> -rect.left
            rect.right < w -> w - rect.right
            else -> 0f
        }
        val dy = when {
            rect.height() <= h -> (h - rect.height()) / 2f - rect.top
            rect.top > 0f -> -rect.top
            rect.bottom < h -> h - rect.bottom
            else -> 0f
        }
        if (dx != 0f || dy != 0f) zoomMatrix.postTranslate(dx, dy)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && scaleFactor > 1f) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    zoomMatrix.postTranslate(dx, dy)
                    clampTranslation()
                    imageMatrix = zoomMatrix
                    userInteracted = true
                }
                lastX = event.x
                lastY = event.y
            }
        }
        return true
    }
}
