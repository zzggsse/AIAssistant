package com.aibutler.app

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 刷金币链路的本地回归测试（不需要手机：gradle testDebugUnitTest 或 双击「一键测试.bat」）。
 *
 * 测的是【本地决策与安全层】的真实代码：
 *  - 任务规划 JSON 解析（parsePlan）
 *  - AutoGLM 动作解析与安全拦截（execAutoGLM）：跳转拦截 / 商单拦截 / 敏感操作跳过 / 坏格式容错
 *  - 工具清单与系统提示词的关键约定（update_step 攒批、安全红线等）
 *
 * 视觉模型本身对真实页面的判断只能靠真机多次实测（见「真机刷金币测试记录.md」），本套件不模拟模型。
 */
class AgentLogicTest {

    // ========== parsePlan：任务规划解析 ==========

    @Test
    fun parsePlan_标准screen_task() {
        val plan = AgentEngine.parsePlan(
            """{"task_type":"screen_task","target_app":"淘宝",
               "steps":[{"id":1,"action":"打开淘宝","done_hint":"当前应用是淘宝"},
                        {"id":2,"action":"点搜索框","done_hint":"出现搜索输入框"}],
               "final_check":"出现搜索结果"}"""
        )
        assertNotNull(plan)
        assertEquals("screen_task", plan!!.taskType)
        assertEquals("淘宝", plan.targetApp)
        assertEquals(2, plan.steps.size)
        assertEquals("打开淘宝", plan.steps[0].action)
        assertEquals("出现搜索结果", plan.finalCheck)
    }

    @Test
    fun parsePlan_带markdown代码块包裹() {
        val plan = AgentEngine.parsePlan(
            "```json\n{\"task_type\":\"chat\",\"target_app\":\"\",\"steps\":[],\"final_check\":\"\"}\n```"
        )
        assertNotNull(plan)
        assertEquals("chat", plan!!.taskType)
        assertTrue(plan.steps.isEmpty())
    }

    @Test
    fun parsePlan_前后有废话的JSON也能提取() {
        val plan = AgentEngine.parsePlan(
            "好的，以下是计划：{\"task_type\":\"chat\",\"steps\":[]} 希望有帮助"
        )
        assertNotNull(plan)
        assertEquals("chat", plan!!.taskType)
    }

    @Test
    fun parsePlan_垃圾输入返回null() {
        assertNull(AgentEngine.parsePlan("我不会这个"))
        assertNull(AgentEngine.parsePlan(""))
        assertNull(AgentEngine.parsePlan("{{{不是json"))
    }

    // ========== toolsJson：工具清单结构 ==========

    @Test
    fun toolsJson_工具数量与关键工具齐全() {
        val tools = AgentEngine.toolsJson()
        assertTrue("工具数量异常：${tools.size()}", tools.size() >= 20)
        val names = HashSet<String>()
        for (t in tools) {
            names.add(t.asJsonObject.getAsJsonObject("function").get("name").asString)
        }
        for (must in listOf(
            "get_screen_state", "click_number", "click_element", "look_screen",
            "open_app", "input_text", "wait_seconds", "web_search", "update_step"
        )) {
            assertTrue("缺少关键工具：$must", must in names)
        }
        // 第二次调用应命中缓存（同一个对象）
        assertTrue(AgentEngine.toolsJson() === tools)
    }

    // ========== systemPrompt：关键行为约定不被改动 ==========

    @Test
    fun systemPrompt_包含提速约定与安全红线() {
        val sp = AgentEngine.systemPrompt()
        // 2026-10 提速改动：update_step 攒批汇报，不再每步单独占一个往返
        assertTrue("缺少 update_step 攒批约定", sp.contains("攒批调用") || sp.contains("每2步"))
        assertTrue("缺少安全红线", sp.contains("安全红线"))
        assertTrue("缺少执行循环", sp.contains("执行循环"))
    }

    @Test
    fun autoGlmPrompt_任务开始允许启动目标应用_执行中禁止误跳转() {
        val sp = AgentEngine.autoGlmSystemPrompt()
        assertTrue("缺少任务开始时启动目标应用的约定", sp.contains("任务开始时允许并应当 Launch"))
        assertTrue("缺少执行中防误跳转的约定", sp.contains("点击前判断入口是否可能离开目标应用"))
    }

    @Test
    fun autoGlmPrompt_激励广告倒计时必须等待或恢复() {
        val sp = AgentEngine.autoGlmSystemPrompt()
        assertTrue("缺少激励广告等待要求", sp.contains("倒计时没结束绝不能 Swipe、Back、点×或点下载"))
        assertTrue("缺少继续观看恢复要求", sp.contains("必须先点它恢复计时"))
    }

    @Test
    fun 目标搜索_完整名称提示和通用目标抽取() {
        val prompt = AgentEngine.autoGlmSystemPrompt()
        assertTrue("缺少完整搜索词约定", prompt.contains("Type 必须输入用户说的完整目标名称"))
        assertEquals("我的电脑", AgentEngine.namedActionTarget("打开QQ给“我的电脑”发送：AI助手测试消息"))
        assertEquals("文件传输助手", AgentEngine.namedActionTarget("打开微信给文件传输助手发送：你好"))
        assertEquals("AI助手", AgentEngine.namedActionTarget("打开B站搜索AI助手，然后播放第一个视频"))
        assertEquals("保温杯", AgentEngine.namedActionTarget("打开淘宝搜索保温杯，然后找销量最高的商品"))
    }

    @Test
    fun 发送任务_机械证据链解析() {
        val goal = AgentEngine.parseMessageSendGoal("打开QQ给“我的电脑”发送：AI助手测试消息")
        assertEquals(AgentEngine.MessageSendGoal("我的电脑", "AI助手测试消息"), goal)
        assertEquals("AI助手测试消息", AgentEngine.parseAutoGlmTypeText("do(action=\"Type\", text=\"AI助手测试消息\")"))

        val chatPage = "当前应用: com.any.social\n" +
            "可点击: [6] 发送 {send_btn,Button} [852,511]\n" +
            "输入框: [7] AI助手测试消息 {input,EditText} [499,511]"
        assertTrue(
            AgentEngine.isSendControlTap(
                chatPage,
                "do(action=\"Tap\", element=[852,511])"
            )
        )
        assertFalse(
            AgentEngine.isSendControlTap(
                chatPage,
                "do(action=\"Tap\", element=[499,511])"
            )
        )
        val hiddenSendPage = "当前应用: com.any.social\n" +
            "输入框: [7] AI助手测试消息 {input,EditText} [499,511]"
        assertTrue(
            AgentEngine.isMessageSendTapIntent(
                hiddenSendPage,
                "do(action=\"Tap\", element=[759,866])",
                "消息已完整输入，点击键盘上的发送按钮。"
            )
        )
        assertFalse(
            AgentEngine.isMessageSendTapIntent(
                hiddenSendPage,
                "do(action=\"Tap\", element=[759,866])",
                "输入框看起来已经生效，点击右侧确认图标。"
            )
        )
        assertNull(AgentEngine.parseMessageSendGoal("打开淘宝搜索保温杯，然后找销量最高的商品"))
    }

    @Test
    fun 目标搜索_目标未出现前拦截取消和清空() {
        val searchPage = "当前应用: com.any.social\n" +
            "输入框: [1] mde {edit,EditText} [419,87]\n" +
            "可点击: [2] 清空 {clear,Button} [777,87]\n" +
            "可点击: [3] 取消 {cancel,Button} [906,87]\n" +
            "页面内容: 最近搜索"
        assertTrue(AgentEngine.isSearchInputContext(searchPage))
        assertTrue(AgentEngine.isSearchDismissTap(searchPage, 906, 87))
        assertTrue(AgentEngine.isSearchDismissTap(searchPage, 777, 87))
        // 目标结果出现后允许点结果，也不能把普通页面取消按钮误伤成搜索退出。
        assertFalse(AgentEngine.isSearchDismissTap(searchPage, 499, 300))
        val messagePage = "当前应用: com.any.social\n" +
            "输入框: [1] 说点什么 {edit,EditText} [499,900]\n" +
            "可点击: [2] 取消 {cancel,Button} [100,100]\n" +
            "页面内容: 聊天"
        assertFalse(AgentEngine.isSearchDismissTap(messagePage, 100, 100))
    }

    @Test
    fun 目标搜索_提交后回到发现页必须重输而不是点历史词() {
        val discoveryPage = "当前应用: com.any.search\n" +
            "输入框: [1] ai视频小助理 {edit,EditText} [419,87]\n" +
            "可点击: [2] 搜索 {action_search,TextView} [908,81]\n" +
            "页面内容: bilibili热搜 搜索历史 搜索发现 AI助手 AI助手"
        assertTrue(AgentEngine.isSubmittedSearchDiscovery(discoveryPage, "AI助手"))
        assertTrue(AgentEngine.isSearchSubmitTap(discoveryPage, 908, 81))

        val resultPage = "当前应用: com.any.search\n" +
            "输入框: [1] AI助手 {edit,EditText} [419,87]\n" +
            "可点击: [2] 搜索 {action_search,TextView} [908,81]\n" +
            "可点击: [3] AI助手 视频教程 {,ViewGroup} [499,440]\n" +
            "页面内容: 搜索结果"
        assertFalse(AgentEngine.isSubmittedSearchDiscovery(resultPage, "AI助手"))
    }

    @Test
    fun 目标搜索_广告结果卡拦截且关闭控件不误伤() {
        assertTrue(AgentEngine.isAdvertisementResultTap("AI助手", "广告 Codex教程 下载应用"))
        assertTrue(AgentEngine.isAdvertisementResultTap("AI助手", "赞助内容"))
        assertTrue(AgentEngine.isAdvertisementResultTap("千问 点击了解更多", "立即下载"))
        assertFalse(AgentEngine.isAdvertisementResultTap("关闭广告", "×"))
        assertFalse(AgentEngine.isAdvertisementResultTap("AI助手 视频教程", "普通搜索结果"))
    }

    @Test
    fun 目标应用内搜索栏_携带无关推荐词仍可作为搜索入口() {
        // 目标 App 首页搜索栏旁边/内部常有历史推荐词；上下文含“安装”不代表搜索栏是下载入口。
        assertTrue(
            AgentEngine.isNativeSearchEntryLabel(
                "搜索栏，按钮 / codex安装 / 搜索栏，按钮 {expand_search,LinearLayout}"
            )
        )
        assertTrue(AgentEngine.isNativeSearchEntryLabel("搜索 {action_search,TextView}"))
        assertFalse(AgentEngine.isNativeSearchEntryLabel("立即安装搜索工具 {download,Button}"))
    }

    @Test
    fun 链接任务_浏览器入口识别为任务目标而不是无关跳转() {
        val task = "open_url http://127.0.0.1:8000/order-test.html and tap Submit Order"
        assertEquals("http://127.0.0.1:8000/order-test.html", AgentEngine.extractHttpUrl(task))
        assertTrue(AgentEngine.isBrowserLikeLaunch("浏览器"))
        assertTrue(AgentEngine.isBrowserLikeLaunch("System Browser"))
    }

    @Test
    fun 链接提取_排除中文标点尾巴并忽略普通App名() {
        assertEquals("https://example.com/a", AgentEngine.extractHttpUrl("打开 https://example.com/a，然后提交"))
        assertFalse(AgentEngine.isBrowserLikeLaunch("淘宝"))
    }

    @Test
    fun 通用启动守卫_只允许任务开始时确定的目标包() {
        assertTrue(TaskAppGuard.isAllowed("com.tencent.mm", "com.tencent.mm"))
        assertFalse(TaskAppGuard.isAllowed("com.taobao.taobao", "com.tencent.mm"))
        assertTrue("目标未知时允许模型先选择应用", TaskAppGuard.isAllowed("com.taobao.taobao", null))
    }

    @Test
    fun 单步闹钟本地路由_中文八点() {
        assertEquals(
            NativeTaskRouter.AlarmRequest(8, 0),
            NativeTaskRouter.parseAlarmRequest("帮我设一个八点钟的闹钟")
        )
    }

    @Test
    fun 单步闹钟本地路由_晚上九点与备注() {
        assertEquals(
            NativeTaskRouter.AlarmRequest(21, 0, "吃药"),
            NativeTaskRouter.parseAlarmRequest("帮我设个晚上9点的闹钟，备注吃药")
        )
    }

    @Test
    fun 单步闹钟本地路由_查询取消和组合任务不吞掉() {
        assertNull(NativeTaskRouter.parseAlarmRequest("查看明天八点的闹钟"))
        assertNull(NativeTaskRouter.parseAlarmRequest("帮我取消八点的闹钟"))
        assertNull(NativeTaskRouter.parseAlarmRequest("帮我设八点的闹钟然后发消息给小王"))
    }

