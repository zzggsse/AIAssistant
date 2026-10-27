package com.aibutler.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.method.PasswordTransformationMethod
import android.graphics.PorterDuff
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class SettingsActivity : AppCompatActivity() {

    private var loading = true

    private lateinit var spinner: Spinner
    private lateinit var etBaseUrl: EditText
    private lateinit var etModel: AutoCompleteTextView
    private lateinit var etApiKey: EditText
    private lateinit var tvKeyHint: TextView
    private lateinit var swDeveloperMode: JellyToggle
    private lateinit var llDeveloperPanel: LinearLayout
    private lateinit var llExperienceList: LinearLayout
    private lateinit var tvExperienceSummary: TextView
    private lateinit var btnFilterAllExperience: Button
    private lateinit var btnFilterSuccessExperience: Button
    private lateinit var btnFilterFailureExperience: Button

    /** 行为记录列表渲染：仅在设置页内查看和编辑 */
    private val experienceRenderer by lazy {
        ExperienceListRenderer(
            this, llExperienceList,
            onEdit = { ExperienceDialogs.showEdit(this, it) { renderExperienceList() } },
            onDelete = { ExperienceDialogs.showDelete(this, it) { renderExperienceList() } }
        )
    }

    private companion object {
        /** 清空行为记录的确认密码 */
        const val CLEAR_PASSWORD = "000000"
    }

    private fun currentVendor(): Vendor =
        Vendors.list.getOrNull(spinner.selectedItemPosition) ?: Vendors.list[0]

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tvAppVersion).text = "v${
            runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "unknown"
        }"

        spinner = findViewById(R.id.spinnerVendor)
        etBaseUrl = findViewById(R.id.etBaseUrl)
        etModel = findViewById(R.id.etModel)
        etApiKey = findViewById(R.id.etApiKey)
        tvKeyHint = findViewById(R.id.tvKeyHint)
        swDeveloperMode = findViewById(R.id.swDeveloperMode)
        llDeveloperPanel = findViewById(R.id.llDeveloperPanel)
        llExperienceList = findViewById(R.id.llExperienceList)
        tvExperienceSummary = findViewById(R.id.tvExperienceSummary)
        btnFilterAllExperience = findViewById(R.id.btnFilterAllExperience)
        btnFilterSuccessExperience = findViewById(R.id.btnFilterSuccessExperience)
        btnFilterFailureExperience = findViewById(R.id.btnFilterFailureExperience)

        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            Vendors.list.map { it.name })

        // 载入已保存的配置
        val savedId = Prefs.vendorId(this).ifBlank { "zhipu" }
        val savedIndex = Vendors.list.indexOfFirst { it.id == savedId }.coerceAtLeast(0)
        spinner.setSelection(savedIndex, false)
        etBaseUrl.setText(if (Prefs.baseUrl(this).isBlank()) Vendors.list[savedIndex].baseUrl else Prefs.baseUrl(this))
        etModel.setText(Prefs.model(this).ifBlank { Vendors.list[savedIndex].model })
        etApiKey.setText(Prefs.apiKey(this))
        bindPasswordToggle(etApiKey, findViewById(R.id.toggleApiKey))
        loading = false
        // 只套用厂商下拉/提示文案，不回填默认 baseUrl/model——否则刚载入的已保存自定义配置
        // 会被冲掉，用户改个 PIN 点保存就把自定义配置永久覆盖成厂商默认（体检第五轮 C1）
        applyVendorUi(savedIndex, fillDefaults = false)

        // Android Spinner 注册 listener 后首次 layout 会异步回调一次 onItemSelected（setSelection(pos,false)
        // 挡不住），彼时 loading 已为 false，仅靠 loading 挡不住这次"假切换"，自定义配置照样被冲掉
        // （体检第六轮 P2）。再按"已套用厂商"去重：回调报告的 position 与当前厂商相同就不是用户主动切换
        var appliedVendorIndex = savedIndex
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (loading || position == appliedVendorIndex) return
                // 用户主动切换厂商：这才应该用新厂商的默认端点/模型填输入框
                appliedVendorIndex = position
                applyVendorUi(position, fillDefaults = true)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        findViewById<Button>(R.id.btnFetchModels).setOnClickListener {
            val base = etBaseUrl.text.toString().trim()
            val key = etApiKey.text.toString().trim()
            NetGuard.cleartextProblem(base)?.let { msg ->
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val btn = it as Button
            btn.isEnabled = false
            btn.text = "拉取中…"
            Thread {
                val result = runCatching { LlmClient.listModels(base, key) }
                runOnUiThread {
                    btn.isEnabled = true
                    btn.text = "拉取"
                    result.onSuccess { models ->
                        // 接口返回的列表和内置候选合并：AutoGLM-Phone 这类特殊模型不在 /models 里，但必须可选
                        val merged = (currentVendor().models + models).distinct()
                        if (merged.isEmpty()) {
                            Toast.makeText(this, "接口返回了空列表", Toast.LENGTH_SHORT).show()
                        } else {
                            etModel.setAdapter(
                                ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, merged)
                            )
                            if (etModel.text.isNullOrBlank() && merged.isNotEmpty()) etModel.setText(merged[0], false)
                            Toast.makeText(this, "拉到 ${models.size} 个模型（含内置候选），点输入框查看", Toast.LENGTH_SHORT).show()
                        }
                    }.onFailure { e ->
                        // 拉取失败不影响使用：直接给内置候选（含 autoglm-phone）
                        val builtin = currentVendor().models
                        if (builtin.isNotEmpty()) {
                            etModel.setAdapter(
                                ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, builtin)
                            )
                            Toast.makeText(this, "接口拉取失败（${e.message?.take(50) ?: "未知错误"}），已显示内置候选", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(this, "拉取失败：${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }.start()
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            val base = etBaseUrl.text.toString().trim()
            val model = etModel.text.toString().trim()
            NetGuard.cleartextProblem(base)?.let { msg ->
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (model.isBlank()) {
                Toast.makeText(this, "模型名不能为空", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Prefs.save(this, currentVendor().id, base, model, etApiKey.text.toString().trim())
            // 选了 AutoGLM-Phone 就默认开手机脑，两个开关保持一致
            if (model == "autoglm-phone" && !Prefs.autoGlm(this)) {
                Prefs.setAutoGlm(this, true)
                Toast.makeText(this, "已保存 ✅ 模型是 AutoGLM-Phone，已自动开启手机脑", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "已保存 ✅", Toast.LENGTH_SHORT).show()
            }
            finish()
        }

        findViewById<Button>(R.id.btnA11y).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "在列表里找到「AI小助手·自动执行」并打开开关", Toast.LENGTH_LONG).show()
        }

        val etPin = findViewById<EditText>(R.id.etPin)
        etPin.setText(Prefs.pin(this))
        bindPasswordToggle(etPin, findViewById(R.id.togglePin))
        findViewById<Button>(R.id.btnSavePin).setOnClickListener {
            val v = etPin.text.toString().trim()
            Prefs.setPin(this, v)
            Toast.makeText(
                this,
                if (v.isBlank()) "已清除PIN（熄屏时不会自动解锁）" else "PIN已保存 ✅ 熄屏时可自动亮屏解锁",
                Toast.LENGTH_LONG
            ).show()
        }

        findViewById<Button>(R.id.btnNotif).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            Toast.makeText(this, "找到「AI小助手」并允许读取通知", Toast.LENGTH_LONG).show()
        }

        val swShowThinking = findViewById<JellyToggle>(R.id.swShowThinking)
        swShowThinking.setCheckedWithoutCallback(Prefs.showThinking(this))
        swShowThinking.onStateChanged = { checked ->
            Prefs.setShowThinking(this, checked)
        }

        val swDeepThink = findViewById<JellyToggle>(R.id.swDeepThink)
        swDeepThink.setCheckedWithoutCallback(!Prefs.fastMode(this))
        swDeepThink.onStateChanged = { checked ->
            Prefs.setFastMode(this, !checked)
        }

        val swAutoGlm = findViewById<JellyToggle>(R.id.swAutoGlm)
        swAutoGlm.setCheckedWithoutCallback(Prefs.autoGlm(this))
        swAutoGlm.onStateChanged = { checked ->
            Prefs.setAutoGlm(this, checked)
            if (checked) {
                val vid = Prefs.vendorId(this)
                if (vid != "zhipu" && vid != "zhipu_coding") {
                    Toast.makeText(
                        this,
                        "手机脑走智谱接口：请把上方厂商切到「智谱 GLM」并填智谱 API Key（AutoGLM-Phone 目前限时免费）",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(
                        this,
                        "已开启手机脑：执行任务时会自动请求屏幕录制授权，之后 AI 靠截图看屏幕",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        // 开放式任务挂机上限：点一下弹数字输入（5-240分钟），到点询问是否继续、不点默认继续
        val tvSessionCapValue = findViewById<android.widget.TextView>(R.id.tvSessionCapValue)
        fun renderSessionCap() {
            tvSessionCapValue.text = "${Prefs.openEndedSessionMinutes(this)}分钟"
        }
        renderSessionCap()
        findViewById<android.view.View>(R.id.rowSessionCap).setOnClickListener {
            val input = EditText(this).apply {
                hint = "5-240 分钟"
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText(Prefs.openEndedSessionMinutes(this@SettingsActivity).toString())
            }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("开放式任务挂机上限（分钟）")
                .setMessage("「刷视频赚金币」这类不说停的任务，每挂机这么久暂停问一次要不要继续；2分钟内没点就默认继续挂机。")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存") { _, _ ->
                    val v = input.text.toString().toIntOrNull() ?: return@setPositiveButton
                    Prefs.setOpenEndedSessionMinutes(this, v)
                    renderSessionCap()
                    Toast.makeText(this, "已保存：每轮挂机${Prefs.openEndedSessionMinutes(this)}分钟", Toast.LENGTH_SHORT).show()
                }
                .show()
        }

        swDeveloperMode.setCheckedWithoutCallback(Prefs.developerMode(this))
        llDeveloperPanel.visibility = if (swDeveloperMode.isChecked) android.view.View.VISIBLE else android.view.View.GONE
        swDeveloperMode.onStateChanged = { checked ->
            Prefs.setDeveloperMode(this, checked)
            llDeveloperPanel.visibility = if (checked) android.view.View.VISIBLE else android.view.View.GONE
            if (checked) renderExperienceList() else llExperienceList.removeAllViews()
        }

        btnFilterAllExperience.setOnClickListener {
            experienceRenderer.filter = ExperienceListRenderer.Filter.ALL
            renderExperienceList()
        }
        btnFilterSuccessExperience.setOnClickListener {
            experienceRenderer.filter = ExperienceListRenderer.Filter.SUCCESS
            renderExperienceList()
        }
        btnFilterFailureExperience.setOnClickListener {
            experienceRenderer.filter = ExperienceListRenderer.Filter.FAILURE
            renderExperienceList()
        }

        findViewById<Button>(R.id.btnClearExperience).setOnClickListener {
            val input = EditText(this).apply {
                hint = "输入密码（6位数字）"
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                        android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("清空行为记录")
                .setMessage("所有正确和错误行为记录都会删除，且无法恢复。\n输入密码以确认：")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", null)
                .create()
                .apply {
                    setOnShowListener {
                        getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                            if (input.text.toString() == CLEAR_PASSWORD) {
                                AgentExperienceStore.clear(this@SettingsActivity)
                                renderExperienceList()
                                Toast.makeText(this@SettingsActivity, "行为记录已清空", Toast.LENGTH_SHORT).show()
                                dismiss()
                            } else {
                                Toast.makeText(this@SettingsActivity, "密码错误", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                .show()
        }

        // 错误日志查看/导出（开发者面板里）
        val llDeveloperPanelLayout = llDeveloperPanel
        val btnViewLog = Button(this).apply {
            text = "查看错误日志"
            setOnClickListener {
                val logFile = CrashLogger.logFile(this@SettingsActivity)
                // 只读前 4KB：日志文件上限 8MB，整文件 readText() 会卡主线程（体检 P2-16）
                val content = if (logFile.exists()) {
                    logFile.inputStream().use { input ->
                        val buf = ByteArray(4096)
                        val n = input.read(buf)
                        if (n <= 0) "（空日志）" else String(buf, 0, n, Charsets.UTF_8)
                    }
                } else "暂无错误日志"
                androidx.appcompat.app.AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("错误日志（最近部分）\n${CrashLogger.deviceInfo()}")
                    .setMessage(content)
                    .setPositiveButton("清空") { _, _ ->
                        CrashLogger.clear(this@SettingsActivity)
                        Toast.makeText(this@SettingsActivity, "日志已清空", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("关闭", null)
                    .show()
            }
        }
        llDeveloperPanelLayout.addView(btnViewLog)
    }

    private fun applyVendorUi(position: Int, fillDefaults: Boolean) {
        val v = Vendors.list.getOrNull(position) ?: return
        if (fillDefaults) {
            etBaseUrl.setText(v.baseUrl)
            etModel.setText(v.model)
        }
        etModel.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, v.models))
        etModel.setOnClickListener { if (v.models.isNotEmpty()) etModel.showDropDown() }
        tvKeyHint.text = v.keyHint
        if (v.keyUrl.isNotBlank()) {
            tvKeyHint.append("\n👉 ${v.keyUrl}（点我打开）")
            tvKeyHint.setOnClickListener {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(v.keyUrl))) } catch (e: Exception) {}
            }
        } else {
            tvKeyHint.setOnClickListener(null)
        }
    }

    private fun bindPasswordToggle(field: EditText, toggle: ImageView) {
        var visible = false
        toggle.setOnClickListener {
            visible = !visible
            val selection = field.selectionEnd.coerceIn(0, field.text.length)
            field.transformationMethod = if (visible) null else PasswordTransformationMethod.getInstance()
            field.setSelection(selection)
            toggle.setImageResource(if (visible) R.drawable.ic_eye_open else R.drawable.ic_eye_off)
            toggle.setColorFilter(
                ContextCompat.getColor(
                    this,
                    if (visible) R.color.colorTextPrimary else R.color.colorTextSecondary
                ),
                PorterDuff.Mode.SRC_IN
            )
        }
    }

    private fun renderExperienceList() {
        if (!Prefs.developerMode(this)) return

        val (total, successCount) = experienceRenderer.render()
        tvExperienceSummary.text =
            "共 $total 条 · 正确 $successCount · 错误 ${total - successCount}"
        updateExperienceFilterUi()
    }

    private fun updateExperienceFilterUi() {
        val selectedBg = R.drawable.bg_developer_filter_selected
        val unselectedBg = R.drawable.bg_developer_filter_unselected
        val selectedColor = ContextCompat.getColor(this, R.color.colorOnPrimary)
        val unselectedColor = ContextCompat.getColor(this, R.color.colorTextPrimary)

        listOf(
            btnFilterAllExperience to (experienceRenderer.filter == ExperienceListRenderer.Filter.ALL),
            btnFilterSuccessExperience to (experienceRenderer.filter == ExperienceListRenderer.Filter.SUCCESS),
            btnFilterFailureExperience to (experienceRenderer.filter == ExperienceListRenderer.Filter.FAILURE)
        ).forEach { (button, selected) ->
            button.setBackgroundResource(if (selected) selectedBg else unselectedBg)
            button.setTextColor(if (selected) selectedColor else unselectedColor)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermStatus()
        renderExperienceList()
    }

    private fun a11yEnabled(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return s.contains(packageName)
    }

    private fun notifEnabled(): Boolean {
        val s = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return s.contains(packageName)
    }

    private fun refreshPermStatus() {
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val contacts = checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        findViewById<TextView>(R.id.tvPermStatus).text =
            "无障碍自动执行: ${if (a11yEnabled()) "✅ 已开启" else "❌ 未开启"}\n" +
            "通知读取: ${if (notifEnabled()) "✅ 已开启" else "❌ 未开启"}\n" +
            "麦克风: ${if (mic) "✅" else "❌"}   通讯录: ${if (contacts) "✅" else "❌"}"
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshPermStatus()
    }
}
