package top.hsyscn.opedrgent.cultivation.mirror

import org.json.JSONArray
import org.json.JSONObject
import top.hsyscn.opedrgent.cultivation.model.ExemplarAction
import top.hsyscn.opedrgent.cultivation.model.ExemplarHighlight
import top.hsyscn.opedrgent.cultivation.model.ExemplarImprovement
import top.hsyscn.opedrgent.cultivation.model.ExemplarReport
import top.hsyscn.opedrgent.cultivation.model.FeedbackMode
import top.hsyscn.opedrgent.cultivation.model.FollowUp
import top.hsyscn.opedrgent.cultivation.model.MirrorRoute
import top.hsyscn.opedrgent.utils.DebugLog

/**
 * 榜样镜分析器：组装提示、调用统一后端、把轻量 JSON 协议解析为 [ExemplarReport]。
 * 与 [MirrorAnalyzer] 同构：不写死分析步骤，只负责一次“生成 + 结构化解析”。
 */
class ExemplarAnalyzer {

    /**
     * 生成并解析一份榜样镜报告。
     * @throws MirrorParseException 模型未按协议返回可解析 JSON 时
     */
    suspend fun analyze(
        backend: MirrorLlmBackend,
        exemplar: String,
        whyExemplar: String?,
        transcript: String,
        mode: FeedbackMode = FeedbackMode.STANDARD,
        historyHint: String? = null,
    ): ExemplarReport {
        val system = ExemplarPromptBuilder.systemPrompt(exemplar, whyExemplar, mode)
        val user = ExemplarPromptBuilder.userPrompt(transcript, historyHint)
        val raw = backend.complete(system, user)
        return parse(raw, exemplar, mode, backend)
    }

    /** 从原始文本解析结构化报告；可见性 internal 以便质量门与单测复用。 */
    internal fun parse(
        raw: String,
        exemplar: String,
        mode: FeedbackMode,
        backend: MirrorLlmBackend,
    ): ExemplarReport {
        val jsonText = MirrorPromptBuilder.extractJsonObject(raw)
        val root = runCatching { JSONObject(jsonText) }
            .getOrElse { throw MirrorParseException("榜样镜返回不是有效 JSON: ${it.message}") }

        val route = MirrorRoute.fromName(root.optString("route"))
        val analyze = route == MirrorRoute.ANALYZE
        val report = ExemplarReport(
            exemplar = exemplar,
            situation = root.optString("situation", "").trim(),
            actions = if (analyze) parseActions(root.optJSONArray("actions")) else emptyList(),
            highlights = if (analyze) parseHighlights(root.optJSONArray("highlights")) else emptyList(),
            improvements = if (analyze) parseImprovements(root.optJSONArray("improvements")) else emptyList(),
            takeaway = if (analyze) root.optString("takeaway", "").trim() else "",
            route = route,
            support = root.optString("support", "").trim(),
            helpResources = root.optString("helpResources", "").trim(),
            followUps = parseFollowUps(root.optJSONArray("followUps")),
            mode = mode,
            modelUsed = backend.modelLabel,
            backend = backend.id,
            createdAt = System.currentTimeMillis(),
            rawResponse = raw,
        )
        DebugLog.i(
            TAG,
            "榜样镜解析 actions=${report.actions.size} highlights=${report.highlights.size} " +
                "improvements=${report.improvements.size} backend=${backend.id}",
        )
        return report
    }

    private fun parseActions(arr: JSONArray?): List<ExemplarAction> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            ExemplarAction(
                action = o.optString("action", "").trim(),
                rationale = o.optString("rationale", "").trim(),
            )
        }
    }

    private fun parseHighlights(arr: JSONArray?): List<ExemplarHighlight> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            ExemplarHighlight(
                quote = o.optString("quote", "").trim(),
                point = o.optString("point", "").trim(),
            )
        }
    }

    private fun parseImprovements(arr: JSONArray?): List<ExemplarImprovement> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            ExemplarImprovement(
                observation = o.optString("observation", "").trim(),
                suggestion = o.optString("suggestion", "").trim(),
            )
        }
    }

    private fun parseFollowUps(arr: JSONArray?): List<FollowUp> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { i ->
            when {
                arr.opt(i) is String -> FollowUp(arr.getString(i).trim(), id = "e$i")
                else -> {
                    val o = arr.optJSONObject(i)
                    FollowUp(o?.optString("text", "")?.trim().orEmpty(), id = "e$i")
                }
            }
        }.filter { it.text.isNotBlank() }
    }

    companion object {
        private const val TAG = "ExemplarAnalyzer"
    }
}