    @Test
    fun 单步便签本地路由_支持写入和输入说法() {
        assertEquals(
            NativeTaskRouter.NoteRequest("abcd", "abcd"),
            NativeTaskRouter.parseNoteRequest("去便签写入abcd")
        )
        assertEquals(
            NativeTaskRouter.NoteRequest("买牛奶", "买牛奶"),
            NativeTaskRouter.parseNoteRequest("在便签里输入买牛奶")
        )
    }

    @Test
    fun 单步便签本地路由_查询和组合不吞掉() {
        assertNull(NativeTaskRouter.parseNoteRequest("查看便签"))
        assertNull(NativeTaskRouter.parseNoteRequest("删除便签abcd"))
        assertNull(NativeTaskRouter.parseNoteRequest("便签写入abcd然后发消息"))
    }

    // ========== TaskRouter：统一路由（本地直达出口 / 快捷指令 / 大脑选择） ==========

    @Test
    fun 路由_本地直达_闹钟() {
        val d = TaskRouter.parseNativeDirect("帮我设一个八点钟的闹钟")
        assertTrue(d is TaskRouter.NativeDirect.Alarm)
        assertEquals(8, (d as TaskRouter.NativeDirect.Alarm).req.hour)
    }

    @Test
    fun 路由_本地直达_便签() {
        val d = TaskRouter.parseNativeDirect("去便签写入abcd")
        assertTrue(d is TaskRouter.NativeDirect.Note)
        assertEquals("abcd", (d as TaskRouter.NativeDirect.Note).req.content)
    }

    @Test
    fun 路由_本地直达_未命中与否决仍交还模型() {
        assertTrue(TaskRouter.parseNativeDirect("打开淘宝") is TaskRouter.NativeDirect.None)
        assertTrue(TaskRouter.parseNativeDirect("帮我取消八点的闹钟") is TaskRouter.NativeDirect.None)
    }

    @Test
    fun 路由_快捷指令判定() {
        assertTrue(TaskRouter.isQuickTask("打开淘宝"))
        assertFalse(TaskRouter.isQuickTask("打开淘宝然后搜索手机")) // 组合指令不能跳过规划
        assertFalse(TaskRouter.isQuickTask("什么是量子力学")) // 非动词开头
    }

    @Test
    fun 路由_聊天兜底识别() {
        assertTrue(TaskRouter.looksLikeChat("你好"))
        assertTrue(TaskRouter.looksLikeChat("什么是量子力学"))
        // 快捷指令优先于聊天兜底（与旧行为一致）
        assertFalse(TaskRouter.looksLikeChat("打开淘宝"))
    }

    @Test
    fun 路由_大脑决策() {
        val chatPlan = AgentEngine.TaskPlan("chat", "", emptyList(), "")
        val r1 = TaskRouter.routeBrain("你好", chatPlan)
        assertTrue(r1.chatPlan)
        assertTrue(r1.skipAutoGlm)
        assertFalse(r1.launchTargetApp)

        val taskPlan = AgentEngine.TaskPlan(
            "screen_task", "淘宝",
            listOf(AgentEngine.PlanStep(1, "打开淘宝", "当前应用是淘宝")), "出现搜索结果"
        )
        val r2 = TaskRouter.routeBrain("打开淘宝", taskPlan)
        assertFalse(r2.skipAutoGlm)
        assertTrue(r2.launchTargetApp)
        assertFalse(r2.nativeToolFirst)

        // Bug#3：命中原生工具的指令不进视觉脑，且注入"原生工具优先"
        val r3 = TaskRouter.routeBrain("记一下明天开会", taskPlan)
        assertTrue(r3.skipAutoGlm)
        assertTrue(r3.nativeToolFirst)
        assertFalse(r3.launchTargetApp)

        // 无计划（规划失败降级）+ 快捷指令：仍允许进视觉脑
        val r4 = TaskRouter.routeBrain("打开淘宝", null)
        assertFalse(r4.skipAutoGlm)
        assertTrue(r4.launchTargetApp)
    }

    @Test
    fun 安全守卫_登录墙必须交给用户手动处理() {
        assertTrue(PageGuard.isManualAuthPage("当前应用: 微信\n账号登录\n输入密码\n忘记密码"))
        assertTrue(PageGuard.isManualAuthPage("请输入手机号和验证码\n登录/注册"))
        assertFalse(PageGuard.isManualAuthPage("我的淘宝\n已登录账号\n待收货\n设置"))
        assertFalse(PageGuard.isManualAuthPage("搜索结果\n保温杯\n加入购物车"))
    }

    @Test
    fun 安全守卫_已登录社交主页的登录通知不是登录墙() {
        val socialHome = "当前应用: com.any.social.app 页面内容: 账户及设置 在线-WiFi " +
            "消息 频道 联系人 动态 QQ安全中心 账号登录通知"
        assertFalse(PageGuard.isManualAuthPage(socialHome))
        val segmentedHome = "页面内容: 账户及设置 / 在线-WiFi / QQ安全中心 账号登录通知 / " +
            "我的关联QQ账号 / 游戏消息 / 登录游戏领好礼 / 消息"
        assertFalse(PageGuard.isManualAuthPage(segmentedHome))
        assertTrue(PageGuard.isManualAuthPage("账号登录\n输入账号\n输入密码"))
        assertTrue(PageGuard.isManualAuthPage("当前应用: com.any.app\n页面内容: 账号登录\n输入密码"))
    }

    @Test
    fun 闹钟工具明确会打开时钟应用() {
        val tools = AgentEngine.toolsJson()
        val alarm = tools.map { it.asJsonObject }
            .first { it.getAsJsonObject("function").get("name").asString == "set_alarm" }
            .getAsJsonObject("function")
        assertTrue(alarm.get("description").asString.contains("打开系统时钟应用"))
    }

    @Test
    fun plannerPrompt_包含用户指令与格式要求() {
        val p = AgentEngine.plannerPrompt("帮我刷金币", "历史上下文")
        assertTrue(p.contains("帮我刷金币"))
        assertTrue(p.contains("task_type"))
        assertTrue(p.contains("只输出严格 JSON"))
    }

    @Test
    fun autoGlmPrompt_始终要求输出思考过程() {
        val p = AgentEngine.autoGlmSystemPrompt()
        assertTrue("缺少 think 输出格式", p.contains("<think>{think}</think>"))
        assertFalse("不应允许省略思考过程", p.contains("快速模式下可以省略 think"))
    }

    @Test
    fun 目标解析_明天早上8点默认分钟为00() {
        val goal = TaskGoalVerifier.parse("帮我设个明天早上 8 点的闹钟")
        assertEquals(TaskGoalVerifier.TimeGoal(8, 0), goal.time)
    }

    @Test
    fun 目标解析_中文时间段归一化为24小时制() {
        assertEquals(TaskGoalVerifier.TimeGoal(21, 0), TaskGoalVerifier.parseTime("晚上9点"))
        assertEquals(TaskGoalVerifier.TimeGoal(15, 30), TaskGoalVerifier.parseTime("下午三点半"))
        assertEquals(TaskGoalVerifier.TimeGoal(8, 15), TaskGoalVerifier.parseTime("早上八点十五"))
    }

    @Test
    fun 屏幕校验_选择器错误时间拒绝结束() {
        val dump = "当前应用: clock\n" +
            "关键元素(坐标0-999):\n" +
            "可滑动: 08时 {NumberPicker} [191,291]\n" +
            "可滑动: 18分 {NumberPicker} [364,291]"
        val result = TaskGoalVerifier.check(
            TaskGoalVerifier.parse("帮我设个明天早上 8 点的闹钟"),
            dump
        )
        assertEquals(TaskGoalVerifier.Status.BLOCKED, result.status)
        assertTrue(result.detail.contains("08:18"))
        assertTrue(result.detail.contains("08:00"))
    }

    @Test
    fun 屏幕校验_选择器正确但未保存不许结束() {
        val dump = "关键元素(坐标0-999):\n" +
            "可滑动: 08时 {NumberPicker} [191,291]\n" +
            "可滑动: 00分 {NumberPicker} [364,291]\n" +
            "可点击: 完成 {save,Button} [908,89]"
        val result = TaskGoalVerifier.check(
            TaskGoalVerifier.parse("帮我设个明天早上 8 点的闹钟"),
            dump
        )
        assertEquals(TaskGoalVerifier.Status.BLOCKED, result.status)
        assertTrue(result.detail.contains("还没有保存"))
    }

    @Test
    fun 屏幕校验_保存后目标时间允许结束() {
        val dump = "页面内容: 闹钟 明天 08:00 已开启"
        val result = TaskGoalVerifier.check(
            TaskGoalVerifier.parse("帮我设个明天早上 8 点的闹钟"),
            dump
        )
        assertEquals(TaskGoalVerifier.Status.CONFIRMED, result.status)
    }

    // ========== 屏幕校验：小数目标 × 滚轮档位（体检第六轮 P3-4） ==========

    @Test
    fun 屏幕校验_小数目标滚轮最近整数档位不再卡死() {
        // 目标 2.5 杯，滚轮只有整数档且已到 3（round(2.5)=3）：老写法字符串比对 "2"!="2.5" 永久 BLOCKED
        val dump = "关键元素(坐标0-999):\n可滑动: 3杯 {NumberPicker} [200,300]"
        val result = TaskGoalVerifier.check(
            TaskGoalVerifier.parse("今天喝 2.5 杯水"),
            dump
        )
        assertEquals(TaskGoalVerifier.Status.NEEDS_EVIDENCE, result.status)
    }

    @Test
    fun 屏幕校验_小数目标滚轮差一档仍拦截() {
        val dump = "关键元素(坐标0-999):\n可滑动: 2杯 {NumberPicker} [200,300]"
        val result = TaskGoalVerifier.check(
            TaskGoalVerifier.parse("今天喝 2.5 杯水"),
            dump
        )
        assertEquals(TaskGoalVerifier.Status.BLOCKED, result.status)
        assertTrue(result.detail.contains("2杯"))
    }

    @Test
    fun 屏幕校验_滚轮本身显示小数时按原值比对() {
        // 滚轮标签就带小数（"2.5杯"）：老解析 \\d+ 截成 2 再字符串比对会误拦
        val dump = "关键元素(坐标0-999):\n可滑动: 2.5杯 {NumberPicker} [200,300]"
        val result = TaskGoalVerifier.check(
            TaskGoalVerifier.parse("今天喝 2.5 杯水"),
            dump
        )
        assertEquals(TaskGoalVerifier.Status.NEEDS_EVIDENCE, result.status)
    }

    // ========== 数量目标：动作次数不认屏幕字样（真机假完成修复） ==========

    @Test
    fun 目标校验_动作数量不把屏幕字样当完成证据() {
        val goal = TaskGoalVerifier.parse("去快手极速版看3个广告刷金币")
        assertEquals("3", goal.quantity?.value)
        assertTrue(goal.quantity?.isActionCount == true)
        // 系统提示"检测到3个可滑动区域"曾是假完成证据来源：零广告也被判完成
        val dump = "当前屏幕检测到3个可滑动区域 页面内容: 任务中心 去赚钱"
        assertEquals(TaskGoalVerifier.Status.NEEDS_EVIDENCE, TaskGoalVerifier.check(goal, dump).status)
    }

    @Test
    fun 目标校验_非动作数量仍按屏幕显示值确认() {
        val goal = TaskGoalVerifier.parse("帮我要3杯奶茶")
        assertEquals("3", goal.quantity?.value)
        assertFalse(goal.quantity?.isActionCount == true)
        val dump = "页面内容: 购物车 3杯 可点击: 去结算"
        assertEquals(TaskGoalVerifier.Status.CONFIRMED, TaskGoalVerifier.check(goal, dump).status)
    }

    @Test
    fun 目标提取_动作数量目标支持数字和中文() {
        assertEquals(3, TaskGoalVerifier.actionQuantityTarget("去快手极速版看3个广告刷金币", Regex("广告|激励|视频")))
        assertEquals(1, TaskGoalVerifier.actionQuantityTarget("看一个广告", Regex("广告|激励|视频")))
        assertEquals(null, TaskGoalVerifier.actionQuantityTarget("看广告赚金币", Regex("广告|激励|视频")))
    }

    @Test
    fun 目标提取_时长目标按真实经过时间裁决() {
        assertEquals(
            TaskGoalVerifier.DurationGoal(60),
            TaskGoalVerifier.parseDuration("去抖音极速版刷1分钟视频")
        )
        assertEquals(TaskGoalVerifier.DurationGoal(1800), TaskGoalVerifier.parseDuration("看半小时视频"))
        assertEquals(TaskGoalVerifier.DurationGoal(5400), TaskGoalVerifier.parseDuration("刷1小时30分视频"))
        assertNull(TaskGoalVerifier.parseDuration("去快手极速版看3个广告刷金币"))
        assertNull(TaskGoalVerifier.parseDuration("帮我设一个明早7:30的闹钟"))
    }

