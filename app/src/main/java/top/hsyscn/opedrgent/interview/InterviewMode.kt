package top.hsyscn.opedrgent.interview

// ==================== 面试类型枚举（开放式） ====================

/**
 * 面试类型 — 支持预置快捷选项和完全自定义场景。
 *
 * 设计理念：
 * - 预置选项（JOB_INTERVIEW/THESIS_DEFENSE/SCENARIO）作为常用场景的快捷入口
 * - CUSTOM 构造器允许用户用自然语言描述任意面试场景，LLM 将据此自主决策行为策略
 * - 不再限制为固定几种类型，真正实现"万物皆可面试"
 */
enum class InterviewType(
    val label: String,
    val description: String,
) {
    JOB_INTERVIEW("求职面试", "模拟真实求职面试，根据公司/岗位定制问题"),
    THESIS_DEFENSE("论文答辩", "模拟学术答辩，挑战研究方法与结论"),
    SCENARIO("自定义场景", "根据用户需求定制面试场景"),

    /**
     * 完全自定义场景 — 用户用自然语言描述任意面试/对话场景。
     *
     * @param scenario 自定义场景描述（如"模拟产品经理与研发的跨部门沟通"、"英语口语考试"、"投资路演"等）
     *
     * 使用示例：
     * ```kotlin
     * val type = InterviewType.CUSTOM("模拟一场投资人路演，我是创业者，需要说服投资人")
     * val type = InterviewType.CUSTOM("日语N1口语模拟测试，重点考察敬语使用和商务表达")
     * ```
     */
    CUSTOM("", "") {
        override fun toString(): String = if (label.isEmpty()) "自定义场景" else label
    };
}

// ==================== TTS 场景枚举 ====================

/**
 * TTS 语音合成场景 — 控制语音风格、语速、音调等参数。
 *
 * 每个场景对应不同的"导演模式"配置，
 * 让 AI 的语音表现贴合当前对话场景的氛围。
 *
 * 参考飞书 APK 中的 TTS_SCENARIO_TYPE_NORMAL / NOVEL 等场景化设计。
 */
enum class TtsScenario(
    val label: String,
    val defaultRate: Float,        // 默认语速倍率
    val defaultPitch: Float,       // 默认音调
    val directorCharacter: String, // 导演模式：角色设定
    val directorScene: String,     // 导演模式：场景描述
) {
    INTERVIEW("面试模式", 0.9f, 1.0f,
        "资深面试官",
        "正式面试房间",
    ),
    DEBATE("辩论模式", 1.1f, 1.0f,
        "辩手",
        "辩论赛场",
    ),
    PRESENTATION("演讲模式", 0.95f, 1.05f,
        "演讲者",
        "公开演讲舞台",
    ),
    CASUAL("轻松聊天", 1.0f, 1.0f,
        "朋友",
        "咖啡厅",
    ),
    STORYTELLING("故事讲述", 0.85f, 0.95f,
        "讲故事的人",
        "篝火旁",
    ),
    PRESSURE_TEST("压力测试", 1.05f, 1.1f,
        "压力面试官",
        "高压审讯室",
    ),
}

// ==================== 面试官风格枚举 ====================

/**
 * 面试官交互风格 — 控制 LLM 的语气和行为倾向。
 *
 * 不再硬编码"中性专业语气"，而是让用户选择或 LLM 根据场景自适应。
 */
enum class InterviewerStyle {
    /** 专业严谨 — 标准面试官形象，客观中立 */
    PROFESSIONAL,

    /** 友善亲和 — 轻松氛围，适合初学者或减压练习 */
    FRIENDLY,

    /** 严格苛刻 — 高压面试，挑战候选人极限 */
    RIGOROUS,

    /** 随性自然 — 像真实聊天一样，不刻意保持面试感 */
    CASUAL,
}

// ==================== 判定阈值配置 ====================

