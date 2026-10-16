package com.aibutler.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlin.math.abs

/**
 * 开发者悬浮球：跳到其他 App 时，屏幕边缘半隐一个 📚 小球，点一下滑出行为记录面板，
 * 查看/筛选/编辑/删除跟设置页完全一样。挂在无障碍服务的 TYPE_ACCESSIBILITY_OVERLAY
 * 窗口上（和任务停止按钮同机制），不需要悬浮窗权限；回到本 App 或关闭开发者模式时自动收起。
 */
class DeveloperBallController(private val service: AssistantAccessibilityService) {

    companion object {
        /** 当前活跃的控制器；透明弹窗页保存后用它刷新面板 */
        @JvmStatic
        var instance: DeveloperBallController? = null
    }

    private val wm get() = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    /** 服务 Context 没有主题，套一层 App 主题，item_experience 里的 ?attr 背景才能正常解析 */
    private val themedCtx = android.view.ContextThemeWrapper(service, R.style.Theme_Assistant)

    private var ballView: View? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var panelView: View? = null
    private var filterButtons: List<Button> = emptyList()
    private var renderer: ExperienceListRenderer? = null
    private var filter = ExperienceListRenderer.Filter.ALL
    private var ballOnRightEdge = true
    private var panelOpen = false

    /** 无障碍窗口切换回调：别的前台 App 显示悬浮球，回到本 App 收起一切 */
    fun onAppForeground(pkg: String, className: String?) {
        if (pkg == service.packageName) {
            // 弹窗宿主页是全透明的，前后台切换时保持面板不动，等弹窗关掉再刷新
            if (className?.endsWith("ExperienceDialogActivity") == true) return
            closePanel(immediate = true)
            hideBall()
        } else if (Prefs.developerMode(service)) {
            showBall()
        }
    }

    /** 设置页里开发者模式被关掉时调用 */
    fun onDeveloperModeChanged() {
        if (!Prefs.developerMode(service)) {
            closePanel(immediate = true)
            hideBall()
        }
    }

    /** 弹窗保存/删除后刷新面板列表 */
    fun refreshPanel() {
        if (!panelOpen) return
        renderer?.render()
        updateFilterButtons()
    }

    fun shutdown() {
        closePanel(immediate = true)
        hideBall()
        if (instance === this) instance = null
    }

    // ---------- 悬浮球 ----------

    private fun showBall() {
        if (ballView != null) return
        val size = dp(44)
        val screen = service.realScreenSize()

        val view = FrameLayout(themedCtx).apply {
            clipToOutline = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E61F1F24"))
            }
            elevation = dp(6).toFloat()
            contentDescription = "行为记录悬浮球，点按打开面板"
            addView(
                TextView(themedCtx).apply {
                    text = "📚"
                    textSize = 18f
                    gravity = Gravity.CENTER
                },
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }

        // 半隐在屏幕边缘：x 让小球一半露在外面
        val params = service.addToOverlay(view, modal = false) {
            flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            gravity = Gravity.TOP or Gravity.START
            width = size
            height = size
            x = if (ballOnRightEdge) screen.first - size / 2 else -size / 2
            y = screen.second / 3
        } ?: run {
            removeFromWindow(view)
            return
        }

        view.setOnTouchListener(ballTouchListener(view, params))
        ballView = view
        ballParams = params
    }

    private fun hideBall() {
        ballView?.let(::removeFromWindow)
        ballView = null
        ballParams = null
    }

