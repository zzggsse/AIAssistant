package com.aibutler.app

import java.util.Locale

/**
 * 用户精确目标和屏幕证据之间的通用闭环。
 *
 * 这里刻意不认识具体应用：只要页面暴露了滚轮/选择器或完成证据，就能对时间、
 * 数量、日期做保存前拦截；后续新增 App 不需要改这个类。
 */
object TaskGoalVerifier {

    data class TimeGoal(val hour: Int, val minute: Int) {
        val compact = String.format(Locale.US, "%02d:%02d", hour, minute)
        val displayValues = listOf(
            compact,
            String.format(Locale.US, "%d:%02d", hour, minute),
            String.format(Locale.US, "%02d时%02d分", hour, minute),
            String.format(Locale.US, "%d点%02d分", hour, minute),
            String.format(Locale.US, "%02d：%02d", hour, minute)
        )
    }

    data class QuantityGoal(
        val value: String,
        val unit: String,
        /** 数量挂在动作动词上（看3个广告/发5条消息）：数的是做了几次，屏幕字样不是证据 */
        val isActionCount: Boolean = false
    ) {
        val display = value + unit
    }

    data class TaskGoal(
        val time: TimeGoal? = null,
        val quantity: QuantityGoal? = null,
        val absoluteDate: String? = null,
        val relativeDate: String? = null
    ) {
        val hasConstraint = time != null || quantity != null ||
            !absoluteDate.isNullOrBlank() || !relativeDate.isNullOrBlank()
    }

    enum class Status { CONFIRMED, BLOCKED, NEEDS_EVIDENCE }

    data class Check(val status: Status, val detail: String)

    private val cnDigits = mapOf(
        '零' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9
    )

    fun parse(userText: String): TaskGoal {
        return TaskGoal(
            time = parseTime(userText),
            quantity = parseQuantity(userText),
            absoluteDate = parseAbsoluteDate(userText),
            relativeDate = parseRelativeDate(userText)
        )
    }

    fun check(goal: TaskGoal, screenDump: String): Check {
        if (!goal.hasConstraint) return Check(Status.NEEDS_EVIDENCE, "未识别到精确目标值")

        val picker = PickerSnapshot.from(screenDump)
        val compactScreen = screenDump.replace(Regex("\\s+"), "")

        goal.time?.let { target ->
            if (picker.isPicker && picker.timeValue != null) {
                val (hour, minute) = picker.timeValue ?: Pair(0, 0)
                if (hour != target.hour || minute != target.minute) {
                    return Check(
                        Status.BLOCKED,
                        "选择器当前是 ${"%02d:%02d".format(hour, minute)}，目标必须是 ${target.compact}"
                    )
                }
                return Check(
                    Status.BLOCKED,
                    "选择器已到 ${target.compact}，但还没有保存；请点击完成/保存，保存并看到列表/成功证据后再结束"
                )
            }
            val visibleTarget = target.displayValues.any { compactScreen.contains(it) }
            if (visibleTarget) return Check(Status.CONFIRMED, "屏幕显示目标时间 ${target.compact}")
        }

        goal.quantity?.let { target ->
            val column = picker.columns.firstOrNull {
                it.label.endsWith(target.unit)
            }
            if (column != null && column.value != null) {
                // 数值化比对：value 是 Int（"2.5" 截成 2），老写法 "2" != "2.5" 字符串永不等 → 永久 BLOCKED
                // 卡死到超时（体检第六轮 P3-4）。滚轮只有整数档位而目标带小数时，按四舍五入最接近档位算达成
                val wheelText = column.valueText ?: column.value.toString()
                val wheelNum = wheelText.toDoubleOrNull()
                val wantedNum = target.value.toDoubleOrNull()
                val wheelMatches = if (wheelNum != null && wantedNum != null) {
                    wheelNum == wantedNum ||
                        (wheelNum % 1.0 == 0.0 && wantedNum % 1.0 != 0.0 &&
                            // kotlin.math.round 是银行家舍入（round(2.5)=2.0），这里需要四舍五入
                            // 远离零：Math.round(2.5)=3、Math.round(-2.5)=-2（正数域等价于远离零）
                            wheelNum == Math.round(wantedNum).toDouble())
                } else {
                    wheelText == target.value
                }
                if (!wheelMatches) {
                    return Check(
                        Status.BLOCKED,
                        "选择器当前是 ${wheelText}${target.unit}，目标必须是 ${target.display}"
                    )
                }
                // 滚轮已到目标值（含最近整数档）不算完成证据：滚轮 label 本身就是"2.5杯"，
                // compactScreen.contains 会把滚轮自己的文本误判成"屏幕已看到目标值"→ 跳过让模型走保存
            }
            else if (!target.isActionCount && compactScreen.contains(target.display)) {
                return Check(Status.CONFIRMED, "屏幕显示目标数量 ${target.display}")
            }
        }

        goal.absoluteDate?.let { target ->
            if (compactScreen.contains(target)) {
                return Check(Status.CONFIRMED, "屏幕显示目标日期 $target")
            }
        }

        goal.relativeDate?.let { target ->
            if (compactScreen.contains(target)) {
                return Check(Status.CONFIRMED, "屏幕显示目标日期 $target")
            }
        }

        return Check(Status.NEEDS_EVIDENCE, "最新屏幕还没有目标值或明确的保存成功证据")
    }

