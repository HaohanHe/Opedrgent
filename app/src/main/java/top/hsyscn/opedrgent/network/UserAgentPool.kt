package top.hsyscn.opedrgent.network

import kotlin.random.Random

/**
 * User-Agent 池管理器（单例）
 *
 * 提供三类主流浏览器的真实 UA 模板随机生成能力，
 * 支持固定 UA 缓存以保持 DDG vqd 等场景的会话一致性。
 */
object UserAgentPool {

    // ======================== OS 列表 ========================
    private val OS_LIST = listOf(
        "Windows NT 10.0; Win64; x64",
        "Macintosh; Intel Mac OS X 10_15_7",
        "X11; Linux x86_64",
        "Linux; Android 14; Pixel 8 Pro"
    )

    // ======================== 浏览器版本号 ========================
    private val CHROME_VERSIONS = listOf("132.0.6834.160", "133.0.6943.99", "134.0.6998.89")
    private val FIREFOX_VERSIONS = listOf("130.0.1", "131.0.3", "132.0.1")
    private val EDGE_VERSIONS = listOf("132.0.2957.104", "133.0.3065.72", "134.0.0.0")

    // ======================== UA 模板 ========================
    private const val CHROME_TEMPLATE =
        "Mozilla/5.0 ({os}) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/{ver} Safari/537.36"

    private const val FIREFOX_TEMPLATE =
        "Mozilla/5.0 ({os}; rv:{ver}) Gecko/20100101 Firefox/{ver}"

    private const val EDGE_TEMPLATE =
        "Mozilla/5.0 ({os}) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/{ver} Safari/537.36 Edg/{ver}"

    // ======================== 固定 UA 缓存 ========================
    @Volatile
    private var fixedUa: String? = null

    /**
     * 随机生成一个完整的 User-Agent 字符串。
     *
     * 随机选取操作系统 + 浏览器类型 + 版本号进行组合，
     * 返回符合真实浏览器格式的 UA 字符串。
     *
     * @return 完整的 User-Agent 字符串
     */
    fun generate(): String {
        val os = OS_LIST.random()
        return when (Random.nextInt(3)) {
            0 -> CHROME_TEMPLATE.replace("{os}", os).replace("{ver}", CHROME_VERSIONS.random())
            1 -> FIREFOX_TEMPLATE.replace("{os}", os).replace("{ver}", FIREFOX_VERSIONS.random())
            else -> EDGE_TEMPLATE.replace("{os}", os).replace("{ver}", EDGE_VERSIONS.random())
        }
    }

    /**
     * 获取固定的 User-Agent 字符串（线程安全懒加载）。
     *
     * 首次调用时生成一个随机 UA 并缓存，
     * 后续调用始终返回同一个值，用于保持 DDG vqd 等场景的会话一致性。
     *
     * @return 缓存的 User-Agent 字符串
     */
    fun getFixedUa(): String {
        return fixedUa ?: synchronized(this) {
            fixedUa ?: generate().also { fixedUa = it }
        }
    }

    /**
     * 重置固定 User-Agent 缓存。
     *
     * 下次调用 [getFixedUa] 时将重新生成新的随机 UA。
     */
    fun resetFixedUa() {
        synchronized(this) {
            fixedUa = null
        }
    }
}