    @Test
    fun 被动视频识别_带时长限定仍按刷视频收口() {
        // 带时间词后老识别会掉进普通有终点任务，模型就能在20秒时假称“刷了1分钟”
        assertTrue(AgentEngine.isPassiveVideoTask("去抖音极速版刷1分钟视频"))
        assertTrue(AgentEngine.isPassiveVideoTask("看半小时视频"))
        assertFalse(AgentEngine.isPassiveVideoTask("不要刷视频了，回来"))
    }

    @Test
    fun 路径回放_必须有总时长预算() {
        // 53步路径按单步等待上限本应约2分钟；没有整条路径预算时，慢页面或反复等待会拖死开局
        assertTrue(PathMemoryStore.REPLAY_TOTAL_BUDGET_MS in 120_000L..300_000L)
    }

    // ========== 历史压缩（体检第六轮 P3-3） ==========

    @Test
    fun 历史压缩_轮次全保留时工具截断仍生效() {
        // 构造「总量超预算但 70% 窗口装得下全部轮次」的形态：pinned（system+首尾 user）占大头。
        // 老实现压缩结果与原历史同长 → 调用方按 size 相同跳过回填 → 三层截断作用在副本上整段白算
        val history = JsonArray()
        history.add(JsonObject().apply {
            addProperty("role", "system"); addProperty("content", "s".repeat(1500))
        })
        history.add(JsonObject().apply {
            addProperty("role", "user"); addProperty("content", "u".repeat(10))
        })
        repeat(5) {
            history.add(JsonObject().apply {
                addProperty("role", "assistant")
                add("tool_calls", JsonArray().apply {
                    add(JsonObject().apply {
                        add("function", JsonObject().apply { addProperty("arguments", "a".repeat(10)) })
                    })
                })
            })
            history.add(JsonObject().apply {
                addProperty("role", "tool"); addProperty("content", "x".repeat(650))
            })
        }
        history.add(JsonObject().apply {
            addProperty("role", "user"); addProperty("content", "l".repeat(1500))
        })
        val compressed = ContextCompressor.compress(history, 5000)
        assertEquals("轮次应全部保留（size 与原历史相同）", history.size(), compressed.size())
        val oldestTool = compressed.get(3).asJsonObject.get("content").asString
        assertTrue("最旧工具结果应被头尾截断: ${oldestTool.take(30)}", oldestTool.contains("（中间已省略）"))
    }

    @Test
    fun 保存拦截_滚轮错误时拦截完成按钮() {
        val dump = "关键元素(坐标0-999):\n" +
            "可滑动: 08时 {NumberPicker} [191,291]\n" +
            "可滑动: 18分 {NumberPicker} [364,291]\n" +
            "可点击: 完成 {save,Button} [908,89]"
        val mismatch = TaskGoalVerifier.completionTapMismatch(
            TaskGoalVerifier.parse("帮我设个明天早上 8 点的闹钟"),
            "do(action=\"Tap\", element=[908,89])",
            dump
        )
        assertTrue(mismatch!!.contains("已拦截保存"))
    }

    @Test
    fun 滚轮建议_按目标列中心给出方向() {
        val snapshot = TaskGoalVerifier.PickerSnapshot.from(
            "可滑动: 08时 {NumberPicker} [191,291]\n" +
                "可滑动: 18分 {NumberPicker} [364,291]"
        )
        val action = TaskGoalVerifier.PickerTracker().recommendedSwipe(
            TaskGoalVerifier.TimeGoal(8, 0), snapshot
        )
        assertTrue(action!!.startsWith("do(action=\"Swipe\", start=[364,"))
        assertTrue(action.contains("], end=[364,"))
    }

    // ========== execAutoGLM：动作解析 + 安全拦截矩阵 ==========
    // 说明：第一个参数传 null（可测化改动），只会影响 Launch 分支；下面用例不测 Launch。

    private fun exec(answer: String, task: String = "帮我刷金币", dump: String? = null) =
        AgentEngine.execAutoGLM(null, answer, 1080, 2400, task, dump)

    @Test
    fun finish_正常结束并提取message() {
        val r = exec("finish(message=\"签到成功，金币+15\")")
        assertTrue(r.finished)
        assertEquals("签到成功，金币+15", r.message)
    }

    @Test
    fun tap_缺坐标不再崩溃_直接返回提示() {
        // 回归测试：修复前这里会因 correctTapToScreenElement 的 modelPoint!! 抛异常，整个任务中止
        val r = exec(
            "do(action=\"Tap\")",
            dump = "当前应用: com.kuaishou.nebula\n可点击: 去签到 [500,300]"
        )
        assertEquals("Tap缺坐标", r.info)
        assertFalse(r.finished)
    }

    @Test
    fun tap_敏感操作直接跳过() {
        val r = exec("do(action=\"Tap\", element=[500,600], message=\"重要操作\")")
        assertTrue(r.info.contains("敏感操作已跳过"))
        assertFalse(r.finished)
    }

    @Test
    fun tap_普通点击无拦截时走到执行层() {
        // 无障碍服务为 null（测试环境），所以点击返回 NO_A11Y；关键是【没有】被误拦截
        val r = exec(
            "do(action=\"Tap\", element=[500,300])",
            task = "打开淘宝搜一下",
            dump = "当前应用: com.ss.android.ugc.aweme.lite\n可点击: 搜索框 [500,300]\n页面内容: 首页"
        )
        assertEquals(DeviceTools.NO_A11Y, r.info)
        assertNull(r.message)
    }

    @Test
    fun tap_商单页拦截_三个App都生效() {
        val packages = listOf(
            "com.kuaishou.nebula",
            "com.ss.android.ugc.aweme.lite",
            "com.sankuai.meituan"
        )
        for (pkg in packages) {
            val dump = "当前应用: $pkg\n页面内容: 立即抢购 提交订单 商品详情\n可点击: 立即抢购 [500,300]"
            val r = exec("do(action=\"Tap\", element=[500,300])", task = "帮我刷金币", dump = dump)
            assertEquals("包名 $pkg 未拦截商单页点击", "__SENSITIVE_BLOCK__", r.message)
        }
    }

    @Test
    fun tap_跳转下载类入口拦截_三个App都生效() {
        val packages = listOf(
            "com.kuaishou.nebula",
            "com.ss.android.ugc.aweme.lite",
            "com.sankuai.meituan"
        )
        for (pkg in packages) {
            val dump = "当前应用: $pkg\n页面内容: 任务中心\n可点击: 去下载安装APP领金币 [500,400]"
            val r = exec("do(action=\"Tap\", element=[500,400])", task = "帮我刷金币", dump = dump)
            assertEquals("包名 $pkg 未拦截下载跳转入口", "__JUMP_BLOCK__", r.message)
        }
    }

    @Test
    fun tap_购物任务订单确认按钮仍然拦截() {
        // 购物任务可以浏览商品，但订单确认属于用户手动动作
        val dump = "当前应用: com.sankuai.meituan\n页面内容: 提交订单\n可点击: 提交订单 [500,300]"
        val r = exec("do(action=\"Tap\", element=[500,300])", task = "帮我买一杯奶茶下单", dump = dump)
        assertEquals("__SENSITIVE_BLOCK__", r.message)
    }

    @Test
    fun 工具红线_订单确认按钮主链路硬拦截() {
        listOf("提交订单", "确认订单", "确认下单", "Place Order").forEach { label ->
            assertTrue("主工具链未硬拦截：$label", AgentEngine.isSensitiveButton(label))
        }
    }

    @Test
    fun tap_金币任务不误点直播引流卡() {
        val dump = "当前应用: com.any.reward.app\n" +
            "页面内容: 任务中心 看直播领金币\n" +
            "可点击: 看直播领金币 [500,300]"
        val r = exec("do(action=\"Tap\", element=[500,300])", task = "看广告刷金币", dump = dump)
        assertEquals("__JUMP_BLOCK__", r.message)
    }

    @Test
    fun wait_解析秒数并执行() {
        val r = exec("do(action=\"Wait\", duration=\"1 seconds\")")
        assertEquals("已等待1秒", r.info)
    }

    @Test
    fun swipe_缺参数给出提示() {
        val r = exec("do(action=\"Swipe\")")
        assertEquals("Swipe参数缺失", r.info)
    }

    @Test
    fun takeOver_需要用户接管时结束本轮() {
        val r = exec("do(action=\"Take_over\", message=\"需要验证码\")")
        assertTrue(r.finished)
        assertTrue(r.message!!.contains("接管"))
        assertTrue(r.message!!.contains("验证码"))
    }

    @Test
    fun autoGlm_多动作拆分并本地连续执行() {
        val answer = "do(action=\"Tap\", element=[500,300])\n" +
            "do(action=\"Type\", text=\"测试\")"
        val actions = AgentEngine.parseAutoGlmActions(answer)
        assertEquals(2, actions.size)

        val r = exec(answer)
        assertTrue(r.settled)
        assertTrue(r.info.contains("1/2"))
        assertTrue(r.info.contains("2/2"))
    }

    @Test
    fun autoGlm_含启动动作时不批量执行() {
        val answer = "do(action=\"Tap\", element=[500,300])\n" +
            "do(action=\"Launch\", app=\"淘宝\")"
        val r = exec(answer)
        assertFalse(r.settled)
        assertEquals(DeviceTools.NO_A11Y, r.info)
        assertFalse(r.info.contains("1/2"))
    }

    @Test
    fun 坏格式_纯描述文本报PARSE_FAIL() {
        val r = exec("我觉得现在这个页面是任务中心，看起来不错")
        assertEquals("__PARSE_FAIL__", r.message)
        assertTrue(r.info.contains("无法解析动作"))
    }

    @Test
    fun 未知动作_给出提示不崩溃() {
        val r = exec("do(action=\"Fly\", target=\"月亮\")")
        assertTrue(r.info.contains("未知动作"))
    }

    // ========== 消息结构 ==========

    @Test
    fun msg与toolMsg_字段正确() {
        val m = AgentEngine.msg("user", "你好")
        assertEquals("user", m.get("role").asString)
        assertEquals("你好", m.get("content").asString)

        val t = AgentEngine.toolMsg("call_42", "结果")
        assertEquals("tool", t.get("role").asString)
        assertEquals("call_42", t.get("tool_call_id").asString)
        assertEquals("结果", t.get("content").asString)
    }

    // ========== TaskCardMemory：任务卡记忆纯逻辑 ==========

    @Test
    fun 卡片记忆_判定阈值() {
        assertNull(TaskCardMemory.verdictOf(CardStat(good = 0, bad = 0)))
        assertNull(TaskCardMemory.verdictOf(CardStat(good = 0, bad = 1)))   // 一次失败不下结论
        assertEquals("wall", TaskCardMemory.verdictOf(CardStat(good = 0, bad = 2)))
        assertNull(TaskCardMemory.verdictOf(CardStat(good = 1, bad = 1)))   // 好坏参半不下结论
        assertEquals("good", TaskCardMemory.verdictOf(CardStat(good = 2, bad = 1)))
        assertEquals("good", TaskCardMemory.verdictOf(CardStat(good = 1, bad = 0)))
    }

    @Test
    fun 卡片记忆_外跳一次即判别碰() {
        assertEquals("wall", TaskCardMemory.verdictOf(CardStat(good = 0, bad = 1, lastOutcome = "jump")))
        assertNull(TaskCardMemory.verdictOf(CardStat(good = 0, bad = 1, lastOutcome = "wall")))
        assertNull(TaskCardMemory.verdictOf(CardStat(good = 1, bad = 1, lastOutcome = "jump")))
    }

    @Test
    fun 卡片记忆_标签规范化() {
        assertEquals("去观看得金币", TaskCardMemory.normalizeLabel(" 去观看 得金币 "))
        assertTrue(TaskCardMemory.normalizeLabel("很长的标签文字".repeat(10)).length <= 24)
        // 倒计时数字必须剔除：同一张卡的「19s后领取观看奖励」「5s后领取观看奖励」要归并成一条记忆
        assertEquals(
            TaskCardMemory.normalizeLabel("19s后领取观看奖励"),
            TaskCardMemory.normalizeLabel("5s后领取观看奖励")
        )
    }

    @Test
    fun 卡片记忆_垃圾标签不可靠() {
        assertFalse(TaskCardMemory.isReliableLabel(TaskCardMemory.normalizeLabel("无文字元素")))
        assertFalse(TaskCardMemory.isReliableLabel(TaskCardMemory.normalizeLabel("action_bar_view")))
        assertFalse(TaskCardMemory.isReliableLabel(TaskCardMemory.normalizeLabel("abc")))
        assertTrue(TaskCardMemory.isReliableLabel(TaskCardMemory.normalizeLabel("开宝箱得金币")))
    }

