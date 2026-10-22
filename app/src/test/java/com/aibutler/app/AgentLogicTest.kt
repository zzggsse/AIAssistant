package com.aibutler.app

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
            assertEquals("包名 $pkg 未拦截商单页点击", "__COMMERCE_BLOCK__", r.message)
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
    fun tap_购物类任务不误伤商单点击() {
        // 用户明确要买东西时，商单页不应拦截
        val dump = "当前应用: com.sankuai.meituan\n页面内容: 提交订单\n可点击: 提交订单 [500,300]"
        val r = exec("do(action=\"Tap\", element=[500,300])", task = "帮我买一杯奶茶下单", dump = dump)
        // 没有被拦截才会走到执行层（NO_A11Y）
        assertEquals(DeviceTools.NO_A11Y, r.info)
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
        assertFalse(PageGuard.isForbiddenPayment("帮我下单购买", dump))
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
    fun 页面防护_通用奖励卡只拦高风险语义() {
        assertTrue(PageGuard.isUnsafeRewardTap("看广告刷金币", "看直播领金币"))
        assertTrue(PageGuard.isUnsafeRewardTap("看广告刷金币", "下载APP得奖励"))
        assertFalse(PageGuard.isUnsafeRewardTap("看广告刷金币", "观看广告领金币"))
        assertFalse(PageGuard.isUnsafeRewardTap("看直播领金币", "进入直播间"))
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
    fun 奖励倒计时_读屏可识别且数字下降算进展() {
        val cooldown = "限时专区 等倒计时结束再来吧~ 看广告赚金币 最高20000金币 15:43"
        assertEquals(15 * 60 + 43, AgentEngine.rewardCountdownSeconds(cooldown))
        assertEquals(48, AgentEngine.rewardCountdownSeconds("看广告奖励 还需48秒"))
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
}