    fun parseTime(raw: String): TimeGoal? {
        parseHalfTime(raw)?.let { return it }
        val text = raw.replace("：" , ":").replace(Regex("(?<=[点时])\\s*半"), "点:30")
        val pattern = Regex(
            "(凌晨|早上|早晨|上午|中午|下午|傍晚|晚上|夜里|深夜)?\\s*" +
                "(\\d{1,2}|[零一二两三四五六七八九十]{1,3})\\s*[:时点]\\s*" +
                "(?:(\\d{1,2}|[零一二三四五六七八九十]{1,3})(?:半)?|半)?\\s*分?"
        )
        for (match in pattern.findAll(text)) {
            val hourRaw = match.groupValues[2]
            val minuteRaw = match.groupValues[3]
            val hourBase = number(hourRaw) ?: continue
            val minute = when {
                match.groupValues[0].contains("半") -> 30
                minuteRaw.isBlank() -> parseMinuteSuffix(text.substring(match.range.last + 1)) ?: 0
                else -> number(minuteRaw) ?: continue
            }
            if (minute !in 0..59) continue

            var hour = hourBase
            val qualifier = match.groupValues[1]
            if (qualifier.isBlank()) {
                // “9点”只按自然口语处理；“21点/09点”保持数值。
                if (match.range.first > 0) {
                    val before = text.substring(0, match.range.first).takeLast(14)
                    if (Regex("(小时|耗时|用\\s*\\d+\\s*个?)\\s*$").containsMatchIn(before)) continue
                }
            } else {
                hour = when (qualifier) {
                    "凌晨" -> if (hour == 12) 0 else hour
                    "中午" -> if (hour in 1..11) hour + 12 else hour
                    "下午", "傍晚" -> if (hour in 1..11) hour + 12 else hour
                    "晚上", "夜里", "深夜" -> if (hour in 1..11) hour + 12 else hour
                    else -> hour
                }
            }
            if (hour in 0..23) return TimeGoal(hour, minute)
        }
        return null
    }

    private fun parseHalfTime(text: String): TimeGoal? {
        val m = Regex(
            "(凌晨|早上|早晨|上午|中午|下午|傍晚|晚上|夜里|深夜)?\\s*" +
                "(\\d{1,2}|[零一二两三四五六七八九十]{1,3})\\s*[:时点]\\s*半"
        ).find(text) ?: return null
        val hourBase = number(m.groupValues[2]) ?: return null
        val qualifier = m.groupValues[1]
        var hour = when (qualifier) {
            "凌晨" -> if (hourBase == 12) 0 else hourBase
            "中午" -> if (hourBase in 1..11) hourBase + 12 else hourBase
            "下午", "傍晚", "晚上", "夜里", "深夜" -> if (hourBase in 1..11) hourBase + 12 else hourBase
            else -> hourBase
        }
        if (hour !in 0..23) return null
        return TimeGoal(hour, 30)
    }

    private fun parseMinuteSuffix(rest: String): Int? {
        if (rest.startsWith("半")) return 30
        val m = Regex("^\\s*[:：]?\\s*(\\d{1,2}|[零一二三四五六七八九十]{1,3})\\s*分?").find(rest)
            ?: return null
        val value = number(m.groupValues[1]) ?: return null
        return value.takeIf { it in 0..59 }
    }

