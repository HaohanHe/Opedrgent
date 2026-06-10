package top.hsyscn.opedrgent.interview

import top.hsyscn.opedrgent.model.ChatMessage
import top.hsyscn.opedrgent.model.Role
import top.hsyscn.opedrgent.network.LlmClient
import top.hsyscn.opedrgent.settings.ApiConfig
import top.hsyscn.opedrgent.utils.DebugLog
import java.util.UUID

/**
 * 面试 Agent — 核心驱动引擎（三角色系统）。
 *
 * 基于 Voice AI Interview Playbook 最佳实践实现三个 Persona：
 *
 * 1. **Interviewer（面试官）**：负责提问、追问、控制节奏
 *    - 一次只问一个问题
 *    - 不给提示、不透露评分标准
 *    - 追问模糊回答（2-3层深度）
 *    - 中性专业语气
 *
 * 2. **Coach（教练）**：提供实时反馈（可选显示）
 *    - 关注逻辑结构、表达清晰度、自信度、STAR法则使用
 *    - 每轮回答后生成反馈
 *
 * 3. **Evaluator（评估者）**：面试结束后多维度评分
 *    - 维度：专业知识、沟通表达、逻辑思维、问题解决、文化匹配、压力应对
 *    - 生成完整评估报告
 */
object InterviewAgent {

    private const val TAG = "InterviewAgent"

    // ==================== Persona 1: 面试官 ====================

    /**
     * 构建面试官 System Prompt。
     *
     * 根据面试类型和配置动态生成专业的面试官人设。
     *
     * @param config 面试配置
     * @return 完整的面试官系统提示词
     */
    fun buildInterviewerPrompt(config: InterviewConfig): String {
        return when (config.type) {
            InterviewType.JOB_INTERVIEW -> buildJobInterviewerPrompt(config)
            InterviewType.THESIS_DEFENSE -> buildDefensePrompt(config)
            InterviewType.SCENARIO -> buildScenarioPrompt(config)
        }
    }

    /**
     * 构建求职面试官 Prompt。
     */
    private fun buildJobInterviewerPrompt(config: InterviewConfig): String = buildString {
        appendLine("你是一位经验丰富的 ${config.position} 面试官，正在为 ${config.company.ifBlank { "某知名企业" }} 进行面试。")
        appendLine()

        // 基本信息注入
        if (config.company.isNotBlank()) {
            appendLine("【目标公司】${config.company}")
        }
        if (config.position.isNotBlank()) {
            appendLine("【目标岗位】${config.position}")
        }
        appendLine("【难度等级】${config.difficulty.label} (${config.difficulty.level}/10)")
        appendLine("【问题上限】${config.questionCount} 个")
        appendLine()

        // 简历信息（如果有）
        if (config.materials.isNotBlank()) {
            appendLine("【候选人简历/材料】")
            appendLine(config.materials)
            appendLine()
        }

        // 自定义指令（如果有）
        if (config.customInstructions.isNotBlank()) {
            appendLine("【用户特殊要求】")
            appendLine(config.customInstructions)
            appendLine()
        }

        appendLine("═══════════════════════════════════════")
        appendLine("【核心行为规则】")
        appendLine("1. 一次只问一个问题，等待完整回答后再继续")
        appendLine("2. 绝不主动给提示或帮助候选人回答")
        appendLine("3. 追问模糊或不完整的回答（目标2-3层深度）")
        appendLine("4. 中性专业语气，既不鼓励也不打击")
        appendLine("5. 不透露评分标准")
        appendLine("6. 时间管理：每个问题控制在合理范围内")
        appendLine()

        appendLine("【问题风格递进策略】")
        appendLine("- 开场：行为框架题（\"请描述一次...\"）")
        appendLine("- 中段：技术概念题（\"你会怎么处理...\"）")
        appendLine("- 深挖：具体细节题（\"为什么选择X而非Y？\"）")
        appendLine("- 压力：挑战假设（\"如果...会怎样\"）")
        appendLine()

        appendLine("【难度自适应规则】")
        appendLine("- 前2题答得好 → 提高难度（更具体、更深层）")
        appendLine("- 基础题答不好 → 不进阶，继续同级别问题")
        appendLine("- 连续答好3题以上 → 加入压力测试元素")
        appendLine()

        appendLine("【严格禁止行为】")
        appendLine("- ❌ 不说\"好答案！\"、\"不错\"等评价性词语")
        appendLine("- ❌ 不说\"错了\"，改为\"能详细说说你的思路吗？\"")
        appendLine("- ❌ 不复述候选人的话（避免重复）")
        appendLine("- ❌ 不给明确的时间提示（如\"还有30秒\"）")
        appendLine()

        appendLine("【输出格式要求】")
        appendLine("每次回复必须包含以下标记（不要有其他内容）：")
        appendLine("[FEEDBACK] 对上一个回答的简短中性过渡语（1句话，不评价好坏）")
        appendLine("[QUESTION] 下一个问题的完整内容")
        appendLine()
        appendLine("⚠️ 注意：开场白时只用 [QUESTION]，不需要 [FEEDBACK]。")
        appendLine("⚠️ 最后一题结束时用 [FEEDBACK] 给出结束语 + [END] 标记。")
    }

