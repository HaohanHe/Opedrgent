package top.hsyscn.opedrgent.stt

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import top.hsyscn.opedrgent.settings.ApiSettings
import top.hsyscn.opedrgent.utils.DebugLog

private fun SttResult.applyVocabulary(vocabularyStore: VocabularyStore): SttResult {
    return this.copy(
        text = vocabularyStore.applyVocabulary(this.text),
        segments = this.segments.map { seg ->
            seg.copy(text = vocabularyStore.applyVocabulary(seg.text))
        }
    )
}

/**
 * 统一 ASR 管理器 — 消除三个入口的代码重复。
 *
 * 所有 ASR 请求都通过 AsrManager 路由：
 * - 根据 ApiSettings.getSttEngine() 选择引擎
 * - 自动处理引擎初始化（懒加载）
 * - 统一的错误处理
 *
 * 使用方式：
 * 1. 文件转录：`asrManager.transcribeFile(uri)`
 * 2. 流式识别：先 `asrManager.ensureInitialized()`，再 `asrManager.startStreaming()`
 * 3. 工具调用：`asrManager.getEngine()` 获取引擎实例
 */
class AsrManager(
    private val context: Context,
    private val apiSettings: ApiSettings,
    private val forceLocal: Boolean = false,
) {
    companion object {
        private const val TAG = "AsrManager"
    }

    private val mutex = Mutex()
    private var engine: SpeechEngine? = null
    private val vocabularyStore = VocabularyStore(context)
    private val postProcessor = AsrPostProcessor()

    // ==================== 公开 API ====================

    /**
     * 转录音频/视频文件（URI）。
     * 自动根据设置选择引擎并初始化。
     */
    suspend fun transcribeFile(uri: Uri): SttResult = withContext(Dispatchers.IO) {
        val e = getOrCreateEngine()
        DebugLog.i(TAG, "transcribeFile(uri): engine=${e.engineType}, uri=$uri")
        val rawResult = e.recognizeFile(uri).applyVocabulary(vocabularyStore)

        // 后处理: 标点恢复
        val apiKey = apiSettings.getApiKey()
        val processed = postProcessor.postProcess(rawText = rawResult.text, apiKey = apiKey)

        val result = rawResult.copy(text = processed.punctuated)
        DebugLog.i(TAG, "transcribeFile(uri) 结果: text=${result.text.length}字, confidence=${result.confidence}, segments=${result.segments.size}")
        if (result.text.isEmpty()) {
            DebugLog.w(TAG, "转写结果为空(uri)! processingTime=${result.processingTimeMs}ms, model=${result.modelUsed}")
        }
        result
    }

    /**
     * 转录音频/视频文件（文件路径）。
     */
    suspend fun transcribeFile(filePath: String): SttResult = withContext(Dispatchers.IO) {
        val e = getOrCreateEngine()
        DebugLog.i(TAG, "transcribeFile(path): engine=${e.engineType}, filePath=$filePath")
        val rawResult = e.recognizeFile(filePath).applyVocabulary(vocabularyStore)
        DebugLog.i(TAG, "transcribeFile 原始结果: text=${rawResult.text.length}字, confidence=${rawResult.confidence}, segments=${rawResult.segments.size}, duration=${rawResult.durationMs}ms")

        // 后处理: 标点恢复 + 语义分段
        val apiKey = apiSettings.getApiKey()
        val processed = postProcessor.postProcess(
            rawText = rawResult.text,
            apiKey = apiKey,
        )

        // 将后处理分段映射回 SttSegment (保留原始时间信息)
        val postSegments = if (processed.segments.size > 1) {
            processed.segments.mapIndexed { idx, seg ->
                val ratioStart = seg.startTime.coerceIn(0f, 1f)
                val ratioEnd = seg.endTime.coerceIn(ratioStart, 1f)
                SttSegment(
                    text = seg.text,
                    startTimeMs = (rawResult.durationMs * ratioStart).toLong(),
                    endTimeMs = (rawResult.durationMs * ratioEnd).toLong(),
                    confidence = rawResult.confidence,
                )
            }
        } else {
            rawResult.segments
        }

        val result = rawResult.copy(
            text = processed.punctuated,
            segments = postSegments,
        )

        DebugLog.i(TAG, "transcribeFile 后处理完成: punctuated=${result.text.length}字, segments=${result.segments.size}")
        if (result.text.isEmpty()) {
            DebugLog.w(TAG, "转写结果为空! processingTime=${result.processingTimeMs}ms, model=${result.modelUsed}")
        }
        result
    }

    /**
     * 启动流式识别。
     * 调用前必须确保引擎已初始化（通过 [ensureInitialized] 或 [transcribeFile]）。
     */
    fun startStreaming(): Flow<StreamingRecognitionState> {
        val e = engine
        if (e == null || !e.isAvailable) {
            DebugLog.w(TAG, "startStreaming: 引擎未就绪，返回空流")
            return emptyFlow()
        }
        DebugLog.i(TAG, "startStreaming: engine=${e.engineType}")
        return e.startStreamingRecognition()
    }

    /**
     * 停止当前流式识别。
     */
    fun stopStreaming() {
        engine?.stopStreamingRecognition()
    }

    /**
     * 确保引擎已初始化。
     * 用于在调用 [startStreaming] 前预初始化引擎。
     */
    suspend fun ensureInitialized(): SpeechEngine {
        return getOrCreateEngine()
    }

    /**
     * 获取当前引擎实例（可能为 null）。
     * 用于 SpeechToTextTool 等需要直接访问引擎的场景。
     */
    suspend fun getEngine(): SpeechEngine = getOrCreateEngine()

    /**
     * 获取后处理器实例（用于说话人分离等高级功能）。
     */
    fun getPostProcessor(): AsrPostProcessor = postProcessor

    /**
     * 获取已缓存的引擎实例（不触发初始化）。
     * 如果引擎尚未初始化，返回 null。
     */
    fun getCachedEngine(): SpeechEngine? = engine

    /**
     * 获取当前引擎类型名称（用于 UI 显示）。
     */
    fun getCurrentEngineName(): String {
        return when (engine?.engineType) {
            EngineType.MIMO_ASR -> "MiMo ASR (在线)"
            EngineType.SHERPA_ONNX -> "Sherpa-ONNX (本地)"
            EngineType.ANDROID_SPEECH_RECOGNIZER -> "Android SpeechRecognizer"
            EngineType.STEP_AUDIO_ASR -> "StepAudio 2.5 ASR (阶跃云端)"
            else -> "未初始化"
        }
    }

    /**
     * 关闭引擎并释放资源。
     */
    fun close() {
        DebugLog.i(TAG, "关闭引擎: ${engine?.engineType}")
        engine?.close()
        engine = null
    }

    // ==================== 内部实现 ====================

    private suspend fun getOrCreateEngine(): SpeechEngine = mutex.withLock {
        val existing = engine
        if (existing != null && existing.isAvailable) {
            return@withLock existing
        }

        // 关闭旧引擎
        existing?.close()

        // 创建新引擎
        val newEngine = createEngine()
        engine = newEngine
        newEngine
    }

    /**
     * 创建 ASR 引擎实例（支持自动降级）。
     *
     * **降级策略**（P1-4 修复）：
     * 1. 优先使用用户选择的引擎（MiMO 在线 或 Sherpa-ONNX 本地）
     * 2. 如果首选引擎初始化失败，自动尝试降级到备用引擎
     * 3. 如果所有引擎都不可用，抛出包含详细原因的异常
     *
     * 降级场景：
     * - MiMO 初始化失败 → 尝试 Sherpa-ONNX 本地引擎
     * - API Key 未设置但选择了 MiMO → 自动切换到本地引擎
     * - 模型未下载 → 提示用户下载模型（不静默降级，避免意外行为）
     *
     * @return 可用的 SpeechEngine 实例
     * @throws IllegalStateException 所有引擎都不可用时抛出
     */
    private fun createEngine(): SpeechEngine {
        val sttEngine = apiSettings.getSttEngine()
        val hasKey = apiSettings.hasApiKey()
        val keyPrefix = apiSettings.getApiKey()?.take(6)
        DebugLog.i(TAG, "createEngine: sttEngine=$sttEngine, hasApiKey=$hasKey, forceLocal=$forceLocal")

        // 强制本地模式：优先 Sherpa-ONNX，本地不可用时降级到在线引擎（安全降级）
        if (forceLocal) {
            DebugLog.i(TAG, "forceLocal=true，优先使用本地引擎")
            // 先尝试本地引擎
            return try {
                createLocalEngine()
            } catch (e: Exception) {
                DebugLog.w(TAG, "本地引擎不可用 (${e.message})，安全降级到在线引擎")
                // 本地不可用且有 API Key → 降级到在线
                if (hasKey) {
                    try {
                        val mimoEngine = MimoAsrEngine(context, apiSettings)
                        if (mimoEngine.initialize()) {
                            DebugLog.i(TAG, "降级到 MiMO 在线引擎成功")
                            return mimoEngine
                        }
                    } catch (ex: Exception) {
                        DebugLog.w(TAG, "MiMO 降级也失败: ${ex.message}")
                    }
                }
                // 全部不可用
                val errorMsg = buildString {
                    append("所有语音识别引擎均不可用：\n")
                    append("1. Sherpa-ONNX 本地引擎: ${e.message}\n")
                    append("2. MiMO 在线引擎: ${if (hasKey) "有 Key 但初始化失败" else "无 API Key"}\n")
                    append("\n建议操作：在设置页下载本地识别模型 (PARAFORMER)")
                }
                throw IllegalStateException(errorMsg)
            }
        }

        // 场景1：用户选择 StepAudio 且有 API Key → 尝试创建 StepAudio 引擎
        if (sttEngine == "stepaudio" && hasKey) {
            try {
                DebugLog.i(TAG, "尝试创建 StepAudio 2.5 ASR 引擎 (云端模式)")
                val stepAudioEngine = StepAudioAsrEngine(context, apiSettings)
                if (stepAudioEngine.initialize()) {
                    DebugLog.i(TAG, "StepAudio 2.5 ASR 引擎创建成功")
                    return stepAudioEngine
                }
                DebugLog.w(TAG, "StepAudio ASR 初始化返回 false，尝试降级到本地引擎...")
            } catch (e: Exception) {
                DebugLog.w(TAG, "StepAudio ASR 初始化异常: ${e.message}，尝试降级到本地引擎...")
            }

            // 降级：StepAudio 失败 → 尝试本地引擎
            return fallbackToLocalEngine("StepAudio ASR 初始化失败")
        }

        // 场景1b：用户选择 StepAudio 但无 API Key → 自动使用本地引擎
        if (sttEngine == "stepaudio" && !hasKey) {
            DebugLog.w(TAG, "sttEngine=stepaudio 但 API Key 未设置，自动降级到本地引擎")
            return fallbackToLocalEngine("API Key 未设置")
        }

        // 场景2：用户选择 MiMo 且有 API Key → 尝试创建 MiMO 引擎
        if (sttEngine == "mimo" && hasKey) {
            try {
                DebugLog.i(TAG, "尝试创建 MiMO ASR 引擎 (在线模式)")
                val mimoEngine = MimoAsrEngine(context, apiSettings)
                if (mimoEngine.initialize()) {
                    DebugLog.i(TAG, "MiMO ASR 引擎创建成功")
                    return mimoEngine
                }
                DebugLog.w(TAG, "MiMO ASR 初始化返回 false，尝试降级到本地引擎...")
            } catch (e: Exception) {
                DebugLog.w(TAG, "MiMO ASR 初始化异常: ${e.message}，尝试降级到本地引擎...")
            }

            // 降级：MiMO 失败 → 尝试本地引擎
            return fallbackToLocalEngine("MiMO ASR 初始化失败")
        }

        // 场景2：用户选择 MiMO 但无 API Key → 自动使用本地引擎（不报错）
        if (sttEngine == "mimo" && !hasKey) {
            DebugLog.w(TAG, "sttEngine=mimo 但 API Key 未设置，自动降级到本地引擎")
            return fallbackToLocalEngine("API Key 未设置")
        }

        // 场景3：用户选择本地引擎或默认 → 创建 Sherpa-ONNX 引擎
        return createLocalEngine()
    }

    /**
     * 降级到本地 Sherpa-ONNX 引擎。
     *
     * @param reason 降级原因（用于日志记录）
     * @return 本地引擎实例
     * @throws IllegalStateException 如果本地引擎也不可用
     */
    private fun fallbackToLocalEngine(reason: String): SpeechEngine {
        DebugLog.i(TAG, "降级到本地引擎 (原因: $reason)")
        
        return try {
            createLocalEngine()
        } catch (e: Exception) {
            // 本地引擎也失败了，抛出综合错误信息
            val errorMsg = buildString {
                append("所有语音识别引擎均不可用：\n")
                append("1. MiMO 在线引擎: $reason\n")
                append("2. Sherpa-ONNX 本地引擎: ${e.message}\n")
                append("\n建议操作：\n")
                append("- 检查网络连接和 API Key 设置\n")
                append("- 或在设置页下载本地识别模型")
            }
            DebugLog.e(TAG, errorMsg)
            throw IllegalStateException(errorMsg)
        }
    }

    /**
     * 创建本地 Sherpa-ONNX 引擎。
     * 按优先级遍历所有已下载的模型，找到第一个可用的。
     *
     * @return 本地引擎实例
     * @throws IllegalStateException 如果没有任何模型已下载
     */
    private fun createLocalEngine(): SpeechEngine {
        // 优先使用用户选择的模型
        val selectedModel = apiSettings.getSelectedLocalModel()
        val modelTypeList = if (selectedModel.isNotBlank()) {
            try {
                val selected = ModelType.valueOf(selectedModel)
                val others = ModelManager.AVAILABLE_MODELS.map { it.type }.filter { it != selected }
                listOf(selected) + others
            } catch (_: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }

        // 如果没有选中模型，按默认优先级
        val allModels = if (modelTypeList.isNotEmpty()) {
            modelTypeList
        } else {
            val recommended = ModelManager.getRecommendedModel(context)
            listOf(recommended) + ModelManager.AVAILABLE_MODELS
                .map { it.type }
                .filter { it != recommended }
        }

        for (modelType in allModels) {
            val modelDir = ModelManager.getModelPath(context, modelType)
            val downloaded = modelDir != null && ModelManager.isModelDownloaded(context, modelType)

            DebugLog.i(TAG, "尝试模型 $modelType: dir=${modelDir?.absolutePath}, 已下载=$downloaded")

            if (!downloaded || modelDir == null) continue

            val engine = SherpaOnnxEngine(context, SttConfig(modelType = modelType))
            if (engine.initialize(modelDir)) {
                DebugLog.i(TAG, "Sherpa-ONNX 引擎创建成功 (model=$modelType)")
                return engine
            } else {
                DebugLog.w(TAG, "模型 $modelType 初始化失败，尝试下一个")
            }
        }

        // 所有模型都失败了
        val availableModels = ModelManager.AVAILABLE_MODELS.filter {
            ModelManager.isModelDownloaded(context, it.type)
        }.joinToString { it.modelName }

        throw IllegalStateException(
            if (availableModels.isEmpty()) {
                "语音识别模型未下载。\n请在设置页下载模型后重试。"
            } else {
                "已下载的模型均初始化失败。\n已下载: $availableModels"
            }
        )
    }
}
