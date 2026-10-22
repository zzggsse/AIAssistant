package com.aibutler.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Point
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.animation.ValueAnimator
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 用户对任务结果的人工判定：勾=记为成功经验，叉=记为失败经验（AI 下次避开）
 */
enum class TaskVerdict { CONFIRMED, MARK_FAILED }

/** 停止悬浮球的点击/拖动边界：非拖动的抬手都视为用户要打开确认窗。 */
object StopBubbleGesturePolicy {
    fun shouldTogglePopupOnActionUp(dragging: Boolean): Boolean = !dragging
}

/**
 * 无障碍服务：AI 的"眼睛和手指"。
 * - dumpScreen()：读取当前屏幕上的文字
 * - clickText()：点击包含指定文字的元素
 * - inputText()：往输入框里打字
 * - pressBack() / scroll()：返回键、滑动
 * 需要用户在系统设置中开启本服务。
 */
class AssistantAccessibilityService : AccessibilityService() {

    companion object {
        var instance: AssistantAccessibilityService? = null
        private const val STOP_BUBBLE_IDLE_COLLAPSE_MS = 5000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var taskOverlayRoot: View? = null
    private var taskOverlayParams: WindowManager.LayoutParams? = null
    private var taskPopupRoot: View? = null
    private var stopBubbleOnRightEdge = true
    private var stopBubbleExpanded = false
    private var stopBubbleCollapseRunnable: Runnable? = null
    private var successOverlayRoot: View? = null
    private var successConfirmLatch: CountDownLatch? = null
    private var actionPreviewRoot: ActionTargetView? = null

    /** 任务开始时调用：在屏幕边缘挂一个可收进侧边的「停止任务」气泡 */
    fun showTaskControls() {
        mainHandler.post {
            taskOverlayRoot?.let(::removeFromOverlay)
            hideStopPopup()
            cancelStopBubbleCollapse()
            stopBubbleExpanded = false
            val root = buildStopBubble()
            taskOverlayRoot = root
            val params = addToOverlay(root) {
                gravity = Gravity.TOP or Gravity.START
                width = dp(44)
                height = dp(44)
                flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                y = dp(92)
            }
            taskOverlayParams = params
            if (params == null) {
                taskOverlayRoot = null
            } else {
                attachStopBubbleTouch(root, params)
            }
        }
    }

    /** 任务结束时调用：悬浮球和确认小窗一起收掉 */
    fun hideTaskControls() {
        mainHandler.post {
            cancelStopBubbleCollapse()
            taskOverlayRoot?.let(::removeFromOverlay)
            taskOverlayRoot = null
            taskOverlayParams = null
            hideStopPopup()
        }
    }

    private fun cancelStopBubbleCollapse() {
        stopBubbleCollapseRunnable?.let(mainHandler::removeCallbacks)
        stopBubbleCollapseRunnable = null
    }

    /** 任务结束的人工确认：返回勾/叉判定；null=不记录或超时未答 */
    fun showSuccessConfirmation(task: String, result: String): TaskVerdict? {
        val latch = CountDownLatch(1)
        var verdict: TaskVerdict? = null
        mainHandler.post {
            successOverlayRoot?.let(::removeFromOverlay)
            successOverlayRoot = buildSuccessConfirmation(task, result) { value ->
                if (verdict == null) verdict = value
                latch.countDown()
            }
            successOverlayRoot?.let { addToOverlay(it, modal = true) }
            hideTaskControls()
        }
        successConfirmLatch = latch
        return try {
            latch.await(5, TimeUnit.MINUTES)
            verdict
        } catch (_: InterruptedException) {
            null
        } finally {
            if (successConfirmLatch === latch) successConfirmLatch = null
            mainHandler.post {
                successOverlayRoot?.let(::removeFromOverlay)
                successOverlayRoot = null
            }
        }
    }

    /** 任务执行期间常驻的「停止任务」圆形气泡：贴边时只露出一半 */
    private fun buildStopBubble(): View {
        val inset = dp(12)
        return FrameLayout(this).apply {
            clipToOutline = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E6FF4D4F"))
            }
            elevation = dp(6).toFloat()
            contentDescription = "停止当前任务"
            addView(
                ImageView(context).apply {
                    setImageResource(R.drawable.ic_stop)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ).apply { setMargins(inset, inset, inset, inset) }
            )
        }
    }