    @Test
    fun 卡片记忆_从页面提取标签时剔除占位符() {
        val dump = "当前应用: com.kuaishou.nebula\n" +
            "可点击: 去观看 [499,400]\n" +
            "可点击: 无文字元素 [499,500]\n" +
            "可点击: action_bar_view [499,778]\n" +
            "可点击: 立即领取 [500,600]"
        val labels = TaskCardMemory.visibleLabels(dump)
        assertTrue("去观看" in labels)
        assertTrue("立即领取" in labels)
        assertFalse("无文字元素" in labels)
        assertFalse("action_bar_view" in labels)
    }

    @Test
    fun 卡片记忆_建议文本生成() {
        val stats = mapOf(
            "com.kuaishou.nebula|开宝箱得金币" to CardStat(good = 3, bad = 0, lastOutcome = "real"),
            "com.kuaishou.nebula|美团酒店优惠" to CardStat(good = 0, bad = 3, lastOutcome = "wall"),
            "com.other.pkg|无关的卡" to CardStat(good = 5, bad = 0, lastOutcome = "real"),
            "com.kuaishou.nebula|证据不足的卡" to CardStat(good = 1, bad = 1, lastOutcome = "real")
        )
        val dump = "当前应用: com.kuaishou.nebula\n" +
            "可点击: 开宝箱得金币 [400,300]\n" +
            "可点击: 美团酒店优惠进行中 [400,500]"
        val note = TaskCardMemory.adviseFrom(stats, "com.kuaishou.nebula", dump)
        assertTrue("好卡应被推荐", note.contains("开宝箱得金币"))
        assertTrue("坏卡应被拉黑", note.contains("美团酒店优惠"))
        assertTrue(note.contains("优先考虑"))
        assertTrue(note.contains("不要再点"))
        assertFalse("其他包的卡不应出现", note.contains("无关的卡"))
        assertFalse("证据不足的卡不应出现", note.contains("证据不足的卡"))
        // 页面上没有的卡不出现在建议里
        val dump2 = "当前应用: com.kuaishou.nebula\n可点击: 别的什么 [400,300]"
        assertTrue(TaskCardMemory.adviseFrom(stats, "com.kuaishou.nebula", dump2).isEmpty())
        // 包名不匹配不给建议
        assertTrue(TaskCardMemory.adviseFrom(stats, "com.sankuai.meituan", dump).isEmpty())
    }

    @Test
    fun 卡片记忆_模糊匹配可见标签() {
        assertEquals("美团酒店优惠进行中", TaskCardMemory.matchLabel(setOf("美团酒店优惠进行中"), "美团酒店优惠"))
        assertEquals("去观看", TaskCardMemory.matchLabel(setOf("去观看"), "去观看得金币"))
        assertNull(TaskCardMemory.matchLabel(setOf("完全无关"), "去观看得金币"))
    }

    // ========== ExitMemory：通用外跳退出记忆 ==========

    @Test
    fun 退出记忆_按语义提取按钮并排除继续观看() {
        val dump = "当前应用: com.any.third.party\n" +
            "页面内容: 看完广告可得奖励\n" +
            "可点击: 继续观看得奖励 {btn_continue,TextView} [500,700]\n" +
            "可点击: 立即下载 {btn_download,TextView} [500,820]\n" +
            "可点击: 放弃奖励 {btn_give_up,TextView} [220,720]\n" +
            "可点击: 关闭 5s {btn_close,ImageView} [940,80]"
        val labels = ExitMemory.visibleButtons(dump).map { it.normalized }

        assertTrue("放弃奖励应作为通用退出候选", "放弃奖励" in labels)
        assertTrue("关闭应作为通用退出候选", labels.any { it.contains("关闭") })
        assertFalse("继续观看不是退出控件", labels.any { it.contains("继续观看") })
        assertFalse("下载按钮不能当作退出控件", labels.any { it.contains("下载") })
    }

    @Test
    fun 退出记忆_激励广告退出文案变体可识别() {
        // 激励广告 SDK 常见的「退出广告」按钮变体都应得正分，系统才允许代点
        for (label in listOf("残忍拒绝", "不玩了", "狠心离开", "狠心放弃", "暂离", "拒绝", "不再提醒")) {
            assertTrue("「$label」应识别为退出控件", ExitMemory.scoreButton(label) > 0)
        }
        // 「残忍拒绝」是明确的放弃语义，得分应高于普通关闭类
        assertTrue(ExitMemory.scoreButton("残忍拒绝") >= ExitMemory.scoreButton("关闭"))
        // 广告转化/继续类新变体永远不能当退出
        for (label in listOf("立即体验", "了解更多", "去完成", "去看看", "去逛逛", "开心收下", "点击领取")) {
            assertTrue("「$label」不应识别为退出控件", ExitMemory.scoreButton(label) <= 0)
        }
    }

    @Test
    fun 退出记忆_验证路线排在普通候选前() {
        val dump = "当前应用: com.any.third.party\n" +
            "可点击: 关闭 {btn_close,ImageView} [940,80]\n" +
            "可点击: 放弃奖励 {btn_give_up,TextView} [220,720]"
        val ordered = ExitMemory.orderedCandidates(
            dump,
            remembered = listOf("放弃奖励")
        ).map { it.normalized }

        assertEquals("放弃奖励", ordered.first())
        assertTrue(ordered.any { it.contains("关闭") })
    }

    @Test
    fun 退出记忆_存储键剥离倒计时和奖励数字() {
        val first = ExitMemory.storageKey(
            "com.target.app", "com.any.third.party", "点击并浏览15秒领88金币"
        )
        val second = ExitMemory.storageKey(
            "com.target.app", "com.any.third.party", "点击并浏览30秒领66金币"
        )
        assertEquals(first, second)
    }

    @Test
    fun 页面防护_识别误入直播间并优先明确退出() {
        val dump = "当前应用: com.any.app\n" +
            "页面内容: 官方旗舰店 1274观看 直播回放\n" +
            "可点击: 开启通知 [500,510]\n" +
            "可点击: 退出直播间 [500,585]\n" +
            "可点击: 说点什么... [240,850]"

        assertTrue(PageGuard.isLiveRoom(dump))
        assertTrue(PageGuard.isUnintendedLiveRoom("看广告刷金币", dump))
        assertEquals("退出直播间", PageGuard.liveRoomExitLabel(dump))
    }

    @Test
    fun 页面防护_金币任务进入支付页必须拦截() {
        val dump = "当前应用: com.any.app\n" +
            "页面内容: éLL旗舰店 补贴价79.9 支付宝支付 微信支付 立即支付\n"

        assertTrue(PageGuard.isPaymentOrOrderPage(dump))
        assertTrue(PageGuard.isForbiddenPayment("看广告刷金币", dump))
        assertTrue(PageGuard.isForbiddenPayment("帮我下单购买", dump))
    }

    @Test
    fun 页面防护_普通首页营销流不算支付订单页() {
        val homeFeed = "当前应用: com.any.shop\n" +
            "页面内容: 双11品类周 有券待使用 4张共136元 去使用\n" +
            "可点击: 立即抢购 [500,340]\n" +
            "可点击: 搜索 [860,118]\n" +
            "可点击: 购物车 [820,910]"
        assertFalse(PageGuard.isPaymentOrOrderPage(homeFeed))
        assertFalse(PageGuard.isForbiddenPayment("搜索保温杯并找销量最高的商品", homeFeed))

        val orderPage = "当前应用: com.any.shop\n" +
            "页面内容: 应付金额 136元\n" +
            "可点击: 提交订单 [500,910]"
        assertTrue(PageGuard.isPaymentOrOrderPage(orderPage))
    }

    @Test
    fun 页面防护_商品销量付款人数不等于付款按钮() {
        val searchResult = "当前应用: com.any.shop\n" +
            "页面内容: 搜索 / 怎么买\n" +
            "可点击: 泰福高保温杯 116.00元 4000+人付款 [300,500]\n" +
            "可点击: 乐扣乐扣保温杯 61.00元 6万+人付款 [300,900]"

        assertFalse(PageGuard.isPaymentOrOrderPage(searchResult))
        assertFalse(PageGuard.isForbiddenPayment("搜索保温杯并找销量最高的商品", searchResult))
        assertFalse(PageGuard.isSensitiveButton("保温杯 116.00元 4000+人付款"))
        assertFalse(PageGuard.isSensitiveButton("保温杯 116.00元 6万+人付款"))
        assertTrue(PageGuard.isSensitiveButton("立即付款"))
    }

    @Test
    fun 页面防护_商品研究结果卡可以点开核对销量() {
        val task = "打开淘宝搜索保温杯，然后找销量最高的商品"
        val label = "磨砂黑350ML保温杯 9.70元 2000+人付款"
        val context = "$label / 更多 / 8年老店进店"

        assertTrue(PageGuard.isProductResearchCard(task, label, context))
        assertFalse(PageGuard.isSensitiveButton(label))
        assertFalse(PageGuard.isProductResearchCard(task, "8年老店进店", "领取优惠券"))
        assertFalse(PageGuard.isProductResearchCard("搜索保温杯并下单", label, context))
    }

    @Test
    fun 页面防护_局部隐藏文案不等于隐私遮挡() {
        val searchablePage = "当前应用: com.any.shop\n" +
            "页面内容: 已隐藏 商品推荐\n" +
            "可点击: 搜索 [500,118]\n" +
            "可点击: 购物车 [820,910]"
        assertFalse(PageGuard.isPrivacyOverlay(searchablePage))

        val hiddenScreen = "当前应用: com.any.app\n" +
            "页面内容: 屏幕共享画面已隐藏"
        assertTrue(PageGuard.isPrivacyOverlay(hiddenScreen))
    }

    @Test
    fun 页面防护_订单确认按钮任何购物措辞都不豁免() {
        val labels = listOf(
            "提交订单", "确认订单", "确认下单", "立即购买",
            "Place Order", "Confirm Order", "Submit Order", "SubmitOrder"
        )
        labels.forEach { label ->
            assertTrue("订单确认按钮未硬拦截：$label", PageGuard.isSensitiveButton(label))
            val dump = "当前应用: com.any.app\n页面内容: $label\n可点击: $label [500,300]"
            assertTrue(
                "订单确认页未硬拦截：$label",
                PageGuard.isForbiddenPayment("帮我买奶茶并下单", dump)
            )
        }
        assertFalse(PageGuard.isSensitiveButton("商品详情"))
        assertFalse(PageGuard.isSensitiveButton("加入购物车"))
    }

    @Test
    fun 页面防护_否定支付描述不触发视觉拦截() {
        assertTrue(
            PageGuard.isRejectedPaymentMention(
                "这不是支付页，我要点击免费领取金币。do(action=\"Tap\", element=[500,300])"
            )
        )
        assertFalse(PageGuard.isRejectedPaymentMention("当前是支付页，请点击立即支付"))
    }

    @Test
    fun 页面防护_模糊奖励任务只拦高风险语义() {
        assertTrue(PageGuard.isUnsafeRewardTap("帮我赚钱", "看直播领金币"))
        assertTrue(PageGuard.isUnsafeRewardTap("帮我赚钱", "下载APP得奖励"))
        assertTrue(PageGuard.isUnsafeRewardTap("帮我赚钱", "立即投币报名"))
        assertTrue(PageGuard.isUnsafeRewardTap("帮我赚钱", "立即参与"))
        assertTrue(PageGuard.isUnsafeRewardTap("帮我赚钱", "幸运抽奖"))
        assertFalse(PageGuard.isUnsafeRewardTap("看广告刷金币", "观看广告领金币"))
        assertFalse(PageGuard.isUnsafeRewardTap("看直播领金币", "进入直播间"))
    }

    @Test
    fun 页面防护_广告奖励任务必须拦截消费送礼入口() {
        val adTask = "看广告赚金币"
        assertTrue(PageGuard.isSpendActionTap(adTask, "发红包"))
        assertTrue(PageGuard.isSpendActionTap(adTask, "礼物面板"))
        assertTrue(PageGuard.isSpendActionTap(adTask, "赠送礼物"))
        assertTrue(PageGuard.isSpendActionTap(adTask, "查看详情", "直播间发红包"))
        assertTrue(PageGuard.isSpendActionTap(adTask, "去参与", "送礼打赏"))
        assertFalse(PageGuard.isSpendActionTap(adTask, "观看广告领金币"))
        assertFalse(PageGuard.isSpendActionTap(adTask, "领红包"))
        assertFalse(PageGuard.isSpendActionTap(adTask, "已成功领取奖励", "旗舰店 购买后确认收货"))
        assertFalse(PageGuard.isSpendActionTap(adTask, "无文字元素", "旗舰店 购买后确认收货"))
    }

    @Test
    fun 页面防护_任务点名消费动作时放行() {
        assertFalse(PageGuard.isSpendActionTap("帮我发红包", "发红包"))
        assertFalse(PageGuard.isSpendActionTap("给主播送礼物", "礼物面板"))
        assertFalse(PageGuard.isSpendActionTap("去旗舰店看看商品", "美妆旗舰店"))
        assertTrue(PageGuard.isSpendActionTap("不要发红包，只看广告", "发红包"))
    }