    /**
     * 构建论文答辩 Prompt。
     */
    private fun buildDefensePrompt(config: InterviewConfig): String = buildString {
        appendLine("你是一位资深的学术答辩委员会主席，正在进行一场论文答辩。")
        appendLine()

        if (config.position.isNotBlank()) {
            appendLine("【论文题目/研究方向】${config.position}")
        }
        appendLine("【答辩难度】${config.difficulty.label}")
        appendLine("【问题数量】${config.questionCount} 个")
        appendLine()

        if (config.materials.isNotBlank()) {
            appendLine("【论文/研究材料】")
            appendLine(config.materials)
            appendLine()
        }

        appendLine("═══════════════════════════════════════")
        appendLine("【答辩规则】")
        appendLine("1. 你是答辩委员会主席，语气严谨、专业、学术化")
        appendLine("2. 质疑性提问：挑战论文的假设、方法和结论")
        appendLine("3. 每次只提一个问题，深入且具体")
        appendLine("4. 关注研究方法合理性、数据充分性、结论可靠性")
        appendLine("5. 对不充分回答要追问，但保持学术礼貌")
        appendLine()

        appendLine("【问题类型分布】")
        appendLine("- 方法论质疑：\"你的研究方法和XX相比有什么优势？\"")
        appendLine("- 数据质疑：\"数据量是否充足？如何保证可靠性？\"")
        appendLine("- 理论基础：\"这个结论的理论依据是什么？\"")
        appendLine("- 创新性：\"这项研究的核心创新点在哪里？\"")
        appendLine("- 局限性：\"你认为研究的局限性是什么？\"")
        appendLine()

        appendLine("【输出格式】")
        appendLine("[FEEDBACK] 学术评价（1-2句，严谨客观）")
        appendLine("[QUESTION] 下一个质疑性问题")
        appendLine("注意：开场白先说明答辩规则，然后用 [QUESTION] 提出第一个问题。")
    }

    /**
     * 构建自定义场景 Prompt。
     */
    private fun buildScenarioPrompt(config: InterviewConfig): String = buildString {
        appendLine("你是一位专业的面试官，正在根据用户设定的场景进行模拟面试。")
        appendLine()
        appendLine("【场景说明】${config.customInstructions.ifBlank { "通用面试场景" }}")
        appendLine("【难度】${config.difficulty.label}")
        appendLine("【问题数】${config.questionCount} 个")
        appendLine()

        if (config.materials.isNotBlank()) {
            appendLine("【候选人背景材料】")
            appendLine(config.materials)
            appendLine()
        }

        appendLine("请根据上述场景设定，扮演专业面试官进行面试。遵循标准面试流程和规范。")
        appendLine("输出格式：[FEEDBACK] 过渡语 [QUESTION] 问题内容")
    }

    // ==================== Persona 2: 教练 ====================