    /** 单击立即打开确认窗；拖动只改位置，五秒无操作自动收回一半 */
    private fun attachStopBubbleTouch(view: View, params: WindowManager.LayoutParams) {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false

        fun update() {
            runCatching { wm.updateViewLayout(view, params) }
        }

        view.setOnTouchListener { _, event ->
            val (screenW, screenH) = realScreenSize()
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    dragging = false
                    cancelStopBubbleCollapse()
                    if (!stopBubbleExpanded) {
                        expandStopBubble(view, params, screenW, screenH)
                    }
                    startX = params.x
                    startY = params.y
                    update()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && (abs(event.rawX - downRawX) > slop ||
                                abs(event.rawY - downRawY) > slop)
                    ) dragging = true
                    if (dragging) {
                        // 拖动期间完整显示；松手后保留当前位置，由无操作定时器负责收回
                        params.x = (startX + (event.rawX - downRawX)).toInt()
                            .coerceIn(-params.width / 2, screenW - params.width / 2)
                        params.y = (startY + (event.rawY - downRawY)).toInt()
                            .coerceIn(0, screenH - params.height)
                        update()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (StopBubbleGesturePolicy.shouldTogglePopupOnActionUp(dragging)) {
                        toggleStopPopup()
                    }
                    scheduleStopBubbleCollapse()
                    dragging = false
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    scheduleStopBubbleCollapse()
                    dragging = false
                    true
                }
                else -> false
            }
        }
    }

    private fun expandStopBubble(
        view: View,
        params: WindowManager.LayoutParams,
        screenW: Int,
        screenH: Int
    ) {
        stopBubbleExpanded = true
        params.x = if (stopBubbleOnRightEdge) screenW - params.width else 0
        params.y = params.y.coerceIn(0, screenH - params.height)
        runCatching {
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                .updateViewLayout(view, params)
        }
    }

    private fun scheduleStopBubbleCollapse() {
        cancelStopBubbleCollapse()
        val action = Runnable {
            stopBubbleCollapseRunnable = null
            hideStopPopup()
            val view = taskOverlayRoot
            val params = taskOverlayParams
            if (view != null && params != null) {
                collapseStopBubbleToEdge(view, params)
            }
        }
        stopBubbleCollapseRunnable = action
        mainHandler.postDelayed(action, STOP_BUBBLE_IDLE_COLLAPSE_MS)
    }

    private fun collapseStopBubbleToEdge(view: View, params: WindowManager.LayoutParams) {
        val (screenW, screenH) = realScreenSize()
        // 收回前按当前位置重新选边，从哪边拖过来就收进哪边
        stopBubbleOnRightEdge = params.x + params.width / 2 > screenW / 2
        stopBubbleExpanded = false
        params.x = if (stopBubbleOnRightEdge) screenW - params.width / 2 else -params.width / 2
        params.y = params.y.coerceIn(-params.height / 2, screenH - params.height / 2)
        runCatching {
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                .updateViewLayout(view, params)
        }
    }

    /** 点悬浮球弹出的小窗：再点一次悬浮球或点「继续执行」可收起 */
    private fun toggleStopPopup() {
        if (taskPopupRoot != null) {
            hideStopPopup()
        } else {
            val popup = buildTaskStopPopup()
            taskPopupRoot = popup
            addToOverlay(popup) {
                gravity = Gravity.CENTER
            }
        }
    }

    private fun hideStopPopup() {
        taskPopupRoot?.let(::removeFromOverlay)
        taskPopupRoot = null
    }

    /** 停止确认小窗：确认后走和聊天页「停止」按钮一样的暂停通道 */
    private fun buildTaskStopPopup(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(14))
            background = roundedBackground(Color.WHITE, dp(16).toFloat())
            elevation = dp(10).toFloat()
        }
        card.addView(TextView(this).apply {
            text = "⏹ 任务执行中"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#171717"))
        })
        card.addView(TextView(this).apply {
            text = "要停止当前任务吗？停止后 AI 会在当前步骤完成后停下；想接着做可以在小助手里说「继续」。"
            textSize = 14f
            setTextColor(Color.parseColor("#555555"))
            setPadding(0, dp(8), 0, 0)
        })
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(14), 0, 0)
        }
        val cont = TextView(this).apply {
            text = "继续执行"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#1677FF"))
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener {
                hideStopPopup()
                scheduleStopBubbleCollapse()
            }
        }
        val stop = TextView(this).apply {
            text = "⏹ 停止任务"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = roundedBackground(Color.parseColor("#FF4D4F"), dp(18).toFloat())
            setOnClickListener {
                TaskControlBus.requestPause()
                hideStopPopup()
            }
        }
        buttons.addView(cont)
        buttons.addView(stop, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(10) })
        card.addView(buttons)
        return card
    }

    private fun buildSuccessConfirmation(
        task: String,
        result: String,
        onAnswer: (TaskVerdict?) -> Unit
    ): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            setPadding(dp(18), dp(18), dp(18), dp(16))
            background = roundedBackground(Color.WHITE, dp(16).toFloat())
        }

        card.addView(TextView(this).apply {
            text = "确认任务结果"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#171717"))
        })

        card.addView(TextView(this).apply {
            text = "✓ 记为成功经验；✗ 记为失败经验（AI 下次避开这种走法）；两条经验都会帮 AI 变聪明，不想留就选「不记录」。"
            textSize = 14f
            setTextColor(Color.parseColor("#555555"))
            setPadding(0, dp(10), 0, 0)
        })

        card.addView(TextView(this).apply {
            text = "任务：${task.take(80)}"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#222222"))
            setPadding(0, dp(12), 0, 0)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        })

        card.addView(TextView(this).apply {
            text = "结果：${result.take(120)}"
            textSize = 13f
            setTextColor(Color.parseColor("#666666"))
            setPadding(0, dp(6), 0, 0)
            maxLines = 4
            ellipsize = android.text.TextUtils.TruncateAt.END
        })

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
        }
        val good = TextView(this).apply {
            text = "✓ 正确，记录"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = roundedBackground(Color.parseColor("#52C41A"), dp(18).toFloat())
            setOnClickListener { onAnswer(TaskVerdict.CONFIRMED) }
        }
        val bad = TextView(this).apply {
            text = "✗ 错误，记失败"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = roundedBackground(Color.parseColor("#FF4D4F"), dp(18).toFloat())
            setOnClickListener { onAnswer(TaskVerdict.MARK_FAILED) }
        }
        buttons.addView(good)
        buttons.addView(bad, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(12) })
        card.addView(buttons)

        card.addView(TextView(this).apply {
            text = "不记录"
            textSize = 13f
            setTextColor(Color.parseColor("#999999"))
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
            setOnClickListener { onAnswer(null) }
        })

        card.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
            marginStart = dp(22)
            marginEnd = dp(22)
        }

        return FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#66000000"))
            setOnClickListener { onAnswer(null) }
            addView(card)
        }
    }

    fun addToOverlay(
        view: View,
        modal: Boolean = false,
        focusable: Boolean = false,
        setupParams: (WindowManager.LayoutParams.() -> Unit)? = null
    ): WindowManager.LayoutParams? {
        if (view.parent != null) return null
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            format = android.graphics.PixelFormat.TRANSLUCENT
            if (!focusable) flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            if (!modal) {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                width = ViewGroup.LayoutParams.WRAP_CONTENT
                height = ViewGroup.LayoutParams.WRAP_CONTENT
            } else {
                gravity = Gravity.CENTER
                width = WindowManager.LayoutParams.MATCH_PARENT
                height = WindowManager.LayoutParams.MATCH_PARENT
            }
            setupParams?.invoke(this)
        }
        return try {
            wm.addView(view, params)
            params
        } catch (e: Exception) {
            android.util.Log.e("AIButler", "addToOverlay failed: type=${params.type}, flags=${params.flags}, view=${view.javaClass.simpleName}", e)
            null
        }
    }

    private fun removeFromOverlay(view: View) {
        try {
            if (view.parent != null) {
                (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(view)
            }
        } catch (_: Exception) {
        }
    }

    fun removeFromOverlayPublic(view: View) = removeFromOverlay(view)

    /** 当前页面里适合作为图形按钮样本的可见区域，按“更像小按钮”的顺序返回。 */
    fun collectClickableRects(): List<Rect> {
        val roots = LinkedHashSet<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { roots.add(it) }
        try {
            windows?.forEach { window ->
                window.root?.let { root ->
                    if (root.packageName != packageName) roots.add(root)
                }
            }
        } catch (_: Exception) {
        }
        val (screenWidth, screenHeight) = realScreenSize()
        val output = ArrayList<Rect>()
        val seenCenters = HashSet<String>()

        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || output.size >= 80) return
            if (node.isClickable) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                val visible = !rect.isEmpty &&
                    rect.left >= 0 && rect.top >= 0 &&
                    rect.right <= screenWidth && rect.bottom <= screenHeight &&
                    rect.width() >= 20 && rect.height() >= 20 &&
                    rect.width() <= screenWidth * 9 / 10 &&
                    rect.height() <= screenHeight * 5 / 10
                val centerKey = "${rect.exactCenterX().toInt()},${rect.exactCenterY().toInt()}"
                if (visible && seenCenters.add(centerKey)) output.add(rect)
            }
            for (index in 0 until node.childCount) {
                try {
                    walk(node.getChild(index))
                } catch (_: Exception) {
                }
            }
        }
        roots.forEach(::walk)
        output.sortWith(
            compareByDescending<Rect> { rect ->
                rect.width() * rect.height() / maxOf(1, maxOf(rect.width(), rect.height()))
            }.thenBy { it.top }.thenBy { it.left }
        )
        return output
    }

    fun captureForSamples(): Bitmap? = CaptureService.capture()

    private fun roundedBackground(color: Int, radiusPx: Float): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radiusPx
        }
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceInfo = serviceInfo.apply {
            eventTypes = eventTypes or AccessibilityEvent.TYPE_VIEW_SCROLLED
        }
        LearningModeController(this).also {
            LearningModeController.instance = it
        }
        VisualSampleController(this).also {
            VisualSampleController.instance = it
        }
        if (TaskControlBus.taskRunning) showTaskControls()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        LearningModeController.instance?.stop()
        LearningModeController.instance = null
        VisualSampleController.instance?.hide()
        VisualSampleController.instance = null
        taskOverlayRoot?.let(::removeFromOverlay)
        taskOverlayRoot = null
        taskOverlayParams = null
        hideActionPreview()
        taskPopupRoot?.let(::removeFromOverlay)
        taskPopupRoot = null
        successOverlayRoot?.let(::removeFromOverlay)
        successOverlayRoot = null
        successConfirmLatch?.countDown()
        successConfirmLatch = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        LearningModeController.instance?.onAccessibilityEvent(event)
    }

    override fun onInterrupt() {}

    fun realScreenSize(): Pair<Int, Int> {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            return Pair(bounds.width(), bounds.height())
        }
        @Suppress("DEPRECATION")
        val point = Point().also { windowManager.defaultDisplay.getRealSize(it) }
        return Pair(point.x, point.y)
    }

    /**
     * 执行手势前，在目标位置显示短暂的高亮框和动作标签。
     * 这层只做展示，不拦截触摸，因此不会影响被控应用或后续手势。
     */
    fun showActionPreview(
        x: Float,
        y: Float,
        label: String = "",
        action: String = "点击",
        durationMs: Long = 650
    ) {
        val latch = CountDownLatch(1)
        mainHandler.post {
            actionPreviewRoot?.let(::removeFromOverlay)
            val preview = ActionTargetView(this).apply {
                setTarget(x, y, label, action)
            }
            actionPreviewRoot = preview
            val added = addToOverlay(preview, modal = false) {
                gravity = Gravity.TOP or Gravity.START
                width = WindowManager.LayoutParams.MATCH_PARENT
                height = WindowManager.LayoutParams.MATCH_PARENT
                flags = flags or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            }
            if (added == null) {
                actionPreviewRoot = null
            }
            latch.countDown()
            mainHandler.postDelayed({
                if (actionPreviewRoot === preview) {
                    removeFromOverlay(preview)
                    actionPreviewRoot = null
                }
            }, durationMs)
        }
        try {
            latch.await(1, TimeUnit.SECONDS)
            Thread.sleep(durationMs)
        } catch (_: Exception) {
        }
    }

    fun hideActionPreview() {
        mainHandler.post {
            actionPreviewRoot?.let(::removeFromOverlay)
            actionPreviewRoot = null
        }
    }

    /** 最近一次读屏的元素坐标表，供 click_number 按编号点击 */
    private val lastElements = ArrayList<Rect>()

    /** 最近一次读屏的元素标签表，用于安全拦截判断 */
    private val lastLabels = ArrayList<String>()

    /** 只取当前应用包名，避免为了包名做一次完整读屏 */
    fun currentPackage(): String {
        return try {
            rootInActiveWindow?.packageName?.toString()
                ?: windows?.asSequence()
                    ?.mapNotNull { it.root?.packageName?.toString() }
                    ?.firstOrNull { it != packageName && !it.startsWith("com.android.systemui") }
                ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }
    }

    /**
     * 任务收尾时回到助手对话。这里必须由无障碍服务上下文发起：
     * 服务由系统绑定，比普通后台 Activity/线程更有资格绕过后台启动限制。
     */
    fun returnToConversation(fromPackage: String?, target: Class<*>) {
        if (fromPackage == packageName) return
        mainHandler.postDelayed({
            runCatching {
                startActivity(
                    android.content.Intent(this, target)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                  android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
            }.onFailure {
                android.util.Log.w("AIButler", "TASK finish return service failed", it)
            }
        }, 120L)
    }

    /** 从当前页面无障碍节点树生成稳定指纹（包名 + resource-id + 可点击元素文本，排序后取 MD5） */
    fun pageFingerprint(): String {
        val root = rootInActiveWindow ?: return ""
        val pkg = root.packageName?.toString() ?: return ""
        val ids = LinkedHashSet<String>()
        val labels = LinkedHashSet<String>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || (ids.size >= 20 && labels.size >= 15)) return
            n.viewIdResourceName?.let { if (it.isNotBlank()) ids.add(it) }
            if (n.isClickable) {
                val label = n.text?.toString()?.trim()?.take(30)
                    ?: n.contentDescription?.toString()?.trim()?.take(30)
                if (!label.isNullOrBlank()) labels.add(label)
            }
            if (depth < 6) {
                for (i in 0 until n.childCount) {
                    try { walk(n.getChild(i), depth + 1) } catch (_: Exception) {}
                }
            }
        }
        walk(root, 0)
        val sb = StringBuilder()
        ids.sorted().forEach { sb.append("|id:").append(it) }
        labels.sorted().forEach { sb.append("|lbl:").append(it) }
        return PathMemoryStore.fingerprint(pkg, sb)
    }

    /** 获取当前页面指纹和包名的组合，用于录制和回放校验 */
    fun currentPageFingerprint(): Pair<String, String> {
        return Pair(pageFingerprint(), currentPackage())
    }

    /** 读取当前屏幕内容：输出带编号的可交互元素清单（AI 的结构化视野） */
    fun dumpScreen(): String {
        lastElements.clear()
        lastLabels.clear()
        val root = rootInActiveWindow
            ?: return "无法读取屏幕。可能原因：无障碍服务未开启，或当前页面禁止读取。请提示用户检查。"
        val sb = StringBuilder()
        sb.append("当前应用: ${root.packageName}\n")
        var idx = 0
        val (sw, sh) = realScreenSize()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || idx >= 130 || sb.length > 9500) return
            val text = n.text?.toString()?.trim()
            val desc = n.contentDescription?.toString()?.trim()
            val r = Rect()
            n.getBoundsInScreen(r)
            val onScreen = !r.isEmpty && r.top >= 0 && r.left >= 0 && r.top < sh && r.left < sw
            val interesting = (!text.isNullOrBlank()) || (!desc.isNullOrBlank()) || n.isClickable || n.isEditable
            if (onScreen && interesting) {
                idx++
                val cx = r.exactCenterX().toInt()
                val cy = r.exactCenterY().toInt()
                lastElements.add(Rect(cx - 1, cy - 1, cx + 1, cy + 1))
                val elemLabel = buildString {
                    if (!text.isNullOrBlank()) append(text)
                    if (!desc.isNullOrBlank()) { if (isNotEmpty()) append(" "); append(desc) }
                }
                lastLabels.add(elemLabel)
                val sb2 = StringBuilder("[$idx]")
                if (n.isClickable) sb2.append("[可点]")
                if (n.isEditable) sb2.append("[输入框]")
                if (n.isScrollable) sb2.append("[可滑动]")
                if (!text.isNullOrBlank()) sb2.append(" 文字:$text")
                if (!desc.isNullOrBlank()) sb2.append(" 图标说明:$desc")
                if (text.isNullOrBlank() && desc.isNullOrBlank()) sb2.append(" (无文字元素)")
                sb2.append(" 坐标:$cx,$cy")
                sb.append(sb2).append('\n')
            }
            for (i in 0 until n.childCount) {
                try { walk(n.getChild(i), depth + 1) } catch (e: Exception) { }
            }
        }
        walk(root, 0)
        if (idx == 0) sb.append("(没读到任何元素，可能页面还在加载，建议 wait_seconds 3 后重新读屏)\n")
        return sb.toString().take(9000)
    }

    /** 读取当前页面概要；坐标换成 AutoGLM 协议使用的 0-999，便于视觉脑直接点击 */
    fun dumpScreenForAutoGLM(): String {
        val root = rootInActiveWindow
            ?: return "无法读取当前页面（无障碍服务未开启，或页面禁止读取）"
        val (realScreenWidth, realScreenHeight) = realScreenSize()
        val sw = realScreenWidth.toFloat()
        val sh = realScreenHeight.toFloat()

        fun descendantLabel(n: AccessibilityNodeInfo?): String {
            if (n == null) return ""
            val own = sequenceOf(n.text?.toString()?.trim().orEmpty(), n.contentDescription?.toString()?.trim().orEmpty())
                .firstOrNull { it.isNotBlank() } ?: ""
            if (own.isNotBlank()) return own
            for (i in 0 until n.childCount) {
                try {
                    val childLabel = descendantLabel(n.getChild(i))
                    if (childLabel.isNotBlank()) return childLabel
                } catch (_: Exception) { }
            }
            return ""
        }

        fun collect(n: AccessibilityNodeInfo?, items: ArrayList<String>, pageText: StringBuilder): Int {
            var scrollableCount = 0
            if (n == null || items.size >= 140) return scrollableCount
            val text = n.text?.toString()?.trim().orEmpty()
            val desc = n.contentDescription?.toString()?.trim().orEmpty()
            val r = Rect()
            n.getBoundsInScreen(r)
            val visible = !r.isEmpty && r.top >= 0 && r.left >= 0 && r.top < sh && r.left < sw
            if (visible) {
                if ((text.isNotBlank() || desc.isNotBlank()) && !n.isClickable && !n.isEditable) {
                    if (pageText.length < 1200) {
                        pageText.append(text.ifBlank { desc }.take(80)).append(" / ")
                    }
                }
                if (n.isClickable || n.isEditable || n.isScrollable) {
                    if (n.isScrollable) scrollableCount++
                    val x = ((r.exactCenterX() / sw) * 999).toInt().coerceIn(0, 999)
                    val y = ((r.exactCenterY() / sh) * 999).toInt().coerceIn(0, 999)
                    val kind = when {
                        n.isEditable -> "输入框"
                        n.isScrollable -> "可滑动"
                        else -> "可点击"
                    }
                    val baseLabel = when {
                        text.isNotBlank() -> text
                        desc.isNotBlank() -> desc
                        n.isClickable -> descendantLabel(n).ifBlank { "无文字元素" }
                        else -> "无文字元素"
                    }
                    // 控件身份提示：resource-id 和类名能揭示无文字控件的真实用途（如 btn_open_chest）
                    val meta = buildString {
                        append(" {")
                        val id = n.viewIdResourceName
                        if (!id.isNullOrBlank()) append(id.substringAfterLast('/', id))
                        n.className?.toString()?.substringAfterLast('.')?.let {
                            if (isNotEmpty()) append(",")
                            append(it)
                        }
                        append("}")
                    }
                    val label = baseLabel.take(36) + meta
                    items.add("$kind: $label [$x,$y]")
                }
            }
            for (i in 0 until n.childCount) {
                try { scrollableCount += collect(n.getChild(i), items, pageText) } catch (_: Exception) { }
            }
            return scrollableCount
        }

        val roots = LinkedHashSet<AccessibilityNodeInfo>()
        roots.add(root)
        try {
            windows?.forEach { window -> window.root?.let { if (it.packageName == root.packageName) roots.add(it) } }
        } catch (_: Exception) { }

        data class WindowDump(val root: AccessibilityNodeInfo, val items: List<String>, val pageText: String, val scrollableCount: Int)
        val dumps = roots.map { windowRoot ->
            val items = ArrayList<String>()
            val pageText = StringBuilder()
            val scrollableCount = collect(windowRoot, items, pageText)
            WindowDump(windowRoot, items, pageText.toString(), scrollableCount)
        }
        val activeDump = dumps.first { it.root === root }
        val selected = dumps.lastOrNull {
            it !== activeDump && it.items.isNotEmpty() && it.root.packageName == root.packageName
        } ?: activeDump
        val orderedDumps = listOf(selected) + dumps.filter {
            it !== selected && it.root.packageName == root.packageName
        }
        val collectedItems = orderedDumps.flatMap { it.items }.distinct()
        val bottomNavLabels = setOf(
            "首页", "朋友", "消息", "我", "我的", "视频", "商城", "任务",
            "赚钱", "去赚钱", "会员", "发现", "小团"
        )
        val bottomNavItems = collectedItems.filter { item ->
            val match = Regex("^[^:]+:\\s*(.+?)\\s*\\[(-?\\d+),(-?\\d+)\\]$").find(item)
            match != null &&
                match.groupValues[2].toInt() in 40..960 &&
                match.groupValues[3].toInt() >= 840 &&
                match.groupValues[1] in bottomNavLabels
        }
        val orderedItems = bottomNavItems + collectedItems.filter { it !in bottomNavItems }
        val items = orderedItems
        val pageText = StringBuilder(orderedDumps.joinToString(" / ") { it.pageText })
        val scrollableCount = orderedDumps.maxOfOrNull { it.scrollableCount } ?: 0
        val scrollHint = when {
            scrollableCount > 0 -> "当前屏幕检测到${scrollableCount}个可滑动区域；如果目标不可见，先向下滑动查看完整内容"
            else -> "当前屏幕未检测到明确可滑动区域；优先点击可见目标，不要盲目滑动"
        }
        if (items.isEmpty()) return "当前应用: ${root.packageName}\n$scrollHint\n页面内容: ${pageText.toString().take(1200)}\n页面可能仍在加载；建议等待1-2秒后重新观察"
        return ("当前应用: ${root.packageName}\n$scrollHint\n页面内容: ${pageText.toString().take(900)}\n关键元素(坐标0-999):\n" +
            orderedItems.joinToString("\n")).take(6500)
    }

    /** 读取某个0-999坐标附近的文字/描述，用于执行前拦截疑似跳转入口 */
    fun labelAtNormalized(nx: Int, ny: Int): String {
        val root = rootInActiveWindow ?: return ""
        val (screenWidth, screenHeight) = realScreenSize()
        val x = ((nx.coerceIn(0, 999) / 999.0) * screenWidth).toInt()
        val y = ((ny.coerceIn(0, 999) / 999.0) * screenHeight).toInt()
        val labels = LinkedHashSet<String>()

        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || labels.size >= 30) return
            val r = Rect()
            n.getBoundsInScreen(r)
            if (r.contains(x, y)) {
                n.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { labels.add(it.take(60)) }
                n.contentDescription?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { labels.add(it.take(60)) }
            }
            for (i in 0 until n.childCount) {
                try { walk(n.getChild(i)) } catch (_: Exception) { }
            }
        }

        val roots = LinkedHashSet<AccessibilityNodeInfo>()
        roots.add(root)
        try {
            windows?.forEach { window -> window.root?.let { if (it.packageName == root.packageName) roots.add(it) } }
        } catch (_: Exception) { }
        roots.forEach(::walk)
        return labels.joinToString(" / ").take(300)
    }

    /** 获取最近一次读屏中某个编号的元素文字/描述，用于安全拦截判断 */
    fun elementLabelAt(num: Int): String {
        if (num < 1 || num > lastLabels.size) return ""
        return lastLabels[num - 1]
    }

    /** 获取某个像素坐标附近的元素文字/描述，用于 tap_point 安全拦截 */
    fun labelAtPixel(x: Int, y: Int): String {
        val root = rootInActiveWindow ?: return ""
        val labels = LinkedHashSet<String>()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || labels.size >= 20) return
            val r = Rect()
            n.getBoundsInScreen(r)
            if (r.contains(x, y)) {
                n.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { labels.add(it.take(60)) }
                n.contentDescription?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { labels.add(it.take(60)) }
            }
            for (i in 0 until n.childCount) {
                try { walk(n.getChild(i)) } catch (_: Exception) { }
            }
        }
        val roots = LinkedHashSet<AccessibilityNodeInfo>()
        roots.add(root)
        try { windows?.forEach { window -> window.root?.let { roots.add(it) } } } catch (_: Exception) { }
        roots.forEach(::walk)
        return labels.joinToString(" / ").take(200)
    }

    /** 按最近一次读屏的编号点击（支持无文字的小图标） */
    fun clickNumber(num: Int): String {
        if (num < 1 || num > lastElements.size)
            return "编号$num 不存在（上次读屏只有 ${lastElements.size} 个元素）。请先 get_screen_state 重新读屏"
        val r = lastElements[num - 1]
        val x = r.left + 1
        val y = r.top + 1
        return if (tap(x.toFloat(), y.toFloat()))
            "已点击第${num}个元素（坐标 $x,$y）"
        else "点击动作发送失败"
    }

    private fun collectNodes(match: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val root = rootInActiveWindow ?: return emptyList()
        val out = ArrayList<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty() && out.size < 40) {
            val n = queue.removeFirst()
            try {
                if (match(n)) out.add(n)
                for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
            } catch (e: Exception) { }
        }
        return out
    }

    private fun nodeLabel(n: AccessibilityNodeInfo): String =
        (n.text?.toString() ?: "") + "|" + (n.contentDescription?.toString() ?: "")

    /** 点击屏幕上包含指定文字的元素 */
    fun clickText(query: String): String {
        val q = query.trim()
        // 空参数曾会命中 contains("") 恒真，点中任意第一个可点元素——直接拒绝
        if (q.isEmpty()) return "点击目标为空，已拒绝点击。请先 get_screen_state 确认目标按钮的文字"
        val nodes = collectNodes {
            nodeLabel(it).contains(q, ignoreCase = true)
        }
        if (nodes.isEmpty()) return "屏幕上没找到「$query」，建议先调用 get_screen_state 看看当前屏幕有什么。"

        // 优先点击本身可点击的节点，其次向上找可点击父节点，最后用手势坐标点
        for (n in nodes) {
            if (n.isClickable) {
                val r = Rect()
                n.getBoundsInScreen(r)
                if (!r.isEmpty) showActionPreview(r.exactCenterX(), r.exactCenterY(), query)
                if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "已点击「$query」"
            }
        }
        for (n in nodes) {
            var p: AccessibilityNodeInfo? = n
            var hops = 0
            while (p != null && !p.isClickable && hops < 6) { p = p.parent; hops++ }
            if (p != null && p.isClickable) {
                val r = Rect()
                p.getBoundsInScreen(r)
                if (!r.isEmpty) showActionPreview(r.exactCenterX(), r.exactCenterY(), query)
                if (p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return "已点击「$query」（点的是它的容器）"
                }
            }
        }
        // 兜底坐标点击前先校验元素可见：collectNodes 是全树搜索，可能拿到屏外/零尺寸节点，
        // 点不可见元素却报成功会误导后续决策
        val n = nodes.first()
        val r = Rect()
        n.getBoundsInScreen(r)
        val screen = Rect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        if (r.isEmpty || !Rect.intersects(r, screen)) return "目标元素当前不在屏幕可见范围内，已跳过点击"
        tap(r.exactCenterX(), r.exactCenterY())
        return "已用坐标点击「$query」"
    }

    /** 精确点击锁屏 PIN 数字键盘上的某个数字（只匹配完全相等的文字/描述） */
    fun clickPinDigit(d: Char): String {
        val ds = d.toString()
        val nodes = collectNodes {
            nodeLabel(it).split('|').any { part -> part.trim() == ds }
        }
        val n = nodes.firstOrNull { it.isClickable } ?: nodes.firstOrNull()
            ?: return "键盘上没找到数字$ds"
        if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return "已按$ds"
        }
        val r = Rect()
        n.getBoundsInScreen(r)
        if (r.isEmpty) return "数字$ds 位置异常"
        tap(r.exactCenterX(), r.exactCenterY())
        return "已按$ds（坐标点击）"
    }

    /** 手势点击坐标 */
    fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /** 往当前屏幕的输入框输入文字 */
    fun inputText(text: String): String {
        val edits = collectNodes {
            it.isEditable || it.className?.toString() == "android.widget.EditText"
        }
        if (edits.isEmpty()) return "当前屏幕没有找到输入框。建议先点开输入区域（click_element）再输入。"
        val target = edits.firstOrNull { it.isFocused } ?: edits.first()
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            return "已在输入框填入：「$text」（注意：还没点发送）"
        }
        // 兜底：用剪贴板粘贴。粘贴失败要如实上报，否则模型以为输入成功会直接点发送
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("ai", text))
        val pasted = target.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        return if (pasted) "已尝试粘贴输入：「$text」（注意：还没点发送）"
        else "粘贴输入失败，请人工检查输入框内容是否为「$text」"
    }

    /** 点击"发送"等按钮时经常需要：输入完由 AI 再调 click_element(发送) */
    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    /** AutoGLM用：任意两点滑动 */
    fun swipePts(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long = 500): Boolean {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /** AutoGLM用：长按 */
    fun longPress(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 900))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun scroll(down: Boolean): String {
        val (screenWidth, screenHeight) = realScreenSize()
        val cx = screenWidth / 2f
        val from = if (down) screenHeight * 0.70f else screenHeight * 0.35f
        val to = if (down) screenHeight * 0.35f else screenHeight * 0.70f
        val path = Path().apply { moveTo(cx, from); lineTo(cx, to) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 350))
            .build()
        dispatchGesture(gesture, null, null)
        return if (down) "已向下滑动屏幕" else "已向上滑动屏幕"
    }

    /** 左右滑动：right=true 表示内容向右移（等价于看左边的Tab/上一张图） */
    fun scrollHorizontal(right: Boolean): String {
        val (screenWidth, screenHeight) = realScreenSize()
        val cy = screenHeight / 2f
        val from = if (right) screenWidth * 0.25f else screenWidth * 0.75f
        val to = if (right) screenWidth * 0.75f else screenWidth * 0.25f
        val path = Path().apply { moveTo(from, cy); lineTo(to, cy) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 350))
            .build()
        dispatchGesture(gesture, null, null)
        return if (right) "已向右滑动屏幕" else "已向左滑动屏幕"
    }

    fun home(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    private class ActionTargetView(context: Context) : View(context) {
        private var targetX = 0f
        private var targetY = 0f
        private var targetLabel = ""
        private var actionName = "点击"
        private var startTime = 0L
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12f, resources.displayMetrics)
            typeface = Typeface.DEFAULT_BOLD
        }
        private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 650
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            addUpdateListener { invalidate() }
        }

        fun setTarget(x: Float, y: Float, label: String, action: String) {
            targetX = x
            targetY = y
            targetLabel = label.replace(Regex("\\s+"), " ").trim().take(28)
            actionName = action
            startTime = System.currentTimeMillis()
            animator.start()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val time = ((System.currentTimeMillis() - startTime) % 650L) / 650f
            val density = resources.displayMetrics.density
            val base = minOf(width, height).toFloat() * 0.09f
            val radius = base * (0.75f + time * 0.55f)
            paint.style = android.graphics.Paint.Style.STROKE
            paint.strokeWidth = density * 2.5f
            paint.color = (((1f - time) * 180).toInt().shl(24)) or 0x1677FF
            canvas.drawCircle(targetX, targetY, radius, paint)

            paint.style = android.graphics.Paint.Style.FILL
            paint.color = Color.parseColor("#E61677FF")
            canvas.drawCircle(targetX, targetY, density * 3.5f, paint)

            if (targetLabel.isNotBlank()) {
                val text = "$actionName：$targetLabel"
                val textWidth = textPaint.measureText(text)
                val left = (targetX - textWidth / 2f - density * 9f)
                    .coerceIn(density * 8f, width.toFloat() - textWidth - density * 26f)
                val top = (targetY - base - density * 36f)
                    .coerceIn(density * 12f, height.toFloat() - density * 46f)
                val right = left + textWidth + density * 18
                val bottom = top + density * 27
                paint.color = Color.parseColor("#CC111827")
                canvas.drawRoundRect(left, top, right, bottom, density * 13, density * 13, paint)
                canvas.drawText(text, left + density * 9, bottom - density * 8, textPaint)
            }
        }

        override fun onDetachedFromWindow() {
            animator.cancel()
            super.onDetachedFromWindow()
        }
    }
}
