package top.hsyscn.opedrgent.cultivation.engine

import android.content.Context
import top.hsyscn.opedrgent.cultivation.mirror.AntiSycophancyGuard
import top.hsyscn.opedrgent.cultivation.mirror.AntiSycophancyGuard.GuardResult
import top.hsyscn.opedrgent.cultivation.mirror.ExemplarAnalyzer
import top.hsyscn.opedrgent.cultivation.mirror.ExemplarGuard
import top.hsyscn.opedrgent.cultivation.mirror.ExemplarPromptBuilder
import top.hsyscn.opedrgent.cultivation.mirror.LocalMirrorBackend
import top.hsyscn.opedrgent.cultivation.mirror.MirrorAnalyzer
import top.hsyscn.opedrgent.cultivation.mirror.MirrorLlmBackend
import top.hsyscn.opedrgent.cultivation.mirror.MirrorParseException
import top.hsyscn.opedrgent.cultivation.mirror.MirrorPromptBuilder
import top.hsyscn.opedrgent.cultivation.mirror.ReflectionToolProtocol
import top.hsyscn.opedrgent.cultivation.model.ExemplarReport
import top.hsyscn.opedrgent.cultivation.model.FeedbackMode
import top.hsyscn.opedrgent.cultivation.model.MirrorReport
import top.hsyscn.opedrgent.cultivation.model.ReflectionLens
import top.hsyscn.opedrgent.cultivation.model.VirtueBaseline
import top.hsyscn.opedrgent.cultivation.store.ReflectionRecord
import top.hsyscn.opedrgent.cultivation.store.ReflectionStore
import top.hsyscn.opedrgent.cultivation.store.VirtueBaselineStore
import top.hsyscn.opedrgent.storage.HippocampusIndex
import top.hsyscn.opedrgent.utils.DebugLog

/**
 * 修炼模式编排引擎（逻辑重设计 P1）。
 *
 * 定位是“模型主导的轻量会话 Loop + 确定性质量门”，不是固定分析管线：
 * - 如何组织分析、聚焦哪几点、走哪条状态路由、是否先取长期记忆，全部由模型结合语境决定；
 * - 工程只负责：取基准 →（模型可声明、最多一轮的）本地工具往返 → 调一次分析 →
 *   反讨好质量门 → 至多重做一次 → 统一落库；
 * - 两次仍不达标则明确返回未过质量门，绝不用低质反馈充数。
 *
 * 批判镜与榜样镜共用同一套工具、质量门与统一存储 [ReflectionStore]，以 [ReflectionLens] 区分；
 * 默认端侧后端，云端必须由调用方在用户显式开关后传入 RemoteMirrorBackend。
 */
