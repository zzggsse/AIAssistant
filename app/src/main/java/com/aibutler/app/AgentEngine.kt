package com.aibutler.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.PowerManager
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * AI 的大脑外壳：定义工具清单（function calling），把大模型的指令翻译成手机动作。
 */
object AgentEngine {

    // ========== 任务规划 ==========

    data class PlanStep(val id: Int, val action: String, val doneHint: String)

    data class TaskPlan(
        val taskType: String,
        val targetApp: String,
        val steps: List<PlanStep>,
        val finalCheck: String
    )

    /** 规划器提示词：让 LLM 把用户指令拆成结构化 JSON 步骤 */
    fun plannerPrompt(userText: String, contextText: String): String {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date())
        return """
            你是手机任务规划器。分析用户指令，只输出严格 JSON，不要任何其他文字或markdown代码块标记。
            格式：
            {"task_type":"chat或screen_task","target_app":"目标应用名，纯聊天留空","steps":[{"id":1,"action":"动作描述","done_hint":"完成标志"}],"final_check":"最终任务完成标志"}
            规则：
            - 纯聊天、知识问答、写作类 → task_type=chat，steps=[]
            - 需要操作手机的任务 → task_type=screen_task，拆成3-6步
            - 需要打开应用的任务，第一步固定写"打开XX应用"
            - 涉及付款、转账、验证码的最后一步写"提示用户手动确认"
            - done_hint 必须是屏幕上可观察的标志，如"当前应用是淘宝"、"出现搜索输入框"
            - 用户有历史指令时，优先以最新指令为准
            - 跨应用比价类任务：每个平台搜索后加一步"记录该平台价格"，最后加"比较并选最低价平台"步骤
            - 目标类任务：拆成"打开应用→进入任务入口→执行目标要求的原生操作→确认结果→汇报"步骤
            当前时间：$now
            用户指令：$userText
        """.trimIndent()
    }

    /** 解析规划器返回的 JSON 为 TaskPlan；失败返回 null */
    fun parsePlan(raw: String): TaskPlan? {
        return try {
            var text = raw.trim()
            val fence = Regex("```(?:json)?\\s*([\\s\\S]*?)```").find(text)
            if (fence != null) text = fence.groupValues[1].trim()
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            val json = JsonParser.parseString(text.substring(start, end + 1)).asJsonObject
            val type = json.get("task_type")?.takeIf { !it.isJsonNull }?.asString ?: "chat"
            val app = json.get("target_app")?.takeIf { !it.isJsonNull }?.asString ?: ""
            val steps = ArrayList<PlanStep>()
            json.getAsJsonArray("steps")?.forEach { s ->
                val o = s.asJsonObject
                steps.add(PlanStep(
                    o.get("id")?.takeIf { !it.isJsonNull }?.asInt ?: (steps.size + 1),
                    o.get("action")?.takeIf { !it.isJsonNull }?.asString ?: "",
                    o.get("done_hint")?.takeIf { !it.isJsonNull }?.asString ?: ""
                ))
            }
            TaskPlan(type, app, steps, json.get("final_check")?.takeIf { !it.isJsonNull }?.asString ?: "")
        } catch (e: Exception) {
            null
        }
    }

    /** 把计划格式化为注入系统提示的文字 */
    private fun planSection(plan: TaskPlan?, currentStep: Int): String {
        if (plan == null || plan.steps.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("\n【当前任务计划】\n")
        sb.append("任务类型：${plan.taskType}\n")
        if (plan.targetApp.isNotBlank()) sb.append("目标应用：${plan.targetApp}\n")
        sb.append("步骤清单：\n")
        plan.steps.forEach { s ->
            val marker = if (s.id < currentStep) "✅" else if (s.id == currentStep) "▶️" else "  "
            sb.append("  $marker ${s.id}. ${s.action}")
            if (s.doneHint.isNotBlank()) sb.append("（完成标志：${s.doneHint}）")
            sb.append('\n')
        }
        if (plan.finalCheck.isNotBlank()) sb.append("最终完成标志：${plan.finalCheck}\n")
        sb.append("你当前应执行第 $currentStep 步。为提高执行速度：连续执行动作，不要每完成一步就单独调用 update_step——每完成2步、或全部完成/被安全规则拦截时才调用一次 update_step(step=最新完成的步骤编号, status=\"done\")。某步重试2次仍失败时调用 update_step(step=$currentStep, status=\"failed\", note=\"原因\")。\n")
        return sb.toString()
    }

    /** 屏幕熄灭/锁定时返回提示语，正常返回 null */

    private fun awakeMsg(a: Activity): String? {
        val pm = a.getSystemService(Context.POWER_SERVICE) as PowerManager
        val km = a.getSystemService(android.app.KeyguardManager::class.java)
        val asleep = !pm.isInteractive
        val locked = km?.isKeyguardLocked == true
        return when {
            asleep -> "⚠️ 手机屏幕已熄灭。请立即调用 wake_unlock_screen 自动亮屏解锁（需在设置里保存过PIN码），成功后重新读屏继续任务。"
            locked -> "⚠️ 屏幕亮着但处于锁屏状态。请调用 wake_unlock_screen 输入PIN自动解锁，成功后重新读屏继续任务。"
            else -> null
        }
    }

    /** 亮屏+PIN解锁的完整流程 */
    private fun wakeAndUnlock(a: Activity): String {
        val svc = AssistantAccessibilityService.instance
        val pm = a.getSystemService(Context.POWER_SERVICE) as PowerManager
        val km = a.getSystemService(android.app.KeyguardManager::class.java)
        if (pm.isInteractive && (km == null || !km.isKeyguardLocked)) {
            return "屏幕已亮且已解锁，无需操作"
        }
        // 1) 亮屏：透明 turnScreenOn 活动 + WakeLock 双保险
        try {
            a.startActivity(
                android.content.Intent(a, WakeActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            android.util.Log.i("AIButler", "WakeActivity启动失败: ${e.message}")
        }
        try {
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "aibutler:wake"
            )
            wl.acquire(8000)
        } catch (e: Exception) { }
        Thread.sleep(2500)
        if (pm.isInteractive && (km == null || !km.isKeyguardLocked)) {
            return "✅ 屏幕已点亮并解锁"
        }
        val pin = Prefs.pin(a)
        if (pin.isBlank()) {
            return "屏幕已点亮，但锁屏需要密码且未在设置里保存PIN码，无法自动解锁。请提示用户手动解锁"
        }
        var unlocked = false
        var detail = ""
        for (attempt in 1..3) {
            var dump = svc?.dumpScreen() ?: ""
            val padCount = Regex("[文字明]:[0-9] ").findAll(dump).count()
            if (padCount < 8) {
                svc?.scroll(false)   // 上滑唤出PIN键盘
                Thread.sleep(1500)
                dump = svc?.dumpScreen() ?: ""
            }
            for (ch in pin) {
                svc?.clickPinDigit(ch)
                Thread.sleep(500)
            }
            Thread.sleep(1800)
            if (pm.isInteractive && (km == null || !km.isKeyguardLocked)) {
                unlocked = true
                break
            }
            detail = "第${attempt}次输入后仍在锁屏"
        }
        return if (unlocked) "✅ 已自动亮屏并解锁成功"
        else "屏幕已点亮，但自动解锁未成功（$detail）。请提示用户手动解锁"
    }

    private fun prop(type: String, desc: String): JsonObject = JsonObject().apply {
        addProperty("type", type)
        addProperty("description", desc)
    }

    private fun tool(name: String, desc: String, props: JsonObject, required: List<String> = emptyList()): JsonObject {
        val parameters = JsonObject().apply {
            addProperty("type", "object")
            add("properties", props)
            if (required.isNotEmpty()) add("required", JsonArray().apply { required.forEach { add(it) } })
        }
        val fn = JsonObject().apply {
            addProperty("name", name)
            addProperty("description", desc)
            add("parameters", parameters)
        }
        return JsonObject().apply {
            addProperty("type", "function")
            add("function", fn)
        }
    }

    private fun obj(vararg pairs: Pair<String, JsonObject>): JsonObject = JsonObject().apply {
        pairs.forEach { (k, v) -> add(k, v) }
    }

    @Volatile
    private var cachedTools: JsonArray? = null

    fun toolsJson(): JsonArray {
        cachedTools?.let { return it }
        val tools = JsonArray()
        fun t(name: String, desc: String, props: JsonObject = obj(), required: List<String> = emptyList()) {
            tools.add(tool(name, desc, props, required))
        }

        t("get_current_time", "获取当前日期和时间")
        t("list_installed_apps", "列出手机上安装的所有应用")
        t("open_app", "按名字打开一个应用",
            obj("app_name" to prop("string", "目标应用名称")), listOf("app_name"))
        t("get_screen_state", "读取当前手机屏幕，返回带编号的元素清单（每个可点击/可输入的元素都有编号和坐标，AI 的眼睛）")
        t("click_number", "点击屏幕清单里第N号元素（推荐！比按文字找更准，能点无文字的小图标）",
            obj("index" to prop("integer", "get_screen_state 返回的元素编号")), listOf("index"))
        t("click_element", "点击屏幕上包含指定文字的元素（当编号点不准时的备用方案）",
            obj("text" to prop("string", "要点击的文字，如：发送、搜索")), listOf("text"))
        t("look_screen", "视觉识别（清单里找不到目标时必用）：截屏发给视觉模型分析，返回页面说明和该点击的坐标。适合纯图形元素：悬浮球、图片按钮、非文字图标等",
            obj("goal" to prop("string", "当前要完成的目标")), listOf("goal"))
        t("wake_unlock_screen", "屏幕熄灭或锁定时必用：自动点亮屏幕并输入已保存的PIN码解锁（需在App设置里保存过PIN码）",
            obj(), listOf())
        t("tap_point", "在屏幕坐标处点击（当 click_element 找不到时用）",
            obj("x" to prop("integer", "横坐标像素"), "y" to prop("integer", "纵坐标像素")),
            listOf("x", "y"))
        t("input_text", "把文字输入到当前屏幕的输入框（不会自动发送）",
            obj("text" to prop("string", "要输入的内容")), listOf("text"))
        t("press_back", "按返回键")
        t("wait_seconds", "等待一段时间：等加载、倒计时、播放或结果刷新时用它",
            obj("seconds" to prop("integer", "等待秒数，1到60")), listOf("seconds"))
        t("scroll_screen", "滑动屏幕（支持四个方向）",
            obj("direction" to prop("string", "down=往下滑看更多, up=往上滑, left=往左滑（下一个Tab/下一张图）, right=往右滑（上一个Tab/上一张图）")), listOf("direction"))
        t("go_home", "回到手机桌面")
        t("set_alarm", "设置系统闹钟，并打开系统时钟应用显示结果",
            obj("hour" to prop("integer", "小时(24小时制)"),
                "minute" to prop("integer", "分钟"),
                "label" to prop("string", "闹钟备注，可不填")),
            listOf("hour", "minute"))
        t("cancel_alarm", "取消闹钟（会打开闹钟列表让用户手动删除，因为系统不允许第三方直接删除）",
            obj("hour" to prop("integer", "要取消的闹钟小时(24小时制)，可不填"),
                "minute" to prop("integer", "要取消的闹钟分钟，可不填"),
                "label" to prop("string", "要取消的闹钟备注关键词，可不填")))
        t("find_contact", "在手机通讯录里查找联系人，返回姓名和电话号码",
            obj("keyword" to prop("string", "联系人姓名或关键词")), listOf("keyword"))
        t("create_calendar_event", "创建日历日程",
            obj("title" to prop("string", "日程标题"),
                "start_time" to prop("string", "开始时间，格式 yyyy-MM-dd HH:mm"),
                "end_time" to prop("string", "结束时间，格式 yyyy-MM-dd HH:mm，可不填则默认1小时"),
                "description" to prop("string", "日程描述，可不填")),
            listOf("title", "start_time"))
        t("copy_to_clipboard", "把一段文字复制到剪贴板",
            obj("text" to prop("string", "要复制的内容")), listOf("text"))
        t("read_clipboard", "读取剪贴板当前的内容")
        t("send_sms", "给联系人发短信（会打开短信应用让用户确认发送）",
            obj("contact" to prop("string", "联系人名字或手机号"),
                "message" to prop("string", "短信内容")),
            listOf("contact", "message"))
        t("make_call", "打开拨号盘填号码（不会自动拨出）",
            obj("contact" to prop("string", "联系人名字或手机号")), listOf("contact"))
        t("open_url", "用浏览器打开链接",
            obj("url" to prop("string", "网址")), listOf("url"))
        t("web_search", "联网搜索并返回结果文字",
            obj("query" to prop("string", "搜索词")), listOf("query"))
        t("read_web_page", "读取一个网页的文字内容",
            obj("url" to prop("string", "网址")), listOf("url"))
        t("read_recent_notifications", "读取最近收到的通知消息（微信/QQ/短信等），用于知道谁发来了什么",
            obj("limit" to prop("integer", "条数，默认15")))
        t("create_note", "记一条便签",
            obj("title" to prop("string", "标题"), "content" to prop("string", "内容")),
            listOf("title", "content"))
        t("read_notes", "查看便签",
            obj("keyword" to prop("string", "关键词，可不填")))
        t("update_note", "修改一条已有的便签",
            obj("id" to prop("integer", "便签编号（read_notes 返回的 id=xxx）"),
                "title" to prop("string", "新标题，留空则不改"),
                "content" to prop("string", "新内容，留空则不改")),
            listOf("id"))
        t("delete_note", "删除一条便签",
            obj("id" to prop("integer", "便签编号（read_notes 返回的 id=xxx）")),
            listOf("id"))
        t("update_step", "完成计划中的一个步骤后必调。汇报该步骤状态，然后继续执行下一步。",
            obj(
                "step" to prop("integer", "刚完成的步骤编号"),
                "status" to prop("string", "done=成功 failed=多次重试后仍失败 blocked=被安全规则拦截"),
                "note" to prop("string", "一句话说明结果或失败原因，可不填")
            ),
            listOf("step", "status"))
        t("replan", "当前计划不可行、页面情况和预期严重不符、或所有已知路径都走不通时，调用此工具请求重新规划剩余步骤。",
            obj("reason" to prop("string", "为什么需要重新规划，一句话")), listOf("reason"))

        cachedTools = tools
        return tools
    }

    fun systemPrompt(plan: TaskPlan? = null, currentStep: Int = 1, experience: String = "", nativeToolFirst: Boolean = false): String {
        val now = SimpleDateFormat("yyyy-MM-dd EEEE HH:mm", Locale.CHINA).format(Date())
        val planText = planSection(plan, currentStep)
        val expSection = if (experience.isNotBlank()) "\n【相关历史经验（优先按经验走老路，页面变化时必须重新读屏确认）】\n$experience\n" else ""
        val nativeFirstSection = if (nativeToolFirst) "\n【原生工具优先】这条指令命中了原生工具（如 create_note、set_alarm、send_sms 等）。直接调用对应的原生工具完成，不要走屏幕操作流程。\n" else ""
        return """
            你是运行在用户安卓手机里的智能助理，能直接替用户操作手机。当前时间：$now。设备：${Build.MANUFACTURER} ${Build.MODEL}。
            $nativeFirstSection

            【执行框架】每个任务按这四步走：
            1. 分类：这是纯聊天/知识问答（直接回答，不用工具），还是要操作手机的任务？
            2. 计划：操作类任务按下方计划逐步执行（无计划时自己列3-6步）。
            3. 执行：每步动作后读屏确认；确认无误就连续推进下一步，不要每步都停下来单独汇报，update_step 攒到每2步或完成/失败时才调用一次。
            4. 汇报：全部完成后用简洁中文汇报结果。

            【执行循环（每个动作后都走一遍）】
            - 观察：get_screen_state 读屏 → 当前在哪个应用？目标元素可见吗？
            - 决策：可见 → click_number 按编号点；不可见 → wait_seconds 或 scroll_screen；找不到文字元素 → look_screen 视觉识别 + tap_point。
            - 确认：点击后重新读屏，页面变化是否符合预期？不符合（无反应/跳错页/弹窗）→ press_back 或处理弹窗后重试；符合 → 直接进入下一步（update_step 按节奏攒批调用，不要为它单独停一轮）。
            - 【绝不在错误状态下继续】：身份不对→go_home+open_app；弹窗挡路→先关；目标元素消失→重新读屏再找。

            【屏幕操作规则】
            1. open_app 后必须核对『当前应用』字段与目标一致；不一致立即 go_home 再开一次并重新核对。
            2. 优先用 click_number 按编号点击（最准）；编号清单找不到目标时用 look_screen 视觉识别。
            3. 输入前先点击输入框；输入后确认内容再点发送。
            4. 屏幕熄灭或锁定时先调用 wake_unlock_screen。
            5. 新页面像加载页时 wait_seconds 3 后再读屏。
            6. 广告/弹窗：关闭按钮通常在四角；视频广告的×延迟出现，等5秒再看；跳转其他App的入口绝不点，press_back 返回。
            7. 屏幕边缘红色圆形「停止任务」悬浮气泡（可能只露出一半）是用户手动叫停任务用的助手控件，不是页面内容：绝不要点击它，也不要把它当成目标元素。

            【安全红线】
            - 付款、转账、验证码、输入密码等支付类按钮绝不代替用户点击；"提交订单""确认订单"可以自动点击（不扣钱），但到支付页面必须停下告诉用户"请你手动输入密码完成支付"。
            - 广告要求下载/安装/试玩的入口绝不点击。

            【跨步骤数据记忆】
            某步的执行结果包含后续步骤需要用的数据（如价格、商品名、编号、验证结果）时，立即用 create_note(title="步骤数据", content="具体值") 存下来。到需要用的步骤时先 read_notes 读取，不要凭记忆猜。

            【经验记忆】
            摸清某App操作路径后用 create_note 存"App名+路径+要点"；同类任务先 read_notes 查攻略，按经验直接走老路。
            $expSection
            $planText
            纯聊天或知识问答不需要工具，直接回答。完成后简洁汇报结果。
        """.trimIndent()
    }

    // ========== AutoGLM-Phone 专用手机脑支持 ==========

    data class AglmResult(
        val info: String,
        val finished: Boolean,
        val message: String? = null,
        val settled: Boolean = false
    )

    /** 发给视觉脑的截图最大宽度：太低时小字/图标糊成一片，1280 是清晰度与流量的平衡点 */
    const val VISION_MAX_W = 1280

    /** 截屏并缩放编码base64，返回 {b64, realW, realH}；失败返回null */
    fun screenB64(a: Activity, maxW: Int): JsonObject? {
        if (!CaptureService.isActive()) return null
        val bmp = try { CaptureService.capture() } catch (e: Exception) { null } ?: return null
        val realW = bmp.width
        val realH = bmp.height
        val scaled = if (realW > maxW) {
            val h = (realH.toDouble() * maxW / realW).toInt()
            Bitmap.createScaledBitmap(bmp, maxW, h, true)
        } else bmp
        val bos = java.io.ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 58, bos)
        val b64 = android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP)
        if (scaled !== bmp) scaled.recycle()
        bmp.recycle()
        return JsonObject().apply {
            addProperty("b64", b64)
            addProperty("realW", realW)
            addProperty("realH", realH)
        }
    }

    /** AutoGLM 视觉脑系统提示词：通用任务循环，而不是只针对某类收益任务 */
    fun autoGlmSystemPrompt(): String {
        val now = SimpleDateFormat("yyyy年MM月dd日", Locale.CHINA).format(Date())
        val wd = arrayOf("星期一","星期二","星期三","星期四","星期五","星期六","星期日")[(SimpleDateFormat("u", Locale.CHINA).format(Date()).toInt() - 1 + 7) % 7]
        return "今天的日期是: $now $wd\n" +
            "你是一个运行在安卓手机上的页面操作智能体。用户只给出目标；你负责推断大致流程，进入合适应用，然后逐页观察和操作。\n" +
            "每一轮都按这个固定循环思考：1) 现在在哪个应用和页面；2) 浏览页面内容并判断目标是否可见；3) 判断页面能否继续下滑；4) 目标可见就点击/输入，目标不可见且能下滑就先滑动查看完整内容；5) 进入新页面后重新观察，绝不沿用旧结论。\n" +
            "你必须严格按照要求输出以下格式：\n<think>{think}</think>\n<answer>{action}</answer>\n\n" +
            "其中：\n- {think} 用中文写清楚你的观察和判断：当前是什么页面、目标元素在哪、为什么选这个入口、下一步做什么。不超过120个字。\n- {action} 是本次执行的唯一操作指令，必须严格遵循下方定义的指令格式。\n\n" +
            "操作指令及其作用如下：\n" +
            "- do(action=\"Launch\", app=\"xxx\")  启动目标app。\n" +
            "- do(action=\"Tap\", element=[x,y])  点击屏幕上的特定点。坐标从左上角(0,0)到右下角(999,999)。\n" +
            "- do(action=\"Tap\", element=[x,y], message=\"重要操作\")  点击涉及财产、支付、隐私等敏感按钮时触发。\n" +
            "- do(action=\"Type\", text=\"xxx\")  在当前聚焦的输入框中输入文本（会自动清除原有文本）。使用前先点击输入框。\n" +
            "- do(action=\"Swipe\", start=[x1,y1], end=[x2,y2])  从起始点拖动到结束点，可滚动内容、翻页或调节滚轮/选择器。\n" +
            "- do(action=\"Long Press\", element=[x,y])  长按。\n" +
            "- do(action=\"Double Tap\", element=[x,y])  双击。\n" +
            "- do(action=\"Back\")  返回上一屏或关闭弹窗。\n" +
            "- do(action=\"Home\")  回到桌面。\n" +
            "- do(action=\"Wait\", duration=\"x seconds\")  等待页面加载。\n" +
            "- do(action=\"Take_over\", message=\"xxx\")  登录/验证码等需要用户协助时触发。\n" +
            "- finish(message=\"xxx\")  结束任务，message是给用户的结果说明。\n\n" +
            "必须遵循的规则：\n" +
            "1. 第一轮先从任务推断目标应用和粗略路径（最多3-6步），不要问用户；任务开始时允许并应当 Launch 与任务直接相关的目标应用，这是正常导航。进入目标应用后，才按第7条严格防止误跳转；目标不明确时优先从桌面找官方应用。\n" +
            "2. 默认每次点击、输入、滑动、返回后都会进入下一轮；必须把下一轮截图/页面内容当作新页面重新观察，不能沿用旧坐标或旧结论（同页批量例外见第17条）。『当前应用/页面内容/关键元素』是系统实时读取的权威状态；如果它和截图看起来冲突，以实时读取为准，必要时 Wait 后重新观察。\n" +
            "3. 优先点击文字、图标说明或页面概要中明确匹配目标的可点击元素；无文字按钮要结合截图位置判断。\n" +
            "4. 输入前先点击输入框，确认聚焦后 Type；需要确认/搜索/发送时，观察新页面再点击对应按钮。\n" +
            "5. 页面加载中、看视频或等倒计时时用 Wait；连续 Wait 三次仍无变化就重新观察或返回重试。\n" +
            "6. 【弹窗优先】每个新页面先检查弹窗。添加至桌面、添加组件、安装、下载、开屏广告、公告、更新提示都属于阻塞性弹窗：优先点取消/关闭/跳过/以后再说/我知道了；绝不点添加、安装、去下载、立即更新。关闭后重新读屏再操作。\n" +
            "6.1 【禁碰系统开关】绝不从屏幕顶部下拉通知栏/快捷中心，绝不点击里面的任何开关（自动旋转、手电筒、省电模式等）；滑动一律从屏幕中部开始。\n" +
            "6.2 【助手悬浮气泡】截图里屏幕边缘的红色圆形「停止任务」悬浮气泡（可能只露出一半）是用户手动叫停任务用的助手控件，不属于任何App页面：绝不点击它、不绕开它，也不把它当作页面元素或完成证据。\n" +
            "7. 【跳转判定】点击前判断入口是否可能离开目标应用。明确的目标应用原生入口可以点击；含下载、安装、去下载、试玩、浏览器、第三方、外部、其他App、跳转等含义的入口不要点。看不准时不要点，改选明确匹配任务的原生入口；若已经误入其他应用，先 Back 返回，回不去就 Home 后重新 Launch 目标应用。\n" +
            "8. 【下滑优先规则】每个新页面先判断目标是否已可见。目标可见就直接操作；目标不可见且页面有列表/信息流/被截断内容/可滑动区域时，先 Swipe 从下往上查看完整内容，每次滑动后重新读屏判断。连续滑动2-3次页面内容不变就停止滑动，改点可见入口或返回。\n" +
            "8.1 【遮挡处理】被悬浮挂件挡住的目标一律按「不可见」处理。宝箱、红包、金币浮标、道具、悬浮球等挂件盖住入口时：先 Swipe 从屏幕中部滑动半屏让页面滚动，再重新观察；若挂件本身与赚钱任务相关（开宝箱/领金币/看广告得奖励），可先点挂件领完奖励再返回；也可从「我/钱包/任务中心」等其他入口绕行。绝不反复点击被挡住的同一位置。\n" +
            "8.2 【滚轮/选择器通用规则】遇到时间、日期、数字、选项滚轮时，先从最新截图和关键元素里独立识别每一列/每一项的控件中心。Swipe 必须以目标控件自身的 X 坐标为准，上下小幅滑动；绝不跨到相邻列、页面中部或别的控件。每次滑动后重新读屏确认当前值；方向错了下一轮反向，改到与用户要求的精确值完全一致前不要点击保存/完成/确定。用户说「X点/X时」但没有指定分钟时，目标分钟是00。\n" +
            "10. 不要在首屏目标不可见时乱点猜测入口；也不要在任务入口已经可见时继续无意义滑动。动态视频/信息流不算页面推进，同一坐标绝不能反复点。\n" +
            "11. 涉及登录、验证码、付款、转账、身份信息的敏感步骤不要代替用户确认，输出 Take_over 并说明需要手动完成什么。提交订单/确认订单可以自动点击（不扣钱），到支付页面再 Take_over。\n" +
            "12. 每步之后检查页面是否按预期变化；没有变化就换元素、换入口、Back 或等待，绝不允许连续三次点同一个无效位置。\n" +
            "13. 输出动作前自查：是否已经浏览当前页？是否能下滑但还没看？这个动作是否可能离开目标应用？是让任务更接近目标，还是只是在重复？\n" +
            "14. 任何时候都必须输出动作：即使等待加载、播放或倒计时，也要用 do(action=\"Wait\", duration=\"x seconds\") 表示，绝不输出纯画面描述。\n" +
            "15. 【完成判定】只到达目标页面不等于完成任务；必须执行目标要求的原生操作并确认结果。付款、转账、验证码等敏感步骤要 Take_over 交给用户。只有最终目标状态已达成、无法继续、或连续多次换路径仍失败时，才用 finish(message=\"简洁结果\") 结束。\n" +
            "15.2 【完成证据】判定完成的唯一标准：页面上出现与任务目标直接对应的完成状态——余额/金币数字确实比操作前增加、任务列表项变为已完成、明确的「领取成功/已到账」提示。到达某个页面、弹出弹窗、看到活动banner、或页面上本来就显示的余额数字，都只是背景信息，绝不是完成证据。点击悬浮挂件后弹出的弹窗/广告是中间步骤，更不是完成。输出 finish 前，必须在 think 里先引用具体的完成证据；引用不出来就继续干活。\n" +
            "15.1 【奖励完成证据】金币、积分、签到、奖励任务只有看到“签到成功”“已签到”“领取成功”“已到账”“明日签到”等明确证据才能 finish；“任务未完成”“不能领取”“倒计时”“mm:ss计时”都不是完成证据，必须 Wait 并等待进度结束后领取。\n" +
            "16. 【只报告可见事实】最终回复只能引用当前或本轮页面实际显示的值；看不到就说明没有找到并继续查找。不要把定制系统版本、营销版本或猜测值当作 Android 版本；页面没有目标字段时先滑动或进入明确对应详情页。\n" +
            "17. 【批量加速】只有当同一页面上的连续动作彼此不依赖新页面信息时，才可以在同一个 <answer> 中用换行输出 2-4 个动作。涉及 Launch、Take_over、重要操作、支付或不确定状态时只能输出一个动作。\n" +
            "18. 【循环型任务】看广告/看视频/刷内容类赚钱任务通常要重复多轮，完成一轮绝不等于任务完成。每看完一段视频或广告，按固定序列进入下一轮：① 关闭视频层——找 X/跳过/关闭按钮，找不到就 Back 退出；② 处理结算弹窗——点「开心收下/知道了/领取」把奖励拿到手；③ 回到任务页，再次点击同一任务入口开始下一轮。终止条件（满足其一才 finish）：入口显示今日已达上限/明日再来/次数用完；连续两轮奖励数字没有增加；或系统轮次上限。finish 的 message 里要写清本轮累计赚了多少。\n" +
            "19. 【全屏广告关闭模式】全屏视频/广告的关闭按钮几乎总是一个小 X，位于屏幕左上角或右上角，且经常要等视频倒计时结束才出现。视频播完后：1) 用截图仔细检查左上角和右上角，X 通常很小，靠边必有按钮；2) 读屏清单里找含「关闭/跳过」或 id 含 close/skip 的元素；3) 一时找不到就 Wait 2-3 秒再看（X 常延迟出现）；4) 还没有就 Back 退出。绝不在视频中央区域点击——那会误开广告落地页。\n" +
            "19.1 【广告内容免疫】广告页面和广告落地页上，除倒计时区域和关闭控件外的任何内容都绝不能点击：商品图、品牌文字（如美团/京东/拼多多）、下载按钮、「去完成任务/了解详情/立即抢购」类 CTA、推荐信息流，全都是广告主的引流陷阱，点了会被拉出目标应用、前功尽弃。广告结算/退出弹窗上要退出就找「放弃奖励/放弃/以后再说/我知道了/关闭」或 X；「继续观看」只在该你想补完倒计时领奖时才点。\n" +
            "19.2 【禁用旧坐标】页面切换后的每一轮截图都是新页面：绝不能沿用上一轮的点击坐标。每次 Tap 前必须在当前截图上重新找到目标的新位置。\n" +
            "20. 【刷视频≠做任务】用户说「刷视频/看视频/刷一会儿」指的是打开应用的正常视频信息流，直接上下滑动观看即可；极速版类应用的观看奖励会在普通刷视频时自动累积，不需要进任务中心。只有用户明确提到「看广告/去赚钱/做任务/签到」时才进入任务中心类页面；用户没提赚钱相关字眼时，不要主动点击任何金币、红包、宝箱、签到或任务入口。\n" +
            "20.1 【开放任务永不停】用户说「刷视频/看视频/浏览/逛逛/刷内容」这类没有明确数量或时间目标的任务时：你绝对不能调用 finish，必须无限循环执行——Swipe 上滑一段视频 → Wait 2-3秒 → 继续 Swipe，一直重复。唯一的停止方式是：① 用户点了「停止任务」气泡；② 系统达到最大轮次自动终止。每轮 Swipe 后都算继续干活，不要在 think 里写「已完成」。\n" +
            "21. 【入口找不到的系统排查】当前页面确实没有目标入口时，按固定顺序排查，每个候选只尝试一次：① 底部导航各标签页，优先[我/我的]（赚钱/签到/钱包/金币类入口几乎总在这一页，钱包常在头像附近或右上角菜单里）；② 页面右上角悬挂图标（红包/金币/宝箱类挂件）；③ 首页活动弹窗里的活动入口。全部找不到就 finish 如实说明「该应用未找到目标入口及已尝试的位置」。绝不重复尝试已确认无效的位置，绝不在视频流里乱点猜坐标。\n"
    }

    /** 从一个 answer 中拆出多个可独立执行的动作；无法拆分时返回原始内容。 */
    fun parseAutoGlmActions(answer: String): List<String> {
        val raw = answer.trim()
        if (raw.isBlank()) return emptyList()
        val starts = Regex("(?s)(?:do\\s*\\(|finish\\s*\\()")
            .findAll(raw)
            .map { it.range.first }
            .toList()
        if (starts.size <= 1) return listOf(raw)

        val actions = ArrayList<String>()
        for ((index, start) in starts.withIndex()) {
            val end = if (index + 1 < starts.size) starts[index + 1] else raw.length
            val chunk = raw.substring(start, end).trim().trimEnd(';', ',', '\n', '\r', ' ')
            val close = findClosingParen(chunk, 0)
            val clean = if (close >= 0) chunk.substring(0, close + 1) else chunk
            if (clean.startsWith("finish") || clean.startsWith("do(")) actions.add(clean)
        }
        return if (actions.size > 1) actions else listOf(raw)
    }

    private fun findClosingParen(text: String, start: Int): Int {
        var depth = 0
        var inQuote = false
        var escaped = false
        for (index in start until text.length) {
            val ch = text[index]
            if (inQuote) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inQuote = false
                }
            } else {
                when (ch) {
                    '"' -> inQuote = true
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) return index
                    }
                }
            }
        }
        return -1
    }

    private fun batchActionAllowed(action: String): Boolean {
        if (action.contains("Launch") || action.contains("Take_over") ||
            action.contains("message=\"重要操作\"")
        ) return false
        return Regex("action=\"(Tap|Type|Swipe|Back|Home|Wait|Long Press|Double Tap)\"")
            .containsMatchIn(action)
    }

    /** 动作后不固定死等：页面指纹变化并稳定后立即返回，最多等一个上限。 */
    fun waitForActionSettle(action: String, beforeFingerprint: String?) {
        if (action.contains("Wait")) return
        val minMs = when {
            action.contains("Launch") -> 300L
            action.contains("Type") -> 120L
            action.contains("Tap") -> 180L
            action.contains("Swipe") -> 140L
            action.contains("Back") || action.contains("Home") -> 160L
            else -> 120L
        }
        val maxMs = when {
            action.contains("Launch") -> 1200L
            action.contains("Tap") -> 900L
            action.contains("Back") || action.contains("Home") -> 700L
            action.contains("Swipe") -> 600L
            else -> 320L
        }
        // 统一用 System.nanoTime 计时（单调时钟，专用于测耗时）。
        // 注意不能和 SystemClock.elapsedRealtime 混用——两者原点不同，旧代码正是混用导致
        // "页面稳定即提前返回"永远不生效；也不能只依赖 elapsedRealtime——单测环境它被 mock 成 0 会死循环
        val start = System.nanoTime() / 1_000_000
        var stable = 0
        while (System.nanoTime() / 1_000_000 - start < maxMs) {
            Thread.sleep(60)
            val elapsed = System.nanoTime() / 1_000_000 - start
            if (elapsed < minMs) continue
            if (action.contains("Type")) return
            val nowFingerprint = AssistantAccessibilityService.instance?.currentPageFingerprint()?.first
            if (beforeFingerprint.isNullOrBlank() || nowFingerprint.isNullOrBlank()) return
            if (nowFingerprint != beforeFingerprint) {
                stable++
                if (stable >= 2) return
            } else {
                stable = 0
            }
        }
    }

    /**
     * 协议违规后的纠正提示。
     * 实测（真机 2026-10）：模型有时会复读上下文里的占位/提示文字（如"旧页面内容已丢弃"）
     * 而不是输出动作，常规提醒挡不住，需要更直白的针对性纠正。
     */
    fun protocolRetryNote(raw: String): String {
        val t = raw.replace(Regex("\\s+"), "")
        val echoedPlaceholder = t.contains("旧页面内容已丢弃") ||
            t.contains("历史已省略") ||
            t.contains("只根据最新页面决策")
        return if (echoedPlaceholder || t.length <= 6) {
            "\n⚠️ 上一轮你复读了系统提示文字，属于无效输出。禁止输出任何方括号/括号提示语、禁止解释页面。" +
                "只输出一个动作：先<think>一句话</think>，再<answer>do(action=\"Tap\", element=[x,y])</answer>" +
                "或<answer>finish(message=\"结果\")</answer>。"
        } else {
            "\n⚠️ 上一轮输出无效：必须输出 <think>...</think> 和 <answer>do(...)</answer>，不要解释页面或输出其他文字。当前截图和屏幕信息就是最新页面。"
        }
    }

    /** 防卡死：连续 Wait 且读屏无变化的纠正轮次（达到后注入强纠正提示） */
    const val WAIT_STALL_CORRECT_AT = 4

    /** 防卡死：连续 Wait 且读屏无变化的中止轮次（到达后自动结束任务，防止烧到80轮上限） */
    const val WAIT_STALL_ABORT_AT = 8

    /** 是否应该冷启动目标App：恢复类指令（继续/接着）除外 */
    fun shouldColdStart(userText: String): Boolean {
        return !Regex("继续|接着|上次的|resume", RegexOption.IGNORE_CASE).containsMatchIn(userText)
    }

    /**
     * 页面指纹归一化：剥离数字与多余空白。
     * 真机教训：抖音视频流的评论数/计时器每隔几轮跳动一次（"评论182"→"评论183"），
     * 原始文本哈希把它当成"页面变了"，冻结判定和防空转计数全部失效。
     */
    fun fingerprint(screenText: String): String {
        return screenText.replace(Regex("\\d+"), "").replace(Regex("\\s+"), " ").trim()
    }

    /**
     * think-动作一致性检查：思考说要点底部导航[我/我的]，但给的协议坐标明显不在导航栏。
     * 真机教训：视觉脑在深色信息流上策略对、坐标错——think写"先点我"，Tap却落在[899,912]等
     * 中上部位置。底部导航栏在协议坐标y约930-990，y<850即明显不在导航栏。
     * 返回true=应拦截本次点击并注入坐标纠正（不能死拦，调用方要限制连续拦截次数）。
     */
    fun thinkActNavMismatch(answer: String, tapX: Int?, tapY: Int?): Boolean {
        if (tapY == null || tapY !in 1..848) return false
        val navIntent = answer.contains("底部导航") || answer.contains("导航栏") ||
            Regex("(点击|进入|切到|切换到|回到)[「\"']?我[」\"'，。;；\\s页]").containsMatchIn(answer) ||
            Regex("(点击|进入|切到|切换到|回到)[「\"']?我的[」\"'，。;；\\s页]").containsMatchIn(answer)
        return navIntent
    }

    /** 连续 Wait 卡死的纠正提示 */
    fun waitStallCorrectNote(count: Int): String =
        "⚠️ 系统提示：页面内容已连续 $count 轮完全相同，而你在持续 Wait。视频/进度大概率已结束，" +
            "或当前页是读屏不可见的自绘层（截图和读屏可能不一致）。请改用 Back 返回上一页，" +
            "或按截图位置点击关闭/领取/跳过按钮。禁止再输出 Wait。"

    /** 连续 Wait 卡死的中止文案 */
    val WAIT_STALL_ABORT_MESSAGE: String
        get() = "页面连续多轮无变化且持续等待，防卡死保护已自动停止任务。" +
            "此页面可能读屏不可用（视频/自绘层），建议手动完成该步后重新下发任务。"

    /**
     * 无进展熔断阈值：连续 N 轮页面文本无任何变化（无论动作是点击还是等待）即中止。
     * 真机教训：抖音视频层上模型换着坐标点击绕过了重复动作检测，烧到80轮上限。
     */
    const val PROGRESS_STALL_ABORT_AT = 16

    /** 无进展熔断的中止文案 */
    val PROGRESS_STALL_ABORT_MESSAGE: String
        get() = "已连续${PROGRESS_STALL_ABORT_AT}轮页面毫无变化，防卡死保护自动停止任务。" +
            "本页可能读屏不可用或入口不在当前页面，建议手动操作该步骤，或换个说法描述任务。"

    /** 解析并执行 AutoGLM 动作（坐标0-999 → 真实像素）；a 允许为 null（单元测试纯逻辑模式用） */
    fun execAutoGLM(
        a: Activity?,
        answer: String,
        realW: Int,
        realH: Int,
        userTask: String = "",
        screenDump: String? = null,
        allowedApp: Pair<String, String>? = null
    ): AglmResult {
        val actions = parseAutoGlmActions(answer)
        if (actions.isEmpty()) return execSingleAutoGLM(a, answer, realW, realH, userTask, screenDump)
        if (actions.size == 1) {
            return execSingleAutoGLM(a, actions.first(), realW, realH, userTask, screenDump, allowedApp)
        }
        if (!actions.all(::batchActionAllowed)) {
            return execSingleAutoGLM(a, actions.first(), realW, realH, userTask, screenDump)
        }

        val infos = ArrayList<String>()
        for ((index, action) in actions.withIndex()) {
            val beforeFingerprint = AssistantAccessibilityService.instance?.currentPageFingerprint()?.first
            val result = execSingleAutoGLM(a, action, realW, realH, userTask, screenDump, allowedApp)
            infos.add("${index + 1}/${actions.size}: ${result.info}")
            if (result.finished) return AglmResult(infos.joinToString("\n"), true, result.message, settled = true)
            if (result.message == "__PARSE_FAIL__" || result.message == "__JUMP_BLOCK__" ||
                result.message == "__COMMERCE_BLOCK__"
            ) {
                return AglmResult(infos.joinToString("\n"), false, result.message, settled = true)
            }
            waitForActionSettle(action, beforeFingerprint)
        }
        return AglmResult(infos.joinToString("\n"), false, settled = true)
    }

    private fun execSingleAutoGLM(
        a: Activity?,
        answer: String,
        realW: Int,
        realH: Int,
        userTask: String = "",
        screenDump: String? = null,
        allowedApp: Pair<String, String>? = null
    ): AglmResult {
        val svc = AssistantAccessibilityService.instance
        fun norm(v: Int, total: Int) = ((v.coerceIn(0, 999) / 999.0) * total)
        fun str(k: String): String? = Regex("$k=\"((?:[^\"\\\\]|\\\\.)*)\"").find(answer)?.groupValues?.get(1)
        fun pt(k: String): Pair<Int, Int>? = Regex("$k=\\[(-?\\d+)\\s*,\\s*(-?\\d+)\\]").find(answer)?.let {
            Pair(it.groupValues[1].toInt(), it.groupValues[2].toInt())
        }
        if (answer.trimStart().startsWith("finish", ignoreCase = true)) {
            val quoted = answer.indexOf('"')
            val message = if (quoted >= 0) {
                answer.substring(quoted + 1)
                    .trim()
                    .removeSuffix(")")
                    .removeSuffix("\"")
                    .trim()
                .ifBlank { "任务结束" }
            } else "任务结束"
            return AglmResult("任务结束", true, message)
        }
        val m = Regex("do\\(action=\"([^\"]+)\"(.*)", RegexOption.DOT_MATCHES_ALL).find(answer)
            ?: return AglmResult("无法解析动作: ${answer.take(80)}", false, "__PARSE_FAIL__")
        val act = m.groupValues[1].trim()
        return when (act) {
            "Launch" -> {
                val appName = str("app") ?: ""
                if (a == null) {
                    AglmResult("无界面上下文，无法启动应用（单元测试环境）", false)
                } else if (!confirmLaunch(a, appName, userTask, allowedApp)) {
                    AglmResult("🛑 已拦截无关应用启动：$appName。请重新判断与任务直接相关的系统或原生入口。", false, "__JUMP_BLOCK__")
                } else {
                    AglmResult(DeviceTools.openApp(a, appName), false)
                }
            }
            "Tap" -> {
                // 修复：缺坐标时直接返回提示。旧代码会先走进 correctTapToScreenElement 的 modelPoint!! 抛异常，
                // 导致整个 AutoGLM 任务中止（视觉模型偶尔输出不带坐标的 Tap 时必崩）
                val p = pt("element") ?: return AglmResult("Tap缺坐标", false)
                if (isTextSelectionToolbar(screenDump)) {
                    return AglmResult(
                        if (svc?.pressBack() == true) "已退出文本选择工具栏" else DeviceTools.NO_A11Y,
                        false
                    )
                }
                val corrected = correctTapToScreenElement(p, userTask, screenDump)
                val correctedLabel = if (screenDump.isNullOrBlank()) "" else labelAtDumpPoint(screenDump, corrected)
                val nearbyLabel = listOf(
                    svc?.labelAtNormalized(p.first, p.second).orEmpty(),
                    correctedLabel
                ).filter { it.isNotBlank() }.joinToString(" / ")
                val targetAppMatched = allowedApp?.let { app ->
                    val normalizedLabel = app.first.replace(Regex("\\s+"), "")
                    val normalizedLabelAtPoint = nearbyLabel.replace(Regex("\\s+"), "")
                    normalizedLabelAtPoint == normalizedLabel ||
                        normalizedLabelAtPoint.contains(normalizedLabel) ||
                        normalizedLabel.contains(normalizedLabelAtPoint)
                } == true
                val jumpPattern = Regex(
                    "下载|安装|试玩|第三方|外部|跳转|网盘",
                    RegexOption.IGNORE_CASE
                )
                val normalizedPage = screenDump.orEmpty().replace(Regex("\\s+"), "")
                if (PageGuard.isUnintendedLiveRoom(userTask, normalizedPage)) {
                    val exitLabel = PageGuard.liveRoomExitLabel(screenDump.orEmpty())
                    val info = when {
                        exitLabel != null && svc?.clickText(exitLabel)?.startsWith("已") == true ->
                            "已点击「$exitLabel」退出直播间"
                        svc?.pressBack() == true -> "已按返回退出直播间"
                        else -> DeviceTools.NO_A11Y
                    }
                    android.util.Log.i("AIButler", "Blocked Tap in unintended live room: $exitLabel")
                    return AglmResult(info, false)
                }
                val isCommercePage = Regex(
                    "立即抢购|立即购买|去支付|提交订单|确认订单|马上抢|单件包邮|拼单|加入购物车|购买数量|商品详情|已选.*规格",
                    RegexOption.IGNORE_CASE
                ).containsMatchIn(normalizedPage)
                val isNonShoppingTask = !Regex("下单|购买|支付|抢购|购物|买").containsMatchIn(userTask)
                if (str("message") == "重要操作") AglmResult("⚠️ 敏感操作已跳过（涉及支付/隐私，请你手动处理）", false)
                else if (isCommercePage && isNonShoppingTask) {
                    android.util.Log.i("AIButler", "Blocked Tap on commerce page")
                    AglmResult(
                        "🛑 已拦截商品/下单页点击。当前页面疑似会导向下单或支付。",
                        false,
                        "__COMMERCE_BLOCK__"
                    )
                }
                else if (!targetAppMatched && jumpPattern.containsMatchIn(nearbyLabel)) {
                    AglmResult("🛑 已拦截疑似跳转其他应用：${nearbyLabel.take(100)}", false, "__JUMP_BLOCK__")
                }
                else if (PageGuard.isUnsafeRewardTap(userTask, nearbyLabel)) {
                    android.util.Log.i("AIButler", "Blocked unsafe reward tap: $nearbyLabel")
                    AglmResult(
                        "🛑 已拦截奖励任务里的高风险入口：${nearbyLabel.take(100)}",
                        false,
                        "__JUMP_BLOCK__"
                    )
                }
                else if (PageGuard.isForbiddenPayment(userTask, normalizedPage)) {
                    android.util.Log.i("AIButler", "Blocked Tap on payment/order page")
                    AglmResult(
                        "🛑 已拦截商品确认/支付页点击。当前页面疑似会花钱。",
                        false,
                        "__COMMERCE_BLOCK__"
                    )
                }
                else if (Regex("同意并使用|同意全部|同意并继续|接受全部|同意.?cookie", RegexOption.IGNORE_CASE)
                    .containsMatchIn(nearbyLabel)
                ) {
                    android.util.Log.i("AIButler", "Blocked Tap on privacy consent button: $nearbyLabel")
                    AglmResult("🛑 已拦截隐私条款同意按钮，需要用户自行确认是否同意。", false, "__PRIVACY_BLOCK__")
                }
                else {
                    val targetX = norm(corrected.first, realW).toFloat()
                    val targetY = norm(corrected.second, realH).toFloat()
                    svc?.showActionPreview(targetX, targetY, nearbyLabel)
                    AglmResult(
                    if (svc?.tap(targetX, targetY) == true)
                        "已点击屏幕(${corrected.first},${corrected.second})" +
                            if (correctedLabel.isBlank()) "" else "→${correctedLabel}"
                    else DeviceTools.NO_A11Y, false)
                    }
            }
            "Type", "Type_Name" -> {
                val t = str("text") ?: ""
                AglmResult(svc?.inputText(t) ?: DeviceTools.NO_A11Y, false)
            }
            "Swipe" -> {
                val s = pt("start"); val e = pt("end")
                if (s == null || e == null) AglmResult("Swipe参数缺失", false)
                else AglmResult(
                    if (svc?.swipePts(norm(s.first, realW).toFloat(), norm(s.second, realH).toFloat(),
                            norm(e.first, realW).toFloat(), norm(e.second, realH).toFloat()) == true)
                        "已滑动(${s.first},${s.second})→(${e.first},${e.second})" else DeviceTools.NO_A11Y, false)
            }
            "Back" -> {
                val sequenceTarget = if (screenDump.isNullOrBlank()) null else {
                    val clickable = Regex(
                        "可点击:\\s*(.+?)\\s*\\[(\\d+),(\\d+)\\]\\s*$",
                        RegexOption.MULTILINE
                    ).findAll(screenDump).mapNotNull { match ->
                        val label = match.groupValues[1].trim()
                        val x = match.groupValues[2].toIntOrNull()
                        val y = match.groupValues[3].toIntOrNull()
                        if (label.isBlank() || x == null || y == null) null else Triple(label, x, y)
                    }.toList()
                    arithmeticSequenceTarget(userTask, clickable, screenDump)
                }
                val point = sequenceTarget
                if (point == null) AglmResult(if (svc?.pressBack() == true) "已按返回" else DeviceTools.NO_A11Y, false)
                else AglmResult(
                    if (svc?.tap(norm(point.second, realW).toFloat(), norm(point.third, realH).toFloat()) == true)
                        "已按任务序列点击${point.first}" else DeviceTools.NO_A11Y, false)
            }
            "Home" -> AglmResult(if (svc?.home() == true) "已回桌面" else DeviceTools.NO_A11Y, false)
            "Wait" -> {
                val d = (str("duration") ?: "2").filter { it.isDigit() }.toIntOrNull()?.coerceIn(1, 60) ?: 2
                Thread.sleep(d * 1000L)
                AglmResult("已等待${d}秒", false)
            }
            "Long Press" -> {
                val p = pt("element")
                if (p == null) AglmResult("缺坐标", false)
                else AglmResult(if (svc?.longPress(norm(p.first, realW).toFloat(), norm(p.second, realH).toFloat()) == true) "已长按" else DeviceTools.NO_A11Y, false)
            }
            "Double Tap" -> {
                val p = pt("element")
                if (p == null) AglmResult("缺坐标", false)
                else {
                    svc?.tap(norm(p.first, realW).toFloat(), norm(p.second, realH).toFloat())
                    Thread.sleep(100)
                    svc?.tap(norm(p.first, realW).toFloat(), norm(p.second, realH).toFloat())
                    AglmResult("已双击", false)
                }
            }
            "Take_over", "Interact" -> AglmResult("需要用户协助: ${str("message") ?: ""}", true, "⏸️ 需要你接管（登录/验证/选择）：${str("message") ?: "请手动完成后说继续"}")
            "Note" -> AglmResult("已记录页面内容", false)
            "Call_API" -> AglmResult("已分析当前内容", false)
            else -> AglmResult("未知动作: $act", false)
        }
    }

    /** 用读屏到的可点击文字校正视觉模型坐标，避免“看对了入口、点错了位置”。 */
    private fun correctTapToScreenElement(
        modelPoint: Pair<Int, Int>?,
        userTask: String,
        screenDump: String?
    ): Pair<Int, Int> {
        if (modelPoint == null || userTask.isBlank() || screenDump.isNullOrBlank()) return modelPoint!!
        val candidates = Regex(
            "可点击:\\s*(.+?)\\s*\\[(\\d+),(\\d+)\\]\\s*$",
            RegexOption.MULTILINE
        ).findAll(screenDump).mapNotNull { match ->
            val label = match.groupValues[1].trim()
            val x = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            val y = match.groupValues[3].toIntOrNull() ?: return@mapNotNull null
            if (label.isBlank()) return@mapNotNull null
            Triple(label, x, y)
        }.toList()
        if (candidates.isEmpty()) return modelPoint

        val cancelButton = candidates
            .filter {
                val label = it.first.replace(Regex("\\s+"), "")
                label == "取消" || label == "关闭" || label == "跳过" ||
                    label == "以后再说" || label == "我知道了"
            }
            .minByOrNull {
                (it.second - modelPoint.first) * (it.second - modelPoint.first) +
                    (it.third - modelPoint.second) * (it.third - modelPoint.second)
            }
        if (cancelButton != null) {
            val cancelDistance = (cancelButton.second - modelPoint.first) * (cancelButton.second - modelPoint.first) +
                (cancelButton.third - modelPoint.second) * (cancelButton.third - modelPoint.second)
            if (cancelDistance <= 20000) {
                android.util.Log.i("AIButler", "Tap corrected to nearby dialog dismissal: ${cancelButton.first}")
                return Pair(cancelButton.second, cancelButton.third)
            }
        }

        arithmeticSequenceTarget(userTask, candidates, screenDump)?.let { target ->
            android.util.Log.i("AIButler", "Tap corrected by task sequence: " +
                "model=${modelPoint.first},${modelPoint.second} element=${target.second},${target.third} " +
                "label=${target.first}")
            return Pair(target.second, target.third)
        }

        fun normalized(value: String) = value.lowercase().replace(Regex("\\s+"), "")
        val task = normalized(userTask).let { full ->
            Regex("不要|千万别|别点|禁止|不得|避免").find(full)?.range?.first
                ?.let { full.substring(0, it) } ?: full
        }
        val routePhrases = Regex(
            "(?:在|从)([^，。,.？！?!；;\\n]{1,30}?)(?:里|中|内|里面)"
        ).findAll(task).map { it.groupValues[1] }.toList()
        val objectivePhrases = Regex(
            "(?:刷|领取|领|赚|查看|查询|打开|进入|点击|设置|开启|关闭|搜索|发送)([^，。,.？！?!；;\\n]{1,30})"
        ).findAll(task).map { it.groupValues[1] }.toList()
        fun textScore(label: String): Int {
            val normalizedLabel = normalized(label)
            if (normalizedLabel.length < 2) return 0
            val isRouteLabel = routePhrases.any { route -> route.contains(normalizedLabel) }
            val isObjectiveLabel = objectivePhrases.any { objective -> objective.contains(normalizedLabel) }
            if (task.contains(normalizedLabel)) {
                return when {
                    isObjectiveLabel -> 200000 + normalizedLabel.length
                    isRouteLabel -> 60000 + normalizedLabel.length
                    else -> 100000 + normalizedLabel.length
                }
            }
            var best = 0
            for (start in task.indices) {
                var length = 0
                while (start + length < task.length && length < normalizedLabel.length &&
                    task[start + length] == normalizedLabel[length]
                ) {
                    length++
                    if (length >= 2) best = maxOf(best, length)
                }
            }
            return if (best >= 3) best * best * 10 else 0
        }

        val scored = candidates.map { candidate ->
            val distance = (candidate.second - modelPoint.first) * (candidate.second - modelPoint.first) +
                (candidate.third - modelPoint.second) * (candidate.third - modelPoint.second)
            Triple(candidate, textScore(candidate.first), distance)
        }.filter { candidate ->
            candidate.second >= 40 && candidate.third <= 5000 &&
                !PageGuard.isUnsafeRewardTap(userTask, candidate.first.first)
        }
        val target = scored.maxWithOrNull(compareBy({ it.second }, { -it.third }))?.first ?: return modelPoint!!
        android.util.Log.i("AIButler", "Tap corrected: model=${modelPoint.first},${modelPoint.second} " +
            "element=${target.first},${target.second} label=${target.first}")
        return Pair(target.second, target.third)
    }

    private fun isTextSelectionToolbar(screenDump: String?): Boolean {
        if (screenDump.isNullOrBlank()) return false
        val labels = Regex("可点击:\\s*(.+?)\\s*\\[", RegexOption.MULTILINE)
            .findAll(screenDump).map { it.groupValues[1].replace(Regex("\\s+"), "") }.toList()
        return labels.isNotEmpty() && labels.all { it == "全选" || it == "自动填充" || it == "选择" } &&
            screenDump.contains("页面内容:  ")
    }

    private fun labelAtDumpPoint(screenDump: String?, point: Pair<Int, Int>): String {
        if (screenDump.isNullOrBlank()) return ""
        return Regex("可点击:\\s*(.+?)\\s*\\[(\\d+),(\\d+)\\]\\s*$", RegexOption.MULTILINE)
            .findAll(screenDump)
            .filter {
                val distance = (it.groupValues[2].toIntOrNull() ?: 9999).let { x ->
                    (x - point.first) * (x - point.first)
                } + (it.groupValues[3].toIntOrNull() ?: 9999).let { y ->
                    (y - point.second) * (y - point.second)
                }
                distance <= 900
            }
            .minByOrNull {
                (it.groupValues[2].toInt() - point.first) * (it.groupValues[2].toInt() - point.first) +
                    (it.groupValues[3].toInt() - point.second) * (it.groupValues[3].toInt() - point.second)
            }
            ?.groupValues?.get(1).orEmpty()
    }

    /** 对明确的算式任务做输入序列校验，避免视觉模型在数字键上重复错点。 */
    private fun arithmeticSequenceTarget(
        userTask: String,
        candidates: List<Triple<String, Int, Int>>,
        screenDump: String
    ): Triple<String, Int, Int>? {
        if (!screenDump.contains("输入框")) return null
        val expression = Regex(
            "([0-9]+(?:\\.[0-9]+)?)\\s*(?:乘|×|x|\\*)\\s*([0-9]+(?:\\.[0-9]+)?)",
            RegexOption.IGNORE_CASE
        ).find(userTask)?.groupValues ?: return null
        val first = expression[1]
        val second = expression[2]
        val display = Regex("输入框:\\s*(.*?)\\s*\\[", RegexOption.MULTILINE)
            .find(screenDump)?.groupValues?.get(1)?.trim().orEmpty()
            .replace(",", "").replace("，", "").replace(" ", "")
            .replace(Regex("[乘×x*]", RegexOption.IGNORE_CASE), "*")
            .takeUnless { it == "无文字元素" || it == "0" } ?: ""
        if (display.contains("=")) return null

        val displayedNumber = display.toDoubleOrNull()
        val expectedResult = first.toDouble() * second.toDouble()
        if (displayedNumber != null && displayedNumber == expectedResult) return null

        fun choose(labelPredicate: (String) -> Boolean) =
            candidates.firstOrNull { labelPredicate(it.first.replace(Regex("\\s+"), "")) }

        val desired = first + "*" + second + "="
        if (display.isNotEmpty() && !desired.startsWith(display)) {
            return choose { it == "清除" || it.equals("AC", true) || it.equals("C", true) || it == "清空" }
        }
        if (display.length >= desired.length) return null
        val next = desired[display.length]
        return when {
            next.isDigit() -> choose { it == next.toString() }
            next == '*' -> choose { it == "乘" || it == "×" || it == "*" }
            next == '=' -> choose { it == "=" || it == "等于" }
            else -> null
        }
    }

    private fun confirmLaunch(
        a: Activity,
        appName: String,
        userTask: String,
        allowedApp: Pair<String, String>? = null
    ): Boolean {
        if (appName.isBlank() || userTask.isBlank()) return true
        // Bug#1b：先检查设备上是否真的安装了这个应用，不存在就不用浪费 LLM 调用
        val pm = a.packageManager
        val appInstalled = try {
            pm.getLaunchIntentForPackage(
                pm.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
                ).firstOrNull { ri ->
                    try { ri.loadLabel(pm).toString().equals(appName, ignoreCase = true) ||
                        ri.loadLabel(pm).toString().contains(appName) ||
                        appName.contains(ri.loadLabel(pm).toString())
                    } catch (_: Exception) { false }
                }?.activityInfo?.packageName ?: ""
            ) != null
        } catch (_: Exception) { false }
        if (!appInstalled) {
            android.util.Log.i("AIButler", "confirmLaunch: app not installed locally: $appName")
            return false
        }
        // 用户指令里明确提到目标 App 名时，不需要再问 LLM 确认，省 1-3 秒延迟
        val normalizedName = appName.trim().lowercase().replace(Regex("\\s+"), "")
        val normalizedTask = userTask.trim().lowercase().replace(Regex("\\s+"), "")
        if (normalizedTask.contains(normalizedName) && normalizedName.length >= 2) {
            android.util.Log.i("AIButler", "confirmLaunch: app name in user instruction, skip LLM: $appName")
            return true
        }
        allowedApp?.let { app ->
            val requested = appName.trim()
            return requested.equals(app.first, ignoreCase = true) ||
                requested.equals(app.second, ignoreCase = true) ||
                requested.contains(app.first) || app.first.contains(requested)
        }
        DeviceTools.guessApp(a, userTask)?.let { requested ->
            return appName == requested.first || appName.contains(requested.first) || requested.first.contains(appName)
        }
        val messages = JsonArray().apply {
            add(msg(
                "system",
                "你只输出YES或NO。判断拟启动应用是否是完成用户任务的必要且直接相关的系统/原生应用。搜索、闲聊、AI问答、广告、下载、第三方跳转都不能作为替代入口。"
            ))
            add(msg("user", "用户任务：$userTask\n拟启动应用：$appName\n只输出YES或NO。"))
        }
        return try {
            LlmClient.chat(
                Prefs.baseUrl(a), Prefs.apiKey(a), Prefs.model(a), messages,
                JsonArray(), Prefs.fastMode(a)
            ).content.trim().uppercase().startsWith("YES")
        } catch (_: Exception) {
            false
        }
    }

    fun msg(role: String, content: String): JsonObject = JsonObject().apply {
        addProperty("role", role)
        addProperty("content", content)
    }

    fun toolMsg(callId: String, content: String): JsonObject = JsonObject().apply {
        addProperty("role", "tool")
        addProperty("tool_call_id", callId)
        addProperty("content", content)
    }

    private fun onUi(a: Activity, block: () -> String): String {
        var result = ""
        val latch = CountDownLatch(1)
        a.runOnUiThread {
            try { result = block() } catch (e: Exception) { result = "执行失败：${e.message}" }
            finally { latch.countDown() }
        }
        latch.await(10, TimeUnit.SECONDS)
        return result
    }

    /** 判断按钮文字是否涉及支付/验证等敏感操作（技术硬拦截；提交订单不拦截，AI可以点）。
     *  中英文关键词都要覆盖，防止英文 App 的支付按钮漏过拦截。 */
    private fun isSensitiveButton(label: String): Boolean {
        if (label.isBlank()) return false
        return Regex(
            "确认支付|立即支付|去支付|确认付款|立即付款|支付|付款|转账|验证码|输入密码|" +
            "(?i)pay\\s*now|make.{0,4}payment|checkout|confirm.{0,4}payment|" +
            "(?i)enter.{0,4}password|proceed.{0,8}(?:pay|check.?out)|transfer\\s*(?:money|fund)?|" +
            "(?i)verify.{0,4}code|one.?time.(?:pass.?word|code)|otp"
        ).containsMatchIn(label)
    }

    /** 执行大模型要求的工具，返回给大模型看的结果文字 */
    fun execute(
        a: Activity,
        name: String,
        argsJson: String,
        allowedPackage: String? = null
    ): String = try {
        val args = try { JsonParser.parseString(argsJson).asJsonObject } catch (e: Exception) { JsonObject() }
        fun s(k: String): String = args.get(k)?.takeIf { !it.isJsonNull }?.asString ?: ""

        val svc = AssistantAccessibilityService.instance
        when (name) {
            "get_current_time" -> SimpleDateFormat("yyyy-MM-dd EEEE HH:mm:ss", Locale.CHINA).format(Date())
            "list_installed_apps" -> DeviceTools.listApps(a)
            "open_app" -> {
                val requested = DeviceTools.resolveApp(a, s("app_name"))
                when {
                    requested != null && !TaskAppGuard.isAllowed(requested.second, allowedPackage) ->
                        "🛑 已拦截执行期切换到其他应用：${requested.first}。当前任务只允许停留在启动时确定的目标应用。"
                    else -> DeviceTools.openApp(a, s("app_name"))
                }
            }
            "get_screen_state" -> {
                val m = awakeMsg(a)
                if (m != null) m else svc?.dumpScreen() ?: DeviceTools.NO_A11Y
            }
            "click_number" -> {
                val idx = s("index").toIntOrNull() ?: -1
                val label = svc?.elementLabelAt(idx) ?: ""
                if (isSensitiveButton(label)) {
                    "🛑 安全拦截：编号$idx 的元素「${label.take(40)}」涉及付款/下单/验证，已阻止自动点击。请告诉用户手动确认这一步。"
                } else {
                    svc?.clickNumber(idx) ?: DeviceTools.NO_A11Y
                }
            }
            "wake_unlock_screen" -> wakeAndUnlock(a)
            "look_screen" -> {
                val awake = awakeMsg(a)
                if (awake != null) {
                    awake
                } else if (!CaptureService.isActive()) {
                    "屏幕视觉未开启。请告诉用户：点App顶部栏的 👁 按钮（现在是🙈）并允许屏幕采集，然后重试"
                } else {
                    val bmp = CaptureService.capture()
                    if (bmp == null) {
                        "截图失败，请稍后重试"
                    } else {
                        val localHits = try {
                            val service = AssistantAccessibilityService.instance
                            if (service == null) emptyList() else {
                                VisualFeatureLibrary(a).matchScreen(
                                    bmp,
                                    service.currentPackage(),
                                    service.collectClickableRects()
                                )
                            }
                        } catch (_: Exception) {
                            emptyList()
                        }
                        val safeHits = localHits.filterNot { isSensitiveButton(it.label) }
                        if (safeHits.isNotEmpty()) {
                            val details = safeHits.take(8).joinToString("\n") { hit ->
                                "本地样本「${hit.label}」可点击 ${hit.centerX},${hit.centerY}（相似度 ${(hit.score * 100).toInt()}%）"
                            }
                            bmp.recycle()
                            "🖼 本地图形样本命中：\n$details\n\n" +
                                "只把这些高置信度结果当作当前页面事实；如果与用户目标匹配，用 tap_point 点击对应新坐标。"
                        } else {
                        val sw = bmp.width
                        val sh = bmp.height
                        val maxW = 800
                        val scaled = if (sw > maxW) {
                            val h = (sh.toDouble() * maxW / sw).toInt()
                            Bitmap.createScaledBitmap(bmp, maxW, h, true)
                        } else bmp
                        val outW = scaled.width
                        val outH = scaled.height
                        val bos = java.io.ByteArrayOutputStream()
                        scaled.compress(Bitmap.CompressFormat.JPEG, 55, bos)
                        val b64 = android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP)
                        if (scaled !== bmp) scaled.recycle()
                        val prompt = "这是手机屏幕截图，原始分辨率 ${sw}x${sh} 像素，截图已等比缩小到 ${outW}x${outH}。\n用户要完成的目标：${s("goal")}\n请输出：\n第1行：当前页面是什么。\n第2行起：列出屏幕上所有可见的、可能可以点击的元素（包括无文字的图标/按钮），每个一行，格式：说明 | 点击 x,y（坐标用原始分辨率 ${sw}x${sh} 像素）。\n最后一行：建议动作，格式为：点击 x,y 或 滑动 up/down/left/right 或 输入 具体内容 或 已完成 或 无法完成(原因)。\n注意：所有坐标必须换算成原始分辨率 ${sw}x${sh} 的像素坐标，不要给截图上的缩小坐标。看不清的文字要说明不确定。"
                        try {
                            "👁 视觉分析结果：\n" + LlmClient.chatVision(
                                Prefs.baseUrl(a), Prefs.apiKey(a), Prefs.model(a), prompt, b64
                            )
                        } catch (e: Exception) {
                            "视觉识别失败：${e.message}"
                        }
                    }
                    }
                }
            }
            "click_element" -> {
                val target = s("text").trim()
                if (target.isEmpty()) {
                    // 空参数曾会命中 contains("") 恒真，点中屏幕任意第一个可点元素——必须拒绝
                    "text 参数为空，已拒绝点击。请先 get_screen_state 看清目标按钮文字再调用 click_element"
                } else if (isSensitiveButton(target)) {
                    "🛑 安全拦截：「${target.take(40)}」涉及付款/下单/验证，已阻止自动点击。请告诉用户手动确认这一步。"
                } else {
                    svc?.clickText(target) ?: DeviceTools.NO_A11Y
                }
            }
            "tap_point" -> {
                val x = s("x").toFloatOrNull(); val y = s("y").toFloatOrNull()
                if (x == null || y == null) "x/y 参数无效"
                else {
                    val nearby = svc?.labelAtPixel(x.toInt(), y.toInt()) ?: ""
                    if (isSensitiveButton(nearby)) "🛑 安全拦截：坐标($x,$y)附近元素「${nearby.take(40)}」涉及付款/下单/验证，已阻止自动点击。请告诉用户手动确认这一步。"
                    else {
                        svc?.showActionPreview(x, y, nearby)
                        if (svc?.tap(x, y) == true) "已在坐标($x,$y)点击" else DeviceTools.NO_A11Y
                    }
                }
            }
            "input_text" -> svc?.inputText(s("text")) ?: DeviceTools.NO_A11Y
            "press_back" -> if (svc?.pressBack() == true) "已按返回键" else DeviceTools.NO_A11Y
            "wait_seconds" -> {
                val sec = (s("seconds").toIntOrNull() ?: 5).coerceIn(1, 60)
                // 可中断等待：每 300ms 检查一次暂停请求，用户按停止时最多等 300ms 就能响应
                var waited = 0L
                val target = sec * 1000L
                var interrupted = false
                while (waited < target) {
                    if (TaskControlBus.pauseRequested) { interrupted = true; break }
                    val chunk = minOf(300L, target - waited)
                    Thread.sleep(chunk)
                    waited += chunk
                }
                if (interrupted) "已中断等待（用户叫停）" else "已等待${sec}秒"
            }
            "scroll_screen" -> {
                val dir = s("direction").lowercase().trim()
                when (dir) {
                    "up" -> svc?.scroll(false) ?: DeviceTools.NO_A11Y
                    "left" -> svc?.scrollHorizontal(false) ?: DeviceTools.NO_A11Y
                    "right" -> svc?.scrollHorizontal(true) ?: DeviceTools.NO_A11Y
                    else -> svc?.scroll(true) ?: DeviceTools.NO_A11Y
                }
            }
            "go_home" -> if (svc?.home() == true) "已回到桌面" else DeviceTools.NO_A11Y
            "set_alarm" -> {
                val hour = s("hour").toIntOrNull()
                val minute = s("minute").toIntOrNull()
                if (hour == null || minute == null || hour !in 0..23 || minute !in 0..59) {
                    "hour/minute 参数无效（需要 hour 0-23、minute 0-59）。请把目标时间换算成 24 小时制后重试"
                } else onUi(a) { DeviceTools.setAlarm(a, hour, minute, s("label")) }
            }
            "cancel_alarm" -> onUi(a) {
                DeviceTools.cancelAlarm(a, s("hour").toIntOrNull(), s("minute").toIntOrNull(), s("label"))
            }
            "find_contact" -> DeviceTools.findContact(a, s("keyword"))
            "create_calendar_event" -> {
                val title = s("title")
                val startStr = s("start_time")
                if (title.isBlank()) "title 不能为空"
                else {
                    val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
                    val begin = try { fmt.parse(startStr)?.time } catch (_: Exception) { null }
                    if (begin == null) "start_time 格式错误，需要 yyyy-MM-dd HH:mm，例如 2025-01-15 14:00"
                    else {
                        val endStr = s("end_time")
                        val end = if (endStr.isNotBlank()) {
                            try { fmt.parse(endStr)?.time } catch (_: Exception) { null }
                        } else null
                        onUi(a) { DeviceTools.createCalendarEvent(a, title, begin, end ?: (begin + 3600000L), s("description")) }
                    }
                }
            }
            "copy_to_clipboard" -> DeviceTools.copyToClipboard(a, s("text"))
            "read_clipboard" -> DeviceTools.readClipboard(a)
            "send_sms" -> onUi(a) { DeviceTools.sendSms(a, s("contact"), s("message")) }
            "make_call" -> onUi(a) { DeviceTools.makeCall(a, s("contact")) }
            "open_url" -> onUi(a) { DeviceTools.openUrl(a, s("url")) }
            "web_search" -> DeviceTools.webSearch(s("query"))
            "read_web_page" -> DeviceTools.readWebPage(s("url"))
            "read_recent_notifications" -> DeviceTools.readNotifications(s("limit").toIntOrNull() ?: 15)
            "create_note" -> NotesStore.create(a, s("title"), s("content"))
            "read_notes" -> NotesStore.read(a, s("keyword"))
            "update_note" -> {
                val id = s("id").toLongOrNull()
                if (id == null) "id 无效，请先 read_notes 确认编号"
                else NotesStore.update(a, id, s("title"), s("content"))
            }
            "delete_note" -> {
                val id = s("id").toLongOrNull()
                if (id == null) "id 无效，请先 read_notes 确认编号"
                else NotesStore.delete(a, id)
            }
            "update_step" -> "已记录步骤${args.get("step")?.takeIf { !it.isJsonNull }?.asInt ?: "?"}状态：${s("status")}。继续执行下一步。"
            "replan" -> "🔄 重新规划请求已发出（原因：${s("reason")}）。等待系统重新分配计划后继续执行。"
            else -> "未知工具：$name"
        }
    } catch (e: Exception) {
        "工具执行出错：${e.message}"
    }
}
