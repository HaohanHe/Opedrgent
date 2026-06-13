package top.hsyscn.opedrgent.utils

/**
 * 流式消息缓冲器 — 节流 UI 更新，减少 Compose 重组频率
 *
 * 学自 GPT Mobile 的 StreamingMessageBuffer 模式：
 * - 缓存 incoming stream chunks
 * - 每 50ms 才发布一次到 UI
 * - stream 结束时 flush 剩余内容
 */
class StreamingMessageBuffer(
    private val publishIntervalMs: Long = 50L,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private val content = StringBuilder()
    private var lastPublishedAtMs = 0L
    private var publishedLength = 0

    fun append(chunk: String) {
        if (chunk.isNotEmpty()) {
            content.append(chunk)
        }
    }

    /**
     * 如果有未发布的内容且距上次发布超过间隔，返回当前累积文本。
     * 否则返回 null。
     */
    fun pollIfDue(): String? {
        if (content.length == publishedLength) return null
        val now = clockMs()
        if (lastPublishedAtMs == 0L || now - lastPublishedAtMs >= publishIntervalMs) {
            return publish(now)
        }
        return null
    }

    /**
     * 强制返回当前累积文本（无论是否到间隔）。
     */
    fun flush(): String? {
        if (content.length == publishedLength) return null
        return publish(clockMs())
    }

    fun currentText(): String = content.toString()

    fun reset() {
        content.clear()
        lastPublishedAtMs = 0L
        publishedLength = 0
    }

    private fun publish(now: Long): String {
        publishedLength = content.length
        lastPublishedAtMs = now
        return content.toString()
    }
}
