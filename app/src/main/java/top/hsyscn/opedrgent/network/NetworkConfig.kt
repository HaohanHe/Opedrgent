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
}
