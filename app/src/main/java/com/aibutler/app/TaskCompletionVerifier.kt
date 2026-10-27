package com.aibutler.app

/**
 * 任务完成判定契约（纯函数，不依赖 android.*，可整体单测）。
 *
 * 解决两类真实痛点：
 * 1. 简单任务("打开微信")明明已达成却一直显示"执行中"——完成与否不该只由模型自己判断：
 *    能机械验证的意图(打开App = 目标包名在前台)用代码直接收敛，模型没机会磨蹭也不敢乱 finish；
 * 2. 开放式任务("刷视频赚金币")用户不说停——不能被轮次上限硬杀，改为"会话制"：
 *    每挂机一段时间暂停询问是否继续；用户没点确认就默认继续，直到用户主动收工。
 *
 * 与 TaskGoalVerifier 的分工：那边管"精确值约束"(时间/数量/日期，保存前拦截)，
 * 这里管"任务整体算不算完成"(收敛出口)。两者都遵循同一哲学：刻意不认识具体应用，
 * 新增 App 不需要改这个类。
 *
 * 执行循环的使用顺序（见 MainActivity.runAutoGLMLoop）：
 *   每轮开始 → 先查机械判据(通过=强制完成) → 再查预算(到点收工/询问) → 才轮到模型干活；
 *   模型输出 finish 时 → 机械判据优先裁决 → 无判据才走既有"完成证据挑战"链路。
 */
object TaskCompletionVerifier {

    /** 有明确终点的任务 vs 开放式挂机任务，两者的预算语义完全不同 */
    enum class TaskClass {
        /** 打开微信/定闹钟/发消息…：有终点，超时保险丝到期=失败 */
        BOUNDED,
        /** 刷视频/赚金币/逛淘宝…：无终点，到点询问续期，不点收工就一直挂 */
        OPEN_ENDED
    }

    /** 机械可验证的成功判据。按意图声明，代码在循环里直接查询，不经模型。 */
    sealed class SuccessCheck {
        /** 打开App类：目标包名稳定出现在前台即算完成（无障碍服务零成本可查）。 */
        data class ForegroundApp(val pkg: String, val appName: String) : SuccessCheck()

        /** 无机械判据：沿用模型 finish + 完成证据挑战的既有链路。 */
        object None : SuccessCheck()
    }

    data class Contract(
        val taskClass: TaskClass,
        val successCheck: SuccessCheck,
        /** OPEN_ENDED：每轮挂机上限(毫秒)，到点暂停询问是否继续 */
        val sessionCapMs: Long,
        /** 询问后等用户点按钮的宽限(毫秒)；超时没人点 = 默认继续挂机 */
        val askGraceMs: Long,
        /** BOUNDED：整任务墙钟保险丝(毫秒)。轮次上限/无进展熔断通常先触发，这是最后兜底 */
        val boundedTimeoutMs: Long
    )

    const val DEFAULT_SESSION_MINUTES = 30
    const val DEFAULT_ASK_GRACE_MINUTES = 2L
    const val DEFAULT_BOUNDED_TIMEOUT_MINUTES = 15L

    private fun minutes(ms: Long) = ms * 60_000L

    // ---- 意图识别（正则均为真机语料打磨，改动需同步补单测） ----

    /** 打开/启动类动词：目标App到达前台即算完成 */
    private val launchVerb = Regex("(打开|启动|开启|进入|运行|拉起|切到)")

    /** 开放式：内容消费动词+内容对象（刷视频/看直播/逛小红书/听书…） */
    private val openEndedBrowse = Regex(
        "(刷|看|逛|浏览|听)(一)?(会|会儿|下|一下)?(短?视频|直播|内容|头条|小说|微博|朋友圈|小红书|" +
            "抖音|快手|淘宝|京东|拼多多|哔哩|B站)"
    )

    /** 广告作为明确任务对象：覆盖"看个广告/广告视频/激励广告/浏览广告领金币"等说法 */
    private val explicitAdTask = Regex(
        "(?:观看|看|刷|浏览|播放|点开|点击|做)[^\\n，。;；!！?？]{0,6}?广告|" +
            "广告(?:视频|任务)|激励广告|插屏广告|信息流广告|" +
            "广告[^\\n，。;；!！?？]{0,8}?(?:赚|挣|领|得|拿|换|金币|积分|现金|收益|奖励|钱)"
    )

    private val negatedAdTask = Regex(
        "(?:不要|别再?|禁止|避免|不得)[^\\n，。;；!！?？]{0,6}?" +
            "(?:观看|看|刷|浏览|播放|点开|点击|做)[^\\n，。;；!！?？]{0,6}?广告"
    )

    /** 明确的挂机赚钱/任务循环意图（对应系统提示 18/20.1 的循环型任务；"签到"是一次性动作，不算挂机） */
    private val rewardLoopVerb = Regex(
        "(?:赚|挣|领|得|拿|收|换)(?:金币|金豆|积分|现金|红包|奖励|收益|钱)|" +
            "(?:金币|积分|现金|收益|红包|奖励)(?:任务|收益|中心)|" +
            "赚钱|挣钱|做任务|任务中心|去赚钱|挂机|开宝箱|宝箱"
    )

    /** 明确数量/时长限定：有目标值就交给模型+既有证据链收敛，按 BOUNDED 处理 */
    private val quantified = Regex(
        "\\d+\\s*(条|个|集|篇|名|分钟|分|小时|个小时|秒)|[一二两三四五六七八九十]+\\s*(条|个|集|篇|名)|半小时|[一二两]整?(天|晚|夜|小时)"
    )

