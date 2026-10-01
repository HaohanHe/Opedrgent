package top.hsyscn.opedrgent.utils

import java.io.File
import java.security.MessageDigest

/**
 * 文件完整性哈希工具。
 *
 * 仅在调用方持有可信上游期望值（expectedSha256 / fileSha256）时使用；
 * 没有可信上游哈希时，调用方应保持 null 并沿用体积/存在性校验，禁止臆造期望值。
 */
object FileHasher {

    private const val BUFFER_SIZE = 8192

    /**
     * 计算文件的小写十六进制 SHA-256。
     *
     * @throws Exception 读取失败时抛出，由调用方决定是否判定为下载失败。
     */
    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 校验文件 SHA-256 是否与期望值一致。
     *
     * @param expected 小写或大写十六进制哈希；空串/空白视为“未提供”，返回 true（不校验）。
     * @return true 表示一致或未提供期望值；false 表示读取失败或哈希不匹配。
     */
    fun matchesSha256(file: File, expected: String?): Boolean {
        val normalized = expected?.trim()?.takeIf { it.isNotEmpty() } ?: return true
        return try {
            sha256Hex(file).equals(normalized, ignoreCase = true)
        } catch (e: Exception) {
            DebugLog.e("FileHasher", "Failed to hash ${file.absolutePath}: ${e.message}", e)
            false
        }
    }
}
