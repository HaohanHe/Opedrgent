package top.hsyscn.opedrgent.storage

import java.util.UUID

/**
 * 知识库信息（元数据）
 */
data class KnowledgeBaseInfo(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val visibility: Visibility = Visibility.PRIVATE,
    val documentCount: Int = 0,
    val totalSizeBytes: Long = 0L,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis(),
    val coverColor: String = "#4A90D9",
)

/**
 * 知识库可见性
 */
enum class Visibility(val label: String) {
    PRIVATE("私密"),
    PUBLIC("公开"),
    TEAM("团队"),
}
