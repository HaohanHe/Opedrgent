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
import okio.source
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import top.hsyscn.opedrgent.network.NetworkConfig
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.File
import java.util.concurrent.TimeUnit

object ModelManager {

    private const val TAG = "ModelManager"

    /** 下载超时：连续 N 毫秒无进度则切换源 */
    private const val STALL_TIMEOUT_MS = 5_000L

    /** ModelScope 国内直连 base */
    private const val MODELSCOPE_BASE = "https://www.modelscope.cn/models"

    /** GitHub sherpa-onnx releases — HR (同音字替换) 资源文件 */
    private const val GITHUB_HR_BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/hr-files"

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
         *
         * 特殊处理: 远程文件名以 "hr-files/" 开头的资源（同音字替换HR资源）
         *          自动路由到 GitHub hr-files release，不受 primarySource 影响。
         */
        fun downloadTasks(): List<Triple<String, String, String>> {
            return files.map { (remoteName, localName) ->
                if (remoteName.startsWith("hr-files/")) {
                    // HR 资源统一从 GitHub sherpa-onnx/hr-files release 下载
                    val hrFileName = remoteName.removePrefix("hr-files/")
                    Triple("GitHub(HR)", "$GITHUB_HR_BASE/$hrFileName", localName)
                } else {
                    val baseUrl = when (primarySource) {
                        DownloadSource.MODELSCOPE -> "$MODELSCOPE_BASE/$repoPath/resolve/master"
                        DownloadSource.HUGGINGFACE -> "https://hf-mirror.com/$repoPath/resolve/main"
                        DownloadSource.GITHUB -> "https://github.com/$repoPath/releases/download/asr-models"
                    }
                    Triple(primarySource.label, "$baseUrl/$remoteName", localName)
                }
            }
        }