    @Test
    fun 页面防护_中性入口只在紧邻消费语境时拦截() {
        val adTask = "看广告赚金币"
        assertTrue(PageGuard.isSpendActionTap(adTask, "查看详情", "直播间发红包"))
        assertFalse(PageGuard.isSpendActionTap(adTask, "观看广告得金币", "购买后确认收货得金币"))
        assertFalse(PageGuard.isSpendActionTap(adTask, "已成功领取奖励", "店铺讲解中"))
    }

    @Test
    fun 页面防护_广告任务不点店铺入口且识别无文字直播关闭() {
        val adTask = "看广告赚金币"
        assertTrue(PageGuard.isSpendActionTap(adTask, "Judydoll橘朵美妆旗舰店"))

        val liveDump = "当前应用: com.any.app\n" +
            "页面内容: 关注 2917观看 更多直播 说点什么\n" +
            "可点击: 无文字元素 {live_close,ImageView} [936,75]\n" +
            "可点击: 说点什么... [240,850]\n" +
            "可点击: 底部栏礼物面板按钮 [699,912]"

        assertTrue(PageGuard.isLiveRoom(liveDump))
        assertTrue(PageGuard.isUnintendedLiveRoom(adTask, liveDump))
        assertEquals("无文字元素", PageGuard.liveRoomExitLabel(liveDump))
    }

    @Test
    fun 页面防护_投币抽奖活动页按任务对齐() {
        assertTrue(PageGuard.isRewardActivityPage("立即投币报名 完整参与金币必暴涨", "看广告赚金币"))
        assertTrue(PageGuard.isRewardActivityPage("立即参与 千万金币瓜分", "看广告赚金币"))
        assertFalse(PageGuard.isRewardActivityPage("立即投币报名 完整参与金币必暴涨", "帮我投币抽大奖"))
        assertFalse(PageGuard.isRewardActivityPage("立即参与 千万金币瓜分", "参与抽奖赢金币"))
        assertFalse(PageGuard.isRewardActivityPage("立即投币"))
        assertFalse(PageGuard.isRewardActivityPage("看广告得金币 立即观看"))
    }

    @Test
    fun 页面防护_活动层退出控件优先文字按钮其次图形X() {
        val textDump = "当前应用: com.any.app\n" +
            "页面内容: 瓜分百亿金币 今日投币 立即参与\n" +
            "可点击: 取消 [500,620]\n" +
            "可点击: × [905,190]"
        val textExit = PageGuard.rewardActivityExitControl(textDump)
        assertEquals("取消", textExit?.closeLabel)
        assertFalse(PageGuard.isGlyphCloseQuery(textExit?.closeLabel))

        // 没有文字关闭按钮时取最靠上的关闭角标/无名元素（真机弹窗 X 常不响应 Back）
        val glyphDump = "当前应用: com.any.app\n" +
            "页面内容: 瓜分百亿金币 今日投币 立即参与\n" +
            "可点击: × [880,420]\n" +
            "可点击: 无文字元素 [905,190]"
        val glyphExit = PageGuard.rewardActivityExitControl(glyphDump)
        assertEquals("无文字元素", glyphExit?.closeLabel)
        assertEquals(905, glyphExit?.x)
        assertEquals(190, glyphExit?.y)
        assertTrue(PageGuard.isGlyphCloseQuery(glyphExit?.closeLabel))

        assertNull(PageGuard.rewardActivityExitControl("当前应用: com.any.app\n页面内容: 立即参与 千万金币瓜分"))
    }

    @Test
    fun 页面防护_点击目标与任务对齐() {
        val adTask = "去极速版看广告赚金币"
        assertTrue(PageGuard.isOffMissionTap(adTask, "立即参与"))
        assertTrue(PageGuard.isOffMissionTap(adTask, "立即投币报名 完整参与金币必暴涨"))
        assertTrue(PageGuard.isOffMissionTap(adTask, "每日签到"))
        assertTrue(PageGuard.isOffMissionTap(adTask, "开宝箱"))
        assertFalse(PageGuard.isOffMissionTap(adTask, "看广告得金币"))
        assertFalse(PageGuard.isOffMissionTap(adTask, "参与看广告赢金币"))
        assertFalse(PageGuard.isOffMissionTap(adTask, "去赚钱"))

        assertTrue(PageGuard.isOffMissionTap("帮我签到领金币", "看广告得金币"))
        assertFalse(PageGuard.isOffMissionTap("帮我签到领金币", "每日签到"))
        assertFalse(PageGuard.isOffMissionTap("去参加幸运抽奖", "幸运抽奖"))
    }

    @Test
    fun 页面防护_中性按钮按卡片语境判断去向() {
        val adTask = "看广告赚金币"
        assertTrue(PageGuard.isOffMissionTap(adTask, "立即参与", "投币报名 完整参与金币必暴涨"))
        assertTrue(PageGuard.isOffMissionTap(adTask, "去观看", "幸运抽奖 转盘赢大奖"))
        assertTrue(PageGuard.isOffMissionTap(adTask, "去观看", "下载APP领现金"))
        assertFalse(PageGuard.isOffMissionTap(adTask, "去观看", "看广告得金币 立即观看"))
        assertFalse(
            PageGuard.isOffMissionTap(
                adTask,
                "去赚钱 / 去赚钱 {,ActionBar}",
                "暂停视频 / 音乐转盘 / 去赚钱 / 拍摄"
            )
        )
        assertTrue(PageGuard.isOffMissionTap("不要下载只看广告", "立即下载"))
        assertTrue(PageGuard.isOffMissionTap("不要下载只看广告", "去观看", "下载APP领现金"))
        assertFalse(PageGuard.isOffMissionTap("不要下载只看广告", "看广告得金币"))
    }

    @Test
    fun 页面防护_无目的地证据不拦截中性关闭控件() {
        val adTask = "去快手极速版看广告赚金币"
        assertFalse(PageGuard.isOffMissionTap(adTask, ""))
        assertFalse(PageGuard.isOffMissionTap(adTask, "   "))
        assertFalse(PageGuard.isOffMissionTap(adTask, "", " / / "))
        assertTrue(AgentEngine.isDismissIntent("我需要先关闭这个弹窗，然后点击右上角的关闭按钮（X按钮）。"))
        assertTrue(PageGuard.isDismissControl("", 908, 246))
        assertFalse(PageGuard.isDismissControl("去微信邀请好友", 499, 609))
    }

    @Test
    fun 页面防护_奖励路由不被当前页面背景污染() {
        val pageNoise = "刚刚看过 / 暂停视频 / 同城 / 音乐转盘 / 拍摄 / 点赞 / 评论 / 分享"
        listOf("任务中心", "赚钱任务", "去赚钱", "去赚金币", "奖励中心", "收益中心", "钱包")
            .forEach { route ->
                assertFalse(PageGuard.isOffMissionTap("去极速版看广告赚金币", route, pageNoise))
                assertFalse(PageGuard.isOffMissionTap("帮我签到领金币", route, pageNoise))
            }
        assertTrue(PageGuard.isOffMissionTap("看广告赚金币", "看直播领金币", pageNoise))
        assertTrue(PageGuard.isOffMissionTap("看广告赚金币", "去观看", "幸运抽奖 转盘赢大奖"))
    }

    @Test
    fun 页面防护_任务页混排入口不因背景连坐拦截() {
        val adTask = "去抖音极速版看广告刷金币"
        assertFalse(
            PageGuard.isOffMissionTap(
                adTask,
                "播放视频，按钮 / 短剧，按钮 / 热点，按钮 / 直播，按钮",
                "短剧 / 热点 / 直播 / 看广告赚金币"
            )
        )
        assertFalse(
            PageGuard.isOffMissionTap(
                adTask,
                "无文字元素",
                "看20秒指定视频任务 / 单日最高可赚金币"
            )
        )
        assertTrue(PageGuard.isOffMissionTap(adTask, "无文字元素", "幸运抽奖 / 邀请好友得现金"))
    }

    @Test
    fun 页面防护_限时奖励广告入口按任务目的地放行() {
        val adTask = "去快手极速版看3个广告刷金币"
        assertFalse(PageGuard.isOffMissionTap(adTask, "点击看5秒"))
        assertFalse(PageGuard.isOffMissionTap(adTask, "action_bar_view", "点击看5秒 领1456金币"))
        assertFalse(PageGuard.isOffMissionTap(adTask, "去完成", "点击并浏览 15 秒 额外得 330 金币"))
        assertTrue(PageGuard.isOffMissionTap(adTask, "去观看", "下载APP领现金"))
    }

    @Test
    fun 页面防护_底部无文字导航容器不算偏离任务() {
        assertTrue(PageGuard.isNeutralBottomNavigation("无文字元素 {kdu,FrameLayout}", 912))
        assertTrue(PageGuard.isNeutralBottomNavigation("", 905))
        assertFalse(PageGuard.isNeutralBottomNavigation("无文字元素 {kdu,FrameLayout}", 500))
        assertFalse(PageGuard.isNeutralBottomNavigation("看直播领金币", 912))
    }

    @Test
    fun 刷视频赚金币_按普通视频流处理() {
        assertTrue(AgentEngine.isPassiveVideoTask("去抖音极速版刷视频赚金币"))
        assertTrue(AgentEngine.isPassiveVideoTask("看视频刷金币"))
        assertTrue(AgentEngine.isPassiveVideoTask("刷会儿视频"))
        assertTrue(AgentEngine.isPassiveVideoTask("浏览一会儿短视频"))
    }

    @Test
    fun 明确广告或任务指令_不按普通视频流处理() {
        assertFalse(AgentEngine.isPassiveVideoTask("看广告赚金币"))
        assertFalse(AgentEngine.isPassiveVideoTask("去抖音极速版看广告赚金币"))
        assertFalse(AgentEngine.isPassiveVideoTask("去抖音极速版看广告刷金币"))
        assertFalse(AgentEngine.isPassiveVideoTask("看广告视频赚金币"))
        assertFalse(AgentEngine.isPassiveVideoTask("刷广告领现金"))
        assertFalse(AgentEngine.isPassiveVideoTask("去任务中心做任务赚金币"))
        assertFalse(AgentEngine.isPassiveVideoTask("先签到，再看视频"))
        assertTrue(AgentEngine.isPassiveVideoTask("不要做任务，直接刷视频"))
    }

    @Test
    fun 普通刷视频_误入任务中心可被读屏兜底识别() {
        val taskCenter = "当前应用: com.any.video.app\n" +
            "页面内容: 去赚钱 现金收益 签到 邀请好友\n" +
            "可点击: 看广告赚金币 [500,300]"
        val feedWithCoinBadge = "当前应用: com.any.video.app\n" +
            "页面内容: 推荐视频 视频标题\n" +
            "可点击: 金币 [940,120]"

        assertTrue(PageGuard.isRewardTaskCenter(taskCenter))
        assertFalse(PageGuard.isRewardTaskCenter(feedWithCoinBadge))
    }

    @Test
    fun 明确看广告任务_视频流可识别并可路由到赚钱入口() {
        val feed = "当前应用: com.any.video.app\n" +
            "页面内容: 推荐 关注 你好baby\n" +
            "可点击: 点赞 [940,900]\n" +
            "可点击: 评论 [940,1000]\n" +
            "可点击: 分享 [940,1100]\n" +
            "可点击: 赚钱 {,Button} [914,120]"
        val taskCenter = "当前应用: com.any.video.app\n" +
            "页面内容: 去赚钱 现金收益 签到 邀请好友\n" +
            "可点击: 看广告赚金币 [500,300]"
        val feedWithoutSafeEntry = "当前应用: com.any.video.app\n" +
            "页面内容: 推荐 视频标题\n" +
            "可点击: 点赞 [940,900]\n" +
            "可点击: 下载并打开得10000金币 [499,1800]"

        assertTrue(PageGuard.isVideoFeed(feed))
        assertEquals("赚钱", PageGuard.rewardEntryLabel(feed))
        assertFalse(PageGuard.isVideoFeed(taskCenter))
        assertNull(PageGuard.rewardEntryLabel(feedWithoutSafeEntry))
    }

    @Test
    fun autoGlmPrompt_刷视频赚金币优先于任务中心() {
        val sp = AgentEngine.autoGlmSystemPrompt()
        assertTrue(sp.contains("即使同时提到金币、赚钱"))
        assertTrue(sp.contains("只适用于明确的广告/任务指令"))
        assertTrue(sp.contains("如果已经进入任务中心页，立即 Back 回视频流"))
        assertTrue(sp.contains("绝不能在普通视频流 Swipe 刷视频"))
        assertTrue(sp.contains("用户明确说「看广告、看个广告、刷广告/广告视频、看激励广告」时不适用本条"))
    }