/**
 * 判定阈值 — 可配置的通过/不通过分数线。
 *
 * 默认值参考传统标准（PASS≥75），但完全可由用户或 LLM 根据场景调整。
 * 例如：
 * - 高难度专家面：PASS≥85
 * - 入门级练习：PASS≥60
 * - 学术答辩：PASS≥70（更严格的条件通过线）
 *
 * 当 config.verdictThresholds 为 null 时，由 LLM 根据场景自主判断合理阈值。
 */
data class VerdictThresholds(
    val passScore: Float = 75f,            // 通过分数线
    val conditionalPassScore: Float = 60f, // 有条件通过分数线
)

// ==================== 材料条目 ====================

/**
 * 材料条目 — 支持多种类型的输入材料。
 *
 * 不再局限于单一文本字段，支持：
 * - 文本材料（简历、论文摘要等）
 * - URL 链接（GitHub主页、作品集网站等）
 * - 结构化信息（JSON格式的职位JD等）
 * - 多段材料组合（简历+作品集+自荐信）
 *
 * @param content 材料内容（文本或URL）
 * @param type 材料类型标识（text/url/json/markdown 等），默认"text"
 * @param title 材料标题/说明（可选，帮助LLM理解材料用途）
 */
data class MaterialEntry(
    val content: String,
    val type: String = "text",
    val title: String = "",
)

// ==================== 面试阶段状态机 ====================

/**
 * 面试阶段 — 状态机驱动整个面试流程
 */
enum class InterviewPhase {
    /** 设置阶段：填写信息（公司/岗位/简历） */
    SETUP,

    /** 准备中：AI 分析材料，生成问题集 */
    PREPARING,

    /** 进行中：问答循环 */
    IN_PROGRESS,

    /** 评估中：AI 生成评估报告 */
    EVALUATING,

    /** 完成：展示报告 */
    COMPLETED,
}

// ==================== 难度等级（开放式） ====================

/**
 * 面试难度等级 — 支持预置等级和完全自定义。
 *
 * CUSTOM 构造器允许用户指定任意 1-10 的难度值和标签，
 * 实现真正的连续难度控制而非离散档位。
 */
enum class DifficultyLevel(
    val level: Int,
    val label: String,
    val description: String,
) {
    EASY(1, "简单", "基础问题为主，适合初次练习"),
    NORMAL(5, "普通", "标准面试难度，包含追问"),
    HARD(8, "困难", "深度技术/行为问题，压力测试"),
    EXPERT(10, "专家", "最高难度，全面挑战综合能力"),

    /**
     * 自定义难度等级。
     *
     * @param level 难度值（1-10）
     * @param label 显示标签
     */
    CUSTOM(5, "自定义", "用户自定义难度") {
        override fun toString(): String = label
    };
}

// ==================== 评估判定 ====================

/**
 * 面试结果判定
 *
 * 判定阈值由 [VerdictThresholds] 配置，不再硬编码 75/60 分界线。
 */
enum class Verdict(
    val label: String,
    val color: String, // 用于 UI 显示的颜色标识
) {
    PASS("通过", "#2E7D32"),           // 绿色
    CONDITIONAL_PASS("有条件通过", "#F57C00"), // 橙色
    FAIL("未通过", "#E53935"),         // 红色
}

// ==================== 核心数据类 ====================

/**
 * 对话轮次记录
 *
 * @param role 角色："interviewer"（面试官）或 "candidate"（候选人）
 * @param content 对话内容
 * @param timestamp 时间戳
 * @param questionCategory 问题分类（仅面试官消息）
 * @param followUpDepth 追问深度（0=原始问题，1=第一层追问...）
 */
data class DialogueTurn(
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val questionCategory: String? = null,
    val followUpDepth: Int = 0,
)

