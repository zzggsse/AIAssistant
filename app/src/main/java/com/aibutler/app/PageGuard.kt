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

    fun isRewardTask(task: String): Boolean = Regex(
        "金币|金币奖励|积分|奖励|赚钱|收益|签到|看广告|浏览广告|刷广告",
        RegexOption.IGNORE_CASE
    ).containsMatchIn(normalize(task))

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

    /**
     * Reward pages often contain a “live-room bonus” ad card; that is not a safe reward entry.
     * 2026-10-23 扩展：补上购物返利 CTA 词表（真实案例：广告上红色的「购买后确认收货最高得
     * 200000金币」被模型当成赚金币入口误点）。这类按钮承诺的金币要"先花钱购物"才拿得到，
     * 对看广告/刷金币任务是纯陷阱。用户任务本身就是购物类（下单/购买…）时不拦，那是正当目标。
     */
    fun isUnsafeRewardTap(task: String, label: String): Boolean {
        if (!isRewardTask(task) || isLiveIntentTask(task) || isCommerceTask(task)) return false
        val text = normalize(label)
        return Regex(
            "直播|开播|主播|立即支付|去支付|确认支付|提交订单|确认订单|" +
                "购买|确认收货|下单|抢购|拼单|拼团|0元|一分钱|一分购|包邮|" +
                "进店|商品详情|加入购物车|去逛逛|下载|安装|试玩|去体验"
        ).containsMatchIn(text)
    }

    /**
     * 广告 SDK 的「报错/反馈」按钮（常为红色，多在广告底部）是向广告平台投诉的入口，
     * 不是关闭按钮——模型找关闭控件时经常被它的“错”字和醒目红色带偏，点了会打开
     * 反馈页而不是关掉广告。用户任务本身就是要反馈/报错/投诉时放行。
     */
    fun isAdFeedbackTap(task: String, label: String): Boolean {
        if (Regex("反馈|报错|投诉|举报").containsMatchIn(normalize(task))) return false
        return Regex("报错|错误上报|问题反馈|意见反馈|反馈").containsMatchIn(normalize(label))
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

    /** Install/download walls often cover the task page and swallow system Back. */
    fun isDownloadWall(screenDump: String): Boolean = Regex(
        "下载并体验|下载应用|立即下载|下载安装|安装应用|免费下载|戳我下载|下载领\\d*金币",
        RegexOption.IGNORE_CASE
    ).containsMatchIn(normalize(screenDump))

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