    private fun parseQuantity(raw: String): QuantityGoal? {
        val m = Regex("(\\d+(?:\\.\\d+)?)\\s*(杯|个|份|瓶|件|张|次|人|斤|公斤|克|毫升|升|公里|米)")
            .find(raw) ?: return null
        // 只对带小数点的值去尾零（"2.0"→"2"）；整数不能动，否则 "10".trimEnd('0','.') 会变成 "1"
        val rawNum = m.groupValues[1]
        val value = (if (rawNum.contains('.')) rawNum.trimEnd('0').trimEnd('.') else rawNum).ifBlank { "0" }
        // 数量紧跟在动作动词后面 = 动作次数（看3个广告）；前面没有动作动词才是页面值（下单2杯奶茶的"2杯"）
        val before = raw.substring(0, m.range.first).takeLast(6)
        val isActionCount = Regex(
            "(看|刷|浏览|观看|听|播放|发|点赞|评论|关注|邀请|买|下单|点|开|设|订|拍|加|回复)[^0-9]*$"
        ).containsMatchIn(before)
        return QuantityGoal(value, m.groupValues[2], isActionCount)
    }

    /**
     * 提取「N个广告」类动作数量目标；数量词不在名词前或没有数量词时返回 null。
     * 供完成关做机械计数裁决，替代"屏幕出现目标字样"这类假证据。
     */
    fun actionQuantityTarget(raw: String, noun: Regex): Int? {
        val text = raw.replace(Regex("\\s+"), "")
        val m = Regex("(\\d+|[零一二两三四五六七八九十]+)个(?=" + noun.pattern + ")").find(text)
            ?: return null
        return number(m.groupValues[1])
    }

    private fun parseAbsoluteDate(raw: String): String? {
        val m = Regex("(\\d{4})\\s*年\\s*(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*[日号]").find(raw)
            ?: return null
        return String.format(
            Locale.US, "%04d年%02d月%02d日",
            m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()
        )
    }

    private fun parseRelativeDate(raw: String): String? {
        return Regex("(今天|明天|后天|大后天)").find(raw)?.groupValues?.get(1)
    }

    fun number(raw: String): Int? {
        if (raw.isBlank()) return null
        raw.toIntOrNull()?.let { return it }
        if (raw.length == 1) return cnDigits[raw[0]]
        if (raw == "十") return 10
        if (raw.length == 2 && raw.contains('十')) {
            val parts = raw.split('十')
            val tens = if (parts[0].isEmpty()) 1 else cnDigits[parts[0][0]] ?: return null
            val ones = if (parts[1].isEmpty()) 0 else cnDigits[parts[1][0]] ?: return null
            return tens * 10 + ones
        }
        return raw.map { cnDigits[it] ?: return null }.reduce { a, b -> a * 10 + b }
    }

    enum class PickerUnit { HOUR, MINUTE, SECOND, YEAR, MONTH, DAY, UNKNOWN }

    data class PickerColumn(
        val label: String,
        val meta: String,
        val x: Int,
        val y: Int,
        val value: Int?,
        val unit: PickerUnit,
        /** 原始数字文本（如 "2.5"）：value 是 Int，截断后无法与小数目标比对（体检第六轮 P3-4） */
        val valueText: String? = null
    )

