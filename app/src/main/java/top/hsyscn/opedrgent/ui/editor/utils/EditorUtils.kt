package top.hsyscn.opedrgent.ui.editor.utils

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * 编辑器工具函数集合
 */
object EditorUtils {

    /**
     * 格式化时间为相对时间（刚刚、X分钟前、X小时前、X天前）
     */
    fun formatTimeAgo(timestamp: Long): String {
        val diff = System.currentTimeMillis() - timestamp
        return when {
            diff < 60_000L -> "刚刚"
            diff < 3600_000L -> "${diff / 60_000}分钟前"
            diff < 86400_000L -> "${diff / 3600_000}小时前"
            else -> "${diff / 86400_000}天前"
        }
    }

    /**
     * 本地启发式文本补全（LLM 未接入时的 fallback）。
     *
     * 基于常见中文写作模式提供简单补全建议：
     * - 常见动词后接宾语
     * - 列表项自动补全下一项前缀
     * - 标点后的常见接续词
     */
    fun heuristicComplete(context: String): String {
        val trimmed = context.trimEnd()
        if (trimmed.isEmpty()) return ""

        // 列表模式检测：如果当前行以数字/符号开头，提示下一项
        if (Regex("""^(\d+[\.\、]|\-|\*)\s+""").containsMatchIn(trimmed.split("\n").lastOrNull() ?: "")) {
            val lines = trimmed.split("\n")
            val lastLine = lines.lastOrNull() ?: ""
            val match = Regex("""^(\d+[\.\、]|\-|\*)\s+""").find(lastLine)
            if (match != null) {
                val prefix = match.value
                // 数字列表递增
                val numMatch = Regex("""^(\d+)""").find(prefix)
                if (numMatch != null) {
                    val nextNum = (numMatch.value.toInt() + 1).toString()
                    return prefix.replaceFirst(Regex("""^\d+"""), nextNum) + " "
                }
                return prefix
            }
        }

        // 常见句尾补全
        return when {
            trimmed.endsWith("首先") -> "，其次"
            trimmed.endsWith("其次") -> "，再次"
            trimmed.endsWith("再次") -> "，最后"
            trimmed.endsWith("一方面") -> "，另一方面"
            trimmed.endsWith("例如") -> "，"
            trimmed.endsWith("包括") -> "："
            trimmed.endsWith("因为") -> "，所以"
            trimmed.endsWith("虽然") -> "，但是"
            trimmed.endsWith("不仅") -> "，而且"
            trimmed.endsWith("总") -> "结"
            trimmed.endsWith("具") -> "体来说"
            else -> ""
        }
    }

    /**
     * 插入格式化文本到当前光标位置
     */
    fun insertFormatting(
        content: TextFieldValue,
        prefix: String,
        suffix: String = ""
    ): TextFieldValue {
        val selection = content.selection
        val text = content.text
        val selectedText = text.substring(selection.start, selection.end)
        val newText = text.substring(0, selection.start) + prefix + selectedText + suffix + text.substring(selection.end)
        return TextFieldValue(
            newText,
            TextRange(selection.start + prefix.length + selectedText.length + suffix.length)
        )
    }
}