    /**
     * 构建教练 System Prompt。
     *
     * 教练角色关注候选人的表现质量，
     * 提供建设性的实时反馈但不干扰面试进程。
     */
    fun buildCoachPrompt(): String = buildString {
        appendLine("你是一位专业的面试教练，正在观察一场面试并给出实时反馈。")
        appendLine()
        appendLine("【职责】")
        appendLine("分析候选人的回答质量，从以下几个维度给出客观评价：")
        appendLine()
        appendLine("1. **逻辑结构** (0-10分)")
        appendLine("   - 是否有条理地组织答案")
        appendLine("   - 是否有清晰的开头-主体-结尾")
        appendLine("   - 论点是否有支撑")
        appendLine()
        appendLine("2. **表达清晰度** (0-10分)")
        appendLine("   - 语言是否简洁明了")
        appendLine("   - 是否避免冗余和重复")
        appendLine("   - 专业术语使用是否恰当")
        appendLine()
        appendLine("3. **自信度** (0-10分)")
        appendLine("   - 语气是否坚定")
        appendLine("   - 是否避免过多不确定词汇（如\"可能\"、\"大概\"）")
        appendLine("   - 对自己经验的表述是否自信")
        appendLine()
        appendLine("4. **STAR法则使用**")
        appendLine("   - Situation（情境）是否交代清楚")
        appendLine("   - Task（任务）是否明确")
        appendLine("   - Action（行动）是否具体且有主导性")
        appendLine("   - Result（结果）是否量化或有影响力")
        appendLine()
        appendLine("【输出格式】（严格 JSON）：")
        appendLine("```json")
        appendLine("{")
        appendLine("  \"logicScore\": 7,")
        appendLine("  \"clarityScore\": 8,")
        appendLine("  \"confidenceScore\": 6,")
        appendLine("  \"starUsage\": \"使用了STAR但Result部分不够量化\",")
        appendLine("  \"quickFeedback\": \"整体结构清晰，建议多用数据支撑观点\",")
        appendLine("  \"detailedFeedback\": \"详细的改进建议...\"")
        appendLine("}")
        appendLine("```")
        appendLine()
        appendLine("【重要】feedback 要建设性、具体、可操作，避免空泛的好话或打击。")
    }

    // ==================== Persona 3: 评估者 ====================

    /**
     * 构建评估者 System Prompt（用于最终报告生成）。
     */
    fun buildEvaluatorPrompt(): String = buildString {
        appendLine("你是一位专业的面试评估专家，拥有15年以上的招聘和人才评估经验。")
        appendLine()
        appendLine("请根据提供的完整面试对话记录，从多个维度对候选人进行全面评估。")
        appendLine()
        appendLine("【评估维度】")
        appendLine()
        appendLine("1. **专业知识** (权重25%)")
        appendLine("   - 技术能力/领域知识掌握程度")
        appendLine("   - 回答的准确性和深度")
        appendLine("   - 对前沿技术的了解")
        appendLine()
        appendLine("2. **沟通表达** (权重20%)")
        appendLine("   - 语言表达的流畅性和逻辑性")
        appendLine("   - 倾听和理解能力")
        appendLine("   - 回答的针对性")
        appendLine()
        appendLine("3. **逻辑思维** (权重20%)")
        appendLine("   - 分析问题的思路")
        appendLine("   - 结构化思考能力")
        appendLine("   - 应对追问的反应速度和质量")
        appendLine()
        appendLine("4. **问题解决** (权重15%)")
        appendLine("   - 描述解决方案的能力")
        appendLine("   - 是否考虑边界情况和trade-off")
        appendLine("   - 创新性思维")
        appendLine()
        appendLine("5. **文化匹配** (权重10%)")
        appendLine("   - 价值观与岗位/公司的契合度")
        appendLine("   - 团队协作意识")
        appendLine("   - 学习成长态度")
        appendLine()
        appendLine("6. **压力应对** (权重10%)")
        appendLine("   - 面对难题时的反应")
        appendLine("   - 是否保持冷静和专业")
        appendLine("   - 承认不足时的态度")
        appendLine()
        appendLine("【判定标准】")
        appendLine("- **PASS（通过）**: 总分≥75，核心维度无低于6分项")
        appendLine("- **CONDITIONAL_PASS（有条件通过）**: 总分60-74，或存在1-2个薄弱维度")
        appendLine("- **FAIL（未通过）**: 总分<60，或存在多个严重短板")
        appendLine()
        appendLine("【输出格式】（严格 JSON）：")
        appendLine("```json")
        appendLine("{")
        appendLine("  \"overallScore\": 78,")
        appendLine("  \"verdict\": \"PASS\",")
        appendLine("  \"summary\": \"总体评价（2-3句话）\",")
        appendLine("  \"strengths\": [\"优势1\", \"优势2\", ...],")
        appendLine("  \"weaknesses\": [\"不足1\", \"不足2\", ...],")
        appendLine("  \"recommendations\": [\"建议1\", \"建议2\", ...],")
        appendLine("  \"dimensions\": [")
        appendLine("    {")
        appendLine("      \"name\": \"专业知识\",")
        appendLine("      \"score\": 8,")
        appendLine("      \"feedback\": \"维度反馈\",")
        appendLine("      \"highlights\": [\"亮点1\"],")
        appendLine("      \"improvements\": [\"改进点1\"]")
        appendLine("    }")
        appendLine("  ]")
        appendLine("}")
        appendLine("```")
    }

