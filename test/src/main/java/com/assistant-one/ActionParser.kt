package com.assistant.one

data class ParsedAction(
    val isFinish: Boolean,
    val action: String,
    val elementX: Int? = null,
    val elementY: Int? = null,
    val startX: Int? = null,
    val startY: Int? = null,
    val endX: Int? = null,
    val endY: Int? = null,
    val app: String? = null,
    val text: String? = null,
    val message: String? = null,
    val durationSeconds: Float = 1f
)

object ActionParser {
    fun parse(raw: String): ParsedAction? {
        val doIndex = raw.lastIndexOf("do(action=")
        val finishIndex = raw.lastIndexOf("finish(message=")
        return when {
            finishIndex >= 0 && finishIndex > doIndex -> parseFinish(raw.substring(finishIndex))
            doIndex >= 0 -> parseDo(raw.substring(doIndex))
            else -> null
        }
    }

    private fun parseFinish(fragment: String): ParsedAction {
        return ParsedAction(
            isFinish = true,
            action = "Finish",
            message = quotedValue(fragment, "message") ?: fragment
        )
    }

    private fun parseDo(fragment: String): ParsedAction? {
        val action = quotedValue(fragment, "action") ?: return null
        val element = intList(fragment, "element")
        val start = intList(fragment, "start")
        val end = intList(fragment, "end")
        val duration = Regex("duration\\s*=\\s*\"?([0-9.]+)").find(fragment)
            ?.groupValues?.get(1)?.toFloatOrNull() ?: 1f
        return ParsedAction(
            isFinish = false,
            action = action,
            elementX = element?.getOrNull(0),
            elementY = element?.getOrNull(1),
            startX = start?.getOrNull(0),
            startY = start?.getOrNull(1),
            endX = end?.getOrNull(0),
            endY = end?.getOrNull(1),
            app = quotedValue(fragment, "app"),
            text = quotedValue(fragment, "text"),
            message = quotedValue(fragment, "message"),
            durationSeconds = duration.coerceAtLeast(0.1f)
        )
    }

    private fun quotedValue(source: String, key: String): String? {
        val match = Regex("$key\\s*=\\s*\"((?:\\\\.|[^\"\\\\])*)\"", RegexOption.DOT_MATCHES_ALL)
            .find(source) ?: return null
        return match.groupValues[1]
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }

    private fun intList(source: String, key: String): List<Int>? {
        val regex = Regex("$key\\s*=\\s*\\[\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*\\]")
        val match = regex.find(source) ?: return null
        return listOf(match.groupValues[1].toInt(), match.groupValues[2].toInt())
    }
}