    /** 拖动换边、点按开合面板，手感对齐常见的清理工具球 */
    private fun ballTouchListener(view: View, params: WindowManager.LayoutParams): View.OnTouchListener {
        val slop = ViewConfiguration.get(service).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var startXPos = 0
        var startYPos = 0
        var dragging = false
        return View.OnTouchListener { _, event ->
            val screen = service.realScreenSize()
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startXPos = params.x
                    startYPos = params.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && (abs(event.rawX - downRawX) > slop ||
                                abs(event.rawY - downRawY) > slop)
                    ) dragging = true
                    if (dragging) {
                        params.y = (startYPos + (event.rawY - downRawY)).toInt()
                            .coerceIn(-params.height / 2, screen.second - params.height / 2)
                        params.x = (startXPos + (event.rawX - downRawX)).toInt()
                            .coerceIn(-params.width / 2, screen.first - params.width / 2)
                        runCatching { wm.updateViewLayout(view, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        // 松手吸附到较近的左右边缘，继续保持半隐
                        ballOnRightEdge = event.rawX > screen.first / 2f
                        params.x = if (ballOnRightEdge) screen.first - params.width / 2 else -params.width / 2
                        runCatching { wm.updateViewLayout(view, params) }
                    } else {
                        togglePanel()
                    }
                    dragging = false
                    true
                }
                else -> false
            }
        }
    }

    // ---------- 行为记录面板 ----------

    private fun togglePanel() {
        if (panelOpen) closePanel() else openPanel()
    }

    private fun openPanel() {
        if (panelOpen || ballView == null) return
        val screen = service.realScreenSize()
        val panelW = (screen.first * 0.84f).toInt()
        val panelH = (screen.second * 0.72f).toInt()

        val content = buildPanelContent()
        service.addToOverlay(content, modal = false) {
            gravity = Gravity.TOP or Gravity.START
            width = panelW
            height = panelH
            x = screen.first - panelW
            y = (screen.second - panelH) / 2
        } ?: run {
            removeFromWindow(content)
            return
        }
        panelView = content
        panelOpen = true
        content.translationX = panelW.toFloat()
        content.animate().translationX(0f).setDuration(200).start()
        renderList()
    }

    fun closePanel(immediate: Boolean = false) {
        val view = panelView
        panelOpen = false
        panelView = null
        renderer = null
        filterButtons = emptyList()
        if (view == null) return
        if (immediate) {
            removeFromWindow(view)
        } else {
            view.animate()
                .translationX(view.width.coerceAtLeast(1).toFloat())
                .setDuration(160)
                .withEndAction { removeFromWindow(view) }
                .start()
        }
    }

    private fun buildPanelContent(): View {
        val root = LinearLayout(themedCtx).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(service, R.drawable.bg_settings_card)?.mutate()
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }

        val header = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(themedCtx).apply {
            text = "行为记录"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ContextCompat.getColor(service, R.color.colorTextPrimary))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(themedCtx).apply {
            text = "✕ 收起"
            textSize = 13f
            setTextColor(ContextCompat.getColor(service, R.color.colorTextSecondary))
            setPadding(dp(10), dp(6), 0, dp(6))
            setOnClickListener { closePanel() }
        })
        root.addView(header)

        root.addView(TextView(themedCtx).apply {
            text = "✏️ 可删掉测试时误点的多余步骤，让经验复用更准"
            textSize = 11f
            setTextColor(ContextCompat.getColor(service, R.color.colorTextHint))
            setPadding(0, dp(3), 0, 0)
        })

        val filterRow = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        val filters = listOf(
            Pair("全部", ExperienceListRenderer.Filter.ALL),
            Pair("正确", ExperienceListRenderer.Filter.SUCCESS),
            Pair("错误", ExperienceListRenderer.Filter.FAILURE)
        )
        filters.forEachIndexed { index, (text, f) ->
            val btn = Button(themedCtx).apply {
                this.text = text
                textSize = 13f
                isAllCaps = false
                minWidth = 0
                minimumWidth = 0
                backgroundTintList = null
                setTextColor(ContextCompat.getColor(service, R.color.colorTextPrimary))
                setOnClickListener { filter = f; renderList() }
            }
            filterRow.addView(
                btn,
                LinearLayout.LayoutParams(0, dp(34), 1f).apply { marginEnd = if (index < filters.size - 1) dp(6) else 0 }
            )
        }
        root.addView(filterRow)

        val list = LinearLayout(themedCtx).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(themedCtx).apply {
            clipToPadding = false
            isFillViewport = true
            addView(list)
        }
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply { topMargin = dp(4) }
        )

        renderer = ExperienceListRenderer(
            themedCtx, list,
            onEdit = { ExperienceDialogActivity.launch(service, ExperienceDialogActivity.ACTION_EDIT, it) },
            onDelete = { ExperienceDialogActivity.launch(service, ExperienceDialogActivity.ACTION_DELETE, it) }
        )
        filterButtons = filters.mapIndexed { index, _ -> filterRow.getChildAt(index) as Button }
        return root
    }

    private fun renderList() {
        val r = renderer ?: return
        r.filter = filter
        r.render()
        updateFilterButtons()
    }

    private fun updateFilterButtons() {
        val selectedColor = ContextCompat.getColor(service, R.color.colorOnPrimary)
        val unselectedColor = ContextCompat.getColor(service, R.color.colorTextPrimary)
        val order = listOf(
            ExperienceListRenderer.Filter.ALL,
            ExperienceListRenderer.Filter.SUCCESS,
            ExperienceListRenderer.Filter.FAILURE
        )
        filterButtons.forEachIndexed { index, btn ->
            val selected = filter == order[index]
            // 每个按钮取一份独立 drawable，避免共享实例导致选中态互相串
            btn.background = ContextCompat.getDrawable(
                service,
                if (selected) R.drawable.bg_developer_filter_selected
                else R.drawable.bg_developer_filter_unselected
            )?.mutate()
            btn.setTextColor(if (selected) selectedColor else unselectedColor)
        }
    }

    // ---------- 工具 ----------

    private fun removeFromWindow(view: View) {
        try {
            if (view.parent != null) wm.removeView(view)
        } catch (_: Exception) {
        }
    }

    private fun dp(value: Int): Int = android.util.TypedValue.applyDimension(
        android.util.TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        service.resources.displayMetrics
    ).toInt()
}
