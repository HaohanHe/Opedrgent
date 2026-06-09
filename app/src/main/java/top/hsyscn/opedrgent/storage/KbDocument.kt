package top.hsyscn.opedrgent.storage

import java.util.UUID

/**
 * 知识库中的一个文档
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
)

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
