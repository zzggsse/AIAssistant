package com.aibutler.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
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
    @Volatile
    private var activeTaskText: String? = null
    @Volatile
    private var lastCompletedTaskText: String? = null
    @Volatile
    private var lastCompletedTaskAt: Long = 0L

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speakOn = true
    private var recognizer: SpeechRecognizer? = null
    private var captureStarting = false

    // ===== 暂停后「继续」的恢复状态 =====
    private data class PausedTask(
        val mode: String,               // "normal" or "autoglm"
        val userText: String,
        val fromVoice: Boolean,
        val contextUserText: String,
        val plan: AgentEngine.TaskPlan?,
        val currentStep: Int,
        val launchedTarget: Pair<String, String>?,
        val aglmCtx: JsonArray?,
        val aglmGuessedApp: Pair<String, String>?,
        val aglmRelatedMemory: String,
        val historySnapshot: JsonArray
    )
    @Volatile
    private var pausedTask: PausedTask? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        CrashLogger.install(this)
        NotificationCatcher.init(this)
        runCatching { AgentExperienceStore.all(this) }
        val appVersion = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "unknown"
        android.util.Log.i("AIButler", "APP onCreate build=v$appVersion (双脑切换+AutoGLM专用脑+全能力)")
        Prefs.ensureThinkingVisible(this)
        Prefs.ensureAutoGlmDefault(this)

        adapter = ChatAdapter(this)
        adapter.loadPersisted()
        TaskControlBus.pauseHandler = { returnToAssistant ->
            if (busy) {
                tts?.stop()
                if (returnToAssistant) returnToConversationAfterTask()
                runOnUiThread {
                    adapter.add(
                        ChatAdapter.Msg(
                            "system",
                            if (returnToAssistant) "⏸️ 已开始返回助手；当前动作收尾后会停下。"
                            else "⏸️ 收到，正在暂停（当前步骤完成后停下）…"
                        )
                    )
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
        findViewById<View>(R.id.btnCollapseThinking).setOnClickListener {
            if (adapter.collapseAllThinking()) {
                Toast.makeText(this, "已收起全部思考", Toast.LENGTH_SHORT).show()
            }
        }
        adapter.onZoomImage = { path, preview ->
            ImageZoomDialog.show(this, path, preview)
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
            hideKeyboardForHistory()
            refreshHistory()
            drawerLayout.openDrawer(android.view.Gravity.START)
        }
        findViewById<View>(R.id.btnNewChat).setOnClickListener { startNewConversation(closeDrawer = true) }
        findViewById<View>(R.id.btnTopNewChat).setOnClickListener { startNewConversation(closeDrawer = false) }
        findViewById<View>(R.id.btnDrawerSettings).setOnClickListener {
            drawerLayout.closeDrawer(android.view.Gravity.START)
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.btnVisualSamples).setOnClickListener {
            VisualSampleListDialog.show(this) {
                showVisualSampleLauncher()
            }
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
        uiTickHandler.postDelayed(uiTick, 2000)

        // 调试令牌：仅用于 adb 远程测试（令牌打印在 logcat，只有有 adb 权限的人能看到）
        val sp = Prefs.get(this)
        lastCompletedTaskText = sp.getString("lastCompletedTaskText", null)
        lastCompletedTaskAt = sp.getLong("lastCompletedTaskAt", 0L)
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
        lastCompletedTaskText = null
        Prefs.get(this).edit()
            .remove("lastCompletedTaskText")
            .remove("lastCompletedTaskAt")
            .apply()
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
                        if (msg == activeTaskText || pendingRuns.contains(msg)) {
                            android.util.Log.i("AIButler", "AGLM dbg duplicate ignored while busy: $msg")
                            return
                        }
                        // 只拦真正的重复下发；不同指令才允许取消当前任务并排队（同语义改为替换整条队列）
                    cancelRequested = true
                    pendingRuns.clear()
                    pendingRuns.add(msg)
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
        resumePendingRunIfReady()
        removePermissionWarnings()
        updateEmptyState()
    }

    /** 权限补全后自动继续排队中的任务 */
    private fun resumePendingRunIfReady() {
        val pending = synchronized(pendingRuns) { pendingRuns.firstOrNull() } ?: return
        if (busy) return
        if (!checkPermissionsOrLaunch()) return
        synchronized(pendingRuns) {
            // 再核对队头：这期间任务线程可能刚好取走了它
            if (pendingRuns.firstOrNull() == pending) pendingRuns.removeAt(0)
        }
        Thread {
            if (!CaptureService.isActive()) {
                var waited = 0
                while (!CaptureService.isActive() && waited < 5000) {
                    Thread.sleep(200)
                    waited += 200
                }
            }
            runOnUiThread {
                if (checkPermissionsOrLaunch()) {
                    adapter.add(ChatAdapter.Msg("system", "▶️ 权限已就绪，继续执行刚才的任务"))
                    runAgent(pending, fromVoice = false)
                } else {
                    pendingRuns.add(0, pending)
                }
            }
        }.start()
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
        // 只授 WRITE 不授 READ 时，日历查询会抛 SecurityException 被误报成"没有可写的日历"（体检 P2-9）
        if (checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.READ_CALENDAR)
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            if (checkSelfPermission(Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.WRITE_CALENDAR)
        }
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), 400)
            return
        }
        // 运行时权限都有了，检查截图
        if (!CaptureService.isActive()) requestCapture()
    }

    /** 权限已全部开启时，清除聊天里的"XX未启动"警告。
     *  不再按「重试」关键词过滤：LLM 的正常报错（如"请求超时（已重试1次）"）会被误删（体检 P2-8） */
    private fun removePermissionWarnings() {
        val toRemove = adapter.items.filter {
            it.role == "system" && (it.text.contains("未连接") || it.text.contains("未启动") ||
                it.text.contains("用不了"))
        }
        if (toRemove.isNotEmpty()) {
            adapter.items.removeAll(toRemove)
            adapter.notifyDataSetChanged()
            adapter.saveCurrent()
        }
    }

    private fun removeA11yStatusMessages() {
        val statusTexts = buildSet {
            add("⚠️ 无障碍服务未连接，发指令时会自动弹出授权页面")
            add("✅ 无障碍服务已连接，可以自动操作手机了")
            oemHint()?.let(::add)
        }
        val toRemove = adapter.items.filter { it.role == "system" && it.text in statusTexts }
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

    private fun hideKeyboardForHistory() {
        currentFocus?.let { focusedView ->
            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(focusedView.windowToken, 0)
        }
        etInput.clearFocus()
    }

    private fun openConversation(conversation: ConversationStore.Conversation) {
        if (busy) {
            Toast.makeText(this, "任务进行中，暂不能切换对话", Toast.LENGTH_SHORT).show()
            return
        }
        while (history.size() > 0) history.remove(0)
        adapter.switchTo(conversation.id)
        lastCompletedTaskText = null
        Prefs.get(this).edit()
            .remove("lastCompletedTaskText")
            .remove("lastCompletedTaskAt")
            .apply()
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
        AlertDialog.Builder(this, R.style.ThemeOverlay_Assistant_AlertDialog)
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
            val pending = synchronized(pendingRuns) { pendingRuns.firstOrNull() }
            if (pending != null) {
                synchronized(pendingRuns) {
                    if (pendingRuns.firstOrNull() == pending) pendingRuns.removeAt(0)
                }
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
                            pendingRuns.add(0, pending)
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
            removeA11yStatusMessages()
            if (!ok) {
                adapter.add(ChatAdapter.Msg("system", "⚠️ 无障碍服务未连接，发指令时会自动弹出授权页面"))
                oemHint()?.let { adapter.add(ChatAdapter.Msg("system", it)) }
            } else {
                adapter.add(ChatAdapter.Msg("system", "✅ 无障碍服务已连接，可以自动操作手机了"))
            }
        }
        lastA11yOk = ok
    }

    /** 国产 ROM 后台限制提示：检测到限制型厂商时给用户一行引导 */
    private fun oemHint(): String? {
        val m = android.os.Build.MANUFACTURER.lowercase()
        return when {
            m.contains("xiaomi") || m.contains("redmi") ->
                "小米手机：设置→应用管理→AI小助手→自启动 打开；省电策略设为「无限制」"
            m.contains("huawei") || m.contains("honor") ->
                "华为/荣耀：设置→电池→启动管理→AI小助手→关闭自动管理，允许后台活动"
            m.contains("oppo") || m.contains("realme") || m.contains("oneplus") ->
                "OPPO/一加：设置→电池→应用耗电管理→AI小助手→允许后台运行"
            m.contains("vivo") || m.contains("iqoo") ->
                "vivo：设置→电池→后台耗电→AI小助手→允许后台高耗电"
            m.contains("samsung") ->
                "三星：设置→电池→后台使用限制→确保AI小助手不在休眠列表中"
            else -> null
        }
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
        findViewById<View>(R.id.btnCollapseThinking).visibility =
            if (adapter.items.any { it.role == "think" && !it.collapsed }) View.VISIBLE else View.GONE
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

    private fun runAgent(userText: String, fromVoice: Boolean) {
        // ===== 暂停恢复：用户说「继续」且刚暂停过 → 恢复上下文继续执行 =====
        val trimmedText = userText.trim()
        if ((trimmedText == "继续" || trimmedText == "接着做" || trimmedText == "接着来") && pausedTask != null && !busy) {
            val saved = pausedTask!!
            pausedTask = null
            cancelRequested = false
            busy = true
            activeTaskText = saved.userText
            TaskControlBus.taskRunning = true
            AssistantAccessibilityService.instance?.showTaskControls()
            setBusyUi(true)
            AgentKeepAliveService.start(this)
            adapter.add(ChatAdapter.Msg("user", userText))
            adapter.add(ChatAdapter.Msg("system", "▶️ 正在恢复之前的任务：${saved.userText.take(50)}…"))
            rv.scrollToPosition(adapter.items.size - 1)

            Thread {
                try {
                    if (saved.mode == "autoglm" && saved.aglmCtx != null) {
                        resumeAutoGLMFrom(saved)
                    } else {
                        resumeNormalFrom(saved)
                    }
                } catch (e: Exception) {
                    CrashLogger.log("RESUME", "Failed to resume task", e)
                    runOnUiThread {
                        adapter.add(ChatAdapter.Msg("system", "❌ 恢复任务出错：${e.message}，请重新描述任务"))
                        rv.scrollToPosition(adapter.items.size - 1)
                    }
                } finally {
                    busy = false
                    activeTaskText = null
                    TaskControlBus.taskRunning = false
                    AssistantAccessibilityService.instance?.hideTaskControls()
                    AgentKeepAliveService.stop(this@MainActivity)
                    runOnUiThread { setBusyUi(false) }
                    returnToConversationAfterTask()
                }
            }.start()
            return
        }
        // 不是「继续」→ 清掉旧的暂停状态，走新任务
        if (trimmedText != "继续") pausedTask = null
        val isRepeatedCompletedTask = trimmedText == lastCompletedTaskText &&
            System.currentTimeMillis() - lastCompletedTaskAt < 60_000
        if (isRepeatedCompletedTask) {
            Toast.makeText(this, "已拦截自动重复指令；如需重跑，请开启新对话", Toast.LENGTH_SHORT).show()
            return
        }

        if (busy) {
            // 与 handleDebugSend 对齐：忙时同指令去重，语音连说两遍不会排两条（体检 P2-20）
            if (trimmedText == activeTaskText || pendingRuns.contains(userText)) {
                Toast.makeText(this, "已拦截自动重复指令；如需重跑，请开启新对话", Toast.LENGTH_SHORT).show()
                return
            }
            Toast.makeText(this, "⏳ AI 正忙，任务已排队，完成后自动开始", Toast.LENGTH_SHORT).show()
            pendingRuns.add(userText)
            adapter.add(ChatAdapter.Msg("system", "已排队：$userText"))
            return
        }
        // 统一走 TaskRouter：本地确定性直达解析（设闹钟/记便签，零模型调用）
        val nativeDirect = TaskRouter.parseNativeDirect(userText)
        val directAlarm = (nativeDirect as? TaskRouter.NativeDirect.Alarm)?.req
        val directNote = (nativeDirect as? TaskRouter.NativeDirect.Note)?.req
        val isNativeDirectTask = nativeDirect !is TaskRouter.NativeDirect.None
        if (!isNativeDirectTask && !Prefs.configured(this)) {
            adapter.add(ChatAdapter.Msg("system", "还没配置 AI 厂商，请先点右上角 ⚙️ 设置。"))
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        if (!isNativeDirectTask && !checkPermissionsOrLaunch()) {
            pendingRuns.add(userText)
            return
        }
        val pw = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val km = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        if (!pw.isInteractive || km.isKeyguardLocked) {
            android.util.Log.i("AIButler", "APP 屏幕熄灭/锁定中启动任务——将尝试自动亮屏解锁")
        }
        busy = true
        activeTaskText = userText
        cancelRequested = false
        AgentEngine.resetNavigationMemory()
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
            var endedInError = false
            try {
                if (directAlarm != null) {
                    android.util.Log.i(
                        "AIButler",
                        "NATIVE alarm direct: ${directAlarm.hour}:${directAlarm.minute} label=${directAlarm.label}"
                    )
                    runOnUiThread {
                        adapter.add(ChatAdapter.Msg(
                            "system",
                            "⏰ 正在设置 %02d:%02d 的闹钟…".format(
                                directAlarm.hour,
                                directAlarm.minute
                            )
                        ))
                        rv.scrollToPosition(adapter.items.size - 1)
                    }
                    val args = JsonObject().apply {
                        addProperty("hour", directAlarm.hour)
                        addProperty("minute", directAlarm.minute)
                        if (directAlarm.label.isNotBlank()) addProperty("label", directAlarm.label)
                    }
                    val result = AgentEngine.execute(this, "set_alarm", args.toString())
                    runOnUiThread {
                        val success = result.startsWith("已设置 ") && result.contains("确认闹钟已开启")
                        val prefix = if (success) "✅ " else "⚠️ "
                        adapter.add(ChatAdapter.Msg("assistant", "$prefix$result"))
                        rv.scrollToPosition(adapter.items.size - 1)
                    }
                    return@Thread
                }
                if (directNote != null) {
                    val args = JsonObject().apply {
                        addProperty("title", directNote.title)
                        addProperty("content", directNote.content)
                    }
                    val result = AgentEngine.execute(this, "create_note", args.toString())
                    runOnUiThread {
                        adapter.add(ChatAdapter.Msg("assistant", "✅ $result\n内容：${directNote.content}"))
                        rv.scrollToPosition(adapter.items.size - 1)
                    }
                    return@Thread
                }

                // ===== 任务规划：先拆解再执行（简单的单步指令跳过规划，省一次大模型往返） =====
                val relatedExp = AgentExperienceStore.relevant(this, userText)
                var plan: AgentEngine.TaskPlan? = null
                var currentStep = 1
                var stepRetries = 0
                var replansUsed = 0
                val quickTask = TaskRouter.isQuickTask(userText)
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
                // 大脑路由唯一出口（TaskRouter.routeBrain）：决策与原内联判断行为一致
                val route = TaskRouter.routeBrain(userText, plan)
                val chatPlan = route.chatPlan
                val looksLikeChat = route.looksLikeChat
                val hasNativeToolMatch = route.nativeToolMatch
                val skipAutoGlm = route.skipAutoGlm
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
                // 普通大脑也先做通用目标应用路由：显式 App 名优先，未点名时由真实安装列表语义选择。
                // 这一步发生在模型执行前，因此任何任务都不再依赖模型是否恰好调用 open_app。
                val launchedTarget = if (route.launchTargetApp) {
                    launchTargetAppForTask(userText, contextUserText)
                } else null
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

                // ===== 跨任务上下文隔离：新任务开始时清掉上一轮的工具调用残留 =====
                // 保留 system 提示和上一轮的最终回答（作为上一任务的参考），其余全清。
                run {
                    val lastAssistantReply = StringBuilder()
                    for (i in history.size() - 1 downTo 0) {
                        val e = history.get(i).asJsonObject
                        if (e.get("role")?.asString == "assistant" &&
                            e.get("tool_calls") == null &&
                            e.get("content")?.asString?.isNotBlank() == true) {
                            lastAssistantReply.append(e.get("content").asString.take(500))
                            break
                        }
                    }
                    while (history.size() > 0) history.remove(0)
                    if (lastAssistantReply.isNotBlank()) {
                        history.add(AgentEngine.msg("assistant", "（上一任务的参考结果）${lastAssistantReply}"))
                    }
                }

                // 确保 system 提示永远在第一位（JsonArray 不能按位插入，只能重建）
                if (history.size() == 0 || history.get(0).asJsonObject.get("role")?.asString != "system") {
                    val fresh = JsonArray()
                    fresh.add(AgentEngine.systemPrompt(plan, currentStep, relatedExp, hasNativeToolMatch).let { AgentEngine.msg("system", it) })
                    for (e in history) fresh.add(e)
                    while (history.size() > 0) history.remove(0)
                    for (e in fresh) history.add(e)
                } else {
                    history.get(0).asJsonObject.addProperty("content", AgentEngine.systemPrompt(plan, currentStep, relatedExp, hasNativeToolMatch))
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
                    // 流式响应：用户实时看到内容增长，不再干等
                    var streamIdx = -1
                    val streamBuf = StringBuilder()
                    val res = try {
                        LlmClient.chatStream(
                            Prefs.baseUrl(this), Prefs.apiKey(this), Prefs.model(this),
                            sanitizeHistory(), AgentEngine.toolsJson(), Prefs.fastMode(this),
                            onDelta = { chunk ->
                                streamBuf.append(chunk)
                                val text = streamBuf.toString()
                                runOnUiThread {
                                    if (streamIdx < 0 || streamIdx >= adapter.items.size) {
                                        adapter.items.add(ChatAdapter.Msg("assistant", text))
                                        streamIdx = adapter.items.size - 1
                                    } else {
                                        adapter.items[streamIdx].text = text
                                    }
                                    adapter.notifyDataSetChanged()
                                    rv.scrollToPosition(adapter.items.size - 1)
                                }
                            }
                        )
                    } catch (e: Exception) {
                        CrashLogger.log("STREAM_FALLBACK", "chatStream failed, falling back to non-streaming", e)
                        LlmClient.chat(
                            Prefs.baseUrl(this), Prefs.apiKey(this), Prefs.model(this),
                            sanitizeHistory(), AgentEngine.toolsJson(), Prefs.fastMode(this)
                        )
                    }
                    // 清除流式过渡气泡（后续由 emitThinking / 最终 assistant 消息替代）
                    if (streamIdx >= 0) {
                        val idx = streamIdx
                        runOnUiThread {
                            if (idx < adapter.items.size && adapter.items[idx].role == "assistant") {
                                adapter.items.removeAt(idx)
                                adapter.notifyDataSetChanged()
                            }
                        }
                    }
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
                            requireOutcomeJudgement = plan?.taskType == "screen_task"
                        )
                        runOnUiThread {
                            adapter.add(ChatAdapter.Msg("assistant", reply))
                            rv.scrollToPosition(adapter.items.size - 1)
                            if (speakOn && ttsReady && !cancelRequested) speak(reply)
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
                        // ===== replan 工具：AI 主动请求重新规划 =====
                        if (call.name == "replan") {
                            val replanReason = try {
                                JsonParser.parseString(call.arguments).asJsonObject
                                    .get("reason")?.takeIf { !it.isJsonNull }?.asString ?: ""
                            } catch (e: Exception) { "" }
                            CrashLogger.log("REPLAN", "AI requested replan: $replanReason")
                            if (replansUsed < 3) {
                                try {
                                    val replanMsgs = JsonArray()
                                    replanMsgs.add(AgentEngine.msg("user", AgentEngine.plannerPrompt(
                                        "$userText\n\n（AI 请求重新规划，原因：$replanReason。请给出新的执行计划。）",
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
                                            adapter.add(ChatAdapter.Msg("system", "🔄 AI 请求重新规划：$replanReason\n已生成新计划（第${replansUsed}次重新规划）"))
                                            rv.scrollToPosition(adapter.items.size - 1)
                                        }
                                    }
                                } catch (e: Exception) {
                                    CrashLogger.log("REPLAN_FAIL", "replan failed", e)
                                }
                            }
                            history.add(AgentEngine.toolMsg(call.id, "已处理重新规划请求。请继续按新计划执行。"))
                            continue
                        }
                        val result = AgentEngine.execute(this@MainActivity, call.name, call.arguments, launchedTarget?.second)
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
                    pausedTask = PausedTask(
                        mode = "normal",
                        userText = userText,
                        fromVoice = fromVoice,
                        contextUserText = contextUserText,
                        plan = plan,
                        currentStep = currentStep,
                        launchedTarget = launchedTarget,
                        aglmCtx = null,
                        aglmGuessedApp = null,
                        aglmRelatedMemory = relatedExp,
                        historySnapshot = history.deepCopy()
                    )
                    runOnUiThread {
                        adapter.add(ChatAdapter.Msg("system", "⏸️ 已暂停。想继续就说「继续」，想改主意直接说新的指令。"))
                        rv.scrollToPosition(adapter.items.size - 1)
                    }
                }
            } catch (e: Exception) {
                endedInError = true
                android.util.Log.e("AIButler", "AGENT 异常", e)
                CrashLogger.log("AGENT", "Agent task failed for: $userText", e)
                runOnUiThread {
                    adapter.add(ChatAdapter.Msg("system", "❌ 出错了：${e.message}"))
                    rv.scrollToPosition(adapter.items.size - 1)
                }
            } finally {
                busy = false
                activeTaskText = null
                // 只把「自然跑完」的任务记为已完成：用户主动暂停/出错的任务允许 60 秒内
                // 重发同指令重试，不再被「已拦截自动重复指令」误拦（体检 P1-7）
                if (!endedInError && !cancelRequested) {
                    lastCompletedTaskText = userText
                    lastCompletedTaskAt = System.currentTimeMillis()
                    Prefs.get(this).edit()
                        .putString("lastCompletedTaskText", userText)
                        .putLong("lastCompletedTaskAt", lastCompletedTaskAt)
                        .apply()
                }
                TaskControlBus.taskRunning = false
                AssistantAccessibilityService.instance?.hideTaskControls()
                AgentKeepAliveService.stop(this@MainActivity)
                runOnUiThread { setBusyUi(false, success = !cancelRequested) }
                val next = synchronized(pendingRuns) { pendingRuns.removeFirstOrNull() }
                if (next != null) {
                    runOnUiThread {
                        adapter.add(ChatAdapter.Msg("system", "▶️ 排队的任务开始了"))
                        runAgent(next, fromVoice = false)
                    }
                } else {
                    returnToConversationAfterTask()
                }
            }
        }.start()
    }

    /** 任务线程收尾统一回对话；用户主动切走后，也不把结果留在别的应用里。 */
    private fun returnToConversationAfterTask() {
        if (isFinishing || isDestroyed) return
        val currentPackage = AssistantAccessibilityService.instance?.currentPackage()
        if (currentPackage == packageName) return

        android.util.Log.i("AIButler", "TASK finish return: from=$currentPackage")
        val service = AssistantAccessibilityService.instance
        if (service != null) {
            service.returnToConversation(currentPackage, MainActivity::class.java)
        } else {
            runCatching {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
            }.onFailure {
                android.util.Log.w("AIButler", "TASK finish return failed", it)
            }
        }
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

    /** 忙时排队的任务（FIFO）：每条指令独立执行，不再用换行拼成一条。
     *  synchronizedList：主线程 add/remove 与任务线程 removeFirstOrNull 并发，裸 ArrayList
     *  有极小概率抛 IndexOutOfBounds 直接崩（体检 P1-8）；复合操作处再用 synchronized(pendingRuns) */
    private val pendingRuns: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
    private var projectionRequestActive = false

    /** 界面重建后持续刷新 busy 状态的 tick（onDestroy 里统一移除回调，防止旧界面反复刷新泄漏） */
    private val uiTickHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val uiTick = object : Runnable {
        override fun run() {
            if (busy) {
                adapter.notifyDataSetChanged()
                // 只在用户本来就在底部时才跟随滚动，否则会把上翻看历史的人拽回去（体检 P2-7）
                val lm = rv.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager
                val last = lm?.findLastCompletelyVisibleItemPosition() ?: -1
                if (last < 0 || last >= adapter.items.size - 2) {
                    rv.scrollToPosition(adapter.items.size - 1)
                }
            }
            uiTickHandler.postDelayed(this, 2000)
        }
    }

    /** 恢复普通模式：把暂停时保存的 plan/history 还原，从当前步骤继续 */
    private fun resumeNormalFrom(saved: PausedTask) {
        while (history.size() > 0) history.remove(0)
        for (e in saved.historySnapshot) history.add(e.deepCopy())
        val plan = saved.plan
        val currentStep = saved.currentStep
        val launchedTarget = saved.launchedTarget

        // 恢复时补齐经验与原生工具标记：原来只传 2 参，重建的 system prompt 丢了这两段（体检 P2-10）
        val relatedExp = AgentExperienceStore.relevant(this, saved.userText)
        val hasNativeToolMatch = TaskRouter.matchesNativeTool(saved.userText)
        if (history.size() == 0 || history.get(0).asJsonObject.get("role")?.asString != "system") {
            val fresh = JsonArray()
            fresh.add(AgentEngine.systemPrompt(plan, currentStep, relatedExp, hasNativeToolMatch).let { AgentEngine.msg("system", it) })
            for (e in history) fresh.add(e)
            while (history.size() > 0) history.remove(0)
            for (e in fresh) history.add(e)
        } else {
            history.get(0).asJsonObject.addProperty("content", AgentEngine.systemPrompt(plan, currentStep, relatedExp, hasNativeToolMatch))
        }
        history.add(AgentEngine.msg("user", "（用户说继续，请从上次暂停的地方接着做）"))

        var round = 0
        val MAX_ROUNDS = 40
        while (true) {
            round++
            if (cancelRequested) break
            if (round > MAX_ROUNDS) break
            compactHistory()
            Thread.sleep(200)
            val res = try {
                LlmClient.chatStream(
                    Prefs.baseUrl(this), Prefs.apiKey(this), Prefs.model(this),
                    sanitizeHistory(), AgentEngine.toolsJson(), Prefs.fastMode(this)
                )
            } catch (e: Exception) {
                LlmClient.chat(
                    Prefs.baseUrl(this), Prefs.apiKey(this), Prefs.model(this),
                    sanitizeHistory(), AgentEngine.toolsJson(), Prefs.fastMode(this)
                )
            }
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
                val reply = stripThinkTags(res.content).ifBlank { "任务已完成" }
                runOnUiThread {
                    adapter.add(ChatAdapter.Msg("assistant", reply))
                    rv.scrollToPosition(adapter.items.size - 1)
                }
                break
            }
            for (call in res.toolCalls) {
                if (cancelRequested) break
                val result = AgentEngine.execute(this@MainActivity, call.name, call.arguments, launchedTarget?.second)
                runOnUiThread {
                    adapter.add(ChatAdapter.Msg("system", result.take(300)))
                    rv.scrollToPosition(adapter.items.size - 1)
                }
                history.add(AgentEngine.toolMsg(call.id, result.take(4500)))
            }
            if (cancelRequested) break
        }
        if (cancelRequested) {
            pausedTask = saved.copy(historySnapshot = history.deepCopy())
            runOnUiThread {
                adapter.add(ChatAdapter.Msg("system", "⏸️ 又暂停了。说「继续」接着做"))
                rv.scrollToPosition(adapter.items.size - 1)
            }
        } else if (round > MAX_ROUNDS) {
            // 达到轮次上限退出时必须给用户一个明确交代，否则界面看起来像还在执行
            runOnUiThread {
                adapter.add(ChatAdapter.Msg("system", "⚠️ 连续执行${MAX_ROUNDS}轮未完成，先停在这里。可以再说「继续」接着做"))
                rv.scrollToPosition(adapter.items.size - 1)
            }
        }
    }

    /** 恢复 AutoGLM：把暂停时的 ctx 还原，从刚才的轮次继续 */
    private fun resumeAutoGLMFrom(saved: PausedTask) {
        val ctx = saved.aglmCtx!!.deepCopy()
        ctx.add(JsonObject().apply {
            addProperty("role", "user")
            addProperty("content", "（用户说继续，请从上次暂停的地方接着做）")
        })
        // 恢复任务重建完成判定契约（体检 P2-10）：开放式任务恢复后不再被旧 40 轮上限误杀，
        // 且「打开App」类恢复同样能被前台硬判据直接收敛
        val completion = TaskCompletionVerifier.contractFor(
            saved.userText, DeviceTools.guessApp(this, saved.userText), Prefs.openEndedSessionMinutes(this)
        )
        val roundCap = if (completion.taskClass == TaskCompletionVerifier.TaskClass.OPEN_ENDED) 800 else 80
        var sessionStartMs = android.os.SystemClock.elapsedRealtime()
        val boundedStartMs = sessionStartMs
        var finalReply: String? = null
        var round = 0
        while (finalReply == null && !cancelRequested) {
            round++
            if (round >= roundCap) { finalReply = "恢复后达到轮次上限，自动停止"; break }
            val nowMs = android.os.SystemClock.elapsedRealtime()
            val foregroundCheck = completion.successCheck as? TaskCompletionVerifier.SuccessCheck.ForegroundApp
            if (foregroundCheck != null &&
                TaskCompletionVerifier.isForegroundStable(
                    AssistantAccessibilityService.instance?.currentPackage(), foregroundCheck.pkg
                ) {
                    Thread.sleep(600)
                    AssistantAccessibilityService.instance?.currentPackage()
                }
            ) {
                finalReply = "✅ 已完成：${foregroundCheck.appName} 已打开（已验证应用在前台）"
                break
            }
            if (completion.taskClass == TaskCompletionVerifier.TaskClass.OPEN_ENDED) {
                if (nowMs - sessionStartMs >= completion.sessionCapMs) {
                    val sessionMsg = "已连续挂机${TaskCompletionVerifier.formatDuration(nowMs - sessionStartMs)}、执行${round}轮。要继续挂机还是现在收工？"
                    runOnUiThread {
                        adapter.add(ChatAdapter.Msg("system", "⏰ $sessionMsg"))
                        rv.scrollToPosition(adapter.items.size - 1)
                    }
                    val ask = AssistantAccessibilityService.instance
                        ?.showSessionContinueAsk(sessionMsg, completion.askGraceMs)
                    if (ask == SessionAsk.END) {
                        finalReply = "⏹ 已按你的选择收工：本次共挂机${TaskCompletionVerifier.formatDuration(nowMs - sessionStartMs)}、执行${round}轮。"
                        break
                    }
                    sessionStartMs = android.os.SystemClock.elapsedRealtime()
                    runOnUiThread {
                        adapter.add(ChatAdapter.Msg("system", "▶️ 继续挂机，${TaskCompletionVerifier.formatDuration(completion.sessionCapMs)}后再来问你。"))
                        rv.scrollToPosition(adapter.items.size - 1)
                    }
                }
            } else if (nowMs - boundedStartMs >= completion.boundedTimeoutMs) {
                finalReply = "⚠️ 任务已运行${TaskCompletionVerifier.formatDuration(nowMs - boundedStartMs)}仍未收敛，超时保护自动停止。可以再说「继续」重新执行。"
                break
            }
            while (ctx.size() > 10 && ctx.size() >= 4) { ctx.remove(2); ctx.remove(2) }
            val shot = AgentEngine.screenB64(this, AgentEngine.VISION_MAX_W)
            if (shot == null) { Thread.sleep(2000); continue }
            val messages = JsonArray()
            for (e in ctx) messages.add(e.deepCopy())
            messages.add(JsonObject().apply {
                addProperty("role", "user")
                add("content", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("type", "image_url")
                        add("image_url", JsonObject().apply {
                            addProperty("url", "data:image/jpeg;base64,${shot.get("b64").asString}")
                        })
                    })
                    add(JsonObject().apply {
                        addProperty("type", "text")
                        addProperty("text", "当前屏幕截图。请继续完成任务：「${saved.userText.take(80)}」")
                    })
                })
            })
            val reply = try {
                LlmClient.chatAutoGLM(Prefs.apiKey(this), messages)
            } catch (e: Exception) {
                finalReply = "恢复任务时出错：${e.message}"; break
            }
            ctx.add(JsonObject().apply { addProperty("role", "assistant"); addProperty("content", reply.content) })
            // 恢复链必须走主循环同一条执行通道 execAutoGLM（含直播间/下载墙/弹窗/消费守卫、批量中止与 settle）。
            // 旧 execSingleAglmAction 按旧协议解析（startsWith("Tap") + point=），对 parseAutoGlmActions 返回的
            // do(...) 全格式（element= 坐标）永远不命中，动作被静默丢弃，「继续」后画面永不变、空转到轮次上限
            // 白烧 token（体检第六轮 P1）。
            // 注：PausedTask 不带激励倒计时快照，恢复时按无倒计时处理——暂停通常以分钟计，原倒计时大概率已过期
            val svcResume = AssistantAccessibilityService.instance
            val (rw, rh) = svcResume?.realScreenSize() ?: Pair(1080, 1920)
            val guardDump = svcResume?.dumpScreenForAutoGLM()
            val res = AgentEngine.execAutoGLM(
                this, reply.content, rw, rh, saved.userText, guardDump, saved.aglmGuessedApp,
                rewardCountdownSeconds = null
            )
            if (res.finished) {
                finalReply = res.info.ifBlank { "任务完成" }
                break
            }
            if (res.message == "__PARSE_FAIL__") {
                finalReply = reply.content.ifBlank { "任务完成" }
                break
            }
            if (res.message == "__PRIVACY_BLOCK__") {
                // 恢复链与主循环同一条安全规则：隐私同意不代点，也不把哨兵当作普通执行结果
                // 回给模型继续重试；停下来交给用户手动确认。
                runOnUiThread {
                    adapter.add(ChatAdapter.Msg("system", res.info))
                    rv.scrollToPosition(adapter.items.size - 1)
                }
                finalReply = "🛑 检测到隐私条款/用户协议同意按钮。出于安全考虑，系统不会自动点击此类按钮，需要您手动确认后重试。"
                break
            }
            // 执行结果回填给模型（与主循环 Bug#1a 对齐）；拦截/代退哨兵的 info 已如实说明被拦或已代退
            ctx.add(JsonObject().apply {
                addProperty("role", "user")
                addProperty("content", "执行结果：${res.info.take(200)}")
            })
        }
        if (cancelRequested) {
            pausedTask = saved.copy(aglmCtx = ctx)
            runOnUiThread {
                adapter.add(ChatAdapter.Msg("system", "⏸️ 又暂停了。说「继续」接着做"))
                rv.scrollToPosition(adapter.items.size - 1)
            }
        } else if (finalReply != null) {
            runOnUiThread {
                adapter.add(ChatAdapter.Msg("assistant", finalReply!!))
                rv.scrollToPosition(adapter.items.size - 1)
            }
        }
    }

    /** AutoGLM-Phone 专用脑模式：截图→模型出动作→本机执行（协议与 Open-AutoGLM 一致） */
    private fun runAutoGLMLoop(userText: String, contextUserText: String) {
        try {
            // Bug#5：路由只用当前任务句，不带上一次会话的历史，防止跨会话污染
            val guessedApp = AgentEngine.extractHttpUrl(userText)
                ?.let { resolveBrowserForUrl(it) }
                ?: (DeviceTools.guessApp(this, userText) ?: inferTargetApp(userText, ""))
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
            var navMismatchCount = 0
            var lastScreenHash = ""
            var lastDumpSig = ""      // 上一轮无障碍页面文本指纹（动画页骗得过截图哈希，骗不过这个）
            var sameTapCount = 0
            var lastTapBucket = ""
            var lastTapLabel = ""
            var lastSwipeSig = ""
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
            val jumpTrapLabels = LinkedHashSet<String>() // 本任务内实测会跳出目标App的入口标签（规范化后）
            val blockedTapBuckets = HashSet<String>()    // 本任务内实测会跳出目标App的点击坐标桶（文字守卫的兜底）
            var targetAppPackage: String? = null
            targetAppPackage = guessedApp?.second
            // 冷启动目标App：上一轮任务可能把它留在任意深层页面（实测：死在拍摄页污染下一轮开局）
            // 「继续/接着」类恢复指令除外
            if (targetAppPackage != null && AgentEngine.shouldColdStart(userText)) {
                try {
                    val coldMsg = DeviceTools.openAppFresh(this, targetAppPackage!!)
                    android.util.Log.i("AIButler", "AGLM cold-start: $coldMsg")
                } catch (e: Exception) {
                    android.util.Log.w("AIButler", "AGLM cold-start failed", e)
                }
            }
            // Bug#2：guessedApp=null 时模型没有目标，此时进入无引导模式，任何非系统/非本App的包名都视为失控
            val unguidedMode = guessedApp == null
            var shortcutOnlyRounds = 0      // 连续由捷径直行（模型未参与）的轮数
            var shortcutSkipRounds = 0      // 熔断后暂停捷径的剩余轮数
            var shortcutBreakerFired = 0    // 捷径空转熔断累计触发次数
            var loadingWaitCount = 0
            var finishVerifyCount = 0
            val taskGoal = TaskGoalVerifier.parse(userText)
            val pickerTracker = TaskGoalVerifier.PickerTracker()
            // ===== 完成判定契约（TaskCompletionVerifier）：能机械验证的用代码收敛，开放式任务按会话询问续期 =====
            val completion = TaskCompletionVerifier.contractFor(userText, guessedApp, Prefs.openEndedSessionMinutes(this))
            var sessionStartMs = android.os.SystemClock.elapsedRealtime()
            val boundedStartMs = sessionStartMs
            val durationTargetSeconds = taskGoal.duration?.seconds
            var durationWatchStartMs: Long? = null
            var launchFinishRejects = 0
            var verifiedByCheck = false
            val searchTarget = AgentEngine.namedActionTarget(userText)
            var searchTargetVisible = false
            var searchSubmitWaitUntilMs = 0L
            var searchSubmitted = false
            val messageSendGoal = AgentEngine.parseMessageSendGoal(userText)
            var pendingMessageBody: String? = null
            var messageSendConfirmed = false
            var messageSendFinishRejects = 0
            android.util.Log.i("AIButler", "AGLM completion: class=${completion.taskClass} check=${completion.successCheck}")
            var prevTapCoords: Pair<Int, Int>? = null
            var waitStallCount = 0
            var lastWaitDumpSig = ""
            var rewardWaitRounds = 0
            var lastRewardCountdownSeconds: Int? = null
            var rewardCountdownStallRounds = 0
            var blockedRewardFinishRounds = 0
            var downloadWallExitRounds = 0
            var rewardActivityExitRounds = 0
            var rewardLayerExitRounds = 0
            var adRewardCompletions = 0              // 机械计数：已退出几次奖励结算层（数量型广告任务的 finish 证据）
            var lastRewardEntryRound: Int? = null
            var rewardLiveEmbedRounds = 0
            var lastLiveExitRound: Int? = null
            var liveResumeConflictRounds = 0
            var liveResumeRecoveries = 0
            var lastRoundHadRewardCompletion = false   // 完成证据新鲜度：只在证据新出现时才当作结算层（体检追加轮 P1-8）
            var adLoopFinishRejects = 0                // 循环型广告任务 finish 拒绝次数：防无终止信号时无限烧轮次（体检 P2-2）
            var rewardCenterExitRounds = 0
            var adTaskFeedRounds = 0
            var retryNote = ""
            var lastActionFeedback = ""    // 上一轮「动作→页面变化」反馈，注入下一轮 screenInfo 后清空
            var jumpBlockStreak = 0        // 连续跳转/下载类拦截次数：模型反复撞同一拦截时升级处理
            var progressHoldRounds = 0
            var visionProgressWaitRounds = 0   // 「屏幕进度未完」强制等待轮数：无上限会反复把领取 Tap 改写成 Wait（体检第六轮 P3-2）
            val visitedPackages = LinkedHashSet<String>()
            val pageTrace = LinkedHashSet<String>()
            val actionTrace = LinkedHashSet<String>()
            // ===== 路径记忆：录制 + 回放 =====
            val recordingSteps = ArrayList<PageStep>()
            // 有精确约束或开放目标的任务不能“执行过路径”就宣称完成；
            // 路径记忆只服务确定终点的简单任务，复杂目标必须重新走机械/AI裁决。
            val replayAllowed = completion.taskClass == TaskCompletionVerifier.TaskClass.BOUNDED &&
                !taskGoal.hasConstraint
            // 启动前尝试回放已验证路径；失败后自动降级到AI探索。
            run {
                val matchedPath = if (replayAllowed) {
                    PathMemoryStore.findMatch(this, userText, guessedApp?.second)
                } else {
                    null
                }
                if (matchedPath != null) {
                    runOnUiThread {
                        adapter.add(ChatAdapter.Msg("system", "⚡ 检测到缓存路径（${matchedPath.targetAppName}，${matchedPath.steps.size}步），正在快速回放…"))
                        rv.scrollToPosition(adapter.items.size - 1)
                    }
                    val replayResult = PathMemoryStore.tryReplay(this, matchedPath) { cancelRequested }
                    if (replayResult.success) {
                        // 回放本身就是逐步核对过的机械结果；这里不再让用户补一道人工判定。
                        val replayConfirmed = replayResult.success
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
                // 开放式任务(刷视频等)每轮只要几秒，80轮≈10分钟——轮数上限不该终止它，
                // 终止权交给"挂机到点询问续期"；只保留极端异常保护(800轮)
                val roundCap = if (completion.taskClass == TaskCompletionVerifier.TaskClass.OPEN_ENDED) 800 else 80
                if (round >= roundCap) {
                    finalReply = "已达到安全轮次上限（${roundCap}轮），自动停止。未能稳定确认目标已完成，请查看失败经验后重试。"
                    android.util.Log.i("AIButler", "AGLM max rounds reached ($roundCap)")
                    break
                }
                // ===== 完成判定：先问代码，再让模型干活（打开App类：目标在前台=完成，不再等模型） =====
                val nowMs = android.os.SystemClock.elapsedRealtime()
                val foregroundCheck = completion.successCheck as? TaskCompletionVerifier.SuccessCheck.ForegroundApp
                if (foregroundCheck != null &&
                    TaskCompletionVerifier.isForegroundStable(
                        AssistantAccessibilityService.instance?.currentPackage(), foregroundCheck.pkg
                    ) {
                        Thread.sleep(600)
                        AssistantAccessibilityService.instance?.currentPackage()
                    }
                ) {
                    verifiedByCheck = true
                    visitedPackages.add(foregroundCheck.pkg)
                    finalReply = "✅ 已完成：${foregroundCheck.appName} 已打开（已验证应用在前台）"
                    android.util.Log.i("AIButler", "AGLM hard-check complete: ${foregroundCheck.pkg} round=$round")
                    break
                }
                if (messageSendGoal != null && messageSendConfirmed) {
                    verifiedByCheck = true
                    finalReply = "✅ 已完成：消息「${messageSendGoal.body}」已点击发送"
                    android.util.Log.i("AIButler", "AGLM message send complete: round=$round")
                    break
                }
                if (completion.taskClass == TaskCompletionVerifier.TaskClass.OPEN_ENDED) {
                    if (nowMs - sessionStartMs >= completion.sessionCapMs) {
                        val sessionMsg = "已连续挂机${TaskCompletionVerifier.formatDuration(nowMs - sessionStartMs)}、执行${round}轮。要继续挂机还是现在收工？"
                        runOnUiThread {
                            adapter.add(ChatAdapter.Msg("system", "⏰ $sessionMsg"))
                            rv.scrollToPosition(adapter.items.size - 1)
                        }
                        val ask = AssistantAccessibilityService.instance
                            ?.showSessionContinueAsk(sessionMsg, completion.askGraceMs)
                        if (ask == SessionAsk.END) {
                            finalReply = "⏹ 已按你的选择收工：本次共挂机${TaskCompletionVerifier.formatDuration(nowMs - sessionStartMs)}、执行${round}轮。"
                            break
                        }
                        // 点了继续 / 宽限期内没点(用户可能不在手机旁) → 按约定默认继续，重开一轮
                        sessionStartMs = android.os.SystemClock.elapsedRealtime()
                        runOnUiThread {
                            adapter.add(ChatAdapter.Msg("system", "▶️ 继续挂机，${TaskCompletionVerifier.formatDuration(completion.sessionCapMs)}后再来问你。"))
                            rv.scrollToPosition(adapter.items.size - 1)
                        }
                    }
                } else if (nowMs - boundedStartMs >= completion.boundedTimeoutMs) {
                    finalReply = "⚠️ 任务已运行${TaskCompletionVerifier.formatDuration(nowMs - boundedStartMs)}仍未收敛，超时保护自动停止。可以再说「继续」重新执行。"
                    android.util.Log.i("AIButler", "AGLM bounded timeout fuse fired round=$round")
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
                            "第${round}轮 · 原始截图（发给AI的版本另叠加读屏编号角标）\n原始：${realW}×${realH}，识别用：${AgentEngine.VISION_MAX_W}宽",
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
                searchTargetVisible = searchTarget != null && screenDump.contains(searchTarget)
                val passiveVideoTask = AgentEngine.isPassiveVideoTask(userText)
                if (passiveVideoTask && durationTargetSeconds != null &&
                    durationWatchStartMs == null && guessedApp?.second == appPkg
                ) {
                    durationWatchStartMs = android.os.SystemClock.elapsedRealtime()
                }
                if (durationTargetSeconds != null && durationWatchStartMs != null) {
                    val watchElapsedMs = android.os.SystemClock.elapsedRealtime() - durationWatchStartMs
                    if (watchElapsedMs >= durationTargetSeconds * 1000L) {
                        android.util.Log.i(
                            "AIButler",
                            "AGLM duration target reached: ${watchElapsedMs / 1000}s/$durationTargetSeconds"
                        )
                        verifiedByCheck = true
                        finalReply = "✅ 已完成：已按系统计时刷满${taskGoal.duration?.display}。"
                        break
                    }
                }
                val durationNote = if (passiveVideoTask && durationTargetSeconds != null) {
                    val elapsedMs = durationWatchStartMs?.let { android.os.SystemClock.elapsedRealtime() - it } ?: 0L
                    val leftSeconds = (durationTargetSeconds - (elapsedMs / 1000L)).coerceAtLeast(0L)
                    if (durationWatchStartMs == null) {
                        "\n⏱️ 系统计时：进入目标应用后才开始计算${taskGoal.duration?.display}；完成与否以系统计时为准。"
                    } else {
                        "\n⏱️ 系统计时：已刷${TaskCompletionVerifier.formatDuration(elapsedMs)}，还差${TaskCompletionVerifier.formatDuration(leftSeconds * 1000L)}。" +
                            "不要凭感觉 finish；计时满后系统会自动收口。"
                    }
                } else ""
                // Set-of-Marks：给可点击/输入框行加编号，供截图角标与模型对照。
                // 屏幕信息展示用带编号版；执行链路（纠偏/校验/守卫）仍用原始 screenDump，互不影响。
                val (markedDump, screenMarks) = AgentEngine.annotateAutoGlmDump(screenDump)
                // Bug#6：系统隐私遮挡（ColorOS投屏抹黑）时视觉脑完全不可用，立即终止
                if (PageGuard.isPrivacyOverlay(screenDump)) {
                    android.util.Log.i("AIButler", "AGLM privacy overlay detected, stopping")
                    finalReply = "该应用受系统隐私保护，视觉脑无法读取屏幕内容。请手动操作该应用，或在系统设置中关闭隐私遮挡。"
                    break
                }
                val compactGuardDump = screenDump.replace(Regex("\\s+"), "")
                val quantifiedAdTarget = if (TaskCompletionVerifier.hasQuantityConstraint(userText) &&
                    TaskCompletionVerifier.isExplicitAdRewardTask(userText)
                ) {
                    TaskGoalVerifier.actionQuantityTarget(userText, Regex("广告|激励|视频"))
                } else null
                if (quantifiedAdTarget != null && quantifiedAdTarget > 0 &&
                    adRewardCompletions >= quantifiedAdTarget
                ) {
                    android.util.Log.i(
                        "AIButler",
                        "AGLM quantified-ad target reached: $adRewardCompletions/$quantifiedAdTarget"
                    )
                    verifiedByCheck = true
                    finalReply = "✅ 已完成：系统确认看完了 ${quantifiedAdTarget} 个广告的领取结算。"
                    break
                }
                // 登录墙判断必须保留换行/分段边界；compactGuardDump 已把整页消息压成一行。
                if (PageGuard.isManualAuthPage(screenDump)) {
                    android.util.Log.i("AIButler", "AGLM manual auth page detected, stopping")
                    runOnUiThread {
                        adapter.add(ChatAdapter.Msg(
                            "system",
                            "🛑 当前页面需要登录或输入账号凭据。出于安全考虑，已停止并交还给您手动处理。"
                        ))
                        rv.scrollToPosition(adapter.items.size - 1)
                    }
                    finalReply = "任务已停止：需要您先手动登录或完成账号验证；完成后可以再说「继续」。"
                    break
                }
                // 倒计时读数提前到页面级守卫之前：激励广告倒计时活跃时，直播间/下载墙/活动页守卫都要让路，
                // 否则广告素材自带的直播引流/下载条/抽奖文案会触发强退、作废已看时长（体检 P2-4/P1-1）
                if (!passiveVideoTask && PageGuard.isRewardTask(userText) &&
                    PageGuard.hasRewardAdEntry(compactGuardDump)
                ) {
                    lastRewardEntryRound = round
                    rewardLiveEmbedRounds = 0
                }
                val rewardCountdownSeconds = if (passiveVideoTask) {
                    null
                } else {
                    AgentEngine.rewardCountdownSeconds(compactGuardDump)
                }
                val rewardAdLiveEmbed = !passiveVideoTask &&
                    PageGuard.isRecentRewardEntry(lastRewardEntryRound, round) &&
                    !PageGuard.hasExplicitLiveExit(compactGuardDump) &&
                    rewardLiveEmbedRounds < PageGuard.REWARD_LIVE_EMBED_MAX_ROUNDS
                if (rewardCountdownSeconds == null && rewardAdLiveEmbed &&
                    !PageGuard.hasRewardCompletion(compactGuardDump) &&
                    PageGuard.isUnintendedLiveRoom(userText, compactGuardDump)
                ) {
                    rewardLiveEmbedRounds++
                    // 同一支广告素材可能在第5轮后才展开直播画面；播放等待期间刷新入口窗口，
                    // 避免窗口刚好过期后被直播间守卫误退。总轮数上限仍防止无限等待。
                    lastRewardEntryRound = round
                    android.util.Log.i(
                        "AIButler",
                        "AGLM reward ad contains live content, waiting #$rewardLiveEmbedRounds"
                    )
                    // 激励广告常用 30-60 秒直播素材；这里给到 12 轮，避免刚好在领奖前熔断。
                    runOnUiThread {
                        adapter.add(ChatAdapter.Msg("system", "⏳ 激励广告素材包含直播内容，先等待播放完成，不强退。"))
                        rv.scrollToPosition(adapter.items.size - 1)
                    }
                    Thread.sleep(5000)
                    continue
                }
                if (rewardCountdownSeconds == null && PageGuard.isUnintendedLiveRoom(userText, compactGuardDump)) {
                    val exitLabel = PageGuard.liveRoomExitLabel(screenDump)
                    val clickResult = exitLabel?.let {
                        AssistantAccessibilityService.instance?.clickText(it)
                    }
                    val exited = when {
                        clickResult?.startsWith("已") == true -> true
                        else -> AssistantAccessibilityService.instance?.pressBack() == true
                    }
                    android.util.Log.i("AIButler", "AGLM force exit live room, label=$exitLabel, exited=$exited")
                    lastLiveExitRound = round
                    runOnUiThread {
                        adapter.add(ChatAdapter.Msg("system", "🎯 识别到误入直播间，已优先点击明确的离开控件/返回。"))
                        rv.scrollToPosition(adapter.items.size - 1)
                    }
                    ctx.add(JsonObject().apply {
                        addProperty(
                            "role", "user",
                        )
                        addProperty(
                            "content",
                            "🛑 系统提示：刚才误入了直播间。这不是金币广告任务页；已优先点击“退出直播间/离开直播间”或系统返回。" +
                                "下一轮重新读屏，不要点击直播间卡片、商品卡、主播头像、礼物或关注按钮。"
                        )
                    })
                    Thread.sleep(1500)
                    continue
                }
                if (PageGuard.isForbiddenPayment(userText, screenDump)) {
                    AssistantAccessibilityService.instance?.pressBack()
                    android.util.Log.i("AIButler", "AGLM force back from payment/order page")
                    runOnUiThread {
                        adapter.add(ChatAdapter.Msg("system", "🛑 已离开误入的支付/确认页，未点击任何付款控件。"))
                        rv.scrollToPosition(adapter.items.size - 1)
                    }
                    ctx.add(JsonObject().apply {
                        addProperty("role", "user")
                        addProperty(
                            "content",
                            "🛑 系统提示：刚才误入了商品确认/支付/充值页，系统只按了返回键，绝不能点支付、充值或订单按钮。" +
                                "下一轮回到目标应用后继续找与「$userText」直接相关的免费入口。"
                        )
                    })
                    Thread.sleep(1500)
                    continue
                }
                // 页面文本指纹：本轮和上轮完全一致 = 冻结页（假倒计时/广告墙，等待无效）
                val countdownProgressed = AgentEngine.rewardCountdownProgressed(
                    lastRewardCountdownSeconds,
                    rewardCountdownSeconds
                )
                if (countdownProgressed) {
                    rewardCountdownStallRounds = 0
                } else if (rewardCountdownSeconds != null) {
                    rewardCountdownStallRounds++
                } else {
                    rewardCountdownStallRounds = 0
                }
                lastRewardCountdownSeconds = rewardCountdownSeconds
                val dumpSigNow = AgentEngine.fingerprint(screenDump).hashCode().toString()
                val prevRoundDumpSig = lastDumpSig
                val frozenPage = dumpSigNow == lastDumpSig && !countdownProgressed
                lastDumpSig = dumpSigNow
                val commerceTaskPage = Regex(
                    "加入购物车|购买数量|立即抢购|立即购买|去支付|提交订单|确认订单",
                    RegexOption.IGNORE_CASE
                ).containsMatchIn(compactGuardDump)
                // 商品页守卫放行明确交易任务和搜索/比较类浏览任务；后者仍由敏感按钮红线拦交易点击。
                val pageGuardAllowed =
                    PageGuard.isCommerceTask(userText) || PageGuard.isProductResearchTask(userText)
                if (commerceTaskPage && !pageGuardAllowed) {
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
                if (AgentEngine.isPassiveVideoTask(userText) && PageGuard.isRewardTaskCenter(compactGuardDump)) {
                    if (lastCardLabel.isNotBlank() && appPkg == lastCardPkg && round - lastCardRound <= 6) {
                        TaskCardMemory.mark(this, appPkg, lastCardLabel, good = false)
                    }
                    lastCardLabel = ""
                    rewardCenterExitRounds++
                    AssistantAccessibilityService.instance?.pressBack()
                    android.util.Log.i(
                        "AIButler",
                        "AGLM passive-video task left reward center #$rewardCenterExitRounds, pkg=$appPkg"
                    )
                    runOnUiThread {
                        adapter.add(ChatAdapter.Msg("system", "🎯 普通刷视频任务误入赚钱页，已返回视频流。"))
                        rv.scrollToPosition(adapter.items.size - 1)
                    }
                    if (rewardCenterExitRounds >= 3) {
                        finalReply = "普通刷视频任务连续识别到任务中心页，已自动停止。请手动回到视频流后重试。"
                        break
                    }
                    ctx.add(JsonObject().apply {
                        addProperty("role", "user")
                        addProperty(
                            "content",
                            "🛑 系统提示：这是普通刷视频任务，不是明确的赚钱任务。刚才误入任务中心/赚钱页，已按返回。" +
                                "下一轮回到首页或视频流后必须 Swipe 上滑一段视频，再 Wait 观看；不要点击金币、红包、宝箱、任务卡或赚钱入口。"
                        )
                    })
                    Thread.sleep(1500)
                    continue
                }
                // 拍摄/创作页对签到任务是高危路径：一旦进入倒计时或编辑页，很容易连续操作到发布。
                // 判定逻辑收拢到 PageGuard.isUnintendedCreationPage，供主循环与 AgentEngine 点击链共用
                if (PageGuard.isUnintendedCreationPage(userText, screenDump)) {
                    val discardResult = listOf("不保存返回", "不保存", "丢弃")
                        .firstNotNullOfOrNull { label ->
                            AssistantAccessibilityService.instance?.clickText(label)
                                ?.takeIf { it.contains("已点击") }
                        }
                    if (discardResult != null) {
                        android.util.Log.i("AIButler", "AGLM left creation page via discard exit")
                    } else {
                        AssistantAccessibilityService.instance?.pressBack()
                        android.util.Log.i("AIButler", "AGLM force back from creation page (PageGuard.isUnintendedCreationPage)")
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
                // 明确「看广告」任务不能在普通视频流里滑动。系统按语义代点赚钱/看广告入口，
                // 避免视觉模型把「刷金币」误解成开放式刷视频。
                val explicitAdTask = !passiveVideoTask &&
                    TaskCompletionVerifier.isExplicitAdRewardTask(userText)
                if (explicitAdTask && PageGuard.isVideoFeed(compactGuardDump)) {
                    adTaskFeedRounds++
                    if (adTaskFeedRounds >= 2) {
                        val entryLabel = PageGuard.rewardEntryLabel(screenDump)
                        val entryResult = entryLabel?.let {
                            AssistantAccessibilityService.instance?.clickText(it)
                        }
                        if (entryResult?.startsWith("已") == true) {
                            android.util.Log.i("AIButler", "AGLM explicit ad task routed via $entryLabel")
                            runOnUiThread {
                                adapter.add(ChatAdapter.Msg("system", "🧭 看广告任务已从视频流切到「$entryLabel」任务页。"))
                                rv.scrollToPosition(adapter.items.size - 1)
                            }
                            adTaskFeedRounds = 0
                            Thread.sleep(1500)
                            continue
                        }
                        ctx.add(JsonObject().apply {
                            addProperty("role", "user")
                            addProperty(
                                "content",
                                "🛑 系统提示：用户要求看广告赚钱，当前是普通视频流，继续滑动是错误路线。" +
                                    "必须点击「赚钱/去赚钱/看广告」入口进入任务页；找不到就点右上角红包挂件或[我/我的]页里的金币任务入口，绝不能 Swipe。"
                            )
                        })
                    }
                } else {
                    adTaskFeedRounds = 0
                }
                // 激励广告被暂停时，「继续观看」是恢复进度的明确入口；必须由系统先代点，
                // 不能把无文字/低对比弹窗交给视觉模型去猜坐标。
                val rewardContinueLabel = PageGuard.rewardContinueLabel(screenDump, rewardCountdownSeconds)
                if (!passiveVideoTask && rewardContinueLabel != null && PageGuard.isRewardTask(userText)) {
                    if (PageGuard.isRecentLiveExit(lastLiveExitRound, round)) {
                        liveResumeConflictRounds++
                        android.util.Log.i(
                            "AIButler",
                            "AGLM live/reward guard conflict #$liveResumeConflictRounds, suppress resume"
                        )
                        if (liveResumeConflictRounds == 1 && liveResumeRecoveries < 1) {
                            liveResumeRecoveries++
                            val resumeResult =
                                AssistantAccessibilityService.instance?.clickText(rewardContinueLabel)
                            if (resumeResult?.startsWith("已") == true) {
                                lastLiveExitRound = null
                                liveResumeConflictRounds = 0
                                rewardLiveEmbedRounds = 0
                                android.util.Log.i(
                                    "AIButler",
                                    "AGLM live/reward one-shot resume via $rewardContinueLabel"
                                )
                                runOnUiThread {
                                    adapter.add(
                                        ChatAdapter.Msg(
                                            "system",
                                            "▶️ 检测到刚误退了仍在倒计时的激励广告，已恢复一次观看进度。"
                                        )
                                    )
                                    rv.scrollToPosition(adapter.items.size - 1)
                                }
                                Thread.sleep(1000)
                                continue
                            }
                        }
                        AssistantAccessibilityService.instance?.pressBack()
                        if (liveResumeConflictRounds >= 2) {
                            finalReply = "奖励广告里反复夹带直播间内容，系统已停止以避免误操作；请手动回到任务页后重试。"
                            break
                        }
                        ctx.add(JsonObject().apply {
                            addProperty("role", "user")
                            addProperty(
                                "content",
                                "🛑 系统提示：刚离开直播间又出现“继续观看”，这是奖励广告与直播间守卫冲突。已按返回离开冲突层；" +
                                    "下一轮回到任务页后继续找与目标直接相关的免费广告入口，不要再立刻恢复刚才的播放。"
                            )
                        })
                        Thread.sleep(1200)
                        continue
                    }
                    val clickResult = AssistantAccessibilityService.instance?.clickText(rewardContinueLabel)
                    if (clickResult?.startsWith("已") == true) {
                        android.util.Log.i("AIButler", "AGLM resumed rewarded ad via $rewardContinueLabel")
                        runOnUiThread {
                            adapter.add(ChatAdapter.Msg("system", "▶️ 激励广告已暂停，先点击「$rewardContinueLabel」恢复进度。"))
                            rv.scrollToPosition(adapter.items.size - 1)
                        }
                        Thread.sleep(1000)
                        continue
                    }
                    ctx.add(JsonObject().apply {
                        addProperty("role", "user")
                        addProperty(
                            "content",
                            "🛑 系统提示：激励广告倒计时还没结束，当前弹窗里有「$rewardContinueLabel」。这是恢复计时的入口，必须点它；" +
                                "绝不能点下载、×或坚持退出。"
                        )
                    })
                }
                // 🎁 奖励完成层不是任务终点：完成证据**本轮新出现**时立即退出广告/结算层，再回任务页继续循环。
                // 这里不信任模型对底部 CTA 的理解；系统只点击完成提示同一语境里的关闭控件，找不到才 Back。
                // 「今日已签到/已成功领取」也常驻在普通状态页上——只在证据新出现时才当作结算层，
                // 常驻状态页放行给模型走正常 finish，否则会被连点4次关闭后以失败文案告终（体检追加轮 P1-8）。
                val completionEvidenceNow = !passiveVideoTask && PageGuard.hasRewardCompletion(screenDump)
                val rewardCompletion = if (completionEvidenceNow && rewardCountdownSeconds == null) {
                    PageGuard.rewardCompletionExit(screenDump)
                } else null
                val completionEvidenceFresh = completionEvidenceNow && !lastRoundHadRewardCompletion
                lastRoundHadRewardCompletion = completionEvidenceNow
                val rewardAdLayer = rewardCompletion != null && Regex(
                    "广告|额外得|点击并浏览|继续观看|激励|看完.*领|浏览.*秒"
                ).containsMatchIn(compactGuardDump)
                if (PageGuard.isRewardTask(userText) &&
                    completionEvidenceFresh &&
                    (rewardCompletion?.closeLabel != null || rewardAdLayer)
                ) {
                    val structuralExit = AssistantAccessibilityService.instance?.clickRewardCompletionExit()
                    val usedStructuralExit = structuralExit?.startsWith("已") == true
                    val explicitExit = if (!usedStructuralExit) {
                        val rc = rewardCompletion
                        when {
                            rc == null || rc.closeLabel == null -> null
                            // glyph/无名标签不做 clickText 全树文本匹配（会误中「金币×100」类节点），按行坐标直点（体检追加轮 P1-8）
                            PageGuard.isGlyphCloseQuery(rc.closeLabel) && rc.x != null && rc.y != null -> {
                                // 与 AgentEngine.norm / labelAtNormalized 同规：999 → total-1，
                                // 落在屏幕外一像素的手势会直接失败（体检 P3-2 同族）
                                val px = ((rc.x!! * realW) / 999f).coerceIn(0f, (realW - 1).toFloat())
                                val py = ((rc.y!! * realH) / 999f).coerceIn(0f, (realH - 1).toFloat())
                                if (AssistantAccessibilityService.instance?.tap(px, py) == true)
                                    "已用坐标点击「${rc.closeLabel}」"
                                else null
                            }
                            else -> AssistantAccessibilityService.instance?.clickText(rc.closeLabel)
                        }
                    } else null
                    val usedExplicitExit = explicitExit?.startsWith("已") == true
                    if (!usedStructuralExit && !usedExplicitExit) {
                        AssistantAccessibilityService.instance?.pressBack()
                    }
                    rewardLayerExitRounds++
                    if (completionEvidenceFresh) adRewardCompletions++
                    android.util.Log.i(
                        "AIButler",
                        "AGLM reward-layer exit #$rewardLayerExitRounds: evidence=${rewardCompletion?.evidence}, " +
                            "label=${rewardCompletion?.closeLabel}, structural=$usedStructuralExit, explicit=$usedExplicitExit, " +
                            "completions=$adRewardCompletions"
                    )
                    runOnUiThread {
                        adapter.add(
                            ChatAdapter.Msg(
                                "system",
                                when {
                                    usedStructuralExit -> "✅ 奖励已到手，已点击完成提示旁的退出控件。"
                                    usedExplicitExit -> "✅ 奖励已到手，已点击「${rewardCompletion?.closeLabel}」退出结算层。"
                                    else -> "✅ 奖励已到手，已按返回退出当前广告层。"
                                }
                            )
                        )
                        rv.scrollToPosition(adapter.items.size - 1)
                    }
                    if (rewardLayerExitRounds >= 4) {
                        finalReply = "奖励已领取，但连续退出结算层后仍无法回到任务页。为避免误点广告已停止；请手动返回后说「继续」。"
                        android.util.Log.i("AIButler", "AGLM reward-layer exit breaker fired")
                        break
                    }
                    ctx.add(
                        JsonObject().apply {
                            addProperty("role", "user")
                            addProperty(
                                "content",
                                "✅ 系统提示：屏幕出现「${rewardCompletion?.evidence}」，本轮奖励证据已经成立。" +
                                    "系统已退出当前广告/结算层，这不是任务终点。下一轮必须回到任务中心或赚钱页，" +
                                    "重新读取同一个广告任务入口并开始下一轮；绝不要点击「点击并浏览/额外得金币/再看一个再领金币」" +
                                    "这类广告 CTA 或挽留弹窗，也不要输出 finish。"
                            )
                        }
                    )
                    Thread.sleep(1200)
                    continue
                } else {
                    // 证据消失、或证据是常驻状态（本轮未触发强退）→ 计数清零，避免跨场景累积误熔断（体检追加轮 P1-8）
                    if (!completionEvidenceNow || !completionEvidenceFresh) {
                        rewardLayerExitRounds = 0
                    }
                }
                // 📵 下载墙页：这类广告页会自动触发APK下载/安装（用户投诉「老安装软件」的元凶），绝不停留。
                // 但活跃激励广告例外：奖励倒计时存在时提前 Back 会作废已看时长；应等待或点「继续观看」。
                if (searchTarget != null &&
                    appPkg == targetAppPackage &&
                    android.os.SystemClock.elapsedRealtime() < searchSubmitWaitUntilMs &&
                    screenDump.contains("正在加载")
                ) {
                    android.util.Log.i("AIButler", "AGLM targeted search loading; defer exit guard")
                    Thread.sleep(1200)
                    continue
                }
                if (PageGuard.isDownloadWall(compactGuardDump) && rewardCountdownSeconds == null) {
                    if (lastCardLabel.isNotBlank() && appPkg == lastCardPkg && round - lastCardRound <= 6) {
                        TaskCardMemory.mark(this, appPkg, lastCardLabel, good = false)
                    }
                    lastCardLabel = ""
                    val exitLabel = PageGuard.safeAdExitLabel(screenDump)
                    val exitResult = exitLabel?.let {
                        AssistantAccessibilityService.instance?.clickText(it)
                    }
                    val usedExplicitExit = exitResult?.startsWith("已") == true
                    if (!usedExplicitExit) {
                        AssistantAccessibilityService.instance?.pressBack()
                    }
                    downloadWallExitRounds++
                    android.util.Log.i(
                        "AIButler",
                        "AGLM download-wall exit #$downloadWallExitRounds: label=$exitLabel, explicit=$usedExplicitExit, pkg=$appPkg"
                    )
                    runOnUiThread {
                        adapter.add(
                            ChatAdapter.Msg(
                                "system",
                                if (usedExplicitExit) "📵 发现下载/安装类广告页，已点击明确退出控件。"
                                else "📵 发现下载/安装类广告页，已自动返回（防止装垃圾软件）"
                            )
                        )
                        rv.scrollToPosition(adapter.items.size - 1)
                    }
                    if (downloadWallExitRounds >= 3) {
                        finalReply = "广告下载墙连续退出失败，为防止误下载已停止。请手动关闭广告后重试。"
                        android.util.Log.i("AIButler", "AGLM download-wall exit breaker fired")
                        break
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
                // 🎰 投币/报名/抽奖活动页：会消耗或锁定金币，不是看广告路线，立即退出
                if (PageGuard.isRewardTask(userText) && PageGuard.isRewardActivityPage(compactGuardDump, userText)) {
                    if (lastCardLabel.isNotBlank() && appPkg == lastCardPkg && round - lastCardRound <= 6) {
                        TaskCardMemory.mark(this, appPkg, lastCardLabel, good = false)
                    }
                    lastCardLabel = ""
                    // 奖励弹窗常不响应 Back，只能点角上的关闭控件；先点控件，Back 只做兜底
                    val exitControl = PageGuard.rewardActivityExitControl(compactGuardDump)
                    val explicitExit = when {
                        exitControl == null -> null
                        PageGuard.isGlyphCloseQuery(exitControl.closeLabel) &&
                            exitControl.x != null && exitControl.y != null -> {
                            val px = ((exitControl.x!! * realW) / 999f).coerceIn(0f, (realW - 1).toFloat())
                            val py = ((exitControl.y!! * realH) / 999f).coerceIn(0f, (realH - 1).toFloat())
                            if (AssistantAccessibilityService.instance?.tap(px, py) == true)
                                "已用坐标点击「${exitControl.closeLabel}」"
                            else null
                        }
                        else -> AssistantAccessibilityService.instance?.clickText(exitControl.closeLabel!!)
                    }
                    val usedExplicitExit = explicitExit?.startsWith("已") == true
                    if (!usedExplicitExit) {
                        AssistantAccessibilityService.instance?.pressBack()
                    }
                    rewardActivityExitRounds++
                    android.util.Log.i(
                        "AIButler",
                        "AGLM reward-activity exit #$rewardActivityExitRounds: " +
                            "label=${exitControl?.closeLabel}, explicit=$usedExplicitExit, pkg=$appPkg"
                    )
                    runOnUiThread {
                        adapter.add(
                            ChatAdapter.Msg(
                                "system",
                                if (usedExplicitExit) "🎯 发现与任务无关的活动弹窗，已点击「${exitControl?.closeLabel}」关闭"
                                else "🎯 发现与任务无关的活动页，已自动返回"
                            )
                        )
                        rv.scrollToPosition(adapter.items.size - 1)
                    }
                    if (rewardActivityExitRounds >= 3) {
                        finalReply = "多次尝试退出投币/抽奖类活动弹窗未成功，为防金币被消耗已停止。请手动关闭活动后重试。"
                        android.util.Log.i("AIButler", "AGLM reward-activity exit breaker fired")
                        break
                    }
                    ctx.add(JsonObject().apply {
                        addProperty("role", "user")
                        addProperty(
                            "content",
                            "🛑 系统提示：刚才那页是投币/报名/抽奖类活动页（或其弹窗），参与会消耗或锁定金币，已自动关闭/离开。" +
                                "用户要的是「$userText」，绝不要再点任何带「投币/报名/立即参与/抽奖」字样的入口；只执行看广告、领取、签到类动作。"
                        )
                    })
                    Thread.sleep(1200)
                    continue
                }
                // 🪟 通用阻塞性弹窗：特征命中且有安全关闭控件时直接代点，不再只依赖模型遵守「弹窗优先」
                val popupPlan = PopupGuard.plan(userText, screenDump)
                if (popupPlan != null) {
                    val closed = AssistantAccessibilityService.instance?.clickText(popupPlan.closeLabel)
                        ?.startsWith("已") == true
                    android.util.Log.i(
                        "AIButler",
                        "AGLM popup-guard: ${popupPlan.reason}, label=${popupPlan.closeLabel}, closed=$closed"
                    )
                    runOnUiThread {
                        adapter.add(
                            ChatAdapter.Msg(
                                "system",
                                "🪟 检测到阻塞性弹窗（${popupPlan.reason}），已自动点击「${popupPlan.closeLabel}」" +
                                    if (closed) "" else "（未点中，交还视觉脑处理）"
                            )
                        )
                        rv.scrollToPosition(adapter.items.size - 1)
                    }
                    // 点没点中要如实告诉模型：明明没关掉却说「已关闭」，模型下轮看到弹窗还在会困惑（体检 P1-9）
                    ctx.add(JsonObject().apply {
                        addProperty("role", "user")
                        addProperty(
                            "content",
                            if (closed)
                                "🛑 系统提示：刚才页面被${popupPlan.reason}挡住，系统已自动点击「${popupPlan.closeLabel}」关闭。" +
                                    "弹窗不是任务进度，别把弹窗当成完成证据。关闭后请重新读屏，继续找与「$userText」直接相关的入口；" +
                                    "绝不要点击弹窗里的添加/安装/立即更新/去开启。"
                            else
                                "🪟 系统提示：页面被${popupPlan.reason}挡住，系统尝试点「${popupPlan.closeLabel}」但没点中。" +
                                    "请你自己点击弹窗上的关闭/跳过/以后再说/我知道了；实在找不到就 do(action=\"Back\")。" +
                                    "绝不要点击弹窗里的添加/安装/立即更新/去开启，也别把弹窗当成完成证据。"
                        )
                    })
                    Thread.sleep(1000)
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
                    // 等应用起来再读屏，否则这里会快速自旋连续 openApp（体检 P3-11）
                    Thread.sleep(1200)
                    continue
                }
                if (targetAppPackage != null && appPkg == targetAppPackage) {
                    wrongAppReturns = 0   // 回到目标应用即重新计数（只看「连续」离开）
                }
                if (((targetAppPackage != null && wrongAppReturns < 5) || unguidedMode) && !appIsSystemFlow &&
                    appPkg != targetAppPackage && !appPkg.startsWith("com.aibutler")
                ) {
                    // 被拉出目标App：如果刚点过任务卡，这就是一张「外跳广告卡」，记入任务卡记忆
                    val trapLabel = if (lastCardLabel.isNotBlank() && round - lastCardRound <= 6) {
                        lastCardLabel
                    } else lastTapLabel
                    if (lastCardLabel.isNotBlank() && round - lastCardRound <= 6) {
                        TaskCardMemory.mark(this, targetAppPackage ?: lastCardPkg, lastCardLabel, good = false, outcome = "jump")
                    }
                    if (trapLabel.isNotBlank()) jumpTrapLabels.add(TaskCardMemory.normalizeLabel(trapLabel))
                    AgentEngine.markLastClickBad()
                    // 文字守卫可能记到「无文字元素」这类泛化标签，坐标桶是更可靠的机械记忆
                    if (lastTapBucket.isNotBlank()) blockedTapBuckets.add(lastTapBucket)
                    lastCardLabel = ""
                    wrongAppReturns++
                    val recovery = if (!unguidedMode) {
                        attemptExitRecovery(targetAppPackage, guessedApp?.first ?: "", trapLabel)
                    } else {
                        ExitRecoveryResult(false, emptyList(), "未确定目标应用，先按返回兜底")
                    }
                    if (recovery.recovered) {
                        runOnUiThread {
                            adapter.add(ChatAdapter.Msg("system", "↩️ ${recovery.detail}，继续任务。"))
                            rv.scrollToPosition(adapter.items.size - 1)
                        }
                        ctx.add(JsonObject().apply {
                            addProperty("role", "user")
                            addProperty(
                                "content",
                                "🛑 系统提示：刚才的入口曾把页面拉出目标应用，系统已通过通用退出路线回到目标应用。" +
                                    "不要再点同一入口；重新读屏后换与「$userText」直接相关的原生入口。"
                            )
                        })
                        Thread.sleep(1000)
                        continue
                    } else {
                        if (wrongAppCycles >= 2) {
                            android.util.Log.i("AIButler", "AGLM wrongApp cycles exceeded, stopping")
                            finalReply = "任务多次被广告/外跳拉出目标应用，为防误操作已自动停止。已完成部分以页面显示为准。"
                            break
                        }
                        wrongAppCycles++
                        // 通用兜底：通用按钮路线和返回都没回到目标应用时，不再在第三方页面继续探索
                        android.util.Log.i(
                            "AIButler",
                            "AGLM wrongApp recovery failed x$wrongAppReturns, relaunching target #$wrongAppCycles detail=${recovery.detail}"
                        )
                        DeviceTools.openApp(this, guessedApp?.first ?: "")
                        runOnUiThread {
                            adapter.add(ChatAdapter.Msg("system", "🚀 返回无效，已直接重新打开目标应用。"))
                            rv.scrollToPosition(adapter.items.size - 1)
                        }
                        // 教会模型：刚才点击的入口会跳出目标应用，别再点它
                        ctx.add(JsonObject().apply {
                            addProperty("role", "user")
                            addProperty(
                                "content",
                                "🛑 系统提示：你刚才点击的入口会跳出到无关第三方应用（通用退出尝试未成功，已重新拉回${guessedApp?.first ?: "目标应用"}）。" +
                                    "绝不要再点击那个位置或广告/下载/外跳入口。请回到目标应用内，继续寻找与「$userText」直接相关的原生入口。"
                            )
                        })
                    }
                    Thread.sleep(1800)
                    continue
                }
                shortcutOnlyRounds = 0
                val taskNote = "\n用户任务: $userText"
                val rewardDone = !passiveVideoTask && Regex(
                    "明日签到可领|今日已签到|已签到\\s*\\d+\\s*/\\s*\\d+\\s*天|领取成功|已成功领取|奖励已发放|已到账",
                    RegexOption.IGNORE_CASE
                ).containsMatchIn(screenDump)
                val rewardCountdown = !rewardDone && rewardCountdownSeconds != null
                val countdownNote = if (rewardCountdown) {
                    if (rewardCountdownStallRounds >= 3) {
                        "\n⏳ 系统读屏：页面有倒计时，但页面文本和上一步完全一样——倒计时没有走动，等待是无效的。请点击「继续观看/领取/放弃奖励」等按钮推进；点了也没反应就换其他金币入口；若确实没有可推进的入口，直接 finish 并汇报当前进度。"
                    } else {
                        val minutes = (rewardCountdownSeconds ?: 0) / 60
                        val seconds = (rewardCountdownSeconds ?: 0) % 60
                        "\n⏳ 系统读屏：当前奖励入口仍在倒计时（约${minutes}分${seconds}秒）。必须等待，不要点击禁用入口；倒计时结束后再点击当前页面的领取/继续入口。"
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
                val doneNote = if (rewardDone && TaskCompletionVerifier.isExplicitAdRewardTask(userText)) {
                    "\n✅ 系统读屏：广告任务的完成证据已出现。完成一轮不等于任务结束，也不允许输出 finish；" +
                        "必须退出当前广告/结算层并回到任务中心，再看是否还有下一轮。"
                } else if (rewardDone && Regex("金币|签到|赚钱").containsMatchIn(userText)) {
                    "\n✅ 系统读屏：页面显示今日签到/领取已完成。不要再找签到入口，也不要反复尝试广告类外跳任务卡；只有页面上存在明确的非广告领取按钮（立即领取/开宝箱等）时才点它，否则直接 finish 并汇报今日已完成内容。"
                } else ""
                val cardNote = if (Regex("金币|任务中心|去赚钱|奖励").containsMatchIn(screenDump))
                    TaskCardMemory.advise(this, appPkg, screenDump) else ""
                val trapNote = if (jumpTrapLabels.isNotEmpty()) {
                    val visible = TaskCardMemory.visibleLabels(screenDump)
                    val hits = LinkedHashSet<String>()
                    for (trap in jumpTrapLabels) {
                        TaskCardMemory.matchLabel(visible, trap)?.let { hits.add(it) }
                    }
                    if (hits.isNotEmpty())
                        "\n🚫 系统记忆：这些入口本任务内已实测会跳出目标应用，绝不能再点：${hits.joinToString("、")}。请换与任务直接相关的原生入口。"
                    else ""
                } else ""
                val adAnchorNote = if (PageGuard.hasMissionVerbs(userText))
                    "\n🎯 任务锚定：只执行用户点名的玩法，优先点击与任务目标直接对应的入口、继续/领取/关闭控件；" +
                        "页面上的其他任务、活动、下载、跳转入口都不是本任务，绝不点击。"
                else ""
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
                // 动作-变化反馈：注入一次就清空，只描述紧邻的上一动作
                val feedbackLine = if (lastActionFeedback.isBlank()) "" else "$lastActionFeedback\n"
                lastActionFeedback = ""
                val markNote = if (screenMarks.isEmpty()) ""
                    else "\n🎯 本轮截图已叠加蓝色数字角标（共${screenMarks.size}个）：角标=可点击/输入框元素的编号，与关键元素行首的[N]一一对应。" +
                        "要点击带角标的元素，Tap 坐标就是角标中心（即该元素中心）；编号绝不能当坐标输出；没有角标的元素仍按截图位置输出。"
                val screenInfo = "$feedbackLine$appIdentityNote$markedDump$countdownNote$doneNote$cardNote$trapNote$adAnchorNote$pickerNote$pickerTargetAdvise$goalNote$durationNote$markNote\n(截图是最新页面；所有Tap/Swipe坐标必须用0-999)" +
                    "\n坐标规则：Tap坐标只能来自截图目标中心；不要抄“关键元素”里的坐标。截图上明显存在但关键元素没列出的按钮，也必须按截图位置输出。" +
                    retryNote +
                    "$taskNote\n输出要求：先输出<think>一句话说明当前是什么页面、目标元素在哪、为什么选这个动作（120字以内）</think>，再输出一个动作。"
                android.util.Log.i("AIButler", "AGLM screenInfo=${screenInfo.take(500).replace('\n', ' ')}")
                val textPart = if (round == 1) "$userText\n\n$screenInfo" else "** Screen Info **\n\n$screenInfo"
                // 角标版截图：叠加失败或没有可标注元素时回退原图
                val markedB64 = if (screenMarks.isEmpty()) b64
                    else AgentEngine.drawMarksOnB64(b64, screenMarks) ?: b64
                ctx.add(JsonObject().apply {
                    addProperty("role", "user")
                    add("content", JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("type", "image_url")
                            add("image_url", JsonObject().apply { addProperty("url", "data:image/jpeg;base64,$markedB64") })
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
                    val beforeRecoveryFp = AssistantAccessibilityService.instance?.currentPageFingerprint()?.first
                    val recoveryDismiss = AgentEngine.protocolRecoveryDismiss(raw, screenDump)
                    val recoveryClicked = recoveryDismiss?.let {
                        AssistantAccessibilityService.instance?.clickText(it)
                    }?.startsWith("已") == true
                    if (recoveryClicked) {
                        Thread.sleep(1000)
                        val afterRecoveryFp =
                            AssistantAccessibilityService.instance?.currentPageFingerprint()?.first
                        if (afterRecoveryFp != null && afterRecoveryFp != beforeRecoveryFp) {
                            parseFails = 0
                            retryNote = AgentEngine.protocolRetryNote(raw)
                            android.util.Log.i(
                                "AIButler",
                                "AGLM protocol recovery dismissed overlay via $recoveryDismiss"
                            )
                            ctx.add(JsonObject().apply {
                                addProperty("role", "user")
                                addProperty(
                                    "content",
                                    "🪟 系统提示：你说要处理弹窗但没有输出动作协议。系统已按通用规则点击明确关闭控件「$recoveryDismiss」。" +
                                        "下一轮不要描述步骤，必须直接输出一个完整动作。"
                                )
                            })
                            continue
                        }
                    }
                    parseFails++
                    android.util.Log.i("AIButler", "AGLM protocol violation: ${raw.take(180)}")
                    if (Prefs.evalLog(this)) EvalLogger.log(this, JsonObject().apply {
                        addProperty("type", "parse_fail")
                        addProperty("goal", userText.take(80))
                        addProperty("round", round)
                        addProperty("raw", raw.take(400))
                    })
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
                val expectedMessageBody = messageSendGoal?.body
                val messageType = if (expectedMessageBody != null) AgentEngine.parseAutoGlmTypeText(answer) else null
                if (messageType != null) {
                    pendingMessageBody = messageType.takeIf {
                        it == expectedMessageBody
                    }
                }
                if (pendingMessageBody != null && pendingMessageBody == expectedMessageBody &&
                    AgentEngine.isMessageSendTapIntent(screenDump, answer, raw)
                ) {
                    android.util.Log.i("AIButler", "AGLM message send tap armed")
                }
                // 目标搜索通用纠偏：完整名称优先，搜索未出目标前不允许用取消/清空来“提交”。
                val searchTypeMatch = Regex(
                    "action\\s*=\\s*\"Type\"\\s*,\\s*text\\s*=\\s*\"((?:\\\\.|[^\"\\\\])*)\"",
                    RegexOption.IGNORE_CASE
                ).find(answer)
                if (searchTarget != null && searchSubmitted &&
                    AgentEngine.isSubmittedSearchDiscovery(screenDump, searchTarget)
                ) {
                    searchSubmitted = false
                    answer = "do(action=\"Type\", text=\"$searchTarget\")"
                    android.util.Log.i("AIButler", "AGLM recovered submitted search discovery: $searchTarget")
                    ctx.add(JsonObject().apply {
                        addProperty(
                            "role",
                            "user"
                        )
                        addProperty(
                            "content",
                            "🛑 系统提示：搜索已经提交过，但页面回到搜索历史/发现页，说明结果会话丢了。" +
                                "这里的同名历史词不是结果，不能点击。已改为重新输入完整目标「$searchTarget」；" +
                                "下一轮只点击明确的搜索控件，看到真实结果列表后再点结果。"
                        )
                    })
                    continue
                }
                if (searchTarget != null && searchTypeMatch != null &&
                    AgentEngine.isSearchInputContext(screenDump)
                ) {
                    val typed = searchTypeMatch.groupValues[1]
                    if (!AgentEngine.normalizeSearchText(typed)
                        .equals(AgentEngine.normalizeSearchText(searchTarget), ignoreCase = true)
                    ) {
                        answer = "do(action=\"Type\", text=\"$searchTarget\")"
                        android.util.Log.i("AIButler", "AGLM forced full search target: $searchTarget")
                        ctx.add(JsonObject().apply {
                            addProperty(
                                "role", "user"
                            )
                            addProperty(
                                "content",
                                "🛑 系统提示：搜索/查找必须输入完整目标名称「$searchTarget」，不要用缩写或猜测词。已改写你的输入；下一轮观察真实结果后再点明确匹配的目标项。"
                            )
                        })
                        continue
                    }
                    searchSubmitWaitUntilMs = android.os.SystemClock.elapsedRealtime() + 10_000L
                }
                val searchTapMatch = Regex(
                    "Tap[^\\]]*element=\\[(-?\\d+)\\s*,\\s*(-?\\d+)\\]",
                    RegexOption.IGNORE_CASE
                ).find(answer)
                if (searchTarget != null && searchTapMatch != null &&
                    AgentEngine.isSearchSubmitTap(
                        screenDump,
                        searchTapMatch.groupValues[1].toIntOrNull(),
                        searchTapMatch.groupValues[2].toIntOrNull()
                    )
                ) {
                    searchSubmitted = true
                    searchSubmitWaitUntilMs = android.os.SystemClock.elapsedRealtime() + 20_000L
                }
                if (searchTarget != null && !searchTargetVisible && searchTapMatch != null &&
                    AgentEngine.isSearchDismissTap(
                        screenDump,
                        searchTapMatch.groupValues[1].toIntOrNull(),
                        searchTapMatch.groupValues[2].toIntOrNull()
                    )
                ) {
                    answer = "do(action=\"Type\", text=\"$searchTarget\")"
                    android.util.Log.i("AIButler", "AGLM blocked search dismissal before target visible")
                    ctx.add(JsonObject().apply {
                        addProperty(
                            "role", "user"
                        )
                        addProperty(
                            "content",
                            "🛑 系统提示：目标「$searchTarget」还没有出现在结果里，取消/清空不能提交搜索。已改为重新输入完整目标；下一轮点击明确匹配目标的结果项。"
                        )
                    })
                    continue
                }
                if (passiveVideoTask && durationTargetSeconds == null &&
                    answer.startsWith("finish", ignoreCase = true)
                ) {
                    answer = "do(action=\"Swipe\", start=[500,650], end=[500,300])"
                    retryNote = "\n⚠️ 系统提示：普通刷视频是开放任务，没有明确数量或时间目标时不能 finish。" +
                        "已改为上滑观看下一段视频；继续保持 Swipe → Wait 循环。"
                    android.util.Log.i("AIButler", "AGLM blocked passive-video finish, forced swipe")
                    runOnUiThread {
                        adapter.add(ChatAdapter.Msg("system", "⏳ 普通刷视频任务继续观看，不提前结束"))
                        rv.scrollToPosition(adapter.items.size - 1)
                    }
                }
                val thinkTag = Regex("(?s)<think>(.*?)</think>", RegexOption.IGNORE_CASE)
                    .find(raw)?.groupValues?.get(1)?.trim().orEmpty()
                // 优先用 <think> 标签，其次用模型单独返回的推理字段
                val think = thinkTag.ifBlank { glmReply.reasoning.trim() }
                // AutoGLM 常把解释直接写在动作前；没有标签或推理字段时也展示这段说明。
                val visibleThink = think.ifBlank {
                    val anchor = protocolMatch?.value ?: fallbackFinish?.value ?: fallbackAction?.value
                    if (anchor == null) "" else raw.substringBefore(anchor).trim()
                }
                val perceivedPageText = "$visibleThink\n$answer"
                if (PageGuard.isRewardTask(userText) && answer.contains("Tap") &&
                    PageGuard.isPaymentOrOrderPage(perceivedPageText) &&
                    !PageGuard.isRejectedPaymentMention(perceivedPageText)
                ) {
                    answer = "do(action=\"Back\")"
                    android.util.Log.i("AIButler", "AGLM blocked visually perceived payment tap")
                    runOnUiThread {
                        adapter.add(ChatAdapter.Msg("system", "🛑 AI 视觉识别到支付/订单入口，已改为返回。"))
                        rv.scrollToPosition(adapter.items.size - 1)
                    }
                    ctx.add(JsonObject().apply {
                        addProperty("role", "user")
                        addProperty(
                            "content",
                            "🛑 系统提示：你刚要在视觉上识别出的支付/订单/充值控件上点击，已拦截并改为返回。" +
                                "奖励任务禁止进入付费流程；请重新找免费金币入口。"
                        )
                    })
                }
                if (PageGuard.isRewardTask(userText) && answer.contains("Tap") &&
                    Regex("直播间|正在直播|直播中|主播").containsMatchIn(perceivedPageText) &&
                    PageGuard.liveRoomExitLabel(perceivedPageText) == null
                ) {
                    answer = "do(action=\"Back\")"
                    android.util.Log.i("AIButler", "AGLM blocked visually perceived live-room tap")
                    runOnUiThread {
                        adapter.add(ChatAdapter.Msg("system", "🎯 AI 视觉识别到误入直播间，已改为返回。"))
                        rv.scrollToPosition(adapter.items.size - 1)
                    }
                    ctx.add(JsonObject().apply {
                        addProperty("role", "user")
                        addProperty(
                            "content",
                            "🛑 系统提示：你准备点击直播间里的商品/主播/礼物区域，已拦截并改为返回。" +
                                "金币任务不要停留在直播间；优先找“退出直播间/离开直播间”，再回任务页。"
                        )
                    })
                }
                // 只认屏幕上的真实进度信号，不认模型 raw 文本：模型写「倒计时已结束，点领取」也会命中
                // 旧正则（倒计时|计时|进度 无数字要求），把领取 Tap 改写成 Wait（体检第六轮 P3-2）。
                // 全部模式要求带数字，裸「倒计时/计时/进度」一律去掉
                val visionProgressHint = Regex(
                    "再看\\s*\\d+|还需\\s*\\d+|\\d+\\s*秒后|倒计时\\s*\\d+|剩余\\s*\\d+\\s*秒",
                    RegexOption.IGNORE_CASE
                ).containsMatchIn(screenDump)
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
                if (rewardCountdown && !exitDialogUp && rewardCountdownStallRounds < 3) {
                    if (rewardWaitRounds >= 45) {
                        val remaining = rewardCountdownSeconds ?: 0
                        finalReply = "奖励入口仍在倒计时（约${remaining / 60}分${remaining % 60}秒）。已自动等待一段时间，为避免长期占用手机先停止；稍后可再说「继续」。"
                        android.util.Log.i("AIButler", "AGLM reward cooldown wait limit reached: ${remaining}s")
                        break
                    }
                    if (!answer.contains("Wait") && !answer.startsWith("finish")) {
                        rewardWaitRounds++
                        val waitSeconds = if ((rewardCountdownSeconds ?: 0) > 90) 30 else 8
                        answer = "do(action=\"Wait\", duration=\"$waitSeconds seconds\")"
                        android.util.Log.i(
                            "AIButler",
                            "AGLM forced reward countdown wait #$rewardWaitRounds, left=${rewardCountdownSeconds}s"
                        )
                        runOnUiThread {
                            adapter.add(
                                ChatAdapter.Msg(
                                    "system",
                                    "⏳ 奖励入口还在倒计时，先等待，完成后自动领取（约${rewardCountdownSeconds ?: 0}秒）"
                                )
                            )
                            rv.scrollToPosition(adapter.items.size - 1)
                        }
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
                    !passiveVideoTask &&
                    Regex("金币|积分|奖励|收益|赚钱").containsMatchIn(userText) &&
                    answer.contains("Tap") && visionProgressWaitRounds < 10
                ) {
                    visionProgressWaitRounds++
                    answer = "do(action=\"Wait\", duration=\"10 seconds\")"
                    android.util.Log.i("AIButler", "AGLM forced visual progress wait")
                    runOnUiThread {
                        adapter.add(ChatAdapter.Msg("system", "⏳ AI 看到任务进度还没完成，先等待"))
                        rv.scrollToPosition(adapter.items.size - 1)
                    }
                } else if (!visionProgressHint || rewardWaitRounds != 0) {
                    visionProgressWaitRounds = 0
                }
                if (progressHoldRounds > 0 && !frozenPage &&
                    !passiveVideoTask &&
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
                    if (messageSendGoal != null && !messageSendConfirmed) {
                        messageSendFinishRejects++
                        if (messageSendFinishRejects >= 3) {
                            finalReply = "⚠️ 消息发送没有被系统确认：必须先完整输入消息，再成功点击发送控件且页面变化。已停止，请查看手机里的聊天页面。"
                            android.util.Log.i("AIButler", "AGLM message send rejected x3, stop")
                            break
                        }
                        android.util.Log.i("AIButler", "AGLM blocked finish: message send not mechanically confirmed #$messageSendFinishRejects")
                        ctx.add(JsonObject().apply {
                            addProperty("role", "user")
                            addProperty(
                                "content",
                                "🛑 系统校验：当前没有“已输入「${messageSendGoal.body}」→已点击发送→页面变化”的机械证据。" +
                                    "如果输入框已有完整消息，请点击屏幕上的发送控件；如果还没输入，先聚焦输入框并完整输入。" +
                                    "没有发送证据时 finish 会被拒绝。"
                            )
                        })
                        continue
                    }
                    // 机械判据优先裁决：打开App类任务，前台是目标=完成；不是=拒绝并告诉模型差什么
                    val fgCheck = completion.successCheck as? TaskCompletionVerifier.SuccessCheck.ForegroundApp
                    if (fgCheck != null) {
                        val pkgNow = AssistantAccessibilityService.instance?.currentPackage()
                        if (TaskCompletionVerifier.isForegroundStable(pkgNow, fgCheck.pkg) {
                                Thread.sleep(600)
                                AssistantAccessibilityService.instance?.currentPackage()
                            }) {
                            verifiedByCheck = true
                            visitedPackages.add(fgCheck.pkg)
                            finalReply = "✅ 已完成：${fgCheck.appName} 已打开（已验证应用在前台）"
                            break
                        }
                        if (pkgNow != null && pkgNow != "unknown") {
                            launchFinishRejects++
                            if (launchFinishRejects <= 2) {
                                android.util.Log.i("AIButler", "AGLM reject finish: ${fgCheck.appName} not foreground (now=$pkgNow) x$launchFinishRejects")
                                ctx.add(JsonObject().apply {
                                    addProperty("role", "user")
                                    addProperty(
                                        "content",
                                        "🛑 系统校验：${fgCheck.appName} 还没有出现在前台（当前前台是 $pkgNow）。这次 finish 已拒绝；请先用 Launch 打开 ${fgCheck.appName}，确认它已在前台（不要重复点同一个位置），再输出 finish。"
                                    )
                                })
                                continue
                            }
                            finalReply = "⚠️ 多次尝试后${fgCheck.appName}仍未出现在前台，任务停止。请手动确认，或换个说法重试。"
                            android.util.Log.i("AIButler", "AGLM launch finish rejected x3, stop")
                            break
                        }
                        // 读不到前台包名(unknown)→ 退回既有证据挑战链路
                    }
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
                        verifiedByCheck = true
                        finalReply = "✅ 已完成：${finishGoalCheck.detail}"
                        break
                    }
                    // 通用防误判：第一次 finish 不直接采信（到达页面/弹窗出现常被当成完成），
                    // 强制模型对照最新截图给出任务完成的页面证据；给得出才结束，给不出继续干活
                    if (finishVerifyCount < 1) {
                        finishVerifyCount++
                        android.util.Log.i("AIButler", "AGLM finish 验证关：要求出示完成证据")
                        val finishPrompt = if (PageGuard.isProductResearchTask(userText)) {
                            "🛑 系统提示：finish 前必须验证。请重新观察最新截图，对照用户任务「$userText」：" +
                                "在 finish 的 message 里说明搜索词、结果/详情页的关键证据，以及为什么它满足比较条件（如销量最高）；" +
                                "到达搜索框、普通首页或没有任何结果的页面都不是证据。" +
                                "证据足够→重新输出 finish(message=...)；不够→不要 finish，立即输出继续任务的下一个动作。"
                        } else {
                            "🛑 系统提示：finish 前必须验证。请重新观察最新截图，对照用户任务「$userText」：" +
                                "页面上有没有任务完成的直接证据（金币/余额确实比操作前增加、任务列表项变为已完成、明确的领取成功提示）？" +
                                "如果用户给出了精确数字/时间/日期，最新页面必须显示完全一致的目标值或明确的保存/设置成功证据；" +
                                "到达某个页面、出现弹窗、看到活动信息或原有余额数字都不算证据。有证据→重新输出 finish(message=...) 并在 message 里引用该证据；" +
                                "没有→不要 finish，立即输出继续完成任务的下一个动作。"
                        }
                        ctx.add(JsonObject().apply {
                            addProperty("role", "user")
                            addProperty(
                                "content",
                                finishPrompt
                            )
                        })
                        continue
                    }
                    // 一次性广告任务（「看一个广告」，带数量限定）有明确终点，不属于循环型：
                    // 完成一个就该允许 finish，否则拿到领取成功证据也被无限拒绝（体检 P2-2）
                    val isLoopingAdTask = !TaskCompletionVerifier.hasQuantityConstraint(userText) &&
                        Regex("看广告|刷广告|赚金币|赚积分|看激励|浏览广告|做任务|去赚钱").containsMatchIn(userText)
                    if (isLoopingAdTask) {
                        // 拒绝 12 次后放行：App 没有终止文案时不能无限烧轮次，模型坚持即如实收尾（体检 P2-2）
                        val hasTerminationSignal = Regex("今日已达上限|明日再来|次数已用完|次数用完|已达上限|没有更多|再看看别的").containsMatchIn(screenDump) ||
                            adLoopFinishRejects >= 12
                        if (!hasTerminationSignal) {
                            adLoopFinishRejects++
                            android.util.Log.i("AIButler", "AGLM finish rejected: looping ad task without termination signal #$adLoopFinishRejects")
                            ctx.add(JsonObject().apply {
                                addProperty("role", "user")
                                addProperty(
                                    "content",
                                    "🛑 系统提示：这是一个循环型广告任务（看广告赚金币/做任务）。完成一轮广告绝不算任务完成。" +
                                        "当前页面没有出现终止信号（今日已达上限/明日再来/次数用完），你必须继续：" +
                                        "① 如果还在广告/结算页，先关闭或领取奖励；② 回到任务中心页面；③ 再次点击同一个任务入口开始下一轮。" +
                                        "只有看到明确的终止提示才允许 finish。不要 finish，立即输出下一步动作。"
                                )
                            })
                            finishVerifyCount = 0
                            continue
                        }
                    }
                    // 数量型广告任务（看3个广告刷金币）：finish 由机械计数裁决。
                    // 真机曾出现零广告时模型口头报完成、完成关被页面"3个"字样放行（假完成），
                    // 所以这里只认结算层退出计数，不够目标数一律拒绝（无终止信号最多拒 12 次防烧轮次）。
                    if (quantifiedAdTarget != null && quantifiedAdTarget > 0 &&
                        adRewardCompletions < quantifiedAdTarget &&
                        !Regex("今日已达上限|明日再来|次数已用完|次数用完|已达上限|没有更多|再看看别的").containsMatchIn(screenDump) &&
                        adLoopFinishRejects < 12
                    ) {
                        adLoopFinishRejects++
                        android.util.Log.i(
                            "AIButler",
                            "AGLM quantified-ad finish rejected: $adRewardCompletions/$quantifiedAdTarget #$adLoopFinishRejects"
                        )
                        ctx.add(JsonObject().apply {
                            addProperty("role", "user")
                            addProperty(
                                "content",
                                "🛑 系统校验：任务要求看 ${quantifiedAdTarget} 个广告，系统已确认完成的只有 ${adRewardCompletions} 个（以领取结算证据为准，口头汇报不算）。" +
                                    "这次 finish 已拒绝；请回到任务中心继续点击同一个广告任务入口，直到完成数量达标或页面出现终止提示。"
                            )
                        })
                        finishVerifyCount = 0
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
                    val rewardishTask = !passiveVideoTask &&
                        Regex("金币|积分|奖励|收益|赚钱").containsMatchIn(userText) &&
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
                // think-动作一致性防护（通用）：思考要点底部导航[我]，坐标却在屏幕中上部 → 拦截并要求重新定位
                if (AgentEngine.thinkActNavMismatch(answer, recParsed?.third, recParsed?.fourth)) {
                    navMismatchCount++
                    if (navMismatchCount <= 2) {
                        android.util.Log.i("AIButler", "AGLM think-act坐标纠正: tap=(${recParsed?.third},${recParsed?.fourth})")
                        ctx.add(JsonObject().apply {
                            addProperty("role", "user")
                            addProperty("content", "⚠️ 坐标纠正：你上一步思考要点底部导航（[我]），但给的坐标在屏幕中上部，点击不会落在导航栏上。底部导航栏位于屏幕最底部（协议坐标y约930-990），[我]标签在最右下角（x约900、y约965）。请重新观察截图，直接输出点击右下角[我]的动作。")
                        })
                        continue
                    }
                } else {
                    navMismatchCount = 0
                }
                // 会话内外跳陷阱：点击前拦截「上次点击后跳出目标App」的同一入口（卡片挪位置时坐标守卫拦不住，靠文字兜底）
                if (jumpTrapLabels.isNotEmpty() && recParsed?.first == "tap" && recParsed.second.isNotBlank()) {
                    val tapNorm = TaskCardMemory.normalizeLabel(recParsed.second)
                    val trapHit = jumpTrapLabels.firstOrNull { trap ->
                        tapNorm.contains(trap) || trap.contains(tapNorm)
                    }
                    if (trapHit != null) {
                        jumpBlockStreak++
                        val blockedLabel = recParsed.second.take(40)
                        runOnUiThread {
                            adapter.add(ChatAdapter.Msg("system", "🛑 已拦截重复点击外跳入口「$blockedLabel」"))
                            rv.scrollToPosition(adapter.items.size - 1)
                        }
                        ctx.add(JsonObject().apply {
                            addProperty("role", "user")
                            addProperty(
                                "content",
                                "🛑 系统提示：「$blockedLabel」这个入口你刚才点过一次，点击后页面被拉出了目标应用，系统已拦截这次点击。" +
                                    "绝不能再点它或同类广告/下载入口；请换与「$userText」直接相关的原生入口，找不到就 Back 或 Swipe。" +
                                    (if (jumpBlockStreak >= 3) "已连续${jumpBlockStreak}次撞拦截，先输出 Back 离开当前页面。" else "")
                            )
                        })
                        continue
                    }
                }
                // 坐标级机械拦截：实测跳出目标App的位置，本任务内绝不允许再点
                if (recParsed?.first == "tap") {
                    val tapBucket = JumpTrapGuard.bucketFromAnswer(answer)
                    if (JumpTrapGuard.isBlocked(tapBucket, blockedTapBuckets)) {
                        jumpBlockStreak++
                        val bx = recParsed.third
                        val by = recParsed.fourth
                        runOnUiThread {
                            adapter.add(ChatAdapter.Msg("system", "🛑 已拦截重复点击实测外跳位置($bx,$by)"))
                            rv.scrollToPosition(adapter.items.size - 1)
                        }
                        ctx.add(JsonObject().apply {
                            addProperty("role", "user")
                            addProperty(
                                "content",
                                "🛑 系统提示：这个位置你之前点过一次，点击后页面被拉出了目标应用，系统已机械拦截。" +
                                    "绝不能再点这个位置（哪怕按钮文案变了）；请换与「$userText」直接相关的其他入口，" +
                                    "找不到就 Back 离开当前页面或 Swipe 换路。" +
                                    (if (jumpBlockStreak >= 2) "已连续${jumpBlockStreak}次撞拦截，先输出 Back 离开当前页面。" else "")
                            )
                        })
                        continue
                    }
                }
                val res = AgentEngine.execAutoGLM(
                    this, answer, realW, realH, userText, screenDump, guessedApp,
                    rewardCountdownSeconds = rewardCountdownSeconds
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
                // ===== 评测落盘：本轮「回答→执行→页面变化」写一条 JSONL =====
                val postExecFp = AssistantAccessibilityService.instance?.currentPageFingerprint()?.first
                val pageChanged = postExecFp != null && postExecFp != preActionFp?.first
                if (messageSendGoal != null && pendingMessageBody == messageSendGoal.body &&
                    pageChanged && AgentEngine.isMessageSendTapIntent(screenDump, answer, raw)
                ) {
                    messageSendConfirmed = true
                    android.util.Log.i("AIButler", "AGLM message send mechanically confirmed")
                }
                if (Prefs.evalLog(this)) {
                    EvalLogger.log(this, JsonObject().apply {
                        addProperty("type", "round")
                        addProperty("goal", userText.take(80))
                        addProperty("round", round)
                        addProperty("pkg", appPkg)
                        addProperty("answer", answer.take(300))
                        addProperty("res_info", res.info.take(300))
                        res.message?.let { addProperty("blocked", it.take(60)) }
                        addProperty("page_changed", pageChanged)
                        addProperty("marks", screenMarks.size)
                        visionFrame?.let { addProperty("frame", it.name) }
                    })
                }
                // ===== 动作-变化反馈：给模型一个明确的「这次点击到底生效没」信号 =====
                // 只有真正执行了的动作（非 finish/拦截/解析失败）才生成反馈并复位拦截连击
                if (!res.finished && res.message == null) {
                    jumpBlockStreak = 0
                    val isWaitAction = answer.contains("Wait")
                    lastActionFeedback = "🧭 上一动作反馈：" + res.info.take(90) + " → " + when {
                        postExecFp == null -> "执行后未能读取页面状态"
                        pageChanged -> "页面已变化，动作生效；请基于新页面重新观察，不要沿用旧结论"
                        isWaitAction -> "页面文本无变化（若在等视频/倒计时属正常，继续推进；连续无变化就别再等了）"
                        else -> "⚠️页面无任何变化，该动作很可能无效——绝不要重复同一坐标/同一元素，换入口、按返回或滑动另找路径"
                    }
                }
                // 录制：执行+稳定后取目标页面指纹，追加到路径链
                // 录制守卫：拦截哨兵（__JUMP/COMMERCE/POPUP/PRIVACY_BLOCK__、__PARSE_FAIL__）一律不入路径——
                // 这些轮要么没执行动作，要么执行的是系统替换的动作（如弹窗代点），
                // 老写法只查 🛑/⚠️ 前缀，哨兵字符串混过守卫被存成"已验证"步骤，
                // 回放时 PathMemory 裸 tap 无任何否决层（体检第五轮 A2/B1）
                if (preActionFp != null && !res.finished &&
                    (res.message == null || !res.message.startsWith("__")) &&
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
                    if (Prefs.evalLog(this)) EvalLogger.log(this, JsonObject().apply {
                        addProperty("type", "parse_fail")
                        addProperty("goal", userText.take(80))
                        addProperty("round", round)
                        addProperty("raw", answer.take(400))
                    })
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
                if (res.message == "__POPUP_BLOCK__") {
                    // 弹窗硬关闭层（AgentEngine 点击链）的拦截：res.info 与点击事实一致，如实转述即可；
                    // 不进 jumpBlockStreak——弹窗不是「跳转入口」，连拦也不该代按返回（体检 P1-9）
                    runOnUiThread {
                        adapter.add(ChatAdapter.Msg("system", res.info))
                        rv.scrollToPosition(adapter.items.size - 1)
                    }
                    ctx.add(JsonObject().apply {
                        addProperty("role", "user")
                        addProperty("content", res.info)
                    })
                    continue
                }
                if (res.message == "__JUMP_BLOCK__") {
                    jumpBlockStreak++
                    runOnUiThread {
                        adapter.add(ChatAdapter.Msg("system", res.info))
                        rv.scrollToPosition(adapter.items.size - 1)
                    }
                    val blockedTarget = res.info.substringAfter("：", "").take(50)
                    // 连续撞拦截说明模型在反复尝试同一类外跳入口：第3次起系统代按返回帮它离开这一页
                    val escapeNote = if (jumpBlockStreak >= 3) {
                        val backed = AssistantAccessibilityService.instance?.pressBack() == true
                        android.util.Log.i("AIButler", "AGLM jump-block streak=$jumpBlockStreak, back=$backed")
                        "已连续${jumpBlockStreak}次撞上拦截，系统已替你按返回离开当前页面。"
                    } else ""
                    ctx.add(JsonObject().apply {
                        addProperty("role", "user")
                        addProperty(
                            "content",
                            "🛑 系统提示：上一步坐标命中疑似跳转/下载/更新类入口（被拦元素：${blockedTarget.ifBlank { "未知" }}），已拦截，没有点击。$escapeNote" +
                                "绝不要再点这个位置或同类入口；请重新看最新截图，改选目标应用内部的原生任务；看不准就 Swipe 或 Back。"
                        )
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
                if (res.message == "__PRIVACY_BLOCK__") {
                    // 隐私条款同意按钮被安全层拦截：模型不知道被拦，不告诉它会反复撞同一拦截。
                    // 隐私同意属于用户手动确认事项，永远不代点——让模型请求用户自行操作
                    runOnUiThread {
                        adapter.add(ChatAdapter.Msg("system", res.info))
                        rv.scrollToPosition(adapter.items.size - 1)
                    }
                    finalReply = "🛑 检测到隐私条款/用户协议同意按钮。出于安全考虑，系统不会自动点击此类按钮，需要您手动确认后重试。"
                    break
                }
                if (res.message == "__SENSITIVE_BLOCK__") {
                    runOnUiThread {
                        adapter.add(ChatAdapter.Msg("system", res.info))
                        rv.scrollToPosition(adapter.items.size - 1)
                    }
                    finalReply = "🛑 这一步涉及付款、订单确认或验证码，系统已停止并交还给您手动确认。"
                    break
                }
                if (res.finished) {
                    finalReply = res.message ?: "任务完成"
                    break
                }
                runOnUiThread {
                    val actionName = Regex("action=\"([^\"]+)\"").find(answer)
                        ?.groupValues?.get(1)?.trim().orEmpty()
                    val actionTitle = when {
                        actionName.equals("Tap", true) -> "点击目标"
                        actionName.equals("Swipe", true) -> "滑动屏幕"
                        actionName.equals("Type", true) || actionName.equals("Type_Name", true) -> "输入内容"
                        actionName.equals("Launch", true) -> "启动应用"
                        actionName.equals("Wait", true) -> "等待页面"
                        actionName.equals("Back", true) -> "返回上一页"
                        actionName.isNotBlank() -> actionName
                        else -> "执行动作"
                    }
                    val target = Regex("element=\\[(-?\\d+)\\s*,\\s*(-?\\d+)\\]").find(answer)
                        ?.let { " · 目标(${it.groupValues[1]},${it.groupValues[2]})" }.orEmpty()
                    val resultLine = res.info.lineSequence().firstOrNull { it.isNotBlank() } ?: "动作已发送"
                    adapter.add(
                        ChatAdapter.Msg(
                            "system",
                            "🎯 第${round}轮 · $actionTitle$target\n→ ${resultLine.take(130)}"
                        )
                    )
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
                // Bug#10b：Swipe 也要纳入防空转计数。
                // 老写法把滑动的重复判断挂在 tap 的 bucket 上，且非 tap 轮先清空再比较，计数恒为 1、整段失效
                // （体检第五轮 A1）。现在滑动用独立签名（s_ 前缀 + 同款 15 桶），与点击共用同一套自愈/熔断阶梯
                val swipeM = Regex("Swipe[^\\]]*start=\\[(-?\\d+)\\s*,\\s*(-?\\d+)\\]").find(answer)
                if (tapM != null) {
                    val bucket = "${tapM.groupValues[1].toInt() / 15}_${tapM.groupValues[2].toInt() / 15}"
                    val tapLabel = Regex("已点击屏幕\\([^)]*\\)→([^\\n]+)").find(res.info)?.groupValues?.get(1)?.trim()
                    val repeated = bucket == lastTapBucket && (tapLabel == null || tapLabel == lastTapLabel)
                    if (repeated) sameTapCount++ else { sameTapCount = 1 }
                    lastTapBucket = bucket
                    lastTapLabel = tapLabel.orEmpty()
                    lastSwipeSig = ""
                } else if (swipeM == null) {
                    // Back/Home/Wait/Type 等其他动作：动作类型变了才清零（滑动轮不再误清 tap 计数）
                    sameTapCount = 0
                    lastTapBucket = ""
                    lastTapLabel = ""
                    lastSwipeSig = ""
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
                    // 目标包名是路由猜的：路由没猜中时不能拿它判"未进入"——用被启动应用自己的包名核对
                    // （unguided 模式 targetAppPackage 为 null，老写法会把已成功打开的应用误判失败，体检第五轮 A3）
                    val launchedPkg = DeviceTools.resolveApp(this, lastLaunchApp)?.second
                    val targetReached = (targetAppPackage ?: launchedPkg)?.let { target ->
                        AssistantAccessibilityService.instance?.currentPackage() == target
                    } == true
                    if (!targetReached) {
                        finalReply = "连续3次启动「$lastLaunchApp」仍未进入该应用。该应用可能不存在或无法打开，任务已自动停止。"
                        android.util.Log.i("AIButler", "AGLM sameLaunchCount breaker fired: app=$lastLaunchApp count=$sameLaunchCount")
                        break
                    }
                }
                // Bug#10b：Swipe 计数收尾（swipeM 复用上面新块的声明：这里再写一遍 val 会同作用域
                // 重复声明直接编译冲突——体检第六轮 P0；tap空/swipe非null 的情形由这里计数）
                if (tapM == null && swipeM != null) {
                    val swipeSig = "s_${swipeM.groupValues[1].toInt() / 15}_${swipeM.groupValues[2].toInt() / 15}"
                    if (ActionProgress.repeatedSwipeIdle(lastSwipeSig, swipeSig, pageChanged)) {
                        sameTapCount++
                    } else {
                        sameTapCount = 1
                    }
                    lastSwipeSig = swipeSig
                }
                if (sameTapCount >= 6) {
                    finalReply = "连续6次重复同一动作都没有推进任务，已自动停止。请手动确认目标入口后重试。"
                    break
                }
                val pageBody = Regex("页面内容:(.*?)关键元素", RegexOption.DOT_MATCHES_ALL)
                    .find(screenDump)?.groupValues?.get(1)?.replace(Regex("\\s+"), "").orEmpty()
                if (sameTapCount == 4 || (swipeM != null && sameTapCount >= 2 && pageBody.isBlank())) {
                    // 自愈第二档：滑屏无效时多半卡在全屏广告/视频层，按返回是通用退出方式。
                    // 读屏只剩空白容器时，同一方向连滑 2 次就要提前返回，避免在无文字层里空烧轮次。
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
                // 真机教训（抖音视频层79轮空转）：换着点的动作签名不同会绕过重复检测，
                // 所以冻结页上的任何动作都计入空转——动作执行了但页面毫无变化=没进展
                val isWait = answer.contains("Wait")
                val sig = answer + "|" + res.info
                if (AgentEngine.isNoProgressRound(pageChanged, passiveVideoTask, countdownProgressed)) {
                    noProgress++
                    android.util.Log.i(
                        "AIButler",
                        "AGLM ${if (isWait) "静止等待" else "静止动作"}计入空转 x$noProgress（页面未变化）"
                    )
                } else {
                    noProgress = 0
                    lastProgressSig = sig
                }
                if (noProgress >= AgentEngine.PROGRESS_STALL_ABORT_AT) {
                    finalReply = AgentEngine.PROGRESS_STALL_ABORT_MESSAGE
                    android.util.Log.i("AIButler", "AGLM 无进展熔断 x$noProgress，任务中止")
                    runOnUiThread {
                        adapter.add(ChatAdapter.Msg("system", "🛑 $finalReply"))
                        rv.scrollToPosition(adapter.items.size - 1)
                    }
                    break
                } else if (noProgress >= 8) {
                    ctx.add(JsonObject().apply {
                        addProperty("role", "user")
                        addProperty("content", "🛑 系统提示：已连续${noProgress}步毫无进展。如果这不是合理的等待类任务，请立即换方法；否则请直接 finish(message=\"说明当前结果\") 结束。")
                    })
                    android.util.Log.i("AIButler", "AGLM 无进展防护触发(硬) x$noProgress")
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
                pausedTask = PausedTask(
                    mode = "autoglm",
                    userText = userText,
                    fromVoice = false,
                    contextUserText = contextUserText,
                    plan = null,
                    currentStep = 1,
                    launchedTarget = guessedApp,
                    aglmCtx = ctx.deepCopy(),
                    aglmGuessedApp = guessedApp,
                    aglmRelatedMemory = relatedMemory,
                    historySnapshot = history.deepCopy()
                )
                runOnUiThread {
                    adapter.settleVision("⏸️ 任务已暂停")
                    adapter.add(ChatAdapter.Msg("system", "⏸️ 已暂停。想继续就说「继续」，想改主意直接说新的指令。"))
                    rv.scrollToPosition(adapter.items.size - 1)
                }
            } else {
                val reply = finalReply ?: "任务结束"
                android.util.Log.i("AIButler", "AGLM 最终回复: $reply")
                if (Prefs.evalLog(this)) EvalLogger.log(this, JsonObject().apply {
                    addProperty("type", "end")
                    addProperty("goal", userText.take(80))
                    addProperty("rounds", round)
                    addProperty("reply", reply.take(400))
                    addProperty("task_class", completion.taskClass.name)
                    if (verifiedByCheck) addProperty("verified", true)
                })
                val judgedSuccess = reply.contains("✅")
                val targetNeverReached = guessedApp != null &&
                    (targetAppPackage == null || targetAppPackage !in visitedPackages)
                // 机械校验优先；没有机械证据时由经验总结模型基于轨迹判定，用户不再补勾叉。
                val effectiveForceSuccess = ExperienceVerdict.resolve(targetNeverReached, verifiedByCheck)
                // 先把结果立刻显示给用户；经验总结要再跑一次大模型调用，放到后台，完成后再补充显示
                runOnUiThread {
                    // 截图状态行先改口（已完成/已停止），最终回复紧随其后
                    adapter.settleVision(if (judgedSuccess) "✅ 已完成任务" else "⛔ 任务已停止")
                    adapter.add(ChatAdapter.Msg("assistant", reply))
                    rv.scrollToPosition(adapter.items.size - 1)
                }
                // 只有用户明确勾选「正确」才保存可回放路径
                val effectiveSteps = recordingSteps.filter { step ->
                    step.actionType == "back" || step.targetFingerprint != step.sourceFingerprint
                }
                if (effectiveForceSuccess == true && effectiveSteps.isNotEmpty() && targetAppPackage != null) {
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
                        mechanicalSuccess = effectiveForceSuccess
                    )
                    runOnUiThread {
                        if (experienceResult.isNotBlank()) adapter.add(ChatAdapter.Msg("system", experienceResult))
                        rv.scrollToPosition(adapter.items.size - 1)
                    }
                }.start()
            }
        } catch (e: Exception) {
            android.util.Log.e("AIButler", "AGLM 异常 class=${e.javaClass.name} message=${e.message}", e)
            runOnUiThread {
                adapter.settleVision("⛔ 任务已停止")
                adapter.add(ChatAdapter.Msg("system", "❌ AutoGLM 出错了：${e.message}"))
                rv.scrollToPosition(adapter.items.size - 1)
            }
        }
    }

    /**
     * 通用任务启动：先用显式应用名匹配真实启动器，失败再从真实安装列表做语义路由。
     * 所有任务共用；具体应用、页面和后续步骤不写死。
     */
    private fun launchTargetAppForTask(userText: String, contextUserText: String): Pair<String, String>? {
        AgentEngine.extractHttpUrl(userText)?.let { url ->
            val browser = resolveBrowserForUrl(url)
            if (browser != null) {
                val launchResult = DeviceTools.openAppFresh(this, browser.second)
                android.util.Log.i("AIButler", "URL target launched=${browser.first}|${browser.second} result=$launchResult")
                return browser
            }
        }
        val target = DeviceTools.guessApp(this, userText, contextUserText)
            ?: inferTargetApp(userText, contextUserText)
        if (target == null) {
            android.util.Log.i("AIButler", "TARGET launch skipped: no installed app matched task=$userText")
            return null
        }
        runOnUiThread {
            adapter.add(ChatAdapter.Msg("system", "🚀 正在进入目标应用：${target.first}"))
            rv.scrollToPosition(adapter.items.size - 1)
        }
        val launchResult = if (AgentEngine.shouldColdStart(userText)) {
            DeviceTools.openAppFresh(this, target.second)
        } else {
            DeviceTools.openApp(this, target.first)
        }
        android.util.Log.i("AIButler", "TARGET launched=${target.first}|${target.second} result=$launchResult")
        return target
    }

    /** 网页任务以系统能处理该链接的浏览器为目标应用，避免误把任务文字匹配到无关 App。 */
    private fun resolveBrowserForUrl(url: String): Pair<String, String>? {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            val ri = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                ?: return null
            val label = ri.loadLabel(packageManager).toString().trim()
            if (label.isBlank()) null else label to ri.activityInfo.packageName
        } catch (_: Exception) {
            null
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

    /**
     * 误入第三方应用后的通用退出恢复：先回放已验证按钮路线，再按当前读屏结果
     * 尝试“放弃/退出/关闭/返回”类控件。只有真实回到目标应用才写入记忆。
     */
    private fun attemptExitRecovery(
        targetPackage: String?,
        targetAppName: String,
        trapLabel: String
    ): ExitRecoveryResult {
        val svc = AssistantAccessibilityService.instance
            ?: return ExitRecoveryResult(false, emptyList(), "无障碍服务不可用")
        val wrongPackage = svc.currentPackage()
        if (targetPackage.isNullOrBlank()) {
            return ExitRecoveryResult(false, emptyList(), "目标应用未知，不能安全尝试按钮退出")
        }
        if (wrongPackage == targetPackage) {
            return ExitRecoveryResult(true, emptyList(), "已在目标应用")
        }

        val remembered = ExitMemory.routeFor(this, targetPackage, wrongPackage, trapLabel)
        val actions = ArrayList<String>()

        fun reachedTarget(): Boolean = svc.currentPackage() == targetPackage
        fun settle(action: String) {
            AgentEngine.waitForActionSettle(if (action == "Back") "Back" else "Tap", null)
        }

        // 记忆路线可能包含 Back。按钮文字变化时 clickText 自然失败，后面会用新读屏重新找。
        for (action in remembered) {
            val sent = if (action == "Back") svc.pressBack() == true
            else svc.clickText(action).startsWith("已")
            if (!sent) continue
            actions.add(action)
            settle(action)
            if (reachedTarget()) {
                ExitMemory.markSuccess(this, targetPackage, wrongPackage, trapLabel, actions)
                return ExitRecoveryResult(true, actions, "已按记忆路线回到${targetAppName.ifBlank { "目标应用" }}")
            }
        }

        var currentDump = svc.dumpScreenForAutoGLM()
        var currentSig = AgentEngine.fingerprint(currentDump)
        val attempted = remembered.map(ExitMemory::normalizeButton).toMutableSet()
        var pageChanges = 0

        // 每次只点一个候选并确认结果。页面变了就重新读屏；同一页上失败过的按钮不再重复点。
        while (!reachedTarget() && actions.size < 5 && pageChanges < 3) {
            val candidates = ExitMemory.orderedCandidates(currentDump, remembered + actions)
            val candidate = candidates.firstOrNull {
                it.score > 0 && it.normalized !in attempted
            } ?: break
            attempted.add(candidate.normalized)
            actions.add(candidate.label)
            val sent = svc.clickText(candidate.label).startsWith("已")
            settle("Tap")
            if (!sent) {
                actions.removeAt(actions.lastIndex)
                continue
            }
            if (reachedTarget()) {
                ExitMemory.markSuccess(this, targetPackage, wrongPackage, trapLabel, actions)
                return ExitRecoveryResult(true, actions, "已通过「${candidate.label}」回到${targetAppName.ifBlank { "目标应用" }}")
            }

            val nextDump = svc.dumpScreenForAutoGLM()
            val nextSig = AgentEngine.fingerprint(nextDump)
            if (nextSig != currentSig) {
                currentDump = nextDump
                currentSig = nextSig
                pageChanges++
            }
        }

        if (reachedTarget()) {
            ExitMemory.markSuccess(this, targetPackage, wrongPackage, trapLabel, actions)
            return ExitRecoveryResult(true, actions, "已回到${targetAppName.ifBlank { "目标应用" }}")
        }

        val backSent = svc.pressBack() == true
        if (backSent) {
            actions.add("Back")
            settle("Back")
            if (reachedTarget()) {
                ExitMemory.markSuccess(this, targetPackage, wrongPackage, trapLabel, actions)
                return ExitRecoveryResult(true, actions, "退出按钮无效，按返回键回到${targetAppName.ifBlank { "目标应用" }}")
            }
        }

        if (remembered.isNotEmpty()) {
            ExitMemory.markFailure(this, targetPackage, wrongPackage, trapLabel)
        }
        return ExitRecoveryResult(false, actions, "屏幕上的退出候选没有带回目标应用")
    }

    /** vision_frames 目录最多保留的截图张数（每轮任务存一张，不清会无限膨胀） */
    private val MAX_VISION_FRAMES = 50

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
            // 每轮任务都存一张截图，不清的话存储无限膨胀；这里保留最新50张（本函数跑在任务线程，不卡UI）
            dir.listFiles()?.sortedBy { it.lastModified() }?.let { olds ->
                for (i in 0 until (olds.size - MAX_VISION_FRAMES)) {
                    runCatching { olds[i].delete() }
                }
            }
            file
        }.getOrNull()
    }

    /** 函数调用模式的经验保存：把任务结果存入经验库，下次同类任务注入系统提示 */
    private fun saveAgentExperience(task: String, result: String, requireOutcomeJudgement: Boolean = false) {
        if (!Prefs.configured(this)) return
        try {
            val guessed = !result.contains("失败") && !result.contains("无法") &&
                !result.contains("超时") && !result.contains("已停止") && !result.contains("拦截")
            if (result.length < 5) return
            var recordSuccess = guessed
            if (guessed && requireOutcomeJudgement) {
                recordSuccess = judgeTaskOutcome(task, result)
                    ?: run {
                        runOnUiThread {
                            adapter.add(ChatAdapter.Msg("system", "⚠️ 结果证据不足，本次经验不记录。"))
                            rv.scrollToPosition(adapter.items.size - 1)
                        }
                        return
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

    /** 通用结果审计：模型只回答完成与否，不改变已经结束的任务，也不写入任何凭据类内容。 */
    private fun judgeTaskOutcome(task: String, result: String): Boolean? {
        if (!Prefs.configured(this)) return null
        return try {
            val prompt = """
                你是手机自动化任务的结果审计员。严格判断用户目标是否已经真实完成，不要相信最终回复里的“已完成”或✅符号。
                页面/动作证据和声明冲突时判失败；只执行到支付、下单、验证码等安全确认前一步并明确交还用户时，任务执行本身判成功。
                只输出 JSON：{"SUCCESS":true} 或 {"SUCCESS":false}

                用户任务：$task
                执行结果：$result
            """.trimIndent()
            val messages = JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "user")
                    addProperty("content", prompt)
                })
            }
            val raw = LlmClient.chat(
                Prefs.baseUrl(this), Prefs.apiKey(this), Prefs.model(this),
                messages, JsonArray(), true
            ).content
            TaskOutcomeJudge.parse(raw).also {
                android.util.Log.i("AIButler", "AGENT outcome judged=$it raw=${raw.take(120)}")
            }
        } catch (e: Exception) {
            android.util.Log.w("AIButler", "任务结果评审失败", e)
            null
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
        mechanicalSuccess: Boolean? = null
    ): String {
        if (!Prefs.configured(this)) return ""
        return try {
            val userOverride = when (mechanicalSuccess) {
                true -> "\n系统机械证据已确认任务实际是「成功」，SUCCESS 字段必须填 true。"
                false -> "\n系统证据已证明任务实际是「失败」，SUCCESS 字段必须填 false；请总结这次失败的原因和应避开的走法。"
                null -> ""
            }
            val prompt = """
                请把下面一次手机自动操作总结为可复用经验。不要记录账号、密码、验证码、IMEI、余额等敏感值；只记录应用名、页面名、入口文字、路径、弹窗处理和失败原因。
                先独立核对 SUCCESS：不要相信结果里的“已完成”或✅符号；页面/动作证据和声明冲突时 SUCCESS 填 false。
                搜索/播放类任务不要因为标题没有复述查询词就判失败；只要进入了与查询语义匹配的详情页或播放页，并且动作里没有广告/下载/支付标记，就按成功总结。
                如果只执行到支付、下单、验证码等安全确认前一步并明确交还用户，任务执行本身算成功。
                严格输出以下字段：
                TITLE: 不超过20字的经验标题
                CONTENT: 不超过300字的路径和要点
                REASON: 不超过80字的成败证据
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
            val reason = field("REASON")
            val keywords = field("KEYWORDS").split(',', '，', ';', '；').map { it.trim() }.filter { it.isNotBlank() }
            val packageNames = field("PACKAGES").split(',', '，', ';', '；').map { it.trim() }.filter { it.isNotBlank() }
            val success = mechanicalSuccess ?: TaskOutcomeJudge.parse(raw)
            if (success == null) {
                return "⚠️ 结果证据不足，本次经验不记录。"
            }
            if (!success && reason.isBlank()) {
                return "⚠️ 失败结论缺少证据，本次经验不记录。"
            }
            AgentExperienceStore.upsert(
                this, AgentExperience(title, content, keywords, packageNames, success)
            )
            if (success) "📚 已保存可复用经验：$title"
            else "🧭 已保存失败教训：$title"
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
        if (!busyNow) {
            // 任务收尾：把刚结束的这次任务折成任务卡，最终结果/暂停原因仍留在聊天里
            if (adapter.settleLastTask()) {
                rv.scrollToPosition(adapter.itemCount - 1)
            }
            updateEmptyState()
        }
    }

    /** 历史压缩：按字符预算裁剪，第一条用户消息（原始指令）永远保留 */
    private fun compactHistory(maxMessages: Int = 14, keepFullTools: Int = 6) {
        val compressed = ContextCompressor.compress(history)
        if (compressed.size() != history.size()) {
            while (history.size() > 0) history.remove(0)
            for (e in compressed) history.add(e)
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
        // 用户拒绝了就不强行往下拉下一个授权框，尊重用户选择（体检 P2-9）
        if (requestCode == 300) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                requestCapture()
            }
        }
        if (requestCode == 400) {
            // 运行时权限授权完毕，继续检查截图
            if (grantResults.isEmpty() || grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                if (!CaptureService.isActive()) requestCapture()
            }
        }
    }

    override fun onDestroy() {
        uiTickHandler.removeCallbacks(uiTick)
        TaskControlBus.release()
        recognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }
}