/**
 * 评估维度 — 完全开放，不再预设固定维度名称。
 *
 * 维度名称、数量、评分标准均由 LLM 根据具体场景动态决定。
 * 例如：
 * - 求职面试可能产生：专业知识、沟通表达、逻辑思维...
 * - 论文答辩可能产生：方法论、创新性、数据可靠性...
 * - 英语口语可能产生：流利度、语法准确度、词汇丰富度...
 * - 投资路演可能产生：商业逻辑、市场洞察、表达感染力...
 *
 * @param name 维度名称（由 LLM 根据场景生成）
 * @param score 得分（0-10）
 * @param maxScore 满分，默认10
 * @param feedback 反馈说明
 * @param highlights 亮点列表
 * @param improvements 待改进点列表
 */
data class EvaluationDimension(
    val name: String,
    val score: Float,
    val maxScore: Float = 10f,
    val feedback: String,
    val highlights: List<String> = emptyList(),
    val improvements: List<String> = emptyList(),
)

/**
 * 最终评估报告
 *
 * @param sessionId 会话ID
 * @param type 面试类型
 * @param overallScore 总分（0-100）
 * @param verdict 判定结果
 * @param dimensions 各维度评分（维度数量和名称由 LLM 动态决定）
 * @param summary 总体评价
 * @param strengths 优势列表
 * @param weaknesses 不足列表
 * @param recommendations 改进建议
 * @param questionCount 问题总数
 * @param durationSeconds 面试时长（秒）
 * @param transcript 完整对话记录
 */
data class InterviewReport(
    val sessionId: String,
    val type: InterviewType,
    val overallScore: Float,
    val verdict: Verdict,
    val dimensions: List<EvaluationDimension>,
    val summary: String,
    val strengths: List<String>,
    val weaknesses: List<String>,
    val recommendations: List<String>,
    val questionCount: Int,
    val durationSeconds: Long,
    val transcript: List<DialogueTurn>,
)

/**
 * 面试配置 — 全可配置架构的核心载体。
 *
 * 设计理念：
 * - 所有影响 LLM 行为的参数都通过此配置注入
 * - null 值表示"由 LLM 自主决策"，非 null 表示"用户明确指定"
 * - 场景描述用自然语言而非枚举，实现真正的开放性
 *
 * @param type 面试类型（预置选项或 CUSTOM 自定义）
 * @param scenarioDescription 用户用自然语言描述的任意场景（核心！当 type=CUSTOM 时必填）
 * @param company 公司名称（求职模式适用）
 * @param position 岗位名称/论文题目/主题
 * @param difficulty 难度等级
 * @param questionCount 面试题数量参考值（LLM 可根据实际情况调整）
 * @param durationMinutes 面试时长限制（分钟）
 * @param enableCoach 是否启用教练实时反馈
 * @param enableRealtimeFeedback 是否在面试中实时显示教练反馈
 * @param interviewerVoice 面试官声音（默认"白桦"成熟男声）
 * @param interviewerStyle 面试官交互风格
 * @param language 对话语言（如 "zh-CN"、"en-US"、"ja-JP"）
 * @param enableVoiceConversation 是否启用语音对话模式
 * @param materials 输入材料列表（支持多段不同类型的材料）
 * @param evalDimensions 预设评估维度（null = LLM 根据场景自动决定）
 * @param verdictThresholds 判定阈值配置（null = LLM 根据场景自动决定）
 * @param customInstructions 用户额外的自由格式指令
 * @param ttsScenario TTS 语音场景（控制语音风格）
 * @param stepApiKey 阶跃星辰 API Key（使用 StepRealtime 引擎时必填）
 * @param stepModel 阶跃星辰模型名称（默认 step-3.7-flash）
 * @param stepVoice 阶跃星辰音色 ID（默认 linjiajiejie）
 */
