package com.aibutler.app

/**
 * 通用阻塞性弹窗硬关闭层。
 *
 * 背景：更新提示、公告、添加至桌面、添加组件、开启通知、青少年模式等弹窗过去只靠系统提示词
 * （规则6【弹窗优先】）引导模型主动关闭；模型偶尔不遵守时任务会卡在弹窗上，甚至点进弹窗里的
 * 转化按钮。本层在读屏文本命中弹窗特征且存在语义安全的关闭控件时，由系统直接代点关闭，
 * 不再依赖模型自觉。
 *
 * 安全边界：
 *  - 只点 ExitMemory.scoreButton 正分的关闭控件（关闭/跳过/取消/以后再说/残忍离开等）；
 *    「立即更新/添加/去开启」等转化类负分按钮永远不会被系统代点；
 *  - 看广告/视频领奖的播放页（倒计时、看完可得）不是弹窗，绝不代点，避免中断奖励结算；
 *  - 用户任务本身要更新/安装/下载时不启用（更新弹窗就是任务目标）；
 *  - 找不到安全关闭控件时返回 null，绝不为关弹窗而盲目 Back 打断正常页面。
 */
object PopupGuard {

    /** 一次可执行的关弹窗计划：closeLabel 交给 clickText 代点 */
    data class PopupPlan(val closeLabel: String, val reason: String)

    /** 强特征：这些词几乎只出现在阻塞性弹窗上，命中一个即可认定。
     *  注意「青少年模式/防沉迷」不能放这里：它是设置页/个人主页的常见菜单词，
     *  曾因放在强特征里，配合正常页面普遍存在的「返回」按钮，导致系统代点返回
     *  离开正常页面并死循环（体检 P1-10），已降为弱特征 */
    private val strongFeature = Regex(
        "发现新版本|版本更新|立即更新|更新提示|更新公告|新版本抢先|强制更新|" +
            "添加至桌面|添加到桌面|添加组件|桌面快捷方式|" +
            "开启通知|打开通知|开启消息推送|接收推送",
        RegexOption.IGNORE_CASE
    )

    /** 弱特征：正常信息流/设置页也可能出现，需 ≥2 个同时命中才认定 */
    private val weakFeatures = listOf(
        "公告", "温馨提示", "新版本", "弹窗", "活动通知", "消息通知", "系统通知",
        "青少年模式", "防沉迷"
    )

    /**
     * 激励播放页保护：看广告/视频领奖过程中的倒计时、结算文案不是弹窗。
     * 「5s后可跳过」的插屏广告也因此交给模型按规则19处理（等倒计时结束再关），不抢跑。
     */
    private fun isRewardPlaybackPage(normalized: String): Boolean = Regex(
        "[0-9０-９]+s后|[0-9０-９]+秒后|倒计时|看完.*可得|观看.*秒|浏览.*秒|" +
            "继续观看|观看完毕|奖励已发放|试玩.*得"
    ).containsMatchIn(normalized)

    /** 命中的弹窗特征描述；未命中返回 null。传入去空白后的读屏文本 */
    fun popupReason(normalized: String): String? = when {
        strongFeature.containsMatchIn(normalized) -> "更新/桌面/通知类阻塞性弹窗"
        weakFeatures.count { normalized.contains(it) } >= 2 -> "疑似公告/提示弹窗"
        else -> null
    }

    /**
     * 判定当前屏幕是否被阻塞性弹窗挡住，并给出可代点的安全关闭控件。
     * 条件全部满足才返回计划：任务不涉及安装/更新、不是激励播放页、命中弹窗特征、存在正分关闭控件。
     */
    fun plan(userTask: String, screenDump: String): PopupPlan? {
        val normalized = screenDump.replace(Regex("\\s+"), "")
        if (normalized.isBlank()) return null
        val taskText = userTask.replace(Regex("\\s+"), "")
        if (Regex("更新|升级|下载|安装", RegexOption.IGNORE_CASE).containsMatchIn(taskText)) return null
        if (isRewardPlaybackPage(normalized)) return null
        val reason = popupReason(normalized) ?: return null
        // visibleButtons 按语义打分排序，但 500 分档含「返回/退出/放弃类」——那不是弹窗关闭按钮，
        // 误判到正常页面时会变成系统反复替用户按返回（体检 P1-2 / P1-10 同族）。只认 400 分档
        // （关闭/跳过/取消/以后再说/我知道了），真弹窗几乎必有；没有就交还模型，绝不硬关。
        val close = ExitMemory.visibleButtons(screenDump)
            .firstOrNull { it.score in 300..499 }?.label ?: return null
        return PopupPlan(close, reason)
    }
}
