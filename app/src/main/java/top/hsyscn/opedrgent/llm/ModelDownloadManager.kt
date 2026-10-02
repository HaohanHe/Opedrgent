package top.hsyscn.opedrgent.llm

import android.content.Context
import top.hsyscn.opedrgent.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.appendingSink
import okio.buffer
import okio.sink
import top.hsyscn.opedrgent.network.HttpClients
import top.hsyscn.opedrgent.utils.DebugLog
import top.hsyscn.opedrgent.service.ModelDownloadService
import top.hsyscn.opedrgent.utils.FileHasher
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

private const val MAX_DOWNLOAD_RETRIES = 3

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

class ModelDownloadManager private constructor(private val context: Context) {

    companion object {
        @Volatile private var instance: ModelDownloadManager? = null

        fun getInstance(context: Context): ModelDownloadManager =
            instance ?: synchronized(this) {
                instance ?: create(context.applicationContext).also { instance = it }
            }

        private fun create(appContext: Context): ModelDownloadManager = ModelDownloadManager(appContext)
    }

    private val httpClient = HttpClients.download.newBuilder()
        .followRedirects(true)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val activeDownloads = ConcurrentHashMap<String, Job>()
    private val progressFlows = ConcurrentHashMap<String, MutableSharedFlow<DownloadProgress>>()

    private val inventory = LocalModelInventory(context)

