package top.hsyscn.opedrgent.ocr

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

/**
 * OCR 模型管理器 — 负责 PP-OCRv6 模型的下载和管理。
 * 模型来源：ModelScope（百度 PaddlePaddle 官方仓库）。
 */
object OcrModelManager {

    private const val TAG = "OcrModelManager"
    private const val STALL_TIMEOUT_MS = 5_000L

    /** ModelScope 模型下载基础 URL */
    private const val MODELSCOPE_BASE = "https://www.modelscope.cn/models"

    data class OcrModelInfo(
        val id: String,
        val displayName: String,
        val description: String,
        val sizeBytes: Long,
        val minRamMB: Int,
        /** ModelScope 仓库路径 */
        val modelscopeRepo: String,
        /** 需要下载的文件列表 (远程文件名 to 本地文件名) */
        val files: List<Pair<String, String>>,
    ) {
        fun downloadTasks(): List<Triple<String, String, String>> {
            return files.map { (remoteName, localName) ->
                val url = "$MODELSCOPE_BASE/$modelscopeRepo/resolve/master/$remoteName"
                Triple("ModelScope", url, localName)
            }
        }
    }

    /** PP-OCRv6 Medium — 中英文文字识别 */
    val PP_OCR_V6 = OcrModelInfo(
        id = "pp_ocrv6_medium",
        displayName = "PP-OCRv6 Medium",
        description = "百度 PaddleOCR 第六代文字识别模型，支持中英文，精度高",
        sizeBytes = 77 * 1024 * 1024L,  // 76.55MB
        minRamMB = 512,
        modelscopeRepo = "PaddlePaddle/PP-OCRv6_medium_rec_onnx",
        files = listOf(
            "inference.onnx" to "pp_ocrv6_rec.onnx",
        ),
    )

    val AVAILABLE_MODELS = listOf(PP_OCR_V6)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun getModelDirectory(context: Context): File {
        val dir = File(context.filesDir, "ocr_models")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun isModelDownloaded(context: Context, modelId: String): Boolean {
        val model = AVAILABLE_MODELS.find { it.id == modelId } ?: return false
        return model.files.all { File(getModelDirectory(context), it.second).exists() }
    }

    fun getModelPath(context: Context, modelId: String): File? {
        val model = AVAILABLE_MODELS.find { it.id == modelId } ?: return null
        val recFile = File(getModelDirectory(context), model.files.first().second)
        return if (recFile.exists()) recFile else null
    }

    fun downloadModel(context: Context, modelId: String): Flow<OcrDownloadProgress> = flow {
        val model = AVAILABLE_MODELS.find { it.id == modelId } ?: run {
            emit(OcrDownloadProgress.Error("未知模型: $modelId"))
            return@flow
        }

        if (isModelDownloaded(context, modelId)) {
            DebugLog.i("$TAG: 模型 ${model.displayName} 已存在，跳过下载")
            emit(OcrDownloadProgress.Complete)
            return@flow
        }

        val tasks = model.downloadTasks()
        val modelDir = getModelDirectory(context)
        modelDir.mkdirs()
        DebugLog.i("$TAG: 开始下载 ${model.displayName} (${model.sizeBytes / 1024 / 1024}MB), 共 ${tasks.size} 个文件")

        for ((index, task) in tasks.withIndex()) {
            val (sourceName, url, localName) = task
            val localFile = File(modelDir, localName)

            DebugLog.i("$TAG: [${index + 1}/${tasks.size}] 下载 $localName <- $sourceName")
            emit(OcrDownloadProgress.SourceSwitch(sourceName, index + 1, tasks.size))

            val result = downloadWithProgress(url, localFile, model.sizeBytes)
            when (result) {
                is DownloadResult.Success -> {
                    DebugLog.i("$TAG: $localName 下载完成 (${localFile.length() / 1024 / 1024}MB)")
                }
                is DownloadResult.Stalled -> {
                    emit(OcrDownloadProgress.Error("下载卡住: ${result.reason}"))
                    return@flow
                }
                is DownloadResult.Failed -> {
                    emit(OcrDownloadProgress.Error("下载失败: ${result.reason}"))
                    return@flow
                }
            }
        }

        val allExist = tasks.all { File(getModelDirectory(context), it.third).exists() }
        if (allExist) {
            DebugLog.i("$TAG: ${model.displayName} 安装完成")
            emit(OcrDownloadProgress.Complete)
        } else {
            emit(OcrDownloadProgress.Error("部分文件缺失"))
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun kotlinx.coroutines.flow.FlowCollector<OcrDownloadProgress>.downloadWithProgress(
        url: String, localFile: File, totalBytes: Long,
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
                        emit(OcrDownloadProgress.Downloading(progress))
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
                emit(OcrDownloadProgress.Downloading(progress))
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

    fun clearModelCache(context: Context, modelId: String?) {
        if (modelId != null) {
            val model = AVAILABLE_MODELS.find { it.id == modelId }
            if (model != null) {
                model.files.forEach { File(getModelDirectory(context), it.second).delete() }
            }
        } else {
            getModelDirectory(context).deleteRecursively()
        }
    }

    private sealed class DownloadResult {
        data class Success(val file: File) : DownloadResult()
        data class Stalled(val reason: String) : DownloadResult()
        data class Failed(val reason: String) : DownloadResult()
    }
}

sealed class OcrDownloadProgress {
    data class Downloading(val progress: Float) : OcrDownloadProgress()   // 0..1
    data class SourceSwitch(val sourceName: String, val current: Int, val total: Int) : OcrDownloadProgress()
    data object Complete : OcrDownloadProgress()
    data class Error(val message: String) : OcrDownloadProgress()
}
