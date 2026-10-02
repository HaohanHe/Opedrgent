package top.hsyscn.opedrgent.cultivation.mirror

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import top.hsyscn.opedrgent.cultivation.model.FeedbackMode
import top.hsyscn.opedrgent.cultivation.model.FollowUp
import top.hsyscn.opedrgent.cultivation.model.PatternNote
import top.hsyscn.opedrgent.cultivation.model.StrengthNote
import top.hsyscn.opedrgent.cultivation.model.MirrorIssue
import top.hsyscn.opedrgent.cultivation.model.MirrorReport
import top.hsyscn.opedrgent.cultivation.model.MirrorRoute
import top.hsyscn.opedrgent.cultivation.model.VirtueBaseline
import top.hsyscn.opedrgent.llm.LocalLlmEngine
import top.hsyscn.opedrgent.model.ChatMessage
import top.hsyscn.opedrgent.model.Role
import top.hsyscn.opedrgent.network.LlmClient
import top.hsyscn.opedrgent.settings.ApiConfig
import top.hsyscn.opedrgent.utils.DebugLog

/** 分析输出无法解析为约定 JSON 时抛出，交由引擎决定是否重做，而不是用低质结果充数。 */
class MirrorParseException(message: String) : Exception(message)

/**
 * 统一的补全后端：默认端侧，云端必须由用户显式开关后才构造 [RemoteMirrorBackend]。
 */
interface MirrorLlmBackend {
    /** 后端标识，落库用，例如 local / remote。 */
    val id: String
    /** 模型标签，落库与排查用。 */
    val modelLabel: String
    suspend fun complete(system: String, user: String): String
}

/**
 * 端侧后端：走 LiteRT-LM 本地引擎，全程不出设备。
 * 端侧系统指令在加载模型时绑定，这里把系统原则与用户内容合并为单次提示。
 */
class LocalMirrorBackend(context: Context) : MirrorLlmBackend {
    private val engine = LocalLlmEngine.getInstance(context)
    override val id: String = "local"
    override val modelLabel: String get() = engine.currentModelId ?: "local-llm"

    override suspend fun complete(system: String, user: String): String {
        check(engine.isReady) { "端侧模型未就绪，请先在设置中下载并加载模型" }
        val merged = "$system\n\n$user"
        return engine.generate(merged).text
    }
}

/**
 * 云端后端：仅当用户显式选择云端时使用，复用全局 OpenAI 兼容客户端。
 */
class RemoteMirrorBackend(
    private val config: ApiConfig,
    private val client: LlmClient = LlmClient(),
) : MirrorLlmBackend {
    override val id: String = "remote"
    override val modelLabel: String = config.model

    override suspend fun complete(system: String, user: String): String = withContext(Dispatchers.IO) {
        client.chatCompletions(
            config = config,
            system = system,
            messages = listOf(ChatMessage(role = Role.USER, content = user)),
        )
    }
}

/**
 * 批判镜分析器：组装提示、调用后端、把轻量 JSON 协议解析为 [MirrorReport]。
 * 不写死分析步骤，只负责一次“生成 + 结构化解析”。
 */
class MirrorAnalyzer {

    /**
     * 生成并解析一份报告。
     * @throws MirrorParseException 模型未按协议返回可解析 JSON 时
     */
    suspend fun analyze(
        backend: MirrorLlmBackend,
        baseline: VirtueBaseline,
        transcript: String,
        sessionId: String,
        transcriptId: String,
        mode: FeedbackMode = FeedbackMode.STANDARD,
        historyHint: String? = null,
    ): MirrorReport {
        val system = MirrorPromptBuilder.systemPrompt(mode)
        val user = MirrorPromptBuilder.userPrompt(baseline, transcript, historyHint)
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
        val jsonText = MirrorPromptBuilder.extractJsonObject(raw)
        val root = runCatching { JSONObject(jsonText) }
            .getOrElse { throw MirrorParseException("返回不是有效 JSON: ${it.message}") }

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
        DebugLog.i(TAG, "解析完成 route=$route issues=${report.issues.size} backend=${backend.id}")
        return report
    }

    private fun parseIssues(arr: JSONArray?): List<MirrorIssue> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            MirrorIssue(
                quote = o.optString("quote", "").trim(),
                baselineRef = o.optString("baselineRef", "").trim(),
                impact = o.optString("impact", "").trim(),
                alternative = o.optString("alternative", "").trim(),
                dimension = o.optString("dimension", "").trim(),
                // 认知镜会填 referenceName；言行镜不产出该字段，读出恒为空，不影响既有行为。
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
        private const val TAG = "MirrorAnalyzer"
    }
}
