package top.hsyscn.opedrgent.cultivation.model

/**
 * 个人修炼（批判镜）领域模型。
 *
 * 设计约定：
 * - 这里只描述“结果数据结构”，不描述分析流程；分析如何组织由模型自主决定（模型为大）。
 * - 不设任何关键词/敏感词表，用户状态与路由由模型结合完整语境判断后以结构化字段返回。
 * - 不做数值打分（MVP 口径），报告只承载逐字证据、行为后果与可执行替代说法。
 */

/**
 * 理想人格基准下的单个维度。
 *
 * @param name 维度名，例如“尊重学生”
 * @param doBehaviors 做到时可观察的行为描述
 * @param dontBehaviors 不接受的行为描述
 */
data class VirtueDimension(
    val name: String,
    val doBehaviors: List<String> = emptyList(),
    val dontBehaviors: List<String> = emptyList(),
)

/**
 * 一份理想人格基准（带版本，校准后整体存版本）。
 */
data class VirtueBaseline(
    val id: Long = 0,
    val version: Int = 1,
    val dimensions: List<VirtueDimension> = emptyList(),
    val complete: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    /** 基准是否足以支撑分析：至少一个维度且每个维度至少有一条可观察行为描述。 */
    fun isUsable(): Boolean = dimensions.any {
        it.name.isNotBlank() && (it.doBehaviors.isNotEmpty() || it.dontBehaviors.isNotEmpty())
    }
}

/**
 * 反馈模式（对应需求卡 N7 风险分层与温和模式）。
 * 档位由模型结合入口分层与近期语境给出建议、用户可随时改，工程不据此做语义判定。
 */
enum class FeedbackMode {
    /** 标准模式：正常批判镜。 */
    STANDARD,

    /** 温和模式：更低频次、更强自我悲悯框架、优先肯定具体行为。 */
    GENTLE;

    companion object {
        fun fromName(raw: String?): FeedbackMode =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: STANDARD
    }
}

/**
 * 本次分析的总体路由。由模型在完整语境中判断，工程只按路由做机械分流，
 * 不通过匹配任何词语来决定路由。
 */
enum class MirrorRoute {
    /** 正常：输出批判镜分析。 */
    ANALYZE,

    /** 用户正强烈自我否定：先稳住情绪、拆回具体行为，再加码最小下一步。 */
    SUPPORT,

    /** 真实危机信号：停止一切挑错，转陪伴与本地求助资源（危机独立旁路）。 */
    CRISIS;

    companion object {
        fun fromName(raw: String?): MirrorRoute =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: ANALYZE
    }
}

/**
 * 批判镜挑出的单条问题。
 *
 * @param quote 逐字引用的转写原句（必须真实出现，由代码做字符串级核验）
 * @param baselineRef 对应的基准维度/行为条目
 * @param impact 该言语行为及其可能后果（只对行为，不对人格）
 * @param alternative 下次可直接使用的替代说法或动作
 * @param dimension 简短、可复用的行为维度名，由模型从这段内容自行归纳（如打断、以偏概全、承诺未跟进），
 *        没有固定词表；供长期趋势聚合用。工程只做字符串级计数，不对它做语义判断或关键词命中；无法归类可留空。
 */
data class MirrorIssue(
    val quote: String,
    val baselineRef: String,
    val impact: String,
    val alternative: String,
    val dimension: String = "",
    /**
     * 仅认知镜按需填写：仅当某参考认知偏差确实在完整语境中支撑了用户结论时才填其参考名（见
     * CognitiveBiasCatalog），否则留空。它不是判定词表命中结果——批判镜恒为空；认知镜也不得为填而填。
     * 工程不据此做任何匹配，仅随报告落库与展示。放在末尾、默认空，保持既有具名构造点不变。
     */
    val referenceName: String = "",
)

/** 用户对单条问题的标记（需求卡 N5）。 */
enum class IssueMark {
    NONE,
    ACCEPTED,
    DISAGREED,
    IMPROVED;

    companion object {
        fun fromName(raw: String?): IssueMark =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: NONE
    }
}

/**
 * 一份批判镜报告（结构化结果，便于落库与报告卡展示）。
 *
 * @param route 模型判定的总体路由
 * @param overall 总体一两句观察，至少包含一个用户做到的具体点
 * @param issues 值得调整之处；[MirrorRoute.SUPPORT]/[MirrorRoute.CRISIS] 时为空
 * @param nextStep 一个最小、现在就能做的下一步
 * @param support SUPPORT/CRISIS 路由下替代分析的支持性回应
 * @param helpResources CRISIS 路由下给出的本地求助资源文案
 * @param strengths 用户确实做到的优势/特质（逐字证据），服务于长期全景式优势显影
 * @param patterns 结合历次复盘与长期记忆识别出的跨时间模式，没有可空
 * @param followUps 本次镜鉴落成的可执行跟进，供后续复评，没有可空
 * @param rawResponse 模型原始返回，留档用于复核与评测
 */
data class MirrorReport(
    val sessionId: String,
    val transcriptId: String,
    val route: MirrorRoute,
    val overall: String,
    val issues: List<MirrorIssue>,
    val nextStep: String,
    val support: String = "",
    val helpResources: String = "",
    val strengths: List<StrengthNote> = emptyList(),
    val patterns: List<PatternNote> = emptyList(),
    val followUps: List<FollowUp> = emptyList(),
    val mode: FeedbackMode = FeedbackMode.STANDARD,
    val modelUsed: String = "",
    val backend: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val rawResponse: String = "",
)
