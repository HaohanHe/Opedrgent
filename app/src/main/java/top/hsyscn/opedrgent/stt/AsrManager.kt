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

    private fun createEngine(): SpeechEngine {
        val sttEngine = apiSettings.getSttEngine()
        val hasKey = apiSettings.hasApiKey()
        val keyPrefix = apiSettings.getApiKey()?.take(6)
        DebugLog.i(TAG, "createEngine: sttEngine=$sttEngine, hasApiKey=$hasKey, keyPrefix=${keyPrefix}...")
        
        val useMiMo = sttEngine == "mimo" && hasKey

        return if (useMiMo) {
            DebugLog.i(TAG, "创建 MiMO ASR 引擎 (在线模式)")
            val engine = MimoAsrEngine(context, apiSettings)
            if (!engine.initialize()) {
                throw IllegalStateException("MiMO ASR 初始化失败，请检查 API Key 是否正确设置")
            }
            engine
        } else {
            if (!hasKey && sttEngine == "mimo") {
                DebugLog.w(TAG, "sttEngine=mimo 但 API Key 未设置，降级到本地引擎")
            }
            val recommendedModel = ModelManager.getRecommendedModel(context)
            val modelDir = ModelManager.getModelPath(context, recommendedModel)
            DebugLog.i(TAG, "推荐模型: $recommendedModel, 模型目录: ${modelDir?.absolutePath}, 已下载: ${modelDir != null && ModelManager.isModelDownloaded(context, recommendedModel)}")
            if (modelDir == null || !ModelManager.isModelDownloaded(context, recommendedModel)) {
                throw IllegalStateException("语音识别模型未下载，请在设置页下载模型")
            }
            DebugLog.i(TAG, "创建 Sherpa-ONNX 引擎, model=$recommendedModel, dir=${modelDir.absolutePath}")
            val engine = SherpaOnnxEngine(context, SttConfig(modelType = recommendedModel))
            if (!engine.initialize(modelDir)) {
                throw IllegalStateException("Sherpa-ONNX 引擎初始化失败")
            }
            engine
        }
    }
}