    data class PickerSnapshot(
        val isPicker: Boolean,
        val columns: List<PickerColumn>
    ) {
        val timeValue: Pair<Int, Int>? by lazy {
            val hour = columns.firstOrNull { it.unit == PickerUnit.HOUR }?.value
                ?: columns.getOrNull(0)?.value
            val minute = columns.firstOrNull { it.unit == PickerUnit.MINUTE }?.value
                ?: columns.getOrNull(1)?.value
            if (hour != null && minute != null && hour in 0..23 && minute in 0..59) {
                Pair(hour, minute)
            } else null
        }

        companion object {
            private val linePattern = Regex(
                "(?m)^(输入框|可点击|可滑动):\\s*(.+?)\\s*(?:\\{([^}]*)\\})?\\s*\\[(-?\\d+),(-?\\d+)\\]\\s*$"
            )

            fun from(screenDump: String): PickerSnapshot {
                val rawColumns = linePattern.findAll(screenDump).mapNotNull { m ->
                    val label = m.groupValues[2].trim()
                    val meta = m.groupValues[3].orEmpty()
                    val numberText = Regex("\\d+(?:\\.\\d+)?").find(label)?.value
                    val number = numberText?.toDoubleOrNull()?.toInt()
                    if (number == null) null else PickerColumn(
                        label = label,
                        meta = meta,
                        x = m.groupValues[4].toInt(),
                        y = m.groupValues[5].toInt(),
                        value = number,
                        unit = unitOf(label, meta),
                        valueText = numberText
                    )
                }.toList()
                val detected = rawColumns.any {
                    Regex("NumberPicker|WheelView|WheelPicker|TimePicker|DatePicker", RegexOption.IGNORE_CASE)
                        .containsMatchIn(it.meta)
                } || Regex(
                    "NumberPicker|WheelView|WheelPicker|TimePicker|DatePicker", RegexOption.IGNORE_CASE
                ).containsMatchIn(screenDump)
                val columns = if (!detected) emptyList() else assignImplicitUnits(rawColumns)
                return PickerSnapshot(detected && columns.isNotEmpty(), columns)
            }

            private fun assignImplicitUnits(raw: List<PickerColumn>): List<PickerColumn> {
                val known = raw.count { it.unit != PickerUnit.UNKNOWN }
                if (known >= 2) return raw
                val byX = raw.sortedBy { it.x }
                if (byX.size == 2 && byX.all { it.value in 0..59 }) {
                    return listOf(
                        byX[0].copy(unit = PickerUnit.HOUR),
                        byX[1].copy(unit = PickerUnit.MINUTE)
                    )
                }
                return raw
            }

            private fun unitOf(label: String, meta: String): PickerUnit {
                val context = (label + " " + meta).lowercase(Locale.US)
                return when {
                    label.contains("小时") -> PickerUnit.HOUR
                    label.contains("时") || label.contains("点") || context.contains("hour") -> PickerUnit.HOUR
                    label.contains("分") || context.contains("minute") || context.contains("_min") -> PickerUnit.MINUTE
                    label.contains("秒") || context.contains("second") -> PickerUnit.SECOND
                    label.contains("年") || context.contains("year") -> PickerUnit.YEAR
                    label.contains("月") || context.contains("month") -> PickerUnit.MONTH
                    label.contains("日") || label.contains("号") || context.contains("day") -> PickerUnit.DAY
                    else -> PickerUnit.UNKNOWN
                }
            }
        }
    }

    class PickerTracker {
        private var lastSnapshot: PickerSnapshot? = null
        private var lastObservedChange: Int? = null
        private var lastActionDy: Int = 0
        private var unchangedStreak = 0

        fun observe(snapshot: PickerSnapshot) {
            val previous = lastSnapshot ?: run {
                lastSnapshot = snapshot
                return
            }
            val changed = pickerTimeChange(previous, snapshot)
            if (changed != null && changed != 0) {
                lastObservedChange = changed
                unchangedStreak = 0
            }
            lastSnapshot = snapshot
        }

        fun record(before: PickerSnapshot, after: PickerSnapshot, action: String) {
            val swipe = Regex("start=\\[\\d+\\s*,\\s*(\\d+)\\].*end=\\[\\d+\\s*,\\s*(\\d+)\\]")
                .find(action.replace("\n", " ")) ?: return
            val startY = swipe.groupValues[1].toInt()
            val endY = swipe.groupValues[2].toInt()
            lastActionDy = endY - startY
            val changed = pickerTimeChange(before, after)
            if (changed == null || changed == 0) {
                unchangedStreak++
                return
            }
            lastObservedChange = changed
            unchangedStreak = 0
        }

