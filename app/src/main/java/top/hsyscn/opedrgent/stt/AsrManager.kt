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
) {
    companion object {
        private const val TAG = "AsrManager"
    }

    private val mutex = Mutex()
    private var engine: SpeechEngine? = null

    // ==================== 公开 API ====================

    /**
     * 转录音频/视频文件（URI）。
     * 自动根据设置选择引擎并初始化。
     */
    suspend fun transcribeFile(uri: Uri): SttResult = withContext(Dispatchers.IO) {
        val e = getOrCreateEngine()
        DebugLog.i(TAG, "transcribeFile(uri): engine=${e.engineType}, uri=$uri")
        val result = e.recognizeFile(uri)
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
        val result = e.recognizeFile(filePath)
        DebugLog.i(TAG, "transcribeFile 结果: text=${result.text.length}字, confidence=${result.confidence}, segments=${result.segments.size}, duration=${result.durationMs}ms, engine=${result.engineType}")
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
        DebugLog.i(TAG, "createEngine: sttEngine=$sttEngine, hasApiKey=$hasKey, keyPrefix=${keyPrefix}...")

        // 场景1：用户选择 MiMO 且有 API Key → 尝试创建 MiMO 引擎
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
     *
     * @return 本地引擎实例
     * @throws IllegalStateException 如果模型未下载或初始化失败
     */
    private fun createLocalEngine(): SpeechEngine {
        val recommendedModel = ModelManager.getRecommendedModel(context)
        val modelDir = ModelManager.getModelPath(context, recommendedModel)
        
        DebugLog.i(
            TAG,
            "创建 Sherpa-ONNX 引擎: model=$recommendedModel, " +
            "dir=${modelDir?.absolutePath}, " +
            "已下载=${modelDir != null && ModelManager.isModelDownloaded(context, recommendedModel)}"
        )

        // 检查模型是否已下载
        if (modelDir == null || !ModelManager.isModelDownloaded(context, recommendedModel)) {
            throw IllegalStateException(
                "语音识别模型未下载（推荐模型: $recommendedModel）。\n" +
                "请在设置页下载模型后重试。\n" +
                "当前设备 RAM 可支持的模型: ${recommendedModel.name}"
            )
        }

        // 创建并初始化引擎
        val engine = SherpaOnnxEngine(context, SttConfig(modelType = recommendedModel))
        if (!engine.initialize(modelDir)) {
            throw IllegalStateException(
                "Sherpa-ONNX 引擎初始化失败（模型: $recommendedModel）。\n" +
                "可能原因：模型文件损坏、存储空间不足或设备不兼容。"
            )
        }

        DebugLog.i(TAG, "Sherpa-ONNX 引擎创建成功 (model=$recommendedModel)")
        return engine
    }
}
