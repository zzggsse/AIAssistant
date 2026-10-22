package com.aibutler.app

/**
 * 统一意图路由（纯函数，不依赖 android.*，可整体单测，见 AgentLogicTest）。
 *
 * 一条用户指令进来后"走哪条路"的全部判断都收在这里，作为唯一出口：
 *  1. parseNativeDirect —— 本地确定性直达（设闹钟/记便签，零模型调用），
 *     未命中或被否决词拦下的交还给模型路径；
 *  2. isQuickTask —— 短单步指令，跳过规划器省一次大模型往返；
 *  3. routeBrain —— 大脑路由：纯聊天/问答不进 AutoGLM 视觉脑（否则闲聊会误入空转）；
 *     命中原生工具的指令不进视觉脑（Bug#3），并注入"原生工具优先"系统提示。
 *
 * 原则：本对象只做决策不做执行；排队、确认弹窗、线程与 UI 交互留在 MainActivity。
 * 历史上这些判断散落在 MainActivity 的内联正则里，2026-10-22 收拢为此处（行为不变）。
 * 以下正则与原内联版本逐字一致，勿随手改动（都是真机回归换来的经验）。
 */
object TaskRouter {

    /** 本地直达解析结果。None = 没命中本地捷径，走模型路径。 */
    sealed class NativeDirect {
        data class Alarm(val req: NativeTaskRouter.AlarmRequest) : NativeDirect()
        data class Note(val req: NativeTaskRouter.NoteRequest) : NativeDirect()
        object None : NativeDirect()
    }

    fun parseNativeDirect(raw: String): NativeDirect {
        NativeTaskRouter.parseAlarmRequest(raw)?.let { return NativeDirect.Alarm(it) }
        NativeTaskRouter.parseNoteRequest(raw)?.let { return NativeDirect.Note(it) }
        return NativeDirect.None
    }

    // ---- isQuickTask：短单步指令判定 ----

    private val quickTaskBlock = Regex("然后|接着|之后|最后|并且|同时|比较|每个|所有|一步步")
    private val quickTaskVerb = Regex(
        "^\\s*(请|帮我|麻烦|帮忙|给我|我)?(打开|启动|开启|关闭|退出|回到|回|搜一?下|搜索|搜|查一?下|查询|查|设一?个|设置|定一?个|定个|记一?下|记个|看看|看下|读一?下|拨|打给|发)"
    )

    /** 短单步指令：跳过任务规划，省一次大模型往返。组合指令（含"然后"等）不算。 */
    fun isQuickTask(userText: String): Boolean =
        userText.length <= 14 &&
            !quickTaskBlock.containsMatchIn(userText) &&
            quickTaskVerb.containsMatchIn(userText)

    // ---- 聊天兜底与原生工具识别 ----

    private val chatLike = Regex(
        "^(你好|您好|嗨|hi|hello)|你是谁|介绍一下你自己|什么是|为什么|讲个|说个|写个故事|帮我解释",
        RegexOption.IGNORE_CASE
    )

    /** 规划器漏判时的兜底：明显是问候/自我介绍/知识问答，直接走聊天。快捷指令优先于此判定。 */
    fun looksLikeChat(userText: String): Boolean =
        !isQuickTask(userText) && chatLike.containsMatchIn(userText.trim())

    /** Bug#3：记便签/设闹钟/发短信等有原生 function calling 工具的指令不进视觉脑。 */
    private val nativeToolPattern = Regex("记一下|记个|便签|备忘|设.*闹钟|定.*闹钟|发短信|发消息给")

    fun matchesNativeTool(userText: String): Boolean =
        nativeToolPattern.containsMatchIn(userText.trim())

    /**
     * 大脑路由决策（规划器跑完之后调用）。
     * @property skipAutoGlm  纯聊天/问答/原生工具指令 → 不进 AutoGLM 视觉脑
     * @property nativeToolFirst  注入"原生工具优先"系统提示（与 nativeToolMatch 同值）
     * @property launchTargetApp  模型执行前是否先做通用目标应用路由
     *          （等价于旧的 !chatPlan && !looksLikeChat && !hasNativeToolMatch）
     */
    data class BrainRoute(
        val chatPlan: Boolean,
        val looksLikeChat: Boolean,
        val nativeToolMatch: Boolean,
        val skipAutoGlm: Boolean,
        val nativeToolFirst: Boolean,
        val launchTargetApp: Boolean
    )

    fun routeBrain(userText: String, plan: AgentEngine.TaskPlan?): BrainRoute {
        val chatPlan = plan?.taskType == "chat"
        val chat = looksLikeChat(userText)
        val nativeTool = matchesNativeTool(userText)
        val skip = chatPlan || chat || nativeTool
        return BrainRoute(
            chatPlan = chatPlan,
            looksLikeChat = chat,
            nativeToolMatch = nativeTool,
            skipAutoGlm = skip,
            nativeToolFirst = nativeTool,
            launchTargetApp = !skip
        )
    }
}
