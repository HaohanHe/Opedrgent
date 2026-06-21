package top.hsyscn.opedrgent.storage

import java.util.UUID

/**
 * 知识库中的一个文档
 *
 * @param sourceLastModified 源文件最后修改时间戳 (用于增量同步变更检测)
 * @param sourceSize 源文件大小 (字节, 用于快速变更检测)
 * @param contentHash 解析内容的 SHA-256 哈希前 16 字符 (用于检测内容实际变化)
 * @param cloudFileId 阶跃云端文件 ID (向量存储同步用, null 表示未上传)
 * @param syncStatus 云端同步状态
 * @param lastSyncedAt 上次成功同步时间戳
 */
data class KbDocument(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val fileName: String,
    val fileType: String,
    val fileSizeBytes: Long,
    val contentLength: Int,
    val addedAtMs: Long,
    val content: String = "",
    val knowledgeBaseId: String = "default",
    val tags: List<String> = emptyList(),
    val sourceUri: String? = null,
    val sourceLastModified: Long = 0L,
    val sourceSize: Long = 0L,
    val contentHash: String = "",
    val cloudFileId: String? = null,
    val syncStatus: SyncStatus = SyncStatus.LOCAL_ONLY,
    val lastSyncedAt: Long = 0L,
)

/**
 * 文档云端同步状态
 */
enum class SyncStatus(val label: String) {
    /** 仅本地, 未上传云端 */
    LOCAL_ONLY("仅本地"),
    /** 已与云端同步 */
    SYNCED("已同步"),
    /** 待同步 (内容已变更, 等待上传) */
    PENDING("待同步"),
    /** 同步失败 */
    FAILED("同步失败"),
}

/**
 * 添加文件的结果
 */
data class KbAddResult(
    val success: Boolean,
    val document: KbDocument? = null,
    val error: String? = null,
) {
    companion object {
        fun success(doc: KbDocument) = KbAddResult(success = true, document = doc)
        fun error(msg: String) = KbAddResult(success = false, error = msg)
    }
}

/**
 * 知识库统计信息
 */
data class KbStats(
    val documentCount: Int,
    val totalFileSizeBytes: Long,
    val totalContentChars: Long,
    val fileTypes: Map<String, Int>,
)
