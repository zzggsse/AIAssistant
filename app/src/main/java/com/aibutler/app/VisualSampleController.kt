package com.aibutler.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.Gravity
import android.widget.FrameLayout
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

class VisualSampleController(private val service: AssistantAccessibilityService) {

    companion object {
        var instance: VisualSampleController? = null
        private val QUICK_LABELS = listOf("去观看", "领金币", "关闭弹窗", "开宝箱")
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val saveExecutor = Executors.newSingleThreadExecutor()
    private val library = VisualFeatureLibrary(service)
    private var root: View? = null
    private var selected = -1
    private var selectedRow: View? = null
    private var screen: Bitmap? = null
    private var rects: List<Rect> = emptyList()
    private var savingSample = false

    fun show() {
        mainHandler.post {
            hide()
            val bar = LinearLayout(service).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            fun pillButton(title: String, onClick: () -> Unit): TextView {
                return TextView(service).apply {
                    text = title
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    background = rounded(Color.parseColor("#CC0F766E"), dp(18f))
                    elevation = dp(5).toFloat()
                    setOnClickListener { onClick() }
                }
            }

            bar.addView(
                pillButton("存图形样本") { captureCurrentScreen() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
            bar.addView(View(service), LinearLayout.LayoutParams(dp(7), 1))
            bar.addView(
                pillButton("点选样本") { captureCurrentScreen(selectByTap = true) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
            bar.addView(View(service), LinearLayout.LayoutParams(dp(7), 1))
            bar.addView(
                pillButton("关闭") { hide() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
            root = bar
            service.addToOverlay(bar) {
                gravity = Gravity.TOP or Gravity.START
                x = dp(12)
                y = dp(122)
                width = ViewGroup.LayoutParams.WRAP_CONTENT
                height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
        }
    }

    fun hide() {
        root?.let(service::removeFromOverlayPublic)
        root = null
        selectedRow = null
        screen = null
        rects = emptyList()
        selected = -1
    }

    private fun captureCurrentScreen(selectByTap: Boolean = false) {
        val pill = root
        mainHandler.post {
            pill?.let(service::removeFromOverlayPublic)
            if (root === pill) root = null
        }
        Thread {
            try {
                Thread.sleep(260)
                val frame = if (root == null && CaptureService.isActive()) {
                    service.captureForSamples()
                } else {
                    null
                }
                val candidates = if (frame != null) service.collectClickableRects() else emptyList()
                mainHandler.post {
                    if (frame == null) {
                        toast("截图失败，请确认屏幕视觉已开启")
                        show()
                    } else if (candidates.isEmpty()) {
                        toast("当前页面没找到可点击区域")
                        show()
                    } else {
                        screen = frame
                        rects = candidates
                        if (selectByTap) showTapTargetPicker() else showPicker()
                    }
                }
            } catch (_: Exception) {
                mainHandler.post {
                    toast("样本保存失败")
                    show()
                }
            }
        }.start()
    }

    private fun showTapTargetPicker() {
        val instruction = TextView(service).apply {
            text = "点一下要保存的按钮 · 点顶部取消"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(Color.parseColor("#CC111827"), dp(16f))
        }
        val top = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(10), dp(72), dp(10), 0)
            addView(instruction)
        }

        val catcher = FrameLayout(service).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setOnTouchListener { _, event ->
                if (event.action != MotionEvent.ACTION_UP) false else {
                    if (event.rawY < dp(112)) {
                        show()
                    } else {
                        val index = findTapCandidate(event.rawX, event.rawY)
                        if (index >= 0) {
                            showPicker(preselectedIndex = index)
                        } else {
                            openPickerForPureImageTarget(
                                x = event.rawX.toInt(),
                                y = event.rawY.toInt()
                            )
                        }
                    }
                    true
                }
            }
        }
        catcher.addView(top)

        root = catcher
        service.addToOverlay(catcher) {
            gravity = Gravity.TOP or Gravity.START
            width = ViewGroup.LayoutParams.MATCH_PARENT
            height = ViewGroup.LayoutParams.MATCH_PARENT
        }
    }

    private fun findTapCandidate(x: Float, y: Float): Int {
        val maxDistance = dp(96f)
        var bestIndex = -1
        var bestDistance = Float.MAX_VALUE
        rects.forEachIndexed { index, rect ->
            val padded = Rect(rect).apply { inset(-dp(10), -dp(10)) }
            if (!padded.contains(x.toInt(), y.toInt())) return@forEachIndexed
            val dx = x - rect.exactCenterX()
            val dy = y - rect.exactCenterY()
            val distance = kotlin.math.sqrt(dx * dx + dy * dy)
            if (distance < bestDistance && distance <= maxDistance) {
                bestIndex = index
                bestDistance = distance
            }
        }
        return bestIndex
    }

    private fun openPickerForPureImageTarget(x: Int, y: Int) {
        val frame = screen
        if (frame == null) {
            toast("截图已失效，请重新点选样本")
            show()
            return
        }

        val visibleRects = service.collectVisibleRectsAt(x, y)
        if (visibleRects.isNotEmpty()) {
            val baseIndex = rects.size
            val targets = visibleRects.map { rect ->
                VisualSampleTapTarget(rect.left, rect.top, rect.right, rect.bottom)
            }
            val best = VisualSampleTapPolicy.smallestContaining(targets, x, y)
            rects = rects + visibleRects
            showPicker(preselectedIndex = baseIndex + (best ?: 0))
            return
        }

        val manualTarget = VisualSampleTapPolicy.fallbackCrop(
            x = x,
            y = y,
            screenWidth = frame.width,
            screenHeight = frame.height,
            targetSize = dp(92)
        )
        if (manualTarget == null) {
            toast("这个位置在截图外，请再点准一点")
            return
        }
        rects = rects + Rect(
            manualTarget.left,
            manualTarget.top,
            manualTarget.right,
            manualTarget.bottom
        )
        showPicker(preselectedIndex = rects.lastIndex)
    }

    private fun showPicker(preselectedIndex: Int = -1) {
        val frame = screen ?: return
        val candidates = rects
        hide()
        // hide() 会清空捕获状态；确认窗必须继续使用刚才截到的同一帧和同一组候选区域。
        screen = frame
        rects = candidates
        selected = -1
        val packageName = service.currentPackage()
        val panel = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(Color.parseColor("#F7FFFFFF"), dp(12f))
        }
        panel.addView(TextView(service).apply {
            text = "确认图形样本 · ${candidates.size}个候选"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#111827"))
        })
        val labelInput = EditText(service).apply {
            hint = "按钮名称，例如 去观看"
            setSingleLine(true)
            textSize = 14f
        }
        panel.addView(labelInput)

        val quickRow = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL }
        QUICK_LABELS.forEach { label ->
            quickRow.addView(TextView(service).apply {
                text = label
                textSize = 12f
                setPadding(dp(8), dp(5), dp(8), dp(5))
                setTextColor(Color.parseColor("#0F766E"))
                background = rounded(Color.parseColor("#140F766E"), dp(12f))
                setOnClickListener { labelInput.setText(label) }
            })
            quickRow.addView(View(service), LinearLayout.LayoutParams(dp(5), 1))
        }
        panel.addView(quickRow)

        val previewImage = ImageView(service).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(6), dp(6), dp(6), dp(6))
            visibility = View.GONE
        }
        val previewHint = TextView(service).apply {
            text = "选中候选后，这里会显示实际截取内容"
            textSize = 12f
            setTextColor(Color.parseColor("#6B7280"))
            gravity = Gravity.CENTER
        }
        val preview = FrameLayout(service).apply {
            setBackgroundColor(Color.parseColor("#11000000"))
            addView(previewImage, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
            addView(previewHint, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ))
        }
        panel.addView(
            preview,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(86)).apply {
                topMargin = dp(8)
            }
        )

        fun updatePreview(index: Int) {
            val rect = rects.getOrNull(index)
            val crop = rect?.let { createSampleCrop(frame, it) }
            if (crop == null) {
                previewImage.visibility = View.GONE
                previewHint.visibility = View.VISIBLE
            } else {
                previewImage.setImageBitmap(crop)
                previewImage.visibility = View.VISIBLE
                previewHint.visibility = View.GONE
            }
        }

        fun selectCandidate(index: Int, row: View) {
            selected = index
            selectedRow?.background = rounded(Color.parseColor("#F3F4F6"), dp(8f))
            row.background = rounded(Color.parseColor("#CCDCFCE7"), dp(8f))
            selectedRow = row
            updatePreview(index)
        }

        val list = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
        candidates.forEachIndexed { index, rect ->
            val (existing, score) = try {
                library.previewBest(frame, packageName, rect)
            } catch (_: Exception) {
                Pair("", 0f)
            }
            val text = TextView(service).apply {
                text = rowText(index, rect, existing, score)
                textSize = 13f
                setTextColor(Color.parseColor("#1F2937"))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val thumbnail = ImageView(service).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = rounded(Color.parseColor("#E5E7EB"), dp(6f))
                layoutParams = LinearLayout.LayoutParams(dp(54), dp(36)).apply {
                    marginEnd = dp(8)
                }
                setImageBitmap(createSampleThumbnail(frame, rect, dp(54), dp(36)))
            }
            val row = LinearLayout(service).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(9), dp(8), dp(9), dp(8))
                background = rounded(Color.parseColor("#F3F4F6"), dp(8f))
                setOnClickListener {
                    selectCandidate(index, this)
                }
            }
            row.addView(thumbnail)
            row.addView(text)
            if (index == preselectedIndex) selectCandidate(index, row)
            list.addView(
                row,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(6)
                }
            )
        }
        val scroll = ScrollView(service).apply { addView(list) }
        panel.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                topMargin = dp(8)
            }
        )

        val actions = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(
            TextView(service).apply {
                text = "保存"
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(8), dp(16), dp(8))
                background = rounded(Color.parseColor("#FF0F766E"), dp(16f))
                setOnClickListener {
                    saveSelected(labelInput.text.toString(), packageName) {
                        labelInput.setText("")
                        selectedRow?.background = rounded(Color.parseColor("#F3F4F6"), dp(8f))
                        selectedRow = null
                        selected = -1
                        previewImage.visibility = View.GONE
                        previewHint.text = "已保存，继续选择下一个候选"
                        previewHint.visibility = View.VISIBLE
                    }
                }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(7)
            }
        )
        actions.addView(
            TextView(service).apply {
                text = "关闭"
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(8), dp(16), dp(8))
                setTextColor(Color.parseColor("#374151"))
                background = rounded(Color.parseColor("#FFE5E7EB"), dp(16f))
                setOnClickListener {
                    hide()
                }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        panel.addView(
            actions,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            }
        )

        val outer = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(20), dp(20), dp(20))
            setBackgroundColor(Color.parseColor("#55000000"))
        }
        outer.addView(panel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(390)))
        root = outer
        service.addToOverlay(outer, focusable = true) {
            gravity = Gravity.CENTER
            width = ViewGroup.LayoutParams.MATCH_PARENT
            height = ViewGroup.LayoutParams.MATCH_PARENT
        }
    }

    private fun saveSelected(label: String, packageName: String, onSaved: () -> Unit) {
        val frame = screen
        val rect = rects.getOrNull(selected)
        if (frame == null || rect == null) {
            toast("先选中一个按钮区域")
            return
        }
        if (label.isBlank()) {
            toast("先填写样本名称")
            return
        }
        if (savingSample) {
            toast("正在保存上一个样本…")
            return
        }
        savingSample = true
        saveExecutor.execute {
            val result = runCatching {
                val crop = Bitmap.createBitmap(frame, rect.left, rect.top, rect.width(), rect.height())
                library.add(crop, label, packageName)
            }
            mainHandler.post {
                savingSample = false
                val saved = result.getOrNull()
                toast(if (saved == null || result.isFailure) "样本保存失败" else "已保存「$label」")
                if (saved != null) {
                    onSaved()
                }
            }
            }
        }

    private fun rowText(index: Int, rect: Rect, existing: String, score: Float): String {
        val size = "${rect.width()}×${rect.height()}"
        return if (existing.isBlank()) "#${index + 1} · $size"
        else "#${index + 1} · $size · 已像「$existing」(${(score * 100).toInt()}%)"
    }

    private fun createSampleCrop(frame: Bitmap, rect: Rect): Bitmap? {
        val left = rect.left.coerceIn(0, frame.width - 1)
        val top = rect.top.coerceIn(0, frame.height - 1)
        val right = rect.right.coerceIn(left + 1, frame.width)
        val bottom = rect.bottom.coerceIn(top + 1, frame.height)
        return try {
            Bitmap.createBitmap(frame, left, top, right - left, bottom - top)
        } catch (_: Exception) {
            null
        }
    }

    private fun createSampleThumbnail(
        frame: Bitmap,
        rect: Rect,
        width: Int,
        height: Int
    ): Bitmap? {
        val crop = createSampleCrop(frame, rect) ?: return null
        return try {
            Bitmap.createScaledBitmap(crop, width, height, true)
        } catch (_: Exception) {
            crop
        }
    }

    private fun toast(message: String) {
        Toast.makeText(service, message, Toast.LENGTH_SHORT).show()
    }

    private fun rounded(color: Int, radius: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
        }
    }

    private fun dp(value: Int): Int {
        return (value * service.resources.displayMetrics.density).toInt()
    }

    private fun dp(value: Float): Float {
        return value * service.resources.displayMetrics.density
    }
}

internal data class VisualSampleTapTarget(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val area: Int get() = width * height

    fun contains(x: Int, y: Int): Boolean =
        x in left until right && y in top until bottom
}

internal object VisualSampleTapPolicy {
    fun smallestContaining(
        targets: List<VisualSampleTapTarget>,
        x: Int,
        y: Int
    ): Int? {
        return targets.withIndex()
            .filter { it.value.contains(x, y) }
            .minByOrNull { it.value.area }
            ?.index
    }

    fun fallbackCrop(
        x: Int,
        y: Int,
        screenWidth: Int,
        screenHeight: Int,
        targetSize: Int
    ): VisualSampleTapTarget? {
        if (x !in 0 until screenWidth || y !in 0 until screenHeight ||
            screenWidth <= 0 || screenHeight <= 0 || targetSize <= 0
        ) return null

        val half = targetSize / 2
        val left = (x - half).coerceAtLeast(0)
        val top = (y - half).coerceAtLeast(0)
        val right = (left + targetSize).coerceAtMost(screenWidth)
        val bottom = (top + targetSize).coerceAtMost(screenHeight)
        return VisualSampleTapTarget(left, top, right, bottom)
    }
}
