package top.hsyscn.opedrgent.stt

import android.app.ActivityManager
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.File
import java.util.concurrent.TimeUnit

object ModelManager {

    private const val TAG = "ModelManager"

    /** 下载超时：连续 N 毫秒无进度则切换源 */
    private const val STALL_TIMEOUT_MS = 5_000L

    /** ModelScope 国内直连 base */
    private const val MODELSCOPE_BASE = "https://www.modelscope.cn/models"

    /** 下载源类型 */
    enum class DownloadSource(val label: String) {
        MODELSCOPE("ModelScope"),
        HUGGINGFACE("HuggingFace"),
        GITHUB("GitHub"),
    }

    data class ModelInfo(
        val type: ModelType,
        val modelName: String,
        val version: String,
        val sizeBytes: Long,
        val minRamMB: Int,
        /** 首选下载源 */
        val primarySource: DownloadSource = DownloadSource.MODELSCOPE,
        /** 仓库路径（根据 source 类型解释） */
        val repoPath: String,
        /** 需要下载的文件列表 (远程文件名 to 本地文件名) */
        val files: List<Pair<String, String>>,
    ) {
        /**
         * 生成下载任务列表。
         *
         * - MODELSCOPE: https://www.modelscope.cn/models/{repoPath}/resolve/master/{file}
         * - HUGGINGFACE: https://hf-mirror.com/{repoPath}/resolve/main/{file}
         * - GITHUB: https://github.com/{repoPath}/releases/download/asr-models/{file}
         */
        fun downloadTasks(): List<Triple<String, String, String>> {
            val baseUrl = when (primarySource) {
                DownloadSource.MODELSCOPE -> "$MODELSCOPE_BASE/$repoPath/resolve/master"
                DownloadSource.HUGGINGFACE -> "https://hf-mirror.com/$repoPath/resolve/main"
                DownloadSource.GITHUB -> "https://github.com/$repoPath/releases/download/asr-models"
            }
            return files.map { (remoteName, localName) ->
                Triple(primarySource.label, "$baseUrl/$remoteName", localName)
            }
        }

        /** GitHub 超时时的 ModelScope 备用下载地址 */
        fun fallbackTasks(): List<Triple<String, String, String>>? {
            if (primarySource == DownloadSource.MODELSCOPE) return null
            // ModelScope 备用：单文件仓库 pengzhendong/sherpa-onnx-streaming-paraformer-bilingual-zh-en
            val fallbackRepo = when (type) {
                ModelType.STREAMING_PARAFORMER -> "pengzhendong/sherpa-onnx-streaming-paraformer-bilingual-zh-en"
                else -> return null
            }
            val baseUrl = "$MODELSCOPE_BASE/$fallbackRepo/resolve/master"
            return files.map { (remoteName, localName) ->
                // ModelScope 单文件仓库的文件名不带子目录前缀
                val msRemoteName = remoteName.substringAfterLast("/")
                Triple("ModelScope(备用)", "$baseUrl/$msRemoteName", localName)
            }
        }
    }

