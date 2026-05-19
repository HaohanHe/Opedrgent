package top.hsyscn.opedrgent.llm

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink
import top.hsyscn.opedrgent.utils.DebugLog
import top.hsyscn.opedrgent.service.ModelDownloadService
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

enum class DownloadStatus {
    IDLE,
    QUEUED,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class DownloadProgress(
    val modelId: String,
    val status: DownloadStatus,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val speedBytesPerSec: Long = 0,
    val error: String? = null,
    val filePath: String? = null,
    val remainingTimeSec: Long = 0,
) {
    val progressPercent: Float
        get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes.toFloat() * 100f).coerceIn(0f, 100f) else 0f

    val downloadedMb: Float get() = downloadedBytes / (1024f * 1024f)
    val totalMb: Float get() = totalBytes / (1024f * 1024f)
}

class ModelDownloadManager(private val context: Context) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val activeDownloads = ConcurrentHashMap<String, Job>()
    private val progressFlows = ConcurrentHashMap<String, MutableSharedFlow<DownloadProgress>>()

    private val modelDir: File by lazy {
        File(context.filesDir, "local_models").also { if (!it.exists()) it.mkdirs() }
    }

    fun observeProgress(modelId: String): SharedFlow<DownloadProgress> {
        return progressFlows.getOrPut(modelId) { MutableSharedFlow(replay = 1) }
    }

    fun startDownload(modelInfo: LocalModelInfo): Boolean {
        if (activeDownloads.containsKey(modelInfo.id)) {
            DebugLog.w("ModelDownloadManager", "Already downloading: ${modelInfo.id}")
            return false
        }

        val flow = progressFlows.getOrPut(modelInfo.id) { MutableSharedFlow(replay = 1) }

        val job = scope.launch {
            emitProgress(flow, modelInfo.id, DownloadProgress(
                modelId = modelInfo.id,
                status = DownloadStatus.QUEUED,
                totalBytes = modelInfo.sizeMb * 1024 * 1024,
            ))

            runCatching {
                doDownload(modelInfo, flow)
            }.onFailure { e ->
                if (e is CancellationException) {
                    emitProgress(flow, modelInfo.id, DownloadProgress(
                        modelId = modelInfo.id,
                        status = DownloadStatus.CANCELLED,
                        error = "用户取消"
                    ))
                } else {
                    emitProgress(flow, modelInfo.id, DownloadProgress(
                        modelId = modelInfo.id,
                        status = DownloadStatus.FAILED,
                        error = e.message ?: "未知错误"
                    ))
                    DebugLog.e("ModelDownloadManager", "Download failed: ${modelInfo.id} - ${e.message}")
                }
                ModelDownloadService.stop(context)
            }
        }

        activeDownloads[modelInfo.id] = job
        job.invokeOnCompletion { activeDownloads.remove(modelInfo.id) }

        return true
    }

    fun cancelDownload(modelId: String) {
        activeDownloads[modelId]?.cancel()
        activeDownloads.remove(modelId)

        val info = AvailableLocalModels.findById(modelId)
        if (info != null) {
            val tempFile = File(modelDir, "${info.fileName}.tmp")
            if (tempFile.exists()) tempFile.delete()
        }

        DebugLog.i("ModelDownloadManager", "Download cancelled: $modelId")
    }

    fun deleteModel(modelId: String): Boolean {
        cancelDownload(modelId)

        val info = AvailableLocalModels.findById(modelId) ?: return false
        val file = File(modelDir, info.fileName)
        val tempFile = File(modelDir, "${info.fileName}.tmp")

        val deleted = (if (file.exists()) file.delete() else true) &&
                      (if (tempFile.exists()) tempFile.delete() else true)

        if (deleted) {
            DebugLog.i("ModelDownloadManager", "Model deleted: $modelId")
        }
        return deleted
    }

    fun getAllStatuses(): Map<String, DownloadStatus> {
        return AvailableLocalModels.MODELS.associate { model ->
            when {
                activeDownloads.containsKey(model.id) -> model.id to DownloadStatus.DOWNLOADING
                isModelComplete(model) -> model.id to DownloadStatus.COMPLETED
                isPartialDownload(model) -> model.id to DownloadStatus.PAUSED
                else -> model.id to DownloadStatus.IDLE
            }
        }
    }

    fun getDownloadedModels(): List<LocalModelInfo> {
        return AvailableLocalModels.MODELS.filter { isModelComplete(it) }
    }

    fun getTotalUsedSpaceMb(): Long {
        return modelDir.listFiles()?.sumOf { it.length() }?.div(1024 * 1024) ?: 0
    }

    fun release() {
        activeDownloads.values.forEach { it.cancel() }
        activeDownloads.clear()
        scope.cancel()
    }

    private suspend fun doDownload(modelInfo: LocalModelInfo, flow: MutableSharedFlow<DownloadProgress>) {
        ModelDownloadService.start(context, modelInfo.displayName)

        val outputFile = File(modelDir, modelInfo.fileName)
        val tempFile = File(modelDir, "${modelInfo.fileName}.tmp")

        if (outputFile.exists() && outputFile.length() >= modelInfo.sizeMb * 1024 * 1024 * 0.95) {
            emitProgress(flow, modelInfo.id, DownloadProgress(
                modelId = modelInfo.id,
                status = DownloadStatus.COMPLETED,
                downloadedBytes = outputFile.length(),
                totalBytes = modelInfo.sizeMb * 1024 * 1024,
                filePath = outputFile.absolutePath,
            ))
            return
        }

        val existingLength = if (tempFile.exists()) tempFile.length() else 0L
        var downloadedSoFar = existingLength

        emitProgress(flow, modelInfo.id, DownloadProgress(
                modelId = modelInfo.id,
                status = DownloadStatus.DOWNLOADING,
                downloadedBytes = downloadedSoFar,
                totalBytes = modelInfo.sizeMb * 1024 * 1024,
            ))

        val request = Request.Builder()
            .url(modelInfo.downloadUrl)
            .apply {
                if (existingLength > 0) addHeader("Range", "bytes=$existingLength-")
            }
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 206) {
                throw Exception("HTTP ${response.code}: ${response.message}")
            }

            val body = response.body ?: throw Exception("Empty response body")
            val contentLength = body.contentLength()
            val totalBytes = if (contentLength > 0) existingLength + contentLength else modelInfo.sizeMb * 1024 * 1024

            tempFile.sink().buffer().use { sink ->
                val source = body.source()
                val buffer = ByteArray(8192)
                var lastEmitTime = 0L
                var lastEmittedBytes = downloadedSoFar

                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = source.read(buffer)
                    if (read == -1) break

                    sink.write(buffer, 0, read)
                    downloadedSoFar += read

                    val now = System.currentTimeMillis()
                    if (now - lastEmitTime >= 300 || downloadedSoFar == totalBytes.toLong()) {
                        val speed = if (now - lastEmitTime > 0) {
                            (downloadedSoFar - lastEmittedBytes) * 1000 / (now - lastEmitTime)
                        } else 0

                        val remaining = if (speed > 0) (totalBytes - downloadedSoFar) / speed else 0

                        emitProgress(flow, modelInfo.id, DownloadProgress(
                            modelId = modelInfo.id,
                            status = DownloadStatus.DOWNLOADING,
                            downloadedBytes = downloadedSoFar,
                            totalBytes = totalBytes,
                            speedBytesPerSec = speed,
                            remainingTimeSec = remaining,
                        ))

                        ModelDownloadService.updateProgress(context, DownloadProgress(
                            modelId = modelInfo.id,
                            status = DownloadStatus.DOWNLOADING,
                            downloadedBytes = downloadedSoFar,
                            totalBytes = totalBytes,
                            speedBytesPerSec = speed
                        ))

                        lastEmitTime = now
                        lastEmittedBytes = downloadedSoFar
                    }
                }
            }

            tempFile.renameTo(outputFile)

            emitProgress(flow, modelInfo.id, DownloadProgress(
                modelId = modelInfo.id,
                status = DownloadStatus.COMPLETED,
                downloadedBytes = outputFile.length(),
                totalBytes = totalBytes,
                filePath = outputFile.absolutePath,
            ))

            ModelDownloadService.stop(context)

            DebugLog.i("ModelDownloadManager", "Download complete: ${modelInfo.displayName} (${outputFile.length() / 1024 / 1024}MB)")
        }
    }

    private suspend fun emitProgress(flow: MutableSharedFlow<DownloadProgress>, modelId: String, progress: DownloadProgress) {
        flow.emit(progress)
    }

    private fun isModelComplete(model: LocalModelInfo): Boolean {
        val file = File(modelDir, model.fileName)
        return file.exists() && file.length() >= model.sizeMb * 1024 * 1024 * 0.9
    }

    private fun isPartialDownload(model: LocalModelInfo): Boolean {
        val tempFile = File(modelDir, "${model.fileName}.tmp")
        return tempFile.exists() && tempFile.length() > 0
    }
}
