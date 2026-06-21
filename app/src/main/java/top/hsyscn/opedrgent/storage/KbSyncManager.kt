package top.hsyscn.opedrgent.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.File

/**
 * 知识库增量同步管理器
 *
 * 负责两阶段同步:
 *
 * ## 阶段 1: 本地重解析 (Local Re-parse)
 * 扫描所有有本地源文件的文档, 通过 lastModified + size 快速检测变更,
 * 重新解析文件内容并更新数据库。内容哈希变化时标记为 PENDING。
 *
 * ## 阶段 2: 云端向量存储同步 (Cloud Vector Store Sync)
 * 将 PENDING/FAILED 状态的文档上传到阶跃云端向量存储,
 * 成功后标记为 SYNCED。
 *
 * ## 使用场景
 * - App 启动时自动扫描本地变更 (轻量, 仅文件元数据比较)
 * - 用户手动触发完整同步 (本地 + 云端)
 * - 定时任务 (WorkManager) 后台同步
 *
 * @param knowledgeBase 知识库实例
 */
class KbSyncManager(
    private val knowledgeBase: KnowledgeBase,
) {

    companion object {
        private const val TAG = "KbSyncManager"
    }

    private val _progress = MutableSharedFlow<SyncProgress>(extraBufferCapacity = 16)
    /** 同步进度事件流 */
    val progress: SharedFlow<SyncProgress> = _progress.asSharedFlow()

    // ---- 完整同步 ----

    /**
     * 执行完整同步: 本地重解析 + 云端上传。
     *
     * @param cloudApiKey 阶跃 API Key (为空则跳过云端同步)
     * @param cloudStoreId 目标向量存储 ID (为空则跳过云端同步)
     * @return 同步汇总结果
     */
    suspend fun syncAll(cloudApiKey: String? = null, cloudStoreId: String? = null): SyncSummary {
        DebugLog.i(TAG, "开始完整知识库同步 (cloud=${!cloudApiKey.isNullOrBlank() && !cloudStoreId.isNullOrBlank()})")
        _progress.tryEmit(SyncProgress.StageChange(SyncStage.STARTED, "开始同步"))

        // 阶段 1: 本地重解析
        val localResult = syncLocalChanges()

        // 阶段 2: 云端同步
        val cloudResult = if (!cloudApiKey.isNullOrBlank() && !cloudStoreId.isNullOrBlank()) {
            syncToCloud(cloudApiKey, cloudStoreId)
        } else {
            DebugLog.i(TAG, "跳过云端同步 (未提供 apiKey 或 storeId)")
            CloudSyncResult(0, 0, 0)
        }

        val summary = SyncSummary(
            scannedCount = localResult.scannedCount,
            reparsedCount = localResult.reparsedCount,
            contentChangedCount = localResult.contentChangedCount,
            failedReparseCount = localResult.failedCount,
            cloudUploadedCount = cloudResult.uploadedCount,
            cloudFailedCount = cloudResult.failedCount,
            cloudSkippedCount = cloudResult.skippedCount,
        )
        _progress.tryEmit(SyncProgress.StageChange(SyncStage.COMPLETED, "同步完成: ${summary.reparsedCount} 重解析, ${summary.cloudUploadedCount} 上传云端"))
        DebugLog.i(TAG, "同步完成: $summary")
        return summary
    }

    // ---- 阶段 1: 本地重解析 ----

    /**
     * 扫描本地源文件变更并重新解析。
     *
     * 仅处理 sourceUri 为本地文件路径的文档 (content:// URI 无法检测变更)。
     *
     * @param cloudApiKey 本地解析失败时的云端回退 API Key (可选)
     * @return 本地同步结果
     */
    suspend fun syncLocalChanges(cloudApiKey: String? = null): LocalSyncResult {
        return withContext(Dispatchers.IO) {
            val changedDocs = knowledgeBase.scanForChangedDocuments()
            DebugLog.i(TAG, "本地变更扫描: 发现 ${changedDocs.size} 个文档源文件已变更")

            if (changedDocs.isEmpty()) {
                return@withContext LocalSyncResult(scannedCount = 0)
            }

            _progress.tryEmit(SyncProgress.StageChange(SyncStage.LOCAL_SCANNING, "发现 ${changedDocs.size} 个文件变更"))

            var reparsed = 0
            var contentChanged = 0
            var failed = 0

            changedDocs.forEachIndexed { index, doc ->
                _progress.tryEmit(SyncProgress.Item(
                    stage = SyncStage.LOCAL_REPARSING,
                    current = index + 1,
                    total = changedDocs.size,
                    message = "重新解析: ${doc.title}",
                ))

                val result = knowledgeBase.reparseDocument(doc.id, cloudApiKey)
                if (result.success) {
                    reparsed++
                    if (result.contentChanged) contentChanged++
                } else {
                    failed++
                    DebugLog.w(TAG, "重新解析失败: ${doc.title} -> ${result.message}")
                }
            }

            _progress.tryEmit(SyncProgress.StageChange(SyncStage.LOCAL_DONE, "本地重解析完成: $reparsed/${changedDocs.size}"))
            LocalSyncResult(
                scannedCount = changedDocs.size,
                reparsedCount = reparsed,
                contentChangedCount = contentChanged,
                failedCount = failed,
            )
        }
    }

    // ---- 阶段 2: 云端向量存储同步 ----

    /**
     * 将待同步文档上传到阶跃云端向量存储。
     *
     * 处理 PENDING 和 FAILED 状态的文档, 上传成功后标记为 SYNCED。
     *
     * @param apiKey 阶跃 API Key
     * @param storeId 目标向量存储 ID
     * @return 云端同步结果
     */
    suspend fun syncToCloud(apiKey: String, storeId: String): CloudSyncResult {
        return withContext(Dispatchers.IO) {
            val pendingDocs = knowledgeBase.getDocumentsNeedingSync()
            DebugLog.i(TAG, "云端同步: ${pendingDocs.size} 个文档待上传")

            if (pendingDocs.isEmpty()) {
                return@withContext CloudSyncResult(0, 0, 0)
            }

            _progress.tryEmit(SyncProgress.StageChange(SyncStage.CLOUD_UPLOADING, "上传 ${pendingDocs.size} 个文档到云端"))

            var uploaded = 0
            var failed = 0
            var skipped = 0

            pendingDocs.forEachIndexed { index, doc ->
                _progress.tryEmit(SyncProgress.Item(
                    stage = SyncStage.CLOUD_UPLOADING,
                    current = index + 1,
                    total = pendingDocs.size,
                    message = "上传: ${doc.title}",
                ))

                val sourcePath = doc.sourceUri
                // 仅本地文件可上传; content:// URI 需要先复制到临时文件
                if (sourcePath.isNullOrBlank() || sourcePath.startsWith("content://")) {
                    // 尝试将内容写入临时文件再上传
                    val tempResult = uploadContentAsTempFile(apiKey, storeId, doc)
                    if (tempResult) {
                        uploaded++
                    } else {
                        failed++
                        knowledgeBase.updateSyncStatus(doc.id, SyncStatus.FAILED)
                    }
                    return@forEachIndexed
                }

                val file = File(sourcePath)
                if (!file.exists()) {
                    DebugLog.w(TAG, "云端同步跳过 (源文件不存在): ${doc.title}")
                    skipped++
                    return@forEachIndexed
                }

                val result = StepVectorStoreClient.uploadFileForRetrieval(
                    apiKey = apiKey,
                    filePath = sourcePath,
                    storeId = storeId,
                )

                if (result.success && !result.fileId.isNullOrBlank()) {
                    uploaded++
                    knowledgeBase.updateSyncStatus(doc.id, SyncStatus.SYNCED, result.fileId)
                    DebugLog.i(TAG, "云端上传成功: ${doc.title} -> ${result.fileId}")
                } else {
                    failed++
                    knowledgeBase.updateSyncStatus(doc.id, SyncStatus.FAILED)
                    DebugLog.w(TAG, "云端上传失败: ${doc.title} -> ${result.message}")
                }
            }

            _progress.tryEmit(SyncProgress.StageChange(SyncStage.CLOUD_DONE, "云端同步完成: $uploaded 上传, $failed 失败"))
            CloudSyncResult(
                uploadedCount = uploaded,
                failedCount = failed,
                skippedCount = skipped,
            )
        }
    }

    /**
     * 将文档内容写入临时文件后上传到云端 (用于无源文件路径的文档)。
     */
    private suspend fun uploadContentAsTempFile(
        apiKey: String,
        storeId: String,
        doc: KbDocument,
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                if (doc.content.isBlank()) return@withContext false
                val tempFile = File.createTempFile("kb_sync_${doc.id}_", ".txt")
                try {
                    tempFile.writeText(doc.content)
                    val result = StepVectorStoreClient.uploadFileForRetrieval(
                        apiKey = apiKey,
                        filePath = tempFile.absolutePath,
                        storeId = storeId,
                    )
                    if (result.success && !result.fileId.isNullOrBlank()) {
                        knowledgeBase.updateSyncStatus(doc.id, SyncStatus.SYNCED, result.fileId)
                        DebugLog.i(TAG, "云端上传成功 (临时文件): ${doc.title} -> ${result.fileId}")
                        true
                    } else {
                        DebugLog.w(TAG, "云端上传失败 (临时文件): ${doc.title} -> ${result.message}")
                        false
                    }
                } finally {
                    tempFile.delete()
                }
            } catch (e: Exception) {
                DebugLog.e(TAG, "临时文件上传异常: ${doc.title} -> ${e.message}", e)
                false
            }
        }
    }

    // ---- 同步状态查询 ----

    /**
     * 获取同步状态统计。
     */
    fun getSyncStats(): SyncStats {
        val allDocs = knowledgeBase.getAllDocuments()
        return SyncStats(
            total = allDocs.size,
            synced = allDocs.count { it.syncStatus == SyncStatus.SYNCED },
            pending = allDocs.count { it.syncStatus == SyncStatus.PENDING },
            failed = allDocs.count { it.syncStatus == SyncStatus.FAILED },
            localOnly = allDocs.count { it.syncStatus == SyncStatus.LOCAL_ONLY },
        )
    }

    // ---- 数据模型 ----

    /** 同步阶段 */
    enum class SyncStage {
        STARTED, LOCAL_SCANNING, LOCAL_REPARSING, LOCAL_DONE,
        CLOUD_UPLOADING, CLOUD_DONE, COMPLETED,
    }

    /** 同步进度事件 */
    sealed class SyncProgress {
        /** 阶段切换 */
        data class StageChange(val stage: SyncStage, val message: String) : SyncProgress()
        /** 单项处理进度 */
        data class Item(val stage: SyncStage, val current: Int, val total: Int, val message: String) : SyncProgress()
    }

    /** 本地同步结果 */
    data class LocalSyncResult(
        val scannedCount: Int,
        val reparsedCount: Int = 0,
        val contentChangedCount: Int = 0,
        val failedCount: Int = 0,
    )

    /** 云端同步结果 */
    data class CloudSyncResult(
        val uploadedCount: Int,
        val failedCount: Int,
        val skippedCount: Int,
    )

    /** 完整同步汇总 */
    data class SyncSummary(
        val scannedCount: Int,
        val reparsedCount: Int,
        val contentChangedCount: Int,
        val failedReparseCount: Int,
        val cloudUploadedCount: Int,
        val cloudFailedCount: Int,
        val cloudSkippedCount: Int,
    ) {
        /** 是否有实际变更发生 */
        val hasChanges: Boolean get() = reparsedCount > 0 || cloudUploadedCount > 0
    }

    /** 同步状态统计 */
    data class SyncStats(
        val total: Int,
        val synced: Int,
        val pending: Int,
        val failed: Int,
        val localOnly: Int,
    )
}

