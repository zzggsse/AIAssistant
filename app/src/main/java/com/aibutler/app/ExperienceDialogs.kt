package com.aibutler.app

import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/** 行为记录「编辑 / 删除」弹窗：设置页和悬浮球的透明弹窗页共用 */
object ExperienceDialogs {

    /** 编辑标题和详细内容；测试时录进来的多余步骤（比如误点其他界面）在这里删掉，让经验复用更准 */
    fun showEdit(
        activity: AppCompatActivity,
        original: AgentExperience,
        onSaved: () -> Unit = {},
        onCancelled: (() -> Unit)? = null
    ) {
        val density = activity.resources.displayMetrics.density
        fun field(text: String, hint: String, multiline: Boolean) = EditText(activity).apply {
            setText(text)
            this.hint = hint
            textSize = 14f
            setTextColor(ContextCompat.getColor(activity, R.color.colorTextPrimary))
            setHintTextColor(ContextCompat.getColor(activity, R.color.colorTextHint))
            background = ContextCompat.getDrawable(activity, R.drawable.bg_settings_input)
            if (multiline) {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                gravity = Gravity.TOP or Gravity.START
                minLines = 5
            } else {
                inputType = InputType.TYPE_CLASS_TEXT
                setSingleLine(true)
            }
            setPadding(
                (12 * density).toInt(), (10 * density).toInt(),
                (12 * density).toInt(), (10 * density).toInt()
            )
        }
        fun label(text: String, topPad: Int = 0) = TextView(activity).apply {
            this.text = text
            textSize = 13f
            setPadding(0, (topPad * density).toInt(), 0, (4 * density).toInt())
            setTextColor(ContextCompat.getColor(activity, R.color.colorTextSecondary))
        }

        val etTitle = field(original.title, "标题", multiline = false)
        val etContent = field(
            original.content,
            "详细内容（删掉测试时误点的多余步骤，只留可复用的路径）",
            multiline = true
        )
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * density).toInt(), (8 * density).toInt(), (20 * density).toInt(), 0)
            addView(label("标题"))
            addView(etTitle)
            addView(label("详细内容", topPad = 12))
            addView(etContent)
        }

        AlertDialog.Builder(activity)
            .setTitle("编辑记录详细")
            .setView(container)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create()
            .apply {
                setOnCancelListener { onCancelled?.invoke() }
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val newTitle = etTitle.text.toString().trim()
                        if (newTitle.isBlank()) {
                            Toast.makeText(activity, "标题不能为空", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        val newContent = etContent.text.toString().trim()
                        if (AgentExperienceStore.update(activity, original, newTitle, newContent)) {
                            Toast.makeText(activity, "已保存修改 ✅", Toast.LENGTH_SHORT).show()
                            dismiss()
                            onSaved()
                        } else {
                            Toast.makeText(activity, "记录已不存在，请重新打开后重试", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .show()
    }

    fun showDelete(
        activity: AppCompatActivity,
        original: AgentExperience,
        onDeleted: () -> Unit = {},
        onCancelled: (() -> Unit)? = null
    ) {
        AlertDialog.Builder(activity)
            .setTitle("删除行为记录")
            .setMessage("确定删除「${original.title.ifBlank { "未命名任务" }}」这条记录吗？")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                if (AgentExperienceStore.remove(activity, original)) {
                    Toast.makeText(activity, "记录已删除", Toast.LENGTH_SHORT).show()
                    onDeleted()
                } else {
                    Toast.makeText(activity, "记录已不存在", Toast.LENGTH_SHORT).show()
                }
            }
            .create()
            .apply { setOnCancelListener { onCancelled?.invoke() } }
            .show()
    }
}