    /** 任务带数量/时长限定（「看一个广告」「刷10条视频」）→ 有明确终点，循环型闸门应放行（体检 P2-2） */
    fun hasQuantityConstraint(userText: String): Boolean =
        quantified.containsMatchIn(userText.replace(Regex("\\s+"), ""))

    /** 否定词出现在开放式短语之前 = 用户不是要挂机（"不要刷视频了，回来"） */
    private val negation = Regex("不要|别再?|禁止|避免|不得")

    /** 组合指令保护：命中这些连接词/第二动作的指令不给"打开即完成"判据 */
    private val compoundMarker = Regex(
        "然后|接着|之后|最后|并且|同时|再(看|搜|查|发|打|点)|并|搜索|搜一?下|查一?下|查询|发送|发消息|发短信|扫码|截图"
    )

    /**
     * 任务类别：
     * - 挂机赚钱/循环任务 → OPEN_ENDED
     * - 内容消费类(刷视频等) 且 无数量时长限定 且 非否定 → OPEN_ENDED
     * - 其余(含带数量限定的"刷10条视频") → BOUNDED
     */
    fun classify(userText: String): TaskClass {
        val text = userText.replace(Regex("\\s+"), "")
        if (quantified.containsMatchIn(text)) return TaskClass.BOUNDED
        val neg = negation.find(text)
        if (isExplicitAdRewardTask(text)) return TaskClass.OPEN_ENDED
        rewardLoopVerb.find(text)?.let { m ->
            if (neg == null || m.range.first < neg.range.first) return TaskClass.OPEN_ENDED
        }
        openEndedBrowse.find(text)?.let { m ->
            if (neg == null || m.range.first < neg.range.first) return TaskClass.OPEN_ENDED
        }
        return TaskClass.BOUNDED
    }

    /**
     * 用户是否把“广告”当成任务对象。这里不做具体平台/任务卡名匹配，
     * “看个广告”“看激励广告”“刷广告视频领现金”都能归到同一条广告任务路线。
     */
    fun isExplicitAdRewardTask(userText: String): Boolean {
        val text = userText.replace(Regex("\\s+"), "")
        explicitAdTask.find(text) ?: return false
        return !negatedAdTask.containsMatchIn(text)
    }

    /**
     * 打开App类意图的机械判据。
     * 只有"单纯打开"（打开/启动+应用名，无第二个动作）才给 ForegroundApp——
     * "打开微信并扫码"这种组合指令仍然走模型链路，避免应用一打开就误判完成。
     * @param guessedApp DeviceTools.guessApp 的解析结果(应用名, 包名)；null = 安装列表里没匹配上
     */
    fun launchCheckFor(userText: String, guessedApp: Pair<String, String>?): SuccessCheck {
        val app = guessedApp ?: return SuccessCheck.None
        val text = userText.replace(Regex("\\s+"), "")
        if (!launchVerb.containsMatchIn(text)) return SuccessCheck.None
        if (text.length > 14) return SuccessCheck.None
        if (compoundMarker.containsMatchIn(text)) return SuccessCheck.None
        // 剥掉前缀敬语与动词后，剩下的必须就是应用名本身（容忍"打开微信""帮我打开一下微信"）。
        // 不做模糊包含：'进入淘宝店铺'≠打开淘宝，宁可回退模型链路也不误判提前完成。
        val stripped = text
            .replace(Regex("^(请|帮我|麻烦|帮忙|给我|我)"), "")
            .replace(launchVerb, "")
            .replace(Regex("^(了)?(一下)?"), "")
            .replace(Regex("(这个|那个)?(App|app|应用|软件)?$"), "")
        val label = app.first.replace(Regex("\\s+"), "")
        val closeEnough = stripped.isBlank() || stripped == label
        return if (closeEnough) SuccessCheck.ForegroundApp(app.second, app.first) else SuccessCheck.None
    }

    fun contractFor(
        userText: String,
        guessedApp: Pair<String, String>?,
        sessionMinutes: Int = DEFAULT_SESSION_MINUTES
    ): Contract {
        val taskClass = classify(userText)
        return Contract(
            taskClass = taskClass,
            successCheck = launchCheckFor(userText, guessedApp),
            sessionCapMs = minutes(sessionMinutes.coerceIn(5, 240).toLong()),
            askGraceMs = minutes(DEFAULT_ASK_GRACE_MINUTES),
            boundedTimeoutMs = minutes(DEFAULT_BOUNDED_TIMEOUT_MINUTES)
        )
    }

    /**
     * 前台判据稳定性双读：第一次读中目标后，由调用方隔一小段再读一次确认不是路过弹窗。
     * recheck 由调用方注入(内部 sleep + 重新读包名)，本函数保持纯逻辑可单测。
     */
    fun isForegroundStable(firstPkg: String?, targetPkg: String, recheck: () -> String?): Boolean {
        if (targetPkg.isBlank()) return false
        if (firstPkg != targetPkg) return false
        return recheck() == targetPkg
    }

    /** 挂机时长的人类可读格式：90分钟 → "1小时30分钟"；45秒 → "不到1分钟" */
    fun formatDuration(ms: Long): String {
        val totalMinutes = ms / 60_000
        val hours = totalMinutes / 60
        val mins = totalMinutes % 60
        return when {
            totalMinutes < 1 -> "不到1分钟"
            hours <= 0 -> "${mins}分钟"
            mins == 0L -> "${hours}小时"
            else -> "${hours}小时${mins}分钟"
        }
    }
}