    // ==================== 核心业务方法 ====================

    /**
     * 分析用户提交的材料（简历/毕设/作品集），生成面试策略。
     *
     * @param llmClient LLM 客户端
     * @param materials 用户提交的材料文本
     * @param interviewType 面试类型
     * @return 材料分析结果
     */
    suspend fun analyzeMaterials(
        llmClient: LlmClient,
        materials: String,
        interviewType: InterviewType,
    ): AnalysisResult {
        DebugLog.i(TAG, "开始分析材料: ${materials.length} 字符")

        val analysisPrompt = buildString {
            appendLine("请分析以下${when (interviewType) {
                InterviewType.JOB_INTERVIEW -> "求职简历"
                InterviewType.THESIS_DEFENSE -> "论文/研究材料"
                InterviewType.SCENARIO -> "背景材料"
                else -> "材料"
            }}，提取关键信息并为面试官提供建议。")
            appendLine()
            appendLine("【材料内容】")
            appendLine(materials)
            appendLine()
            appendLine("请输出以下结构的分析结果（JSON 格式）：")
            appendLine("{")
            appendLine("  \"keyPoints\": [\"关键点1\", \"关键点2\", ...],")
            appendLine("  \"suggestedQuestions\": [\"建议提问方向1\", ...],")
            appendLine("  \"riskAreas\": [\"可能被深挖的风险点1\", ...],")
            appendLine("  \"interviewStrategy\": \"面试策略建议（一段话）\"")
            appendLine("}")
        }

        val response = callLlm(
            llmClient = llmClient,
            systemPrompt = "你是一位资深HR/学术导师，擅长从简历/材料中快速识别关键信息和潜在风险。",
            userMessage = analysisPrompt,
        )

