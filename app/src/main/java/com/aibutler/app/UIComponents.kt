package com.aibutler.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

class LikeBurstView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onLikeChanged: ((count: Int, liked: Boolean) -> Unit)? = null

    private var liked = false
    private var count = 0
    private var phase = 1f
    private var animator: ValueAnimator? = null
    private var previousCount = 0

    private val particles = Array(14) { index ->
        val angle = index * (Math.PI * 2 / 14) + (if (index % 2 == 0) -0.12 else 0.16)
        Particle(angle.toFloat(), dp(if (index % 3 == 0) 22f else if (index % 3 == 1) 17f else 14f))
    }

    private val heartPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(113, 113, 122)
        style = Paint.Style.FILL
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.colorTextSecondary)
        textSize = sp(13f)
        textAlign = Paint.Align.LEFT
    }
    private val heartPath = Path()

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "点赞这条回复"
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setLikeState(newCount: Int, newLiked: Boolean, animate: Boolean = false) {
        animator?.cancel()
        liked = newLiked
        count = max(0, newCount)
        previousCount = count
        phase = 1f
        contentDescription = if (liked) "已赞，$count 个赞" else "点赞这条回复，$count 个赞"
        if (!animate) invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(dp(82f).toInt(), dp(36f).toInt())
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            performClick()
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        toggleLike()
        return true
    }

    private fun toggleLike() {
        if (animator?.isRunning == true) return
        liked = !liked
        previousCount = count
        count = if (liked) count + 1 else max(0, count - 1)
        contentDescription = if (liked) "已赞，$count 个赞" else "点赞这条回复，$count 个赞"
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        startBurst()
        onLikeChanged?.invoke(count, liked)
    }

    private fun startBurst() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 680L
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    phase = 1f
                    invalidate()
                }
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val cx = dp(21f)
        val cy = height * 0.52f
        val baseSize = dp(9f)
        val squashT = (phase / 0.22f).coerceIn(0f, 1f)
        val popT = ((phase - 0.18f) / 0.34f).coerceIn(0f, 1f)

        var sx = 1f
        var sy = 1f
        var lift = 0f
        if (phase < 0.22f) {
            sx = 1f - 0.32f * sin(Math.PI * squashT).toFloat()
            sy = 1f + 0.17f * sin(Math.PI * squashT).toFloat()
        } else if (phase < 0.52f) {
            sx = 1f + 0.18f * sin(Math.PI * popT).toFloat()
            sy = 1f - 0.10f * sin(Math.PI * popT).toFloat()
            lift = dp(6f) * sin(Math.PI * popT).toFloat()
        }

        drawParticles(canvas, cx, cy)

        heartPath.reset()
        heartPath.moveTo(cx, cy + baseSize * 0.72f)
        heartPath.cubicTo(
            cx - baseSize * 1.15f, cy + baseSize * 0.10f,
            cx - baseSize * 0.55f, cy - baseSize * 0.98f,
            cx, cy - baseSize * 0.32f
        )
        heartPath.cubicTo(
            cx + baseSize * 0.55f, cy - baseSize * 0.98f,
            cx + baseSize * 1.15f, cy + baseSize * 0.10f,
            cx, cy + baseSize * 0.72f
        )
        heartPath.close()

        val saved = canvas.save()
        canvas.translate(cx, cy - lift)
        canvas.scale(sx, sy)
        canvas.translate(-cx, -cy)
        heartPaint.color = if (liked) Color.rgb(244, 63, 94) else Color.rgb(113, 113, 122)
        canvas.drawPath(heartPath, heartPaint)
        canvas.restoreToCount(saved)

        val rollT = ((phase - 0.28f) / 0.45f).coerceIn(0f, 1f)
        val textX = dp(38f)
        val textY = cy - (textPaint.ascent() + textPaint.descent()) / 2f
        val oldAlpha = (255 * (1f - rollT)).toInt()
        val newAlpha = (255 * rollT).toInt()
        if (oldAlpha > 0) {
            textPaint.alpha = oldAlpha
            canvas.drawText(previousCount.toString(), textX, textY - dp(13f) * rollT, textPaint)
        }
        if (newAlpha > 0) {
            textPaint.alpha = newAlpha
            canvas.drawText(count.toString(), textX, textY + dp(13f) * (1f - rollT), textPaint)
        }
        textPaint.alpha = 255
    }

    private fun drawParticles(canvas: Canvas, cx: Float, cy: Float) {
        val burstT = ((phase - 0.22f) / 0.55f).coerceIn(0f, 1f)
        if (burstT <= 0f || burstT >= 1f) return
        val ease = 1f - (1f - burstT) * (1f - burstT)
        particles.forEach { particle ->
            val x = cx + cos(particle.angle) * particle.distance * ease
            val y = cy + sin(particle.angle) * particle.distance * ease
            dotPaint.color = if (liked) Color.rgb(244, 63, 94) else Color.rgb(113, 113, 122)
            dotPaint.alpha = ((1f - burstT) * 255).toInt()
            canvas.drawCircle(x, y, dp(2.3f) * (1f - 0.35f * burstT), dotPaint)
        }
        dotPaint.alpha = 255
    }

    private class Particle(val angle: Float, val distance: Float)

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
    private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity
}

class SubmitMorphButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private enum class State { IDLE, BUSY, SUCCESS }

    private var state = State.IDLE
    private var morph = 0f
    private var success = 0f
    private var spinnerAngle = 0f
    private var animator: ValueAnimator? = null

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
        strokeWidth = dp(2.5f)
    }
    private val path = Path()

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "发送"
    }

    fun setBusy(busy: Boolean, completedSuccessfully: Boolean = true) {
        when {
            busy -> if (state != State.BUSY) startMorph(State.BUSY)
            completedSuccessfully -> if (state != State.SUCCESS) startMorph(State.SUCCESS)
            else -> startMorph(State.IDLE)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(dp(48f).toInt(), dp(48f).toInt())
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (state == State.BUSY) startSpinner()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    private fun startMorph(target: State) {
        animator?.cancel()
        state = target
        contentDescription = when (target) {
            State.IDLE -> "发送"
            State.BUSY -> "正在执行，点按可停止"
            State.SUCCESS -> "已完成"
        }
        val from = if (target == State.IDLE) 1f else 0f
        animator = ValueAnimator.ofFloat(from, 1f).apply {
            duration = if (target == State.BUSY) 170L else 250L
            addUpdateListener {
                if (state == State.SUCCESS) success = it.animatedValue as Float else morph = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (state == State.BUSY) {
                        morph = 1f
                        startSpinner()
                    } else if (state == State.SUCCESS) {
                        success = 1f
                        postDelayed({
                            if (state == State.SUCCESS) startMorph(State.IDLE)
                        }, 850L)
                    } else {
                        morph = 0f
                    }
                    invalidate()
                }
            })
            start()
        }
    }

    private fun startSpinner() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 820L
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                spinnerAngle = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val inset = dp(2f)
        val radius = when (state) {
            State.IDLE -> width / 2f - inset
            State.BUSY -> (width / 2f - inset) * (1f - 0.10f * morph)
            State.SUCCESS -> width / 2f - inset
        }
        val rect = RectF(inset, inset, width - inset, height - inset)
        bgPaint.color = when (state) {
            State.IDLE -> context.getColor(R.color.colorPrimary)
            State.BUSY -> context.getColor(R.color.colorError)
            State.SUCCESS -> context.getColor(R.color.colorSuccess)
        }
        canvas.drawRoundRect(rect, radius, radius, bgPaint)
        val cx = width / 2f
        val cy = height / 2f

        when (state) {
            State.IDLE -> drawSendArrow(canvas, cx, cy)
            State.BUSY -> {
                val sweep = 90f + 150f * (0.5f + 0.5f * sin(Math.PI * morph).toFloat())
                canvas.drawArc(rect, spinnerAngle, sweep, false, progressPaint)
                iconPaint.alpha = (230 * morph).toInt()
                canvas.drawRect(cx - dp(4f), cy - dp(4f), cx + dp(4f), cy + dp(4f), iconPaint)
                iconPaint.alpha = 255
            }
            State.SUCCESS -> {
                path.reset()
                path.moveTo(cx - dp(7f), cy + dp(0.5f))
                path.lineTo(cx - dp(2f), cy + dp(5.5f))
                path.lineTo(cx + dp(8f), cy - dp(5.5f))
                val measure = android.graphics.PathMeasure(path, false)
                val drawn = measure.length * success
                val visiblePath = Path()
                measure.getSegment(0f, drawn.coerceAtLeast(1f), visiblePath, true)
                progressPaint.style = Paint.Style.STROKE
                canvas.drawPath(visiblePath, progressPaint)
                progressPaint.style = Paint.Style.STROKE
            }
        }
    }

    private fun drawSendArrow(canvas: Canvas, cx: Float, cy: Float) {
        path.reset()
        path.moveTo(cx - dp(6.5f), cy - dp(6.5f))
        path.lineTo(cx + dp(7.5f), cy)
        path.lineTo(cx - dp(6.5f), cy + dp(6.5f))
        path.lineTo(cx - dp(3.5f), cy)
        path.close()
        canvas.drawPath(path, iconPaint)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}

