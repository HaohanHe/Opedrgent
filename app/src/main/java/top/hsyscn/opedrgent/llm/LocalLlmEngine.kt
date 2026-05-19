package top.hsyscn.opedrgent.llm

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

sealed class LocalLlmState {
    object Uninitialized : LocalLlmState()
    object Loading : LocalLlmState()
    data class Ready(val modelName: String, val modelPath: String) : LocalLlmState()
    data class Error(val message: String) : LocalLlmState()
}

data class LocalLlmResponse(
    val text: String,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val latencyMs: Long = 0,
)

class LocalLlmEngine(private val context: Context) {

    var state: LocalLlmState = LocalLlmState.Uninitialized
        private set

    private var engineRef: Any? = null
    private var conversationRef: Any? = null

    val isReady: Boolean get() = state is LocalLlmState.Ready
    val currentModelId: String? get() = (state as? LocalLlmState.Ready)?.modelName

    suspend fun loadModel(modelPath: String, modelInfo: LocalModelInfo): Boolean {
        state = LocalLlmState.Loading

        return try {
            withContext(Dispatchers.Default) {
                DebugLog.i(TAG, "Loading model: ${modelInfo.displayName} from $modelPath")

                val file = File(modelPath)
                if (!file.exists()) {
                    throw IllegalArgumentException("Model file not found: $modelPath")
                }
                if (file.length() < modelInfo.sizeMb * 1024 * 1024 * 0.9) {
                    throw IllegalArgumentException("Model file incomplete: expected ~${modelInfo.sizeMb}MB")
                }

                // TODO: 替换为真实 LiteRT-LM Engine 初始化
                // 真实代码:
                // val config = EngineConfig(modelPath = modelPath, backend = Backend.CPU())
                // engineRef = Engine(config).also { it.initialize() }
                // conversationRef = engineRef!!.createConversation()

                Thread.sleep(500)

                state = LocalLlmState.Ready(
                    modelName = modelInfo.id,
                    modelPath = modelPath
                )

                DebugLog.i(TAG, "Model loaded successfully: ${modelInfo.displayName}")
                true
            }
        } catch (e: CancellationException) {
            state = LocalLlmState.Error("Loading cancelled")
            false
        } catch (e: Exception) {
            DebugLog.e(TAG, "Failed to load model: ${e.message}", e)
            state = LocalLlmState.Error(e.message ?: "Unknown error")
            false
        }
    }

    suspend fun generate(prompt: String): LocalLlmResponse {
        if (!isReady) {
            return LocalLlmResponse(text = "[Error] Engine not ready", latencyMs = 0)
        }

        return try {
            val startTime = System.currentTimeMillis()

            val responseText = withContext(Dispatchers.Default) {
                // TODO: 替换为真实 conversation.sendMessage(prompt) 调用
                "[Local Gemma 4 Response for: ${prompt.take(50)}...]"
            }

            val latency = System.currentTimeMillis() - startTime

            LocalLlmResponse(
                text = responseText,
                latencyMs = latency,
            )
        } catch (e: CancellationException) {
            LocalLlmResponse(text = "[Cancelled]", latencyMs = 0)
        } catch (e: Exception) {
            DebugLog.e(TAG, "Generate error: ${e.message}", e)
            LocalLlmResponse(text = "[Error] ${e.message}", latencyMs = 0)
        }
    }

    suspend fun generateStream(
        prompt: String,
        onDelta: (String) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit,
    ) {
        if (!isReady) {
            onError("Engine not ready")
            return
        }

        try {
            withContext(Dispatchers.Default) {
                // TODO: 替换为真实 LiteRT-LM 流式调用
                // conversation.sendStreamMessage(prompt) { chunk ->
                //     onDelta(chunk.text)
                // }

                val response = "[Local streaming response]"
                response.chunked(2).forEach { chunk ->
                    delay(30)
                    onDelta(chunk)
                }
                onComplete()
            }
        } catch (e: CancellationException) {
            onError("Cancelled")
        } catch (e: Exception) {
            DebugLog.e(TAG, "Stream error: ${e.message}", e)
            onError(e.message ?: "Unknown error")
        }
    }

    fun unload() {
        try {
            conversationRef = null
            engineRef = null
            state = LocalLlmState.Uninitialized
            DebugLog.i(TAG, "Model unloaded, memory released")
        } catch (e: Exception) {
            DebugLog.w(TAG, "Error during unload: ${e.message}")
        }
    }

    fun getModelDir(): File {
        val dir = File(context.filesDir, "local_models")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun isModelDownloaded(modelInfo: LocalModelInfo): Boolean {
        val file = File(getModelDir(), modelInfo.fileName)
        return file.exists() && file.length() > modelInfo.sizeMb * 1024 * 1024 * 0.8
    }

    fun getModelPath(modelInfo: LocalModelInfo): String? {
        val file = File(getModelDir(), modelInfo.fileName)
        return if (file.exists()) file.absolutePath else null
    }

    companion object {
        const val TAG = "LocalLlmEngine"
    }
}
