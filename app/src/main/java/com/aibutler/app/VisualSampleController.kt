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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class VisualSampleController(private val service: AssistantAccessibilityService) {

    companion object {
        var instance: VisualSampleController? = null
        private val QUICK_LABELS = listOf("去观看", "领金币", "关闭弹窗", "开宝箱")
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val library = VisualFeatureLibrary(service)
    private var root: View? = null
    private var selected = -1
    private var selectedRow: TextView? = null
    private var screen: Bitmap? = null
    private var rects: List<Rect> = emptyList()

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
                            toast("没找到这里的可点击区域，请再点准一点")
                        }
                    }
                    true
                }
            }
        }
        catcher.addView(top)
        mainHandler.postDelayed({
            if (root === catcher) show()
        }, 12000L)

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

        val list = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
        candidates.forEachIndexed { index, rect ->
            val (existing, score) = try {
                library.previewBest(frame, packageName, rect)
            } catch (_: Exception) {
                Pair("", 0f)
            }
            val row = TextView(service).apply {
                text = rowText(index, rect, existing, score)
                textSize = 13f
                setTextColor(Color.parseColor("#1F2937"))
                setPadding(dp(9), dp(8), dp(9), dp(8))
                background = rounded(Color.parseColor("#F3F4F6"), dp(8f))
                setOnClickListener {
                    selected = index
                    selectedRow?.setBackgroundColor(Color.parseColor("#F3F4F6"))
                    setBackgroundColor(Color.parseColor("#CCDCFCE7"))
                    selectedRow = this
                }
                if (index == preselectedIndex) {
                    selected = index
                    background = rounded(Color.parseColor("#CCDCFCE7"), dp(8f))
                    selectedRow = this
                }
            }
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
                setOnClickListener { saveSelected(labelInput.text.toString(), packageName) }
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

    private fun saveSelected(label: String, packageName: String) {
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
        try {
            val crop = Bitmap.createBitmap(frame, rect.left, rect.top, rect.width(), rect.height())
            val saved = library.add(crop, label, packageName)
            toast(if (saved == null) "样本保存失败" else "已保存「$label」")
            if (saved != null) {
                selectedRow?.append("  · 已保存")
                mainHandler.postDelayed({ hide() }, 450L)
            }
        } catch (_: Exception) {
            toast("样本保存失败")
        }
    }

    private fun rowText(index: Int, rect: Rect, existing: String, score: Float): String {
        val size = "${rect.width()}×${rect.height()}"
        return if (existing.isBlank()) "#${index + 1} · $size"
        else "#${index + 1} · $size · 已像「$existing」(${(score * 100).toInt()}%)"
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
