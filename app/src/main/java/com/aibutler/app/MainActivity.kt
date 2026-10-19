package com.aibutler.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.text.Editable
import android.text.TextWatcher
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ContextThemeWrapper
import android.widget.LinearLayout
import android.widget.ScrollView
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: ChatAdapter
    private lateinit var etInput: EditText
    private lateinit var rv: RecyclerView
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var tvTts: ImageView
    private lateinit var tvProvider: TextView
    private lateinit var conversationAdapter: ConversationHistoryAdapter
    private lateinit var rvConversations: RecyclerView
    private lateinit var tvHistoryEmpty: TextView

    /** 大模型的对话历史（含工具调用记录） */
    private val history = JsonArray()

    @Volatile
    private var busy = false

    private var cancelRequested: Boolean
        get() = TaskControlBus.pauseRequested
        set(value) {
            TaskControlBus.pauseRequested = value
        }

    private var lastA11yOk: Boolean? = null

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speakOn = true
    private var recognizer: SpeechRecognizer? = null
    private var pendingLearningFinish = false
    private var learningTargetPickerVisible = false
    private var captureStarting = false

    private data class LearningTargetOption(
        val label: String,
        val packageName: String,
        val fromCurrentScreen: Boolean = false
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        android.util.Log.i("AIButler", "APP onCreate build=v10-10-1600 (双脑切换+AutoGLM专用脑+全能力)")
        Prefs.ensureThinkingVisible(this)

        adapter = ChatAdapter(this)
        adapter.loadPersisted()
        TaskControlBus.pauseHandler = {
            if (busy) {
                tts?.stop()
                runOnUiThread {
                    adapter.add(ChatAdapter.Msg("system", "⏸️ 收到，正在暂停（当前步骤完成后停下）…"))
                    rv.scrollToPosition(adapter.items.size - 1)
                }
            }
        }
        rv = findViewById(R.id.rvChat)
        drawerLayout = findViewById(R.id.drawerLayout)
        rvConversations = findViewById(R.id.rvConversations)
        tvHistoryEmpty = findViewById(R.id.tvHistoryEmpty)
        rv.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        rv.adapter = adapter
        conversationAdapter = ConversationHistoryAdapter(
            onConversationClick = ::openConversation,
            onDeleteClick = ::confirmDeleteConversation
        )
        rvConversations.layoutManager = LinearLayoutManager(this)
        rvConversations.adapter = conversationAdapter
        adapter.onItemsChanged = {
            updateEmptyState()
            refreshHistory()
        }

        etInput = findViewById(R.id.etInput)
        tvTts = findViewById(R.id.tvTts)
        tvProvider = findViewById(R.id.tvProvider)

        findViewById<SubmitMorphButton>(R.id.btnSend).setOnClickListener {
            if (busy) {
                // 执行中：按钮变成"停止"
                cancelRequested = true
                tts?.stop()
                adapter.add(ChatAdapter.Msg("system", "⏸️ 收到，正在暂停（当前步骤完成后停下）…"))
            } else {
                val text = etInput.text.toString().trim()
                if (text.isNotEmpty()) {
                    etInput.setText("")
                    runAgent(text, fromVoice = false)
                }
            }
        }

        findViewById<ImageView>(R.id.btnMic).setOnClickListener { startVoice() }
        findViewById<HoldToConfirmLayout>(R.id.tvDeleteHistory).onConfirmed = {
            if (busy) {
                Toast.makeText(this, "任务执行中，无法清空历史", Toast.LENGTH_SHORT).show()
            }
            else {
                while (history.size() > 0) history.remove(0)
                adapter.clearAllConversations()
                updateEmptyState()
                refreshHistory()
                Toast.makeText(this, "历史对话已清空", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<ImageView>(R.id.btnMenu).setOnClickListener {
            refreshHistory()
            drawerLayout.openDrawer(android.view.Gravity.START)
        }
        findViewById<View>(R.id.btnNewChat).setOnClickListener { startNewConversation(closeDrawer = true) }
        findViewById<View>(R.id.btnTopNewChat).setOnClickListener { startNewConversation(closeDrawer = false) }
        findViewById<View>(R.id.btnDrawerSettings).setOnClickListener {
            drawerLayout.closeDrawer(android.view.Gravity.START)
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.btnStartLearning).setOnClickListener {
            startLearningMode()
        }
        findViewById<View>(R.id.btnLearningTasks).setOnClickListener {
            showLearningTaskDialog()
        }
        findViewById<View>(R.id.btnVisualSamples).setOnClickListener {
            showVisualSampleLauncher()
        }
        tvProvider.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        tvTts.setOnClickListener {
            speakOn = !speakOn
            tvTts.setImageResource(if (speakOn) R.drawable.ic_sound_on else R.drawable.ic_sound_off)
        }

        val tvEye = findViewById<ImageView>(R.id.tvEye)
        tvEye.setOnClickListener {
            if (CaptureService.isActive()) {
                CaptureService.stopAll()
                captureStarting = false
                updateEyeIcon()
                Toast.makeText(this, "屏幕视觉已关闭", Toast.LENGTH_SHORT).show()
            } else {
                if (android.os.Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 300)
                    return@setOnClickListener
                }
                requestCapture()
            }
        }

        listOf(
            R.id.suggestionClock to "帮我设个明天早上 8 点的闹钟",
            R.id.suggestionWechat to "打开微信",
            R.id.suggestionNews to "搜一下今天的新闻",
            R.id.suggestionNote to "记一下：周三要交报告"
        ).forEach { (viewId, prompt) ->
            findViewById<View>(viewId).setOnClickListener {
                etInput.setText(prompt)
                etInput.setSelection(prompt.length)
                etInput.requestFocus()
            }
        }

        tts = TextToSpeech(this) { status ->
            val lang = tts?.setLanguage(Locale.CHINA)
            ttsReady = status == TextToSpeech.SUCCESS &&
                    lang != TextToSpeech.LANG_MISSING_DATA &&
                    lang != TextToSpeech.LANG_NOT_SUPPORTED
        }

        updateProviderChip()
        updateEmptyState()
        refreshHistory()

        // 一键授权：app启动后自动弹出所有缺失的权限授权框
        ensurePermissions()

        // 界面重建后，若后台线程仍在干活，让新界面持续刷新（每2秒）
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                if (busy) {
                    adapter.notifyDataSetChanged()
                    rv.scrollToPosition(adapter.items.size - 1)
                }
                h.postDelayed(this, 2000)
            }
        }
        h.postDelayed(tick, 2000)

        // 调试令牌：仅用于 adb 远程测试（令牌打印在 logcat，只有有 adb 权限的人能看到）
        val sp = Prefs.get(this)
        var dbgTok = sp.getString("dbgToken", null)
        if (dbgTok == null) {
            dbgTok = java.util.UUID.randomUUID().toString().replace("-", "").take(10)
            sp.edit().putString("dbgToken", dbgTok).apply()
        }
        android.util.Log.i("AIButler", "DBG_TOKEN=$dbgTok")
        handleDebugSend()
    }

    private fun startNewConversation(closeDrawer: Boolean) {
        if (busy) {
            Toast.makeText(this, "任务进行中，请先停止后再开启新对话", Toast.LENGTH_SHORT).show()
            return
        }
        while (history.size() > 0) history.remove(0)
        adapter.newConversation()
        updateEmptyState()
        refreshHistory()
        if (closeDrawer) {
            drawerLayout.closeDrawer(android.view.Gravity.START)
        }
        Toast.makeText(this, "已开启新对话", Toast.LENGTH_SHORT).show()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDebugSend()
    }

    /** adb 测试入口：am start --es dbgToken XXX --es dbgsend_b64 <base64中文指令> */
    private fun handleDebugSend() {
        val i = intent ?: return
        val cmdB64 = i.getStringExtra("dbgsend_b64") ?: return
        val tok = Prefs.get(this).getString("dbgToken", null)
        android.util.Log.i("AIButler", "DBG handle: cmdB64=${cmdB64.take(20)}… tok=$tok match=${i.getStringExtra("dbgToken") == tok}")
        if (tok == null || i.getStringExtra("dbgToken") != tok) return
        i.removeExtra("dbgsend_b64")
        i.removeExtra("dbgToken")
        try {
            val msg = String(
                android.util.Base64.decode(cmdB64, android.util.Base64.DEFAULT),
                Charsets.UTF_8
            ).trim()
            if (msg.isNotEmpty()) {
                etInput.setText("")
                if (busy) {
                    // 自动化测试会重复下发同一条指令：正在跑的任务直接叫停、新任务排队，
                    // 保证每次 am start 都开启全新一轮，而不是被 pendingRun 吞掉造成轮次污染
                    cancelRequested = true
                    pendingRun = msg
                    android.util.Log.i("AIButler", "AGLM dbg send while busy: cancel current task, queue new round")
                } else {
                    runAgent(msg, fromVoice = false)
                }
            }
        } catch (e: Exception) {
            adapter.add(ChatAdapter.Msg("system", "调试消息解析失败：${e.message}"))
        }
    }

    override fun onResume() {
        super.onResume()
        updateProviderChip()
        updateA11yBanner()
        updateEyeIcon()
        if (pendingLearningFinish) {
            pendingLearningFinish = false
            promptSaveLearningTask()
        }
        removePermissionWarnings()
        updateEmptyState()
    }

    /** 检查所有运行时权限，缺失的直接弹授权框 */
    private fun ensurePermissions() {
        val needed = ArrayList<String>()
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.RECORD_AUDIO)
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.READ_CONTACTS)
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), 400)
            return
        }
        // 运行时权限都有了，检查截图
        if (!CaptureService.isActive()) requestCapture()
    }

    /** 权限已全部开启时，清除聊天里的"XX未启动"警告 */
    private fun removePermissionWarnings() {
        val toRemove = adapter.items.filter {
            it.role == "system" && (it.text.contains("未连接") || it.text.contains("未启动") ||
                it.text.contains("用不了") || it.text.contains("重试"))
        }
        if (toRemove.isNotEmpty()) {
            adapter.items.removeAll(toRemove)
            adapter.notifyDataSetChanged()
            adapter.saveCurrent()
        }
    }

    private fun refreshHistory() {
        val conversations = ConversationStore.list(this)
        val currentId = ConversationStore.currentId(this)
        conversationAdapter.submit(conversations, currentId)
        tvHistoryEmpty.visibility = if (conversations.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openConversation(conversation: ConversationStore.Conversation) {
        if (busy) {
            Toast.makeText(this, "任务进行中，暂不能切换对话", Toast.LENGTH_SHORT).show()
            return
        }
        while (history.size() > 0) history.remove(0)
        adapter.switchTo(conversation.id)
        updateEmptyState()
        refreshHistory()
        rv.scrollToPosition(adapter.items.size - 1)
        drawerLayout.closeDrawer(android.view.Gravity.START)
    }

    private fun confirmDeleteConversation(conversation: ConversationStore.Conversation) {
        if (busy) {
            Toast.makeText(this, "任务进行中，暂不能删除对话", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("删除对话")
            .setMessage("「${conversation.title}」删除后无法恢复。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                while (history.size() > 0) history.remove(0)
                adapter.deleteConversation(conversation.id)
                updateEmptyState()
                refreshHistory()
                Toast.makeText(this, "对话已删除", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun requestCapture() {
        if (projectionRequestActive) return
        projectionRequestActive = true
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), 301)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        projectionRequestActive = false
        if (requestCode == 301 && resultCode == RESULT_OK && data != null) {
            CaptureService.start(this, resultCode, data)
            Toast.makeText(this, "✅ 屏幕视觉已开启，AI 能看懂图标了", Toast.LENGTH_LONG).show()
            observeCaptureStart()
            val pending = pendingRun
            if (pending != null) {
                pendingRun = null
                Thread {
                    var waited = 0
                    while (!CaptureService.isActive() && waited < 8000) {
                        Thread.sleep(200)
                        waited += 200
                    }
                    runOnUiThread {
                        if (CaptureService.isActive()) {
                            adapter.add(ChatAdapter.Msg("system", "▶️ 屏幕视觉已就绪，继续执行刚才的任务"))
                            runAgent(pending, fromVoice = false)
                        } else {
                            pendingRun = pending
                            adapter.add(ChatAdapter.Msg("system", "⚠️ 屏幕视觉启动超时，任务已保留。请重新发一次指令。"))
                        }
                    }
                }.start()
            }
        }
    }

    private fun updateEyeIcon() {
        val eye = findViewById<ImageView>(R.id.tvEye)
        eye.setImageResource(
            if (CaptureService.isActive() || captureStarting) R.drawable.ic_eye_open
            else R.drawable.ic_eye_closed
        )
        eye.contentDescription = if (CaptureService.isActive() || captureStarting) "关闭屏幕视觉" else "开启屏幕视觉"
    }

    private fun observeCaptureStart() {
        captureStarting = true
        updateEyeIcon()
        Thread {
            var waited = 0
            while (!CaptureService.isActive() && waited < 8000) {
                Thread.sleep(200)
                waited += 200
            }
            runOnUiThread {
                captureStarting = false
                updateEyeIcon()
            }
        }.start()
    }

    /** 无障碍断连自检：断开/恢复时在聊天里提示，避免默默失败 */
    private fun updateA11yBanner() {
        val ok = AssistantAccessibilityService.instance != null
        if (lastA11yOk == null || lastA11yOk != ok) {
            if (!ok) {
                adapter.add(ChatAdapter.Msg("system", "⚠️ 无障碍服务未连接，发指令时会自动弹出授权页面"))
            } else {
                removePermissionWarnings()
                adapter.add(ChatAdapter.Msg("system", "✅ 无障碍服务已连接，可以自动操作手机了"))
            }
        }
        lastA11yOk = ok
    }

    private fun updateProviderChip() {
        tvProvider.visibility = View.GONE
        findViewById<TextView>(R.id.tvDrawerProvider).text =
            if (Prefs.configured(this)) "${Vendors.byId(Prefs.vendorId(this)).name} · ${Prefs.model(this)}"
            else "未配置"
    }

    private fun updateEmptyState() {
        findViewById<View>(R.id.emptyState).visibility =
            if (adapter.items.isEmpty()) View.VISIBLE else View.GONE
    }

    // ---------- 大脑主循环 ----------

    // ---------- 思考过程展示 ----------

    /**
     * 把模型返回的思考内容单独显示成"思考过程"气泡。
     * 兼容三种来源：reasoning_content（DeepSeek/GLM/Kimi 等）、reasoning、以及正文里的 <think> 标签。
     * 只用于展示，永远不写回接口历史（回传会因为 message 结构非法报错）。
     */
    private fun emitThinking(raw: JsonObject) {
        if (!Prefs.showThinking(this)) return
        var text = firstText(raw, "reasoning_content", "reasoning", "thinking")
        if (text.isBlank()) {
            val content = raw.get("content")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            text = extractThinkTags(content).orEmpty()
        }
        if (text.isBlank()) return
        runOnUiThread {
            adapter.addThink(text)
            rv.scrollToPosition(adapter.items.size - 1)
        }
    }

    /** 依次取第一个非空文本字段，兼容字符串与 [{text:...}] 两种返回格式 */
    private fun firstText(raw: JsonObject, vararg keys: String): String {
        for (k in keys) {
            val e = raw.get(k) ?: continue
            if (e.isJsonNull) continue
            if (e.isJsonPrimitive) {
                val s = e.asString.trim()
                if (s.isNotEmpty()) return s
            } else if (e.isJsonArray) {
                val sb = StringBuilder()
                for (item in e.asJsonArray) {
                    if (item.isJsonPrimitive) {
                        sb.append(item.asString)
                    } else if (item.isJsonObject) {
                        item.asJsonObject.get("text")?.takeIf { it.isJsonPrimitive }?.asString?.let { sb.append(it) }
                            ?: item.asJsonObject.get("content")?.takeIf { it.isJsonPrimitive }?.asString?.let { sb.append(it) }
                    }
                }
                val s = sb.toString().trim()
                if (s.isNotEmpty()) return s
            }
        }
        return ""
    }

    private fun extractThinkTags(content: String): String? {
        if (content.isBlank()) return null
        Regex("(?s)<think>(.*?)</think>", RegexOption.IGNORE_CASE).find(content)?.let { return it.groupValues[1].trim() }
        Regex("(?s) thinking(.*?)<｜end▁of▁thinking｜>", RegexOption.IGNORE_CASE).find(content)?.let { return it.groupValues[1].trim() }
        return null
    }

    /**
     * 模型没按协议输出时，把它的原文当作"思考过程"展示。
     * 这段文字通常正是"当前在哪一页、准备做什么、为什么"的推理，丢掉等于把最有用的信息扔了。
     */
    private fun showRawAsThinking(raw: String) {
        if (raw.isBlank() || !Prefs.showThinking(this)) return
        val body = extractThinkTags(raw)
            ?: raw.replace(Regex("(?s)<answer>.*?</answer>", RegexOption.IGNORE_CASE), "").trim()
        if (body.isBlank()) return
        runOnUiThread {
            adapter.addThink(body)
            rv.scrollToPosition(adapter.items.size - 1)
        }
    }

    /** 正文里混着思考标签时，只把真正的答案部分给用户 */
    private fun stripThinkTags(content: String): String {
        if (content.isBlank()) return content
        return content
            .replace(Regex("(?s)<think>.*?</think>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("(?s) thinking.*?<｜end▁of▁thinking｜>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^\\s*\\[notool\\]\\s*"), "")
            .trim()
    }

    private fun showVisualSampleLauncher() {
        val service = AssistantAccessibilityService.instance
        if (service == null) {
            adapter.add(ChatAdapter.Msg("system", "本地图形样本需要先开启无障碍服务。"))
            return
        }
        if (!CaptureService.isActive()) {
            Toast.makeText(this, "先点顶部眼睛按钮开启屏幕视觉", Toast.LENGTH_LONG).show()
            return
        }
        drawerLayout.closeDrawer(android.view.Gravity.START)
        VisualSampleController.instance?.show()
        Toast.makeText(this, "回到目标页面后点左上方“存图形样本”", Toast.LENGTH_LONG).show()
    }

    private fun startLearningMode() {
        if (busy) {
            Toast.makeText(this, "AI 正在执行任务，请先停止后再开始学习", Toast.LENGTH_SHORT).show()
            return
        }
        val service = AssistantAccessibilityService.instance
        val controller = LearningModeController.instance
        if (service == null || controller == null) {
            adapter.add(ChatAdapter.Msg("system", "学习模式需要先开启无障碍服务。"))
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        if (controller.isActive) {
            Toast.makeText(this, "学习模式已经开始了", Toast.LENGTH_SHORT).show()
            return
        }
        if (learningTargetPickerVisible) return

        learningTargetPickerVisible = true
        Thread {
            val pm = packageManager
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = pm.queryIntentActivities(launcherIntent, 0)
                .mapNotNull { info ->
                    val label = try {
                        info.loadLabel(pm).toString()
                    } catch (_: Exception) {
                        null
                    }
                    if (label.isNullOrBlank() || info.activityInfo.packageName == packageName) {
                        null
                    } else {
                        LearningTargetOption(label, info.activityInfo.packageName)
                    }
                }
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase(Locale.CHINA) }

            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    learningTargetPickerVisible = false
                    return@runOnUiThread
                }
                showLearningTargetPicker(service, apps)
            }
        }.start()
    }

    private fun showLearningTargetPicker(
        service: AssistantAccessibilityService,
        apps: List<LearningTargetOption>
    ) {
        val currentPackage = service.currentPackage()
        val currentLabel = if (currentPackage == packageName || currentPackage == "unknown") {
            "AI小助手"
        } else {
            LearningTaskStore.appLabel(this, currentPackage)
        }
        fun dp(value: Int): Int {
            return (value * resources.displayMetrics.density).toInt()
        }

        val dialogContext = ContextThemeWrapper(this, R.style.Theme_Assistant_LightDialog)
        val root = LinearLayout(dialogContext).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.colorSurface))
            setPadding(dp(18), dp(10), dp(18), dp(4))
        }
        root.addView(TextView(dialogContext).apply {
            text = "先选择学习目标"
            textSize = 18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.colorTextPrimary))
            setPadding(0, dp(8), 0, dp(12))
        })
        val currentOption = TextView(dialogContext).apply {
            text = "使用当前界面（$currentLabel）"
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.colorPrimary))
            background = getDrawable(R.drawable.bg_section_button)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setOnClickListener {
                beginLearningMode(
                    LearningTargetOption("当前界面", currentPackage, fromCurrentScreen = true)
                )
            }
        }
        root.addView(currentOption)

        root.addView(TextView(dialogContext).apply {
            text = "搜索或选择应用"
            textSize = 13f
            setTextColor(getColor(R.color.colorTextSecondary))
            setPadding(dp(2), dp(16), dp(2), dp(8))
        })

        val search = EditText(dialogContext).apply {
            hint = "应用名称"
            setSingleLine(true)
            setBackgroundColor(getColor(R.color.colorInputField))
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        root.addView(search)

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        fun renderApps(query: String) {
            list.removeAllViews()
            val selected = apps.filter {
                query.isBlank() || it.label.contains(query, ignoreCase = true)
            }
            if (selected.isEmpty()) {
                list.addView(TextView(dialogContext).apply {
                    text = "没有匹配的应用"
                    textSize = 14f
                    setTextColor(getColor(R.color.colorTextSecondary))
                    setPadding(dp(2), dp(16), dp(2), dp(16))
                })
                return
            }
            selected.forEach { app ->
                list.addView(TextView(dialogContext).apply {
                    text = app.label
                    textSize = 15f
                    setTextColor(getColor(R.color.colorTextPrimary))
                    setPadding(dp(2), dp(13), dp(2), dp(13))
                    setOnClickListener { beginLearningMode(app) }
                })
            }
        }
        renderApps("")

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                renderApps(s?.toString().orEmpty().trim())
            }
        })

        val scroll = ScrollView(dialogContext).apply { addView(list) }
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.38f).toInt()
            )
        )

        AlertDialog.Builder(dialogContext)
            .setView(root)
            .setNegativeButton("取消", null)
            .setOnDismissListener { learningTargetPickerVisible = false }
            .show()
    }

    private fun beginLearningMode(target: LearningTargetOption) {
        val service = AssistantAccessibilityService.instance
        val controller = LearningModeController.instance
        if (service == null || controller == null || controller.isActive) {
            learningTargetPickerVisible = false
            return
        }
        val usableCurrent = target.fromCurrentScreen &&
            target.packageName.isNotBlank() &&
            target.packageName != "unknown"
        val lockTarget = usableCurrent && target.packageName != packageName
        val displayName = if (target.fromCurrentScreen && target.packageName == packageName) {
            "当前界面"
        } else {
            target.label
        }

        controller.onFinishRequested = ::requestLearningFinish
        controller.start(
            targetName = displayName,
            targetPackage = target.packageName,
            lockTarget = lockTarget
        )
        learningTargetPickerVisible = false
        drawerLayout.closeDrawer(android.view.Gravity.START)

        if (!target.fromCurrentScreen) {
            packageManager.getLaunchIntentForPackage(target.packageName)?.let { launch ->
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launch)
            }
        }

        adapter.add(ChatAdapter.Msg(
            "system",
            "🎓 已选择「$displayName」。准备好后点屏幕上方的「开始」，我会记录你的点击和滑动；完成后点同一个按钮（会变成「完成」）。\n\n" +
                "支付、转账、密码、验证码、删除这类操作不会被记录。"
        ))
    }

    private fun requestLearningFinish() {
        val controller = LearningModeController.instance ?: return
        val hasSteps = controller.stop()
        if (!hasSteps) {
            pendingLearningFinish = false
            runOnUiThread {
                adapter.add(ChatAdapter.Msg("system", "学习模式已结束，没有记录到可回放步骤。"))
            }
            return
        }
        pendingLearningFinish = true
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        (AssistantAccessibilityService.instance ?: this).startActivity(intent)
    }

    private fun promptSaveLearningTask() {
        val controller = LearningModeController.instance
        if (controller == null) {
            adapter.add(ChatAdapter.Msg("system", "学习模式已结束，没有记录到可回放步骤。"))
            return
        }
        val steps = controller.takeRecording()
        if (steps.isEmpty()) {
            adapter.add(ChatAdapter.Msg("system", "学习模式已结束，没有记录到可回放步骤。"))
            return
        }

        val input = EditText(this).apply {
            hint = "例如：打开B站搜索AI"
            setText(etInput.text.toString().trim())
            setSelection(text.length)
        }
        val target = steps.lastOrNull { step ->
            step.packageName.isNotBlank() &&
                step.packageName != packageName &&
                step.packageName == controller.learningTargetPackage
        } ?: steps.lastOrNull { it.packageName != packageName } ?: steps.first()
        val targetName = controller.learningTargetName.ifBlank {
            LearningTaskStore.appLabel(this, target.packageName)
        }
        val preview = steps.take(8).joinToString("\n") { "• ${LearningTaskStore.describeStep(it)}" } +
                if (steps.size > 8) "\n• ...共${steps.size}步" else ""

        AlertDialog.Builder(this)
            .setTitle("保存学习任务")
            .setMessage("已记录 ${steps.size} 步：\n$preview\n\n目标：$targetName\n\n给这个任务起一个以后能直接说出的名字。")
            .setView(input)
            .setNegativeButton("丢弃") { _, _ ->
                adapter.add(ChatAdapter.Msg("system", "已丢弃本次学习记录。"))
            }
            .setPositiveButton("保存") { _, _ ->
                val name = input.text.toString().trim().ifBlank { "未命名学习任务" }
                val task = LearningTask(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    taskText = name,
                    targetAppName = LearningTaskStore.appLabel(this, target.packageName),
                    targetPackage = target.packageName,
                    steps = steps,
                    createdAt = System.currentTimeMillis(),
                    highPriority = true
                )
                LearningTaskStore.save(this, task)
                etInput.setText("")
                adapter.add(ChatAdapter.Msg(
                    "assistant",
                    "✅ 已保存学习任务「${task.name}」（${task.steps.size}步）。以后说出任务名，我会先确认再回放。"
                ))
            }
            .show()
    }

    private fun showLearningTaskDialog() {
        val activity = this
        if (LearningModeController.instance?.isActive == true) {
            AlertDialog.Builder(this)
                .setTitle("学习模式进行中")
                .setMessage("学习悬浮条在屏幕上方。完成手动操作后点“完成”回来命名。")
                .setPositiveButton("好的", null)
                .show()
            return
        }
        val tasks = LearningTaskStore.all(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 12, 20, 8)
        }
        val scroll = ScrollView(this).apply { addView(container) }

        if (tasks.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "还没有学习任务。先点左侧的“开始学习任务”，手动完成一遍后保存。"
                textSize = 14f
                setTextColor(getColor(R.color.colorTextSecondary))
            })
        } else {
            tasks.forEach { task ->
                container.addView(TextView(this).apply {
                    text = "${task.name}\n${task.targetAppName} · ${task.steps.size}步 · 成功${task.successCount}次"
                    textSize = 15f
                    setTextColor(getColor(R.color.colorTextPrimary))
                    setPadding(0, 14, 0, 14)
                    setOnClickListener {
                        runAgent(task.taskText.ifBlank { task.name }, fromVoice = false)
                    }
                    setOnLongClickListener {
                        AlertDialog.Builder(activity)
                            .setTitle("删除学习任务")
                            .setMessage("确定删除「${task.name}」吗？")
                            .setNegativeButton("取消", null)
                            .setPositiveButton("删除") { _, _ ->
                                LearningTaskStore.delete(activity, task.id)
                                Toast.makeText(activity, "已删除", Toast.LENGTH_SHORT).show()
                            }
                            .show()
                        true
                    }
                })
            }
        }

        AlertDialog.Builder(activity)
            .setTitle("学习任务")
            .setView(scroll)
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun confirmLearningReplay(task: LearningTask): Boolean {
        val confirmed = booleanArrayOf(false)
        val latch = CountDownLatch(1)
        val detail = task.steps.take(12).joinToString("\n") { "• ${LearningTaskStore.describeStep(it)}" } +
                if (task.steps.size > 12) "\n• ...共${task.steps.size}步" else ""
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("回放「${task.name}」")
                .setMessage("将要执行：\n$detail\n\n确认后助手会逐步执行；失败时会交给 AI 继续处理。")
                .setNegativeButton("不使用学习路径") { _, _ -> latch.countDown() }
                .setPositiveButton("确认回放") { _, _ ->
                    confirmed[0] = true
                    latch.countDown()
                }
                .setOnCancelListener { latch.countDown() }
                .show()
        }
        latch.await(3, TimeUnit.MINUTES)
        return confirmed[0]
    }

    private fun executeLearningReplay(task: LearningTask): Boolean {
        runOnUiThread {
            adapter.add(ChatAdapter.Msg("system", "🎓 开始回放学习任务「${task.name}」…"))
        }
        val result = LearningTaskStore.replay(this, task) { cancelRequested }
        runOnUiThread {
            if (result.success) {
                LearningTaskStore.markUsed(this, task.id)
                adapter.add(ChatAdapter.Msg("assistant", "✅ 已完成学习任务「${task.name}」。"))
            } else if (result.cancelled) {
                adapter.add(ChatAdapter.Msg("system", "⏸️ 学习任务已停止。"))
            } else {
                adapter.add(ChatAdapter.Msg("system", "⚠️ ${result.message}。正在交给 AI 继续处理。"))
            }
        }
        return result.success
    }

    private fun runAgent(userText: String, fromVoice: Boolean, skipLearning: Boolean = false) {
        if (LearningModeController.instance?.isActive == true) {
            Toast.makeText(this, "学习模式进行中，请先点学习条上的“完成”", Toast.LENGTH_SHORT).show()
            return
        }
        if (busy) {
            Toast.makeText(this, "⏳ AI 正忙，任务已排队，完成后自动开始", Toast.LENGTH_SHORT).show()
            pendingRun = (pendingRun?.plus("\n") ?: "") + userText
            return
        }
        if (!Prefs.configured(this)) {
            adapter.add(ChatAdapter.Msg("system", "还没配置 AI 厂商，请先点右上角 ⚙️ 设置。"))
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        if (!checkPermissionsOrLaunch()) {
            pendingRun = userText
            return
        }
        val pw = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val km = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        if (!pw.isInteractive || km.isKeyguardLocked) {
            android.util.Log.i("AIButler", "APP 屏幕熄灭/锁定中启动任务——将尝试自动亮屏解锁")
        }
        busy = true
        cancelRequested = false
        TaskControlBus.taskRunning = true
        AssistantAccessibilityService.instance?.showTaskControls()
        setBusyUi(true)
        AgentKeepAliveService.start(this)
        adapter.add(ChatAdapter.Msg("user", userText))
        val contextUserText = adapter.items
            .filter { it.role == "user" && it.text.isNotBlank() }
            .takeLast(20)
            .joinToString("\n") { it.text }

        Thread {
            var paused = false
            var learningFallback = false
            try {
                if (!skipLearning) {
                    val learningTask = LearningTaskStore.findMatch(this, userText)
                    if (learningTask != null && learningTask.steps.isNotEmpty()) {
                        if (confirmLearningReplay(learningTask)) {
                            val learningOk = executeLearningReplay(learningTask)
                            if (!learningOk) learningFallback = true
                            return@Thread
                        } else {
                            learningFallback = true
                        }
                    }
                }

                // ===== 任务规划：先拆解再执行（简单的单步指令跳过规划，省一次大模型往返） =====
                val relatedExp = AgentExperienceStore.relevant(this, userText)
                var plan: AgentEngine.TaskPlan? = null
                var currentStep = 1
                var stepRetries = 0
                var replansUsed = 0
                val quickTask = userText.length <= 14 &&
                    !Regex("然后|接着|之后|最后|并且|同时|比较|每个|所有|一步步").containsMatchIn(userText) &&
                    Regex("^\\s*(请|帮我|麻烦|帮忙|给我|我)?(打开|启动|开启|关闭|退出|回到|回|搜一?下|搜索|搜|查一?下|查询|查|设一?个|设置|定一?个|定个|记一?下|记个|看看|看下|读一?下|拨|打给|发)").containsMatchIn(userText)
                if (quickTask) {
                    android.util.Log.i("AIButler", "简单指令，跳过任务规划：$userText")
                }
                try {
                    if (!quickTask) {
                        val plannerMsgs = JsonArray()
                        plannerMsgs.add(AgentEngine.msg("user", AgentEngine.plannerPrompt(userText, contextUserText)))
                        val planRes = LlmClient.chat(
                            Prefs.baseUrl(this), Prefs.apiKey(this), Prefs.model(this),
                            plannerMsgs, JsonArray(), Prefs.fastMode(this)
                        )
                        plan = AgentEngine.parsePlan(planRes.content)
                    }
                } catch (e: Exception) {
                    android.util.Log.w("AIButler", "任务规划失败，降级为无计划执行", e)
                }

                // ===== AutoGLM-Phone 专用手机脑模式 =====
                // 纯聊天/知识问答不走手机操控（否则闲聊会误入 AutoGLM 空转）
                val chatPlan = plan?.taskType == "chat"
                // 规划器漏判时的兜底：明显是问候/自我介绍/知识问答，直接走聊天
                val looksLikeChat = !quickTask && Regex(
                    "^(你好|您好|嗨|hi|hello)|你是谁|介绍一下你自己|什么是|为什么|讲个|说个|写个故事|帮我解释"
                , RegexOption.IGNORE_CASE).containsMatchIn(userText.trim())
                // Bug#3：记便签/设闹钟/发短信等有原生 function calling 工具的指令不进视觉脑
                val nativeToolPattern = Regex("记一下|记个|便签|备忘|设.*闹钟|定.*闹钟|发短信|发消息给")
                val hasNativeToolMatch = nativeToolPattern.containsMatchIn(userText.trim())
                val skipAutoGlm = chatPlan || looksLikeChat || hasNativeToolMatch
                android.util.Log.i("AIButler", "AGLM routing: plan=${plan?.taskType ?: "null"} chatPlan=$chatPlan looksLikeChat=$looksLikeChat nativeTool=$hasNativeToolMatch")
                if (Prefs.autoGlm(this) && !skipAutoGlm) {
                    val vid = Prefs.vendorId(this)
                    if (vid == "zhipu" || vid == "zhipu_coding") {
                        runAutoGLMLoop(userText, contextUserText)
                        return@Thread
                    }
                    runOnUiThread {
                        adapter.add(ChatAdapter.Msg(
                            "system",
                            "⚠️ 手机脑（AutoGLM-Phone）需要智谱的 API Key，当前厂商不是智谱，本次任务改用普通大脑执行。" +
                                "要启用手机脑：⚙️ 设置 → 厂商选「智谱 GLM」→ 填 open.bigmodel.cn 的 Key → 打开「AutoGLM-Phone 手机脑」。"
                        ))
                        rv.scrollToPosition(adapter.items.size - 1)
                    }
                } else if (Prefs.autoGlm(this) && skipAutoGlm) {
                    android.util.Log.i("AIButler", "纯聊天/问答类请求，跳过AutoGLM：$userText")
                }
                if (plan?.steps?.isNotEmpty() == true) {
                    val shownPlan = plan!!
                    runOnUiThread {
                        val sb = StringBuilder("📋 任务规划：${shownPlan.taskType}")
                        if (shownPlan.targetApp.isNotBlank()) sb.append(" · ${shownPlan.targetApp}")
                        sb.append('\n')
                        shownPlan.steps.forEach { s -> sb.append("${s.id}. ${s.action}\n") }
                        adapter.add(ChatAdapter.Msg("system", sb.toString().trim()))
                        rv.scrollToPosition(adapter.items.size - 1)
                    }
                }

                // 确保 system 提示永远在第一位（JsonArray 不能按位插入，只能重建）
                if (history.size() == 0 || history.get(0).asJsonObject.get("role")?.asString != "system") {
                    val fresh = JsonArray()
                    fresh.add(AgentEngine.systemPrompt(plan, currentStep, relatedExp).let { AgentEngine.msg("system", it) })
                    for (e in history) fresh.add(e)
                    while (history.size() > 0) history.remove(0)
                    for (e in fresh) history.add(e)
                } else {
                    history.get(0).asJsonObject.addProperty("content", AgentEngine.systemPrompt(plan, currentStep, relatedExp))
                }
                history.add(AgentEngine.msg("user", userText))

                var done = false
                var round = 0
                val MAX_ROUNDS = 60
                var lastSig = ""
                var repCount = 0
                val sigWindow = ArrayList<String>()
                var lastScreenHash = ""
                var noProgress = 0
                while (true) {
                    round++
                    if (cancelRequested) { paused = true; break }
                    if (round > MAX_ROUNDS) {
                        android.util.Log.i("AIButler", "AGENT 达到最大轮次限制")
                        runOnUiThread {
                            adapter.add(ChatAdapter.Msg("system", "⏹ 任务执行超过${MAX_ROUNDS}轮仍未完成，已自动停止。请换个说法重试或手动完成剩余步骤。"))
                            rv.scrollToPosition(adapter.items.size - 1)
                        }
                        break
                    }
                    compactHistory()
                    // 动作后的页面加载已由各工具内部等待处理，这里只留短间隔让界面动画收尾
                    if (round > 1) Thread.sleep(300)
                    android.util.Log.i("AIButler", "AGENT round=$round 开始请求模型")
                    val res = LlmClient.chat(
                        Prefs.baseUrl(this), Prefs.apiKey(this), Prefs.model(this),
                        sanitizeHistory(), AgentEngine.toolsJson(), Prefs.fastMode(this)
                    )
                    android.util.Log.i("AIButler", "AGENT round=$round 返回: 文本=${res.content.length}字 工具调用=${res.toolCalls.size}个")
                    emitThinking(res.rawMessage)
                    // 深度思考内容不能回传给接口，剥掉；空内容也补齐，避免 message参数非法(1214)
                    val cleanMsg = res.rawMessage.deepCopy()
                    cleanMsg.remove("reasoning_content")
                    run {
                        val ce = cleanMsg.get("content")
                        val empty = ce == null || ce.isJsonNull || (ce.isJsonPrimitive && ce.asString.isBlank())
                        val hasCalls = (cleanMsg.getAsJsonArray("tool_calls")?.size() ?: 0) > 0
                        if (empty && !hasCalls) cleanMsg.addProperty("content", "（好）")
                    }
                    history.add(cleanMsg)

                    if (res.toolCalls.isEmpty()) {
                        val reply = stripThinkTags(res.content).ifBlank { "（AI 没有返回内容，可以再试一次）" }
                        android.util.Log.i("AIButler", "AGENT 最终回复: $reply")
                        saveAgentExperience(
                            userText,
                            reply,
                            requireSuccessConfirmation = plan?.taskType == "screen_task"
                        )
                        runOnUiThread {
                            adapter.add(ChatAdapter.Msg("assistant", reply))
                            rv.scrollToPosition(adapter.items.size - 1)
                            if (speakOn && fromVoice && ttsReady && !cancelRequested) speak(reply)
                        }
                        done = true
                        break
                    }

                    for (call in res.toolCalls) {
                        if (cancelRequested) { paused = true; break }
                        runOnUiThread { adapter.add(ChatAdapter.Msg("system", "🔧 正在执行：${call.name} ${call.arguments.take(100)}")) }
                        // ===== 步骤管理：update_step 不走 AgentEngine.execute，在本地处理 =====
                        if (call.name == "update_step") {
                            val stepArgs = try {
                                JsonParser.parseString(call.arguments).asJsonObject
                            } catch (e: Exception) { JsonObject() }
                            val stepNum = stepArgs.get("step")?.takeIf { !it.isJsonNull }?.asInt ?: currentStep
                            val stepStatus = stepArgs.get("status")?.takeIf { !it.isJsonNull }?.asString ?: "done"
                            val stepNote = stepArgs.get("note")?.takeIf { !it.isJsonNull }?.asString ?: ""
                            if (stepStatus == "done" && stepNum >= currentStep && plan?.steps?.isNotEmpty() == true) {
                                // 支持批量汇报：一次报第N步完成时，中间步骤视为已完成
                                currentStep = maxOf(currentStep, stepNum + 1)
                                stepRetries = 0
                                if (history.size() > 0 && history.get(0).asJsonObject.get("role")?.asString == "system") {
                                    history.get(0).asJsonObject.addProperty("content", AgentEngine.systemPrompt(plan, currentStep, relatedExp))
                                }
                            } else if (stepStatus == "failed") {
                                stepRetries++
                                if (stepRetries >= 2 && replansUsed < 2 && plan?.steps?.isNotEmpty() == true) {
                                    val failedAction = plan!!.steps.getOrNull(currentStep - 1)?.action ?: ""
                                    try {
                                        val replanMsgs = JsonArray()
                                        replanMsgs.add(AgentEngine.msg("user", AgentEngine.plannerPrompt(
                                            "$userText\n\n（注意：之前的计划在第${currentStep}步「${failedAction}」失败：${stepNote}。请重新规划剩余步骤，换一种方式完成。）",
                                            contextUserText
                                        )))
                                        val replanRes = LlmClient.chat(
                                            Prefs.baseUrl(this), Prefs.apiKey(this), Prefs.model(this),
                                            replanMsgs, JsonArray(), Prefs.fastMode(this)
                                        )
                                        val newPlan = AgentEngine.parsePlan(replanRes.content)
                                        if (newPlan?.steps?.isNotEmpty() == true) {
                                            plan = newPlan
                                            currentStep = 1
                                            stepRetries = 0
                                            replansUsed++
                                            if (history.size() > 0 && history.get(0).asJsonObject.get("role")?.asString == "system") {
                                            history.get(0).asJsonObject.addProperty("content", AgentEngine.systemPrompt(plan, currentStep, relatedExp))
                                            }
                                            runOnUiThread {
                                                adapter.add(ChatAdapter.Msg("system", "🔄 步骤失败，已重新规划"))
                                                rv.scrollToPosition(adapter.items.size - 1)
                                            }
                                        }
                                    } catch (e: Exception) {
                                        android.util.Log.w("AIButler", "重新规划失败", e)
                                    }
                                }
                            }
                            val stepResult = "步骤$stepNum 已标记$stepStatus${if (stepNote.isNotBlank()) "：$stepNote" else ""}。继续执行下一步。"
                            runOnUiThread {
                                adapter.add(ChatAdapter.Msg("system", "📋 步骤$stepNum: $stepStatus${if (stepNote.isNotBlank()) " ($stepNote)" else ""}"))
                                rv.scrollToPosition(adapter.items.size - 1)
                            }
                            history.add(AgentEngine.toolMsg(call.id, stepResult))
                            continue
                        }
                        val result = AgentEngine.execute(this@MainActivity, call.name, call.arguments)
                        // 防空转1：连续完全相同调用
                        val sig = call.name + ":" + call.arguments
                        if (sig == lastSig) repCount++ else { repCount = 1; lastSig = sig }
                        // 防空转2：滑动窗口内同一调用出现4次以上（防A/B交替循环）
                        sigWindow.add(sig)
                        if (sigWindow.size > 8) sigWindow.removeAt(0)
                        var spinNote = ""
                        if (repCount >= 3 || sigWindow.count { it == sig } >= 4) {
                            spinNote = "\n⚠️ 系统提示：检测到你反复执行相同/相似操作且无进展。请立刻换方法：换个目标、用 look_screen 仔细看清屏幕，或承认无法完成并汇报。禁止继续重复。"
                            android.util.Log.i("AIButler", "AGENT 重复防护触发 x$repCount")
                        }
                        // 防空转3：连续多次读到一模一样的屏幕（读屏不产出任何变化）
                        if (call.name == "get_screen_state") {
                            val hsh = result.hashCode().toString()
                            if (hsh == lastScreenHash) noProgress++ else { noProgress = 1; lastScreenHash = hsh }
                            if (noProgress >= 4) {
                                spinNote += "\n🛑 系统提示：你已连续${noProgress}次读到完全相同的屏幕，屏幕毫无变化=你在空转。请立即换策略：换页面、用 look_screen 看清可点目标、或结束任务并汇报原因。"
                                android.util.Log.i("AIButler", "AGENT 无进展防护触发 x$noProgress")
                            }
                        } else if (!call.name.startsWith("wait")) {
                            noProgress = 0
                        }
                        runOnUiThread {
                            adapter.add(ChatAdapter.Msg("system", result.take(300)))
                            rv.scrollToPosition(adapter.items.size - 1)
                        }
                        history.add(AgentEngine.toolMsg(call.id, (result + spinNote).take(4500)))
                    }
                    if (paused) break
                }
                if (paused) {
                    android.util.Log.i("AIButler", "AGENT 结束: 用户暂停")
                    runOnUiThread {
                        adapter.add(ChatAdapter.Msg("system", "⏸️ 已暂停。想继续就说「继续」，想改主意直接说新的指令。"))
                        rv.scrollToPosition(adapter.items.size - 1)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("AIButler", "AGENT 异常", e)
                runOnUiThread {
                    adapter.add(ChatAdapter.Msg("system", "❌ 出错了：${e.message}"))
                    rv.scrollToPosition(adapter.items.size - 1)
                }
            } finally {
                busy = false
                TaskControlBus.taskRunning = false
                AssistantAccessibilityService.instance?.hideTaskControls()
                AgentKeepAliveService.stop(this@MainActivity)
                runOnUiThread { setBusyUi(false, success = !cancelRequested) }
                if (learningFallback) {
                    runOnUiThread {
                        runAgent(userText, fromVoice, skipLearning = true)
                    }
                } else {
                    val next = pendingRun
                    if (next != null) {
                        pendingRun = null
                        runOnUiThread {
                            adapter.add(ChatAdapter.Msg("system", "▶️ 排队的任务开始了"))
                            runAgent(next, fromVoice = false)
                        }
                    }
                }
            }
        }.start()
    }

    /** 输入指令前检查权限：缺什么直接跳什么授权框，不只是提示文字 */
    private fun checkPermissionsOrLaunch(): Boolean {
        if (AssistantAccessibilityService.instance == null) {
            adapter.add(ChatAdapter.Msg("system", "⏳ 正在打开无障碍设置，请开启「AI小助手·自动执行」…"))
            rv.scrollToPosition(adapter.items.size - 1)
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return false
        }
        val runtimeNeeded = ArrayList<String>()
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            runtimeNeeded.add(Manifest.permission.POST_NOTIFICATIONS)
        if (runtimeNeeded.isNotEmpty()) {
            requestPermissions(runtimeNeeded.toTypedArray(), 300)
            return false
        }
        if (!CaptureService.isActive()) {
            if (!projectionRequestActive) {
                adapter.add(ChatAdapter.Msg("system", "⏳ 正在请求屏幕截图权限…"))
                rv.scrollToPosition(adapter.items.size - 1)
            }
            requestCapture()
            return false
        }
        return true
    }

    private var pendingRun: String? = null
    private var projectionRequestActive = false

    /** AutoGLM-Phone 专用脑模式：截图→模型出动作→本机执行（协议与 Open-AutoGLM 一致） */
    private fun runAutoGLMLoop(userText: String, contextUserText: String) {
        try {
            // Bug#5：路由只用当前任务句，不带上一次会话的历史，防止跨会话污染
            val guessedApp = DeviceTools.guessApp(this, userText)
                ?: inferTargetApp(userText, "")
            android.util.Log.i(
                "AIButler",
                "AGLM guessedApp=${guessedApp ?: "null"}"
            )
            val relatedMemory = AgentExperienceStore.relevant(this, userText, guessedApp?.second)
            val ctx = JsonArray()
            ctx.add(JsonObject().apply {
                addProperty("role", "system")
                addProperty(
                    "content",
                    AgentEngine.autoGlmSystemPrompt() + relatedMemory +
                        (guessedApp?.let {
                            "\n【通用路由结果】目标应用：${it.first}（${it.second}）。先进入这个应用，再在应用内完成任务。\n"
                        } ?: "\n【通用路由结果】未确定固定目标应用，先在桌面/系统界面选择合适入口；绝不能操作助手聊天界面。\n") +
                        "\n【通用探索规则】没有相关历史经验时，不要等待写死流程；先从任务句匹配手机上的真实应用，进入后逐页读屏，优先点击文字与目标最接近的原生入口。每次失败都要换路径，并把有效路径记入后续经验。\n"
                )
            })
            var finalReply: String? = null
            var round = 0
            var lastProgressSig = ""
            var noProgress = 0
            var fails = 0
            var parseFails = 0
            var lastScreenHash = ""
            var lastDumpSig = ""      // 上一轮无障碍页面文本指纹（动画页骗得过截图哈希，骗不过这个）
            var sameTapCount = 0
            var lastTapBucket = ""
            var lastTapLabel = ""
            var sameLaunchCount = 0
            var lastLaunchApp = ""
            var lastActionSig = ""
            var prevActionSig = ""
            var alternationCount = 0
            var wrongAppReturns = 0
            var selfHomeRounds = 0
            var wrongAppCycles = 0          // 整个任务里「返回无效→重新拉起」的累计次数，防外跳死循环
            var lastCardLabel = ""          // 任务卡记忆：最近点过的奖励页任务卡标签
            var lastCardPkg = ""            // 该卡所在的应用包名
            var lastCardRound = -99         // 点击发生在第几轮（6轮内出现证据才记账）
            var targetAppPackage: String? = null
            targetAppPackage = guessedApp?.second
            // Bug#2：guessedApp=null 时模型没有目标，此时进入无引导模式，任何非系统/非本App的包名都视为失控
            val unguidedMode = guessedApp == null
            var shortcutOnlyRounds = 0      // 连续由捷径直行（模型未参与）的轮数
            var shortcutSkipRounds = 0      // 熔断后暂停捷径的剩余轮数
            var shortcutBreakerFired = 0    // 捷径空转熔断累计触发次数
            var loadingWaitCount = 0
            var finishVerifyCount = 0
            val taskGoal = TaskGoalVerifier.parse(userText)
            val pickerTracker = TaskGoalVerifier.PickerTracker()
            var prevTapCoords: Pair<Int, Int>? = null
            var waitStallCount = 0
            var lastWaitDumpSig = ""
            var rewardWaitRounds = 0
            var blockedRewardFinishRounds = 0
            var retryNote = ""
            var progressHoldRounds = 0
            val visitedPackages = LinkedHashSet<String>()
            val pageTrace = LinkedHashSet<String>()
            val actionTrace = LinkedHashSet<String>()
            // ===== 路径记忆：录制 + 回放 =====
            val recordingSteps = ArrayList<PageStep>()
            // 启动前尝试回放已验证路径；失败后自动降级到AI探索。
            run {
                val matchedPath = PathMemoryStore.findMatch(this, userText, guessedApp?.second)
                if (matchedPath != null) {
                    runOnUiThread {
                        adapter.add(ChatAdapter.Msg("system", "⚡ 检测到缓存路径（${matchedPath.targetAppName}，${matchedPath.steps.size}步），正在快速回放…"))
                        rv.scrollToPosition(adapter.items.size - 1)
                    }
                    val replayResult = PathMemoryStore.tryReplay(this, matchedPath) { cancelRequested }
                    if (replayResult.success) {
                        val replayConfirmed = AssistantAccessibilityService.instance
                            ?.showSuccessConfirmation(userText, "缓存路径回放成功（${replayResult.stepsExecuted}步）") == TaskVerdict.CONFIRMED
                        if (replayConfirmed) {
                            PathMemoryStore.markSuccess(this, matchedPath)
                        }
                        runOnUiThread {
                            adapter.add(
                                ChatAdapter.Msg(
                                    "assistant",
                                    if (replayConfirmed) {
                                        "✅ 缓存路径回放成功（${replayResult.stepsExecuted}步），任务已完成，路径记录已更新。"
                                    } else {
                                        "⚠️ 缓存路径已执行完成，但你未确认成功；本次不更新路径记录。"
                                    }
                                )
                            )
                            rv.scrollToPosition(adapter.items.size - 1)
                        }
                        android.util.Log.i("AIButler", "AGLM 路径回放成功，跳过LLM循环")
                        return
                    } else {
                        runOnUiThread {
                            adapter.add(ChatAdapter.Msg("system", "⚠️ 缓存路径回放在第${replayResult.failedAtIndex + 1}步失败：${replayResult.message}。降级到AI探索模式。"))
                            rv.scrollToPosition(adapter.items.size - 1)
                        }
                        DeviceTools.openApp(this, guessedApp?.first ?: "")
                        Thread.sleep(2000)
                    }
                }
            }
            while (finalReply == null && !cancelRequested) {
                round++
                android.util.Log.i("AIButler", "AGLM round=$round")
                if (round >= 80) {
                    finalReply = "已达到安全轮次上限（80轮），自动停止。未能稳定确认目标已完成，请查看失败经验后重试。"
                    android.util.Log.i("AIButler", "AGLM max rounds reached (80)")
                    break
                }
                // 上下文长度保护：autoglm-phone 只有20K窗口，长任务里旧思考/动作会快速膨胀
                while (ctx.size() > 10 && ctx.size() >= 4) {
                    ctx.remove(2)
                    ctx.remove(2)
                }
                for (i in 0 until ctx.size()) {
                    val item = ctx.get(i).asJsonObject
                    val role = item.get("role")?.asString
                    if (role == "user" && i != 1) {
                        // ctx[1] 是用户任务本体，永不替换（丢了任务模型会复读占位符空转）
                        // 中性占位：指令式长文案会被模型当台词复读（真机实测协议违规的诱因）
                        item.addProperty("content", "（历史已省略）")
                    } else if (role == "assistant" && i < ctx.size() - 1) {
                        item.addProperty("content", item.get("content")?.asString?.take(220) ?: "")
                    }
                }
                val shot = AgentEngine.screenB64(this, AgentEngine.VISION_MAX_W)
                if (shot == null) {
                    fails++
                    if (fails >= 4) throw LlmException("连续截图失败（请点顶部👁开启视觉后重试）")
                    runOnUiThread { adapter.add(ChatAdapter.Msg("system", "⚠️ 截图失败，重试 $fails/4…")) }
                    Thread.sleep(2000)
                    continue
                }
                fails = 0
                val realW = shot.get("realW").asInt
                val realH = shot.get("realH").asInt
                val b64 = shot.get("b64").asString
                val visionFrame = saveVisionFrame(round, b64)
                if (visionFrame != null) {
                    runOnUiThread {
                        adapter.addVision(
                            "第${round}轮 · 已发送给AI的真实截图\n原始：${realW}×${realH}，识别用：${AgentEngine.VISION_MAX_W}宽",
                            visionFrame.absolutePath
                        )
                        rv.scrollToPosition(adapter.items.size - 1)
                    }
                }
                val screenHash = b64.substring(b64.length / 2).hashCode().toString()
                val screenChanged = screenHash != lastScreenHash
                lastScreenHash = screenHash
                val appPkg = AssistantAccessibilityService.instance?.currentPackage() ?: "unknown"
                visitedPackages.add(appPkg)
                val appIsLauncherPackage = runCatching {
                    packageManager.getLaunchIntentForPackage(appPkg) != null
                }.getOrDefault(false)
                val appIsSystemFlow = appPkg == "android" ||
                    appPkg.startsWith("com.android.settings") ||
                    appPkg.startsWith("com.android.permissioncontroller") ||
                    appPkg.startsWith("com.google.android.permissioncontroller") ||
                    appPkg.startsWith("com.android.packageinstaller") ||
                    appPkg.startsWith("com.google.android.packageinstaller") ||
                    !appIsLauncherPackage
                // 任务还没离开助手自己的聊天页时，绝不能让视觉模型误点聊天气泡或发送按钮
                if (appPkg.startsWith("com.aibutler") && guessedApp?.second != appPkg) {
                    if (guessedApp != null) {
                        val launch = packageManager.getLaunchIntentForPackage(guessedApp.second)
                        if (launch != null) {
                            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(launch)
                        } else {
                            DeviceTools.openApp(this, guessedApp.first)
                        }
                    } else {
                        AssistantAccessibilityService.instance?.home()
                    }
                    selfHomeRounds++
                    android.util.Log.i("AIButler", "AGLM escaped self UI, round=$selfHomeRounds target=${guessedApp?.second ?: "home"}")
                    if (selfHomeRounds >= 4) {
                        finalReply = "无法离开助手界面，任务已自动停止。"
                        break
                    }
                    Thread.sleep(1200)
                    continue
                }
                // 下拉的通知栏/快捷中心不属于任何任务：直接收起，防止误触「自动旋转」等系统开关
                if (appPkg == "com.android.systemui") {
                    AssistantAccessibilityService.instance?.pressBack()
                    android.util.Log.i("AIButler", "AGLM collapsed notification shade")
                    runOnUiThread {
                        adapter.add(ChatAdapter.Msg("system", "↩️ AI 误拉了快捷中心，已自动收起（不碰里面的开关）"))
                        rv.scrollToPosition(adapter.items.size - 1)
                    }
                    Thread.sleep(600)
                    continue
                }
                if (appPkg == "com.oplus.securitypermission") {
                    val dialogDump = AssistantAccessibilityService.instance?.dumpScreenForAutoGLM()
                    if (dialogDump?.contains("\u5141\u8bb8") == true) {
                        val result = AssistantAccessibilityService.instance?.clickText("\u5141\u8bb8")
                        android.util.Log.i("AIButler", "AGLM handled system permission dialog: $result")
                        runOnUiThread {
                            adapter.add(ChatAdapter.Msg("system", "\uD83D\uDD10 handled permission dialog"))
                            rv.scrollToPosition(adapter.items.size - 1)
                        }
                        Thread.sleep(1500)
                        continue
                    }
                }
                if (wrongAppReturns >= 5 &&
                    ((targetAppPackage?.isNotBlank() == true && appPkg != targetAppPackage) || unguidedMode) &&
                    appPkg != "unknown" &&
                    appPkg != targetAppPackage && !appPkg.startsWith("com.aibutler") &&
                    !appIsSystemFlow
                ) {
                    android.util.Log.i("AIButler", "AGLM task left target package: $appPkg")
                    AssistantAccessibilityService.instance?.pressBack()
                    runOnUiThread {
                        adapter.add(ChatAdapter.Msg("system", "🛑 检测到任务离开目标应用，已返回并停止。"))
                        rv.scrollToPosition(adapter.items.size - 1)
                    }
                    finalReply = "任务已停止：连续多次返回+重新拉起后仍未回到目标应用，已拦截第三方跳转。已完成部分以页面显示为准。"
                    break
                }
                // 🔁 捷径空转熔断：连续多轮都被捷径接管（模型未参与）且任务无推进 →
                // 按返回退出当前页，并暂停捷径2轮强制让模型读屏决策；累计3次仍打转则收尾汇报。
                var shortcutGate = true
                if (shortcutSkipRounds > 0) {
                    shortcutSkipRounds--
                    shortcutGate = false
                    android.util.Log.i("AIButler", "AGLM shortcut suppressed, $shortcutSkipRounds rounds left")
                } else if (shortcutOnlyRounds >= 8) {
                    shortcutOnlyRounds = 0
                    shortcutBreakerFired++
                    if (shortcutBreakerFired >= 3) {
                        finalReply = "已多次尝试跳出自动循环，任务仍无推进，已停止。已完成部分以页面显示为准。"
                        android.util.Log.i("AIButler", "AGLM shortcut stall breaker gave up after $shortcutBreakerFired times")
                        break
                    }
                    shortcutSkipRounds = 2
                    AssistantAccessibilityService.instance?.pressBack()
                    android.util.Log.i("AIButler", "AGLM shortcut stall breaker fired #$shortcutBreakerFired, backing out")
                    runOnUiThread {
                        adapter.add(ChatAdapter.Msg("system", "↩️ 快捷操作连续8轮无进展，已返回上一页，接下来2轮由AI亲自看屏决策。"))
                        rv.scrollToPosition(adapter.items.size - 1)
                    }
                    ctx.add(JsonObject().apply {
                        addProperty("role", "user")
                        addProperty(
                            "content",
                            "🛑 系统提示：刚才连续8轮都由内置快捷操作自动点击，但任务毫无推进，已按返回退出该页面，且接下来2轮快捷操作已暂停。请你自己读屏决策：若按钮无效就换入口；若一直在原地打转，直接 finish 并汇报当前状态。"
                        )
                    })
                    Thread.sleep(1500)
                    continue
                }
                if (shortcutGate && guessedApp != null && appPkg == "com.android.launcher") {
                    android.util.Log.i("AIButler", "AGLM task returned launcher; reopening target")
                    DeviceTools.openApp(this, guessedApp.first)
                    shortcutOnlyRounds++
                    continue
                }
                val screenDump = AssistantAccessibilityService.instance?.dumpScreenForAutoGLM()
                    ?: "当前应用: $appPkg\n无法读取页面结构"
                // Bug#6：系统隐私遮挡（ColorOS投屏抹黑）时视觉脑完全不可用，立即终止
                val privacyHidden = screenDump.contains("已隐藏") ||
                    screenDump.contains("画面被隐藏") ||
                    screenDump.contains("屏幕共享画面已隐藏")
                if (privacyHidden) {
                    android.util.Log.i("AIButler", "AGLM privacy overlay detected, stopping")
                    finalReply = "该应用受系统隐私保护，视觉脑无法读取屏幕内容。请手动操作该应用，或在系统设置中关闭隐私遮挡。"
                    break
                }
                // 页面文本指纹：本轮和上轮完全一致 = 冻结页（假倒计时/广告墙，等待无效）
                val dumpSigNow = screenDump.hashCode().toString()
                val prevRoundDumpSig = lastDumpSig
                val frozenPage = dumpSigNow == lastDumpSig
                lastDumpSig = dumpSigNow
                val commerceTaskPage = Regex(
                    "加入购物车|购买数量|立即抢购|立即购买|去支付|提交订单|确认订单",
                    RegexOption.IGNORE_CASE
                ).containsMatchIn(screenDump.replace(Regex("\\s+"), ""))
                if (commerceTaskPage && !Regex("下单|购买|支付|抢购|购物车").containsMatchIn(userText)) {
                    android.util.Log.i("AIButler", "AGLM force back from commerce task page")
                    AssistantAccessibilityService.instance?.pressBack()
                    runOnUiThread {
                    adapter.add(ChatAdapter.Msg("system", "🛑 已离开误入的商品页，正在返回任务路径。"))
                        rv.scrollToPosition(adapter.items.size - 1)
                    }
                    ctx.add(JsonObject().apply {
                        addProperty("role", "user")
                        addProperty(
                            "content",
                            "🛑 系统提示：刚才误入了商品/下单页，已自动按返回键。下一轮重新读屏，只执行与用户任务直接相关的免费入口，不要点击商品、店铺、购物车或安装类任务。"
                        )
                    })
                    continue
                }
                // 拍摄/创作页对签到任务是高危路径：一旦进入倒计时或编辑页，很容易连续操作到发布。
                val creationIntent = Regex("拍摄|录像|发视频|发布|开直播|录屏").containsMatchIn(userText)
                val creationSignals = listOf(
                    "选择音乐", "切换到前置摄像头", "开始拍摄", "停止拍摄", "分段拍",
                    "灵感跟拍", "美颜", "滤镜", "倒计时拍", "视频编辑", "发布"
                ).count { screenDump.contains(it) }
                if (!creationIntent && creationSignals >= 2) {
                    val discardResult = listOf("不保存返回", "不保存", "丢弃")
                        .firstNotNullOfOrNull { label ->
                            AssistantAccessibilityService.instance?.clickText(label)
                                ?.takeIf { it.contains("已点击") }
                        }
                    if (discardResult != null) {
                        android.util.Log.i("AIButler", "AGLM left creation page via discard exit")
                    } else {
                        AssistantAccessibilityService.instance?.pressBack()
                        android.util.Log.i("AIButler", "AGLM force back from creation page, signals=$creationSignals")
                    }
                    runOnUiThread {
                        adapter.add(ChatAdapter.Msg("system", "🛑 已离开误入的拍摄/创作页，正在返回任务路径。"))
                        rv.scrollToPosition(adapter.items.size - 1)
                    }
                    ctx.add(JsonObject().apply {
                        addProperty("role", "user")
                        addProperty(
                            "content",
                            "🛑 系统提示：刚才误入了拍摄/视频编辑页，已自动退出。用户任务是签到，不是创作内容；不要点底部中间的拍摄按钮。签到入口通常在首页红包浮标、金币、去赚钱或任务中心。"
                        )
                    })
                    Thread.sleep(1200)
                    continue
                }
                // 📵 下载墙页：这类广告页会自动触发APK下载/安装（用户投诉「老安装软件」的元凶），绝不停留
                if (Regex("下载并体验|下载应用|立即下载|下载安装|安装应用|免费下载|戳我下载|下载领\\d*金币").containsMatchIn(screenDump)) {
                    if (lastCardLabel.isNotBlank() && appPkg == lastCardPkg && round - lastCardRound <= 6) {
                        TaskCardMemory.mark(this, appPkg, lastCardLabel, good = false)
                    }
                    lastCardLabel = ""
                    AssistantAccessibilityService.instance?.pressBack()
                    android.util.Log.i("AIButler", "AGLM 下载墙页自动返回: $appPkg")
                    runOnUiThread {
                        adapter.add(ChatAdapter.Msg("system", "📵 发现下载/安装类广告页，已自动返回（防止装垃圾软件）"))
                        rv.scrollToPosition(adapter.items.size - 1)
                    }
                    ctx.add(JsonObject().apply {
                        addProperty("role", "user")
                        addProperty(
                            "content",
                            "🛑 系统提示：刚才那页是下载/安装类广告墙，会往手机里装垃圾软件，已自动按返回离开。绝不要再点任何带「下载/安装/试玩/去体验」字样的卡片；只执行应用内直接的领取/签到/开宝箱动作。若刚点过的任务卡就是通向这类页面的入口，换其他入口。"
                        )
                    })
                    Thread.sleep(1200)
                    continue
                }
                screenDump.lineSequence().firstOrNull { line -> line.contains("页面内容:") }
                    ?.let { line -> pageTrace.add(line.take(240)) }
                if (guessedApp != null) targetAppPackage = guessedApp.second
                if (guessedApp != null && appPkg.startsWith("com.aibutler") && appPkg != guessedApp.second) {
                    runOnUiThread {
                        adapter.add(ChatAdapter.Msg("system", "🚀 已按手机里安装的应用名启动目标应用：${guessedApp.first}"))
                        rv.scrollToPosition(adapter.items.size - 1)
                    }
                    DeviceTools.openApp(this, guessedApp.first)
                    continue
                }
                if (targetAppPackage != null && appPkg == targetAppPackage) {
                    wrongAppReturns = 0   // 回到目标应用即重新计数（只看「连续」离开）
                }
                if (((targetAppPackage != null && wrongAppReturns < 5) || unguidedMode) && !appIsSystemFlow &&
                    appPkg != targetAppPackage && !appPkg.startsWith("com.aibutler")
                ) {
                    // 被拉出目标App：如果刚点过任务卡，这就是一张「外跳广告卡」，记入任务卡记忆
                    if (lastCardLabel.isNotBlank() && round - lastCardRound <= 6) {
                        TaskCardMemory.mark(this, targetAppPackage ?: lastCardPkg, lastCardLabel, good = false)
                    }
                    lastCardLabel = ""
                    wrongAppReturns++
                    if (wrongAppReturns <= 2) {
                        AssistantAccessibilityService.instance?.pressBack()
                        runOnUiThread {
                            adapter.add(ChatAdapter.Msg("system", "🛑 已离开目标应用，正在返回。"))
                            rv.scrollToPosition(adapter.items.size - 1)
                        }
                    } else {
                        if (wrongAppCycles >= 2) {
                            android.util.Log.i("AIButler", "AGLM wrongApp cycles exceeded, stopping")
                            finalReply = "任务多次被广告/外跳拉出目标应用，为防误操作已自动停止。已完成部分以页面显示为准。"
                            break
                        }
                        wrongAppCycles++
                        // 广告深链会把人拉进第三方App，在对方App里按返回是退不出去的，直接重新拉起目标应用
                        android.util.Log.i("AIButler", "AGLM wrongApp back x$wrongAppReturns failed, relaunching target #$wrongAppCycles")
                        DeviceTools.openApp(this, guessedApp?.first ?: "")
                        runOnUiThread {
                            adapter.add(ChatAdapter.Msg("system", "🚀 返回无效，已直接重新打开目标应用。"))
                            rv.scrollToPosition(adapter.items.size - 1)
                        }
                        // 教会模型：刚才点的位置会跳出目标应用，别再点它
                        ctx.add(JsonObject().apply {
                            addProperty("role", "user")
                            addProperty(
                                "content",
                                "🛑 系统提示：你刚才点击的位置会跳出到无关第三方应用（已自动拉回${guessedApp?.first ?: "目标应用"}）。绝不要再点击那个位置或广告/下载/外跳入口。请回到目标应用内，继续寻找与「$userText」直接相关的原生入口。"
                            )
                        })
                    }
                    Thread.sleep(1800)
                    continue
                }
                shortcutOnlyRounds = 0
                val taskNote = "\n用户任务: $userText"
                val compactScreen = screenDump.replace(Regex("\\s+"), "")
                val rewardScreen = Regex(
                    "签到|金币|积分|奖励|收益|赚钱|任务中心|去赚钱|宝箱|领取|倒计时",
                    RegexOption.IGNORE_CASE
                ).containsMatchIn(compactScreen)
                val rewardDone = Regex(
                    "明日签到可领|今日已签到|已签到\\s*\\d+\\s*/\\s*\\d+\\s*天|领取成功|已成功领取|奖励已发放|已到账",
                    RegexOption.IGNORE_CASE
                ).containsMatchIn(screenDump)
                val explicitRewardCountdown = Regex(
                    "(?:再看|还需|等待)[^0-9]{0,4}\\d+秒|倒计时[^0-9]{0,5}\\d+秒|赚钱中|任务未完成|不能领取|无法领取",
                    RegexOption.IGNORE_CASE
                ).containsMatchIn(compactScreen)
                val rewardCountdown = !rewardDone && (
                    explicitRewardCountdown ||
                        (rewardScreen && Regex("\\d{1,2}:\\d{2}").containsMatchIn(compactScreen))
                    )
                val countdownNote = if (rewardCountdown) {
                    if (frozenPage) {
                        "\n⏳ 系统读屏：页面有倒计时，但页面文本和上一步完全一样——倒计时没有走动，等待是无效的。请点击「继续观看/领取/放弃奖励」等按钮推进；点了也没反应就换其他金币入口；若确实没有可推进的入口，直接 finish 并汇报当前进度。"
                    } else {
                        "\n⏳ 系统读屏：当前任务进度仍在倒计时/进行中。必须执行 Wait 等它完成，不要 Back、滑动或点击其他位置；倒计时结束后再点击当前页面的领取/继续入口。"
                    }
                } else ""
                // ===== 任务卡记忆：事件记账（点击任务卡后6轮内出现的证据）=====
                run {
                    val cardFresh = lastCardLabel.isNotBlank() && appPkg == lastCardPkg && round - lastCardRound <= 6
                    if (cardFresh && Regex("已成功领取|领取成功|已到账|奖励已发放|额外奖励已到手").containsMatchIn(screenDump)) {
                        TaskCardMemory.mark(this, appPkg, lastCardLabel, good = true)
                        lastCardLabel = ""
                    } else if (cardFresh && frozenPage && rewardCountdown) {
                        TaskCardMemory.mark(this, appPkg, lastCardLabel, good = false)
                        lastCardLabel = ""
                    }
                }
                val doneNote = if (rewardDone && Regex("金币|签到|赚钱").containsMatchIn(userText)) {
                    "\n✅ 系统读屏：页面显示今日签到/领取已完成。不要再找签到入口，也不要反复尝试广告类外跳任务卡；只有页面上存在明确的非广告领取按钮（立即领取/开宝箱等）时才点它，否则直接 finish 并汇报今日已完成内容。"
                } else ""
                val cardNote = if (Regex("金币|任务中心|去赚钱|奖励").containsMatchIn(screenDump))
                    TaskCardMemory.advise(this, appPkg, screenDump) else ""
                val pickerSnapshot = TaskGoalVerifier.PickerSnapshot.from(screenDump)
                pickerTracker.observe(pickerSnapshot)
                val pickerTargetAdvise = if (taskGoal.time != null && pickerSnapshot.isPicker) {
                    pickerTracker.advise(taskGoal.time!!, pickerSnapshot)
                } else ""
                val goalCheck = TaskGoalVerifier.check(taskGoal, screenDump)
                val goalNote = if (taskGoal.hasConstraint) {
                    when (goalCheck.status) {
                        TaskGoalVerifier.Status.BLOCKED -> "\n🛑 系统校验：${goalCheck.detail}。不能报告完成。"
                        TaskGoalVerifier.Status.CONFIRMED -> "\n✅ 系统校验：${goalCheck.detail}。可以把这个屏幕证据写进最终结果。"
                        TaskGoalVerifier.Status.NEEDS_EVIDENCE -> "\n🎯 系统校验：${goalCheck.detail}。继续执行用户目标，直到屏幕出现精确目标值和保存结果。"
                    }
                } else ""
                val pickerNote = if (
                    Regex("NumberPicker|WheelView|WheelPicker|DatePicker|TimePicker", RegexOption.IGNORE_CASE)
                        .containsMatchIn(screenDump) ||
                    Regex("可滑动[^\\n]{0,120}[0-9０-９]+[^\\n]{0,20}(时|分|秒|年|月|日|号)")
                        .containsMatchIn(screenDump)
                ) {
                    "\n🌀 系统读屏：当前有滚轮/数字/日期/时间类选择器。每一列都是独立控件；" +
                        "必须先从最新截图和关键元素里识别它的当前值与控件中心，再在那一列自己的 X 坐标上小幅滑动。" +
                        "每滑一次后重新读屏校验，方向不对就反向；精确值没完全一致前不要保存。"
                } else ""
                val appIdentityNote = when {
                    guessedApp != null && appPkg == guessedApp.second ->
                        "\n✅ 当前应用确认：${guessedApp.first}（包名 ${guessedApp.second}），不要把这个包名误判成其他应用。"
                    targetAppPackage != null && appPkg == targetAppPackage ->
                        "\n✅ 当前应用确认：目标应用（包名 $appPkg），请按这个应用的原生页面继续任务。"
                    else -> ""
                }
                val screenInfo = "$appIdentityNote$screenDump$countdownNote$doneNote$cardNote$pickerNote$pickerTargetAdvise$goalNote\n(截图是最新页面；所有Tap/Swipe坐标必须用0-999)" +
                    "\n坐标规则：Tap坐标只能来自截图目标中心；不要抄“关键元素”里的坐标。截图上明显存在但关键元素没列出的按钮，也必须按截图位置输出。" +
                    retryNote +
                    "$taskNote\n输出要求：先输出<think>一句话说明当前是什么页面、目标元素在哪、为什么选这个动作（120字以内）</think>，再输出一个动作。"
                android.util.Log.i("AIButler", "AGLM screenInfo=${screenInfo.take(500).replace('\n', ' ')}")
                val textPart = if (round == 1) "$userText\n\n$screenInfo" else "** Screen Info **\n\n$screenInfo"
                ctx.add(JsonObject().apply {
                    addProperty("role", "user")
                    add("content", JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("type", "image_url")
                            add("image_url", JsonObject().apply { addProperty("url", "data:image/jpeg;base64,$b64") })
                        })
                        add(JsonObject().apply { addProperty("type", "text"); addProperty("text", textPart) })
                    })
                })
                val glmReply = LlmClient.chatAutoGLM(Prefs.apiKey(this), ctx)
                val raw = glmReply.content
                android.util.Log.i("AIButler", "AGLM raw=${raw.take(300).replace('\n', ' ')}")
                // 历史只留文字（官方同款做法：只有当前步带图）
                val lastUser = ctx.get(ctx.size() - 1).asJsonObject
                lastUser.add("content", JsonArray().apply {
                    add(JsonObject().apply { addProperty("type", "text"); addProperty("text", textPart) })
                })
                var answer = ""
                val protocolMatch = Regex("(?s)<answer>(.*?)</answer>").find(raw)
                val fallbackAction = Regex(
                    "do\\s*\\(\\s*action\\s*=\\s*\"([A-Za-z_ ]+)\"[^)]*\\)",
                    RegexOption.IGNORE_CASE
                ).find(raw)
                // 模型经常不加 <answer> 包裹，直接给 finish(message="...")。
                // 这已经是明确的结束指令，不能被当成"协议错误"丢掉。
                val fallbackFinish = Regex(
                    "finish\\s*\\(\\s*message\\s*=\\s*\"",
                    RegexOption.IGNORE_CASE
                ).find(raw)
                // 结束指令优先级最高：模型说了 finish 就是任务完成，别被正文里引用的动作文本带跑偏
                if (protocolMatch == null && fallbackFinish != null) {
                    answer = fallbackFinish.value.trim()
                } else if (protocolMatch == null && fallbackAction != null) {
                    answer = fallbackAction.value.trim()
                } else if (protocolMatch == null) {
                    parseFails++
                    android.util.Log.i("AIButler", "AGLM protocol violation: ${raw.take(180)}")
                    showRawAsThinking(raw)
                    if (parseFails >= 4) {
                        finalReply = "连续多次未按动作协议输出，任务中止。"
                        break
                    }
                    retryNote = AgentEngine.protocolRetryNote(raw)
                    continue
                }
                parseFails = 0
                retryNote = ""
                answer = when {
                    protocolMatch != null -> protocolMatch.groupValues[1].trim()
                    fallbackFinish != null -> raw.substring(fallbackFinish.range.first).trim()
                    fallbackAction != null -> fallbackAction.value.trim()
                    else -> ""
                }
                if (protocolMatch == null && fallbackAction != null && !answer.endsWith(")")) answer += ")"
                val thinkTag = Regex("(?s)<think>(.*?)</think>", RegexOption.IGNORE_CASE)
                    .find(raw)?.groupValues?.get(1)?.trim().orEmpty()
                // 优先用 <think> 标签，其次用模型单独返回的推理字段
                val think = thinkTag.ifBlank { glmReply.reasoning.trim() }
                // AutoGLM 常把解释直接写在动作前；没有标签或推理字段时也展示这段说明。
                val visibleThink = think.ifBlank {
                    val anchor = protocolMatch?.value ?: fallbackFinish?.value ?: fallbackAction?.value
                    if (anchor == null) "" else raw.substringBefore(anchor).trim()
                }
                val visionProgressHint = Regex(
                    "倒计时|再看\\s*\\d+|还需\\s*\\d+|\\d+\\s*秒后|计时|进度",
                    RegexOption.IGNORE_CASE
                ).containsMatchIn(raw)
                if (screenDump.contains("页面可能仍在加载") && !answer.contains("Wait") && loadingWaitCount < 3) {
                    loadingWaitCount++
                    android.util.Log.i("AIButler", "AGLM forced loading wait")
                    answer = "do(action=\"Wait\", duration=\"2 seconds\")"
                } else if (!screenDump.contains("页面可能仍在加载")) {
                    loadingWaitCount = 0
                }
                // 退出确认弹窗在场上时（close_dialog/再看X秒/放弃奖励），不要抢模型的操作权强制等待
                val exitDialogUp = screenDump.contains("close_dialog") ||
                    Regex("再看\\d+秒|放弃奖励|放弃本次奖励").containsMatchIn(screenDump)
                if (rewardCountdown && !frozenPage) {
                    if (!exitDialogUp && !answer.contains("Wait") && !answer.startsWith("finish") && rewardWaitRounds < 5) {
                        rewardWaitRounds++
                        answer = "do(action=\"Wait\", duration=\"8 seconds\")"
                        android.util.Log.i("AIButler", "AGLM forced reward countdown wait #$rewardWaitRounds")
                        runOnUiThread {
                            adapter.add(ChatAdapter.Msg("system", "⏳ 任务进度还在倒计时，先等待，完成后自动领取"))
                            rv.scrollToPosition(adapter.items.size - 1)
                        }
                    } else if (!answer.contains("Wait")) {
                        rewardWaitRounds = 0
                    }
                    if (answer.startsWith("finish") && !rewardDone &&
                        Regex("金币|积分|奖励|收益|赚钱|赚钱|签到").containsMatchIn(userText) &&
                        blockedRewardFinishRounds < 12
                    ) {
                        blockedRewardFinishRounds++
                        answer = "do(action=\"Wait\", duration=\"10 seconds\")"
                        android.util.Log.i(
                            "AIButler",
                            "AGLM blocked premature reward finish #$blockedRewardFinishRounds"
                        )
                        runOnUiThread {
                            adapter.add(ChatAdapter.Msg("system", "⏳ 页面明确提示任务未完成，已拒绝结束，继续等待奖励进度"))
                            rv.scrollToPosition(adapter.items.size - 1)
                        }
                        ctx.add(
                            JsonObject().apply {
                                addProperty("role", "user")
                                addProperty(
                                    "content",
                                    "🛑 系统提示：页面仍在倒计时或显示任务未完成/不能领取，这不是完成证据。刚才的 finish 已拒绝；必须继续 Wait，倒计时结束后点击当前页面的领取/继续/完成按钮，拿到领取成功或已到账证据才能 finish。"
                                )
                            }
                        )
                    }
                } else {
                    rewardWaitRounds = 0
                    blockedRewardFinishRounds = 0
                }
                if (visionProgressHint && rewardWaitRounds == 0 && !frozenPage &&
                    Regex("金币|积分|奖励|收益|赚钱").containsMatchIn(userText) &&
                    answer.contains("Tap")
                ) {
                    answer = "do(action=\"Wait\", duration=\"10 seconds\")"
                    android.util.Log.i("AIButler", "AGLM forced visual progress wait")
                    runOnUiThread {
                        adapter.add(ChatAdapter.Msg("system", "⏳ AI 看到任务进度还没完成，先等待"))
                        rv.scrollToPosition(adapter.items.size - 1)
                    }
                }
                if (progressHoldRounds > 0 && !frozenPage &&
                    Regex("金币|积分|奖励|收益|赚钱").containsMatchIn(userText) &&
                    Regex("金币|奖励|收益|任务").containsMatchIn(screenDump)
                ) {
                    progressHoldRounds--
                    answer = "do(action=\"Wait\", duration=\"10 seconds\")"
                    android.util.Log.i("AIButler", "AGLM progress hold wait, remaining=$progressHoldRounds")
                    runOnUiThread {
                        adapter.add(ChatAdapter.Msg("system", "⏳ 检测到奖励任务往返，已停在任务页等待进度"))
                        rv.scrollToPosition(adapter.items.size - 1)
                    }
                }
                val blockingDialog = Regex(
                    "添加至桌面|添加组件|安装|去下载|立即更新|用户协议|隐私政策|验证码|付款|支付密码",
                    RegexOption.IGNORE_CASE
                ).containsMatchIn(screenDump)
                if (appPkg == targetAppPackage && guessedApp != null &&
                    Regex("do\\s*\\(\\s*action\\s*=\\s*\"Launch\"\\s*,\\s*app\\s*=\\s*\"${Regex.escape(guessedApp.first)}\"")
                        .containsMatchIn(answer)
                ) {
                    android.util.Log.i("AIButler", "AGLM ignored self launch inside target app")
                    answer = "do(action=\"Wait\", duration=\"1 seconds\")"
                    retryNote = "\n⚠️ 系统提示：你已经在目标应用内，不要再次 Launch 它。请从当前页面的原生入口继续查找任务；不要重复刚才这个位置。"
                }
                if (taskGoal.time != null && pickerSnapshot.isPicker) {
                    val mismatch = TaskGoalVerifier.completionTapMismatch(taskGoal, answer, screenDump)
                    if (mismatch != null) {
                        val forced = pickerTracker.recommendedSwipe(taskGoal.time!!, pickerSnapshot)
                            ?: "do(action=\"Wait\", duration=\"1 seconds\")"
                        android.util.Log.i("AIButler", "AGLM blocked premature goal save: $mismatch")
                        answer = forced
                        ctx.add(JsonObject().apply {
                            addProperty("role", "user")
                            addProperty(
                                "content",
                                "🛑 系统提示：$mismatch。刚才的完成/保存点击已拦截；请先按系统建议把滚轮调到精确目标值，再保存。"
                            )
                        })
                    } else if (answer.contains("Swipe")) {
                        val recommended = pickerTracker.recommendedSwipe(taskGoal.time!!, pickerSnapshot)
                        if (recommended != null) {
                            answer = recommended
                        }
                    }
                }
                android.util.Log.i("AIButler", "AGLM round=$round 动作: ${answer.take(150)}")
                actionTrace.add(answer.take(220))
                if (visibleThink.isNotBlank() && Prefs.showThinking(this)) runOnUiThread {
                    adapter.addThink(visibleThink)
                    rv.scrollToPosition(adapter.items.size - 1)
                }
                val prematureOsFinish = userText.contains("Android版本", true) &&
                    Regex("Android\\s*版本[\\s\\S]{0,100}(ColorOS|MIUI|HarmonyOS|OriginOS|HyperOS)", RegexOption.IGNORE_CASE)
                        .containsMatchIn(answer)
                if (prematureOsFinish) {
                    ctx.add(JsonObject().apply {
                        addProperty("role", "user")
                        addProperty(
                            "content",
                            "🛑 系统提示：ColorOS/MIUI/HarmonyOS等定制系统版本不是Android版本。当前 finish 已拒绝；继续在目标页面或版本详情中查找 Android 版本字段，找不到再滑动或换明确入口。"
                        )
                    })
                    continue
                }
                if (answer.startsWith("finish")) {
                    val finishGoalCheck = TaskGoalVerifier.check(taskGoal, screenDump)
                    if (finishGoalCheck.status == TaskGoalVerifier.Status.BLOCKED) {
                        android.util.Log.i("AIButler", "AGLM blocked finish by goal verifier: ${finishGoalCheck.detail}")
                        ctx.add(JsonObject().apply {
                            addProperty("role", "user")
                            addProperty(
                                "content",
                                "🛑 系统校验：${finishGoalCheck.detail}。这次 finish 已拒绝；继续用最新屏幕上的目标列调整，或先保存。没有目标值/成功证据时不能结束。"
                            )
                        })
                        continue
                    }
                    if (finishGoalCheck.status == TaskGoalVerifier.Status.CONFIRMED) {
                        finalReply = "✅ 已完成：${finishGoalCheck.detail}"
                        break
                    }
                    // 通用防误判：第一次 finish 不直接采信（到达页面/弹窗出现常被当成完成），
                    // 强制模型对照最新截图给出任务完成的页面证据；给得出才结束，给不出继续干活
                    if (finishVerifyCount < 1) {
                        finishVerifyCount++
                        android.util.Log.i("AIButler", "AGLM finish 验证关：要求出示完成证据")
                        ctx.add(JsonObject().apply {
                            addProperty("role", "user")
                            addProperty(
                                "content",
                                "🛑 系统提示：finish 前必须验证。请重新观察最新截图，对照用户任务「$userText」：" +
                                    "页面上有没有任务完成的直接证据（金币/余额确实比操作前增加、任务列表项变为已完成、明确的领取成功提示）？" +
                                    "如果用户给出了精确数字/时间/日期，最新页面必须显示完全一致的目标值或明确的保存/设置成功证据；" +
                                    "到达某个页面、出现弹窗、看到活动信息或原有余额数字都不算证据。有证据→重新输出 finish(message=...) 并在 message 里引用该证据；" +
                                    "没有→不要 finish，立即输出继续完成任务的下一个动作。"
                            )
                        })
                        continue
                    }
                    val quoted = answer.indexOf('"')
                    finalReply = if (quoted >= 0) {
                        answer.substring(quoted + 1)
                            .trim()
                            .removeSuffix(")")
                            .removeSuffix("\"")
                            .trim()
                            .ifBlank { "任务完成" }
                    } else answer
                    break
                }
                val actionSig = answer.hashCode().toString()
                if (actionSig == prevActionSig && actionSig != lastActionSig) {
                    alternationCount++
                } else {
                    alternationCount = 0
                }
                prevActionSig = lastActionSig
                lastActionSig = actionSig
                if (alternationCount >= 2) {
                    android.util.Log.i("AIButler", "AGLM alternation breaker triggered")
                    val rewardishTask = Regex("金币|积分|奖励|收益|赚钱").containsMatchIn(userText) &&
                        Regex("金币|奖励|收益|任务").containsMatchIn(screenDump)
                    if (rewardishTask) {
                        answer = "do(action=\"Wait\", duration=\"10 seconds\")"
                        progressHoldRounds = 3
                        ctx.add(JsonObject().apply {
                            addProperty("role", "user")
                            addProperty(
                                "content",
                                "⏳ 系统提示：检测到任务进度还没有完成却来回切换。请等待当前进度完成，之后只点击当前页面明确的领取/继续入口。"
                            )
                        })
                    } else {
                        answer = "do(action=\"Back\")"
                    }
                    alternationCount = 0
                    if (!rewardishTask) {
                        ctx.add(JsonObject().apply {
                            addProperty("role", "user")
                            addProperty(
                                "content",
                                "🛑 系统提示：检测到你反复在两个页面/弹窗之间来回切换。已自动按返回键退出。请重新读屏，换一个不同的入口或路径，不要再点击刚才导致弹窗的元素。"
                            )
                        })
                        runOnUiThread {
                            adapter.add(ChatAdapter.Msg("system", "↩️ 检测到弹窗循环，已自动返回上一页。"))
                            rv.scrollToPosition(adapter.items.size - 1)
                        }
                    }
                }
                // 通用防呆：页面已切换却沿用上一轮完全相同的点击坐标 → 不执行，强制重新观察
                // （第2轮点旧坐标误入广告落地页的教训；同坐标在页面未变时仍允许，如双击点赞）
                val curTapMatch = Regex("Tap[^\\]]*element=\\[(-?\\d+)\\s*,\\s*(-?\\d+)\\]").find(answer)
                if (curTapMatch != null && prevTapCoords != null && prevRoundDumpSig != dumpSigNow &&
                    curTapMatch.groupValues[1].toInt() == prevTapCoords!!.first &&
                    curTapMatch.groupValues[2].toInt() == prevTapCoords!!.second
                ) {
                    android.util.Log.i("AIButler", "AGLM 拦截旧坐标点击: ${prevTapCoords}")
                    ctx.add(JsonObject().apply {
                        addProperty("role", "user")
                        addProperty(
                            "content",
                            "🛑 系统提示：页面已经切换，你却沿用了上一轮的旧坐标(${prevTapCoords!!.first},${prevTapCoords!!.second})。" +
                                "旧坐标在新页面上毫无意义（这次点击已拒绝执行，你点的很可能是广告陷阱）。" +
                                "请重新观察最新截图，在当前页面上找出目标的新位置再点。"
                        )
                    })
                    continue
                }
                // 防卡死：连续 Wait 且读屏完全无变化 → 先强纠正，仍无变化 → 中止
                // （真机实测：抖音极速版视频层读屏不变，模型连续 Wait 46+轮空烧到80轮上限）
                if (answer.contains("action=\"Wait\"")) {
                    if (dumpSigNow == lastWaitDumpSig) {
                        waitStallCount++
                    } else {
                        waitStallCount = 0
                        lastWaitDumpSig = dumpSigNow
                    }
                    if (waitStallCount == AgentEngine.WAIT_STALL_CORRECT_AT) {
                        ctx.add(JsonObject().apply {
                            addProperty("role", "user")
                            addProperty("content", AgentEngine.waitStallCorrectNote(waitStallCount))
                        })
                        android.util.Log.i("AIButler", "AGLM wait stall correct x$waitStallCount")
                    } else if (waitStallCount >= AgentEngine.WAIT_STALL_ABORT_AT) {
                        finalReply = AgentEngine.WAIT_STALL_ABORT_MESSAGE
                        android.util.Log.i("AIButler", "AGLM wait stall abort x$waitStallCount")
                        runOnUiThread {
                            adapter.add(ChatAdapter.Msg("system", "🛑 $finalReply"))
                            rv.scrollToPosition(adapter.items.size - 1)
                        }
                        break
                    }
                } else {
                    waitStallCount = 0
                    lastWaitDumpSig = dumpSigNow
                }
                // 录制：动作执行前取当前页面指纹
                val preActionFp = AssistantAccessibilityService.instance?.currentPageFingerprint()
                // 任务卡记忆：记住这次点击的奖励页任务卡（6轮内出现到账/冻结/外跳证据时记账）
                val recParsed = parseActionForRecording(answer, screenDump)
                if (recParsed != null && recParsed.first == "tap" && recParsed.second.isNotBlank() &&
                    Regex("金币|奖励|任务").containsMatchIn(screenDump)
                ) {
                    lastCardLabel = recParsed.second
                    lastCardPkg = appPkg
                    lastCardRound = round
                }
                val res = AgentEngine.execAutoGLM(
                    this, answer, realW, realH, userText, screenDump, guessedApp
                )
                if (!res.finished && res.message != "__PARSE_FAIL__" && !res.settled) {
                    AgentEngine.waitForActionSettle(answer, preActionFp?.first)
                }
                if (!res.finished && res.message != "__PARSE_FAIL__") {
                    val postScreenDump = AssistantAccessibilityService.instance?.dumpScreenForAutoGLM()
                    if (!postScreenDump.isNullOrBlank()) {
                        pickerTracker.record(
                            pickerSnapshot,
                            TaskGoalVerifier.PickerSnapshot.from(postScreenDump),
                            answer
                        )
                    }
                }
                // 录制：执行+稳定后取目标页面指纹，追加到路径链
                if (preActionFp != null && !res.finished && res.message != "__PARSE_FAIL__" &&
                    !res.message?.startsWith("🛑").let { it == true } && !res.message?.startsWith("⚠️").let { it == true }) {
                    val postActionFp = AssistantAccessibilityService.instance?.currentPageFingerprint()
                    val parsed = recParsed
                    if (parsed != null && postActionFp != null) {
                        recordingSteps.add(PageStep(
                            sourceFingerprint = preActionFp.first,
                            actionType = parsed.first,
                            actionLabel = parsed.second,
                            actionX = parsed.third,
                            actionY = parsed.fourth,
                            targetFingerprint = postActionFp.first
                        ))
                    }
                }
                if (res.finished) {
                    finalReply = res.message ?: "任务完成"
                    break
                }
                if (res.message == "__PARSE_FAIL__") {
                    parseFails++
                    android.util.Log.i("AIButler", "AGLM 动作解析失败: ${answer.take(120)}")
                    showRawAsThinking(raw)
                    if (parseFails >= 4) {
                        finalReply = "连续多次动作解析失败，任务中止。请换个说法重试。"
                        break
                    }
                    ctx.add(JsonObject().apply {
                        addProperty("role", "user")
                        addProperty("content", "⚠️ 系统提示：你上一步在描述画面，没有输出动作。视频内容不用描述！请立即以严格格式输出本步操作：<answer>do(action=\"Wait\", duration=\"15 seconds\")</answer> 或其他动作。")
                    })
                    continue
                }
                parseFails = 0
                if (res.message == "__JUMP_BLOCK__") {
                    runOnUiThread {
                        adapter.add(ChatAdapter.Msg("system", res.info))
                        rv.scrollToPosition(adapter.items.size - 1)
                    }
                    ctx.add(JsonObject().apply {
                        addProperty("role", "user")
                        addProperty("content", "🛑 系统提示：上一步坐标命中疑似跳转其他应用的入口，已拦截，没有点击。请重新看最新截图，改选目标应用内部的原生任务；看不准就 Swipe 或 Back。")
                    })
                    continue
                }
                if (res.message == "__COMMERCE_BLOCK__") {
                    runOnUiThread {
                        adapter.add(ChatAdapter.Msg("system", res.info))
                        rv.scrollToPosition(adapter.items.size - 1)
                    }
                    ctx.add(JsonObject().apply {
                        addProperty("role", "user")
                        addProperty(
                            "content",
                            "🛑 系统提示：上一步点击已被安全拦截，当前是商品/下单页。不要点击商品、抢购、购买、支付或相关入口；立即输出 do(action=\"Back\") 返回上一页，再重新寻找与用户任务直接相关的入口。"
                        )
                    })
                    continue
                }
                if (res.finished) {
                    finalReply = res.message ?: "任务完成"
                    break
                }
                runOnUiThread {
                    adapter.add(ChatAdapter.Msg("system", "🔧 " + answer.take(110) + "\n→ " + res.info.take(130)))
                    rv.scrollToPosition(adapter.items.size - 1)
                }
                ctx.add(JsonObject().apply {
                    addProperty("role", "assistant")
                    addProperty("content", "<answer>$answer</answer>")
                })
                // Bug#1a：把执行结果回填给模型，模型下一轮才知道动作是成功还是失败
                ctx.add(JsonObject().apply {
                    addProperty("role", "user")
                    addProperty("content", "执行结果：${res.info.take(200)}")
                })
                // 防点死磕：连续3次提示换目标，连续6次熔断；不重置计数
                val tapM = Regex("Tap[^\\]]*element=\\[(-?\\d+)\\s*,\\s*(-?\\d+)\\]").find(answer)
                prevTapCoords = if (tapM != null) Pair(tapM.groupValues[1].toInt(), tapM.groupValues[2].toInt()) else null
                if (tapM != null) {
                    val bucket = "${tapM.groupValues[1].toInt() / 15}_${tapM.groupValues[2].toInt() / 15}"
                    val tapLabel = Regex("已点击屏幕\\([^)]*\\)→([^\\n]+)").find(res.info)?.groupValues?.get(1)?.trim()
                    val repeated = bucket == lastTapBucket && (tapLabel == null || tapLabel == lastTapLabel)
                    if (repeated) sameTapCount++ else { sameTapCount = 1 }
                    lastTapBucket = bucket
                    lastTapLabel = tapLabel.orEmpty()
                } else {
                    sameTapCount = 0
                    lastTapBucket = ""
                    lastTapLabel = ""
                }
                // Bug#1c：Launch 同一应用连续3次且未进入目标包名 = 目标不存在或无法启动，终止
                val launchM = Regex("do\\s*\\(\\s*action\\s*=\\s*\"Launch\"\\s*,\\s*app\\s*=\\s*\"([^\"]+)\"").find(answer)
                if (launchM != null) {
                    val launchedApp = launchM.groupValues[1].trim()
                    if (launchedApp == lastLaunchApp) sameLaunchCount++ else { sameLaunchCount = 1 }
                    lastLaunchApp = launchedApp
                } else {
                    sameLaunchCount = 0
                    lastLaunchApp = ""
                }
                if (sameLaunchCount >= 3) {
                    val targetReached = targetAppPackage != null &&
                        AssistantAccessibilityService.instance?.currentPackage() == targetAppPackage
                    if (!targetReached) {
                        finalReply = "连续3次启动「$lastLaunchApp」仍未进入该应用。该应用可能不存在或无法打开，任务已自动停止。"
                        android.util.Log.i("AIButler", "AGLM sameLaunchCount breaker fired: app=$lastLaunchApp count=$sameLaunchCount")
                        break
                    }
                }
                // Bug#10b：Swipe 也要纳入防空转计数
                val swipeM = Regex("Swipe[^\\]]*start=\\[(-?\\d+)\\s*,\\s*(-?\\d+)\\]").find(answer)
                if (tapM == null && swipeM != null) {
                    val swipeSig = "${swipeM.groupValues[1]}_${swipeM.groupValues[2]}"
                    val swipeRepeated = swipeSig == lastTapBucket
                    if (swipeRepeated) sameTapCount++ else { sameTapCount = 1 }
                    lastTapBucket = swipeSig
                }
                if (sameTapCount >= 6) {
                    finalReply = "连续6次点击同一坐标都没有推进任务，已自动停止。请手动确认目标入口后重试。"
                    break
                }
                if (sameTapCount == 4) {
                    // 自愈第二档：滑屏无效时多半卡在全屏广告/视频层，按返回是通用退出方式
                    val backed = AssistantAccessibilityService.instance?.pressBack() == true
                    android.util.Log.i("AIButler", "AGLM 死点自愈: Back=$backed")
                    ctx.add(JsonObject().apply {
                        addProperty("role", "user")
                        addProperty(
                            "content",
                            "🛑 系统提示：系统已替你按了返回键，用于退出当前全屏视频/广告/弹层。" +
                                "请重新观察最新截图：如果已回到任务页或上级页面，按第18条继续下一轮；" +
                                "如果还在原页面，说明返回无效，改用截图左上/右上角的小 X 关闭，或换其他入口。"
                        )
                    })
                }
                if (sameTapCount == 3 || sameTapCount == 5) {
                    // 通用自愈：连续死点同一坐标时，系统直接代滑一屏制造页面变化，
                    // 同时把命中元素名告诉模型——任何App都适用，不依赖认识具体挂件
                    val actualHit = lastTapLabel.ifBlank { "未知元素" }
                    val cx = realW / 2f
                    val scrolled = AssistantAccessibilityService.instance?.swipePts(
                        cx, realH * 0.62f, cx, realH * 0.30f, 400
                    ) == true
                    android.util.Log.i("AIButler", "AGLM 死点自愈: hit=[$actualHit] scrolled=$scrolled")
                    ctx.add(JsonObject().apply {
                        addProperty("role", "user")
                        addProperty(
                            "content",
                            "🛑 系统提示：你已连续${sameTapCount}次点击同一坐标但任务毫无推进，该位置实际命中的元素是「$actualHit」。" +
                                "系统已替你下滑了一屏（页面已滚动）。请重新观察最新截图并换策略：" +
                                "目标入口现在可见就点它；仍被悬浮挂件挡住就先处理挂件（领取/关闭/跳过）；" +
                                "目标不在这页就继续 Swipe 翻找或换其他入口。绝不要再点原坐标。"
                        )
                    })
                }
                // 防空转：非等待动作重复=空转；页面文本完全没变的等待也=空转
                // （原因：视频/广告动画页每帧截图都不同，截图哈希会失效，必须靠无障碍文本判断）
                val isWait = answer.contains("Wait")
                val sig = answer + "|" + res.info
                if (!isWait) {
                    if (sig == lastProgressSig) noProgress++ else { noProgress = 1; lastProgressSig = sig }
                } else if (frozenPage) {
                    noProgress++
                    android.util.Log.i("AIButler", "AGLM 静止等待计入空转 x$noProgress（页面文本未变化）")
                }
                if (noProgress >= 8) {
                    ctx.add(JsonObject().apply {
                        addProperty("role", "user")
                        addProperty("content", "🛑 系统提示：已连续${noProgress}步毫无进展。如果这不是合理的等待类任务，请立即换方法；否则请直接 finish(message=\"说明当前结果\") 结束。")
                    })
                    android.util.Log.i("AIButler", "AGLM 无进展防护触发(硬) x$noProgress")
                    noProgress = 0
                } else if (noProgress >= 5) {
                    ctx.add(JsonObject().apply {
                        addProperty("role", "user")
                        addProperty("content", "💡 系统提示：动作已重复多次。如果这是合理的等待类任务流程，请忽略此提示继续干活；否则请换一种方法推进。")
                    })
                    android.util.Log.i("AIButler", "AGLM 无进展防护触发(软) x$noProgress")
                }
            }
            if (cancelRequested) {
                android.util.Log.i("AIButler", "AGENT 结束: 用户暂停(AutoGLM)")
                runOnUiThread {
                    adapter.add(ChatAdapter.Msg("system", "⏸️ 已暂停。想继续就说「继续」，想改主意直接说新的指令。"))
                    rv.scrollToPosition(adapter.items.size - 1)
                }
            } else {
                val reply = finalReply ?: "任务结束"
                android.util.Log.i("AIButler", "AGLM 最终回复: $reply")
                val judgedSuccess = reply.contains("✅")
                // 勾=记成功经验；叉=记失败经验（AI 下次避开）；null=不记录/超时未答。
                // AI 自述失败（无✅）时不弹窗，直接交给总结模型判失败经验。
                val verdict = if (judgedSuccess)
                    AssistantAccessibilityService.instance?.showSuccessConfirmation(userText, reply)
                else null
                val recordExperience = !judgedSuccess || verdict != null
                val forceSuccess = when (verdict) {
                    TaskVerdict.CONFIRMED -> true
                    TaskVerdict.MARK_FAILED -> false
                    else -> null
                }
                // Bug#3附带：目标应用从未到达时不允许保存为成功经验
                val targetNeverReached = guessedApp != null &&
                    (targetAppPackage == null || targetAppPackage !in visitedPackages)
                val effectiveForceSuccess = when {
                    targetNeverReached -> false
                    else -> forceSuccess
                }
                // 先把结果立刻显示给用户；经验总结要再跑一次大模型调用，放到后台，完成后再补充显示
                runOnUiThread {
                    adapter.add(ChatAdapter.Msg("assistant", reply))
                    rv.scrollToPosition(adapter.items.size - 1)
                }
                // 只有用户明确勾选「正确」才保存可回放路径
                val effectiveSteps = recordingSteps.filter { step ->
                    step.actionType == "back" || step.targetFingerprint != step.sourceFingerprint
                }
                if (recordExperience) {
                    if (verdict == TaskVerdict.CONFIRMED && effectiveSteps.isNotEmpty() && targetAppPackage != null) {
                        val taskKeywords = Regex("[\\u4e00-\\u9fa5]{2,}").findAll(userText)
                            .map { it.value }.filter { it.length >= 2 }.take(5).toList()
                        val savedPath = RecordedPath(
                            taskKeywords = taskKeywords,
                            targetPackage = targetAppPackage!!,
                            targetAppName = guessedApp?.first ?: "",
                            steps = effectiveSteps,
                            successCount = 1
                        )
                        PathMemoryStore.upsert(this, savedPath)
                        runOnUiThread {
                            adapter.add(ChatAdapter.Msg("system", "💾 已记录有效任务路径（${effectiveSteps.size}步），下次同类任务将自动快速回放。"))
                            rv.scrollToPosition(adapter.items.size - 1)
                        }
                    }
                    Thread {
                        val experienceResult = saveAutoGlmExperience(
                            userText, reply, visitedPackages.toList(), pageTrace.toList(), actionTrace.toList(),
                            forceSuccess = effectiveForceSuccess
                        )
                        if (experienceResult.isNotBlank()) runOnUiThread {
                            adapter.add(ChatAdapter.Msg("system", experienceResult))
                            rv.scrollToPosition(adapter.items.size - 1)
                        }
                    }.start()
                } else {
                    runOnUiThread {
                        adapter.add(ChatAdapter.Msg("system", "⛔ 未记录：本次任务路径和经验已丢弃。"))
                        rv.scrollToPosition(adapter.items.size - 1)
                    }
                }
                // 收尾：任务结束后如果手机还停在第三方App（广告外跳残留），拉回AI小助手界面，
                // 别把用户丢在美团/京东里对着黑屏发呆
                run {
                    val curPkg = AssistantAccessibilityService.instance?.currentPackage()
                    if (curPkg != null && !curPkg.startsWith("com.aibutler") &&
                        curPkg != "com.android.launcher" && curPkg != "unknown"
                    ) {
                        android.util.Log.i("AIButler", "AGLM 收尾：任务结束于 $curPkg，拉回AI小助手界面")
                        startActivity(
                            Intent(this, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        )
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("AIButler", "AGLM 异常 class=${e.javaClass.name} message=${e.message}", e)
            runOnUiThread {
                adapter.add(ChatAdapter.Msg("system", "❌ AutoGLM 出错了：${e.message}"))
                rv.scrollToPosition(adapter.items.size - 1)
            }
        }
    }

    /**
     * 应用名直查失败时做通用语义路由：不写死某类任务的执行步骤，
     * 只让模型从手机里真实存在的启动器应用中选一个目标包名。
     */
    private fun inferTargetApp(userText: String, contextUserText: String): Pair<String, String>? {
        data class RouteCandidate(val label: String, val pkg: String)

        val apps = packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).mapNotNull { ri ->
            val label = try { ri.loadLabel(packageManager).toString().trim() } catch (_: Exception) { "" }
            if (ri.activityInfo.packageName == packageName || label.length < 2) {
                null
            } else {
                RouteCandidate(label, ri.activityInfo.packageName)
            }
        }.distinctBy { it.pkg }.sortedBy { it.label }
        if (apps.isEmpty()) return null
        android.util.Log.i("AIButler", "AGLM app routing start: candidates=${apps.size}")

        val prompt = """
            你是安卓手机的应用路由器。先判断用户任务需要在手机上进入什么应用，再从候选应用中选择最合适的一个。
            候选应用格式：应用名|包名
            ${apps.joinToString("\n") { "${it.label}|${it.pkg}" }}

            用户任务：$userText
            近期上下文：${contextUserText.take(900)}

            只输出下面其中一行，不要 Markdown，不要解释：
            do(action="Launch", app="候选列表里的完整应用名")
            do(action="Wait", duration="1 seconds")

            规则：
            - 按任务能力判断，不按文字表面相似度机械匹配；新闻/搜索类可选择新闻应用或浏览器，闹钟/时间类选择时钟应用。
            - app 的值必须逐字来自候选列表的应用名；候选外应用一律不能输出。
            - 找不到合适应用、纯聊天或纯写作时只输出 Wait。
            - 优先选择用户明确提到的应用；没有明确提及时选择手机上最合适、最通用的入口。
        """.trimIndent()

        return try {
            val messages = JsonArray().apply { add(AgentEngine.msg("user", prompt)) }
            val raw = LlmClient.chat(
                Prefs.baseUrl(this), Prefs.apiKey(this), Prefs.model(this),
                messages, JsonArray(), Prefs.fastMode(this)
            ).content
            android.util.Log.i("AIButler", "AGLM app routing raw=${raw.take(500)}")
            val action = raw.lineSequence()
                .firstOrNull { it.contains("do(", ignoreCase = true) }
                ?.trim()
                .orEmpty()
            val match = Regex(
                """do\s*\(\s*action\s*=\s*["']([^"']+)["']\s*,\s*app\s*=\s*["']([^"']+)["']""",
                RegexOption.IGNORE_CASE
            ).find(action)
            if (match == null || !match.groupValues[1].equals("Launch", ignoreCase = true)) {
                android.util.Log.i("AIButler", "AGLM app routing selected=null action=${action.take(200)}")
                return null
            }

            val requested = match.groupValues[2].trim()
            val normalizedRequested = requested
                .replace("\\s+".toRegex(), "")
                .lowercase(Locale.US)
            val matched = apps.firstOrNull {
                it.pkg.replace("\\s+".toRegex(), "").lowercase(Locale.US) == normalizedRequested
            } ?: apps.firstOrNull {
                it.label.replace("\\s+".toRegex(), "").lowercase(Locale.US) == normalizedRequested
            }
            if (matched == null) {
                android.util.Log.w("AIButler", "AGLM app routing rejected non-launcher app=$requested")
                return null
            }
            android.util.Log.i("AIButler", "AGLM app routing selected=${matched.label}|${matched.pkg}")

            runOnUiThread {
                adapter.add(ChatAdapter.Msg("system", "🧭 已判断目标应用：${matched.label}"))
                rv.scrollToPosition(adapter.items.size - 1)
            }
            matched.label to matched.pkg
        } catch (e: Exception) {
            android.util.Log.w("AIButler", "AGLM app routing failed", e)
            null
        }
    }

    private fun saveVisionFrame(round: Int, b64: String): File? {
        return runCatching {
            val data = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size)
                ?: return null
            val dir = File(filesDir, "vision_frames").apply { mkdirs() }
            val file = File(dir, "round_${String.format(Locale.US, "%05d", round)}_${System.currentTimeMillis()}.jpg")
            file.outputStream().use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 72, out)
            }
            bitmap.recycle()
            file
        }.getOrNull()
    }

    /** 函数调用模式的经验保存：把任务结果存入经验库，下次同类任务注入系统提示 */
    private fun saveAgentExperience(task: String, result: String, requireSuccessConfirmation: Boolean = false) {
        if (!Prefs.configured(this)) return
        try {
            val guessed = !result.contains("失败") && !result.contains("无法") &&
                !result.contains("超时") && !result.contains("已停止") && !result.contains("拦截")
            if (result.length < 5) return
            var recordSuccess = guessed
            if (guessed && requireSuccessConfirmation) {
                // 疑似成功要经用户勾/叉确认：勾=成功经验，叉=失败经验，不记录=丢弃
                when (AssistantAccessibilityService.instance?.showSuccessConfirmation(task, result)) {
                    TaskVerdict.CONFIRMED -> recordSuccess = true
                    TaskVerdict.MARK_FAILED -> recordSuccess = false
                    else -> {
                        runOnUiThread {
                            adapter.add(ChatAdapter.Msg("system", "⛔ 未确认；本次不记录经验。"))
                            rv.scrollToPosition(adapter.items.size - 1)
                        }
                        return
                    }
                }
            }
            AgentExperienceStore.upsert(this, AgentExperience(
                title = task.take(20),
                content = result.take(280),
                keywords = Regex("[\\u4e00-\\u9fa5]{2,}").findAll(task).map { it.value }.toList().take(5),
                success = recordSuccess
            ))
            android.util.Log.i("AIButler", "AGENT 经验已保存: ${task.take(30)} 判定=${if (recordSuccess) "成功" else "失败"}")
        } catch (e: Exception) {
            android.util.Log.w("AIButler", "保存经验失败", e)
        }
    }

    /** 从 AutoGLM 动作字符串中解析出可录制回放的动作类型和目标；不可回放的动作返回 null */
    private fun parseActionForRecording(answer: String, screenDump: String? = null): Tuple4<String, String, Int, Int>? {
        val tapMatch = Regex("do\\s*\\(\\s*action\\s*=\\s*\"Tap\"\\s*,\\s*element\\s*=\\s*\\[(-?\\d+)\\s*,\\s*(-?\\d+)\\]").find(answer)
        if (tapMatch != null) {
            val x = tapMatch.groupValues[1].toIntOrNull() ?: return null
            val y = tapMatch.groupValues[2].toIntOrNull() ?: return null
            val targetX = x.coerceIn(0, 999)
            val targetY = y.coerceIn(0, 999)
            val label = screenDump.orEmpty().lineSequence()
                .mapNotNull { line ->
                    val match = Regex("可点击:\\s*(.+?)\\s*\\[(\\d+),(\\d+)\\]").find(line)
                    if (match == null) null else Triple(
                        match.groupValues[1].trim(),
                        match.groupValues[2].toIntOrNull() ?: -999,
                        match.groupValues[3].toIntOrNull() ?: -999
                    )
                }
                .filter { (_, mx, my) -> mx in 0..999 && my in 0..999 }
                .minByOrNull { (_, mx, my) -> (mx - targetX) * (mx - targetX) + (my - targetY) * (my - targetY) }
                ?.takeIf { (_, mx, my) -> (mx - targetX) * (mx - targetX) + (my - targetY) * (my - targetY) <= 25 }
                ?.first
                .orEmpty()
                .take(40)
            return Tuple4("tap", label, targetX, targetY)
        }
        if (Regex("do\\s*\\(\\s*action\\s*=\\s*\"Back\"").containsMatchIn(answer)) {
            return Tuple4("back", "", 0, 0)
        }
        val swipeMatch = Regex("do\\s*\\(\\s*action\\s*=\\s*\"Swipe\"\\s*,\\s*start\\s*=\\s*\\[(-?\\d+)\\s*,\\s*(-?\\d+)\\]\\s*,\\s*end\\s*=\\s*\\[(-?\\d+)\\s*,\\s*(-?\\d+)\\]").find(answer)
        if (swipeMatch != null) {
            val sy = swipeMatch.groupValues[2].toIntOrNull() ?: return null
            val ey = swipeMatch.groupValues[4].toIntOrNull() ?: return null
            return if (ey < sy) Tuple4("swipe_up", "", 0, 0) else Tuple4("swipe_down", "", 0, 0)
        }
        return null
    }

    private data class Tuple4<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    private fun saveAutoGlmExperience(
        task: String,
        result: String,
        packages: List<String>,
        pages: List<String>,
        actions: List<String>,
        forceSuccess: Boolean? = null
    ): String {
        if (!Prefs.configured(this)) return ""
        return try {
            val userOverride = when (forceSuccess) {
                true -> "\n用户已人工确认本次任务实际是「成功」，SUCCESS 字段必须填 true。"
                false -> "\n用户已人工确认本次任务实际是「失败」，SUCCESS 字段必须填 false；请总结这次失败的原因和应避开的走法。"
                null -> ""
            }
            val prompt = """
                请把下面一次手机自动操作总结为可复用经验。不要记录账号、密码、验证码、IMEI、余额等敏感值；只记录应用名、页面名、入口文字、路径、弹窗处理和失败原因。
                严格输出以下字段：
                TITLE: 不超过20字的经验标题
                CONTENT: 不超过300字的路径和要点
                KEYWORDS: 逗号分隔的检索关键词
                PACKAGES: 逗号分隔的安卓包名；不确定就留空
                SUCCESS: true 或 false
                $userOverride

                用户任务：$task
                最终结果：$result
                出现过的包名：${packages.joinToString(",")}
                页面片段：${pages.takeLast(8).joinToString(" | ")}
                动作片段：${actions.takeLast(20).joinToString(" | ")}
            """.trimIndent()
            val messages = JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "user")
                    addProperty("content", prompt)
                })
            }
            val raw = LlmClient.chat(
                Prefs.baseUrl(this), Prefs.apiKey(this), Prefs.model(this), messages, JsonArray(), true
            ).content
            fun field(name: String) = Regex("(?im)^\\s*$name\\s*:\\s*(.+)$").find(raw)?.groupValues?.get(1)?.trim().orEmpty()
            val title = field("TITLE").ifBlank { task.take(20) }
            val content = field("CONTENT").ifBlank { result.take(280) }
            val keywords = field("KEYWORDS").split(',', '，', ';', '；').map { it.trim() }.filter { it.isNotBlank() }
            val packageNames = field("PACKAGES").split(',', '，', ';', '；').map { it.trim() }.filter { it.isNotBlank() }
            val success = forceSuccess ?: field("SUCCESS").lowercase().contains("true")
            AgentExperienceStore.upsert(
                this, AgentExperience(title, content, keywords, packageNames, success)
            )
            "📚 已保存可复用经验：$title"
        } catch (e: Exception) {
            android.util.Log.w("AIButler", "保存经验失败", e)
            ""
        }
    }

    private fun setBusyUi(busyNow: Boolean, success: Boolean = true) {
        findViewById<SubmitMorphButton>(R.id.btnSend).apply {
            setBusy(busyNow, success)
            contentDescription = if (busyNow) "停止" else "发送"
        }
    }

    /** 历史压缩：超过上限时裁剪旧消息，保留系统提示和最近的完整对话轮次；
     *  并把较早的读屏/视觉结果（每条可达数千字）替换为占位摘要，避免每轮请求体越滚越大 */
    private fun compactHistory(maxMessages: Int = 14, keepFullTools: Int = 6) {
        if (history.size() <= maxMessages) {
            summarizeOldToolResults(keepFullTools)
            return
        }
        val keep = JsonArray()
        keep.add(history.get(0))
        var start = history.size() - (maxMessages - 1)
        if (start < 1) start = 1
        while (start < history.size()) {
            val role = history.get(start).asJsonObject.get("role")?.asString
            if (role == "assistant") break
            start++
        }
        if (start >= history.size()) return
        for (i in start until history.size()) keep.add(history.get(i))
        while (history.size() > 0) history.remove(0)
        for (e in keep) history.add(e)
        summarizeOldToolResults(keepFullTools)
    }

    /** 从最新往前保留 keepFullTools 条工具结果完整，更早的长结果替换为占位摘要 */
    private fun summarizeOldToolResults(keepFullTools: Int) {
        var toolSeen = 0
        for (i in history.size() - 1 downTo 1) {
            val o = history.get(i).asJsonObject
            if (o.get("role")?.asString != "tool") continue
            toolSeen++
            if (toolSeen <= keepFullTools) continue
            val c = o.get("content") ?: continue
            if (c.isJsonNull || !c.isJsonPrimitive) continue
            val text = c.asString
            if (text.length > 400) {
                o.addProperty("content", "[旧读屏/工具结果已省略，需要时重新调用 get_screen_state] " + text.take(80))
            }
        }
    }

    /** 返回当前历史（已在每轮开头做过压缩） */
    private fun sanitizeHistory(): JsonArray {
        return history
    }

    // ---------- 语音 ----------

    private fun startVoice() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 100)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "这台手机缺少语音识别服务（可在设置里安装谷歌语音或用厂商输入法的语音），请直接打字。", Toast.LENGTH_LONG).show()
            return
        }
        recognizer?.destroy()
        val sr = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = sr
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                if (text.isNotBlank()) runOnUiThread { runAgent(text, fromVoice = true) }
            }
            override fun onError(error: Int) {
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "没听清，再试一次？"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没检测到说话"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少麦克风权限"
                    else -> "语音识别出错(code=$error)，可以试试打字"
                }
                runOnUiThread { Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show() }
            }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        sr.startListening(intent)
        Toast.makeText(this, "请说出你的指令…", Toast.LENGTH_SHORT).show()
    }

    private fun speak(text: String) {
        tts?.speak(text.take(400), TextToSpeech.QUEUE_FLUSH, null, "reply_${System.currentTimeMillis()}")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startVoice()
        if (requestCode == 300) requestCapture()
        if (requestCode == 400) {
            // 运行时权限授权完毕，继续检查截图
            if (!CaptureService.isActive()) requestCapture()
        }
    }

    override fun onDestroy() {
        recognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }
}
