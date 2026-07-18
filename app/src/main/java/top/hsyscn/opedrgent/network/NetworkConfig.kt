package top.hsyscn.opedrgent.network

object NetworkConfig {
    const val CONNECT_TIMEOUT_SECONDS = 15L
    /**
     * 常规请求读取超时（default client）。
     * 注意：default client 的 callTimeout=60s 会先于此值触发，因此 60s 即可兜底常规请求。
     * LLM 流式/非流式请求应走 [HttpClients.streaming]（5min readTimeout + 10min callTimeout），
     * 长任务（TTS/ASR/工具执行）走 [HttpClients.longRunning]。
     */
    const val READ_TIMEOUT_SECONDS = 60L
    const val WRITE_TIMEOUT_SECONDS = 30L

    const val MAX_IDLE_CONNECTIONS = 5

    const val MAX_REQUESTS = 64
    const val MAX_REQUESTS_PER_HOST = 10

    const val RETRY_COUNT = 2

    // 快速请求客户端超时（quickTimeout）
    const val QUICK_CONNECT_TIMEOUT_SECONDS = 5L
    const val QUICK_READ_TIMEOUT_SECONDS = 10L
    const val QUICK_WRITE_TIMEOUT_SECONDS = 10L
    const val QUICK_CALL_TIMEOUT_SECONDS = 20L

    // 慢速请求客户端超时（longTimeout）
    const val LONG_CONNECT_TIMEOUT_SECONDS = 20L
    const val LONG_READ_TIMEOUT_SECONDS = 60L
    const val LONG_WRITE_TIMEOUT_SECONDS = 60L
    const val LONG_CALL_TIMEOUT_SECONDS = 120L

    // 流式响应客户端超时（streaming，SSE/LLM 流式）
    const val STREAMING_CONNECT_TIMEOUT_SECONDS = 10L
    const val STREAMING_READ_TIMEOUT_SECONDS = 300L    // 5 分钟
    const val STREAMING_WRITE_TIMEOUT_SECONDS = 30L
    const val STREAMING_CALL_TIMEOUT_SECONDS = 600L    // 10 分钟

    // 长时间运行客户端超时（longRunning，TTS/ASR/工具执行）
    const val LONG_RUNNING_CONNECT_TIMEOUT_SECONDS = 30L
    const val LONG_RUNNING_READ_TIMEOUT_SECONDS = 300L // 5 分钟
    const val LONG_RUNNING_WRITE_TIMEOUT_SECONDS = 60L
    const val LONG_RUNNING_CALL_TIMEOUT_SECONDS = 600L // 10 分钟

    // 大文件下载客户端超时（download）
    const val DOWNLOAD_CONNECT_TIMEOUT_SECONDS = 30L
    const val DOWNLOAD_READ_TIMEOUT_SECONDS = 1800L    // 30 分钟
    const val DOWNLOAD_WRITE_TIMEOUT_SECONDS = 60L
    // 下载不设置总调用超时，允许数小时的大文件下载

    // 默认客户端总调用超时兜底
    const val DEFAULT_CALL_TIMEOUT_SECONDS = 60L
}
