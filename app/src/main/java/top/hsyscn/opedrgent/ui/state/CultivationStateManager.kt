package top.hsyscn.opedrgent.ui.state

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.hsyscn.opedrgent.cultivation.engine.CultivationEngine
import top.hsyscn.opedrgent.cultivation.mirror.MirrorRuntime
import top.hsyscn.opedrgent.cultivation.model.ReflectionLens
import top.hsyscn.opedrgent.cultivation.model.FeedbackMode
import top.hsyscn.opedrgent.cultivation.model.ExemplarReport
import top.hsyscn.opedrgent.cultivation.model.IssueMark
import top.hsyscn.opedrgent.cultivation.model.FollowUpStatus
import top.hsyscn.opedrgent.cultivation.model.MirrorReport
import top.hsyscn.opedrgent.cultivation.model.VirtueBaseline
import top.hsyscn.opedrgent.cultivation.model.VirtueDimension
import top.hsyscn.opedrgent.cultivation.model.BaselineTemplates
import top.hsyscn.opedrgent.cultivation.model.ReflectionInsights
import top.hsyscn.opedrgent.cultivation.store.ReflectionRecord
import top.hsyscn.opedrgent.storage.HippocampusIndex
import top.hsyscn.opedrgent.storage.IndexedItem
import top.hsyscn.opedrgent.storage.SourceType
import top.hsyscn.opedrgent.settings.ApiSettings
import top.hsyscn.opedrgent.utils.DebugLog
import java.util.UUID

/**
 * 个人修炼（批判镜）状态管理器，对齐 SproutStateManager 范式：
 * 单一 UiState + viewModelScope 挂起任务，不持有任何 Compose 依赖。
 *
 * 后端选择：默认端侧（全本地）；云端开关默认关闭，只有用户在页面显式打开、且已配置 API Key 时才走云端。
 */
