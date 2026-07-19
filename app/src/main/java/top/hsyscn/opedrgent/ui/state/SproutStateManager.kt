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

        val cacheKey = trimmedText.hashCode().toString()
        sproutCache[cacheKey]?.let { cached ->
            DebugLog.i("Sprout: 命中缓存，直接返回历史结果")
            _sproutResult.value = cached.markdownReport
            _sproutUiState.value = SproutUiState.Done(cached.markdownReport, computeSproutQualityScore(cached))
            _sproutingState.value = SproutingState.DONE
            return
        }

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
                        SproutPhase.SHOCKING_INSIGHT -> _sproutingState.value = SproutingState.PHASE3
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
                val completedPhases = _sproutUiState.value.let { (it as? SproutUiState.GeneratingReport)?.phasesCompleted ?: 0 }
                DebugLog.i("Sprout: 用户取消发芽 completedPhases=$completedPhases")
                _sproutUiState.value = SproutUiState.Cancelled(completedPhases)
                _sproutingState.value = SproutingState.IDLE
            } catch (e: Exception) {
                val failedPhase = _sproutUiState.value.let { (it as? SproutUiState.PhaseInProgress)?.phase }
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
