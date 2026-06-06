package top.hsyscn.opedrgent.stt

import android.app.ActivityManager
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

object ModelManager {

    private const val TAG = "ModelManager"
    private const val MODEL_BASE_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download"

    data class ModelInfo(
        val type: ModelType,
        val modelName: String,
        val version: String,
        val sizeBytes: Long,
        val downloadUrl: String,
        val minRamMB: Int,
        /** 解压后的模型目录名（tar.bz2 内部可能包含子目录） */
        val extractDirName: String = modelName,
    )

    val AVAILABLE_MODELS = listOf(
        ModelInfo(
            type = ModelType.PARAFORMER,
            modelName = "sherpa-onnx-paraformer-zh",
            version = "2024-03-09",
            sizeBytes = 220 * 1024 * 1024L,
            downloadUrl = "$MODEL_BASE_URL/asr-models/sherpa-onnx-paraformer-zh-2024-03-09.tar.bz2",
            minRamMB = 6 * 1024,
        ),
        ModelInfo(
            type = ModelType.SENSE_VOICE_SMALL,
            modelName = "sherpa-onnx-sense-voice-zh",
            version = "2024-10-30",
            sizeBytes = 240 * 1024 * 1024L,
            downloadUrl = "$MODEL_BASE_URL/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-10-30.tar.bz2",
            minRamMB = 4 * 1024,
            extractDirName = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-10-30",
        ),
        ModelInfo(
            type = ModelType.FUNASR_NANO_INT8,
            modelName = "sherpa-onnx-funasr-nano-int8",
            version = "2025-12-01",
            sizeBytes = 20 * 1024 * 1024L,
            downloadUrl = "$MODEL_BASE_URL/asr-models/models/sherpa-onnx-funasr-nano-int8.tar.bz2",
            minRamMB = 0,
        ),
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)   // 大文件下载给更多时间
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun getModelDirectory(context: Context): File {
        val dir = File(context.filesDir, "stt_models")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getRecommendedModel(context: Context): ModelType {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        val totalRamMB = memoryInfo.totalMem / (1024 * 1024)

        DebugLog.i("$TAG: 设备总 RAM = ${totalRamMB}MB")

        return when {
            totalRamMB >= 6 * 1024 -> ModelType.PARAFORMER
            totalRamMB >= 4 * 1024 -> ModelType.SENSE_VOICE_SMALL
            else -> ModelType.FUNASR_NANO_INT8
        }
    }

    /**
     * 检查模型是否已下载且已解压（目录中存在 model.onnx 或 tokens.txt）
     */
    fun isModelDownloaded(context: Context, modelType: ModelType): Boolean {
        val modelInfo = AVAILABLE_MODELS.find { it.type == modelType } ?: return false
        val modelDir = getModelDir(context, modelInfo)
        if (!modelDir.exists() || !modelDir.isDirectory) return false
        // 确认解压产物存在（至少有模型文件或 token 文件）
        return modelDir.listFiles()?.any {
            it.name.endsWith(".onnx") || it.name == "tokens.txt" || it.name == "tokens"
        } == true
    }

    /** 获取模型的实际存储目录（考虑 tar.bz2 内部可能有子目录） */
    fun getModelPath(context: Context, modelType: ModelType): File? {
        val modelInfo = AVAILABLE_MODELS.find { it.type == modelType } ?: return null
        return getModelDir(context, modelInfo)
    }

    private fun getModelDir(context: Context, info: ModelInfo): File {
        // 优先检查以 extractDirName 命名的子目录（tar.bz2 解压后通常有子目录）
        val subDir = File(getModelDirectory(context), info.extractDirName)
        if (subDir.exists() && subDir.isDirectory && subDir.listFiles()?.isNotEmpty() == true) {
            return subDir
        }
        // 回退到以 modelName 命名的目录
        return File(getModelDirectory(context), info.modelName)
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

        DebugLog.i("$TAG: 开始下载模型 ${modelInfo.modelName} (${formatSize(modelInfo.sizeBytes)})")

        val archiveFile: File
        try {
            emit(DownloadProgress.Downloading(0f))

            val request = Request.Builder().url(modelInfo.downloadUrl).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                emit(DownloadProgress.Error("下载失败 HTTP ${response.code}"))
                return@flow
            }

            val body = response.body ?: run {
                emit(DownloadProgress.Error("响应体为空"))
                return@flow
            }

            val totalBytes = body.contentLength()
            val modelBaseDir = getModelDirectory(context)
            archiveFile = File(modelBaseDir, "${modelInfo.modelName}.tar.bz2")

            FileOutputStream(archiveFile).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(16384)
                    var bytesRead: Int
                    var totalRead = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        if (totalBytes > 0) {
                            val progress = totalRead.toFloat() / totalBytes.toFloat()
                            emit(DownloadProgress.Downloading(progress.coerceIn(0f, 1f)))
                        }
                    }
                }
            }

            DebugLog.i("$TAG: 模型压缩包下载完成 (${formatSize(archiveFile.length())})")

        } catch (e: Exception) {
            DebugLog.e("$TAG: 下载异常: ${e.message}", e)
            emit(DownloadProgress.Error("下载失败: ${e.message}"))
            return@flow
        }

            // 解压 tar.bz2
            try {
                emit(DownloadProgress.Extracting(0f))
                extractTarBz2(archiveFile, getModelDirectory(context)) { progress ->
                    emit(DownloadProgress.Extracting(progress))
                }

            // 删除压缩包释放空间
            if (archiveFile.exists()) {
                archiveFile.delete()
                DebugLog.i("$TAG: 已删除压缩包 ${archiveFile.name}")
            }

            // 验证解压结果
            val modelDir = getModelDir(context, modelInfo)
            if (!modelDir.exists() || modelDir.listFiles()?.isEmpty() != false) {
                emit(DownloadProgress.Error("解压后未找到模型文件"))
                return@flow
            }

            val fileCount = modelDir.listFiles()?.size ?: 0
            DebugLog.i("$TAG: 模型解压完成 ($fileCount 个文件 in ${modelDir.name})")
            emit(DownloadProgress.Complete)

        } catch (e: Exception) {
            DebugLog.e("$TAG: 解压异常: ${e.message}", e)
            emit(DownloadProgress.Error("解压失败: ${e.message}"))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 解压 tar.bz2 文件到目标目录。
     * 自动处理 tar 包内的顶层子目录（展平到目标目录）。
     */
    private suspend fun extractTarBz2(
        archiveFile: File,
        targetDir: File,
        onProgress: suspend (Float) -> Unit,
    ) {
        val totalSize = archiveFile.length()
        var bytesRead = 0L

        archiveFile.inputStream().use { fis ->
            BZip2CompressorInputStream(fis).use { bzis ->
                TarArchiveInputStream(bzis).use { tis ->
                    var entry = tis.nextTarEntry
                    var processedEntries = 0

                    while (entry != null) {
                        if (entry.isFile) {
                            val relativePath = stripTopLevelDir(entry.name)
                            val outputFile = File(targetDir, relativePath)
                            outputFile.parentFile?.mkdirs()

                            FileOutputStream(outputFile).use { fos ->
                                val buffer = ByteArray(8192)
                                var read: Int
                                while (tis.read(buffer).also { read = it } != -1) {
                                    fos.write(buffer, 0, read)
                                    bytesRead += read
                                }
                            }

                            processedEntries++
                            if (totalSize > 0) {
                                onProgress((bytesRead.toFloat() / totalSize.toFloat()).coerceIn(0f, 0.99f))
                            }
                        }
                        entry = tis.nextTarEntry
                    }
                }
            }
        }
        onProgress(1f)
    }

    /**
     * 去掉路径中的第一个目录层级。
     * 例如: "sherpa-onnx-paraformer-zh-2024-03-09/model.onnx" → "model.onnx"
     *       "model.onnx" → "model.onnx"
     */
    private fun stripTopLevelDir(path: String): String {
        val normalized = path.replace("\\", "/").trimStart('/')
        val firstSlash = normalized.indexOf('/')
        return if (firstSlash > 0) normalized.substring(firstSlash + 1) else normalized
    }

    fun clearModelCache(context: Context, modelType: ModelType?) {
        if (modelType != null) {
            val modelInfo = AVAILABLE_MODELS.find { it.type == modelType }
            if (modelInfo != null) {
                // 清除所有可能的目录
                for (name in listOf(modelInfo.modelName, modelInfo.extractDirName)) {
                    val dir = File(getModelDirectory(context), name)
                    if (dir.exists()) dir.deleteRecursively()
                }
                // 也清除可能残留的压缩包
                File(getModelDirectory(context), "${modelInfo.modelName}.tar.bz2").delete()
            }
        } else {
            getModelDirectory(context).deleteRecursively()
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
        data class Extracting(val progress: Float) : DownloadProgress()   // 0..1
        data object Complete : DownloadProgress()
        data class Error(val message: String) : DownloadProgress()
    }
}