class CultivationStateManager(
    private val app: Application,
    private val apiSettings: ApiSettings,
    private val coroutineScope: CoroutineScope,
    private val hippocampusProvider: () -> HippocampusIndex?,
) {
    private val engine = CultivationEngine(app, hippocampusProvider)
    private val runtime = MirrorRuntime(app, apiSettings)

    data class CultivationUiState(
        val loading: Boolean = true,
        val localReady: Boolean = false,
        val localModelId: String? = null,
        val useCloud: Boolean = false,
        val mode: FeedbackMode = FeedbackMode.STANDARD,
        /** 当前选择的反思透镜：CRITIQUE=言行批判，COGNITIVE=认知修炼；analyze() 据此分发。 */
        val lens: ReflectionLens = ReflectionLens.CRITIQUE,
        val transcript: String = "",
        val phase: ReflectionPhase = ReflectionPhase.Idle,
        val activeBaseline: VirtueBaseline? = null,
        val editingDimensions: List<VirtueDimension> = emptyList(),
        val baselineDirty: Boolean = false,
        val result: ReflectionRecord? = null,
        val history: List<ReflectionRecord> = emptyList(),
        val error: String? = null,
        val info: String? = null,
        val exemplarWhy: String = "",
        val exemplarNames: List<String> = emptyList(),
        val exemplarResults: List<ReflectionRecord> = emptyList(),
        val retainExemplar: Boolean = true,
    ) {
        /** 任一面镜子处于准备/推理/质量门阶段都算忙：忙时两面镜子入口都禁用，杜绝并发。 */
        val isBusy: Boolean
            get() = phase is ReflectionPhase.Reflecting ||
                phase is ReflectionPhase.QualityGate ||
                phase is ReflectionPhase.Preparing

        /** 指定镜子是否处于进度态（用于只在对应按钮上转圈）。 */
        fun progressOn(lens: ReflectionLens): Boolean = when (val p = phase) {
            is ReflectionPhase.Reflecting -> p.lens == lens
            is ReflectionPhase.QualityGate -> p.lens == lens
            else -> false
        }

        /** 指定镜子是否被质量门/前置条件挡住，返回挡住状态供结果区逐条展示原因。 */
        fun blockedOn(lens: ReflectionLens): ReflectionPhase.Blocked? =
            (phase as? ReflectionPhase.Blocked)?.takeIf { it.lens == lens }

        /** 成长全景：随 history 自动重算的确定性聚合，不额外占状态。 */
        val insights: ReflectionInsights get() = ReflectionInsights.from(history)
    }

    private val _state = MutableStateFlow(CultivationUiState())
    val state: StateFlow<CultivationUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    /** 加载活跃基准、历史轨迹与端侧模型就绪状态。 */
    fun refresh() {
        coroutineScope.launch {
            val baseline = runCatching { engine.activeBaseline() }.getOrNull()
            val history = runCatching { engine.reports().listRecent() }.getOrDefault(emptyList())
            _state.update {
                it.copy(
                    loading = false,
                    activeBaseline = baseline,
                    editingDimensions = if (it.baselineDirty) it.editingDimensions
                        else baseline?.dimensions ?: BaselineTemplates.STARTER,
                    history = history,
                    localReady = runtime.localReady(),
                    localModelId = runtime.localModelId(),
                )
            }
        }
    }

    fun setTranscript(text: String) = _state.update { it.copy(transcript = text) }

    /** 供录音等外部链路把转写文本带入复盘页。 */
    fun loadTranscript(text: String) = _state.update {
        it.copy(
            transcript = text,
            result = null,
            exemplarResults = emptyList(),
            phase = ReflectionPhase.Idle,
        )
    }

    fun setMode(mode: FeedbackMode) = _state.update { it.copy(mode = mode) }

    /** 切换本次反思使用的透镜（言行批判 / 认知修炼）；不触发分析，仅选择。 */
    fun setLens(lens: ReflectionLens) = _state.update { it.copy(lens = lens) }

    fun setExemplarWhy(why: String) = _state.update { it.copy(exemplarWhy = why) }

    /** 添加一位对标榜样：去空白，空名或已存在则忽略。 */
    fun addExemplar(name: String) = _state.update {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed in it.exemplarNames) return@update it
        it.copy(exemplarNames = it.exemplarNames + trimmed)
    }

    /** 移除一位对标榜样。 */
    fun removeExemplar(name: String) = _state.update {
        it.copy(exemplarNames = it.exemplarNames.filterNot { n -> n == name })
    }

    /** 榜样镜是否留存到本地历史；false=仅本次对照，会话记录不写库。 */
    fun setRetainExemplar(retain: Boolean) = _state.update { it.copy(retainExemplar = retain) }

    /** 云端必须用户显式打开；这里只翻转开关，真正联网发生在点击分析时。 */
    fun setUseCloud(useCloud: Boolean) {
        if (useCloud && apiSettings.getApiConfig() == null) {
            _state.update { it.copy(error = "尚未配置云端 API，将保持端侧本地分析；可先在设置中配置。") }
            return
        }
        _state.update { it.copy(useCloud = useCloud) }
    }

    // ===== 基准编辑 =====

    fun startBaselineEdit() = _state.update {
        it.copy(editingDimensions = it.activeBaseline?.dimensions ?: BaselineTemplates.STARTER, baselineDirty = true)
    }

    /**
     * 把起始模板 BaselineTemplates.STARTER 中指定下标的维度载入编辑区（仅脚手架，不是判定词表）。
     * @param selectedIndex STARTER 的 0..3 下标；越界者忽略
     * @param replace true=整体替换编辑区；false=按维度名（忽略大小写）去重后追加
     */
    fun loadStarterDimensions(selectedIndex: List<Int>, replace: Boolean) = _state.update {
        val picked = selectedIndex.mapNotNull { idx -> BaselineTemplates.STARTER.getOrNull(idx) }
        if (picked.isEmpty()) return@update it
        val existing = it.editingDimensions.mapTo(HashSet()) { d -> d.name.trim().lowercase() }
        val next = if (replace) picked
            else it.editingDimensions + picked.filterNot { d -> d.name.trim().lowercase() in existing }
        it.copy(editingDimensions = next, baselineDirty = true)
    }

    fun addDimension() = _state.update {
        it.copy(
            baselineDirty = true,
            editingDimensions = it.editingDimensions + VirtueDimension("新维度", emptyList(), emptyList()),
        )
    }

    fun removeDimension(index: Int) = _state.update {
        it.copy(baselineDirty = true, editingDimensions = it.editingDimensions.filterIndexed { i, _ -> i != index })
    }

    fun setDimensionName(index: Int, name: String) = _state.update {
        it.copy(baselineDirty = true, editingDimensions = it.editingDimensions.mapIndexed { i, d ->
            if (i == index) d.copy(name = name) else d
        })
    }

    fun setDimensionDo(index: Int, raw: String) = _state.update {
        it.copy(baselineDirty = true, editingDimensions = it.editingDimensions.mapIndexed { i, d ->
            if (i == index) d.copy(doBehaviors = splitBehaviors(raw)) else d
        })
    }

    fun setDimensionDont(index: Int, raw: String) = _state.update {
        it.copy(baselineDirty = true, editingDimensions = it.editingDimensions.mapIndexed { i, d ->
            if (i == index) d.copy(dontBehaviors = splitBehaviors(raw)) else d
        })
    }

    fun saveBaseline() {
        val dims = _state.value.editingDimensions.map { d ->
            d.copy(name = d.name.trim()).let { c ->
                c.copy(doBehaviors = c.doBehaviors.map { s -> s.trim() }.filter { s -> s.isNotBlank() },
                    dontBehaviors = c.dontBehaviors.map { s -> s.trim() }.filter { s -> s.isNotBlank() })
            }
        }.filter { it.name.isNotBlank() }
        if (dims.isEmpty()) {
            _state.update { it.copy(error = "请至少填写一个有效维度名称") }
            return
        }
        coroutineScope.launch {
            val now = System.currentTimeMillis()
            val baseline = VirtueBaseline(
                id = 0,
                version = 0,
                dimensions = dims,
                complete = true,
                createdAt = now,
                updatedAt = now,
            )
            runCatching { engine.saveBaseline(baseline) }
                .onSuccess { _state.update { it.copy(baselineDirty = false, info = "理想人格基准已保存（本地）") } }
                .onFailure { e -> _state.update { it.copy(error = "保存失败：${e.message}") } }
            refresh()
        }
    }

    // ===== 分析 =====

    fun analyze() {
        val current = _state.value
        val transcript = current.transcript.trim()
        if (transcript.isBlank()) {
            _state.update { it.copy(error = "请先粘贴或录入本人语音转写文本") }
            return
        }
        if (current.isBusy) return
        val backend = when (val resolution = runtime.resolve(current.useCloud)) {
            is MirrorRuntime.Resolution.Ready -> resolution.backend
            is MirrorRuntime.Resolution.Unavailable -> {
                _state.update { it.copy(error = resolution.reason) }
                return
            }
        }

        _state.update {
            it.copy(
                phase = ReflectionPhase.Reflecting(current.lens),
                error = null,
                info = null,
                result = null,
            )
        }
        coroutineScope.launch {
            try {
                val sessionId = MANUAL_SESSION
                val transcriptId = UUID.randomUUID().toString()
                val lens = current.lens
                val outcome = when (lens) {
                    ReflectionLens.COGNITIVE -> engine.reflectCognitive(
                        backend = backend,
                        transcript = transcript,
                        sessionId = sessionId,
                        transcriptId = transcriptId,
                        mode = current.mode,
                        historyHint = buildHistoryHint(transcript),
                    )
                    else -> engine.analyze(
                        backend = backend,
                        transcript = transcript,
                        sessionId = sessionId,
                        transcriptId = transcriptId,
                        mode = current.mode,
                        historyHint = buildHistoryHint(transcript),
                    )
                }
                val history = runCatching { engine.reports().listRecent() }.getOrDefault(emptyList())
                if (outcome.success && outcome.record != null) {
                    outcome.record.critique?.let { rpt ->
                        outcome.persistedId?.let { rid -> indexCultivation(rid, rpt) }
                    }
                    _state.update {
                        it.copy(
                            phase = ReflectionPhase.Idle,
                            result = outcome.record,
                            history = history,
                            info = if (outcome.attempts > 1) "首次结果未过自检，已重做后通过" else null,
                        )
                    }
                } else {
                    _state.update {
                        it.copy(
                            phase = ReflectionPhase.Blocked(
                                lens,
                                outcome.violations,
                                outcome.attempts,
                            ),
                            history = history,
                            error = if (lens == ReflectionLens.COGNITIVE)
                                "本次认知反思未达到质量门槛，未展示低质结果。可调整转写后重试。"
                            else
                                "本次分析未达到质量门槛，未展示低质反馈。可补充基准或调整转写后重试。",
                        )
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                DebugLog.e("Cultivation", "分析失败", e)
                _state.update { it.copy(phase = ReflectionPhase.Idle, error = "分析失败：${e.message}") }
            }
        }
    }

    fun analyzeExemplar() {
        val current = _state.value
        val names = current.exemplarNames.map { it.trim() }.filter { it.isNotBlank() }
        if (names.isEmpty()) {
            _state.update { it.copy(error = "请至少添加一位对标的榜样") }
            return
        }
        val transcript = current.transcript.trim()
        if (transcript.isBlank()) {
            _state.update { it.copy(error = "请先粘贴或录入本人语音转写文本（可与批判镜共用同一段）") }
            return
        }
        if (current.isBusy) return
        val backend = when (val resolution = runtime.resolve(current.useCloud)) {
            is MirrorRuntime.Resolution.Ready -> resolution.backend
            is MirrorRuntime.Resolution.Unavailable -> {
                _state.update { it.copy(error = resolution.reason) }
                return
            }
        }

        _state.update {
            it.copy(
                phase = ReflectionPhase.Reflecting(ReflectionLens.EXEMPLAR),
                error = null,
                info = null,
                exemplarResults = emptyList(),
            )
        }
        coroutineScope.launch {
            try {
                // 多榜样同情境：对每位榜样逐人独立走完整质量门与落库，成败互不影响；
                // 历史提示只构建一次并逐人复用，保持上下文一致。
                val historyHint = buildHistoryHint(transcript)
                val succeeded = mutableListOf<ReflectionRecord>()
                val failures = mutableListOf<String>()
                for (name in names) {
                    val outcome = engine.reflectWithExemplar(
                        backend = backend,
                        exemplar = name,
                        whyExemplar = current.exemplarWhy.ifBlank { null },
                        transcript = transcript,
                        mode = current.mode,
                        historyHint = historyHint,
                        retain = current.retainExemplar,
                    )
                    if (outcome.success && outcome.record != null) {
                        outcome.record.exemplar?.let { rpt ->
                            outcome.persistedId?.let { rid -> indexExemplar(rid, rpt) }
                        }
                        succeeded.add(outcome.record)
                    } else {
                        outcome.violations.forEach { v -> failures += "【$name】$v" }
                    }
                    // 每次调用后刷新历史，使后续榜样可看到刚落库的记录。
                    val refreshed = runCatching { engine.reports().listRecent() }.getOrDefault(emptyList())
                    _state.update { it.copy(history = refreshed) }
                }
                val history = runCatching { engine.reports().listRecent() }.getOrDefault(emptyList())
                if (succeeded.isNotEmpty()) {
                    _state.update {
                        it.copy(
                            phase = ReflectionPhase.Idle,
                            exemplarResults = succeeded,
                            history = history,
                            info = if (failures.isNotEmpty())
                                "已完成 ${succeeded.size}/${names.size} 位榜样，其余未过质量门槛" else null,
                        )
                    }
                } else {
                    _state.update {
                        it.copy(
                            phase = ReflectionPhase.Blocked(ReflectionLens.EXEMPLAR, failures, 0),
                            history = history,
                            error = "榜样镜本次未达到质量门槛，未展示低质结果。可调整转写，或换一位信息更充分的榜样后重试。",
                        )
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                DebugLog.e("Cultivation", "榜样镜分析失败", e)
                _state.update { it.copy(phase = ReflectionPhase.Idle, error = "榜样镜分析失败：${e.message}") }
            }
        }
    }

    fun markIssue(recordId: Long, issueIndex: Int, mark: IssueMark) {
        coroutineScope.launch {
            runCatching { engine.reports().updateMark(recordId, issueIndex, mark) }
            val history = runCatching { engine.reports().listRecent() }.getOrDefault(emptyList())
            val updated = history.firstOrNull { it.id == recordId }
            _state.update { it.copy(history = history, result = if (it.result?.id == recordId) updated else it.result) }
        }
    }

    /** 切换某条跟进的状态并即时回显；写库失败不阻断浏览。 */
    fun setFollowUpStatus(recordId: Long, followUpId: String, status: FollowUpStatus) {
        coroutineScope.launch {
            runCatching { engine.reports().updateFollowUpStatus(recordId, followUpId, status) }
            val history = runCatching { engine.reports().listRecent() }.getOrDefault(emptyList())
            val refreshed = history.firstOrNull { it.id == recordId }
            _state.update {
                it.copy(
                    history = history,
                    result = if (it.result?.id == recordId) refreshed else it.result,
                    exemplarResults = if (it.exemplarResults.any { r -> r.id == recordId })
                        it.exemplarResults.map { r -> if (r.id == recordId) refreshed ?: r else r }
                    else it.exemplarResults,
                )
            }
        }
    }

    fun deleteReport(recordId: Long) {
        coroutineScope.launch {
            runCatching { engine.reports().delete(recordId) }
            runCatching { hippocampusProvider()?.deleteCultivation(recordId) }
            val history = runCatching { engine.reports().listRecent() }.getOrDefault(emptyList())
            _state.update { it.copy(history = history, result = if (it.result?.id == recordId) null else it.result) }
        }
    }

    fun clearAll() {
        coroutineScope.launch {
            runCatching { engine.reports().clearAll() }
            runCatching { hippocampusProvider()?.deleteAllCultivation() }
            _state.update { it.copy(history = emptyList(), result = null, exemplarResults = emptyList(), info = "本地修炼记录已清除") }
        }
    }

    fun consumeMessage() = _state.update { it.copy(error = null, info = null) }

    // ===== 内部工具 =====

    /**
     * 长期线索：(a) 最近几次自我复盘结论；(b) 海马中其它来源（录音/对话/笔记/偏好）与本次表达相关的长期背景。
     * 海马只用于识别反复出现的模式，任何失败都不阻断分析。
     */
    private suspend fun buildHistoryHint(transcript: String): String? {
        val sb = StringBuilder()
        val recent = _state.value.history.filter { it.lens == ReflectionLens.CRITIQUE }.take(5)
        if (recent.isNotEmpty()) {
            sb.appendLine("你最近几次自我复盘的结论：")
            recent.forEach { r ->
                val line = r.critique?.overall?.take(120).orEmpty()
                if (line.isNotBlank()) sb.appendLine("- $line")
            }
        }
        runCatching { appendCrossMemoryHint(sb, transcript) }
        return sb.toString().takeIf { it.isNotBlank() }
    }

    /** 从海马跨来源检索与本次转写相关的长期记忆，排除修炼自身以免与 (a) 重复。 */
    private suspend fun appendCrossMemoryHint(sb: StringBuilder, transcript: String) {
        val hip = hippocampusProvider() ?: return
        val tokens = hip.extractKeywords("", transcript)
            .split(",").filter { it.isNotBlank() }.take(6)
        if (tokens.isEmpty()) return
        val picked = LinkedHashMap<String, IndexedItem>()
        for (tk in tokens) {
            hip.query(tk, limit = 3).forEach { item ->
                if (item.sourceType != SourceType.CULTIVATION && item.id !in picked) picked[item.id] = item
            }
            if (picked.size >= 5) break
        }
        if (picked.isEmpty()) return
        sb.appendLine("与本次表达相关的长期背景（来自你以往的录音、对话、笔记等，只用于识别反复出现的模式，不要逐条复述）：")
        picked.values.take(5).forEach { item ->
            val brief = item.summary.replace("\n", " ").take(80)
            sb.appendLine("- 【${item.sourceType.label}】${item.title}：$brief")
        }
    }

    /** 批判镜报告落库后回流到全局海马索引，使其它模块也能 recall 到长期修炼模式；失败不阻断主流程。 */
    private suspend fun indexCultivation(reportId: Long, report: MirrorReport) {
        val hip = hippocampusProvider() ?: return
        runCatching {
            val summary = buildString {
                appendLine(report.overall)
                report.issues.take(3).forEach { i ->
                    if (i.quote.isNotBlank()) appendLine("原句：${i.quote}")
                    if (i.alternative.isNotBlank()) appendLine("替代说法：${i.alternative}")
                }
                if (report.nextStep.isNotBlank()) appendLine("下一步：${report.nextStep}")
            }
            hip.upsertCultivation(reportId, summary)
        }.onFailure { DebugLog.w("Cultivation", "写入海马索引失败：${it.message}") }
    }

    /** 榜样镜报告落库后同样回流海马，使长期模式可跨透镜检索；仅本次对照（未落库）时不回流。 */
    private suspend fun indexExemplar(reportId: Long, report: ExemplarReport) {
        val hip = hippocampusProvider() ?: return
        runCatching {
            val summary = buildString {
                appendLine("以${report.exemplar}为镜：${report.situation}")
                report.highlights.take(2).forEach { h ->
                    if (h.quote.isNotBlank()) appendLine("亮点原句：${h.quote}")
                }
                report.actions.take(3).forEach { a ->
                    if (a.action.isNotBlank()) appendLine("他会怎么做：${a.action}")
                }
                if (report.takeaway.isNotBlank()) appendLine("心法：${report.takeaway}")
            }
            hip.upsertCultivation(reportId, summary)
        }.onFailure { DebugLog.w("Cultivation", "写入榜样镜海马索引失败：${it.message}") }
    }

    private fun splitBehaviors(raw: String): List<String> =
        raw.split('\n', '；', ';', '，', ',').map { it.trim() }.filter { it.isNotBlank() }

    companion object {
        private const val MANUAL_SESSION = "cultivation-self"
    }
}