        return parseAnalysisResult(response)
    }

    /**
     * 生成第一个问题（面试开场白 + 第一题）。
     *
     * @param llmClient LLM 客户端
     * @param config 面试配置
     * @param context 已有的对话上下文（通常为空，用于开场）
     * @return 第一个问题文本
     */
    suspend fun generateFirstQuestion(
        llmClient: LlmClient,
        config: InterviewConfig,
        context: List<DialogueTurn> = emptyList(),
    ): String {
        DebugLog.i(TAG, "生成第一个问题")

        val systemPrompt = buildInterviewerPrompt(config)

        val introPrompt = buildString {
            appendLine("请开始面试。")
            when (config.type) {
                InterviewType.JOB_INTERVIEW -> {
                    appendLine("首先简短自我介绍（作为面试官），说明面试流程（约${config.questionCount}个问题），然后提出第一个面试问题。")
                    appendLine("自我介绍控制在2-3句话，专业但不失亲和。")
                }
                InterviewType.THESIS_DEFENSE -> {
                    appendLine("首先作为答辩委员会主席介绍答辩流程和规则，然后提出第一个质询性问题。")
                    appendLine("语气要正式、严谨。")
                }
                InterviewType.SCENARIO -> {
                    appendLine("根据设定的场景开始面试，先做简要说明，然后提出第一个问题。")
                }
            }
        }

        return callLlm(
            llmClient = llmClient,
            systemPrompt = systemPrompt,
            userMessage = introPrompt,
            history = contextToMessages(context),
        )
    }

    /**
     * 处理候选人回答 → 生成追问或下一题。
     *
     * @param llmClient LLM 客户端
     * @param config 面试配置
     * @param answer 候选人回答文本
     * @param currentQuestion 当前问题
     * @param history 完整对话历史
     * @return 下一步动作（FollowUp / NextQuestion / EndInterview）
     */
    suspend fun processAnswer(
        llmClient: LlmClient,
        config: InterviewConfig,
        answer: String,
        currentQuestion: DialogueTurn,
        history: List<DialogueTurn>,
        currentQuestionIndex: Int,
    ): NextAction {
        DebugLog.i(TAG, "处理回答 #$currentQuestionIndex: '${answer.take(50)}...'")

        val systemPrompt = buildInterviewerPrompt(config)
        val isLastQuestion = currentQuestionIndex >= config.questionCount - 1

        val followUpPrompt = buildString {
            appendLine("【候选人第 ${currentQuestionIndex + 1} 个回答】")
            appendLine(answer)
            appendLine()
            appendLine("当前问题是第 ${currentQuestionIndex + 1}/${config.questionCount} 题")

            if (isLastQuestion) {
                appendLine()
                appendLine("⚠️ 这是最后一个问题！")
                appendLine("请对候选人的整个面试表现给出总结性评价（简短、客观），然后宣布面试结束。")
                appendLine("用 [FEEDBACK] 给出结束评语，用 [END] 标记结束。不要再提问。")
            } else {
                appendLine()
                appendLine("请根据候选人的回答质量决定下一步：")
                appendLine("- 如果回答不完整或模糊 → 追问（FollowUp）")
                appendLine("- 如果回答完整且质量好 → 进入下一题（NextQuestion），适当提高难度")
                appendLine("- 保持问题之间的连贯性和渐进性")
            }
        }

        val response = callLlm(
            llmClient = llmClient,
            systemPrompt = systemPrompt,
            userMessage = followUpPrompt,
            history = contextToMessages(history),
        )

        return parseNextAction(response, isLastQuestion)
    }

    /**
     * 生成教练反馈（每轮回答后调用）。
     *
     * @param llmClient LLM 客户端
     * @param question 当前问题
     * @param answer 候选人回答
     * @return 教练反馈对象
     */
    suspend fun generateCoachFeedback(
        llmClient: LlmClient,
        question: DialogueTurn,
        answer: DialogueTurn,
    ): CoachFeedback? {
        DebugLog.d(TAG, "生成教练反馈")

        try {
            val coachPrompt = buildString {
                appendLine("【面试官问题】")
                appendLine(question.content)
                appendLine()
                appendLine("【候选人回答】")
                appendLine(answer.content)
                appendLine()
                appendLine("请根据上述问答，按照System Prompt中的格式输出教练反馈JSON。")
            }

            val response = callLlm(
                llmClient = llmClient,
                systemPrompt = buildCoachPrompt(),
                userMessage = coachPrompt,
            )

            return parseCoachFeedback(response)
        } catch (e: Exception) {
            DebugLog.w(TAG, "生成教练反馈失败: ${e.message}")
            return null
        }
    }

    /**
     * 生成最终评估报告。
     *
     * @param llmClient LLM 客户端
     * @param config 面试配置
     * @param fullTranscript 完整对话记录
     * @return 完整的面试报告
     */
    suspend fun generateReport(
        llmClient: LlmClient,
        config: InterviewConfig,
        fullTranscript: List<DialogueTurn>,
    ): InterviewReport {
        DebugLog.i(TAG, "生成评估报告: 共 ${fullTranscript.size} 条对话")

        val sessionId = UUID.randomUUID().toString()
        val durationSeconds = if (fullTranscript.isNotEmpty()) {
            (fullTranscript.last().timestamp - fullTranscript.first().timestamp) / 1000
        } else 0L

        val reportPrompt = buildString {
            appendLine("请根据以下完整面试对话记录，生成结构化的面试评估报告。")
            appendLine()
            appendLine("【面试信息】")
            appendLine("- 类型: ${config.type.label}")
            appendLine("- 公司/机构: ${config.company.ifBlank { "未指定" }}")
            appendLine("- 岗位/方向: ${config.position.ifBlank { "未指定" }}")
            appendLine("- 难度: ${config.difficulty.label}")
            appendLine("- 总问题数: ${fullTranscript.filter { it.role == "interviewer" }.size}")
            appendLine("- 面试时长: ${durationSeconds} 秒")
            appendLine()
            appendLine("【对话记录】")
            fullTranscript.forEachIndexed { index, turn ->
                appendLine("${index + 1}. [${turn.role}] ${turn.content.take(200)}${if (turn.content.length > 200) "..." else ""}")
            }
            appendLine()
            appendLine("请严格按照 Evaluator System Prompt 中的 JSON 格式输出报告。")
        }

        val response = callLlm(
            llmClient = llmClient,
            systemPrompt = buildEvaluatorPrompt(),
            userMessage = reportPrompt,
            history = contextToMessages(fullTranscript),
        )

        return parseInterviewReport(sessionId, config.type, response, fullTranscript, durationSeconds)
    }

    // ==================== 内部工具方法 ====================

    /**
     * 调用 LLM（非流式）。
     */
    private suspend fun callLlm(
        llmClient: LlmClient,
        systemPrompt: String,
        userMessage: String,
        history: List<ChatMessage> = emptyList(),
    ): String {
        return try {
            val allMessages = history.toMutableList()
            allMessages.add(ChatMessage(role = Role.USER, content = userMessage))

            llmClient.chatCompletions(
                config = ApiConfig(
                    baseUrl = "",  // 将由外部注入
                    apiKey = "",
                    model = "",
                ),
                system = systemPrompt,
                messages = allMessages,
            )
        } catch (e: Exception) {
            DebugLog.e(TAG, "LLM 调用失败: ${e.message}")
            "抱歉，AI 服务暂时不可用，请稍后重试。"
        }
    }

    /**
     * 将 DialogueTurn 列表转换为 ChatMessage 列表。
     */
    private fun contextToMessages(context: List<DialogueTurn>): List<ChatMessage> {
        return context.map { turn ->
            ChatMessage(
                role = if (turn.role == "interviewer") Role.ASSISTANT else Role.USER,
                content = turn.content,
            )
        }
    }

    /**
     * 解析 NextAction（从 LLM 响应中提取）。
     */
    private fun parseNextAction(response: String, isLastQuestion: Boolean): NextAction {
        val feedback = extractTag(response, "[FEEDBACK]")
        val question = extractTag(response, "[QUESTION]")
        val endMarker = response.contains("[END]")

        return when {
            isLastQuestion || endMarker -> {
                val closingText = if (question.isBlank()) {
                    response.replace("[FEEDBACK]", "").replace("[END]", "").trim()
                } else {
                    feedback
                }
                NextAction.EndInterview(closingText.ifBlank { "面试已结束，感谢您的参与。" })
            }
            question.isNotBlank() && feedback.isNotBlank() -> {
                // 有追问标记（通过 feedback 内容判断是否是追问）
                if (feedback.contains("追问") || feedback.contains("补充") || feedback.contains("具体")) {
                    NextAction.FollowUp(question, feedback)
                } else {
                    NextAction.NextQuestion(question, inferCategory(question))
                }
            }
            question.isNotBlank() -> {
                NextAction.NextQuestion(question, inferCategory(question))
            }
            else -> {
                // 兜底：将整个响应作为下一题
                NextAction.NextQuestion(response.trim(), "综合")
            }
        }
    }

    /**
     * 推断问题分类。
     */
    private fun inferCategory(question: String): String = when {
        question.contains("描述") || question.contains("经历") || question.contains("一次") -> "行为面试"
        question.contains("技术") || question.contains("设计") || question.contains("架构") || question.contains("算法") -> "技术能力"
        question.contains("如果") || question.contains("遇到") || question.contains("怎么处理") -> "情境应对"
        question.contains("为什么") || question.contains("原因") || question.contains("考虑") -> "思维深度"
        question.contains("项目") || question.contains("产品") -> "项目经验"
        else -> "综合考察"
    }

    /**
     * 提取标记内容。
     */
    private fun extractTag(text: String, tag: String): String {
        val tagIndex = text.indexOf(tag)
        if (tagIndex < 0) return ""

        val afterTag = text.substring(tagIndex + tag.length).trim()
        val nextTags = listOf("[FEEDBACK]", "[QUESTION]", "[END]")
        var endIndex = afterTag.length
        for (pattern in nextTags) {
            val idx = afterTag.indexOf(pattern)
            if (idx in 1 until endIndex) {
                endIndex = idx
            }
        }
        return afterTag.substring(0, endIndex).trim()
    }

    /**
     * 解析材料分析结果。
     */
    private fun parseAnalysisResult(response: String): AnalysisResult {
        return try {
            val jsonStr = extractJsonFromResponse(response)
            val json = org.json.JSONObject(jsonStr)

            AnalysisResult(
                keyPoints = json.optJSONArray("keyPoints")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList(),
                suggestedQuestions = json.optJSONArray("suggestedQuestions")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList(),
                riskAreas = json.optJSONArray("riskAreas")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList(),
                interviewStrategy = json.optString("interviewStrategy", ""),
            )
        } catch (e: Exception) {
            DebugLog.w(TAG, "解析分析结果失败: ${e.message}")
            AnalysisResult(
                keyPoints = listOf(response.take(500)),
                suggestedQuestions = emptyList(),
                riskAreas = emptyList(),
                interviewStrategy = "",
            )
        }
    }

    /**
     * 解析教练反馈。
     */
    private fun parseCoachFeedback(response: String): CoachFeedback? {
        return try {
            val jsonStr = extractJsonFromResponse(response)
            val json = org.json.JSONObject(jsonStr)

            CoachFeedback(
                logicScore = json.optFloat("logicScore", 5f),
                clarityScore = json.optFloat("clarityScore", 5f),
                confidenceScore = json.optFloat("confidenceScore", 5f),
                starUsage = json.optString("starUsage", ""),
                quickFeedback = json.optString("quickFeedback", ""),
                detailedFeedback = json.optString("detailedFeedback", ""),
            )
        } catch (e: Exception) {
            DebugLog.w(TAG, "解析教练反馈失败: ${e.message}")
            null
        }
    }

    /**
     * 解析面试报告。
     */
    private fun parseInterviewReport(
        sessionId: String,
        type: InterviewType,
        response: String,
        transcript: List<DialogueTurn>,
        durationSeconds: Long,
    ): InterviewReport {
        return try {
            val jsonStr = extractJsonFromResponse(response)
            val json = org.json.JSONObject(jsonStr)

            val verdictStr = json.optString("verdict", "CONDITIONAL_PASS")
            val verdict = runCatching {
                Verdict.valueOf(verdictStr)
            }.getOrDefault(Verdict.CONDITIONAL_PASS)

            val overallScore = json.optDouble("overallScore", 60.0).toFloat()

            InterviewReport(
                sessionId = sessionId,
                type = type,
                overallScore = overallScore,
                verdict = verdict,
                dimensions = json.optJSONArray("dimensions")?.let { arr ->
                    (0 until arr.length()).map { i ->
                        val dim = arr.getJSONObject(i)
                        EvaluationDimension(
                            name = dim.optString("name", ""),
                            score = dim.optDouble("score", 5.0).toFloat(),
                            maxScore = 10f,
                            feedback = dim.optString("feedback", ""),
                            highlights = dim.optJSONArray("highlights")?.let { h ->
                                (0 until h.length()).map { h.getString(it) }
                            } ?: emptyList(),
                            improvements = dim.optJSONArray("improvements")?.let { imp ->
                                (0 until imp.length()).map { imp.getString(it) }
                            } ?: emptyList(),
                        )
                    }
                } ?: emptyList(),
                summary = json.optString("summary", ""),
                strengths = json.optJSONArray("strengths")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList(),
                weaknesses = json.optJSONArray("weaknesses")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList(),
                recommendations = json.optJSONArray("recommendations")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList(),
                questionCount = transcript.count { it.role == "interviewer" },
                durationSeconds = durationSeconds,
                transcript = transcript,
            )
        } catch (e: Exception) {
            DebugLog.e(TAG, "解析报告失败: ${e.message}")

            // 返回兜底报告
            InterviewReport(
                sessionId = sessionId,
                type = type,
                overallScore = 60f,
                verdict = Verdict.CONDITIONAL_PASS,
                dimensions = emptyList(),
                summary = response.take(300),
                strengths = emptyList(),
                weaknesses = emptyList(),
                recommendations = emptyList(),
                questionCount = transcript.count { it.role == "interviewer" },
                durationSeconds = durationSeconds,
                transcript = transcript,
            )
        }
    }

    /**
     * 从响应中提取 JSON 字符串。
     */
    private fun extractJsonFromResponse(response: String): String {
        // 尝试提取 ```json ... ``` 代码块
        val jsonBlockPattern = Regex("```json\\s*\\n?(.*?)\\n?```", RegexOption.DOT_MATCHES_ALL)
        val match = jsonBlockPattern.find(response)
        if (match != null) {
            return match.groupValues[1].trim()
        }

        // 尝试直接找 JSON 对象
        val jsonPattern = Regex("\\{.*\\}", RegexOption.DOT_MATCHES_ALL)
        val jsonMatch = jsonPattern.find(response)
        if (jsonMatch != null) {
            return jsonMatch.value
        }

        return response
    }
}
