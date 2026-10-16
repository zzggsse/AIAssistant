package com.aibutler.app

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 行为记录列表的共用渲染器：设置页和开发者悬浮球面板都用它，
 * 保证两边看到、编辑、删除的数据和行为完全一致。
 */
class ExperienceListRenderer(
    private val context: Context,
    private val container: LinearLayout,
    private val onEdit: (AgentExperience) -> Unit,
    private val onDelete: (AgentExperience) -> Unit
) {
    enum class Filter { ALL, SUCCESS, FAILURE }

    var filter: Filter = Filter.ALL

    /** 按当前筛选渲染列表；返回 (总条数, 正确条数) 供外部显示统计 */
    fun render(): Pair<Int, Int> {
        val items = AgentExperienceStore.all(context)
        val successCount = items.count { it.success }
        val visibleItems = when (filter) {
            Filter.ALL -> items
            Filter.SUCCESS -> items.filter { it.success }
            Filter.FAILURE -> items.filterNot { it.success }
        }

        container.removeAllViews()
        if (visibleItems.isEmpty()) {
            container.addView(TextView(context).apply {
                text = when (filter) {
                    Filter.ALL -> "暂无行为记录"
                    Filter.SUCCESS -> "暂无正确行为记录"
                    Filter.FAILURE -> "暂无错误行为记录"
                }
                textSize = 13f
                setTextColor(ContextCompat.getColor(context, R.color.colorTextSecondary))
                setPadding(0, dp(16), 0, 0)
            })
            return Pair(items.size, successCount)
        }

        val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
        visibleItems.forEach { item -> container.addView(row(item, timeFormat)) }
        return Pair(items.size, successCount)
    }

    private fun row(item: AgentExperience, timeFormat: SimpleDateFormat): View {
        val row = LayoutInflater.from(context).inflate(R.layout.item_experience, container, false)
        val layoutParams = row.layoutParams as LinearLayout.LayoutParams
        val detailArea = row.findViewById<LinearLayout>(R.id.experienceDetailArea)
        val tvTitle = row.findViewById<TextView>(R.id.tvExperienceTitle)
        val arrow = row.findViewById<android.widget.ImageView>(R.id.ivExperienceArrow)
        val tvStatus = row.findViewById<TextView>(R.id.tvExperienceStatus)
        val tvContent = row.findViewById<TextView>(R.id.tvExperienceContent)
        val tvTime = row.findViewById<TextView>(R.id.tvExperienceTime)
        val btnEdit = row.findViewById<ImageButton>(R.id.btnEditExperience)
        val btnDelete = row.findViewById<ImageButton>(R.id.btnDeleteExperience)
        var expanded = false

        fun updateExpandedState() {
            detailArea.visibility = if (expanded) View.VISIBLE else View.GONE
            arrow.animate().rotation(if (expanded) 180f else 0f).setDuration(140).start()
            layoutParams.height = if (expanded) LinearLayout.LayoutParams.WRAP_CONTENT else dp(48)
            row.layoutParams = layoutParams
            row.requestLayout()
        }

        tvTitle.text = item.title.ifBlank { "未命名任务" }
        tvStatus.text = if (item.success) "正确" else "错误"
        tvStatus.setTextColor(
            ContextCompat.getColor(
                context,
                if (item.success) R.color.colorSuccess else R.color.colorError
            )
        )
        tvContent.text = item.content.ifBlank { "没有保存详细内容" }
        tvTime.text = timeFormat.format(Date(item.updatedAt))

        tvContent.maxLines = Int.MAX_VALUE
        row.layoutParams = layoutParams
        updateExpandedState()

        row.setOnClickListener {
            expanded = !expanded
            updateExpandedState()
        }
        btnEdit.setOnClickListener { onEdit(item) }
        btnDelete.setOnClickListener { onDelete(item) }
        return row
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
