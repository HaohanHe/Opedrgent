package top.hsyscn.opedrgent.cultivation.mirror

import top.hsyscn.opedrgent.cultivation.model.growth.GrowthPeriodData
import top.hsyscn.opedrgent.cultivation.model.growth.GrowthPeriodType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 一次“系统提示 + 用户数据”的提示包。 */
data class GrowthPromptBundle(
    val system: String,
    val user: String,
)

/**
 * 周期成长回顾提示构建器。
 *
 * 工程只把 [GrowthPeriodData] 里已经确定性统计好的真实数据组织成文本，解读完全交给模型：
 * - 数字与逐字原句原样嵌入；模型不得改动，evidence 只能引用下方“可用原句”清单中的某一条；
 * - 不告诉模型“应该夸”或“应该批”，只给统计口径的纪律（有增减才写变化、没进步不强凑）。
 */
object GrowthReviewPromptBuilder {

    fun build(data: GrowthPeriodData): GrowthPromptBundle {
        return GrowthPromptBundle(system = systemPrompt(data.supportMode), user = userPrompt(data))
    }

    private fun systemPrompt(supportMode: Boolean): String {
        val supportLine = if (supportMode) {
            "- 注意：本期记录里出现过需要情绪支持的语境（模型此前已判定为 SUPPORT/CRISIS）。请以支持性、不评判的语言为主，先稳住，再谈观察；不要放大问题、不要给人贴标签。"
        } else {
            "- 若用户数据里出现自我否定倾向的语境，按 SUPPORT/CRISIS 的优先原则处理：先共情稳住，再谈事实。"
        }
        return """
            你在帮用户做一段个人修炼的周期成长回顾。下面的【真实数据】由本地代码对用户历次复盘记录与行动项做确定性统计得到，数字与原句都是真的。你的任务：基于这些数据写一份简短回顾。

            纪律：
            - overall 基于真实数据概括本期；数据为空就如实说“本周期没有复盘记录”，不要编造经历。
            - changes 只陈述数据里确有增减的项（如某维度次数变化、完成数变化）；没有变化就如实说明没有明显变化，不要硬凑、不要罗列猜测。
            - strengths 只写本期有数据支撑的具体进步；没有就给空数组，不讨好、不强行肯定。
            - focus 给下周期 1~3 个可聚焦的具体点，不超过 3 个；写用户能直接做的事，不写抽象口号。
            - evidence 必须逐字复制【真实数据】“可用原句”清单中的某一条，不得改写、拼接、缩写或编造；每条附其维度名。没有合适原句就给空数组。
            - 不对人贴标签、不做人格评判；不打击也不讨好，语气平实。
            $supportLine

            只输出 JSON，不要输出 JSON 以外的任何文字：
            {
              "overall": "string",
              "changes": ["string"],
              "strengths": ["string"],
              "focus": ["string"],
              "evidence": [{"dimension": "string", "quote": "string"}]
            }
        """.trimIndent()
    }

    private fun userPrompt(data: GrowthPeriodData): String {
        val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val start = dayFmt.format(Date(data.periodStart))
        val end = dayFmt.format(Date(data.periodEndExclusive - 1))
        val sb = StringBuilder()
        sb.appendLine("【真实数据】")
        sb.appendLine("周期：${data.periodLabel}（$start ~ $end）")
        sb.appendLine("复盘次数：本期 ${data.reflectionCount} 次（环比 ${signed(data.reflectionCountDelta)}）；" +
            "按透镜：言行镜 ${data.lensBreakdown["CRITIQUE"] ?: 0}，" +
            "认知镜 ${data.lensBreakdown["COGNITIVE"] ?: 0}，" +
            "榜样镜 ${data.lensBreakdown["EXEMPLAR"] ?: 0}")

        sb.appendLine("维度出现次数（本期 / 环比差）：")
        if (data.dimensionTotals.isEmpty()) {
            sb.appendLine("  （无）")
        } else {
            data.dimensionTotals.entries
                .sortedByDescending { it.value }
                .forEach { (dim, total) ->
                    sb.appendLine("  - $dim：$total 次（环比 ${signed(data.dimensionDeltas[dim] ?: 0)}）")
                }
        }

        sb.appendLine("行动项：本期新建 OPEN ${data.actionCreatedOpen} / DONE ${data.actionCreatedDone} / DEFERRED ${data.actionCreatedDeferred}；" +
            "本期完成 ${data.actionCompletedInPeriod} 件（环比 ${signed(data.actionCompletedDelta)}）")

        sb.appendLine("可用原句（evidence 只能逐字复制以下任一条）：")
        if (data.evidence.isEmpty()) {
            sb.appendLine("  （本期无逐字原句）")
        } else {
            data.evidence.forEach { e ->
                e.quotes.forEach { q -> sb.appendLine("  - [${e.dimension}] $q") }
            }
        }
        return sb.toString()
    }

    private fun signed(delta: Int): String =
        if (delta > 0) "+$delta" else delta.toString()

    /** 供解析器复用的 JSON 提取（与 MirrorPromptBuilder 同一实现：先 ```json 块，再裸对象）。 */
    fun extractJsonObject(response: String): String =
        MirrorPromptBuilder.extractJsonObject(response)
}
