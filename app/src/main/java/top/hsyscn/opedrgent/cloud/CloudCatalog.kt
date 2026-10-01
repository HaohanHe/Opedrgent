package top.hsyscn.opedrgent.cloud

/**
 * 阶跃星辰 StepFun。
 *
 * 已核实事实：
 * - 唯一域名 api.stepfun.com；按量通道 /v1，订阅通道 /step_plan/v1；
 * - 鉴权一律 Authorization: Bearer；
 * - Anthropic 兼容端点 /v1/messages（订阅为 /step_plan/v1/messages）。
 */
object StepFunProvider : CloudProvider {
    override val id: String = "stepfun"
    override val displayName: String = "阶跃星辰 StepFun"
    override val baseUrl: String = "https://api.stepfun.com"
    override val capabilities: CloudCapabilities = CloudCapabilities(
        chat = true,
        reasoning = true,
        imageUnderstanding = true,   // step-1o-turbo-vision
        imageEdit = true,             // step-image-edit-2 / step-1x-edit
        realtime = true,              // stepaudio-2.5-realtime
    )
    override val regionalClusters: List<RegionalCluster> = listOf(
        RegionalCluster(
            id = "payg",
            label = "按量付费",
            openAiBaseUrl = "https://api.stepfun.com/v1",
            anthropicBaseUrl = "https://api.stepfun.com/v1",
        ),
        RegionalCluster(
            id = "plan",
            label = "订阅 Step Plan",
            openAiBaseUrl = "https://api.stepfun.com/step_plan/v1",
            anthropicBaseUrl = "https://api.stepfun.com/step_plan/v1",
        ),
    )

    override fun authHeader(apiKey: String): Pair<String, String> =
        "Authorization" to "Bearer $apiKey"

    override fun anthropicBaseUrl(): String? = "https://api.stepfun.com/v1"
}

/**
 * 小米 MiMo。
 *
 * 已核实事实：
 * - 按量 api.xiaomimimo.com；订阅三集群 token-plan-cn / token-plan-sgp / token-plan-ams
 *   （.xiaomimimo.com），每集群配 /v1（OpenAI）与 /anthropic 两套路径；
 * - 鉴权 api-key 头与 Bearer 头等价；密钥前缀 sk-=按量、tp-=个人订阅、ttp-=团队订阅；
 * - mimo-v2.5 / mimo-v2.5-pro 于 2026-10-21 下线，最新聊天模型 mimo-v2.6-pro /
 *   mimo-v2.6-flash / mimo-v2.6-pro-ultraspeed（1M 上下文、128K 输出）；
 * - ASR mimo-v2.5-asr 与 TTS mimo-v2.5-tts 系列不在下线范围。
 */
object XiaomiMiMoProvider : CloudProvider {
    override val id: String = "xiaomi-mimo"
    override val displayName: String = "小米 MiMo"
    override val baseUrl: String = "https://api.xiaomimimo.com"
    override val capabilities: CloudCapabilities = CloudCapabilities(
        chat = true,
        reasoning = true,
        asr = true,                    // mimo-v2.5-asr
        tts = true,                    // mimo-v2.5-tts 系列
        imageUnderstanding = true,
        webSearch = true,             // mimo-v2.5-pro 起支持内置联网
    )
    override val regionalClusters: List<RegionalCluster> = listOf(
        RegionalCluster(
            id = "cn",
            label = "中国（Token Plan）",
            openAiBaseUrl = "https://token-plan-cn.xiaomimimo.com/v1",
            anthropicBaseUrl = "https://token-plan-cn.xiaomimimo.com/anthropic",
        ),
        RegionalCluster(
            id = "sgp",
            label = "新加坡（Token Plan）",
            openAiBaseUrl = "https://token-plan-sgp.xiaomimimo.com/v1",
            anthropicBaseUrl = "https://token-plan-sgp.xiaomimimo.com/anthropic",
        ),
        RegionalCluster(
            id = "ams",
            label = "欧洲（Token Plan）",
            openAiBaseUrl = "https://token-plan-ams.xiaomimimo.com/v1",
            anthropicBaseUrl = "https://token-plan-ams.xiaomimimo.com/anthropic",
        ),
        RegionalCluster(
            id = "payg",
            label = "按量付费",
            openAiBaseUrl = "https://api.xiaomimimo.com/v1",
            anthropicBaseUrl = null,
        ),
    )

    override fun authHeader(apiKey: String): Pair<String, String> {
        // 仅对密钥字段本身做前缀判断：tp-=个人订阅，ttp-=团队订阅，等价于 api-key 头
        if (apiKey.startsWith("tp-") || apiKey.startsWith("ttp-")) {
            return "api-key" to apiKey
        }
        // sk-=按量付费，以及其他未知前缀一律按 Bearer 处理
        return "Authorization" to "Bearer $apiKey"
    }

    override fun anthropicBaseUrl(): String? = null
}

/**
 * 硅基流动 SiliconFlow。
 *
 * 已核实事实：国内 api.siliconflow.cn、国际 api.siliconflow.com；
 * Bearer 鉴权；OpenAI 兼容与 /v1/messages。
 */
