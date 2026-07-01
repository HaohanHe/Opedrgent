package top.hsyscn.opedrgent.llm

import android.content.Context
import android.graphics.Bitmap
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Capabilities
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.cancellation.CancellationException as KtCancellationException

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

data class LlmInferenceConfig(
    val backend: Backend = Backend.CPU(),
    val maxContextLength: Int = 4096,
    val maxTokens: Int = 1024,
    val temperature: Float = 0.7f,
    val topK: Int = 64,
    val topP: Float = 0.95f,
    val enableThinking: Boolean = false,
    val enableSpeculativeDecoding: Boolean = false,
    val supportsImage: Boolean = false,
    val supportsAudio: Boolean = false,
)

class LocalLlmEngine private constructor(private val context: Context) {

    var state: LocalLlmState = LocalLlmState.Uninitialized
        private set

    private var engine: Engine? = null
    private var conversation: Any? = null
    private var lastSessionId: String? = null
    private var cachedConfig: ConversationConfig? = null
    var currentConfig: LlmInferenceConfig? = null
        private set

    val isReady: Boolean get() = state is LocalLlmState.Ready && conversation != null
    val currentModelId: String? get() = (state as? LocalLlmState.Ready)?.modelName

    @OptIn(ExperimentalApi::class)
    suspend fun loadModel(
        modelPath: String,
        modelInfo: LocalModelInfo,
        config: LlmInferenceConfig = LlmInferenceConfig(),
        tools: List<ToolProvider> = emptyList(),
        systemInstruction: Contents? = null,
    ): Boolean {
        state = LocalLlmState.Loading

        return try {
            withContext(Dispatchers.Default) {
                DebugLog.i(TAG, "Loading model: ${modelInfo.displayName} from $modelPath")

                val file = File(modelPath)
                if (!file.exists()) {
                    throw IllegalArgumentException("Model file not found: $modelPath")
                }
                val fileSizeMb = file.length() / (1024.0 * 1024.0)
                val expectedMb = modelInfo.sizeMb.toDouble()
                DebugLog.i(TAG, "Model file size: ${String.format("%.1f", fileSizeMb)}MB, expected: ${expectedMb}MB")

                if (file.length() < modelInfo.sizeMb * 1024 * 1024 * 0.9) {
                    throw IllegalArgumentException(
                        "Model file incomplete: expected ~${modelInfo.sizeMb}MB, actual ${String.format("%.1f", fileSizeMb)}MB"
                    )
                }

                val runtime = Runtime.getRuntime()
                val freeMemMb = runtime.freeMemory() / (1024.0 * 1024.0)
                val requiredMb = modelInfo.minMemoryMb.takeIf { it > 0 } ?: (modelInfo.sizeMb * 0.8).toLong()
                DebugLog.i(TAG, "Memory check: ${String.format("%.0f", freeMemMb)}MB available, ${requiredMb}MB required for ${modelInfo.displayName}")

                if (freeMemMb < requiredMb) {
                    throw IllegalArgumentException(
                        "Insufficient memory: ${String.format("%.0f", freeMemMb)}MB available, " +
                        "~${requiredMb}MB required for ${modelInfo.displayName}. " +
                        "Close other apps or use a smaller model."
                    )
                }

                val maxMemMb = runtime.maxMemory() / (1024.0 * 1024.0)
                val totalMemMb = runtime.totalMemory() / (1024.0 * 1024.0)
                DebugLog.i(TAG, "Memory: free=${String.format("%.0f", freeMemMb)}MB, max=${String.format("%.0f", maxMemMb)}MB, total=${String.format("%.0f", totalMemMb)}MB")

                unload()

                Engine.setNativeMinLogSeverity(LogSeverity.ERROR)

                val selectedBackend = if (config.backend is Backend.GPU) {
                    try {
                        DebugLog.i(TAG, "Attempting GPU backend...")
                        Backend.GPU()
                    } catch (e: Exception) {
                        DebugLog.w(TAG, "GPU not available, falling back to CPU: ${e.message}")
                        Backend.CPU()
                    }
                } else {
                    config.backend
                }
                val backendLabel = when (selectedBackend) {
                    is Backend.GPU -> "GPU"
                    is Backend.CPU -> "CPU"
                    else -> "CPU"
                }
                DebugLog.i(TAG, "Using $backendLabel backend")

                var supportsSpecDec = false
                try {
                    Capabilities(modelPath).use { caps ->
                        supportsSpecDec = caps.hasSpeculativeDecodingSupport()
                    }
                } catch (e: Exception) {
                    DebugLog.w(TAG, "Capabilities check failed: ${e.message}")
                }

                val engineConfig = EngineConfig(
                    modelPath = modelPath,
                    backend = selectedBackend,
                    visionBackend = if (config.supportsImage) Backend.GPU() else null,
                    audioBackend = if (config.supportsAudio) Backend.CPU() else null,
                    maxNumTokens = config.maxTokens,
                    cacheDir = context.cacheDir.path,
                )

                DebugLog.i(TAG, "Creating Engine with $backendLabel backend, maxTokens=${config.maxTokens}, image=${config.supportsImage}, audio=${config.supportsAudio}, tools=${tools.size}...")

                if (config.enableSpeculativeDecoding && supportsSpecDec) {
                    ExperimentalFlags.enableSpeculativeDecoding = true
                    DebugLog.i(TAG, "Speculative decoding enabled")
                }
                engine = Engine(engineConfig)
                ExperimentalFlags.enableSpeculativeDecoding = false

                DebugLog.i(TAG, "Initializing Engine (this may take up to 10 seconds)...")
                val initStart = System.currentTimeMillis()
                engine!!.initialize()
                val initMs = System.currentTimeMillis() - initStart
                DebugLog.i(TAG, "Engine initialized in ${initMs}ms")

                val effectiveSystemInstruction = systemInstruction ?: run {
                    val defaultPrompt = if (config.enableThinking) {
                        "You are a helpful assistant running locally on an Android device. " +
                        "You can use thinking mode for complex reasoning. Be concise and helpful."
                    } else {
                        "You are a helpful assistant running locally on an Android device. Be concise and helpful."
                    }
                    Contents.of(defaultPrompt)
                }

                ExperimentalFlags.enableConversationConstrainedDecoding = true
                val convConfig = ConversationConfig(
                    samplerConfig = SamplerConfig(
                        topK = config.topK,
                        topP = config.topP.toDouble(),
                        temperature = config.temperature.toDouble(),
                    ),
                    systemInstruction = effectiveSystemInstruction,
                    tools = tools,
                )
                ExperimentalFlags.enableConversationConstrainedDecoding = false

                DebugLog.i(TAG, "Creating conversation...")
                conversation = engine!!.createConversation(convConfig)
                cachedConfig = convConfig
                lastSessionId = null

                state = LocalLlmState.Ready(
                    modelName = modelInfo.id,
                    modelPath = modelPath
                )
                currentConfig = config

                DebugLog.i(TAG, "Model loaded successfully: ${modelInfo.displayName} (${String.format("%.1f", fileSizeMb)}MB)")
                true
            }
        } catch (e: CancellationException) {
            state = LocalLlmState.Error("Loading cancelled")
            DebugLog.w(TAG, "Model loading cancelled")
            false
        } catch (e: Exception) {
            val errorMsg = e.message ?: "Unknown error"
            val causeMsg = e.cause?.message?.let { " (cause: $it)" } ?: ""
            DebugLog.e(TAG, "Failed to load model: $errorMsg$causeMsg", e)
            e.stackTrace.take(8).forEach { trace ->
                DebugLog.e(TAG, "    at $trace")
            }
            state = LocalLlmState.Error(errorMsg)
            false
        }
    }

