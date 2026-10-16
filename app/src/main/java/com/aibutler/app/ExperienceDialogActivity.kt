package com.aibutler.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.google.gson.Gson
import androidx.appcompat.app.AppCompatActivity

/**
 * 悬浮球面板专用的透明弹窗宿主：AlertDialog 必须挂在 Activity 窗口上，
 * 所以在无障碍悬浮层里点编辑/删除时，先弹一个全透明页面来承载弹窗；
 * 关掉后回到原来的 App，面板自动刷新。
 */
class ExperienceDialogActivity : AppCompatActivity() {

    companion object {
        const val ACTION_EDIT = "edit"
        const val ACTION_DELETE = "delete"
        private const val EXTRA_ACTION = "action"
        private const val EXTRA_ITEM = "item_json"

        fun launch(context: Context, action: String, item: AgentExperience) {
            context.startActivity(
                Intent(context, ExperienceDialogActivity::class.java)
                    .putExtra(EXTRA_ACTION, action)
                    .putExtra(EXTRA_ITEM, Gson().toJson(item))
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_NO_ANIMATION or
                                Intent.FLAG_ACTIVITY_NO_USER_ACTION
                    )
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val item = runCatching {
            Gson().fromJson(intent?.getStringExtra(EXTRA_ITEM), AgentExperience::class.java)
        }.getOrNull()
        if (item == null) {
            finish()
            return
        }
        val done: () -> Unit = {
            DeveloperBallController.instance?.refreshPanel()
            finish()
        }
        when (intent?.getStringExtra(EXTRA_ACTION)) {
            ACTION_EDIT -> ExperienceDialogs.showEdit(
                this, item, onSaved = done, onCancelled = { finish() }
            )
            ACTION_DELETE -> ExperienceDialogs.showDelete(
                this, item, onDeleted = done, onCancelled = { finish() }
            )
            else -> finish()
        }
    }
}
