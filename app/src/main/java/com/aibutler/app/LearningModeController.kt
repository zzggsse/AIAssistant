package com.aibutler.app

import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.UUID
import kotlin.math.roundToInt

class LearningModeController(private val service: AssistantAccessibilityService) {

    companion object {
        var instance: LearningModeController? = null

        private val sensitivePattern = Regex(
            "支付|付款|转账|密码|验证码|删除|卸载|确认订单|提交订单|立即购买|去支付|身份证",
            RegexOption.IGNORE_CASE
        )
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val recordedSteps = ArrayList<LearningStep>()
    private val stepsLock = Any()

    @Volatile
    private var recording = false

    private var overlayRoot: LinearLayout? = null
    private var overlayTitle: TextView? = null
    private var overlayActionButton: TextView? = null
    private var targetLocked = false
    private var pendingClickStep: LearningStep? = null
    private var pendingClickRunnable: Runnable? = null
    private var pendingInputStep: LearningStep? = null
    private var pendingInputRunnable: Runnable? = null
    private var pendingScrollStep: LearningStep? = null
    private var pendingScrollRunnable: Runnable? = null
    private var overlayVisible = false

    var onFinishRequested: (() -> Unit)? = null

    var learningTargetName: String = ""
        private set

    var learningTargetPackage: String = ""
        private set

    val isActive: Boolean get() = overlayVisible
    val isRecording: Boolean get() = recording
    val stepCount: Int get() = synchronized(stepsLock) { recordedSteps.size }

    fun start(targetName: String = "", targetPackage: String = "", lockTarget: Boolean = false) {
        learningTargetName = targetName
        learningTargetPackage = targetPackage
        targetLocked = lockTarget
        synchronized(stepsLock) { recordedSteps.clear() }
        pendingClickStep = null
        pendingInputStep = null
        pendingScrollStep = null
        pendingClickRunnable?.let(mainHandler::removeCallbacks)
        pendingInputRunnable?.let(mainHandler::removeCallbacks)
        pendingScrollRunnable?.let(mainHandler::removeCallbacks)
        pendingClickRunnable = null
        pendingInputRunnable = null
        pendingScrollRunnable = null
        synchronized(stepsLock) { recordedSteps.clear() }
        recording = false
        showOverlay()
    }

    fun stop(): Boolean {
        val hadSteps = synchronized(stepsLock) { recordedSteps.isNotEmpty() }
        recording = false
        overlayVisible = false
        hideOverlay()
        return hadSteps
    }

    fun takeRecording(): List<LearningStep> {
        recording = false
        hideOverlay()
        synchronized(stepsLock) {
            val result = ArrayList(recordedSteps)
            recordedSteps.clear()
            return result
        }
    }

    fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!recording || event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> recordClick(event)
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> recordInput(event)
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> recordScroll(event)
        }
    }

    private fun recordClick(event: AccessibilityEvent) {
        val eventPackage = event.packageName?.toString().orEmpty()
        if (eventPackage.isBlank() || eventPackage == service.packageName) return
        ensureLearningTarget(eventPackage)
        val node = event.source
        val rect = Rect()
        var fallbackNode: AccessibilityNodeInfo? = null
        val eventLabel = listOf(
            event.contentDescription?.toString().orEmpty(),
            event.text.joinToString(" ").trim()
        ).firstOrNull { it.isNotBlank() }.orEmpty()
        val ownLabel = node?.let { labelForNode(it) }.orEmpty()
            .ifBlank { eventLabel }
        if (node == null && ownLabel.isNotBlank()) {
            fallbackNode = findNodeByLabel(ownLabel)
        }
        val targetNode = node ?: fallbackNode
        if (targetNode != null) targetNode.getBoundsInScreen(rect)
        if ((node == null || rect.isEmpty) && ownLabel.isBlank()) {
            toast("这一下没有可识别的点击目标，已跳过")
            return
        }
        if (sensitivePattern.containsMatchIn(ownLabel)) {
            toast("出于安全考虑，录制模式不会记录支付、密码或删除类操作")
            return
        }

        val size = service.realScreenSize()
        val x = if (rect.isEmpty) -1 else ((rect.exactCenterX().coerceIn(0f, (size.first - 1).toFloat()).toDouble() / size.first) * 999)
            .toInt().coerceIn(0, 999)
        val y = if (rect.isEmpty) -1 else ((rect.exactCenterY().coerceIn(0f, (size.second - 1).toFloat()).toDouble() / size.second) * 999)
            .toInt().coerceIn(0, 999)
        val page = service.currentPageFingerprint()
        val step = LearningStep(
            packageName = eventPackage,
            sourceFingerprint = page.first,
            actionType = "tap",
            actionLabel = ownLabel.take(70),
            resourceId = targetNode?.viewIdResourceName.orEmpty(),
            className = targetNode?.className?.toString().orEmpty(),
            actionX = x,
            actionY = y
        )

        pendingClickStep = step
        pendingClickRunnable?.let(mainHandler::removeCallbacks)
        val runnable = Runnable {
            if (pendingClickStep !== step) return@Runnable
            val target = service.currentPageFingerprint().first
            pendingClickStep = null
            pendingClickRunnable = null
            appendStep(step.copy(targetFingerprint = target))
        }
        pendingClickRunnable = runnable
        mainHandler.postDelayed(runnable, 850L)
    }

    private fun recordInput(event: AccessibilityEvent) {
        val eventPackage = event.packageName?.toString().orEmpty()
        if (eventPackage.isBlank() || eventPackage == service.packageName) return
        ensureLearningTarget(eventPackage)
        val text = event.text.joinToString("").orEmpty().trim()
        if (text.isBlank()) return
        val node = event.source
        val rect = Rect()
        if (node == null) return
        if (node.isPassword) {
            toast("录制模式不会记录密码输入")
            return
        }
        node.getBoundsInScreen(rect)
        val label = labelForNode(node)
        if (sensitivePattern.containsMatchIn(text) || sensitivePattern.containsMatchIn(label)) {
            toast("录制模式不会记录疑似密码、验证码或敏感信息")
            return
        }

        val size = service.realScreenSize()
        val step = pendingInputStep?.copy(actionLabel = text.take(80)) ?: LearningStep(
            packageName = eventPackage,
            sourceFingerprint = service.currentPageFingerprint().first,
            actionType = "input_text",
            actionLabel = text.take(80),
            resourceId = node.viewIdResourceName.orEmpty(),
            className = node.className?.toString().orEmpty(),
            actionX = ((rect.exactCenterX() / size.first) * 999).roundToInt(),
            actionY = ((rect.exactCenterY() / size.second) * 999).roundToInt()
        )
        pendingInputStep = step
        pendingInputRunnable?.let(mainHandler::removeCallbacks)
        val runnable = Runnable {
            if (pendingInputStep !== step) return@Runnable
            pendingInputStep = null
            pendingInputRunnable = null
            synchronized(stepsLock) {
                val last = recordedSteps.lastOrNull()
                if (last?.actionType == "input_text" &&
                    last.sourceFingerprint == step.sourceFingerprint &&
                    last.resourceId == step.resourceId
                ) {
                    recordedSteps[recordedSteps.lastIndex] = step
                } else {
                    recordedSteps.add(step)
                }
            }
            refreshOverlay()
        }
        pendingInputRunnable = runnable
        mainHandler.postDelayed(runnable, 750L)
    }

    private fun recordScroll(event: AccessibilityEvent) {
        val eventPackage = event.packageName?.toString().orEmpty()
        if (eventPackage.isBlank() || eventPackage == service.packageName) return
        ensureLearningTarget(eventPackage)

        val delta = event.scrollDeltaY
        val from = event.fromIndex
        val to = event.toIndex
        val direction = when {
            delta > 0 -> "swipe_down"
            delta < 0 -> "swipe_up"
            from >= 0 && to > from -> "swipe_down"
            from >= 0 && to in 0 until from -> "swipe_up"
            else -> ""
        }
        if (direction.isBlank()) return

        val step = LearningStep(
            packageName = eventPackage,
            sourceFingerprint = service.currentPageFingerprint().first,
            actionType = direction,
            actionLabel = if (direction == "swipe_down") "向下滑动" else "向上滑动"
        )
        pendingScrollStep = step
        pendingScrollRunnable?.let(mainHandler::removeCallbacks)
        val runnable = Runnable {
            if (pendingScrollStep !== step) return@Runnable
            pendingScrollStep = null
            pendingScrollRunnable = null
            appendStep(step.copy(targetFingerprint = service.currentPageFingerprint().first))
        }
        pendingScrollRunnable = runnable
        mainHandler.postDelayed(runnable, 650L)
    }

    private fun ensureLearningTarget(eventPackage: String) {
        val invalid = learningTargetPackage.isBlank() ||
            learningTargetPackage == service.packageName ||
            learningTargetPackage == "unknown"
        if (targetLocked || !invalid || eventPackage.isBlank()) return
        learningTargetPackage = eventPackage
        learningTargetName = try {
            val pm = service.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(eventPackage, 0)).toString()
        } catch (_: Exception) {
            eventPackage.substringAfterLast('.')
        }
    }

    private fun appendStep(step: LearningStep) {
        synchronized(stepsLock) { recordedSteps.add(step) }
        refreshOverlay()
    }

    private fun showOverlay() {
        mainHandler.post {
            hideOverlay()
            overlayVisible = true
            val root = LinearLayout(service).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(6), dp(10), dp(6))
                background = rounded(Color.parseColor("#CC111827"), dp(18f))
                elevation = dp(5).toFloat()
            }

            overlayTitle = TextView(service).apply {
                text = "待开始 · 0步"
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                maxWidth = dp(78)
            }
            root.addView(overlayTitle)

            overlayActionButton = TextView(service).apply {
                text = "开始"
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                background = rounded(Color.parseColor("#FF22C55E"), dp(14f))
                setOnClickListener {
                    if (!recording) beginRecording()
                    else onFinishRequested?.invoke()
                }
            }
            root.addView(overlayActionButton)

            overlayRoot = root
            service.addToOverlay(root) {
                gravity = Gravity.TOP or Gravity.START
                x = dp(12)
                y = dp(62)
                width = ViewGroup.LayoutParams.WRAP_CONTENT
                height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
        }
    }

    private fun refreshOverlay() {
        mainHandler.post {
            overlayTitle?.text = "录制中 · ${stepCount}步"
        }
    }

    fun beginRecording() {
        synchronized(stepsLock) { recordedSteps.clear() }
        pendingClickStep = null
        pendingInputStep = null
        pendingScrollStep = null
        pendingClickRunnable?.let(mainHandler::removeCallbacks)
        pendingInputRunnable?.let(mainHandler::removeCallbacks)
        pendingScrollRunnable?.let(mainHandler::removeCallbacks)
        pendingClickRunnable = null
        pendingInputRunnable = null
        pendingScrollRunnable = null
        recording = true
        mainHandler.post {
            overlayTitle?.text = "录制中 · 0步"
            overlayActionButton?.text = "完成"
        }
    }

    private fun hideOverlay() {
        overlayRoot?.let { service.removeFromOverlayPublic(it) }
        overlayRoot = null
        overlayTitle = null
        overlayActionButton = null
    }

    private fun findNodeByLabel(label: String): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null
        var exact: AccessibilityNodeInfo? = null
        var contains: AccessibilityNodeInfo? = null
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || (exact != null && contains != null)) return
            val own = listOf(
                node.text?.toString()?.trim().orEmpty(),
                node.contentDescription?.toString()?.trim().orEmpty()
            ).firstOrNull { it.isNotBlank() }
            if (!own.isNullOrBlank()) {
                if (own == label) exact = exact ?: node
                else if (own.contains(label, ignoreCase = true)) contains = contains ?: node
            }
            for (index in 0 until node.childCount) {
                try {
                    walk(node.getChild(index))
                } catch (_: Exception) {
                }
            }
        }
        walk(root)
        val node = exact ?: contains ?: return null
        var clickable = node
        var hops = 0
        while (!clickable.isClickable && clickable.parent != null && hops < 6) {
            clickable = clickable.parent
            hops++
        }
        return clickable
    }

    private fun labelForNode(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        val own = listOf(
            node.text?.toString()?.trim().orEmpty(),
            node.contentDescription?.toString()?.trim().orEmpty()
        ).firstOrNull { it.isNotBlank() }
        if (!own.isNullOrBlank()) return own
        for (index in 0 until node.childCount) {
            try {
                val label = labelForNode(node.getChild(index))
                if (label.isNotBlank()) return label
            } catch (_: Exception) {
            }
        }
        return ""
    }

    private fun rounded(color: Int, radius: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
        }
    }

    private fun dp(value: Int): Int {
        return (value * service.resources.displayMetrics.density).roundToInt()
    }

    private fun dp(value: Float): Float {
        return value * service.resources.displayMetrics.density
    }

    private fun toast(message: String) {
        mainHandler.post {
            Toast.makeText(service, message, Toast.LENGTH_SHORT).show()
        }
    }
}
