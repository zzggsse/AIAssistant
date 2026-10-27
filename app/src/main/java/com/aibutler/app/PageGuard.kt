package com.aibutler.app

import kotlin.math.abs

/**
 * Task-scoped guards for pages that a reward task should leave immediately.
 *
 * All checks stay at the semantic level: screen text plus the user's task wording.
 * No package name, activity name, or screen coordinate is stored here, so the same
 * rules work for different short-video, shopping, browser, and mini-app hosts.
 */
object PageGuard {
    private const val TAG = "PageGuard"

    data class RewardCompletionExit(
        val evidence: String,
        val closeLabel: String?,
        /** 命中行在 dump 里的 0-999 坐标：glyph/无名关闭控件必须按坐标直点，见 isGlyphCloseQuery */
        val x: Int? = null,
        val y: Int? = null
    )

    private val rewardCompletionEvidence = Regex(
        "已成功领取|领取成功|已领取奖励|奖励已发放|已到账|额外奖励已到手|签到成功|今日已签到"
    )

    private val rewardCloseGlyph = Regex("[×✕✖⨯]|(?:^|[^A-Za-z])X(?:$|[^A-Za-z])")
    private val rewardCloseText = Regex(
        "关闭|跳过|以后再说|暂不|下次再说|放弃奖励|放弃观看|放弃本次奖励|不再观看|不感兴趣|坚持退出|残忍离开|残忍拒绝|退出广告"
    )
    private val rewardStrongCloseText = Regex(
        "坚持退出|残忍离开|残忍拒绝|退出广告|放弃奖励|放弃观看|放弃本次奖励|不再观看"
    )
    private val rewardAdTransform = Regex(
        "点击并浏览|浏览\\s*\\d+|继续观看|额外得|再看一[个轮集次]|再[领得].{0,8}(?:金币|奖励)|" +
            "立即下载|去下载|安装|试玩|抢购|了解详情|查看详情|了解更多|去完成|去体验"
    )