        /** 主源失败时的备用下载地址 */
        fun fallbackTasks(): List<Triple<String, String, String>>? {
            return when (type) {
                ModelType.STREAMING_PARAFORMER -> {
                    // 分离 HR 文件和模型文件，分别处理备用源
                    val hrFiles = files.filter { it.first.startsWith("hr-files/") }
                    val modelFiles = files.filter { !it.first.startsWith("hr-files/") }

                    val fallbacks = mutableListOf<Triple<String, String, String>>()

                    // HR 文件: 备用源仍然是 GitHub hr-files（唯一来源）
                    for ((remoteName, localName) in hrFiles) {
                        val hrFileName = remoteName.removePrefix("hr-files/")
                        fallbacks.add(Triple("GitHub(HR-备用)", "$GITHUB_HR_BASE/$hrFileName", localName))
                    }

                    // 模型文件: ModelScope ↔ GitHub 互备
                    if (modelFiles.isNotEmpty()) {
                        if (primarySource == DownloadSource.MODELSCOPE) {
                            val baseUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"
                            for ((remoteName, localName) in modelFiles) {
                                val ghRemoteName = "sherpa-onnx-streaming-paraformer-bilingual-zh-en/${localName}"
                                fallbacks.add(Triple("GitHub(备用)", "$baseUrl/$ghRemoteName", localName))
                            }
                        } else {
                            val fallbackRepo = "pengzhendong/sherpa-onnx-streaming-paraformer-bilingual-zh-en"
                            val baseUrl = "$MODELSCOPE_BASE/$fallbackRepo/resolve/master"
                            for ((remoteName, localName) in modelFiles) {
                                val msRemoteName = remoteName.substringAfterLast("/")
                                fallbacks.add(Triple("ModelScope(备用)", "$baseUrl/$msRemoteName", localName))
                            }
                        }
                    }

                    fallbacks.ifEmpty { null }
                }
                else -> null
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
            primarySource = DownloadSource.MODELSCOPE,
            repoPath = "pengzhendong/sherpa-onnx-streaming-paraformer-bilingual-zh-en",
            files = listOf(
                "encoder.int8.onnx" to "encoder.int8.onnx",
                "decoder.int8.onnx" to "decoder.int8.onnx",
                "tokens.txt" to "tokens.txt",
            ),
        ),
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(NetworkConfig.STT_MODEL_DOWNLOAD_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(NetworkConfig.STT_MODEL_DOWNLOAD_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)   // 大文件下载给更多时间
        .writeTimeout(NetworkConfig.STT_MODEL_DOWNLOAD_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
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
    @Volatile private var cachedRecommendedModel: ModelType? = null

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
        // 核心模型文件必须全部存在；HR后处理资源（dict.tar.bz2/lexicon.txt/replace.fst）是可选增强，
        // 缺失不影响模型可用性（只是HR矫正不启用），避免旧安装被误判为"未下载"
        val requiredFiles = modelInfo.files.filter { !it.first.startsWith("hr-files/") }
        val result = requiredFiles.all { File(subDir, it.second).exists() } &&
                ensureHrResourcesExtracted(subDir) &&
                ensureTokensTxtExists(subDir)
        downloadStatusCache[modelType] = result
        return result
    }

    /**
     * 检查是否有任何已下载的模型可用。
     * 优先使用流式模型（实时显示），其次推荐模型，最后其他模型。
     * @return 已下载的模型类型，如果没有则返回 null
     */
    fun getAnyDownloadedModel(context: Context): ModelType? {
        // 优先使用流式模型（支持实时显示）
        if (isModelDownloaded(context, ModelType.STREAMING_PARAFORMER)) return ModelType.STREAMING_PARAFORMER
        // 其次推荐模型
        val recommended = getRecommendedModel(context)
        if (isModelDownloaded(context, recommended)) return recommended
        // 最后检查其他模型
        for (modelType in ModelType.entries) {
            if (modelType == ModelType.STREAMING_PARAFORMER || modelType == recommended) continue
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

    /**
     * 确保 HR 资源中的 dict.tar.bz2 已解压为 dict/ 目录。
     *
     * sherpa-onnx 同音字替换需要三个文件：
     * - dict.tar.bz2 → 解压为 dict/ (jieba 分词词典目录)
     * - lexicon.txt   (汉字→拼音映射)
     * - replace.fst   (同音字替换 FST 规则)
     *
     * 只有当 dict.tar.bz2 存在但 dict/ 不存在时才执行解压。
     */
    private fun ensureHrResourcesExtracted(modelDir: File): Boolean {
        val dictTar = File(modelDir, "dict.tar.bz2")
        val dictDir = File(modelDir, "dict")

        // 没有 HR 资源文件，跳过（不是错误）
        if (!dictTar.exists()) return true

        // 已解压，直接通过
        if (dictDir.exists() && dictDir.isDirectory && dictDir.listFiles()?.isNotEmpty() == true) {
            return true
        }

        DebugLog.i("$TAG: 检测到 dict.tar.bz2 未解压，开始解压...")
        return try {
            extractTarBz2(dictTar, modelDir)
            val success = dictDir.exists() && dictDir.listFiles()?.isNotEmpty() == true
            if (success) {
                DebugLog.i("$TAG: dict.tar.bz2 解压完成 (${dictDir.listFiles()?.size} 个文件)")
            } else {
                DebugLog.e("$TAG: dict.tar.bz2 解压后 dict/ 为空")
            }
            success
        } catch (e: Exception) {
            DebugLog.e("$TAG: 解压 dict.tar.bz2 失败: ${e.message}", e)
            false
        }
    }

    // ── HR 资源单独下载 ──────────────────────────────────────────

    /** HR 资源文件列表 (远程路径 to 本地名) */
    private val HR_FILES = listOf(
        "dict.tar.bz2" to "dict.tar.bz2",
        "lexicon.txt" to "lexicon.txt",
        "replace.fst" to "replace.fst",
    )

    /** HR 资源下载地址 (GitHub hr-files release) */
    private const val HR_BASE_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/hr-files"

    /**
     * 检查 HR 资源是否已下载（3 个文件都在模型目录中）
     */
    fun isHrDownloaded(context: Context): Boolean {
        val modelDir = getModelPath(context, ModelType.STREAMING_PARAFORMER) ?: return false
        return HR_FILES.all { File(modelDir, it.second).exists() }
    }

    /**
     * 单独下载 HR 资源（不跟核心模型一起下）
     * 只有用户主动点击按钮时才触发
     */
    fun downloadHrResources(context: Context): Flow<DownloadProgress> = flow {
        val modelDir = getModelPath(context, ModelType.STREAMING_PARAFORMER) ?: run {
            emit(DownloadProgress.Error("请先下载流式Paraformer模型"))
            return@flow
        }

        if (isHrDownloaded(context)) {
            DebugLog.i("$TAG: HR 资源已存在，跳过下载")
            emit(DownloadProgress.Complete)
            return@flow
        }

        val totalHrBytes = 5 * 1024 * 1024L  // ~5MB 总量
        var downloadedBytes = 0L
        DebugLog.i("$TAG: 开始下载 HR 资源 (同音字替换), 共 ${HR_FILES.size} 个文件")

        for ((remoteName, localName) in HR_FILES) {
            val localFile = File(modelDir, localName)
            if (localFile.exists() && localFile.length() > 0) {
                downloadedBytes += localFile.length()
                continue
            }

            val url = "$HR_BASE_URL/$remoteName"
            DebugLog.i("$TAG: 下载 HR: $localName <- $url")
            emit(DownloadProgress.SourceSwitch("GitHub(hr-files)", 1, 1))

            val result = downloadWithProgress(url, localFile, totalHrBytes, downloadedBytes)
            when (result) {
                is DownloadResult.Success -> {
                    downloadedBytes += localFile.length()
                    DebugLog.i("$TAG: HR $localName 下载完成 (${formatSize(localFile.length())})")
                }
                is DownloadResult.Stalled -> {
                    emit(DownloadProgress.Error("HR下载卡住: ${result.reason}"))
                    return@flow
                }
                is DownloadResult.Failed -> {
                    emit(DownloadProgress.Error("HR下载失败: ${result.reason}"))
                    return@flow
                }
            }
        }

        // 自动解压 dict.tar.bz2
        ensureHrResourcesExtracted(modelDir)
        DebugLog.i("$TAG: HR 资源下载完成")
        emit(DownloadProgress.Complete)
    }.flowOn(Dispatchers.IO)

    /**
     * 解压 .tar.bz2 文件到目标目录。
     *
     * 使用 Apache Commons Compress 处理 bz2 解包 + tar 归档提取。
     */
    private fun extractTarBz2(tarBz2File: File, targetDir: File) {
        tarBz2File.inputStream().use { fis ->
            BZip2CompressorInputStream(fis).use { bzis ->
                // 手动解析 tar 格式（避免引入额外 tar 依赖）
                val buffer = ByteArray(512)
                while (true) {
                    val read = bzis.read(buffer)
                    if (read < 512) break

                    // TAR header: name (100) + mode (8) + uid (8) + gid (8) + size (12) + ...
                    if (buffer[0].toInt() == 0) break // 全零块 = 结束标记

                    val name = String(buffer, 0, 100, Charsets.US_ASCII).trimEnd('\u0000')
                    if (name.isEmpty()) break

                    // size 字段在 offset 512-524（八进制字符串）
                    val sizeStr = String(buffer, 124, 12, Charsets.US_ASCII).trimEnd('\u0000', ' ')
                    if (sizeStr.isEmpty()) continue
                    val fileSize = sizeStr.toLong(8)

                    // 跳过非普通文件（目录、链接等）
                    val typeFlag = buffer[156].toInt()
                    if (typeFlag != 0 && typeFlag != 48) { // '0' = 48
                        // 目录或特殊条目：跳过文件数据
                        val blocks = (fileSize + 511L) / 512L
                        repeat(blocks.toInt()) { bzis.read(buffer) }
                        continue
                    }

                    // 提取文件
                    val outFile = File(targetDir, name)
                    outFile.parentFile?.mkdirs()

                    if (fileSize > 0) {
                        outFile.outputStream().use { fos ->
                            var remaining = fileSize
                            while (remaining > 0) {
                                val toRead = minOf(remaining, buffer.size.toLong()).toInt()
                                val n = bzis.read(buffer, 0, toRead)
                                if (n == -1) break
                                fos.write(buffer, 0, n)
                                remaining -= n
                            }
                        }
                    } else {
                        outFile.createNewFile()
                    }

                    // 对齐到 512 字节块
                    val padding = (512L - (fileSize % 512L)) % 512L
                    if (padding > 0) bzis.skip(padding)
                }
            }
        }
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
        var downloadedBytes = 0L  // 已下载的累计字节数（用于跨文件进度计算）
        DebugLog.i("$TAG: 开始下载模型 ${modelInfo.modelName} (${formatSize(totalBytes)}), 共 ${tasks.size} 个文件")

        for ((index, task) in tasks.withIndex()) {
            val (sourceName, url, localName) = task
            val localFile = File(modelDir, localName)

            // 跳过已下载的文件（断点续传）
            if (localFile.exists() && localFile.length() > 0) {
                DebugLog.i("$TAG: [${index + 1}/${tasks.size}] $localName 已存在 (${formatSize(localFile.length())})，跳过")
                downloadedBytes += localFile.length()
                continue
            }

            DebugLog.i("$TAG: [${index + 1}/${tasks.size}] 下载 $localName <- $sourceName")
            emit(DownloadProgress.SourceSwitch(sourceName, index + 1, tasks.size))

            // 下载文件并在循环中直接 emit 进度
            var downloadResult = downloadWithProgress(url, localFile, totalBytes, downloadedBytes)

            // 主源超时/失败时，尝试备用源
            if (downloadResult is DownloadResult.Stalled || downloadResult is DownloadResult.Failed) {
                val fallback = modelInfo.fallbackTasks()
                if (fallback != null && index < fallback.size) {
                    val (fbSourceName, fbUrl, _) = fallback[index]
                    DebugLog.w("$TAG: 主源下载失败，切换到备用源: $fbSourceName")
                    emit(DownloadProgress.SourceSwitch(fbSourceName, index + 1, tasks.size))
                    downloadResult = downloadWithProgress(fbUrl, localFile, totalBytes, downloadedBytes)
                }
            }

            when (downloadResult) {
                is DownloadResult.Success -> {
                    downloadedBytes += localFile.length()
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
            // 后处理1: 解压 dict.tar.bz2 → dict/ (HR 同音字替换资源)
            ensureHrResourcesExtracted(modelDir)

            // 后处理2: 转换 tokens.json → tokens.txt（部分模型源提供 JSON 格式）
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
     * @param alreadyDownloadedBytes 之前已下载文件的累计字节数（跨文件进度计算）
     */
    private suspend fun kotlinx.coroutines.flow.FlowCollector<DownloadProgress>.downloadWithProgress(
        url: String,
        localFile: File,
        totalBytes: Long,
        alreadyDownloadedBytes: Long = 0L,
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
                    // 每 300ms 上报一次进度（用累计字节数算，避免跨文件跳动）
                    if (now - lastEmitTime >= 300) {
                        val cumulativeRead = alreadyDownloadedBytes + totalRead
                        val progress = (cumulativeRead.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
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
                val cumulativeRead = alreadyDownloadedBytes + totalRead
                val progress = (cumulativeRead.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
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
