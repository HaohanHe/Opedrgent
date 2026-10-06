@file:Suppress("DEPRECATION")

package top.hsyscn.opedrgent.ui.state

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import top.hsyscn.opedrgent.R
import top.hsyscn.opedrgent.insight.InsightSproutEngine
import top.hsyscn.opedrgent.insight.SproutConfig
import top.hsyscn.opedrgent.insight.SproutPhase
import top.hsyscn.opedrgent.insight.SproutResult
import top.hsyscn.opedrgent.model.ChatMessage
import top.hsyscn.opedrgent.model.Role
import top.hsyscn.opedrgent.network.LlmClient
import top.hsyscn.opedrgent.settings.ApiSettings
import top.hsyscn.opedrgent.storage.HippocampusIndex
import top.hsyscn.opedrgent.storage.SproutReportRecord
import top.hsyscn.opedrgent.storage.SproutReportStore
import top.hsyscn.opedrgent.ui.SproutingState
import top.hsyscn.opedrgent.ui.SproutUiState
import top.hsyscn.opedrgent.utils.DebugLog

/**
 * 知识发芽（Insight Sprout）状态管理器。
 *
 * 封装发芽流程的状态机、缓存、持久化以及与海马体索引的同步。
 */
class SproutStateManager(
    private val app: Application,
    private val apiSettings: ApiSettings,
    private val coroutineScope: CoroutineScope,
    private val hippocampus: HippocampusIndex?,
    private val sproutReportStore: SproutReportStore,
) {

    private val _sproutingState = MutableStateFlow<SproutingState>(SproutingState.IDLE)
    val sproutingState: StateFlow<SproutingState> = _sproutingState.asStateFlow()

    private val _sproutUiState = MutableStateFlow<SproutUiState>(SproutUiState.Idle)
    val sproutUiState: StateFlow<SproutUiState> = _sproutUiState.asStateFlow()

    private val _sproutResult = MutableStateFlow<String?>(null)
    val sproutResult: StateFlow<String?> = _sproutResult.asStateFlow()

    private val _sproutHistory = MutableStateFlow<List<SproutResult>>(emptyList())
    val sproutHistory: StateFlow<List<SproutResult>> = _sproutHistory.asStateFlow()

    private val sproutCache = mutableMapOf<String, SproutResult>()
    private var sproutJob: Job? = null
    /** 发芽代次：每次 triggerSprout 自增；旧协程的 Cancelled/Error 终态写入仅在代次仍匹配时生效，
     *  避免取消后旧协程在新协程已置 AnalyzingInput 后再写终态覆盖（U29-4）。 */
    private var sproutRunId = 0

    /**
     * 触发一次知识发芽。
     */
    fun triggerSprout(text: String, config: SproutConfig? = null) {
        val trimmedText = text.trim()
        if (trimmedText.isBlank()) {
            _sproutUiState.value = SproutUiState.Error(app.getString(R.string.sprout_error_empty))
            _sproutingState.value = SproutingState.ERROR
            _sproutResult.value = app.getString(R.string.sprout_error_empty_detail)
            return
        }

        if (trimmedText.length < 10) {
            _sproutUiState.value = SproutUiState.Error(app.getString(R.string.sprout_error_too_short))
            _sproutingState.value = SproutingState.ERROR
            return
        }

        // 缓存键用 SHA-256 前 16 位 hex：String.hashCode 仅 32 位且不同文本会碰撞，
        // 会把 A 文本的发芽结果错配给 B 文本（U29-12）。摘要失败再退化为长度+hashCode。
        val cacheKey = try {
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(trimmedText.toByteArray())
                .joinToString("") { "%02x".format(it) }
                .take(16)
        } catch (e: Exception) {
            "f" + trimmedText.hashCode().toString() + "_" + trimmedText.length
        }
        sproutCache[cacheKey]?.let { cached ->
            DebugLog.i("Sprout: 命中缓存，直接返回历史结果")
            _sproutResult.value = cached.markdownReport
            _sproutUiState.value = SproutUiState.Done(cached.markdownReport, computeSproutQualityScore(cached))
            _sproutingState.value = SproutingState.DONE
            return
        }

        val myRun = ++sproutRunId
        sproutJob?.cancel()
        _sproutingState.value = SproutingState.IDLE
        _sproutResult.value = null
        _sproutUiState.value = SproutUiState.Idle

        val keywordPreview = trimmedText.take(80).replace("\n", " ") + if (trimmedText.length > 80) "..." else ""
        DebugLog.i("Sprout: 开始发芽 inputLength=${trimmedText.length} preview=$keywordPreview")

        sproutJob = coroutineScope.launch {
            try {
                val effectiveConfig = config ?: SproutConfig()
                _sproutUiState.value = SproutUiState.AnalyzingInput(keywordPreview)
                delay(200)

                val engine = InsightSproutEngine(
                    llmCall = { prompt: String ->
                        val apiConfig = apiSettings.getApiConfig()
                            ?: throw IllegalStateException(app.getString(R.string.sprout_error_no_api_key))
                        LlmClient().chatCompletions(
                            config = apiConfig,
                            system = app.getString(R.string.sprout_system_prompt),
                            messages = listOf(
                                ChatMessage(
                                    role = Role.USER,
                                    content = prompt,
                                    createdAt = System.currentTimeMillis(),
                                )
                            ),
                        )
                    },
                )

                _sproutUiState.value = SproutUiState.GeneratingReport(0, 4)

                val result = engine.sprout(trimmedText, effectiveConfig)

                for ((i, phase) in result.completedPhases.withIndex()) {
                    _sproutUiState.value = SproutUiState.GeneratingReport(i + 1, result.completedPhases.size)
                    when (phase) {
                        SproutPhase.SEED_EXTRACTION -> _sproutingState.value = SproutingState.PHASE1
                        SproutPhase.CROSS_DOMAIN -> _sproutingState.value = SproutingState.PHASE2
                        SproutPhase.WEB_ENHANCE -> _sproutingState.value = SproutingState.PHASE2
                        SproutPhase.CORE_INSIGHT -> _sproutingState.value = SproutingState.PHASE3
                        SproutPhase.QUOTE_RESONANCE -> _sproutingState.value = SproutingState.PHASE4
                    }
                }

                val qualityScore = computeSproutQualityScore(result)
                _sproutResult.value = result.markdownReport
                _sproutUiState.value = SproutUiState.Done(result.markdownReport, qualityScore)
                _sproutingState.value = SproutingState.DONE

                sproutCache[cacheKey] = result
                _sproutHistory.value = listOf(result) + _sproutHistory.value.take(49)

                val sproutTitle = trimmedText.take(50).replace("\n", " ")
                hippocampus?.upsertSprout(cacheKey, sproutTitle, result.markdownReport)

                try {
                    sproutReportStore.insert(
                        SproutReportRecord(
                            sourceNoteId = 0,
                            sourceTitle = sproutTitle,
                            markdownReport = result.markdownReport,
                            summary = result.seeds.joinToString("; ") { "${it.concept}: ${it.description.take(100)}" },
                            modelUsed = "insight-engine",
                            createdAt = System.currentTimeMillis(),
                            wordCount = result.markdownReport.length,
                        )
                    )
                } catch (_: Exception) { /* persistence failure is non-critical */ }

                DebugLog.i("Sprout: 发芽完成 phases=${result.completedPhases.size}/4 quality=$qualityScore time=${result.processingTimeMs}ms seeds=${result.seeds.size} insights=${result.insights.size}")
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 已被新一轮 triggerSprout 取代：不再写终态，避免覆盖新协程已置的 AnalyzingInput（U29-4）
                if (sproutRunId != myRun) return@launch
                val completedPhases = _sproutUiState.value.let { (it as? SproutUiState.GeneratingReport)?.phasesCompleted ?: 0 }
                DebugLog.i("Sprout: 用户取消发芽 completedPhases=$completedPhases")
                _sproutUiState.value = SproutUiState.Cancelled(completedPhases)
                _sproutingState.value = SproutingState.IDLE
            } catch (e: Exception) {
                // 已被新一轮取代：同样不写终态
                if (sproutRunId != myRun) return@launch
                // 实际进行态是 AnalyzingInput / GeneratingReport（均为 SproutUiState 直接子类，
                // 不继承 PhaseInProgress），按真实类型反推失败阶段（U29-5）。
                val failedPhase = when (val st = _sproutUiState.value) {
                    is SproutUiState.GeneratingReport -> {
                        val idx = st.phasesCompleted.coerceAtMost(SproutPhase.entries.size - 1)
                        SproutPhase.entries.getOrElse(idx) { SproutPhase.SEED_EXTRACTION }
                    }
                    is SproutUiState.AnalyzingInput -> SproutPhase.SEED_EXTRACTION
                    else -> null
                }
                DebugLog.e("Sprout: 发芽异常 [${failedPhase?.name ?: "UNKNOWN"}] ${e.message}", e)
                _sproutUiState.value = SproutUiState.Error(
                    app.getString(R.string.sprout_error_processing, e.message ?: ""),
                    failedPhase,
                )
                _sproutingState.value = SproutingState.ERROR
                _sproutResult.value = app.getString(R.string.sprout_error_processing, e.message ?: "")
            }
        }
    }

    /**
     * 设置错误状态（用于上下文相关的校验失败）。
     */
    fun setError(message: String) {
        _sproutUiState.value = SproutUiState.Error(message)
        _sproutingState.value = SproutingState.ERROR
    }

    /**
     * 清除发芽结果并重置为空闲状态。
     */
    fun dismissResult() {
        _sproutResult.value = null
        _sproutingState.value = SproutingState.IDLE
        _sproutUiState.value = SproutUiState.Idle
    }

    /**
     * 取消当前发芽任务。
     */
    fun cancelSprouting() {
        sproutJob?.cancel()
        sproutJob = null
        val currentState = _sproutUiState.value
        if (currentState !is SproutUiState.Done && currentState !is SproutUiState.Error && currentState !is SproutUiState.Cancelled) {
            _sproutingState.value = SproutingState.IDLE
            _sproutUiState.value = SproutUiState.Idle
        }
    }

    /**
     * 清空缓存。
     */
    fun clearCache() {
        sproutCache.clear()
    }

    private fun computeSproutQualityScore(result: SproutResult): Int {
        var score = 50
        score += (result.completedPhases.size * 10).coerceAtMost(40)
        score += (result.seeds.size * 3).coerceAtMost(15)
        score += (result.insights.size * 5).coerceAtMost(15)
        score += (result.quotes.size * 2).coerceAtMost(10)
        if (result.markdownReport.length > 500) score += 5
        if (result.connections.isNotEmpty()) score += 5
        return score.coerceIn(0, 100)
    }
}