    fun isRewardTask(task: String): Boolean {
        val text = normalize(task)
        return TaskCompletionVerifier.isExplicitAdRewardTask(text) || Regex(
            "金币|金币奖励|积分|奖励|赚钱|收益|签到|看广告|浏览广告|刷广告",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(text)
    }

    fun isLiveIntentTask(task: String): Boolean {
        val text = normalize(task)
        // 「别」不能裸用——「特别想看直播」会被当成否定句（体检 P3-10）
        if (Regex("不要|(?<!特)别再?|禁止|避免|不得").containsMatchIn(text)) {
            val before = text.substringBefore(Regex("不要|(?<!特)别再?|禁止|避免|不得").find(text)!!.value)
            return Regex("直播|开播|主播").containsMatchIn(before)
        }
        return Regex("看直播|进入直播间|去直播间|开直播|直播收益").containsMatchIn(text)
    }

    fun isLiveRoom(screenDump: String): Boolean {
        val text = normalize(screenDump)
        val strong = Regex("直播间|直播中|正在直播|直播回放|连麦").findAll(text).any()
        val interactive = Regex(
            "退出直播间|离开直播间|退出直播|关闭直播|更多直播|直播广场|说点什么|送礼物|赠送礼物|加入粉丝团|点亮灯牌|礼物面板|抽大奖"
        ).findAll(text).any()
        if (explicitLiveExit(text) != null) return true
        if (!interactive) return false
        // strong 词缺席时，「更多直播/直播广场/抽大奖」这类入口词单独出现不足以判直播间：
        // 任务中心同样有这些入口词，单词命中会反复强退正常页面（体检追加轮 P1-7），
        // 必须同时有直播间特有的互动特征（评论框/送礼/粉丝团/礼物面板）
        val liveOnlyFeature = Regex("说点什么|送礼物|赠送礼物|加入粉丝团|点亮灯牌|礼物面板").containsMatchIn(text)
        return strong || liveOnlyFeature
    }

    fun isUnintendedLiveRoom(task: String, screenDump: String): Boolean =
        isLiveRoom(screenDump) && !isLiveIntentTask(task)

    /**
     * 激励广告里嵌入的直播引流可能同时命中“继续观看”和直播间特征。
     * 刚强退直播间后又自动恢复观看，会形成两个兜底互相抵消的循环；
     * 这个短窗口内优先让冲突层退出，而不是继续恢复播放。
     */
    fun isRecentLiveExit(lastLiveExitRound: Int?, round: Int): Boolean =
        lastLiveExitRound != null && round - lastLiveExitRound in 1..3

    /** 福利/广告入口刚触发后，广告素材本身可能是一段直播内容；这不等同于误入直播间。 */
    fun hasRewardAdEntry(screenDump: String): Boolean = Regex(
        "领福利|领金币|看广告得|刷广告|看视频赚|观看广告|广告任务|继续观看"
    ).containsMatchIn(normalize(screenDump))

    fun isRecentRewardEntry(lastRewardEntryRound: Int?, round: Int): Boolean =
        lastRewardEntryRound != null && round - lastRewardEntryRound in 1..5

    /** 只有显式退出控件才能打断广告素材里的直播内容；结构角标可能是广告关闭/全屏按钮。 */
    fun hasExplicitLiveExit(screenDump: String): Boolean = Regex(
        "退出直播间|离开直播间|退出直播|关闭直播|结束观看"
    ).containsMatchIn(normalize(screenDump))

    /** Best visible, explicit leave control; system Back is only a fallback. */
    fun liveRoomExitLabel(screenDump: String): String? {
        val text = normalize(screenDump)
        val explicit = explicitLiveExit(text)
        if (explicit != null) return explicit

        val labels = clickableLabels(screenDump)
        val explicitLabel = labels.firstOrNull { label ->
            Regex("退出直播间|离开直播间|退出直播|关闭直播|残忍离开|放弃观看|结束观看")
                .containsMatchIn(label)
        }?.let(::queryFor)
        if (explicitLabel != null) return explicitLabel

        return clickableRows(screenDump)
            .filter { (label, x, y) ->
                val cleanLabel = queryFor(label)
                (cleanLabel == "无文字元素" || rewardCloseGlyph.containsMatchIn(cleanLabel)) &&
                    x >= 820 && y <= 160
            }
            .maxByOrNull { it.second }
            ?.let { queryFor(it.first) }
    }

    fun isPaymentOrOrderPage(screenDump: String): Boolean {
        val text = normalize(screenDump)
        val highRisk = Regex(
            "立即支付|去支付|确认支付|正在支付|充值中|立即充值|收银台|付款|" +
            "提交订单|确认订单|确认下单|立即购买|立即抢购|" +
                "place.{0,4}order|submit.{0,4}order|confirm.{0,4}(?:payment|order)|complete.{0,4}order|check.?out",
            RegexOption.IGNORE_CASE
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

    /**
     * 账号登录和凭据填写是用户本人才能完成的授权动作。这里只认页面上明确的
     * 登录方式/凭据语义，避免把“我的”“设置”等普通页面里的历史登录状态误判成登录墙。
     */
    fun isManualAuthPage(screenDump: String): Boolean {
        val text = normalize(screenDump)
        val explicitAuthPage = Regex(
            "扫码登录|二维码登录|账号登录|手机号登录|短信登录|验证码登录|" +
                "微信登录|QQ登录|apple登录|忘记密码|登录/注册|注册/登录|" +
                "(?i)scan.{0,6}qr.{0,6}login|account.?login|mobile.?login|forgot.{0,6}password",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(text)
        if (explicitAuthPage) return true

        val stateRemoved = Regex("已登录|登录成功|退出登录|账号与安全").replace(text, "")
        val authAction = Regex("登录|注册|log\\s*in|sign\\s*in", RegexOption.IGNORE_CASE)
        val credential = Regex("密码|验证码|手机号|账号|password|verification|otp", RegexOption.IGNORE_CASE)
        return authAction.containsMatchIn(stateRemoved) && credential.containsMatchIn(stateRemoved)
    }

    /** Button labels that require the user's manual confirmation, regardless of task wording. */
    private val sensitiveButtonPattern = Regex(
        "确认支付|立即支付|去支付|确认付款|立即付款|支付|付款|转账|验证码|输入密码|" +
            "提交订单|确认订单|确认下单|立即购买|立即抢购|" +
            "(?i)pay\\s*now|make.{0,4}payment|check.?out|confirm.{0,4}(?:payment|order)|" +
            "(?i)place.{0,4}order|submit.{0,4}order|complete.{0,4}order|enter.{0,4}password|" +
            "(?i)proceed.{0,8}(?:pay|check.?out)|transfer\\s*(?:money|fund)?|" +
            "(?i)verify.{0,4}code|one.?time.(?:pass.?word|code)|otp"
    )

    fun isSensitiveButton(label: String): Boolean {
        if (label.isBlank()) return false
        return sensitiveButtonPattern.containsMatchIn(normalize(label))
    }

    /**
     * Shopping tasks may navigate to order pages, but the final order confirmation is still
     * a user-only action. Never let task intent waive this check.
     */
    @Suppress("UNUSED_PARAMETER")
    fun isForbiddenPayment(task: String, screenDump: String): Boolean =
        isPaymentOrOrderPage(screenDump)

    /**
     * 消费/送礼入口和奖励目标本身是两类目的地。即便任务提到“广告”等动词，
     * 也不能因为“有任务动词”而放行红包、礼物、充值、支付类按钮。
     * 用户任务确实点名同一类消费动作时才允许，例如“帮我发红包”“给主播送礼物”。
     */
    fun isSpendActionTap(task: String, label: String, context: String = ""): Boolean {
        val target = normalize(label)
        val nearby = normalize(context)
        if (spendActionEntry.containsMatchIn(target)) return !taskWantsSpendAction(task)
        if (target == "无文字元素") return false
        if (spendGuardExemptTarget.containsMatchIn(target)) return false
        if (!isNeutralSpendTarget(target) || !spendActionEntry.containsMatchIn(nearby)) return false
        return !taskWantsSpendAction(task)
    }

    private val spendActionEntry = Regex(
        "发红包|红包封面|红包雨|送礼物|赠送礼物|刷礼物|礼物面板|打赏|" +
            "充值|快币|余额|付款|支付|下单|购买|抢购|加入购物车|提交订单|确认订单|" +
            "旗舰店|专卖店|工厂店|官方旗舰|店铺|商品详情|讲解中"
    )

    private val spendGuardExemptTarget = Regex(
        "观看广告|看广告|看视频|继续观看|继续看|任务中心|去赚钱|赚钱|金币收益|现金收益|" +
            "签到|已成功领取|领取成功|已到账|关闭|跳过|返回|退出|以后再说"
    )

    private fun isNeutralSpendTarget(target: String): Boolean =
        target.isBlank() || target == "无文字元素" || neutralSpendTarget.containsMatchIn(target)

    private val neutralSpendTarget = Regex(
        "查看详情|去参与|立即参与|去完成|了解更多|立即查看|点击查看|查看更多|去逛逛|" +
            "领取奖励|开心收下|知道了|我知道了|action_bar_view"
    )

    private fun taskWantsSpendAction(task: String): Boolean =
        spendActionIntent.containsMatchIn(
            Regex("(?:不要|别再?|禁止|避免|不得)[^，。,；;！!？?\\n]*")
                .replace(normalize(task), "")
        )

    private val spendActionIntent = Regex(
        "发红包|发(?:一?个|个)?.{0,8}红包|送(?:一?个|个)?.{0,8}礼物|赠送礼物|刷礼物|打赏|" +
            "充值|快币|付款|支付|下单|购买|抢购|购物|加入购物车|提交订单|确认订单|" +
            "旗舰店|专卖店|工厂店|官方旗舰|店铺|商品详情"
    )

    /** Reward pages often contain a “live-room bonus” ad card; that is not a safe reward entry. */
    fun isUnsafeRewardTap(task: String, label: String): Boolean {
        if (!isRewardTask(task) || isLiveIntentTask(task)) return false
        val text = normalize(label)
        val isPaymentLike = Regex(
            "立即支付|去支付|确认支付|提交订单|确认订单|立即购买|立即抢购|加入购物车|" +
                "(?i)submit.{0,4}order"
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
     * 投币/抽奖类活动层的安全退出控件。真机上不少奖励弹窗不响应 Back，
     * 只认角上的关闭角标，所以退出顺序必须是：可见文字关闭按钮 → 图形 X，
     * 两者都没有时由调用方按 Back 兜底。图形 X 取最靠上、其次最靠右的一个：
     * 活动弹窗的关闭角标几乎总在弹窗右上区域。
     */
    fun rewardActivityExitControl(screenDump: String): RewardCompletionExit? {
        ExitMemory.visibleButtons(screenDump)
            .firstOrNull { it.score in 300..499 }
            ?.let { return RewardCompletionExit("活动层退出", it.label) }
        return clickableRows(screenDump)
            .filter { (label, _, _) ->
                val text = normalize(label)
                text == "无文字元素" || rewardCloseGlyph.containsMatchIn(text)
            }
            .minWithOrNull(compareBy({ it.third }, { -it.second }))
            ?.let { row ->
                RewardCompletionExit("活动层退出", queryFor(row.first), row.second, row.third)
            }
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

    private val connectorVerbs = setOf("参与")
    private val rewardOutcome = Regex("金币|现金|积分|奖励|赚钱|收益|佣金")
    private val rewardTaskRoute = Regex(
        "任务中心|任务列表|赚钱任务|奖励中心|收益中心|去赚钱|去赚金币|做任务|领任务|钱包|金币收益|现金收益|视频任务"
    )

    private val timedRewardAdEntry = Regex(
        "点击(?:并)?(?:看|观看|浏览)|看\\s*\\d+\\s*(?:秒|分钟)|浏览\\s*\\d+\\s*(?:秒|分钟)"
    )

    private fun isRewardRouteTap(task: String, label: String): Boolean =
        isRewardTask(task) && rewardTaskRoute.containsMatchIn(normalize(label))

    /**
     * 限时奖励广告常用「点击看5秒」这类动作型文案，本身不含「广告」关键词。
     * 它们是奖励任务的直接入口；只有中性容器交给语境做二次确认，避免误伤整页广告卡。
     */
    private fun isTimedRewardAdTap(label: String, context: String): Boolean {
        val target = normalize(label)
        val nearby = normalize(context)
        if (timedRewardAdEntry.containsMatchIn(target)) return true
        val isNeutralTarget = target.isBlank() || target == "无文字元素" ||
            Regex("action_bar_view|去领取|去完成|领福利").containsMatchIn(target)
        return isNeutralTarget && timedRewardAdEntry.containsMatchIn(nearby) &&
            rewardOutcome.containsMatchIn(nearby)
    }

    private fun specificVerbsIn(text: String): Set<String> = verbsIn(text) - connectorVerbs

    private fun specificContextVerbs(context: String): List<Set<String>> =
        context.split(Regex(" / |\\s+"))
            .map(::specificVerbsIn)
            .filter { it.isNotEmpty() }


    /** 通用关闭控件：语义文字优先，截图上的无文字 X 用右上角区域兜底。 */
    /**
     * 底部导航经常把文字包在无文字容器外层，读屏只能给出空白容器。
     * 这类坐标属于应用内常规导航，不能被任务锚定当成“无目的地跳转”。
     */
    fun isNeutralBottomNavigation(label: String, ny: Int): Boolean {
        val text = normalize(label)
        val isUnlabeledContainer = text.isBlank() ||
            text.startsWith("无文字元素") ||
            text.startsWith("{")
        return isUnlabeledContainer && ny in 850..999
    }

    fun isDismissControl(label: String, nx: Int, ny: Int): Boolean {
        val text = normalize(label)
        // 语义词只认短标签（≤6字）。「关闭广告并领取奖励」这类长文案是转化按钮，不能因含「关闭」放行（体检 P2-8）
        if (text.length <= 6 &&
            Regex("关闭|跳过|以后再说|我知道了|放弃奖励|残忍离开").containsMatchIn(text)
        ) {
            return true
        }
        // 「×」必须整标签就是符号：「金币×100」含 × 但不是关闭控件（体检 P2-8）
        if (Regex("^[×✕✖⨯Xx]$").containsMatchIn(text)) return true
        // 右上角空白兜底：真弹窗的 X 几乎不会低于屏幕 1/3，ny 收到 320 防止误中右缘点赞栏（体检 P2-8）
        return (text.isBlank() || text == "无文字元素") && nx in 830..999 && ny in 30..320
    }

    /**
     * 目标是否跑偏：按钮自带明确动作时按按钮判断；按钮中性时才看卡片语境。
     * 「任务中心/去赚钱/钱包」是奖励任务的路由，不属于另一种玩法。
     */
    fun isOffMissionTap(task: String, label: String, context: String = ""): Boolean {
        val taskVerbsFull = verbsIn(taskTextForMission(task))
        val taskVerbs = taskVerbsFull - connectorVerbs
        // 任务只点了奖励结果、没点名玩法（taskVerbs 为空）时不做动词锚定：
        // 那类任务交给 isUnsafeRewardTap/isSpendActionTap 的通用红线，否则任何动词入口都被一刀切拦掉（体检 P2-1）
        if (taskVerbs.isEmpty()) return false
        if (isRewardRouteTap(task, label) || isRewardRouteTap(task, context)) return false
        if (isRewardTask(task) && isTimedRewardAdTap(label, context)) return false

        val labelVerbs = verbsIn(label)
        if (labelVerbs.isNotEmpty()) {
            val labelSpecific = labelVerbs - connectorVerbs
            if (labelSpecific.isNotEmpty()) {
                if (labelSpecific.intersect(taskVerbs).isEmpty()) return true
                if (context.isBlank()) return false
                val segments = specificContextVerbs(context).filter { it.isNotEmpty() }
                if (segments.isEmpty()) return false
                val aligned = segments.filter { it.intersect(taskVerbs).isNotEmpty() }
                // 标签本身与任务对齐时：只要有明确对齐去向就放行；全是其他玩法才拦。
                // 奖励页常见「播放视频 / 短剧 / 直播」混排，不能因背景连坐误拦。
                return aligned.isEmpty()
            }
            // 标签只有连接动词（「立即参与/去参加」）：任务点名了参与类动作（「参加抽奖」）时
            // 这就是目的地；任务没点名（「看广告赚金币」点「立即参与」= 投币活动入口）则拦截。
            // 两侧对称处理连接动词，老逻辑只剥任务侧会误拦「参加抽奖→立即参与」（体检 P2-1）
            return !taskVerbsFull.contains("参与")
        }

        val segmentVerbs = specificContextVerbs(context)
        if (segmentVerbs.isEmpty()) return false
        val alignedSegments = segmentVerbs.filter { it.intersect(taskVerbs).isNotEmpty() }
        // 中性按钮周围常见混排内容。只要有明确对齐去向就放行；完全没有对齐去向才视为跑偏。
        return alignedSegments.isEmpty()
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
        val unsafe = Regex(
            "发红包|红包封面|红包雨|送礼物|赠送礼物|刷礼物|礼物面板|打赏|" +
                "下载|安装|试玩|购买|支付|下单|抢购|直播|主播|开宝箱|" +
                "旗舰店|专卖店|工厂店|官方旗舰|店铺|商品详情|讲解中|" +
                "跳过广告|关闭广告|屏蔽广告|广告设置|广告管理"
        )
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

    /** A reward result is progress, not the end of a looped task; identify the layer to leave. */
    fun hasRewardCompletion(screenDump: String): Boolean =
        rewardCompletionEvidence.containsMatchIn(normalize(screenDump))

    /** Accessibility may merge the result phrase and X into one node's own label. */
    fun rewardLabelHasCloseControl(label: String): Boolean {
        val text = normalize(label)
        return hasRewardCompletion(text) &&
            (rewardCloseGlyph.containsMatchIn(text) || rewardCloseText.containsMatchIn(text))
    }

    /**
     * closeLabel 是单个关闭符号或「无文字元素」占位时，clickText 的全树 contains 匹配
     * 会误中「金币×100」这类节点——必须用 RewardCompletionExit.x/y 按坐标直点（体检 P1-8）。
     */
    fun isGlyphCloseQuery(label: String?): Boolean {
        if (label.isNullOrBlank()) return false
        val t = label.trim()
        return t == "无文字元素" || Regex("^[×✕✖⨯Xx]$").containsMatchIn(t)
    }

    /**
     * Find a close control that shares the reward-result context. The X is frequently a tiny
     * image or a generic container, so matching the completion phrase is required before using
     * row geometry. This deliberately does not return the ad CTA just because it also mentions
     * another reward.
     */
    fun rewardCompletionExit(screenDump: String): RewardCompletionExit? {
        if (!hasRewardCompletion(screenDump)) return null
        val evidence = rewardCompletionEvidence.find(normalize(screenDump))?.value.orEmpty()
        val rows = clickableRows(screenDump)
        // 结算后的挽留弹窗常把转化按钮放在结果提示很远处。强语义退出优先于几何兜底，
        // 因为它比“关闭”更不容易误中页面里另一条无关 banner。
        rows.firstOrNull { (label, _, _) ->
            val text = normalize(label)
            rewardStrongCloseText.containsMatchIn(text) && !rewardAdTransform.containsMatchIn(text)
        }?.let { row ->
            return RewardCompletionExit(evidence, queryFor(row.first))
        }
        val anchors = rows.filter { (label, _, _) ->
            rewardCompletionEvidence.containsMatchIn(normalize(label))
        }
        val closeGlyphRows = rows.filter { (label, _, _) ->
            val text = normalize(label)
            rewardCloseGlyph.containsMatchIn(text) && !rewardAdTransform.containsMatchIn(text)
        }
        val unnamedCloseRows = rows.filter { (label, _, _) -> label == "无文字元素" }

        // Same row first: the glyph belongs to the result toast/banner, not to an unrelated corner.
        for (anchor in anchors) {
            closeGlyphRows
                .filter { (_, x, y) -> isSameRewardRow(anchor.second, anchor.third, x, y) }
                .minByOrNull { (_, x, y) -> abs(x - anchor.second) + abs(y - anchor.third) }
                ?.let { row -> return RewardCompletionExit(evidence, queryFor(row.first), row.second, row.third) }
        }

        for (anchor in anchors) {
            unnamedCloseRows
                .filter { (_, x, y) ->
                    abs(y - anchor.third) <= 35 &&
                        x > anchor.second &&
                        x - anchor.second <= 650
                }
                .minByOrNull { (_, x, y) -> abs(x - anchor.second) + abs(y - anchor.third) }
                ?.let { row -> return RewardCompletionExit(evidence, queryFor(row.first), row.second, row.third) }
        }

        // Some SDKs merge the result and X into one accessibility label.
        anchors.firstOrNull { (label, _, _) -> rewardCloseGlyph.containsMatchIn(label) }
            ?.let { row -> return RewardCompletionExit(evidence, queryFor(row.first), row.second, row.third) }

        // Text-labeled close/dismiss controls near the result remain the generic fallback.
        val closeRows = rows.filter { (label, _, _) ->
            val text = normalize(label)
            rewardCloseText.containsMatchIn(text) && !rewardAdTransform.containsMatchIn(text)
        }
        for (anchor in anchors) {
            closeRows
                .filter { (_, x, y) -> isNearRewardResult(anchor.second, anchor.third, x, y) }
                .minByOrNull { (_, x, y) -> abs(x - anchor.second) + abs(y - anchor.third) }
                ?.let { row -> return RewardCompletionExit(evidence, queryFor(row.first), row.second, row.third) }
        }
        if (anchors.isEmpty()) {
            closeGlyphRows.firstOrNull()?.let { row ->
                return RewardCompletionExit(evidence, queryFor(row.first), row.second, row.third)
            }
        }
        return RewardCompletionExit(evidence, null)
    }

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

    /**
     * 任务本身是购物/消费类时，支付与商品页是正当目的地。
     * 主循环商品页守卫、isForbiddenPayment、AgentEngine isNonShoppingTask 统一走这里，
     * 词表必须一致——历史上三处各写一份、漏了「买」，导致「买杯奶茶」被反复按返回（体检 P1-6）。
     */
    fun isCommerceTask(task: String): Boolean {
        val text = normalize(task)
        val commerceWords = Regex("充值|支付|下单|购买|付款|抢购|购物|买")
        if (Regex("不要|(?<!特)别再?|禁止|避免|不得").containsMatchIn(text)) {
            val marker = Regex("不要|(?<!特)别再?|禁止|避免|不得").find(text)?.value.orEmpty()
            val before = text.substringBefore(marker, "")
            return commerceWords.containsMatchIn(before)
        }
        return commerceWords.containsMatchIn(text)
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

    private fun clickableRows(screenDump: String): List<Triple<String, Int, Int>> {
        val lineRegex = Regex("(?:^|\\n)可点击:\\s*(.+?)\\s*\\[(-?\\d+),(-?\\d+)]")
        return lineRegex.findAll(screenDump).mapNotNull { match ->
            val label = match.groupValues[1].substringBefore("{").trim()
            val x = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            val y = match.groupValues[3].toIntOrNull() ?: return@mapNotNull null
            if (label.isBlank() || x !in 0..999 || y !in 0..999) null else Triple(label, x, y)
        }.toList()
    }

    private fun isSameRewardRow(anchorX: Int, anchorY: Int, closeX: Int, closeY: Int): Boolean =
        abs(anchorY - closeY) <= 35 &&
            closeX != anchorX &&
            abs(closeX - anchorX) <= 350

    private fun isNearRewardResult(anchorX: Int, anchorY: Int, closeX: Int, closeY: Int): Boolean =
        abs(anchorY - closeY) <= 70 && abs(anchorX - closeX) <= 500

    private fun queryFor(label: String): String {
        val clean = label.substringBefore("{").trim()
        val glyph = rewardCloseGlyph.find(clean)?.value?.trim().orEmpty()
        return when {
            glyph.isNotBlank() -> glyph
            rewardCloseText.containsMatchIn(clean) ->
                rewardCloseText.find(clean)!!.value
            else -> clean.take(48)
        }
    }

    private fun normalize(value: String): String = value.replace(Regex("\\s+"), "")
}