        fun advise(target: TimeGoal, screen: PickerSnapshot): String {
            if (!screen.isPicker) return ""
            val current = screen.timeValue ?: return ""
            val currentText = String.format(Locale.US, "%02d:%02d", current.first, current.second)
            if (current.first == target.hour && current.second == target.minute) {
                return "\n🎯 系统校验：滚轮当前值已经是 ${target.compact}。现在必须点击完成/保存；保存后如果屏幕不是目标值，继续修改，不能 finish。"
            }
            val field = if (current.first != target.hour) "小时" else "分钟"
            val action = recommendedSwipe(target, screen)
            val actionHint = if (action.isNullOrBlank()) "" else "建议这一轮直接输出：$action"
            return "\n🎯 系统校验：滚轮当前值是 $currentText，目标是 ${target.compact}。" +
                "请只调整 $field 列。$actionHint 调整到完全一致前，禁止点击完成/保存。"
        }

        fun recommendedSwipe(target: TimeGoal, screen: PickerSnapshot): String? {
            if (!screen.isPicker) return null
            val current = screen.timeValue ?: return null
            val column = when {
                current.first != target.hour -> screen.columns.firstOrNull { it.unit == PickerUnit.HOUR }
                current.second != target.minute -> screen.columns.firstOrNull { it.unit == PickerUnit.MINUTE }
                else -> null
            } ?: return null

            val rawDiff = when (column.unit) {
                PickerUnit.HOUR -> circularDiff(target.hour, current.first, 24)
                PickerUnit.MINUTE -> circularDiff(target.minute, current.second, 60)
                else -> return null
            }
            // 无障碍坐标是手指方向：向上滑动（dy<0）让数字值增加，向下滑动则减少。
            val desiredSign = if (rawDiff > 0) 1 else -1
            val swipeDy = if (desiredSign > 0) -30 else 30
            val x = column.x.coerceIn(0, 999)
            val y = column.y.coerceIn(40, 960)
            val startY = (y - swipeDy).coerceIn(0, 999)
            val endY = (y + swipeDy).coerceIn(0, 999)
            return "do(action=\"Swipe\", start=[$x,$startY], end=[$x,$endY])"
        }

        private fun pickerTimeChange(before: PickerSnapshot, after: PickerSnapshot): Int? {
            val oldTime = before.timeValue ?: return null
            val newTime = after.timeValue ?: return null
            val old = oldTime.first * 60 + oldTime.second
            val new = newTime.first * 60 + newTime.second
            return circularDiff(new, old, 24 * 60)
        }
    }

    fun labelAtPoint(screenDump: String, x: Int, y: Int): String {
        val pattern = Regex(
            "(输入框|可点击|可滑动):\\s*(.+?)\\s*\\[(-?\\d+),(-?\\d+)\\](?:\\s*\\{([^}]*)\\})?"
        )
        return pattern.findAll(screenDump)
            .map { Triple(it.groupValues[2], it.groupValues[3].toInt(), it.groupValues[4].toInt()) }
            .minByOrNull { (label, cx, cy) ->
                (cx - x) * (cx - x) + (cy - y) * (cy - y) + if (label.isBlank()) 10000 else 0
            }
            ?.first.orEmpty()
    }

    fun completionTapMismatch(
        goal: TaskGoal,
        answer: String,
        screenDump: String
    ): String? {
        if (!goal.hasConstraint) return null
        val point = Regex("element=\\[(-?\\d+)\\s*,\\s*(-?\\d+)\\]").find(answer) ?: return null
        val label = labelAtPoint(screenDump, point.groupValues[1].toInt(), point.groupValues[2].toInt())
        if (!Regex("完成|保存|确定|确认|提交").containsMatchIn(label)) return null
        val picker = PickerSnapshot.from(screenDump)
        if (picker.isPicker) {
            goal.time?.let { target ->
                val current = picker.timeValue ?: return null
                if (current.first != target.hour || current.second != target.minute) {
                    return "选择器当前不是 ${target.compact}，已拦截保存"
                }
            }
            goal.quantity?.let { target ->
                val column = picker.columns.firstOrNull {
                    it.label.endsWith(target.unit)
                }
                if (column?.value != null && column.value.toString() != target.value) {
                    return "选择器当前不是 ${target.display}，已拦截保存"
                }
            }
            return null
        }
        val result = check(goal, screenDump)
        return if (result.status == Status.BLOCKED) result.detail else null
    }

    private fun circularDiff(target: Int, current: Int, range: Int): Int {
        val diff = (target - current) % range
        return when {
            diff > range / 2 -> diff - range
            diff < -range / 2 -> diff + range
            else -> diff
        }
    }
}