    @Test
    fun 返回意图_左上角Tap改走系统返回() {
        val answer = "<think>当前是赚钱任务页，目标视频在外面，先返回到视频播放页面。</think>" +
            "<answer>do(action=\"Tap\", element=[48,48])</answer>"
        val dump = "当前应用: com.any.reward.app\n" +
            "页面内容: 赚钱任务 短剧 热点 直播 侧边栏有8条未读消息\n" +
            "可点击: 短剧，按钮 {,TextView} [52,49]\n" +
            "可点击: 热点 [220,49]"

        val result = AgentEngine.execAutoGLM(
            null,
            answer,
            realW = 1080,
            realH = 2400,
            userTask = "去抖音极速版看视频刷金币",
            screenDump = dump
        )

        assertFalse("返回导航不应命中高风险拦截", result.message == "__JUMP_BLOCK__")
        assertEquals("应已进入系统返回执行层", DeviceTools.NO_A11Y, result.info)
    }

    @Test
    fun 返回意图_普通奖励高风险入口仍拦截() {
        val answer = "<think>这里有看直播领金币入口。</think>" +
            "<answer>do(action=\"Tap\", element=[500,300])</answer>"
        val dump = "当前应用: com.any.reward.app\n" +
            "页面内容: 赚钱任务\n" +
            "可点击: 看直播领金币 [500,300]"

        val result = AgentEngine.execAutoGLM(
            null,
            answer,
            realW = 1080,
            realH = 2400,
            userTask = "去抖音极速版看视频刷金币",
            screenDump = dump
        )

        assertEquals("__JUMP_BLOCK__", result.message)
        assertTrue(result.info.contains("与当前任务"))
    }

    @Test
    fun 页面防护_下载墙优先点击安全退出() {
        val dump = "当前应用: com.any.reward.app\n" +
            "页面内容: 广告 完成互动领取金币福利 立即下载 红果免费短剧\n" +
            "可点击: 继续互动 [500,1040]\n" +
            "可点击: 坚持退出 [500,1180]\n" +
            "可点击: 立即下载 [500,1950]"

        assertTrue(PageGuard.isDownloadWall(dump))
        assertEquals("坚持退出", PageGuard.safeAdExitLabel(dump))
        assertFalse(PageGuard.isDownloadWall("当前应用: com.any.reward.app\n页面内容: 任务中心 看广告领金币"))
    }

    @Test
    fun 页面防护_搜索结果内嵌广告卡不算整页下载墙() {
        val dump = "当前应用: com.any.video.app\n" +
            "输入框: AI助手 {query,EditText} [420,160]\n" +
            "可点击: 搜索 {action_search,TextView} [820,160]\n" +
            "可点击: 综合 [72,250]\n" +
            "可点击: 用户 [456,250]\n" +
            "可点击: 直播 [772,250]\n" +
            "页面内容: 相关搜索 AI工具 智能体\n" +
            "可点击: AI激发孩子求知欲 {card,LinearLayout} [500,550]\n" +
            "页面内容: UP 太帅而彼肘击 播放 61 10月19日\n" +
            "可点击: 千问 点击了解更多 立即下载 {cta,Button} [756,790]\n" +
            "页面内容: UP 编程大佬陈悠秀 共11集 1:34:36 播放 346.7万 6月7日"

        assertTrue(PageGuard.isEmbeddedAdSearchResults(dump))
        assertFalse("应留在搜索结果页并跳过广告卡", PageGuard.isDownloadWall(dump))
    }

    @Test
    fun 页面防护_整屏下载墙不因零散结果词放行() {
        val dump = "当前应用: com.any.reward.app\n" +
            "页面内容: 热门应用 立即下载 点击了解更多\n" +
            "可点击: 立即下载 {cta,Button} [500,840]"

        assertFalse(PageGuard.isEmbeddedAdSearchResults(dump))
        assertTrue(PageGuard.isDownloadWall(dump))
    }

    @Test
    fun 页面防护_活跃激励广告识别继续观看入口() {
        val dump = "当前应用: com.any.reward.app\n" +
            "页面内容: 广告 43秒后可领奖励 再看43秒可领奖励 +238金币\n" +
            "可点击: 继续观看 {,Button} [499,538]\n" +
            "可点击: 坚持退出 {,TextView} [329,605]\n" +
            "可点击: 下载并打开得10000金币 {,Button} [499,945]"

        assertEquals("继续观看", PageGuard.rewardContinueLabel(dump, 43))
        assertNull(PageGuard.rewardContinueLabel(dump, null))
    }

    @Test
    fun 奖励完成层_完成提示旁的无名关闭控件可识别() {
        val dump = "当前应用: com.any.reward.app\n" +
            "页面内容: 已成功领取奖励 广告 点击并浏览15秒 额外得330金币\n" +
            "可点击: 已成功领取奖励 {,TextView} [190,66]\n" +
            "可点击: × {,ImageView} [327,66]\n" +
            "可点击: 点击并浏览15秒 额外得330金币 {,Button} [499,840]"

        val result = PageGuard.rewardCompletionExit(dump)
        assertNotNull(result)
        assertEquals("×", result!!.closeLabel)
    }

    @Test
    fun 奖励完成层_合并结果和关闭图标可识别() {
        val dump = "当前应用: com.any.reward.app\n" +
            "可点击: 已成功领取奖励 × {result_close,ViewGroup} [250,66]\n" +
            "可点击: 点击并浏览15秒 额外得330金币 {cta,Button} [499,840]"

        assertEquals("×", PageGuard.rewardCompletionExit(dump)?.closeLabel)
    }

    @Test
    fun 奖励完成层_底部广告转化按钮不能当退出() {
        val dump = "当前应用: com.any.reward.app\n" +
            "页面内容: 已成功领取奖励 广告\n" +
            "可点击: 点击并浏览15秒 额外得330金币 {,Button} [499,840]"

        assertTrue(PageGuard.hasRewardCompletion(dump))
        assertNull(PageGuard.rewardCompletionExit(dump)?.closeLabel)
    }

    @Test
    fun 奖励完成层_挽留按钮不能当退出且优先坚持退出() {
        val withSafeExit = "当前应用: com.any.reward.app\n" +
            "页面内容: 已成功领取奖励 广告\n" +
            "可点击: 坚持退出 {,TextView} [355,430]\n" +
            "可点击: 再看一个再领500金币 {,Button} [499,690]"
        assertEquals("坚持退出", PageGuard.rewardCompletionExit(withSafeExit)?.closeLabel)

        val withoutSafeExit = "当前应用: com.any.reward.app\n" +
            "页面内容: 已成功领取奖励 广告\n" +
            "可点击: 再看一个再领500金币 {,Button} [499,690]"
        assertTrue(PageGuard.hasRewardCompletion(withoutSafeExit))
        assertNull(PageGuard.rewardCompletionExit(withoutSafeExit)?.closeLabel)
    }

    @Test
    fun 奖励完成层_完成提示同一行的无名关闭控件可识别() {
        val dump = "当前应用: com.any.reward.app\n" +
            "页面内容: 已成功领取奖励 广告 购买后确认收货最高再得金币\n" +
            "可点击: 已成功领取奖励 {countdown_info_container,LinearLayout} [263,87]\n" +
            "可点击: 无文字元素 {video_countdown_end_icon,ImageView} [736,87]\n" +
            "可点击: 黛青丝个护旗舰店 {,ViewGroup} [499,496]\n" +
            "可点击: 点击并浏览15秒 额外得330金币 {,Button} [499,840]"

        val result = PageGuard.rewardCompletionExit(dump)
        assertNotNull(result)
        assertEquals("无文字元素", result!!.closeLabel)
    }

    @Test
    fun 奖励完成层_普通页面和活跃倒计时不触发() {
        val taskCenter = "当前应用: com.any.reward.app\n" +
            "页面内容: 任务中心 看广告赚金币 立即领取\n" +
            "可点击: 立即领取 {,Button} [499,320]"
        val countdown = "当前应用: com.any.reward.app\n" +
            "页面内容: 已成功领取奖励 15秒后可领下一轮\n" +
            "可点击: × {,ImageView} [327,66]"

        assertFalse(PageGuard.hasRewardCompletion(taskCenter))
        assertNull(PageGuard.rewardCompletionExit(taskCenter))
        // 活跃倒计时由调用方守卫阻止退出，这里只保证识别器不凭空制造证据。
        assertTrue(PageGuard.hasRewardCompletion(countdown))
    }

    // ========== PopupGuard：通用阻塞性弹窗硬关闭层 ==========

    @Test
    fun 弹窗硬关闭_更新提示命中且给出安全关闭控件() {
        val dump = "当前应用: com.any.app\n" +
            "页面内容: 发现新版本 v8.2.0 优化性能体验\n" +
            "可点击: 立即更新 {btn_update,TextView} [500,700]\n" +
            "可点击: 以后再说 {btn_cancel,TextView} [500,840]"
        val plan = PopupGuard.plan("帮我看一下今天的金币收益", dump)
        assertNotNull("更新弹窗应被识别", plan)
        assertEquals("以后再说", plan!!.closeLabel)
        assertTrue("转化按钮绝不能作为关闭控件", "立即更新" != plan.closeLabel)
    }

    @Test
    fun 弹窗硬关闭_激励播放页绝不代点() {
        val dump = "当前应用: com.any.reward.app\n" +
            "页面内容: 看完广告可得88金币 15s后可领取\n" +
            "可点击: 关闭 {btn_close,ImageView} [940,80]\n" +
            "可点击: 继续观看得奖励 {btn_continue,TextView} [500,700]"
        assertNull("看广告领奖过程不是弹窗，不能被中断", PopupGuard.plan("看广告刷金币", dump))
    }

    @Test
    fun 弹窗硬关闭_任务本身要更新时不拦() {
        val dump = "当前应用: com.any.app\n" +
            "页面内容: 发现新版本 v8.2.0 立即更新\n" +
            "可点击: 以后再说 {btn_cancel,TextView} [500,840]"
        assertNull("用户就是要更新，更新弹窗是任务目标", PopupGuard.plan("帮我把淘宝更新到最新版", dump))
    }

    @Test
    fun 弹窗硬关闭_普通信息流不误伤() {
        val dump = "当前应用: com.any.video.app\n" +
            "页面内容: 推荐 视频 评论182 分享\n" +
            "可点击: 关闭 {btn_close,ImageView} [940,80]"
        assertNull("无弹窗特征的普通页面不能触发", PopupGuard.plan("刷视频", dump))
    }

    @Test
    fun 弹窗硬关闭_公告加温馨提示双弱特征命中() {
        val dump = "当前应用: com.any.app\n" +
            "页面内容: 活动公告 温馨提示：活动规则调整\n" +
            "可点击: 我知道了 {btn_ok,TextView} [500,800]"
        val plan = PopupGuard.plan("刷视频赚金币", dump)
        assertNotNull("双弱特征+关闭控件应触发", plan)
        assertEquals("我知道了", plan!!.closeLabel)
    }

    @Test
    fun 弹窗硬关闭_没有安全关闭控件时不动手() {
        val dump = "当前应用: com.any.app\n" +
            "页面内容: 发现新版本 v8.2.0 立即更新\n" +
            "可点击: 立即更新 {btn_update,TextView} [500,700]"
        assertNull("只剩转化按钮时绝不代点，也不盲目返回", PopupGuard.plan("刷视频", dump))
    }

    @Test
    fun 页面防护_非创作任务误入拍摄页可识别() {
        val dump = "当前应用: com.any.video.app\n" +
            "页面内容: 选择音乐 美颜 滤镜 发布\n" +
            "可点击: 发布 {publish,TextView} [900,960]"
        assertTrue(PageGuard.isUnintendedCreationPage("帮我签到领金币", dump))
        assertFalse("创作任务本身不拦", PageGuard.isUnintendedCreationPage("帮我拍视频并发布", dump))
    }

    // ========== AgentEngine 点击链：下载墙/创作页/弹窗硬守卫（兜住批量动作间隙） ==========

    @Test
    fun tap_下载墙页点击被改为退出() {
        val dump = "当前应用: com.any.reward.app\n" +
            "页面内容: 广告 立即下载 红果免费短剧\n" +
            "可点击: 坚持退出 [500,1180]\n" +
            "可点击: 立即下载 [500,1950]"
        val r = exec("do(action=\"Tap\", element=[500,1950])", task = "帮我刷金币", dump = dump)
        assertEquals("__JUMP_BLOCK__", r.message)
        assertFalse("不得执行原点击", r.info.contains("已点击屏幕"))
    }

    @Test
    fun tap_非创作任务误入拍摄页点击被改为退出() {
        val dump = "当前应用: com.any.video.app\n" +
            "页面内容: 选择音乐 美颜 滤镜 发布\n" +
            "可点击: 发布 [900,960]"
        val r = exec("do(action=\"Tap\", element=[900,960])", task = "去签到领金币", dump = dump)
        assertEquals("__JUMP_BLOCK__", r.message)
        assertFalse(r.info.contains("已点击屏幕"))
    }