object SiliconFlowProvider : CloudProvider {
    override val id: String = "siliconflow"
    override val displayName: String = "硅基流动 SiliconFlow"
    override val baseUrl: String = "https://api.siliconflow.cn"
    override val capabilities: CloudCapabilities = CloudCapabilities(
        chat = true,
        reasoning = true,             // Qwen3.5 enable_thinking / deepseek-reasoner
    )
    override val regionalClusters: List<RegionalCluster> = listOf(
        RegionalCluster(
            id = "cn",
            label = "国内",
            openAiBaseUrl = "https://api.siliconflow.cn/v1",
            anthropicBaseUrl = "https://api.siliconflow.cn/v1",
        ),
        RegionalCluster(
            id = "intl",
            label = "国际",
            openAiBaseUrl = "https://api.siliconflow.com/v1",
            anthropicBaseUrl = "https://api.siliconflow.com/v1",
        ),
    )

    override fun authHeader(apiKey: String): Pair<String, String> =
        "Authorization" to "Bearer $apiKey"

    override fun anthropicBaseUrl(): String? = "https://api.siliconflow.cn/v1"
}

/**
 * OpenAI 兼容端点兜底 provider：用于未在 [CloudCatalog.providers] 登记的任意自定义 baseUrl。
 * 能力位仅 chat=true；密钥以 AIza 开头（Google AI Studio）时使用 x-goog-api-key 头。
 */
private class GenericOpenAiProvider(
    override val baseUrl: String,
) : CloudProvider {
    override val id: String = "generic-openai"
    override val displayName: String = "OpenAI 兼容端点"
    override val capabilities: CloudCapabilities = CloudCapabilities(chat = true)
    override val regionalClusters: List<RegionalCluster> = emptyList()

    override fun authHeader(apiKey: String): Pair<String, String> {
        if (apiKey.startsWith("AIza")) {
            return "x-goog-api-key" to apiKey
        }
        return "Authorization" to "Bearer $apiKey"
    }

    override fun anthropicBaseUrl(): String? = null
}

object CloudCatalog {

    val providers: List<CloudProvider> = listOf(
        StepFunProvider,
        XiaomiMiMoProvider,
        SiliconFlowProvider,
    )

    /** 每个 provider 需要匹配的 (host, pathPrefix) 候选，按路径长度降序便于最长前缀命中 */
    private data class MatchEntry(
        val host: String,
        val pathPrefix: String,
        val provider: CloudProvider,
    )

    private val matchIndex: List<MatchEntry> = buildList {
        providers.forEach { provider ->
            add(parseEntry(provider.baseUrl, provider))
            provider.regionalClusters.forEach { cluster ->
                add(parseEntry(cluster.openAiBaseUrl, provider))
                cluster.anthropicBaseUrl?.let { add(parseEntry(it, provider)) }
            }
        }
    }.sortedByDescending { it.pathPrefix.length }

    private fun parseEntry(url: String, provider: CloudProvider): MatchEntry {
        val (host, path) = splitHostPath(url)
        return MatchEntry(host, path, provider)
    }

    /**
     * 拆分 URL 为 (host 小写, path 小写且不以 / 结尾)。
     * 无 scheme 时视为不含路径的主机名。
     */
    private fun splitHostPath(url: String): Pair<String, String> {
        val normalized = url.trim().trimEnd('/')
        val schemeIdx = normalized.indexOf("://")
        val afterScheme = if (schemeIdx >= 0) normalized.substring(schemeIdx + 3) else normalized
        val slashIdx = afterScheme.indexOf('/')
        val host = if (slashIdx >= 0) afterScheme.substring(0, slashIdx) else afterScheme
        val path = if (slashIdx >= 0) afterScheme.substring(slashIdx) else ""
        return host.lowercase() to path.lowercase()
    }

    /**
     * 按 host 与路径前缀匹配 provider，忽略末尾斜杠。
     * 先按路径最长前缀命中，再退回仅 host 命中。
     */
    fun findByBaseUrl(baseUrl: String): CloudProvider? {
        val (host, path) = splitHostPath(baseUrl)
        if (host.isEmpty()) return null
        // 最长路径前缀优先
        matchIndex.firstOrNull { entry ->
            entry.host == host && path.startsWith(entry.pathPrefix)
        }?.let { return it.provider }
        // 仅主机名命中（自定义 baseUrl 可能多带或少带路径）
        matchIndex.firstOrNull { it.host == host }?.let { return it.provider }
        return null
    }

    /** 仅按主机名匹配 provider。 */
    fun findByHost(host: String): CloudProvider? {
        val normalized = host.trim().lowercase()
        if (normalized.isEmpty()) return null
        matchIndex.firstOrNull { it.host == normalized }?.let { return it.provider }
        return null
    }

    /**
     * 任意 OpenAI 兼容端点的兜底 provider。调用方在已确认非登记 provider 时使用，
     * 不修改全局注册表。
     */
    fun genericOpenAi(baseUrl: String): CloudProvider = GenericOpenAiProvider(baseUrl)
}
