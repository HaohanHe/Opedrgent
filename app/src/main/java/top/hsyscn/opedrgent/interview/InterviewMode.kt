package top.hsyscn.opedrgent.interview

// ==================== 面试类型枚举 ====================

/**
 * 面试类型
 */
enum class InterviewType(
    val label: String,
    val description: String,
) {
    JOB_INTERVIEW("求职面试", "模拟真实求职面试，根据公司/岗位定制问题"),
    THESIS_DEFENSE("论文答辩", "模拟学术答辩，挑战研究方法与结论"),
    SCENARIO("自定义场景", "根据用户需求定制面试场景"),
}

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

// ==================== 难度等级 ====================

/**
 * 面试难度等级
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
}

// ==================== 评估判定 ====================

/**
 * 面试结果判定
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
 * 评估维度
 *
 * @param name 维度名称（如"专业知识"、"沟通表达"等）
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
 * @param dimensions 各维度评分
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
 * 面试配置
 *
 * @param type 面试类型
 * @param company 公司名称（求职模式必填）
 * @param position 岗位名称（求职模式必填）/ 论文题目（答辩模式）
 * @param difficulty 难度等级
 * @param questionCount 面试题数量（5-15题）
 * @param durationMinutes 面试时长限制（分钟）
 * @param enableCoach 是否启用教练实时反馈
 * @param enableRealtimeFeedback 是否在面试中实时显示教练反馈
 * @param interviewerVoice 面试官声音（默认"白桦"成熟男声）
 * @param materials 简历/毕设/作品集内容
 * @param customInstructions 用户自定义要求
 */
data class InterviewConfig(
    val type: InterviewType = InterviewType.JOB_INTERVIEW,
    val company: String = "",
    val position: String = "",
    val difficulty: DifficultyLevel = DifficultyLevel.NORMAL,
    val questionCount: Int = 8,
    val durationMinutes: Int = 15,
    val enableCoach: Boolean = true,
    val enableRealtimeFeedback: Boolean = false,
    val interviewerVoice: String = "白桦",
    val materials: String = "",
    val customInstructions: String = "",
)

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
     * @param category 问题分类标签
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
 * @param logicScore 逻辑结构评分（0-10）
 * @param clarityScore 表达清晰度评分（0-10）
 * @param confidenceScore 自信度评分（0-10）
 * @param starUsage STAR法则使用情况
 * @param quickFeedback 快速反馈文本（1-2句话）
 * @param detailedFeedback 详细反馈建议
 */
data class CoachFeedback(
    val logicScore: Float,
    val clarityScore: Float,
    val confidenceScore: Float,
    val starUsage: String,
    val quickFeedback: String,
    val detailedFeedback: String,
)
