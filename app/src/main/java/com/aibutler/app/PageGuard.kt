package com.aibutler.app

/**
 * Task-scoped guards for pages that a reward task should leave immediately.
 *
 * All checks stay at the semantic level: screen text plus the user's task wording.
 * No package name, activity name, or screen coordinate is stored here, so the same
 * rules work for different short-video, shopping, browser, and mini-app hosts.
 */
object PageGuard {
    private const val TAG = "PageGuard"

    fun isRewardTask(task: String): Boolean {
        val text = normalize(task)
        return TaskCompletionVerifier.isExplicitAdRewardTask(text) || Regex(
            "金币|金币奖励|积分|奖励|赚钱|收益|签到|看广告|浏览广告|刷广告",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(text)
    }

    fun isLiveIntentTask(task: String): Boolean {
        val text = normalize(task)
        if (Regex("不要|别|禁止|避免|不得").containsMatchIn(text)) {
            val before = text.substringBefore(Regex("不要|别|禁止|避免|不得").find(text)!!.value)
            return Regex("直播|开播|主播").containsMatchIn(before)
        }
        return Regex("看直播|进入直播间|去直播间|开直播|直播收益").containsMatchIn(text)
    }

    fun isLiveRoom(screenDump: String): Boolean {
        val text = normalize(screenDump)
        val strong = Regex("直播间|直播中|正在直播|直播回放|连麦").findAll(text).any()
        val interactive = Regex(
            "退出直播间|离开直播间|退出直播|关闭直播|更多直播|说点什么|送礼物|赠送礼物|加入粉丝团|点亮灯牌"
        ).findAll(text).any()
        return explicitLiveExit(text) != null || (strong && interactive)
    }

    fun isUnintendedLiveRoom(task: String, screenDump: String): Boolean =
        isLiveRoom(screenDump) && !isLiveIntentTask(task)

    /** Best visible, explicit leave control; system Back is only a fallback. */
    fun liveRoomExitLabel(screenDump: String): String? {
        val text = normalize(screenDump)
        val explicit = explicitLiveExit(text)
        if (explicit != null) return explicit

        val labels = clickableLabels(screenDump)
        return labels.firstOrNull { label ->
            Regex("退出直播间|离开直播间|退出直播|关闭直播|残忍离开|放弃观看|结束观看")
                .containsMatchIn(label)
        }
    }

    fun isPaymentOrOrderPage(screenDump: String): Boolean {
        val text = normalize(screenDump)
        val highRisk = Regex(
            "立即支付|去支付|确认支付|正在支付|充值中|立即充值|收银台|付款|提交订单|确认订单"
        ).containsMatchIn(text)
        if (highRisk) return true

        val signals = listOf(
            Regex("支付宝支付"),
            Regex("微信支付"),
            Regex("银行卡支付"),
            Regex("支付方式"),
            Regex("应付金额|订单金额|商品金额"),
            Regex("购买数量|商品详情|加入购物车"),
            Regex("快币余额|[\\d.]+\\s*(快币|金币)余额")
        ).count { it.containsMatchIn(text) }
        return signals >= 2
    }

    /** The model may say “not a payment page”; a negated mention must not trigger the guard. */
    fun isRejectedPaymentMention(text: String): Boolean {
        val plain = normalize(text)
        val phrases = listOf("立即支付", "去支付", "确认支付", "付款", "提交订单", "确认订单", "支付方式", "支付页")
        return phrases.any { phrase ->
            Regex("$phrase").findAll(plain).any { match ->
                val start = (match.range.first - 8).coerceAtLeast(0)
                plain.substring(start, match.range.first).contains(Regex("不是|不要|不能|无需|禁止|避免"))
            }
        }
    }

    fun isForbiddenPayment(task: String, screenDump: String): Boolean =
        isPaymentOrOrderPage(screenDump) && !isCommerceTask(task)

    /** Reward pages often contain a “live-room bonus” ad card; that is not a safe reward entry. */
    fun isUnsafeRewardTap(task: String, label: String): Boolean {
        if (!isRewardTask(task) || isLiveIntentTask(task)) return false
        val text = normalize(label)
        val isPaymentLike = Regex(
            "立即支付|去支付|确认支付|提交订单|确认订单|立即购买|立即抢购|加入购物车"
        ).containsMatchIn(text)
        if (isPaymentLike || !hasMissionVerbs(task)) {
            return isPaymentLike || Regex(
                "直播|开播|主播|立即支付|去支付|确认支付|提交订单|确认订单|立即购买|立即抢购|" +
                    "加入购物车|进店|商品详情|下载|安装|试玩|去体验|" +
                    "投币|报名|立即参与|抽奖"
            ).containsMatchIn(text)
        }
        return false
    }

    /**
     * 奖励任务里的投币/报名/抽奖类活动页：会消耗或锁定金币，不是看广告路线。
     * 必须同时出现「参与类动词」和「暴涨/翻倍/瓜分」类 hype 词，普通任务卡不误伤。
     * 如果用户任务本身点名了对应玩法，则这是任务目的地，不应拦截。
     */
    fun isRewardActivityPage(screenDump: String, task: String = ""): Boolean {
        val text = normalize(screenDump)
        val commit = Regex("立即投币|投币报名|一键报名|立即报名|立即参与|去报名")
        val hype = Regex("暴涨|翻倍|瓜分|大奖|必得|赢取|锦鲤|抽奖")
        if (!commit.containsMatchIn(text) || !hype.containsMatchIn(text)) return false

        val taskVerbs = verbsIn(taskTextForMission(task))
        if (taskVerbs.isEmpty()) return true
        val destinationVerbs = verbsIn(text)
        return destinationVerbs.intersect(taskVerbs).isEmpty()
    }

    /**
     * 任务动词表：用户任务点名了哪些动作，就锚定哪些动作。
     * 目标（按钮文字或周围图文）命中的动作与任务命名的动作完全不同 → 跑偏，拦截。
     * 数据驱动：换任务、换App都按同一张表判断，不写死任何单一场景。
     */
    private val missionVerbs = linkedMapOf(
        "广告" to Regex("广告|激励|观看|播放|继续看"),
        "视频" to Regex("视频"),
        "签到" to Regex("签到"),
        "宝箱" to Regex("宝箱"),
        "抽奖" to Regex("抽奖|转盘"),
        "投币" to Regex("投币"),
        "报名" to Regex("报名"),
        "参与" to Regex("参与|参加"),
        "直播" to Regex("直播"),
        "搜索" to Regex("搜索|搜一搜|搜一下"),
        "下载" to Regex("下载|安装"),
        "游戏" to Regex("试玩|玩游戏"),
        "邀请" to Regex("邀请"),
        "提现" to Regex("提现|兑换"),
        "逛街" to Regex("逛一逛|去逛逛|逛街"),
        "下单" to Regex("下单|订单|购买|抢购|支付|付款")
    )

    /** 去掉短否定短语，但保留后面的正向任务（「不要下载只看广告」仍锚定广告） */
    private fun taskTextForMission(task: String): String =
        Regex("(不要|别再?|禁止|避免|不得)(?:[^，。,；;！!？?]*?)(?=$|[，。,；;！!？?]|而|改|只|然后)")
            .replace(normalize(task), "")

    /** 任务是否点名了具体动作（点名了才做锚定，模糊任务走通用安全红线） */
    fun hasMissionVerbs(task: String): Boolean =
        missionVerbs.values.any { it.containsMatchIn(taskTextForMission(task)) }

    private fun verbsIn(text: String): Set<String> =
        missionVerbs.filterValues { it.containsMatchIn(normalize(text)) }.keys

    /**
     * 目标是否跑偏：按钮文字明确是别的动作 → 跑偏；按钮中性时，
     * 看周围图文指向的目的地——只要目的地点名了任务之外的动作 → 跑偏。
     */
    fun isOffMissionTap(task: String, label: String, context: String = ""): Boolean {
        val taskVerbs = verbsIn(taskTextForMission(task))
        if (taskVerbs.isEmpty()) return false
        val labelVerbs = verbsIn(label)
        if (labelVerbs.isNotEmpty() && labelVerbs.intersect(taskVerbs).isEmpty()) return true

        val segmentVerbs = context.split(Regex(" / |\\s+")).map(::verbsIn)
        if (segmentVerbs.all { it.isEmpty() }) return false
        val alignedSegments = segmentVerbs.filter { it.intersect(taskVerbs).isNotEmpty() }
        val conflictingSegments = segmentVerbs.filter { it.isNotEmpty() && it.intersect(taskVerbs).isEmpty() }
        if (labelVerbs.intersect(taskVerbs).isNotEmpty()) {
            return conflictingSegments.isNotEmpty()
        }
        if (alignedSegments.isNotEmpty() && conflictingSegments.isEmpty()) return false
        return alignedSegments.isEmpty() || conflictingSegments.isNotEmpty()
    }

    /**
     * Task-center pages are meant to be left by passive video tasks. Require either a strong
     * title/entry or several reward features, so a coin badge in the normal feed is not enough.
     */
    fun isRewardTaskCenter(screenDump: String): Boolean {
        val text = normalize(screenDump)
        val strong = Regex(
            "任务中心|赚钱任务|去赚钱|看广告(赚|得|领)|看视频(赚|得|领)金币|现金收益|金币收益|立即提现"
        ).containsMatchIn(text)
        if (strong) return true

        val weak = listOf(
            Regex("金币|现金|奖励|收益"),
            Regex("签到|每日签到"),
            Regex("提现|兑换"),
            Regex("邀请好友|新人专区|限时专区"),
            Regex("开宝箱|宝箱"),
            Regex("看广告|浏览广告|广告任务")
        ).count { it.containsMatchIn(text) }
        return weak >= 3
    }

    /**
     * The ordinary short-video feed is the wrong place for an explicit “watch ad” task.
     * Require both feed navigation and player affordances, and exclude reward centers,
     * so a coin badge alone cannot trigger forced routing.
     */
    fun isVideoFeed(screenDump: String): Boolean {
        if (isRewardTaskCenter(screenDump)) return false
        val navigation = Regex("推荐|关注|首页|商城").containsMatchIn(normalize(screenDump))
        val playerAffordance = Regex("点赞|评论|分享|转发|全屏观看|点击进入直播间|相关搜索")
            .containsMatchIn(normalize(screenDump))
        return navigation && playerAffordance
    }

    /**
     * Generic route back to a task/reward page from the feed: only semantic entries,
     * never download/commerce/live traps.
     */
    fun rewardEntryLabel(screenDump: String): String? {
        val lineRegex = Regex("(?:^|\\n)可点击:\\s*(.+?)\\s*\\[-?\\d+,-?\\d+\\]")
        val unsafe = Regex("下载|安装|试玩|购买|支付|下单|抢购|直播|主播|开宝箱|跳过广告|关闭广告|屏蔽广告|广告设置|广告管理")
        val wanted = Regex("广告|广告任务|任务中心|去赚钱|赚钱|现金收益|金币收益|红包")
        lineRegex.findAll(screenDump).forEach { match ->
            val label = match.groupValues[1].substringBefore("{").trim()
            val text = normalize(label)
            if (wanted.containsMatchIn(text) && !unsafe.containsMatchIn(text)) return label
        }
        return null
    }

    /** Install/download walls often cover the task page and swallow system Back. */
    fun isDownloadWall(screenDump: String): Boolean = Regex(
        "下载并体验|下载应用|立即下载|下载安装|安装应用|免费下载|戳我下载|下载领\\d*金币",
        RegexOption.IGNORE_CASE
    ).containsMatchIn(normalize(screenDump))

    /**
     * A paused rewarded-ad timer must be resumed, not abandoned. Return the visible continue
     * control only when the page also proves that a reward countdown is still active.
     */
    fun rewardContinueLabel(screenDump: String, countdownSeconds: Int?): String? {
        if (countdownSeconds == null) return null
        val lineRegex = Regex("(?:^|\\n)可点击:\\s*(.+?)\\s*\\[-?\\d+,-?\\d+\\]")
        lineRegex.findAll(screenDump).forEach { match ->
            val label = match.groupValues[1].substringBefore("{").trim()
            if (Regex("继续观看|继续看广告|继续播放|继续看视频|继续看完视频").containsMatchIn(normalize(label))) {
                return label
            }
        }
        return if (Regex("继续观看|继续看广告|继续播放|继续看视频|继续看完视频")
                .containsMatchIn(normalize(screenDump))
        ) "继续观看" else null
    }

    /** Retention dialogs can keep Back inside the ad; use an explicit opt-out first. */
    fun safeAdExitLabel(screenDump: String): String? =
        ExitMemory.visibleButtons(screenDump).firstOrNull()?.label

    /** 拍摄/创作页的「不保存」退出按钮，按优先级排列 */
    val CREATION_DISCARD_LABELS = listOf("不保存返回", "不保存", "丢弃")

    /**
     * 拍摄/创作页对非创作任务是高危路径：一旦进入倒计时或编辑页，很容易连续操作到发布。
     * 用户任务本身要拍摄/发布/直播时不拦（那是正当目标）。
     */
    fun isUnintendedCreationPage(task: String, screenDump: String): Boolean {
        if (Regex("拍摄|录像|发视频|发布|开直播|录屏").containsMatchIn(normalize(task))) return false
        val signals = listOf(
            "选择音乐", "切换到前置摄像头", "开始拍摄", "停止拍摄", "分段拍",
            "灵感跟拍", "美颜", "滤镜", "倒计时拍", "视频编辑", "发布"
        )
        return signals.count { screenDump.contains(it) } >= 2
    }

    private fun isCommerceTask(task: String): Boolean {
        val text = normalize(task)
        if (Regex("不要|别|禁止|避免|不得").containsMatchIn(text)) {
            val marker = Regex("不要|别|禁止|避免|不得").find(text)?.value.orEmpty()
            val before = text.substringBefore(marker, "")
            return Regex("充值|支付|下单|购买|付款|抢购|购物").containsMatchIn(before)
        }
        return Regex("充值|支付|下单|购买|付款|抢购|购物").containsMatchIn(text)
    }

    private fun explicitLiveExit(text: String): String? = listOf(
        "退出直播间",
        "离开直播间",
        "退出直播",
        "关闭直播",
        "残忍离开",
        "放弃观看"
    ).firstOrNull { text.contains(it) }

    private fun clickableLabels(screenDump: String): List<String> =
        // 注意：这里的 `[^\[]` 只兼容原始 dump——Set-of-Marks 编号版（`可点击: [N] 文字 [x,y]`）
        // 会让匹配全失败。所有调用方都必须传原始 screenDump，勿传 markedDump（体检 P2-19）
        Regex("可点击:\\s*([^\\[\\n]+?)\\s*\\[")
            .findAll(screenDump)
            .map { it.groupValues[1].substringBefore("{").trim() }
            .filter { it.isNotBlank() }
            .toList()

    private fun normalize(value: String): String = value.replace(Regex("\\s+"), "")
}