class CultivationEngine(
    context: Context,
    hippocampusProvider: () -> HippocampusIndex? = { null },
) {
    private val baselineStore = VirtueBaselineStore(context)
    private val reflectionStore = ReflectionStore(context)
    private val analyzer = MirrorAnalyzer()
    private val guard = AntiSycophancyGuard()
    private val exemplarAnalyzer = ExemplarAnalyzer()
    private val exemplarGuard = ExemplarGuard()
    private val tools = ReflectionToolMediator(reflectionStore, hippocampusProvider)

    /** 一次修炼会话的统一结果；[success] 为 false 时 [record] 为空，调用方展示“未达质量门槛”。 */
    data class ReflectionOutcome(
        val success: Boolean,
        val lens: ReflectionLens,
        val record: ReflectionRecord?,
        val attempts: Int,
        val violations: List<String>,
        val persistedId: Long?,
    )

    /** 便捷构造默认端侧后端。 */
    fun localBackend(context: Context): MirrorLlmBackend = LocalMirrorBackend(context)

    // ===== 批判镜 =====

    /**
     * 对一段本人转写执行批判镜分析并统一落库。
     *
     * @param persist 是否落库，默认落；false 时只返回内存记录（与榜样镜“仅本次对照”同构）
     */
    suspend fun analyze(
        backend: MirrorLlmBackend,
        transcript: String,
        sessionId: String,
        transcriptId: String,
        mode: FeedbackMode = FeedbackMode.STANDARD,
        historyHint: String? = null,
        baseline: VirtueBaseline? = null,
        persist: Boolean = true,
    ): ReflectionOutcome {
        val effectiveBaseline = baseline ?: baselineStore.getActive()
            ?: return fail(ReflectionLens.CRITIQUE, "尚未建立理想人格基准")
        // 把此前仍 OPEN 的跟进交给模型在本次自主复评；工程只取清单，不做命中判定
        val openFollowUps = reflectionStore.openFollowUps().map { it.follow.text }

        var attempts = 0

        // 首次生成（模型可在这一轮选择先请求本地工具）
        attempts++
        var report = runCatching {
            val system = MirrorPromptBuilder.systemPrompt(mode)
            val baseUser = MirrorPromptBuilder.userPrompt(effectiveBaseline, transcript, historyHint, openFollowUps)
            toolGuidedFinal(backend, system, baseUser, transcript) { raw ->
                analyzer.parse(raw, sessionId, transcriptId, mode, backend)
            }
        }.getOrElse { e ->
            return critiqueFailRecovery(
                e, attempts, backend, effectiveBaseline, transcript,
                sessionId, transcriptId, mode, historyHint, openFollowUps, persist,
            )
        }
        var check = guard.verifyReport(report, transcript)

        // 至多重做一次
        if (!check.passed) {
            DebugLog.w(TAG, "首次未过质量门：${check.violations}")
            attempts++
            val revised = retryCritiqueOnce(
                backend, effectiveBaseline, transcript, sessionId, transcriptId,
                mode, historyHint, openFollowUps, report.rawResponse, check,
            )
            if (revised != null) {
                report = revised
                check = guard.verifyReport(report, transcript)
            }
        }

        if (!check.passed) {
            DebugLog.w(TAG, "重做后仍未过质量门，不输出低质反馈：${check.violations}")
            return ReflectionOutcome(false, ReflectionLens.CRITIQUE, null, attempts, check.violations, null)
        }
        return persistCritique(report, attempts, persist)
    }

    /** 重做一次：先模型自审，自审给修订 JSON 则重解析，否则带确定性违规清单重生成（不再走工具）。 */
    private suspend fun retryCritiqueOnce(
        backend: MirrorLlmBackend,
        baseline: VirtueBaseline,
        transcript: String,
        sessionId: String,
        transcriptId: String,
        mode: FeedbackMode,
        historyHint: String?,
        openFollowUps: List<String>,
        firstRaw: String,
        firstCheck: GuardResult,
    ): MirrorReport? {
        val review = guard.selfReview(backend, baseline, transcript, firstRaw)
        if (review.needsRevision && review.revisedRaw != null) {
            runCatching { analyzer.parse(review.revisedRaw, sessionId, transcriptId, mode, backend) }
                .getOrNull()?.let { return it }
        }
        val system = MirrorPromptBuilder.systemPrompt(mode)
        val user = buildString {
            append(MirrorPromptBuilder.userPrompt(baseline, transcript, historyHint, openFollowUps))
            appendLine()
            appendLine("你上一版输出存在以下需要修正的事实性问题，请只依据转写重新输出一份符合协议的 JSON：")
            firstCheck.violations.forEach { appendLine("- $it") }
        }
        return runCatching {
            val raw = backend.complete(system, user)
            analyzer.parse(raw, sessionId, transcriptId, mode, backend)
        }.getOrElse {
            DebugLog.w(TAG, "重做生成失败：${it.message}")
            null
        }
    }

    /** 首次即抛错（如 JSON 不可解析、端侧未就绪）时允许一次重试，仍失败则判未过门槛。 */
    private suspend fun critiqueFailRecovery(
        error: Throwable,
        attemptsIn: Int,
        backend: MirrorLlmBackend,
        baseline: VirtueBaseline,
        transcript: String,
        sessionId: String,
        transcriptId: String,
        mode: FeedbackMode,
        historyHint: String?,
        openFollowUps: List<String>,
        persist: Boolean,
    ): ReflectionOutcome {
        if (error is MirrorParseException && attemptsIn < MAX_ATTEMPTS) {
            val second = runCatching {
                val system = MirrorPromptBuilder.systemPrompt(mode)
                val baseUser = MirrorPromptBuilder.userPrompt(baseline, transcript, historyHint, openFollowUps)
                toolGuidedFinal(backend, system, baseUser, transcript) { raw ->
                    analyzer.parse(raw, sessionId, transcriptId, mode, backend)
                }
            }.getOrNull()
            if (second != null) {
                val check = guard.verifyReport(second, transcript)
                if (check.passed) return persistCritique(second, attemptsIn + 1, persist)
                return ReflectionOutcome(false, ReflectionLens.CRITIQUE, null, attemptsIn + 1, check.violations, null)
            }
        }
        return ReflectionOutcome(false, ReflectionLens.CRITIQUE, null, attemptsIn, listOf("分析失败：${error.message}"), null)
    }

    private suspend fun persistCritique(report: MirrorReport, attempts: Int, persist: Boolean): ReflectionOutcome {
        val record = ReflectionRecord(
            lens = ReflectionLens.CRITIQUE,
            critique = report,
            createdAt = report.createdAt,
        )
        val id = if (persist) reflectionStore.insert(record) else null
        return ReflectionOutcome(true, ReflectionLens.CRITIQUE, record.copy(id = id ?: 0), attempts, emptyList(), id)
    }

    // ===== 榜样镜 =====

    /**
     * 榜样镜：以指定榜样的一贯之道对照本人转写。P1 起与批判镜统一落库，可在历史中回看；
     * [retain]=false 表示“仅本次对照不留存”，只返回内存记录、不写库。
     */
    suspend fun reflectWithExemplar(
        backend: MirrorLlmBackend,
        exemplar: String,
        whyExemplar: String?,
        transcript: String,
        mode: FeedbackMode = FeedbackMode.STANDARD,
        historyHint: String? = null,
        retain: Boolean = true,
    ): ReflectionOutcome {
        val target = exemplar.trim()
        if (target.isBlank()) return fail(ReflectionLens.EXEMPLAR, "尚未指定对标的榜样")
        if (transcript.isBlank()) return fail(ReflectionLens.EXEMPLAR, "转写内容为空")

        var attempts = 0
        attempts++
        var report = runCatching {
            val system = ExemplarPromptBuilder.systemPrompt(target, whyExemplar, mode)
            val baseUser = ExemplarPromptBuilder.userPrompt(transcript, historyHint)
            toolGuidedFinal(backend, system, baseUser, transcript) { raw ->
                exemplarAnalyzer.parse(raw, target, mode, backend)
            }
        }.getOrElse { e ->
            DebugLog.w(TAG, "榜样镜首次生成失败：${e.message}")
            return ReflectionOutcome(false, ReflectionLens.EXEMPLAR, null, attempts, listOf("榜样镜分析失败：${e.message}"), null)
        }
        var check = exemplarGuard.verify(report, transcript)

        if (!check.passed) {
            DebugLog.w(TAG, "榜样镜首次未过质量门：${check.violations}")
            attempts++
            val system = ExemplarPromptBuilder.systemPrompt(target, whyExemplar, mode)
            val user = buildString {
                append(ExemplarPromptBuilder.userPrompt(transcript, historyHint))
                appendLine()
                appendLine("你上一版输出存在以下需要修正的事实性问题，请只依据转写重新输出一份符合协议的 JSON：")
                check.violations.forEach { appendLine("- $it") }
            }
            runCatching {
                val raw = backend.complete(system, user)
                exemplarAnalyzer.parse(raw, target, mode, backend)
            }.onSuccess {
                report = it
                check = exemplarGuard.verify(it, transcript)
            }
        }

        if (!check.passed) {
            DebugLog.w(TAG, "榜样镜重做后仍未过质量门，不输出低质结果：${check.violations}")
            return ReflectionOutcome(false, ReflectionLens.EXEMPLAR, null, attempts, check.violations, null)
        }
        val record = ReflectionRecord(
            lens = ReflectionLens.EXEMPLAR,
            exemplar = report,
            createdAt = report.createdAt,
        )
        val id = if (retain) reflectionStore.insert(record) else null
        return ReflectionOutcome(true, ReflectionLens.EXEMPLAR, record.copy(id = id ?: 0), attempts, emptyList(), id)
    }

    // ===== 有界工具 Loop =====

    /**
     * 模型主导的本地工具往返（最多一轮，端侧小模型友好）：
     *  1. 首轮输出若为合法工具请求，工程在本机执行并回填，再要求模型直接出最终报告；
     *  2. 首轮若直接是最终报告，则等价于一次生成（兜底，绝不比旧行为差）；
     *  3. 若拿到工具结果后模型仍继续请求工具，强制其立即输出最终报告，保证步数有界。
     *
     * @param parseFinal 把最终 JSON 文本解析为目标报告，解析失败抛 [MirrorParseException]
     */
    private suspend fun <T> toolGuidedFinal(
        backend: MirrorLlmBackend,
        system: String,
        baseUser: String,
        transcript: String,
        parseFinal: (String) -> T,
    ): T {
        var user = baseUser
        var raw = backend.complete(system, user)
        val firstCalls = ReflectionToolProtocol.parseToolCalls(raw)
        if (firstCalls != null) {
            val toolResult = tools.execute(firstCalls, transcript)
            user = buildString {
                append(baseUser)
                appendLine()
                appendLine("【本地工具返回，仅供你识别长期模式，不要逐条复述】")
                append(toolResult)
                appendLine()
                appendLine("请据此直接输出最终报告 JSON，不要再请求工具。")
            }
            raw = backend.complete(system, user)
            if (ReflectionToolProtocol.parseToolCalls(raw) != null) {
                DebugLog.w(TAG, "模型在拿到工具结果后仍请求工具，强制输出最终报告")
                raw = backend.complete(
                    system,
                    user + "\n请立即输出最终报告 JSON，禁止再输出 toolCalls。",
                )
            }
        }
        return parseFinal(raw)
    }

    private fun fail(lens: ReflectionLens, reason: String): ReflectionOutcome =
        ReflectionOutcome(false, lens, null, 0, listOf(reason), null)

    // 对外暴露存储操作，便于上层 UI/用例直接复用，不再另写一套
    suspend fun activeBaseline(): VirtueBaseline? = baselineStore.getActive()
    suspend fun saveBaseline(baseline: VirtueBaseline): Long = baselineStore.saveAsActive(baseline)

    /** 统一会话存储；保留 reports() 命名以减少上层改动。 */
    fun reflections(): ReflectionStore = reflectionStore
    fun reports(): ReflectionStore = reflectionStore
    fun baselines(): VirtueBaselineStore = baselineStore

    companion object {
        private const val TAG = "CultivationEngine"
        private const val MAX_ATTEMPTS = 2
    }
}