data class InterviewConfig(
    val type: InterviewType = InterviewType.JOB_INTERVIEW,
    val scenarioDescription: String = "",
    val company: String = "",
    val position: String = "",
    val difficulty: DifficultyLevel = DifficultyLevel.NORMAL,
    val customDifficultyLevel: Int? = null,
    val questionCount: Int = 8,
    val durationMinutes: Int = 15,
    val enableCoach: Boolean = true,
    val enableRealtimeFeedback: Boolean = false,
    val interviewerVoice: String = "白桦",
    val interviewerStyle: InterviewerStyle = InterviewerStyle.PROFESSIONAL,
    val language: String = "zh-CN",
    val enableVoiceConversation: Boolean = true,
    val materials: List<MaterialEntry> = emptyList(),
    val evalDimensions: List<String>? = null,
    val verdictThresholds: VerdictThresholds? = null,
    val customInstructions: String = "",
    val ttsScenario: TtsScenario = TtsScenario.INTERVIEW,
    // Step Realtime 引擎参数
    var stepApiKey: String = "",
    var stepModel: String = "stepaudio-2.5-realtime",  // 默认使用专门的语音大模型
    var stepVoice: String = "linjiajiejie",
) {
    /**
     * 获取材料的合并文本（兼容旧接口）。
     */
    fun getMaterialsText(): String {
        return materials.joinToString("\n\n") { entry ->
            buildString {
                if (entry.title.isNotBlank()) append("【${entry.title}】\n")
                append(entry.content)
            }
        }
    }

    /**
     * 获取有效的场景描述（优先使用 scenarioDescription，其次 fallback 到 type 相关信息）。
     */
    fun getEffectiveScenarioDescription(): String {
        return when {
            scenarioDescription.isNotBlank() -> scenarioDescription
            type == InterviewType.CUSTOM -> "自定义场景"
            type == InterviewType.JOB_INTERVIEW ->
                "${position.ifBlank { "某岗位" }}求职面试@${company.ifBlank { "某公司" }}"
            type == InterviewType.THESIS_DEFENSE ->
                "论文答辩：${position.ifBlank { "某研究课题" }}"
            else -> "通用面试场景"
        }
    }
}

/**
 * 材料分析结果
 *
 * @param keyPoints 材料关键点提取
 * @param suggestedQuestions 建议提问方向
 * @param riskAreas 可能被深挖的风险点
 * @param interviewStrategy 面试策略建议
 */
data class AnalysisResult(
    val keyPoints: List<String>,
    val suggestedQuestions: List<String>,
    val riskAreas: List<String>,
    val interviewStrategy: String,
)

/**
 * 下一步动作（密封类）
 *
 * 面试官处理候选人回答后的三种可能动作：
 * - FollowUp：继续追问同一话题
 * - NextQuestion：切换到下一个新问题
 * - EndInterview：结束面试
 */
sealed class NextAction {
    /**
     * 追问
     * @param question 追问内容
     * @param reason 追问原因
     */
    data class FollowUp(val question: String, val reason: String) : NextAction()

    /**
     * 下一题
     * @param question 新问题内容
     * @param category 问题分类标签（由 LLM 自主标注）
     */
    data class NextQuestion(val question: String, val category: String) : NextAction()

    /**
     * 结束面试
     * @param reason 结束原因说明
     */
    data class EndInterview(val reason: String) : NextAction()
}

/**
 * 教练反馈（每轮回答后可选显示）
 *
 * 反馈维度不再是固定的4项（逻辑/清晰度/自信度/STAR），
 * 而是由 LLM 根据 [InterviewConfig.evalDimensions] 或场景自行决定评估重点。
 *
 * @param scores 各维度评分（key=维度名, value=分数 0-10）
 * @param quickFeedback 快速反馈文本（1-2句话）
 * @param detailedFeedback 详细反馈建议
 */
data class CoachFeedback(
    val scores: Map<String, Float> = emptyMap(),  // 开放式评分维度
    val quickFeedback: String,
    val detailedFeedback: String,
) {
    // 兼容旧接口的便捷属性
    val logicScore: Float get() = scores["逻辑结构"] ?: scores["logic"] ?: 0f
    val clarityScore: Float get() = scores["表达清晰度"] ?: scores["clarity"] ?: 0f
    val confidenceScore: Float get() = scores["自信度"] ?: scores["confidence"] ?: 0f
}
