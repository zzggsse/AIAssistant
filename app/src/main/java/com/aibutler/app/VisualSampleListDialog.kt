package com.aibutler.app

import android.graphics.BitmapFactory
import android.view.View
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object VisualSampleListDialog {

    // 删除/重命名后的刷新是重新 show 一个新对话框：不先关旧的话会层层堆叠，
    // 下层还是带旧数据的列表，点得到「清空全部」等旧按钮（体检第五轮 C2）
    private var currentDialog: AlertDialog? = null

    fun show(activity: AppCompatActivity, onAddNew: () -> Unit = {}) {
        currentDialog?.dismiss()
        currentDialog = null
        val library = VisualFeatureLibrary(activity)
        val samples = library.all().sortedByDescending { it.createdAt }
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 20), dp(activity, 12), dp(activity, 20), dp(activity, 8))
        }

        val header = TextView(activity).apply {
            text = "共 ${samples.size} 个样本 · 命中率越高越好"
            textSize = 13f
            setTextColor(ContextCompat.getColor(activity, R.color.colorTextSecondary))
            setPadding(0, 0, 0, dp(activity, 8))
        }
        container.addView(header)

        if (samples.isEmpty()) {
            container.addView(TextView(activity).apply {
                text = "还没有图形样本。点下方「添加新样本」，到目标页面截取一个按钮保存。"
                textSize = 14f
                setTextColor(ContextCompat.getColor(activity, R.color.colorTextSecondary))
                setPadding(0, dp(activity, 16), 0, dp(activity, 16))
            })
        } else {
            val listLayout = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
            samples.forEach { sample -> listLayout.addView(sampleRow(activity, library, sample, timeFormat) { show(activity, onAddNew) }) }
            val scroll = ScrollView(activity).apply { addView(listLayout) }
            container.addView(scroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ))
        }

        val actions = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(actionButton(activity, "添加新样本", 0xFF0F766E.toInt()) {
            onAddNew()
        }, rowWeight(activity))
        actions.addView(spacer(activity))
        actions.addView(actionButton(activity, "清空全部", 0xFFB91C1C.toInt()) {
            AlertDialog.Builder(activity)
                .setTitle("清空图形样本")
                .setMessage("确定删除全部 ${samples.size} 个样本吗？此操作不可撤销。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空") { _, _ ->
                    library.clear()
                    Toast.makeText(activity, "已清空", Toast.LENGTH_SHORT).show()
                    show(activity, onAddNew)
                }
                .show()
        }, rowWeight(activity))
        container.addView(actions, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(activity, 10) })

        currentDialog = AlertDialog.Builder(activity)
            .setTitle("图形样本库")
            .setView(container)
            .setNegativeButton("关闭", null)
            .show()
            .also {
                // 用户点「关闭」/返回键 dismiss 后清掉静态引用：currentDialog 是 object 级字段，
                // 不清会连着整个 activity 泄漏到下一次 show（体检第六轮 P3-1）
                it.setOnDismissListener { currentDialog = null }
            }
    }

    private fun sampleRow(
        activity: AppCompatActivity,
        library: VisualFeatureLibrary,
        sample: VisualSample,
        timeFormat: SimpleDateFormat,
        onRefresh: () -> Unit
    ): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 8), dp(activity, 8), dp(activity, 4), dp(activity, 8))
            background = ContextCompat.getDrawable(activity, R.drawable.bg_suggestion)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(activity, 4) }
        }

        val thumb = ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(activity, 44), dp(activity, 44)).apply {
                marginEnd = dp(activity, 10)
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
            val bmp = BitmapFactory.decodeFile(sample.imagePath)
            // 绝不能 setImageBitmap 后立刻 recycle：首帧绘制发生在 dialog show 之后，
            // 那时会读已回收的 bitmap 直接崩（Canvas: trying to use a recycled bitmap）
            if (bmp != null) setImageBitmap(bmp)
            background = ContextCompat.getDrawable(activity, R.drawable.bg_input_field)
            clipToOutline = true
        }
        row.addView(thumb)

        val info = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        info.addView(TextView(activity).apply {
            text = sample.label
            textSize = 14f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(ContextCompat.getColor(activity, R.color.colorTextPrimary))
        })
        info.addView(TextView(activity).apply {
            val pkg = if (sample.packageName.isBlank()) "通用" else sample.packageName.substringAfterLast('.')
            text = "$pkg · 匹配${sample.matchCount}次 · ${timeFormat.format(Date(sample.createdAt))}"
            textSize = 11f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(ContextCompat.getColor(activity, R.color.colorTextSecondary))
        })
        row.addView(info)

        row.addView(ImageButton(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(activity, 36), dp(activity, 36)).apply {
                marginStart = dp(activity, 4)
            }
            setImageResource(R.drawable.ic_edit)
            setOnClickListener { showRename(activity, library, sample) { onRefresh() } }
        })
        row.addView(ImageButton(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(activity, 36), dp(activity, 36)).apply {
                marginStart = dp(activity, 4)
            }
            setImageResource(R.drawable.ic_delete)
            setOnClickListener {
                AlertDialog.Builder(activity)
                    .setTitle("删除样本")
                    .setMessage("删除「${sample.label}」？")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("删除") { _, _ ->
                        library.delete(sample.id)
                        Toast.makeText(activity, "已删除", Toast.LENGTH_SHORT).show()
                        onRefresh()
                    }
                    .show()
            }
        })
        return row
    }

    private fun showRename(activity: AppCompatActivity, library: VisualFeatureLibrary, sample: VisualSample, onDone: () -> Unit) {
        val input = EditText(activity).apply {
            setText(sample.label)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            setSelection(text.length)
        }
        AlertDialog.Builder(activity)
            .setTitle("重命名样本")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val newLabel = input.text.toString().trim()
                if (newLabel.isBlank()) {
                    Toast.makeText(activity, "名称不能为空", Toast.LENGTH_SHORT).show()
                } else if (library.rename(sample.id, newLabel)) {
                    Toast.makeText(activity, "已重命名", Toast.LENGTH_SHORT).show()
                    onDone()
                }
            }
            .show()
    }

    private fun actionButton(activity: AppCompatActivity, text: String, bgColor: Int, onClick: () -> Unit): TextView {
        return TextView(activity).apply {
            this.text = text
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(activity, 10), 0, dp(activity, 10))
            setTextColor(android.graphics.Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(bgColor)
                cornerRadius = dp(activity, 8).toFloat()
            }
            setOnClickListener { onClick() }
        }
    }

    private fun rowWeight(activity: AppCompatActivity) = LinearLayout.LayoutParams(
        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
    )

    private fun spacer(activity: AppCompatActivity) = View(activity).also {
        it.layoutParams = LinearLayout.LayoutParams(dp(activity, 8), 1)
    }

    private fun dp(activity: AppCompatActivity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