    suspend fun generateStream(
        sessionId: String,
        prompt: String,
        images: List<Bitmap> = emptyList(),
        audioClips: List<ByteArray> = emptyList(),
        enableThinking: Boolean = false,
        onDelta: (String) -> Unit,
        onThinkingDelta: ((String) -> Unit)? = null,
        onComplete: () -> Unit,
        onError: (String) -> Unit,
    ) {
        if (!isReady) {
            onError("Engine not ready (state=$state)")
            return
        }

        if (lastSessionId != null && lastSessionId != sessionId) {
            DebugLog.i(TAG, "Session changed ($lastSessionId → $sessionId), resetting conversation")
            resetConversation()
        }
        lastSessionId = sessionId

        try {
            val startTime = System.currentTimeMillis()

            withContext(Dispatchers.IO) {
                @Suppress("UNCHECKED_CAST")
                val conv = conversation as com.google.ai.edge.litertlm.Conversation

                val contents = buildContents(prompt, images, audioClips)
                val extraContext = if (enableThinking) mapOf("enable_thinking" to "true") else emptyMap()

                suspendCancellableCoroutine { continuation ->
                    conv.sendMessageAsync(
                        contents,
                        object : MessageCallback {
                            override fun onMessage(message: Message) {
                                val text = message.toString()
                                if (text.isNotEmpty() && !text.startsWith("<ctrl")) {
                                    onDelta(text)
                                }
                                val thinking = message.channels["thought"]
                                if (!thinking.isNullOrEmpty() && onThinkingDelta != null) {
                                    onThinkingDelta(thinking)
                                }
                            }

                            override fun onDone() {
                                val latency = System.currentTimeMillis() - startTime
                                DebugLog.i(TAG, "Stream completed in ${latency}ms")
                                onComplete()
                                continuation.resume(Unit)
                            }

                            override fun onError(throwable: Throwable) {
                                if (throwable is KtCancellationException || throwable is java.util.concurrent.CancellationException) {
                                    DebugLog.i(TAG, "The inference was cancelled.")
                                    onComplete()
                                    continuation.resume(Unit)
                                } else {
                                    DebugLog.e(TAG, "Stream error: ${throwable.message}", throwable)
                                    onError(throwable.message ?: "Unknown error")
                                    if (continuation.isActive) continuation.resume(Unit)
                                }
                            }
                        },
                        extraContext,
                    )
                }
            }
        } catch (e: CancellationException) {
            onError("Cancelled")
        } catch (e: Exception) {
            DebugLog.e(TAG, "Stream error: ${e.message}", e)
            onError(e.message ?: "Unknown error")
        }
    }