    val AVAILABLE_MODELS = listOf(
        ModelInfo(
            type = ModelType.SENSE_VOICE_SMALL,
            modelName = "sherpa-onnx-sense-voice-zh",
            version = "2024-07-17",
            sizeBytes = 228 * 1024 * 1024L,   // model.int8.onnx ~228MB (INT8量化, sherpa-onnx官方导出含完整元数据)
            minRamMB = 4 * 1024,
            primarySource = DownloadSource.MODELSCOPE,
            repoPath = "gomodels/sherpa",
            files = listOf(
                "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/model.int8.onnx" to "model.onnx",
                "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/tokens.txt" to "tokens.txt",
            ),
        ),
        ModelInfo(
            type = ModelType.PARAFORMER,
            modelName = "sherpa-onnx-paraformer-zh",
            version = "2024-03-09",
            sizeBytes = 110 * 1024 * 1024L,  // model.int8.onnx ~110MB
            minRamMB = 6 * 1024,
            primarySource = DownloadSource.MODELSCOPE,
            repoPath = "csukuangfj/sherpa-onnx-paraformer-zh-2024-03-09",
            files = listOf(
                "model.int8.onnx" to "model.onnx",
                "tokens.txt" to "tokens.txt",
            ),
        ),
        ModelInfo(
            type = ModelType.FUNASR_NANO_INT8,
            modelName = "sherpa-onnx-funasr-nano-int8",
            version = "2025-12-01",
            sizeBytes = 20 * 1024 * 1024L,
            minRamMB = 0,
            primarySource = DownloadSource.MODELSCOPE,
            repoPath = "csukuangfj/sherpa-onnx-funasr-nano-int8",
            files = listOf(
                "model.int8.onnx" to "model.onnx",
                "tokens.txt" to "tokens.txt",
            ),
        ),
        ModelInfo(
            type = ModelType.STREAMING_PARAFORMER,
            modelName = "sherpa-onnx-streaming-paraformer-bilingual-zh-en",
            version = "2024-08-14",
            sizeBytes = 237 * 1024 * 1024L,  // encoder.int8 165MB + decoder.int8 72MB
            minRamMB = 6 * 1024,
            primarySource = DownloadSource.GITHUB,
            repoPath = "k2-fsa/sherpa-onnx",
            files = listOf(
                "sherpa-onnx-streaming-paraformer-bilingual-zh-en/encoder.int8.onnx" to "encoder.int8.onnx",
                "sherpa-onnx-streaming-paraformer-bilingual-zh-en/decoder.int8.onnx" to "decoder.int8.onnx",
                "sherpa-onnx-streaming-paraformer-bilingual-zh-en/tokens.txt" to "tokens.txt",
            ),
        ),
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)   // 大文件下载给更多时间
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)               // GitHub/镜像源会 302 重定向到 CDN
        .build()

    fun getModelDirectory(context: Context): File {
        val dir = File(context.filesDir, "stt_models")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 获取模型的独立存储子目录 */
    private fun getModelSubDir(context: Context, modelInfo: ModelInfo): File {
        val dir = File(getModelDirectory(context), modelInfo.modelName)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 缓存的推荐模型（RAM 不会变，只查一次） */
    private var cachedRecommendedModel: ModelType? = null

    fun getRecommendedModel(context: Context): ModelType {
        cachedRecommendedModel?.let { return it }

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        val totalRamMB = memoryInfo.totalMem / (1024 * 1024)

        val result = when {
            totalRamMB >= 6 * 1024 -> ModelType.PARAFORMER
            totalRamMB >= 4 * 1024 -> ModelType.SENSE_VOICE_SMALL
            else -> ModelType.FUNASR_NANO_INT8
        }
        cachedRecommendedModel = result
        DebugLog.i("$TAG: 设备总 RAM = ${totalRamMB}MB, 推荐模型 = ${result.name}")
        return result
    }

    /** 下载状态缓存（modelType → 已下载），避免反复查文件系统 */
    private val downloadStatusCache = mutableMapOf<ModelType, Boolean>()

    /**
     * 检查模型是否已下载且文件完整。
     * 对于 SenseVoice 等需要 tokens.json→tokens.txt 转换的模型，
     * 同时检查 tokens.txt 是否已生成。
     *
     * 结果会被缓存，直到模型被删除或重新下载时才失效。
     */
    fun isModelDownloaded(context: Context, modelType: ModelType): Boolean {
        downloadStatusCache[modelType]?.let { return it }

        val modelInfo = AVAILABLE_MODELS.find { it.type == modelType } ?: return false
        val subDir = getModelSubDir(context, modelInfo)
        val result = modelInfo.files.all { File(subDir, it.second).exists() } &&
                ensureTokensTxtExists(subDir)
        downloadStatusCache[modelType] = result
        return result
    }

    /**
     * 检查是否有任何已下载的模型可用。
     * 优先使用推荐模型，如果没有则检查其他已下载的模型。
     * @return 已下载的模型类型，如果没有则返回 null
     */
    fun getAnyDownloadedModel(context: Context): ModelType? {
        // 优先检查推荐模型
        val recommended = getRecommendedModel(context)
        if (isModelDownloaded(context, recommended)) return recommended
        // 检查其他模型
        for (modelType in ModelType.entries) {
            if (modelType == recommended) continue
            if (isModelDownloaded(context, modelType)) return modelType
        }
        return null
    }

    /** 确保 tokens.txt 存在（从 tokens.json 转换或直接已有） */
    private fun ensureTokensTxtExists(modelDir: File): Boolean {
        val txtFile = File(modelDir, "tokens.txt")
        if (txtFile.exists()) return true
        val jsonFile = File(modelDir, "tokens.json")
        if (!jsonFile.exists()) return true // 没有 tokens.json 的模型不需要转换
        // 旧数据兼容：已下载但缺少 tokens.txt，执行延迟转换
        DebugLog.w("$TAG: 检测到旧数据缺少 tokens.txt，执行延迟转换")
        convertTokensJsonToTxt(modelDir)
        return txtFile.exists()
    }

    /** 获取模型的存储目录 */
    fun getModelPath(context: Context, modelType: ModelType): File? {
        val modelInfo = AVAILABLE_MODELS.find { it.type == modelType } ?: return null
        return getModelSubDir(context, modelInfo).also { it.mkdirs() }
    }

    fun downloadModel(context: Context, modelType: ModelType): Flow<DownloadProgress> = flow {
        val modelInfo = AVAILABLE_MODELS.find { it.type == modelType } ?: run {
            emit(DownloadProgress.Error("未知模型类型: $modelType"))
            return@flow
        }

        if (isModelDownloaded(context, modelType)) {
            DebugLog.i("$TAG: 模型 ${modelInfo.modelName} 已存在，跳过下载")
            emit(DownloadProgress.Complete)
            return@flow
        }

        val tasks = modelInfo.downloadTasks()
        val modelDir = getModelSubDir(context, modelInfo)
        modelDir.mkdirs()
        val totalBytes = modelInfo.sizeBytes
        DebugLog.i("$TAG: 开始下载模型 ${modelInfo.modelName} (${formatSize(totalBytes)}), 共 ${tasks.size} 个文件")

        for ((index, task) in tasks.withIndex()) {
            val (sourceName, url, localName) = task
            val localFile = File(modelDir, localName)

            DebugLog.i("$TAG: [${index + 1}/${tasks.size}] 下载 $localName <- $sourceName")
            emit(DownloadProgress.SourceSwitch(sourceName, index + 1, tasks.size))

            // 下载文件并在循环中直接 emit 进度
            var downloadResult = downloadWithProgress(url, localFile, totalBytes)

            // GitHub 超时/失败时，尝试 ModelScope 备用源
            if (downloadResult is DownloadResult.Stalled || downloadResult is DownloadResult.Failed) {
                val fallback = modelInfo.fallbackTasks()
                if (fallback != null && index < fallback.size) {
                    val (_, fbUrl, _) = fallback[index]
                    DebugLog.w("$TAG: 主源下载失败，切换到 ModelScope 备用: $fbUrl")
                    emit(DownloadProgress.SourceSwitch("ModelScope(备用)", index + 1, tasks.size))
                    downloadResult = downloadWithProgress(fbUrl, localFile, totalBytes)
                }
            }

            when (downloadResult) {
                is DownloadResult.Success -> {
                    DebugLog.i("$TAG: $localName 下载完成 (${formatSize(localFile.length())})")
                }
                is DownloadResult.Stalled -> {
                    emit(DownloadProgress.Error("下载卡住: ${downloadResult.reason}"))
                    return@flow
                }
                is DownloadResult.Failed -> {
                    emit(DownloadProgress.Error("下载失败: ${downloadResult.reason}"))
                    return@flow
                }
            }
        }

        // 验证所有文件都存在
        val allExist = tasks.all { File(modelDir, it.third).exists() }
        if (allExist) {
            // 后处理: 转换 tokens.json → tokens.txt（部分模型源提供 JSON 格式）
            convertTokensJsonToTxt(modelDir)

            downloadStatusCache[modelType] = true
            DebugLog.i("$TAG: 模型 ${modelInfo.modelName} 安装完成")
            emit(DownloadProgress.Complete)
        } else {
            emit(DownloadProgress.Error("部分文件缺失"))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 下载文件并emit进度（在 Flow 上下文中调用）
     */
    private suspend fun kotlinx.coroutines.flow.FlowCollector<DownloadProgress>.downloadWithProgress(
        url: String,
        localFile: File,
        totalBytes: Long,
    ): DownloadResult {
        try {
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                return DownloadResult.Failed("HTTP ${response.code}")
            }

            val body = response.body ?: return DownloadResult.Failed("响应体为空")

            localFile.sink().buffer().use { sink ->
                val source = body.source()
                val buffer = ByteArray(8192)
                var totalRead = 0L
                var lastProgressTime = System.currentTimeMillis()
                var lastProgressBytes = 0L
                var lastEmitTime = 0L

                while (true) {
                    val read = source.read(buffer)
                    if (read == -1) break
                    sink.write(buffer, 0, read)
                    totalRead += read

                    val now = System.currentTimeMillis()
                    // 每 300ms 上报一次进度
                    if (now - lastEmitTime >= 300) {
                        val progress = (totalRead.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                        emit(DownloadProgress.Downloading(progress))
                        lastEmitTime = now
                    }

                    if (totalRead > lastProgressBytes) {
                        lastProgressTime = now
                        lastProgressBytes = totalRead
                    } else if (now - lastProgressTime > STALL_TIMEOUT_MS) {
                        DebugLog.w("$TAG: 下载卡住: ${STALL_TIMEOUT_MS / 1000}秒无数据")
                        localFile.delete()
                        return DownloadResult.Stalled("${STALL_TIMEOUT_MS / 1000}秒内无数据传输")
                    }
                }
                // 最终上报一次
                val progress = (totalRead.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                emit(DownloadProgress.Downloading(progress))
            }

            if (localFile.length() < 1024) {
                localFile.delete()
                return DownloadResult.Failed("文件过小 (${localFile.length()} B)")
            }

            return DownloadResult.Success(localFile)
        } catch (e: Exception) {
            if (localFile.exists()) localFile.delete()
            return DownloadResult.Failed(e.message ?: "未知异常")
        }
    }

    /**
     * 将 tokens.json 转换为 tokens.txt（sherpa-onnx 需要纯文本格式）。
     *
     * 支持两种 JSON 格式：
     * 1. 数组格式（SenseVoice 实际使用）：["<unk>", "<s>", "</s>", "▁the", ...]
     *    数组索引即为 token ID
     * 2. 对象格式（备用）：{"token1": 0, "token2": 1, ...}
     *
     * 输出 TXT 格式：每行一个 token，按索引排列
     */
    private fun convertTokensJsonToTxt(modelDir: File) {
        val jsonFile = File(modelDir, "tokens.json")
        val txtFile = File(modelDir, "tokens.txt")

        if (!jsonFile.exists() || txtFile.exists()) return

        try {
            val json = jsonFile.readText(Charsets.UTF_8).trim()
            DebugLog.i("$TAG: 开始解析 tokens.json (${json.length} 字符)")

            val tokens: List<String> = when {
                // 格式1：JSON Array — ["<unk>", "<s>", ...]（SenseVoice 实际格式）
                json.startsWith("[") -> parseJsonArrayTokens(json)
                // 格式2：JSON Object — {"token": index, ...}
                json.startsWith("{") -> parseJsonObjectTokens(json)
                else -> {
                    DebugLog.w("$TAG: tokens.json 不是有效的 JSON 格式")
                    emptyList()
                }
            }

            if (tokens.isEmpty()) {
                DebugLog.w("$TAG: tokens.json 解析为空，跳过转换")
                return
            }

            txtFile.bufferedWriter().use { writer ->
                for (token in tokens) {
                    writer.write(token)
                    writer.newLine()
                }
            }

            DebugLog.i("$TAG: tokens.json → tokens.txt 转换完成 (${tokens.size} 个 token)")
        } catch (e: Exception) {
            DebugLog.w("$TAG: tokens.json 转换失败: ${e.message}")
        }
    }

    /**
     * 解析 JSON 数组格式的 tokens.json
     * 输入: ["<unk>", "<s>", "</s>", "▁the", "s", "▁to", ...]
     * 输出: 按数组顺序的 token 列表
     */
    private fun parseJsonArrayTokens(json: String): List<String> {
        val tokens = mutableListOf<String>()
        // 去掉首尾方括号
        val content = json.removeSurrounding("[", "]").trim()
        if (content.isEmpty()) return tokens

        // 手动解析 JSON 数组元素（避免引入 JSON 库）
        var i = 0
        while (i < content.length) {
            val ch = content[i]
            when {
                ch == '"' -> {
                    // 解析字符串
                    val end = findStringEnd(content, i + 1)
                    if (end > i + 1) {
                        // 处理转义字符
                        val raw = content.substring(i + 1, end)
                        tokens.add(unescapeJsonString(raw))
                    }
                    i = end + 1
                }
                ch == ',' -> { i++ }  // 跳过逗号分隔符
                ch == ' ' || ch == '\n' || ch == '\r' || ch == '\t' -> { i++ }  // 跳过空白
                else -> { i++ }  // 跳过其他字符
            }
        }
        return tokens
    }

    /**
     * 解析 JSON 对象格式的 tokens.json（备用格式）
     * 输入: {"<unk>": 0, "<s>": 1, ...}
     * 输出: 按索引值排序的 token 列表
     */
    private fun parseJsonObjectTokens(json: String): List<String> {
        val tokens = mutableMapOf<String, Int>()
        val cleaned = json.removeSurrounding("{", "}").trim()
        val pairs = cleaned.split(",").map { it.trim() }
        for (pair in pairs) {
            val colonIdx = pair.indexOf(':')
            if (colonIdx > 0) {
                val keyRaw = pair.substring(0, colonIdx).trim().removeSurrounding("\"")
                val key = unescapeJsonString(keyRaw)
                val valueStr = pair.substring(colonIdx + 1).trim()
                val value = valueStr.toIntOrNull()
                if (value != null) {
                    tokens[key] = value
                }
            }
        }
        return tokens.entries.sortedBy { it.value }.map { it.key }
    }

    /** 在 JSON 内容中找到字符串结束位置（处理转义引号） */
    private fun findStringEnd(content: String, start: Int): Int {
        var i = start
        while (i < content.length) {
            when (content[i]) {
                '\\' -> i += 2  // 跳过转义字符
                '"' -> return i
                else -> i++
            }
        }
        return content.length
    }

    /** 处理 JSON 字符串中的转义序列 */
    private fun unescapeJsonString(raw: String): String {
        return raw
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\/", "/")
    }

    /** 单次下载结果 */
    private sealed class DownloadResult {
        data class Success(val file: File) : DownloadResult()
        data class Stalled(val reason: String) : DownloadResult()
        data class Failed(val reason: String) : DownloadResult()
    }

    fun clearModelCache(context: Context, modelType: ModelType?) {
        if (modelType != null) {
            val modelInfo = AVAILABLE_MODELS.find { it.type == modelType }
            if (modelInfo != null) {
                val subDir = getModelSubDir(context, modelInfo)
                if (subDir.exists()) subDir.deleteRecursively()
            }
            downloadStatusCache.remove(modelType)
        } else {
            getModelDirectory(context).deleteRecursively()
            downloadStatusCache.clear()
        }
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        }
    }

    sealed class DownloadProgress {
        data class Downloading(val progress: Float) : DownloadProgress()   // 0..1
        /** 切换到新的下载源 */
        data class SourceSwitch(val sourceName: String, val current: Int, val total: Int) : DownloadProgress()
        data object Complete : DownloadProgress()
        data class Error(val message: String) : DownloadProgress()
    }
}