    @Test
    fun tap_弹窗页点非关闭控件被改为关弹窗() {
        val dump = "当前应用: com.any.app\n" +
            "页面内容: 发现新版本 优化体验\n" +
            "可点击: 查看详情 [500,500]\n" +
            "可点击: 以后再说 [500,840]"
        val r = exec("do(action=\"Tap\", element=[500,500])", task = "帮我刷金币", dump = dump)
        assertEquals("__POPUP_BLOCK__", r.message)
        assertTrue(r.info.contains("弹窗"))
        assertFalse(r.info.contains("已点击屏幕"))
    }

    @Test
    fun tap_弹窗页点关闭控件本身放行() {
        val dump = "当前应用: com.any.app\n" +
            "页面内容: 发现新版本 优化体验\n" +
            "可点击: 以后再说 [500,840]"
        val r = exec("do(action=\"Tap\", element=[500,840])", task = "帮我刷金币", dump = dump)
        assertFalse("模型自己在关弹窗时不应被拦截", r.message == "__JUMP_BLOCK__")
    }

    @Test
    fun 奖励倒计时_读屏可识别且数字下降算进展() {
        val cooldown = "限时专区 等倒计时结束再来吧~ 看广告赚金币 最高20000金币 15:43"
        assertEquals(15 * 60 + 43, AgentEngine.rewardCountdownSeconds(cooldown))
        assertEquals(48, AgentEngine.rewardCountdownSeconds("看广告奖励 还需48秒"))
        assertEquals(20, AgentEngine.rewardCountdownSeconds("广告 20秒后可领奖励 立即下载"))
        assertEquals(90, AgentEngine.rewardCountdownSeconds("奖励 倒计时1分30秒"))
        assertNull(AgentEngine.rewardCountdownSeconds("任务中心 看广告赚金币 20分钟看一次广告"))

        assertTrue(AgentEngine.rewardCountdownProgressed(943, 913))
        assertFalse(AgentEngine.rewardCountdownProgressed(943, 943))
        assertFalse(AgentEngine.rewardCountdownProgressed(null, 913))
    }

    // ========== protocolRetryNote：协议违规纠正提示（真机协议复读问题回归） ==========

    @Test
    fun 协议纠正_复读占位符触发强纠正() {
        val note = AgentEngine.protocolRetryNote("[旧页面内容已丢弃；只根据最新页面决策]")
        assertTrue("复读型违规应点名复读", note.contains("复读"))
        assertTrue("强纠正必须给出动作格式", note.contains("<answer>"))
    }

    @Test
    fun 协议纠正_新占位符同样触发强纠正() {
        val note = AgentEngine.protocolRetryNote("（历史已省略）")
        assertTrue("中性占位符被复读时也应触发强纠正", note.contains("复读"))
    }

    @Test
    fun 协议纠正_普通违规给常规提醒() {
        val note = AgentEngine.protocolRetryNote("这个页面我不确定该怎么操作，需要更多说明。")
        assertTrue(note.contains("<answer>"))
        assertFalse("普通违规不应误报复读", note.contains("复读"))
    }

    @Test
    fun 协议恢复_模型识别弹窗但漏协议时优先关闭控件() {
        val dump = "当前应用: com.any.shop\n" +
            "页面内容: 活动弹窗 消费券\n" +
            "可点击: 立即抢购 [500,500]\n" +
            "可点击: 关闭按钮 [500,670]\n" +
            "可点击: 返回 [80,120]"
        val dismiss = AgentEngine.protocolRecoveryDismiss(
            "当前状态：页面有弹窗。首先，我需要关闭这个弹窗。",
            dump
        )
        assertEquals("关闭按钮", dismiss)

        val plainPage = "当前应用: com.any.app\n页面内容: 普通列表"
        assertNull(
            AgentEngine.protocolRecoveryDismiss(
                "当前状态：需要搜索目标。首先，我需要点击搜索框。",
                plainPage
            )
        )
    }

    // ========== 等待卡死熔断（真机抖音视频层 46+轮空转的回归） ==========

    @Test
    fun 等待卡死_阈值配置合理() {
        assertTrue("纠正阈值应小于中止阈值", AgentEngine.WAIT_STALL_CORRECT_AT < AgentEngine.WAIT_STALL_ABORT_AT)
        assertTrue("中止阈值应远小于80轮上限", AgentEngine.WAIT_STALL_ABORT_AT <= 16)
    }

    @Test
    fun 等待卡死_纠正文案给出替代动作() {
        val note = AgentEngine.waitStallCorrectNote(4)
        assertTrue(note.contains("禁止再输出 Wait"))
        assertTrue("应提示 Back 或点击按钮", note.contains("Back") && note.contains("关闭"))
    }

    @Test
    fun 等待卡死_中止文案说明原因和建议() {
        assertTrue(AgentEngine.WAIT_STALL_ABORT_MESSAGE.contains("防卡死"))
        assertTrue(AgentEngine.WAIT_STALL_ABORT_MESSAGE.contains("手动"))
    }

    // ========== 目标App冷启动判断（真机：上轮死在拍摄页污染下轮开局） ==========

    @Test
    fun 冷启动_普通任务要冷启动() {
        assertTrue(AgentEngine.shouldColdStart("打开抖音极速版，去赚钱页签到领金币"))
        assertTrue(AgentEngine.shouldColdStart("帮我在淘宝搜奶茶"))
    }

    @Test
    fun 冷启动_恢复类指令不冷启动() {
        assertFalse(AgentEngine.shouldColdStart("继续刚才的任务"))
        assertFalse(AgentEngine.shouldColdStart("接着做"))
        assertFalse(AgentEngine.shouldColdStart("resume the task"))
    }

    // ========== 无进展熔断（真机抖音视频层79轮换坐标空转的回归） ==========

    @Test
    fun 无进展熔断_阈值低于80轮上限且高于软硬警告() {
        assertTrue(AgentEngine.PROGRESS_STALL_ABORT_AT in 9..79)
        assertTrue("应给软/硬警告留出空间", AgentEngine.PROGRESS_STALL_ABORT_AT >= 16)
    }

    @Test
    fun 无进展熔断_文案说明原因和出路() {
        assertTrue(AgentEngine.PROGRESS_STALL_ABORT_MESSAGE.contains("防卡死"))
        assertTrue(AgentEngine.PROGRESS_STALL_ABORT_MESSAGE.contains("手动"))
        assertTrue("应带上实际轮数", AgentEngine.PROGRESS_STALL_ABORT_MESSAGE.contains("16"))
    }

    // ========== 页面指纹归一化（真机：视频流评论数跳动骗过冻结判定） ==========

    @Test
    fun 页面指纹_数字跳动不影响判定() {
        val a = "评论182 拍同款 某音乐名"
        val b = "评论183 拍同款 某音乐名"
        assertEquals(AgentEngine.fingerprint(a), AgentEngine.fingerprint(b))
    }

    @Test
    fun 页面指纹_结构变化仍可判别() {
        val a = AgentEngine.fingerprint("评论182 拍同款")
        val b = AgentEngine.fingerprint("私信 拍同款")
        assertNotEquals(a, b)
        assertEquals("", AgentEngine.fingerprint(" 123  456 "))
    }

    // ========== think-动作一致性（真机：思考要点[我]，坐标落在屏幕中上部） ==========

    @Test
    fun 一致性_导航意图但坐标在中上部要纠正() {
        val a = "当前是视频页。底部有导航栏：首页、商城、+、消息、我。我应该先点击\"我\"进入个人中心。do(action=\"Tap\", element=[499,834])"
        assertTrue(AgentEngine.thinkActNavMismatch(a, 499, 834))
    }

    @Test
    fun 一致性_坐标已在底部导航不纠正() {
        val a = "点击右下角的我。do(action=\"Tap\", element=[930,965])"
        assertFalse(AgentEngine.thinkActNavMismatch(a, 930, 965))
    }

    @Test
    fun 一致性_钱包等具体目标不算导航意图() {
        val a = "进入我的钱包查看余额。do(action=\"Tap\", element=[500,400])"
        assertFalse(AgentEngine.thinkActNavMismatch(a, 500, 400))
    }

    @Test
    fun 一致性_无导航意图不纠正() {
        val a = "点击屏幕中央的视频。do(action=\"Tap\", element=[500,450])"
        assertFalse(AgentEngine.thinkActNavMismatch(a, 500, 450))
    }

    // ========== 判断力增强回归：默认模型 / 思考空间 / 截图质量 / 稳定等待 / 读屏吸附 ==========

    @Test
    fun 默认厂商_智谱默认模型是AutoGLM手机脑() {
        val zhipu = Vendors.byId("zhipu")
        assertEquals("autoglm-phone", zhipu.model)
        assertEquals("autoglm-phone", zhipu.models.first())
    }

    @Test
    fun autoGlmPrompt_返回导航必须使用Back() {
        val prompt = AgentEngine.autoGlmSystemPrompt()
        assertTrue(prompt.contains("【返回导航】"))
        assertTrue(prompt.contains("必须输出 do(action=\"Back\")"))
    }

    @Test
    fun 停止悬浮球_单击直接弹确认窗且拖动不弹() {
        assertTrue("收起态第一次点按也要直接打开确认窗", StopBubbleGesturePolicy.shouldTogglePopupOnActionUp(dragging = false))
        assertFalse("拖动松手只应保留位置，不应误开确认窗", StopBubbleGesturePolicy.shouldTogglePopupOnActionUp(dragging = true))
    }

    @Test
    fun autoGlmPrompt_思考过程不再被120字上限压死() {
        val p = AgentEngine.autoGlmSystemPrompt()
        assertFalse("思考不应再被120字上限压缩", p.contains("不超过120个字"))
        assertTrue("仍要求先思考再输出动作", p.contains("<think>{think}</think>"))
    }

    @Test
    fun 视觉截图_质量不低于80保住小字() {
        assertTrue("截图质量过低会让小按钮文字发糊", AgentEngine.VISION_JPEG_QUALITY >= 80)
    }

    @Test
    fun 动作后稳定等待_上限放宽且快页面不受影响() {
        assertTrue("点击后900ms常还在转场", AgentEngine.actionSettleMaxMs("do(action=\"Tap\", element=[500,300])") >= 2000)
        assertTrue("启动应用加载更慢", AgentEngine.actionSettleMaxMs("do(action=\"Launch\", app=\"淘宝\")") >= 3000)
        assertTrue("滑动后也要给页面滚动留时间", AgentEngine.actionSettleMaxMs("do(action=\"Swipe\", start=[500,700], end=[500,300])") >= 1500)
    }

    @Test
    fun 读屏吸附_任务文字完全命中时跨屏纠正到真实控件() {
        val dump = "当前应用: com.any.app\n" +
            "可点击: 订单 [820,180]\n" +
            "可点击: 首页 [500,500]"
        val snapped = AgentEngine.correctTapToScreenElement(Pair(500, 500), "打开京东看订单", dump)
        assertEquals(Pair(820, 180), snapped)
    }

    @Test
    fun 读屏吸附_一般匹配仍要求视觉坐标落在元素附近() {
        val dump = "当前应用: com.any.app\n" +
            "可点击: 开宝箱得金币 [820,180]"
        val kept = AgentEngine.correctTapToScreenElement(Pair(500, 500), "帮我开宝箱", dump)
        assertEquals(Pair(500, 500), kept)
    }

    @Test
    fun 无进展保护_换着点也不能绕过页面冻结计数() {
        var noProgress = 0
        repeat(16) {
            if (AgentEngine.isNoProgressRound(pageChanged = false)) noProgress++ else noProgress = 0
        }
        assertTrue("连续换动作但页面不变必须熔断", noProgress >= AgentEngine.PROGRESS_STALL_ABORT_AT)
        assertEquals(0, if (AgentEngine.isNoProgressRound(pageChanged = true)) 1 else 0)
    }

    // ========== 体检修复回归（2026-10-26 第三轮 + 追加轮） ==========

    @Test
    fun 页面防护_更多直播单词不再误判直播间() {
        // 任务中心：直播广场入口 + 抽大奖活动卡。单词命中就强退会反复打断正常任务（体检追加轮 P1-7）
        val taskCenter = "当前应用: com.any.app\n" +
            "页面内容: 任务中心 签到 邀请好友 直播广场 抽大奖\n" +
            "可点击: 去直播广场 [500,300]\n" +
            "可点击: 抽大奖 [500,500]"
        assertFalse(PageGuard.isLiveRoom(taskCenter))
        // 只有「更多直播」胶囊、无直播间互动特征的信息流同样不判
        val feed = "当前应用: com.any.app\n页面内容: 关注 推荐 更多直播\n可点击: 更多直播 [500,80]"
        assertFalse(PageGuard.isLiveRoom(feed))
        // 直播间特有互动特征（说点什么/礼物面板）仍可识别（v1.11.10 真机用例形态）
        val live = "当前应用: com.any.app\n" +
            "页面内容: 关注 2917观看 更多直播 说点什么\n" +
            "可点击: 无文字元素 [936,75]\n" +
            "可点击: 底部栏礼物面板按钮 [699,912]"
        assertTrue(PageGuard.isLiveRoom(live))
    }

