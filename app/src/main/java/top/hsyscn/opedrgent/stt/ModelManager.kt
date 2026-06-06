package top.hsyscn.opedrgent.stt

import android.app.ActivityManager
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

object ModelManager {

    private const val MODEL_BASE_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download"

    data class ModelInfo(
        val type: ModelType,
        val modelName: String,
        val version: String,
        val sizeBytes: Long,
        val downloadUrl: String,
        val minRamMB: Int,
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
            sizeBytes = 40 * 1024 * 1024L,
            downloadUrl = "$MODEL_BASE_URL/asr-models/sherpa-onnx-sense-voice-zh.tar.bz2",
            minRamMB = 4 * 1024,
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
        .readTimeout(60, TimeUnit.SECONDS)
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

        DebugLog.i("ModelManager: 设备总 RAM = ${totalRamMB}MB")

        return when {
            totalRamMB >= 6 * 1024 -> ModelType.PARAFORMER
            totalRamMB >= 4 * 1024 -> ModelType.SENSE_VOICE_SMALL
            else -> ModelType.FUNASR_NANO_INT8
        }
    }

    fun isModelDownloaded(context: Context, modelType: ModelType): Boolean {
        val modelInfo = AVAILABLE_MODELS.find { it.type == modelType } ?: return false
        val modelDir = File(getModelDirectory(context), modelInfo.modelName)
        return modelDir.exists() && modelDir.listFiles()?.isNotEmpty() == true
    }

    fun downloadModel(context: Context, modelType: ModelType): Flow<Float> = flow {
        val modelInfo = AVAILABLE_MODELS.find { it.type == modelType } ?: run {
            emit(-1f)
            return@flow
        }

        if (isModelDownloaded(context, modelType)) {
            DebugLog.i("ModelManager: 模型 ${modelInfo.modelName} 已存在，跳过下载")
            emit(1f)
            return@flow
        }

        DebugLog.i("ModelManager: 开始下载模型 ${modelInfo.modelName} (${formatSize(modelInfo.sizeBytes)})")

        try {
            val request = Request.Builder().url(modelInfo.downloadUrl).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                DebugLog.e("ModelManager: 下载失败 HTTP ${response.code}")
                emit(-1f)
                return@flow
            }

            val body = response.body ?: run {
                emit(-1f)
                return@flow
            }

            val totalBytes = body.contentLength()
            val modelDir = File(getModelDirectory(context), modelInfo.modelName)
            if (!modelDir.exists()) modelDir.mkdirs()

            val outputFile = File(modelDir, "${modelInfo.modelName}.tar.bz2")
            FileOutputStream(outputFile).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalRead = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead

                        if (totalBytes > 0) {
                            emit(totalRead.toFloat() / totalBytes.toFloat())
                        }
                    }
                }
            }

            DebugLog.i("ModelManager: 模型下载完成")
            emit(1f)

        } catch (e: Exception) {
            DebugLog.e("ModelManager: 下载异常: ${e.message}", e)
            emit(-1f)
        }
    }.flowOn(Dispatchers.IO)

    fun getModelPath(context: Context, modelType: ModelType): File? {
        val modelInfo = AVAILABLE_MODELS.find { it.type == modelType } ?: return null
        return File(getModelDirectory(context), modelInfo.modelName)
    }

    fun clearModelCache(context: Context, modelType: ModelType?) {
        if (modelType != null) {
            val modelInfo = AVAILABLE_MODELS.find { it.type == modelType }
            if (modelInfo != null) {
                val modelDir = File(getModelDirectory(context), modelInfo.modelName)
                modelDir.deleteRecursively()
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
}