    private val modelDir: File by lazy {
        val externalDir = context.getExternalFilesDir(null)
        if (externalDir != null) {
            File(externalDir, "local_models").also { if (!it.exists()) it.mkdirs() }
        } else {
            File(context.filesDir, "local_models").also { if (!it.exists()) it.mkdirs() }
        }
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
                var lastError: Throwable? = null
                val urls = listOfNotNull(modelInfo.downloadUrl, modelInfo.fallbackUrl)

                for ((urlIndex, downloadUrl) in urls.withIndex()) {
                    if (urlIndex > 0) {
                        DebugLog.w("ModelDownloadManager", "Switching to fallback URL for ${modelInfo.id}")
                        emitProgress(flow, modelInfo.id, DownloadProgress(
                            modelId = modelInfo.id,
                            status = DownloadStatus.DOWNLOADING,
                            error = context.getString(R.string.error_download_switching_source)
                        ))
                        // 不主动删除临时文件，交给 doDownload 根据响应码决定：
                        // 若 fallback 支持 206 断点续传则追加；返回 200 则自动清空重下
                        delay(1000)
                    }

                    repeat(MAX_DOWNLOAD_RETRIES) { attempt ->
                        if (attempt > 0) {
                            DebugLog.w("ModelDownloadManager", "Retrying download ${modelInfo.id} (attempt ${attempt + 1}/$MAX_DOWNLOAD_RETRIES)")
                            emitProgress(flow, modelInfo.id, DownloadProgress(
                                modelId = modelInfo.id,
                                status = DownloadStatus.DOWNLOADING,
                                error = context.getString(R.string.error_download_retrying, attempt + 1, MAX_DOWNLOAD_RETRIES)
                            ))
                            delay((attempt * 2000L).coerceAtMost(5000L))
                        }

                        runCatching {
                            doDownload(modelInfo, downloadUrl, flow)
                            return@launch
                        }.onFailure { e ->
                            lastError = e
                            if (e is CancellationException) {
                                throw e
                            }
                            if (e is IOException || e is SocketTimeoutException) {
                                DebugLog.w("ModelDownloadManager", "Download attempt ${attempt + 1} failed for ${modelInfo.id} from ${downloadUrl}: ${e.message}")
                            } else {
                                // 非网络类错误（如 HTTP 4xx、文件校验失败）不重试当前源，尝试下一个源
                                DebugLog.w("ModelDownloadManager", "Non-network error for ${modelInfo.id} from ${downloadUrl}: ${e.message}")
                                return@repeat
                            }
                        }
                    }
                }
                lastError?.let { throw it }
            }.onFailure { e ->
                if (e is CancellationException) {
                    emitProgress(flow, modelInfo.id, DownloadProgress(
                        modelId = modelInfo.id,
                        status = DownloadStatus.CANCELLED,
                        error = context.getString(R.string.error_user_cancelled)
                    ))
                } else {
                    emitProgress(flow, modelInfo.id, DownloadProgress(
                        modelId = modelInfo.id,
                        status = DownloadStatus.FAILED,
                        error = e.message ?: context.getString(R.string.error_unknown_error)
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

    fun pauseDownload(modelId: String) {
        activeDownloads[modelId]?.cancel()
        activeDownloads.remove(modelId)

        // 暂停不清除临时文件，保留已下载进度用于断点续传
        DebugLog.i("ModelDownloadManager", "Download paused: $modelId")
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
            inventory.remove(modelId)
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

    /** 返回某模型的最终落地文件（可能不存在）；仅供就绪状态读取 filePath。 */
    fun getModelFile(modelId: String): File? {
        val info = AvailableLocalModels.findById(modelId) ?: return null
        return File(modelDir, info.fileName)
    }

    /** 返回 .tmp 临时文件已下载字节数（用于暂停/未完成时展示可断点续传的进度）。 */
    fun getPartialBytes(modelId: String): Long {
        val info = AvailableLocalModels.findById(modelId) ?: return 0L
        val tmp = File(modelDir, "${info.fileName}.tmp")
        return if (tmp.exists()) tmp.length() else 0L
    }

    fun getTotalUsedSpaceMb(): Long {
        return modelDir.listFiles()?.sumOf { it.length() }?.div(1024 * 1024) ?: 0
    }

    /**
     * 已废弃为「关闭本实例」语义：本类现为进程级单例，随进程存活。
     *
     * 共享的 scope / activeDownloads / progressFlows 不应被任一调用方单点关闭，
     * 否则其它调用方将永久失效。现保留方法签名仅为兼容历史调用，不再取消共享 scope、
     * 不清空活动下载表，实际为空操作；如需取消全部活动下载请改用 [cancelAll]。
     */
    fun release() {
        // no-op：进程级单例随进程存活，不应被单点释放。
    }

    /**
     * 取消本实例全部正在进行的下载（复用 [cancelDownload]，会丢弃对应 .tmp 断点临时文件）。
     * 仅清空活动任务、不取消 scope —— 供仍需复用本实例、只想取消全部下载时使用。
     */
    fun cancelAll() {
        activeDownloads.keys.toList().forEach { cancelDownload(it) }
    }

    /**
     * 已废弃为「彻底释放本实例资源」语义：本类现为进程级单例，随进程存活。
     *
     * 历史上供 throwaway 实例在用完后关闭自建 scope；收敛为单例后，scope 为全局共享资源，
     * 任一调用方都不得取消它（否则所有后续下载永久失效），也不应清空共享的 progressFlows。
     *
     * 现保留方法签名，仅委托 [cancelAll] 取消全部活动下载（全局生效，符合预期）；
     * 不取消共享 scope、不清空进度流。真正的全局资源由进程生命周期托管。
     */
    fun close() {
        cancelAll()
    }

    private suspend fun doDownload(modelInfo: LocalModelInfo, downloadUrl: String, flow: MutableSharedFlow<DownloadProgress>) {
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

        val requiredBytes = modelInfo.sizeMb * 1024L * 1024L
        val availableBytes = modelDir.freeSpace
        if (availableBytes < requiredBytes) {
            throw IOException(
                context.getString(
                    R.string.error_insufficient_storage,
                    availableBytes / (1024 * 1024),
                    requiredBytes / (1024 * 1024)
                )
            )
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
            .url(downloadUrl)
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

            // 206 表示服务器支持断点续传；200 等表示返回完整内容，需要从头开始
            val supportsResume = response.code == 206
            DebugLog.i("ModelDownloadManager", "Download response for ${modelInfo.id}: code=${response.code}, existingLength=$existingLength, supportsResume=$supportsResume")
            val startBytes = if (supportsResume) {
                existingLength
            } else {
                if (tempFile.exists()) {
                    DebugLog.w("ModelDownloadManager", "Server ignored Range for ${modelInfo.id}, clearing ${tempFile.length()} bytes temp file")
                    tempFile.delete()
                }
                0L
            }
            val totalBytes = if (contentLength > 0) {
                if (supportsResume) existingLength + contentLength else contentLength
            } else {
                modelInfo.sizeMb * 1024 * 1024
            }

            downloadedSoFar = startBytes

            emitProgress(flow, modelInfo.id, DownloadProgress(
                modelId = modelInfo.id,
                status = DownloadStatus.DOWNLOADING,
                downloadedBytes = downloadedSoFar,
                totalBytes = totalBytes,
            ))

            val sink = if (supportsResume) tempFile.appendingSink().buffer() else tempFile.sink().buffer()
            sink.use {
                val source = body.source()
                val buffer = ByteArray(8192)
                var lastEmitTime = 0L
                var lastEmittedBytes = downloadedSoFar

                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = source.read(buffer)
                    if (read == -1) break

                    it.write(buffer, 0, read)
                    downloadedSoFar += read

                    val now = System.currentTimeMillis()
                    if (now - lastEmitTime >= 300 || downloadedSoFar == totalBytes) {
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

            if (outputFile.exists()) outputFile.delete()
            if (!tempFile.renameTo(outputFile)) {
                throw IOException("Failed to rename temp file to ${outputFile.absolutePath}")
            }

            if (!outputFile.exists() || outputFile.length() < modelInfo.sizeMb * 1024 * 1024 * 0.9) {
                throw IOException("Downloaded file size mismatch: ${outputFile.length()} bytes")
            }

            // 体积校验通过后，若配置了可信上游 SHA-256 则做完整哈希校验；
            // 不一致视为下载失败（不标记完成、不发 COMPLETED，可重试）。为空则仅维持上述体积校验。
            if (modelInfo.expectedSha256 != null &&
                !FileHasher.matchesSha256(outputFile, modelInfo.expectedSha256)
            ) {
                throw Exception("Downloaded file SHA-256 mismatch: ${modelInfo.id}")
            }

            // 下载落定（rename + 体积/可选 SHA 校验通过）后记录每模型本地元信息。
            // verified 口径：配置了可信上游哈希且已通过该校验；无上游哈希时如实记为 false。
            inventory.recordDownloaded(modelInfo.id, modelInfo.expectedSha256 != null)

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

