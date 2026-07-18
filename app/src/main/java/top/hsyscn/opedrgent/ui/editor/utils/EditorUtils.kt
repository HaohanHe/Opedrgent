package top.hsyscn.opedrgent.ui.editor.utils

import android.content.Context
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import top.hsyscn.opedrgent.R

/**
 * 编辑器工具函数集合
 */
object EditorUtils {

    /**
     * 格式化时间为相对时间（刚刚、X分钟前、X小时前、X天前）
     */
    fun formatTimeAgo(context: Context, timestamp: Long): String {
        val diff = System.currentTimeMillis() - timestamp
        return when {
            diff < 60_000L -> context.getString(R.string.editor_gang_gang)
            diff < 3600_000L -> context.getString(R.string.note_list_1_fen_zhong_qian, diff / 60_000)
            diff < 86400_000L -> context.getString(R.string.note_list_1_xiao_shi_qian, diff / 3600_000)
            else -> context.getString(R.string.note_list_1_tian_qian, diff / 86400_000)
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
    fun heuristicComplete(context: Context, text: String): String {
        val trimmed = text.trimEnd()
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
            trimmed.endsWith("首先") -> context.getString(R.string.editor_complete_qi_ci)
            trimmed.endsWith("其次") -> context.getString(R.string.editor_complete_zai_ci)
            trimmed.endsWith("再次") -> context.getString(R.string.editor_complete_zui_hou)
            trimmed.endsWith("一方面") -> context.getString(R.string.editor_complete_ling_yi_fang_mian)
            trimmed.endsWith("例如") -> context.getString(R.string.editor_complete_dou_hao)
            trimmed.endsWith("包括") -> context.getString(R.string.editor_complete_mao_hao)
            trimmed.endsWith("因为") -> context.getString(R.string.editor_complete_suo_yi)
            trimmed.endsWith("虽然") -> context.getString(R.string.editor_complete_dan_shi)
            trimmed.endsWith("不仅") -> context.getString(R.string.editor_complete_er_qie)
            trimmed.endsWith("总") -> context.getString(R.string.editor_complete_jie)
            trimmed.endsWith("具") -> context.getString(R.string.editor_complete_ti_lai_shuo)
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