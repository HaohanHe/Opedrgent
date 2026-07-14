package top.hsyscn.opedrgent.network

object NetworkConfig {
    const val CONNECT_TIMEOUT_SECONDS = 15L
    const val READ_TIMEOUT_SECONDS = 120L
    const val WRITE_TIMEOUT_SECONDS = 30L

    const val MAX_IDLE_CONNECTIONS = 5

    const val MAX_REQUESTS = 64
    const val MAX_REQUESTS_PER_HOST = 10

    const val RETRY_COUNT = 2
}
