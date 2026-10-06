package top.hsyscn.opedrgent.cultivation.mirror

import org.json.JSONObject
import top.hsyscn.opedrgent.cultivation.model.MirrorReport
import top.hsyscn.opedrgent.cultivation.model.MirrorRoute
import top.hsyscn.opedrgent.cultivation.model.PersonaProfile
import top.hsyscn.opedrgent.utils.DebugLog

/**
 * 反讨好双保险（需求卡 N4）。
 *
 * 第 ① 层是模型自审（[selfReview]）：以第二遍批判性自我审视，排查无证据的讨好、空话与人格定性。
 * 第 ② 层是确定性核验（[verifyReport]）：代码只做两项无语义的字符串级事实检查——
 *   - 每条引用原句是否真实出现在转写中；
 *   - 每条问题是否给出了替代行动。
 * 代码侧不建任何敏感词/定性词表，不判断“这句话算不算不尊重”，价值与语义判断全部归模型。
 */
class AntiSycophancyGuard {

    /** 确定性核验结果。 */
    data class GuardResult(
        val passed: Boolean,
        val violations: List<String>,
        val checkedQuotes: Int,
        val matchedQuotes: Int,
    )

    /** 模型自审结果。 */
    data class SelfReviewResult(
        val needsRevision: Boolean,
        val reason: String,
        /** 当模型给出修订后的完整 JSON 文本时非空，可直接重新解析。 */
        val revisedRaw: String?,
    )

    /**
     * 确定性核验（纯函数、无语义、无词表）。
     */
    fun verifyReport(report: MirrorReport, transcript: String): GuardResult {
        val violations = mutableListOf<String>()
        val normalizedTranscript = normalizeForMatch(transcript)

        when (report.route) {
            MirrorRoute.ANALYZE -> {
                if (report.issues.isEmpty()) {
                    // 空问题集本身合法（确实没挑出错），由 overall 说明；此处不判失败。
                    DebugLog.i(TAG, "ANALYZE 路由 issues 为空，按“未发现问题”处理")
                }
                var matched = 0
                report.issues.forEachIndexed { idx, issue ->
                    if (issue.quote.isBlank()) {
                        violations += "第${idx + 1}条缺少逐字引用原句"
                    } else if (!containsQuote(normalizedTranscript, issue.quote)) {
                        violations += "第${idx + 1}条引用未在转写中逐字出现：${issue.quote.take(20)}"
                    } else {
                        matched++
                    }
                    if (issue.alternative.isBlank()) {
                        violations += "第${idx + 1}条缺少可执行的替代说法/动作"
                    }
                }
                report.strengths.forEachIndexed { idx, s ->
                    if (s.quote.isBlank()) {
                        violations += "第${idx + 1}条优势缺少逐字引用原句"
                    } else if (!containsQuote(normalizedTranscript, s.quote)) {
                        violations += "第${idx + 1}条优势引用未在转写中逐字出现：${s.quote.take(20)}"
                    }
                    if (s.trait.isBlank()) violations += "第${idx + 1}条优势未概括特质名"
                }
                return GuardResult(
                    passed = violations.isEmpty(),
                    violations = violations,
                    checkedQuotes = report.issues.size,
                    matchedQuotes = matched,
                )
            }

            MirrorRoute.SUPPORT -> {
                if (report.support.isBlank()) violations += "SUPPORT 路由缺少支持性回应"
                return GuardResult(violations.isEmpty(), violations, 0, 0)
            }

            MirrorRoute.CRISIS -> {
                if (report.support.isBlank()) violations += "CRISIS 路由缺少陪伴/支持性回应"
                if (report.helpResources.isBlank()) violations += "CRISIS 路由缺少求助资源引导"
                if (report.issues.isNotEmpty()) violations += "CRISIS 路由不得继续输出挑错条目"
                return GuardResult(violations.isEmpty(), violations, 0, 0)
            }
        }
    }

    /**
     * 模型自审：让同一后端以批判视角复查自己的原始输出。
     * 仅做一次；是否重做由引擎结合确定性核验结果决定。
     */
    suspend fun selfReview(
        backend: MirrorLlmBackend,
        persona: PersonaProfile?,
        transcript: String,
        originalRaw: String,
    ): SelfReviewResult {
        val system = """
            你是批判镜的自审者。请只依据转写文本，检查下面这份分析初稿是否存在：
            1) 无证据的讨好、空话、套话；2) 对人格/品性/能力的整体定性（应只对具体行为）；
            3) 引用的原句在转写中并不存在；4) 该指出问题却回避，或没有问题却硬挑错；
            5) SUPPORT/CRISIS 状态下仍在挑错。
            只输出一个 JSON：{"needsRevision": true/false, "reason": "简短理由", "revisedJson": "修订后的完整分析 JSON 字符串，无需修订时留空"}。
        """.trimIndent()
        val user = """
            【画像】
            ${MirrorPromptBuilder.userPrompt(persona, transcript).substringBefore("【用户本人语音转写】")}
            【转写】
            ${transcript.trim()}

            【待自审的分析初稿原文】
            $originalRaw
        """.trimIndent()

        return try {
            val resp = backend.complete(system, user)
            val o = JSONObject(MirrorPromptBuilder.extractJsonObject(resp))
            SelfReviewResult(
                needsRevision = o.optBoolean("needsRevision", false),
                reason = o.optString("reason", ""),
                revisedRaw = o.optString("revisedJson", "").takeIf { it.isNotBlank() },
            )
        } catch (e: Exception) {
            // 自审本身失败不应阻断主流程：交由确定性核验把关
            DebugLog.w(TAG, "模型自审失败，退回确定性核验：${e.message}")
            SelfReviewResult(false, "自审不可用：${e.message}", null)
        }
    }

    /** 去除空白与常见中英文标点，降低 ASR/模型输出在标点层面的差异导致的误判。 */
    private fun normalizeForMatch(text: String): String {
        val punct = "，。、；：？！,.;:?!“”\"'‘’（）()【】\\[\\]\\s…—-~·".toRegex()
        return punct.replace(text, "").lowercase()
    }

    private fun containsQuote(normalizedTranscript: String, quote: String): Boolean {
        val nq = normalizeForMatch(quote)
        return nq.isNotEmpty() && normalizedTranscript.contains(nq)
    }

    companion object {
        private const val TAG = "AntiSycophancyGuard"
    }
}
