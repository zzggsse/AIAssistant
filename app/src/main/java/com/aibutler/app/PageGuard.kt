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

    /** Reward pages often contain a “live-room bonus” ad card; that is not a safe reward entry. */
    fun isUnsafeRewardTap(task: String, label: String): Boolean {
        if (!isRewardTask(task) || isLiveIntentTask(task)) return false
        val text = normalize(label)
        return Regex(
            "直播|开播|主播|立即支付|去支付|确认支付|提交订单|确认订单|立即购买|立即抢购|" +
                "加入购物车|进店|商品详情|下载|安装|试玩|去体验"
        ).containsMatchIn(text)
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
        Regex("可点击:\\s*([^\\[\\n]+?)\\s*\\[")
            .findAll(screenDump)
            .map { it.groupValues[1].substringBefore("{").trim() }
            .filter { it.isNotBlank() }
            .toList()

    private fun normalize(value: String): String = value.replace(Regex("\\s+"), "")
}