class JellyToggle @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onStateChanged: ((checked: Boolean) -> Unit)? = null

    var isChecked: Boolean = false
        private set

    private var position = 0f
    private var settle = 0f
    private var animator: ValueAnimator? = null
    private var settleAnimator: ValueAnimator? = null

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(34, 24, 24, 27)
    }

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "开关"
    }

    fun setCheckedWithoutCallback(checked: Boolean) {
        animator?.cancel()
        settleAnimator?.cancel()
        isChecked = checked
        position = if (checked) 1f else 0f
        settle = 0f
        refreshDescription()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = max(dp(58f).toInt(), suggestedMinimumWidth)
        val height = max(dp(32f).toInt(), suggestedMinimumHeight)
        setMeasuredDimension(width, height)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            performClick()
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        setChecked(!isChecked)
        return true
    }

    fun setChecked(checked: Boolean) {
        if (checked == isChecked) return
        isChecked = checked
        refreshDescription()
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        animateTo(if (checked) 1f else 0f)
        onStateChanged?.invoke(checked)
    }

    private fun animateTo(target: Float) {
        animator?.cancel()
        settleAnimator?.cancel()
        val from = position
        animator = ValueAnimator.ofFloat(from, target).apply {
            duration = (90L + (kotlin.math.abs(target - from) * 190L)).toLong()
            interpolator = android.view.animation.DecelerateInterpolator(1.6f)
            addUpdateListener {
                position = it.animatedValue as Float
                settle = 0f
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    position = target
                    startSettle()
                }
            })
            start()
        }
    }

    private fun startSettle() {
        settleAnimator?.cancel()
        settleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 430L
            interpolator = android.view.animation.DecelerateInterpolator(1.2f)
            addUpdateListener {
                settle = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    settle = 0f
                    invalidate()
                }
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val dot = dp(24f)
        val pad = dp(4f)
        val radius = height / 2f
        val fromColor = Color.rgb(228, 228, 231)
        val toColor = context.getColor(R.color.colorPrimary)
        trackPaint.color = blend(fromColor, toColor, position)
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, trackPaint)

        val travel = width - dot - pad * 2
        val stretch = sin(Math.PI * position).toFloat()
        val damping = (1f - settle) * (1f - settle)
        val rebound = 0.15f * sin(3.0 * Math.PI * settle).toFloat() * damping
        val widthScale = 1f + 0.34f * stretch + rebound
        val heightScale = 1f - 0.18f * stretch - rebound
        val dotWidth = dot * widthScale
        val dotHeight = dot * heightScale
        val left = pad + position * travel
        val top = (height - dotHeight) / 2f

        canvas.drawCircle(left + dotWidth / 2f + dp(0.8f), top + dotHeight / 2f + dp(1f), dotWidth * 0.48f, shadowPaint)
        canvas.drawRoundRect(left, top, left + dotWidth, top + dotHeight, dotHeight / 2f, dotHeight / 2f, dotPaint)
    }

    private fun refreshDescription() {
        contentDescription = if (isChecked) "已开启" else "已关闭"
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = android.widget.Switch::class.java.name
        info.isCheckable = true
        info.isChecked = isChecked
    }

    private fun blend(from: Int, to: Int, fraction: Float): Int {
        val r = (Color.red(from) + (Color.red(to) - Color.red(from)) * fraction).toInt()
        val g = (Color.green(from) + (Color.green(to) - Color.green(from)) * fraction).toInt()
        val b = (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * fraction).toInt()
        return Color.rgb(r, g, b)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}

class HoldToConfirmLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    var onConfirmed: (() -> Unit)? = null
    var holdEnabled = true

    private var progress = 0f
    private var animator: ValueAnimator? = null
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = context.getColor(R.color.colorPrimary)
        alpha = 30
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = context.getColor(R.color.colorDivider)
        strokeWidth = dp(2.5f)
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = context.getColor(R.color.colorError)
        strokeWidth = dp(2.5f)
    }

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "按住确认清空全部历史"
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        return holdEnabled
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> if (holdEnabled) startHold()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (holdEnabled) cancelHold()
        }
        return holdEnabled
    }

    private fun startHold() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(progress, 1f).apply {
            duration = (300L + ((1f - progress) * 900L)).toLong()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (progress >= 0.995f) {
                        progress = 0f
                        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        onConfirmed?.invoke()
                    }
                    invalidate()
                }
            })
            start()
        }
    }

    private fun cancelHold() {
        animator?.cancel()
        animateBack()
    }

    private fun animateBack() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(progress, 0f).apply {
            duration = 180L
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val radius = dp(18f)
        val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        if (progress > 0f) {
            canvas.drawRoundRect(rect, radius, radius, fillPaint.apply { alpha = (45 + 45 * progress).toInt() })
        }

        val iconCenterX = dp(26f)
        val iconRadius = dp(17f)
        val circle = RectF(
            iconCenterX - iconRadius, height / 2f - iconRadius,
            iconCenterX + iconRadius, height / 2f + iconRadius
        )
        canvas.drawArc(circle, 0f, 360f, false, trackPaint)
        if (progress > 0f) {
            canvas.drawArc(circle, -90f, 360f * progress, false, progressPaint)
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
