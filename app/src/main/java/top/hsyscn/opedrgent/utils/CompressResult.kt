package top.hsyscn.opedrgent.utils

import top.hsyscn.opedrgent.model.ChatMessage

/**
 * 压缩结果。包含预处理后的消息列表（已注入摘要、已剪枝）和摘要文本。
 *
 * @param messages 预处理后的消息列表，可直接传给 LLM
 * @param summary 生成的摘要文本（null 表示无需压缩）
 * @param tokenCount 估算的总 token 数
 * @param usageRatio token 使用率
 */
data class CompressResult(
    val messages: List<ChatMessage>,
    val summary: String?,
    val tokenCount: Int = 0,
    val usageRatio: Float = 0f,
) {
    val needsCompression: Boolean get() = usageRatio >= 0.90f
    val isCritical: Boolean get() = usageRatio >= 0.95f
}

/** @deprecated 使用 [CompressResult] */
typealias CompressedMessages = CompressResult
