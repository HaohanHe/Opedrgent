package top.hsyscn.opedrgent.sync

/**
 * WebDAV 连接配置。
 */
data class WebDavConfig(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val remotePath: String = "/opedrgent/notes/",
) {
    val isEnabled: Boolean get() = serverUrl.isNotBlank()

    /**
     * 解析远端路径为完整 URL。
     */
    fun resolveUrl(path: String): String {
        val base = serverUrl.trimEnd('/')
        val cleanPath = if (path.startsWith("/")) path else "/$path"
        return "$base$cleanPath"
    }
}
