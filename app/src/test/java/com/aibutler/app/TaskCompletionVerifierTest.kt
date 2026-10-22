package com.aibutler.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务完成判定契约的本地回归测试（不需要手机：gradle testDebugUnitTest）。
 *
 * 覆盖三类决策：
 *  - classify：开放式挂机任务 vs 有终点任务的分界（刷视频赚金币 ≠ 打开微信）
 *  - launchCheckFor：单纯"打开App"才给前台判据，组合指令不给
 *  - 判据稳定性双读与时长格式化
 */
class TaskCompletionVerifierTest {

    // ========== classify：任务类别 ==========

    @Test
    fun classify_打开类是有终点任务() {
        assertEquals(TaskCompletionVerifier.TaskClass.BOUNDED, TaskCompletionVerifier.classify("打开微信"))
        assertEquals(TaskCompletionVerifier.TaskClass.BOUNDED, TaskCompletionVerifier.classify("帮我打开淘宝"))
        assertEquals(TaskCompletionVerifier.TaskClass.BOUNDED, TaskCompletionVerifier.classify("启动设置"))
    }

    @Test
    fun classify_刷视频赚金币是开放式() {
        assertEquals(TaskCompletionVerifier.TaskClass.OPEN_ENDED, TaskCompletionVerifier.classify("去抖音刷视频赚金币"))
        assertEquals(TaskCompletionVerifier.TaskClass.OPEN_ENDED, TaskCompletionVerifier.classify("刷视频赚钱"))
        assertEquals(TaskCompletionVerifier.TaskClass.OPEN_ENDED, TaskCompletionVerifier.classify("看广告赚金币"))
        assertEquals(TaskCompletionVerifier.TaskClass.OPEN_ENDED, TaskCompletionVerifier.classify("去快手挂机做任务"))
        assertEquals(TaskCompletionVerifier.TaskClass.OPEN_ENDED, TaskCompletionVerifier.classify("刷会儿抖音"))
        assertEquals(TaskCompletionVerifier.TaskClass.OPEN_ENDED, TaskCompletionVerifier.classify("逛一下小红书"))
        assertEquals(TaskCompletionVerifier.TaskClass.OPEN_ENDED, TaskCompletionVerifier.classify("浏览直播"))
    }

    @Test
    fun classify_带数量时长的消费是有终点任务() {
        assertEquals(TaskCompletionVerifier.TaskClass.BOUNDED, TaskCompletionVerifier.classify("刷10条视频"))
        assertEquals(TaskCompletionVerifier.TaskClass.BOUNDED, TaskCompletionVerifier.classify("看半小时视频"))
        assertEquals(TaskCompletionVerifier.TaskClass.BOUNDED, TaskCompletionVerifier.classify("刷3个广告领金币"))
    }

    @Test
    fun classify_否定词打头的消费不是开放式() {
        assertEquals(TaskCompletionVerifier.TaskClass.BOUNDED, TaskCompletionVerifier.classify("不要刷视频了，回来"))
        assertEquals(TaskCompletionVerifier.TaskClass.BOUNDED, TaskCompletionVerifier.classify("别逛淘宝了"))
    }

    @Test
    fun classify_签到是一次性动作不是挂机() {
        assertEquals(TaskCompletionVerifier.TaskClass.BOUNDED, TaskCompletionVerifier.classify("帮我签到"))
        assertEquals(TaskCompletionVerifier.TaskClass.BOUNDED, TaskCompletionVerifier.classify("打开京东签到"))
    }

    // ========== launchCheckFor：打开App的机械判据 ==========

    private val wechat = Pair("微信", "com.tencent.mm")

    @Test
    fun 判据_单纯打开给前台判据() {
        val check = TaskCompletionVerifier.launchCheckFor("打开微信", wechat)
        assertTrue(check is TaskCompletionVerifier.SuccessCheck.ForegroundApp)
        assertEquals("com.tencent.mm", (check as TaskCompletionVerifier.SuccessCheck.ForegroundApp).pkg)
    }

    @Test
    fun 判据_敬语和一下也能识别() {
        assertTrue(TaskCompletionVerifier.launchCheckFor("帮我打开微信", wechat)
            is TaskCompletionVerifier.SuccessCheck.ForegroundApp)
        assertTrue(TaskCompletionVerifier.launchCheckFor("打开一下微信", wechat)
            is TaskCompletionVerifier.SuccessCheck.ForegroundApp)
        assertTrue(TaskCompletionVerifier.launchCheckFor("请打开微信", wechat)
            is TaskCompletionVerifier.SuccessCheck.ForegroundApp)
    }

