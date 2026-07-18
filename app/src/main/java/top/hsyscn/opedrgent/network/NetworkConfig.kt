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

    // WebDAV 同步客户端超时
    const val WEBDAV_CONNECT_TIMEOUT_SECONDS = 15L
    const val WEBDAV_READ_TIMEOUT_SECONDS = 30L
    const val WEBDAV_WRITE_TIMEOUT_SECONDS = 30L

    // OCR 模型下载客户端超时
    const val OCR_MODEL_DOWNLOAD_CONNECT_TIMEOUT_SECONDS = 30L
    const val OCR_MODEL_DOWNLOAD_READ_TIMEOUT_SECONDS = 120L
    const val OCR_MODEL_DOWNLOAD_WRITE_TIMEOUT_SECONDS = 30L

    // ASR 后处理器 API 客户端超时
    const val ASR_POST_PROCESSOR_CONNECT_TIMEOUT_SECONDS = 10L
    const val ASR_POST_PROCESSOR_READ_TIMEOUT_SECONDS = 30L

    // STT 模型下载客户端超时
    const val STT_MODEL_DOWNLOAD_CONNECT_TIMEOUT_SECONDS = 30L
    const val STT_MODEL_DOWNLOAD_READ_TIMEOUT_SECONDS = 120L
    const val STT_MODEL_DOWNLOAD_WRITE_TIMEOUT_SECONDS = 30L

    // === SkillLoader 技能加载/更新 ===
    const val SKILL_LOAD_CONNECT_TIMEOUT_SECONDS = 15L
    const val SKILL_LOAD_READ_TIMEOUT_SECONDS = 30L

    const val SKILL_UPDATE_CONNECT_TIMEOUT_SECONDS = 10L
    const val SKILL_UPDATE_READ_TIMEOUT_SECONDS = 15L

    // === StepImageGenTool 图片生成 ===
    const val IMAGE_GEN_CONNECT_TIMEOUT_SECONDS = 30L
    const val IMAGE_GEN_READ_TIMEOUT_SECONDS = 120L
    const val IMAGE_GEN_WRITE_TIMEOUT_SECONDS = 60L

    // === ReadUrlTool URL 读取 ===
    const val READ_URL_TIMEOUT_MS = 15_000L

    // === TlsFingerprintManager TLS 指纹 ===
    const val TLS_CONNECT_TIMEOUT_SECONDS = 15L
    const val TLS_READ_TIMEOUT_SECONDS = 30L
    const val TLS_WRITE_TIMEOUT_SECONDS = 30L
    const val TLS_CALL_TIMEOUT_SECONDS = 60L

    // === 阶跃星辰 StepRealtime 实时语音 WebSocket 客户端 ===
    const val REALTIME_CONNECT_TIMEOUT_SECONDS = 10L
    const val REALTIME_READ_TIMEOUT_MINUTES = 0L         // WebSocket 长连接，不设读超时
    const val REALTIME_WRITE_TIMEOUT_SECONDS = 30L
    const val REALTIME_PING_INTERVAL_SECONDS = 20L

    // === 发芽服务 LLM 调用超时 ===
    const val SPROUT_CONNECT_TIMEOUT_SECONDS = 30L
    const val SPROUT_READ_TIMEOUT_SECONDS = 600L         // 10 分钟
    const val SPROUT_WRITE_TIMEOUT_SECONDS = 30L

    // === 地图瓦片下载超时 ===
    const val MAP_TILE_CONNECT_TIMEOUT_SECONDS = 8L
    const val MAP_TILE_READ_TIMEOUT_SECONDS = 8L
    const val MAP_TILE_CALL_TIMEOUT_SECONDS = 15L
}