    fun cancelProcess() {
        try {
            @Suppress("UNCHECKED_CAST")
            val conv = conversation as? com.google.ai.edge.litertlm.Conversation
            conv?.cancelProcess()
            DebugLog.i(TAG, "Process cancelled")
        } catch (e: Exception) {
            DebugLog.w(TAG, "Error cancelling process: ${e.message}")
        }
    }

    suspend fun generate(
        prompt: String,
        images: List<Bitmap> = emptyList(),
        audioClips: List<ByteArray> = emptyList(),
    ): LocalLlmResponse {
        if (!isReady) {
            return LocalLlmResponse(text = "[Error] Engine not ready (state=$state)", latencyMs = 0)
        }

        return try {
            val startTime = System.currentTimeMillis()
            val responseText = StringBuilder()

            withContext(Dispatchers.IO) {
                @Suppress("UNCHECKED_CAST")
                val conv = conversation as com.google.ai.edge.litertlm.Conversation

                val contents = buildContents(prompt, images, audioClips)
                conv.sendMessageAsync(contents).collect { message ->
                    responseText.append(message.toString())
                }
            }

            val latency = System.currentTimeMillis() - startTime
            val text = responseText.toString()

            DebugLog.i(TAG, "Generate completed: ${text.length} chars in ${latency}ms")

            LocalLlmResponse(text = text, latencyMs = latency)
        } catch (e: CancellationException) {
            LocalLlmResponse(text = "[Cancelled]", latencyMs = 0)
        } catch (e: Exception) {
            DebugLog.e(TAG, "Generate error: ${e.message}", e)
            LocalLlmResponse(text = "[Error] ${e.message}", latencyMs = 0)
        }
    }