    @Test
    fun 判据_组合指令不给判据() {
        // 第二个动作存在时，应用打开≠任务完成
        assertFalse(TaskCompletionVerifier.launchCheckFor("打开微信并扫码", wechat)
            is TaskCompletionVerifier.SuccessCheck.ForegroundApp)
        assertFalse(TaskCompletionVerifier.launchCheckFor("打开微信发消息给妈妈", wechat)
            is TaskCompletionVerifier.SuccessCheck.ForegroundApp)
        assertFalse(TaskCompletionVerifier.launchCheckFor("打开淘宝然后搜索耳机", Pair("淘宝", "com.taobao.taobao"))
            is TaskCompletionVerifier.SuccessCheck.ForegroundApp)
    }

    @Test
    fun 判据_深目标不给判据() {
        // "进入淘宝店铺"≠打开淘宝：宁可回退模型链路也不提前误判
        assertFalse(TaskCompletionVerifier.launchCheckFor("进入淘宝店铺", Pair("淘宝", "com.taobao.taobao"))
            is TaskCompletionVerifier.SuccessCheck.ForegroundApp)
    }

    @Test
    fun 判据_没匹配到应用或动词不足时返回None() {
        assertTrue(TaskCompletionVerifier.launchCheckFor("打开微信", null)
            is TaskCompletionVerifier.SuccessCheck.None)
        assertTrue(TaskCompletionVerifier.launchCheckFor("看看微信消息", wechat)
            is TaskCompletionVerifier.SuccessCheck.None)
    }

    // ========== contractFor：契约组装 ==========

    @Test
    fun 契约_打开微信是BOUNDED加前台判据() {
        val c = TaskCompletionVerifier.contractFor("打开微信", wechat)
        assertEquals(TaskCompletionVerifier.TaskClass.BOUNDED, c.taskClass)
        assertTrue(c.successCheck is TaskCompletionVerifier.SuccessCheck.ForegroundApp)
        assertEquals(15L * 60_000, c.boundedTimeoutMs)
    }

    @Test
    fun 契约_开放式默认30分钟一轮2分钟宽限() {
        val c = TaskCompletionVerifier.contractFor("去抖音刷视频赚金币", Pair("抖音", "com.ss.android.ugc.aweme"))
        assertEquals(TaskCompletionVerifier.TaskClass.OPEN_ENDED, c.taskClass)
        assertEquals(30L * 60_000, c.sessionCapMs)
        assertEquals(2L * 60_000, c.askGraceMs)
        assertTrue(c.successCheck is TaskCompletionVerifier.SuccessCheck.None)
    }

    @Test
    fun 契约_自定义轮长被夹在5到240分钟() {
        val low = TaskCompletionVerifier.contractFor("刷视频", null, sessionMinutes = 1)
        assertEquals(5L * 60_000, low.sessionCapMs)
        val high = TaskCompletionVerifier.contractFor("刷视频", null, sessionMinutes = 999)
        assertEquals(240L * 60_000, high.sessionCapMs)
    }

    // ========== 稳定性双读与格式化 ==========

    @Test
    fun 稳定性_两次读都在前台才算稳定() {
        assertTrue(TaskCompletionVerifier.isForegroundStable("com.tencent.mm", "com.tencent.mm") { "com.tencent.mm" })
        assertFalse(TaskCompletionVerifier.isForegroundStable("com.tencent.mm", "com.tencent.mm") { "com.android.launcher" })
        assertFalse(TaskCompletionVerifier.isForegroundStable("com.android.launcher", "com.tencent.mm") { "com.tencent.mm" })
        assertFalse(TaskCompletionVerifier.isForegroundStable(null, "com.tencent.mm") { "com.tencent.mm" })
        assertFalse(TaskCompletionVerifier.isForegroundStable("com.tencent.mm", "") { "com.tencent.mm" })
    }

    @Test
    fun 时长格式化() {
        assertEquals("不到1分钟", TaskCompletionVerifier.formatDuration(30_000))
        assertEquals("45分钟", TaskCompletionVerifier.formatDuration(45 * 60_000L))
        assertEquals("1小时", TaskCompletionVerifier.formatDuration(60 * 60_000L))
        assertEquals("1小时30分钟", TaskCompletionVerifier.formatDuration(90 * 60_000L))
        assertEquals("2小时5分钟", TaskCompletionVerifier.formatDuration(125 * 60_000L))
    }
}
