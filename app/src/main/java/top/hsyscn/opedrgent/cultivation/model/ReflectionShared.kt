package top.hsyscn.opedrgent.cultivation.model

/**
 * 修炼会话的共享领域模型（逻辑重设计 P1）。
 *
 * 设计立场：统一的是“会话协议层”——多面镜子共用同一套透镜枚举、状态路由、
 * 优势 / 模式 / 跟进维度与存储信封；但不要求端侧小模型一次同时产出多面镜子的全部内容，
 * 每次仍以一面透镜聚焦生成，避免小模型负载过重而降质（模型为大，工程只做确定性支撑）。
 *
 * 三面透镜分工互补：[CRITIQUE] 管“怎么说”（言行是否符合理想人格基准），
 * [COGNITIVE] 管“怎么想”（思考方式与认知偏差，给更合理的替代视角），
 * [EXEMPLAR] 管“对照榜样一贯之道会怎么做”。
 */

/**
 * 一次会话所使用的透镜。
 *
 * 放在领域层而非 UI 层，因为存储、引擎、提示与质量门都需要识别透镜；UI 状态只引用它。
 */
enum class ReflectionLens {
    /** 批判镜：对照用户自定义的理想人格基准，看言行差距与替代说法。 */
    CRITIQUE,

    /**
     * 认知修炼镜：看思考方式与认知偏差，给出更合理的替代视角。
     *
     * 与 [CRITIQUE] 互补——一个管“怎么说”，一个管“怎么想”。它不判定人格、不贴偏差标签，
     * 只在完整语境中、凭逐字证据指出“这个推断可能不全面之处”，并把判断权留给用户。
     */
    COGNITIVE,

    /** 榜样镜：对照理想他者的一贯之道，看他会怎么做、亮点与可优化处。 */
    EXEMPLAR;

    companion object {
        /** 容错解析：未识别名回落 [CRITIQUE]；新透镜加入 [entries] 后此处自动覆盖，无需另改。 */
        fun fromName(raw: String?): ReflectionLens =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: CRITIQUE
    }
}

/**
 * 一条“优势 / 特质”证据：服务于罗振宇所说的“全景信息”——长期、跨场景地让真实优势显影，
 * 而不只是挑错。
 *
 * @param quote 逐字摘录的转写原句（必须真实出现，由代码做字符串级核验，杜绝无证据的讨好）
 * @param trait 这条证据所体现的特质名，例如“愿意先听完”
 * @param note 一两句说明：它为什么是优势、在当下情境起了什么作用
 */
data class StrengthNote(
    val quote: String,
    val trait: String,
    val note: String,
)

/**
 * 一条跨时间识别出的长期行为 / 认知模式。
 *
 * 它依据 recall 工具取回的历次复盘与长期记忆归纳，不要求逐字引用本次转写，
 * 因此质量门只在“给了空壳模式”时拦截，不做逐字匹配。
 *
 * @param pattern 模式的一句话概括
 * @param note 具体说明：它在哪些场景反复出现、带来什么影响
 * @param refs 该模式参考到的历史来源标签（由模型依据工具结果填写，工程只展示、不强制）
 */
data class PatternNote(
    val pattern: String,
    val note: String = "",
    val refs: List<String> = emptyList(),
)

/** 跟进项的生命周期状态；P1 只产出 [OPEN]，复评 / 趋势在 P2 展开。 */
enum class FollowUpStatus {
    OPEN,
    DONE,
    DROPPED;

    companion object {
        fun fromName(raw: String?): FollowUpStatus =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: OPEN
    }
}

/**
 * 一条可执行的修炼跟进：把“镜鉴”落成“下一步行动”，是记录—镜鉴—行动—复评闭环的一环。
 *
 * P1 模型只需产出 [text]；稳定 [id] 由解析层按序补齐，[status] 默认 [FollowUpStatus.OPEN]，
 * 以降低端侧小模型的输出负担。
 */
data class FollowUp(
    val text: String,
    val id: String = "",
    val status: FollowUpStatus = FollowUpStatus.OPEN,
)