    fun generateStreamFlow(
        prompt: String,
        images: List<Bitmap> = emptyList(),
        audioClips: List<ByteArray> = emptyList(),
    ): Flow<String> = flow {
        if (!isReady) {
            emit("[Error] Engine not ready")
            return@flow
        }

        try {
            @Suppress("UNCHECKED_CAST")
            val conv = conversation as com.google.ai.edge.litertlm.Conversation

            val contents = buildContents(prompt, images, audioClips)
            conv.sendMessageAsync(contents).collect { message ->
                val text = message.toString()
                if (text.isNotEmpty()) {
                    emit(text)
                }
            }
        } catch (e: Exception) {
            DebugLog.e(TAG, "StreamFlow error: ${e.message}", e)
            emit("[Error] ${e.message}")
        }
    }

    private fun buildContents(
        prompt: String,
        images: List<Bitmap>,
        audioClips: List<ByteArray>,
    ): Contents {
        val contents = mutableListOf<Content>()
        for (image in images) {
            contents.add(Content.ImageBytes(bitmapToPngBytes(image)))
        }
        for (clip in audioClips) {
            contents.add(Content.AudioBytes(clip))
        }
        if (prompt.trim().isNotEmpty()) {
            contents.add(Content.Text(prompt))
        }
        DebugLog.d(TAG, "buildContents: ${images.size} images, ${audioClips.size} audio, text=${prompt.take(80)}...")
        return Contents.of(contents)
    }

    fun resetConversation() {
        try {
            val config = cachedConfig ?: return
            @Suppress("UNCHECKED_CAST")
            conversation = engine?.createConversation(config)
            DebugLog.i(TAG, "Conversation reset for new session")
        } catch (e: Exception) {
            DebugLog.w(TAG, "Error resetting conversation: ${e.message}")
        }
    }

    fun unload() {
        try {
            conversation = null
            engine?.close()
            engine = null
            state = LocalLlmState.Uninitialized
            currentConfig = null
            cachedConfig = null
            lastSessionId = null
            DebugLog.i(TAG, "Model unloaded, memory released")
        } catch (e: Exception) {
            DebugLog.w(TAG, "Error during unload: ${e.message}")
        }
    }

    fun getModelDir(): File {
        val externalDir = context.getExternalFilesDir(null)
        val baseDir = if (externalDir != null) {
            File(externalDir, "local_models")
        } else {
            DebugLog.w(TAG, "getExternalFilesDir returned null, falling back to filesDir")
            File(context.filesDir, "local_models")
        }
        if (!baseDir.exists()) baseDir.mkdirs()
        return baseDir
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

        @Volatile
        private var instance: LocalLlmEngine? = null

        fun getInstance(context: Context): LocalLlmEngine =
            instance ?: synchronized(this) {
                instance ?: LocalLlmEngine(context.applicationContext).also { engine ->
                    instance = engine
                    migrateOldModelPath(context.applicationContext)
                }
            }

        private fun migrateOldModelPath(context: Context) {
            val oldDir = File(context.filesDir, "local_models")
            if (!oldDir.exists() || !oldDir.isDirectory) return
            val files = oldDir.listFiles()
            if (files == null || files.isEmpty()) return

            val newDir = File(context.getExternalFilesDir(null), "local_models")
            if (!newDir.exists()) newDir.mkdirs()

            var migratedCount = 0
            for (file in files) {
                val targetFile = File(newDir, file.name)
                if (file.renameTo(targetFile)) {
                    migratedCount++
                    DebugLog.i(TAG, "Migrated model file: ${file.name} -> ${targetFile.absolutePath}")
                } else {
                    DebugLog.w(TAG, "Failed to migrate model file: ${file.name}")
                }
            }

            if (migratedCount == files.size) {
                val deleted = oldDir.delete()
                DebugLog.i(TAG, "Migration complete: $migratedCount files moved, old dir deleted=$deleted")
            } else {
                DebugLog.w(TAG, "Partial migration: $migratedCount/${files.size} files moved")
            }
        }

        fun bitmapToPngBytes(bitmap: Bitmap): ByteArray {
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 90, stream)
            return stream.toByteArray()
        }
    }
}
