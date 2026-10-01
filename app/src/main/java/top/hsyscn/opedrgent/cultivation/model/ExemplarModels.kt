package top.hsyscn.opedrgent.cultivation.model

/**
 * 榜样镜（Exemplar Mirror）领域模型。
 *
 * 与批判镜并列的第二面镜子：不评判对错，而是以用户指定榜样（历史人物或其认可的理想原型）
 * 的视角与一以贯之的行事原则，回答“同一件事，他会怎么做、你哪里已经暗合此道、还能怎么优化”。
 *
 * 设计约定与批判镜一致：
 * - 只描述结果结构，不固化分析流程；如何提炼情境、取几个点由模型结合完整语境决定。
 * - 不设任何关键词/定性词表，工程不做语义判定。
 * - 亮点必须逐字引用转写原句，由代码做字符串级核验，杜绝无证据的讨好。
 */

/**
 * “榜样会怎么做”的单步。
 *
 * @param action 榜样在同一情境下会采取的具体做法（可观察、可效仿，而非空泛口号）
 * @param rationale 该做法基于榜样的哪条一贯之道；不得编造其具体言论或史实
 */
data class ExemplarAction(
    val action: String,
    val rationale: String = "",
)

/**
 * 用户当前做法中暗合榜样之道的亮点。
 *
 * @param quote 逐字引用的转写原句（必须真实出现，由代码字符串级核验）
 * @param point 亮点具体是什么、暗合榜样的哪条原则
 */
data class ExemplarHighlight(
    val quote: String,
    val point: String,
)

/**
 * 对照榜样仍可优化的一点。
 *
 * @param observation 观察到的具体行为（对事不对人，不做人格定性）
 * @param suggestion 下次可直接采用的说法或动作
 */
data class ExemplarImprovement(
    val observation: String,
    val suggestion: String,
)

/**
 * 一份榜样镜报告。
 *
 * @param exemplar 本次对标的榜样名
 * @param situation 模型对当下情境的一两句客观提炼
 * @param actions 榜样会怎么做（具体步骤及其依据）
 * @param highlights 用户已做到、暗合榜样之道的真实亮点（逐字证据）
 * @param improvements 对照榜样仍可优化之处与可直接采用的做法
 * @param takeaway 一句可带走的“榜样心法”
 * @param route 统一状态路由：榜样镜也先判状态，用户强烈自我否定/危机时用支持回应替代冷静对照
 * @param support SUPPORT/CRISIS 路由下替代对照分析的支持性回应，ANALYZE 时为空
 * @param helpResources CRISIS 路由下的本地求助资源引导，其余为空
 * @param followUps 看完榜样后落成的可执行跟进，供后续复评，没有可空
 * @param rawResponse 模型原始返回，留档用于复核与评测
 */
data class ExemplarReport(
    val exemplar: String,
    val situation: String,
    val actions: List<ExemplarAction>,
    val highlights: List<ExemplarHighlight>,
    val improvements: List<ExemplarImprovement>,
    val takeaway: String,
    val route: MirrorRoute = MirrorRoute.ANALYZE,
    val support: String = "",
    val helpResources: String = "",
    val followUps: List<FollowUp> = emptyList(),
    val mode: FeedbackMode = FeedbackMode.STANDARD,
    val modelUsed: String = "",
    val backend: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val rawResponse: String = "",
)
