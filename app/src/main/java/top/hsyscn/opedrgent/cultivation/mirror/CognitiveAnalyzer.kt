package top.hsyscn.opedrgent.cultivation.mirror

import org.json.JSONArray
import org.json.JSONObject
import top.hsyscn.opedrgent.cultivation.model.FeedbackMode
import top.hsyscn.opedrgent.cultivation.model.FollowUp
import top.hsyscn.opedrgent.cultivation.model.PatternNote
import top.hsyscn.opedrgent.cultivation.model.StrengthNote
import top.hsyscn.opedrgent.cultivation.model.MirrorIssue
import top.hsyscn.opedrgent.cultivation.model.MirrorReport
import top.hsyscn.opedrgent.cultivation.model.MirrorRoute
import top.hsyscn.opedrgent.utils.DebugLog

/**
 * 认知修炼镜分析器：组装提示、调用统一后端、把轻量 JSON 协议解析为 [MirrorReport]。
 *
 * 与 [MirrorAnalyzer] 同构，但产物报告承载 lens=COGNITIVE：
 * - 认知 issue 不对照理想人格基准（baselineRef 恒空）；
 * - 协议字段 alternativePerspective 映射到通用 [MirrorIssue.alternative]（供反讨好质量门核验“是否给了替代视角”）；
 * - 协议字段 referenceName 映射到 [MirrorIssue.referenceName]（仅当模型确信契合某参考偏差时非空）。
 *
 * 复用同一存储信封 [top.hsyscn.opedrgent.cultivation.store.ReflectionRecord.critique] 承载本报告，
 * 复用同一套反讨好质量门 [AntiSycophancyGuard]（逐字引用核验 + 替代视角非空）。
 */
class CognitiveAnalyzer {

    /**
     * 生成并解析一份认知反思报告。
     * @throws MirrorParseException 模型未按协议返回可解析 JSON 时
     */
    suspend fun analyze(
        backend: MirrorLlmBackend,
        transcript: String,
        sessionId: String,
        transcriptId: String,
        mode: FeedbackMode = FeedbackMode.STANDARD,
        historyHint: String? = null,
    ): MirrorReport {
        val system = CognitivePromptBuilder.systemPrompt(mode)
        val user = CognitivePromptBuilder.userPrompt(transcript, historyHint)
        val raw = backend.complete(system, user)
        return parse(raw, sessionId, transcriptId, mode, backend)
    }

    /** 从原始文本解析结构化报告；可见性 internal 以便护栏与单测复用。 */
    internal fun parse(
        raw: String,
        sessionId: String,
        transcriptId: String,
        mode: FeedbackMode,
        backend: MirrorLlmBackend,
    ): MirrorReport {
        val jsonText = CognitivePromptBuilder.extractJsonObject(raw)
        val root = runCatching { JSONObject(jsonText) }
            .getOrElse { throw MirrorParseException("认知镜返回不是有效 JSON: ${it.message}") }

        val route = MirrorRoute.fromName(root.optString("route"))
        val issues = parseIssues(root.optJSONArray("issues"))
        val report = MirrorReport(
            sessionId = sessionId,
            transcriptId = transcriptId,
            route = route,
            overall = root.optString("overall", ""),
            issues = if (route == MirrorRoute.ANALYZE) issues else emptyList(),
            nextStep = root.optString("nextStep", ""),
            support = root.optString("support", ""),
            helpResources = root.optString("helpResources", ""),
            strengths = if (route == MirrorRoute.ANALYZE) parseStrengths(root.optJSONArray("strengths")) else emptyList(),
            patterns = parsePatterns(root.optJSONArray("patterns")),
            followUps = parseFollowUps(root.optJSONArray("followUps")),
            mode = mode,
            modelUsed = backend.modelLabel,
            backend = backend.id,
            createdAt = System.currentTimeMillis(),
            rawResponse = raw,
        )
        DebugLog.i(TAG, "认知镜解析完成 route=$route issues=${report.issues.size} backend=${backend.id}")
        return report
    }

    private fun parseIssues(arr: JSONArray?): List<MirrorIssue> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            MirrorIssue(
                quote = o.optString("quote", "").trim(),
                baselineRef = "", // 认知镜不对照理想人格基准
                impact = o.optString("impact", "").trim(),
                alternative = o.optString("alternativePerspective", "").trim(),
                dimension = o.optString("dimension", "").trim(),
                referenceName = o.optString("referenceName", "").trim(),
            )
        }
    }

    private fun parseStrengths(arr: JSONArray?): List<StrengthNote> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            StrengthNote(
                quote = o.optString("quote", "").trim(),
                trait = o.optString("trait", "").trim(),
                note = o.optString("note", "").trim(),
            )
        }
    }

    private fun parsePatterns(arr: JSONArray?): List<PatternNote> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            when {
                arr.opt(i) is String -> PatternNote(arr.getString(i).trim())
                else -> {
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val refs = o.optJSONArray("refs")?.let { ra ->
                        (0 until ra.length()).map { ra.optString(it).trim() }.filter { it.isNotBlank() }
                    } ?: emptyList()
                    PatternNote(o.optString("pattern", "").trim(), o.optString("note", "").trim(), refs)
                }
            }
        }.filter { it.pattern.isNotBlank() }
    }

    private fun parseFollowUps(arr: JSONArray?): List<FollowUp> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { i ->
            when {
                arr.opt(i) is String -> FollowUp(arr.getString(i).trim(), id = "f$i")
                else -> {
                    val o = arr.optJSONObject(i)
                    FollowUp(o?.optString("text", "")?.trim().orEmpty(), id = "f$i")
                }
            }
        }.filter { it.text.isNotBlank() }
    }

    companion object {
        private const val TAG = "CognitiveAnalyzer"
    }
}
