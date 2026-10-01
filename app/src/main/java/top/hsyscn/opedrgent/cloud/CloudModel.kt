package top.hsyscn.opedrgent.cloud

/**
 * 某一云服务商下的区域/集群接入点。
 *
 * - [openAiBaseUrl]：OpenAI 兼容端点基地址（一般以 /v1 结尾）。
 * - [anthropicBaseUrl]：Anthropic 兼容端点基地址；该集群未提供时为 null。
 */
data class RegionalCluster(
    val id: String,
    val label: String,
    val openAiBaseUrl: String,
    val anthropicBaseUrl: String? = null,
)

/**
 * 云服务商能力位图。仅按已核实的官方事实如实置位，未确认的能力一律保持 false。
 */
data class CloudCapabilities(
    val chat: Boolean = false,
    val reasoning: Boolean = false,
    val asr: Boolean = false,
    val tts: Boolean = false,
    val imageUnderstanding: Boolean = false,
    val videoUnderstanding: Boolean = false,
    val imageGeneration: Boolean = false,
    val imageEdit: Boolean = false,
    val videoGeneration: Boolean = false,
    val rag: Boolean = false,
    val embeddings: Boolean = false,
    val webSearch: Boolean = false,
    val realtime: Boolean = false,
    val mobileAgent: Boolean = false,
)

/**
 * 统一云 provider 抽象。
 *
 * 注意：实现类只允许对「API Key 字段本身」做前缀判断（如 tp-/ttp-/sk-/AIza），
 * 严禁扫描用户对话或消息文本来推断 provider。
 */
interface CloudProvider {
    /** 稳定标识，用于持久化与日志，不随显示名变化 */
    val id: String

    /** 用户可见名称 */
    val displayName: String

    /** 主接入域名基地址（不含路径后缀，如 https://api.stepfun.com） */
    val baseUrl: String

    /** 能力位图 */
    val capabilities: CloudCapabilities

    /** 区域/集群列表；无区域划分时为空列表 */
    val regionalClusters: List<RegionalCluster>

    /**
     * 根据 API Key 生成鉴权请求头。
     * @return Pair(请求头名称, 请求头值)
     */
    fun authHeader(apiKey: String): Pair<String, String>

    /**
     * 该 provider 默认的 Anthropic 兼容基地址；不支持时返回 null。
     */
    fun anthropicBaseUrl(): String?
}
