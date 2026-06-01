package top.hsyscn.opedrgent.stt

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asFlow
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.File

class SherpaOnnxEngine(
    private val context: Context,
    private val config: SttConfig = SttConfig(),
) : SpeechEngine {

    private var recognizer: Any? = null
    private var isInitialized = false

    override val engineType = EngineType.SHERPA_ONNX

    override val isAvailable: Boolean
        get() = isInitialized

    override suspend fun recognizeFile(uri: Uri): SttResult {
        return SttResult(
            text = "[Sherpa-ONNX 识别结果待实现 - SDK 集成中]",
            engineType = EngineType.SHERPA_ONNX,
            modelUsed = config.modelType.name,
        )
    }

    override suspend fun recognizeFile(filePath: String): SttResult {
        return SttResult(
            text = "[Sherpa-ONNX 文件识别待实现]",
            engineType = EngineType.SHERPA_ONNX,
            modelUsed = config.modelType.name,
        )
    }

    override fun startStreamingRecognition(): Flow<StreamingRecognitionState> {
        return MutableSharedFlow<StreamingRecognitionState>().asFlow()
    }

    override fun stopStreamingRecognition() {
    }

    fun initialize(modelDir: File): Boolean {
        return try {
            DebugLog.i("SherpaOnnxEngine: 初始化模型 from ${modelDir.absolutePath}")
            isInitialized = true
            true
        } catch (e: Exception) {
            DebugLog.e("SherpaOnnxEngine: 初始化失败: ${e.message}", e)
            isInitialized = false
            false
        }
    }

    override fun close() {
        try {
            recognizer = null
            isInitialized = false
            DebugLog.i("SherpaOnnxEngine: 资源已释放")
        } catch (e: Exception) {
            DebugLog.e("SherpaOnnxEngine: 关闭时出错: ${e.message}")
        }
    }
}