    @Test
    fun 页面防护_参加抽奖任务的立即参与放行() {
        // 连接动词两侧对称：任务点名参与类动作时「立即参与」是目的地（体检 P2-1）
        assertFalse(PageGuard.isOffMissionTap("去参加幸运抽奖", "立即参与"))
        // 任务没点名参与时，「立即参与」对广告任务仍是投币/报名路线，保持拦截
        assertTrue(PageGuard.isOffMissionTap("看广告赚金币", "立即参与"))
    }

    @Test
    fun 页面防护_对齐入口的多段混杂语境放行() {
        // 奖励卡副标常见「邀请好友再得XX」，多段混杂不再连坐拦截（体检 P2-7）
        assertFalse(PageGuard.isOffMissionTap("看广告赚金币", "去观看", "看广告得金币 / 邀请好友得18888"))
        // 语境整体指向单一其他玩法时仍拦截
        assertTrue(PageGuard.isOffMissionTap("看广告赚金币", "去观看", "幸运抽奖 转盘赢大奖"))
    }

    @Test
    fun 页面防护_关闭控件语义只认短标签() {
        // 「×」必须整标签就是符号；「金币×100」是权益文案不是关闭控件（体检 P2-8）
        assertFalse(PageGuard.isDismissControl("金币×100", 500, 500))
        assertTrue(PageGuard.isDismissControl("×", 900, 60))
        // 长文案含「关闭」是转化按钮（体检 P2-8）
        assertFalse(PageGuard.isDismissControl("关闭广告并领取奖励", 500, 500))
        // 空白标签右上兜底收紧到屏幕上 1/3（体检 P2-8）
        assertTrue(PageGuard.isDismissControl("", 908, 100))
        assertFalse(PageGuard.isDismissControl("", 908, 500))
    }

    @Test
    fun 购物任务词表三处统一() {
        // 「买」字购物任务不被商品页/支付页守卫打回（体检 P1-6）
        assertTrue(PageGuard.isCommerceTask("帮我买杯奶茶"))
        assertTrue(PageGuard.isCommerceTask("在淘宝下单买一箱牛奶"))
        assertFalse(PageGuard.isCommerceTask("刷视频赚金币"))
        assertFalse(PageGuard.isCommerceTask("不要支付，只看免费广告"))
    }

    @Test
    fun 商品页守卫_搜索比较类浏览任务可看详情但不豁免交易动作() {
        assertTrue(PageGuard.isProductResearchTask("打开淘宝搜索保温杯，然后找销量最高的商品"))
        assertTrue(PageGuard.isProductResearchTask("搜索蓝牙耳机，看看评价和价格"))
        assertFalse(PageGuard.isProductResearchTask("搜索保温杯并加入购物车"))
        assertFalse(PageGuard.isProductResearchTask("不要搜索了，回来"))
    }

    @Test
    fun 奖励倒计时_无语境的时分秒不误报() {
        // 视频时长/页顶时钟不再被当成奖励倒计时（体检 P2-3）
        assertNull(AgentEngine.rewardCountdownSeconds("看广告赚金币 视频时长 03:45"))
        assertNull(AgentEngine.rewardCountdownSeconds("金币收益页 10:08"))
        // 带语境词的照常识别
        assertEquals(205, AgentEngine.rewardCountdownSeconds("看广告奖励 还需03:25"))
    }

    @Test
    fun 奖励完成层_glyph标签带坐标供直点() {
        // ×/无文字元素类 closeLabel 走坐标直点，不走 clickText 全树匹配（体检追加轮 P1-8）
        val dump = "当前应用: com.any.app\n" +
            "页面内容: 已成功领取 金币×100\n" +
            "可点击: 已成功领取奖励 [500,120]\n" +
            "可点击: 无文字元素 [930,110]"
        val exit = PageGuard.rewardCompletionExit(dump)
        assertNotNull(exit)
        assertEquals("已成功领取", exit!!.evidence)
        assertEquals("无文字元素", exit.closeLabel)
        assertTrue(PageGuard.isGlyphCloseQuery(exit.closeLabel))
        assertEquals(930, exit.x)
        assertEquals(110, exit.y)
    }

    @Test
    fun 弹窗代点_只认关闭档不认返回() {
        // 「返回」是 500 分档：弱特征误判到正常页面时不能替用户按返回（体检 P1-2）
        val settingsPage = "当前应用: com.any.app\n" +
            "页面内容: 消息通知 系统通知 消息通知设置\n" +
            "可点击: 返回 [80,90]\n" +
            "可点击: 消息通知 [500,300]"
        assertNull(PopupGuard.plan("查看消息设置", settingsPage))
        // 真弹窗有 400 分档关闭控件时照常代点
        val popup = "当前应用: com.any.app\n" +
            "页面内容: 发现新版本 优化体验\n" +
            "可点击: 立即更新 [500,420]\n" +
            "可点击: 以后再说 [500,560]"
        assertEquals("以后再说", PopupGuard.plan("帮我刷金币", popup)?.closeLabel)
    }

    @Test
    fun 任务分类_数量限定的广告任务有终点() {
        // 「看一个广告」不被当成循环型任务无限拒绝 finish（体检 P2-2）
        assertTrue(TaskCompletionVerifier.hasQuantityConstraint("看一个广告"))
        assertTrue(TaskCompletionVerifier.hasQuantityConstraint("刷10条视频"))
        assertFalse(TaskCompletionVerifier.hasQuantityConstraint("看广告赚金币"))
    }

    @Test
    fun 直播间意图_特别不当成否定() {
        // 「特别想看直播」不是否定句（体检 P3-10）
        assertTrue(PageGuard.isLiveIntentTask("特别想看直播"))
        assertFalse(PageGuard.isLiveIntentTask("看广告赚金币"))
    }

    @Test
    fun 奖励倒计时_X分Y秒无语境不误报() {
        // 视频时长「3分45秒」与「03:45」同族：无倒计时语境词不能触发强制等待（体检 P2-3 同族）
        assertNull(AgentEngine.rewardCountdownSeconds("金币任务页 本集视频时长 3分45秒"))
        // 带语境词的照常识别
        assertEquals(205, AgentEngine.rewardCountdownSeconds("金币任务 还需3分25秒后领"))
    }

    @Test
    fun 倒计时参数透传_动作层页面守卫让路() {
        // rewardCountdownSeconds 必须从主循环穿透 execAutoGLM 到 execSingleAutoGLM，
        // 否则激励广告播放中批量动作间隙仍会被下载墙守卫强退（体检 P1-1 回归）
        val dump = "当前应用: com.any.ad.app\n" +
            "页面内容: 金币奖励 下载并体验 立即下载\n" +
            "可点击: 关闭 [500,500]"
        val blocked = AgentEngine.execAutoGLM(
            null, "do(action=\"Tap\", element=[500,500])", 1080, 2400, "看广告赚金币", dump
        )
        assertEquals("__JUMP_BLOCK__", blocked.message)
        val yielded = AgentEngine.execAutoGLM(
            null, "do(action=\"Tap\", element=[500,500])", 1080, 2400, "看广告赚金币", dump,
            rewardCountdownSeconds = 120
        )
        assertNull("倒计时活跃时下载墙守卫必须让路", yielded.message)
    }

    @Test
    fun 动作拆分_参数引号内的do不算动作边界() {
        // 单动作：text 里的字面 "do(" 不拆分，整条原样保留（体检第四轮 P3-1）
        val single = "do(action=\"Type\", text=\"点击do(按钮\")"
        assertEquals(1, AgentEngine.parseAutoGlmActions(single).size)
        // 批量：第一段 text 含 "do(" 时不得从字符串中间切断，两段都完整
        val batch = "do(action=\"Type\", text=\"说do(x\")\ndo(action=\"Tap\", element=[500,500])"
        val actions = AgentEngine.parseAutoGlmActions(batch)
        assertEquals(2, actions.size)
        assertEquals("do(action=\"Type\", text=\"说do(x\")", actions[0])
    }

    @Test
    fun 经验评审_只认机器可读结论() {
        assertTrue(TaskOutcomeJudge.parse("""{"SUCCESS":true}""") == true)
        assertTrue(TaskOutcomeJudge.parse("""{"SUCCESS": false, "reason": "页面没有保存成功"}""") == false)
        // 模型附带解释里出现“失败”不能覆盖 SUCCESS=true；字段冲突时不能污染经验库
        assertNull(TaskOutcomeJudge.parse("我判断任务失败，但忘了输出 SUCCESS 字段"))
        assertNull(TaskOutcomeJudge.parse("""{"SUCCESS":"unknown"}"""))
    }

    @Test
    fun 经验裁决_机械确认成功不允许被模型改判失败() {
        // 数量型广告任务机械计数达标、目标校验确认等强证据必须强制记成功（v1.13.4 回归）
        assertEquals(true, ExperienceVerdict.resolve(targetNeverReached = false, verifiedByCheck = true))
        // 目标应用从未到达时，即便其他证据模糊也不允许记成功
        assertEquals(false, ExperienceVerdict.resolve(targetNeverReached = true, verifiedByCheck = true))
    }

    @Test
    fun 经验裁决_目标未到达强制失败() {
        assertEquals(false, ExperienceVerdict.resolve(targetNeverReached = true, verifiedByCheck = false))
    }

    @Test
    fun 经验裁决_没有机械证据时交给模型判定() {
        assertNull(ExperienceVerdict.resolve(targetNeverReached = false, verifiedByCheck = false))
    }

    @Test
    fun 外跳陷阱坐标守卫_同一位置机械拦截() {
        // 广告结算层的“免看广告立得金币”类入口实测会把页面拉出目标应用；
        // 坐标桶按模型输出坐标分桶，同一按钮再次出现必须拦下（v1.13.5 回归）
        val answer = "do(action=\"Tap\", element=[499,792])"
        val bucket = JumpTrapGuard.bucketFromAnswer(answer)
        assertEquals(JumpTrapGuard.bucketOf(499, 792), bucket)
        assertTrue(JumpTrapGuard.isBlocked(bucket, setOf("33_52")))
        // 相邻桶不误伤：别的按钮位置不受影响
        assertFalse(JumpTrapGuard.isBlocked(JumpTrapGuard.bucketOf(520, 792), setOf("33_52")))
        // 非 Tap 动作不拦
        assertNull(JumpTrapGuard.bucketFromAnswer("do(action=\"Back\")"))
        assertNull(JumpTrapGuard.bucketFromAnswer("do(action=\"Wait\", duration=\"10 seconds\")"))
    }

    @Test
    fun 激励广告直播素材等待窗口_不少于60秒() {
        // 直播带货类激励广告普遍 30-60 秒；等待窗口太短会在领奖前被直播间守卫误退（v1.13.5 回归）
        assertTrue(PageGuard.REWARD_LIVE_EMBED_MAX_ROUNDS >= 12)
    }

    @Test
    fun 重复滑动_页面变化是刷视频不是空转() {
        // 抖音极速版实测：刷1分钟视频就是同方向连续滑动，每次都换新视频；
        // 页面在变化就不能计入空转，否则任务会被6轮熔断误杀（v1.13.5 回归）
        val sig = "s_33_46"
        assertFalse(ActionProgress.repeatedSwipeIdle(sig, sig, pageChanged = true))
        assertTrue(ActionProgress.repeatedSwipeIdle(sig, sig, pageChanged = false))
        assertFalse(ActionProgress.repeatedSwipeIdle("s_33_45", sig, pageChanged = false))
    }

    @Test
    fun 直播间与继续观看冲突_短窗口内不再自动恢复() {
        // 刚强退直播间（第10轮）后，1-3轮内出现“继续观看”不能马上送回去；
        // 超过短窗口则是正常的新激励广告，可以恢复。
        assertTrue(PageGuard.isRecentLiveExit(10, 11))
        assertTrue(PageGuard.isRecentLiveExit(10, 13))
        assertFalse(PageGuard.isRecentLiveExit(10, 14))
        assertFalse(PageGuard.isRecentLiveExit(null, 11))
    }

    @Test
    fun 广告入口后的直播画面_优先当广告播放() {
        val entry = "页面内容: 任务中心 领福利 看广告得金币"
        val liveAd = "页面内容: 直播中 说点什么 送礼物"
        assertTrue(PageGuard.hasRewardAdEntry(entry))
        assertTrue(PageGuard.isRecentRewardEntry(4, 8))
        assertFalse(PageGuard.isRecentRewardEntry(4, 10))
        assertTrue(PageGuard.hasExplicitLiveExit("页面内容: 退出直播间"))
        // 没有显式退出控件时不能按结构角标强退，否则会作废激励广告进度
        assertFalse(PageGuard.hasExplicitLiveExit(liveAd))
    }
}
