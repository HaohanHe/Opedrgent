package top.hsyscn.opedrgent.interview

import top.hsyscn.opedrgent.model.ChatMessage
import top.hsyscn.opedrgent.model.Role
import top.hsyscn.opedrgent.network.LlmClient
import top.hsyscn.opedrgent.settings.ApiConfig
import top.hsyscn.opedrgent.utils.DebugLog
import java.util.UUID

/**
 * 面试 Agent — 核心驱动引擎（LLM 自主决策架构）。
 *
 * ## 架构理念（v2.0 重构）
 *
 * **旧架构（已废弃）：规则驱动**
 * - 3套硬编码 Prompt 模板（求职/答辩/场景）
 * - 8条固定行为规则
 * - 固定问题风格递进策略
 * - 固定6评估维度 + 权重 + 阈值(PASS≥75)
 * - [FEEDBACK][QUESTION][END] 硬编码标记
 *
 * **新架构：LLM 自主决策**
 * - 1个元 Prompt 模板（告诉 LLM 角色和目标，不教它怎么做）
 * - 所有行为参数通过 [InterviewConfig] 注入
 * - 评估体系完全开放（维度、权重、阈值由 LLM 动态决定）
 * - 轻量 JSON 输出协议
 *
 * 参考飞书 APK 的 Agent 架构设计：
 * - 场景驱动而非规则堆砌
 * - 元指令（meta-prompt）而非规则清单
 * - 配置注入而非硬编码预设
 */
object InterviewAgent {

    private const val TAG = "InterviewAgent"

    // ==================== 海马体记忆系统 ====================

    /**
     * 活跃的海马体实例映射（sessionId → HippocampusMemory）。
     *
     * 支持多个面试会话同时运行，每个会话有独立的海马体实例。
     */
    private val activeHippocampus = mutableMapOf<String, HippocampusMemory>()

    /**
     * 创建带海马体保护的面试会话。
     *
     * @param sessionId 会话唯一标识
     * @param config 面试配置
     * @return 海马体实例（已锚定目标）
     */
    fun createSession(sessionId: String, config: InterviewConfig): HippocampusMemory {
        DebugLog.i(TAG, "创建海马体会话: $sessionId")

        // 清理旧会话（如果存在）
        activeHippocampus[sessionId]?.reset()

        val hippo = HippocampusMemory(config)
        hippo.anchorGoal()
        activeHippocampus[sessionId] = hippo
        return hippo
    }

    /**
     * 获取指定会话的海马体实例。
     */
    fun getHippocampus(sessionId: String): HippocampusMemory? = activeHippocampus[sessionId]

    /**
     * 关闭指定会话的海马体并获取漂移报告。
     */
    fun closeSession(sessionId: String): HippocampusMemory.DriftReport? {
        val hippo = activeHippocampus.remove(sessionId) ?: return null
        DebugLog.i(TAG, "关闭海马体会话: $sessionId")
        return hippo.getDriftReport()
    }

    // ==================== 核心：单一元 Prompt ====================

    /**
     * 构建统一的面试官 System Prompt（元指令）。
     *
     * 设计原则：
     * 1. **角色定义**（1-2句）：你是什么角色
     * 2. **目标定义**（1-2句）：你要达成什么目标
     * 3. **能力边界**（2-3句）：你能做什么不能做什么
     * 4. **输出协议**（结构化格式）：让 LLM 按约定格式输出以便解析
     * 5. **上下文注入**：config 中所有用户配置的信息原样传入
     *
     * 不再写死任何行为规则，而是让 LLM 根据场景自主决策。
     *
     * @param config 面试配置（包含所有用户指定的参数和约束）
     * @return 完整的元 Prompt 文本
     */
    fun buildUnifiedPrompt(config: InterviewConfig): String = buildString {
        // ========== 第一部分：角色与目标定义 ==========
        appendLine("你是一位专业的对话引导者。")
        appendLine()

        // 注入场景描述（核心！）
        val scenario = config.getEffectiveScenarioDescription()
        appendLine("【你的任务】")
        appendLine("正在参与一场「${scenario}」的模拟对话。你需要扮演面试官/考官/评委的角色，")
        appendLine("根据场景特点自主决定提问内容、追问策略、节奏控制和结束时机。")
        appendLine()

        // ========== 第二部分：风格与约束注入 ==========
        appendLine("【交互风格】")
        when (config.interviewerStyle) {
            InterviewerStyle.PROFESSIONAL -> {
                appendLine("- 专业严谨，客观中立，像真实的资深面试官")
            }
            InterviewerStyle.FRIENDLY -> {
                appendLine("- 友善亲和，营造轻松氛围，适合初学者练习")
            }
            InterviewerStyle.RIGOROUS -> {
                appendLine("- 严格苛刻，高压挑战，测试候选人极限反应")
            }
            InterviewerStyle.CASUAL -> {
                appendLine("- 随性自然，像真实聊天一样，不刻意保持面试感")
            }
        }
        appendLine("- 对话语言：${config.language}")
        appendLine()

        // ========== 第三部分：上下文信息注入 ==========
        val contextParts = mutableListOf<String>()

        if (config.company.isNotBlank()) {
            contextParts.add("公司/机构：${config.company}")
        }
        if (config.position.isNotBlank()) {
            contextParts.add("岗位/主题：${config.position}")
        }
        contextParts.add("参考难度：${config.difficulty.label} (${config.difficulty.level}/10)")
        contextParts.add("预期问题数：约 ${config.questionCount} 个（可根据实际情况调整）")
        contextParts.add("时间限制：约 ${config.durationMinutes} 分钟")

        if (contextParts.isNotEmpty()) {
            appendLine("【基本信息】")
            contextParts.forEach { appendLine("- $it") }
            appendLine()
        }

        // 材料信息
        val materialsText = config.getMaterialsText()
        if (materialsText.isNotBlank()) {
            appendLine("【候选人背景材料】")
            appendLine(materialsText)
            appendLine()
        }

        // 用户自定义指令（最高优先级）
        if (config.customInstructions.isNotBlank()) {
            appendLine("【用户的特殊要求】（请务必遵守）")
            appendLine(config.customInstructions)
            appendLine()
        }

        // 评估维度（如果用户指定了）
        if (!config.evalDimensions.isNullOrEmpty()) {
            appendLine("【用户指定的评估维度】")
            appendLine(config.evalDimensions.joinToString("、"))
            appendLine("(请在最终评估时重点关注以上维度)")
            appendLine()
        }

        // 判定阈值（如果用户指定了）
        if (config.verdictThresholds != null) {
            appendLine("【判定标准】")
            appendLine("- 通过(PASS)：总分 ≥ ${config.verdictThresholds.passScore}")
            appendLine("- 有条件通过(CONDITIONAL_PASS)：总分 ≥ ${config.verdictThresholds.conditionalPassScore}")
            appendLine("- 未通过(FAIL)：总分 < ${config.verdictThresholds.conditionalPassScore}")
            appendLine()
        }

        // ========== 第四部分：能力边界（轻量级） ==========
        appendLine("【能力与边界】")
        appendLine("- 你需要根据候选人的回答质量，自主决定是继续追问、进入下一题还是结束面试")
        appendLine("- 追问深度、问题难度递进、时间分配等全部由你根据实际情况灵活掌握")
        appendLine("- 不要给候选人提示或透露评分标准")
        appendLine("- 保持角色一致性，不要出戏")
        appendLine()

        // ========== 第五部分：输出协议 ==========
        appendLine("【输出格式要求】")
        appendLine("每次回复请使用以下 JSON 格式（不要有其他多余内容）：")
        appendLine("```json")
        appendLine("{")
        appendLine("  \"action\": \"follow_up\" | \"next_question\" | \"end_interview\",")
        appendLine("  \"content\": \"你说的话（开场白/问题/追问/结束语）\",")
        appendLine("  \"reason\": \"为什么选择这个动作（简短说明）\",")
        appendLine("  \"category\": \"问题分类标签（可选，如'技术能力'/'行为面试'等）\"")
        appendLine("}")
        appendLine("```")
        appendLine()
        appendLine("注意：开场白时 action 为 \"next_question\"，content 包含自我介绍和第一个问题。")
        appendLine("注意：结束面试时 action 为 \"end_interview\"，content 包含总结评语。")
    }

    /**
     * 构建教练 System Prompt（开放式评估）。
     *
     * 教练反馈的评估维度不再是固定的4项，
     * 而是由 LLM 根据 config.evalDimensions 或场景自行决定。
     */
    fun buildCoachPrompt(config: InterviewConfig): String = buildString {
        appendLine("你是一位专业的对话教练，正在观察一场对话并给出实时反馈。")
        appendLine()

        appendLine("【职责】")
        appendLine("分析参与者当前回合的表现质量，从多个维度给出客观评价和改进建议。")

        // 如果用户指定了评估维度
        if (!config.evalDimensions.isNullOrEmpty()) {
            appendLine()
            appendLine("【重点评估维度】")
            config.evalDimensions.forEachIndexed { index, dim ->
                appendLine("${index + 1}. $dim")
            }
            appendLine("(请主要围绕以上维度进行评分和反馈)")
        } else {
            appendLine()
            appendLine("【评估方式】")
            appendLine("根据当前对话场景的特点，自主决定应该关注哪些评估维度。")
            appendLine("例如：求职面试可关注逻辑/表达/专业性；口语考试可关注流利度/语法/词汇；答辩可关注方法论/创新性等。")
        }

        appendLine()
        appendLine("【输出格式】（严格 JSON）：")
        appendLine("```json")
        appendLine("{")
        appendLine("  \"scores\": {")
        appendLine("    \"维度名称\": 分数(0-10),")
        appendLine("    \"...\": \"...\"")
        appendLine("  },")
        appendLine("  \"quickFeedback\": \"快速反馈（1-2句话，面向参与者）\",")
        appendLine("  \"detailedFeedback\": \"详细反馈建议（具体、可操作、建设性）\"")
        appendLine("}")
        appendLine("```")
        appendLine()
        appendLine("【重要】feedback 要建设性、具体、可操作，避免空泛的好话或打击。")
    }

    /**
     * 构建评估者 System Prompt（用于最终报告生成，开放式）。
     *
     * 评估维度、权重、判定阈值均由 LLM 根据场景动态决定，
     * 不再预设固定的6维度体系。
     */
    fun buildEvaluatorPrompt(config: InterviewConfig): String = buildString {
        appendLine("你是一位专业的对话评估专家，拥有丰富的评估经验。")
        appendLine()

        appendLine("【任务】")
        appendLine("根据提供的完整对话记录，对参与者进行全面评估并生成结构化报告。")
        appendLine()

        // 场景信息
        appendLine("【对话场景】")
        appendLine("- 类型：${config.type.label}")
        appendLine("- 场景描述：${config.getEffectiveScenarioDescription()}")
        if (config.company.isNotBlank()) appendLine("- 公司/机构：${config.company}")
        if (config.position.isNotBlank()) appendLine("- 岗位/主题：${config.position}")

        // 评估维度指导
        if (!config.evalDimensions.isNullOrEmpty()) {
            appendLine()
            appendLine("【用户指定的评估维度（必须覆盖）】")
            config.evalDimensions.forEach { appendLine("- $it") }
        } else {
            appendLine()
            appendLine("【评估维度】")
            appendLine("请根据对话场景的特点，自主确定合适的评估维度（通常3-8个）。")
            appendLine("每个维度应具有明确的评估标准和区分度。")
        }

        // 判定标准
        if (config.verdictThresholds != null) {
            appendLine()
            appendLine("【判定标准（用户指定）】")
            appendLine("- PASS（通过）：总分 ≥ ${config.verdictThresholds.passScore}")
            appendLine("- CONDITIONAL_PASS（有条件通过）：${config.verdictThresholds.conditionalPassScore} ≤ 总分 < ${config.verdictThresholds.passScore}")
            appendLine("- FAIL（未通过）：总分 < ${config.verdictThresholds.conditionalPassScore}")
        } else {
            appendLine()
            appendLine("【判定标准】")
            appendLine("请根据场景特点自主设定合理的判定阈值，并在报告中说明。")
            appendLine("一般而言：PASS 表示表现优秀，CONDITIONAL_PASS 表示基本合格但有明显短板，FAIL 表示未达到要求。")
        }

        appendLine()
        appendLine("【输出格式】（严格 JSON）：")
        appendLine("```json")
        appendLine("{")
        appendLine("  \"overallScore\": 总分(0-100),")
        appendLine("  \"verdict\": \"PASS\" | \"CONDITIONAL_PASS\" | \"FAIL\",")
        appendLine("  \"summary\": \"总体评价（2-3句话）\",")
        appendLine("  \"strengths\": [\"优势1\", \"优势2\"],")
        appendLine("  \"weaknesses\": [\"不足1\", \"不足2\"],")
        appendLine("  \"recommendations\": [\"建议1\", \"建议2\"],")
        appendLine("  \"dimensions\": [")
        appendLine("    {")
        appendLine("      \"name\": \"维度名称\",")
        appendLine("      \"score\": 得分(0-10),")
        appendLine("      \"feedback\": \"维度反馈（具体说明得分理由）\",")
        appendLine("      \"highlights\": [\"亮点1\"],")
        appendLine("      \"improvements\": [\"改进点1\"]")
        appendLine("    }")
        appendLine("  ]")
        appendLine("}")
        appendLine("```")
    }

    // ==================== 业务方法（接口不变，内部实现改变） ====================

    /**
     * 分析用户提交的材料（简历/毕设/作品集），生成面试策略。
     *
     * 改动：分析 prompt 统一化，让 LLM 根据场景自行决定分析重点，
     * 不再硬编码"求职简历"/"论文材料"的分类逻辑。
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
            appendLine("请分析以下材料，提取关键信息并为后续对话提供建议。")
            appendLine()
            appendLine("【对话类型】${interviewType.label}")
            if (interviewType == InterviewType.CUSTOM && interviewType.label.isNotBlank()) {
                appendLine("【场景说明】${interviewType.label}")
            }
            appendLine()
            appendLine("【材料内容】")
            appendLine(materials)
            appendLine()
            appendLine("请根据材料特点和对话场景，自主决定分析的重点方向，然后输出以下结构的分析结果（JSON 格式）：")
            appendLine("{")
            appendLine("  \"keyPoints\": [\"关键点1\", \"关键点2\", ...],")
            appendLine("  \"suggestedQuestions\": [\"建议提问方向1\", ...],")
            appendLine("  \"riskAreas\": [\"可能被深挖的风险点1\", ...],")
            appendLine("  \"interviewStrategy\": \"策略建议（一段话，针对此场景）\"")
            appendLine("}")
        }

        val response = callLlm(
            llmClient = llmClient,
            systemPrompt = "你是一位资深的材料分析师，擅长从各类文档中快速识别关键信息、亮点和潜在风险点，并根据不同场景提供针对性的策略建议。",
            userMessage = analysisPrompt,
        )

        return parseAnalysisResult(response)
    }

    /**
     * 生成第一个问题（面试开场白 + 第一题）。
     *
     * 改动：使用统一的 [buildUnifiedPrompt]，开场白也由 LLM 自由发挥，
     * 不再为每种类型写死不同的开场白模板。
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

        val systemPrompt = buildUnifiedPrompt(config)

        val introPrompt = buildString {
            appendLine("请开始这场对话。")
            appendLine("首先做简短的角色介绍和流程说明，然后提出第一个问题。")
            appendLine("介绍控制在2-3句话，自然地过渡到第一个问题。")
        }

        val response = callLlm(
            llmClient = llmClient,
            systemPrompt = systemPrompt,
            userMessage = introPrompt,
            history = contextToMessages(context),
        )

        // 从 JSON 响应中提取 content 字段
        return extractContentFromJsonResponse(response)
    }

    /**
     * 处理候选人回答 → 生成追问或下一题。
     *
     * 改动：LLM 通过 JSON action 字段自主决定 FollowUp / NextQuestion / EndInterview，
     * 不再依赖 [FEEDBACK][QUESTION][END] 标记解析。
     *
     * @param llmClient LLM 客户端
     * @param config 面试配置
     * @param answer 候选人回答文本
     * @param currentQuestion 当前问题
     * @param history 完整对话历史
     * @param currentQuestionIndex 当前问题索引
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

        val systemPrompt = buildUnifiedPrompt(config)
        val isLastQuestion = currentQuestionIndex >= config.questionCount - 1

        val followUpPrompt = buildString {
            appendLine("【参与者第 ${currentQuestionIndex + 1} 个回答】")
            appendLine(answer)
            appendLine()
            appendLine("当前进度：第 ${currentQuestionIndex + 1}/${config.questionCount} 题")

            if (isLastQuestion) {
                appendLine()
                appendLine("注意：这已是计划中的最后一个问题。")
                appendLine("如果你认为已经获得了足够的信息来做出评估，可以选择结束对话（action: end_interview）。")
                appendLine("如果认为还需要更多信息，可以继续提问（action: next_question 或 follow_up）。")
            } else {
                appendLine()
                appendLine("请根据回答质量和对话进展，自主决定下一步动作：")
                appendLine("- 回答不完整或模糊 → follow_up（追问）")
                appendLine("- 回答完整且质量好 → next_question（下一题）")
                appendLine("- 已获得足够信息 → end_interview（结束）")
            }
        }

        val response = callLlm(
            llmClient = llmClient,
            systemPrompt = systemPrompt,
            userMessage = followUpPrompt,
            history = contextToMessages(history),
        )

        return parseNextActionFromJson(response)
    }

    /**
     * 处理候选人回答 → 生成追问或下一题（带海马体注意力管理版本）。
     *
     * 与原版 [processAnswer] 的区别：
     * - 在调用 LLM 前，通过海马体获取注意力上下文
     * - 将注意力上下文作为额外的 system message 注入
     * - LLM 回复后，海马体检测漂移并记录
     *
     * @param llmClient LLM 客户端
     * @param config 面试配置
     * @param answer 候选人回答文本
     * @param currentQuestion 当前问题
     * @param history 完整对话历史
     * @param currentQuestionIndex 当前问题索引
     * @param hippo 海马体实例（必须已调用 anchorGoal）
     * @return 下一步动作（FollowUp / NextQuestion / EndInterview）
     */
    suspend fun processAnswerWithAttention(
        llmClient: LlmClient,
        config: InterviewConfig,
        answer: String,
        currentQuestion: DialogueTurn,
        history: List<DialogueTurn>,
        currentQuestionIndex: Int,
        hippo: HippocampusMemory,
    ): NextAction {
        DebugLog.i(TAG, "处理回答（海马体保护） #$currentQuestionIndex: '${answer.take(50)}...'")

        val systemPrompt = buildUnifiedPrompt(config)
        val isLastQuestion = currentQuestionIndex >= config.questionCount - 1

        // 1. 海马体准备注意力上下文
        val lastAiResponse = history.lastOrNull()?.aiMessage ?: currentQuestion.content
        val attentionContext = hippo.prepareTurnContext(
            turnIndex = currentQuestionIndex,
            userMessage = answer,
            lastAiResponse = lastAiResponse,
        )

        // 2. 构建用户消息（包含进度信息）
        val followUpPrompt = buildString {
            appendLine("【参与者第 ${currentQuestionIndex + 1} 个回答】")
            appendLine(answer)
            appendLine()
            appendLine("当前进度：第 ${currentQuestionIndex + 1}/${config.questionCount} 题")

            if (isLastQuestion) {
                appendLine()
                appendLine("注意：这已是计划中的最后一个问题。")
                appendLine("如果你认为已经获得了足够的信息来做出评估，可以选择结束对话（action: end_interview）。")
                appendLine("如果认为还需要更多信息，可以继续提问（action: next_question 或 follow_up）。")
            } else {
                appendLine()
                appendLine("请根据回答质量和对话进展，自主决定下一步动作：")
                appendLine("- 回答不完整或模糊 → follow_up（追问）")
                appendLine("- 回答完整且质量好 → next_question（下一题）")
                appendLine("- 已获得足够信息 → end_interview（结束）")
            }
        }

        // 3. 构建消息列表（注入注意力上下文）
        val messages = contextToMessages(history).toMutableList()

        // 注入海马体注意力上下文（作为额外的 system 消息）
        if (attentionContext.isNotBlank()) {
            messages.add(0, ChatMessage(role = Role.SYSTEM, content = attentionContext))
        }

        // 添加当前轮次的用户消息
        messages.add(ChatMessage(role = Role.USER, content = followUpPrompt))

        // 4. 调用 LLM
        val response = callLlmWithMessages(
            llmClient = llmClient,
            systemPrompt = systemPrompt,
            messages = messages,
        )

        // 5. 海马体记录本轮结果（用于漂移追踪）
        val aiContent = extractContentFromJsonResponse(response)
        hippo.detectDrift(currentQuestionIndex, answer, aiContent)

        // 6. 定期更新关键信息快照
        if (currentQuestionIndex > 0 && currentQuestionIndex % HippocampusMemory.SNAPSHOT_INTERVAL == 0) {
            hippo.updateCriticalSnapshot(currentQuestionIndex, "第${currentQuestionIndex + 1}轮对话完成")
        }

        return parseNextActionFromJson(response)
    }

    /**
     * 调用 LLM（支持自定义消息列表）。
     */
    private suspend fun callLlmWithMessages(
        llmClient: LlmClient,
        systemPrompt: String,
        messages: List<ChatMessage>,
    ): String {
        return try {
            llmClient.chatCompletions(
                config = ApiConfig(
                    baseUrl = "",  // 将由外部注入
                    apiKey = "",
                    model = "",
                ),
                system = systemPrompt,
                messages = messages,
            )
        } catch (e: Exception) {
            DebugLog.e(TAG, "LLM 调用失败: ${e.message}")
            "抱歉，AI 服务暂时不可用，请稍后重试。"
        }
    }

    /**
     * 生成教练反馈（每轮回答后调用）。
     *
     * 改动：教练反馈的评估维度也由 LLM 根据 config 决定，
     * 不再固定为 logic/clarity/confidence/star 四项。
     *
     * @param llmClient LLM 客户端
     * @param question 当前问题
     * @param answer 候选人回答
     * @param config 面试配置（用于获取 evalDimensions 等参数）
     * @return 教练反馈对象
     */
    suspend fun generateCoachFeedback(
        llmClient: LlmClient,
        question: DialogueTurn,
        answer: DialogueTurn,
        config: InterviewConfig = InterviewConfig(),
    ): CoachFeedback? {
        DebugLog.d(TAG, "生成教练反馈")

        try {
            val coachPrompt = buildString {
                appendLine("【上一轮对话】")
                appendLine("问：${question.content}")
                appendLine("答：${answer.content}")
                appendLine()
                appendLine("请按照 System Prompt 中的格式输出教练反馈 JSON。")
            }

            val response = callLlm(
                llmClient = llmClient,
                systemPrompt = buildCoachPrompt(config),
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
     * 改动：评估维度、权重、阈值全部由 LLM 动态决定，
     * 不再使用固定的6维度体系和 PASS≥75 阈值。
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
            appendLine("请根据以下完整对话记录，生成结构化的评估报告。")
            appendLine()
            appendLine("【对话信息】")
            appendLine("- 场景：${config.getEffectiveScenarioDescription()}")
            appendLine("- 类型：${config.type.label}")
            if (config.company.isNotBlank()) appendLine("- 公司/机构：${config.company}")
            if (config.position.isNotBlank()) appendLine("- 岗位/主题：${config.position}")
            appendLine("- 总轮次：${fullTranscript.filter { it.role == "interviewer" }.size}")
            appendLine("- 时长：${durationSeconds} 秒")
            appendLine()
            appendLine("【对话记录】")
            fullTranscript.forEachIndexed { index, turn ->
                val roleLabel = if (turn.role == "interviewer") "面试官" else "参与者"
                appendLine("${index + 1}. [$roleLabel] ${turn.content.take(200)}${if (turn.content.length > 200) "..." else ""}")
            }
            appendLine()
            appendLine("请严格按照 Evaluator System Prompt 中的 JSON 格式输出报告。")
        }

        val response = callLlm(
            llmClient = llmClient,
            systemPrompt = buildEvaluatorPrompt(config),
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
     * 从 JSON 响应中解析 NextAction。
     *
     * 新版解析逻辑：基于 JSON 的 action 字段，而非旧的 tag 标记系统。
     */
    private fun parseNextActionFromJson(response: String): NextAction {
        return try {
            val jsonStr = extractJsonFromResponse(response)
            val json = org.json.JSONObject(jsonStr)

            val action = json.optString("action", "next_question")
            val content = json.optString("content", "")
            val reason = json.optString("reason", "")
            val category = json.optString("category", "综合考察")

            when (action.lowercase()) {
                "follow_up" -> NextAction.FollowUp(content.ifBlank { response.trim() }, reason)
                "end_interview" -> NextAction.EndInterview(content.ifBlank { "对话已结束，感谢您的参与。" })
                else -> NextAction.NextQuestion(content.ifBlank { response.trim() }, category)
            }
        } catch (e: Exception) {
            DebugLog.w(TAG, "JSON 解析失败，使用兜底逻辑: ${e.message}")
            // 兜底：将整个响应作为下一题
            NextAction.NextQuestion(response.trim(), "综合考察")
        }
    }

    /**
     * 从 JSON 响应中提取 content 字段文本。
     * 用于 generateFirstQuestion 等只需要纯文本的场景。
     */
    private fun extractContentFromJsonResponse(response: String): String {
        return try {
            val jsonStr = extractJsonFromResponse(response)
            val json = org.json.JSONObject(jsonStr)
            val content = json.optString("content", "")
            content.ifBlank { response.trim() }
        } catch (e: Exception) {
            DebugLog.d(TAG, "JSON content 提取失败，返回原始响应: ${e.message}")
            response.trim()
        }
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
     * 解析教练反馈（新版：支持开放式评分维度）。
     */
    private fun parseCoachFeedback(response: String): CoachFeedback? {
        return try {
            val jsonStr = extractJsonFromResponse(response)
            val json = org.json.JSONObject(jsonStr)

            // 解析开放的 scores Map
            val scores = mutableMapOf<String, Float>()
            val scoresObj = json.optJSONObject("scores")
            if (scoresObj != null) {
                val keys = scoresObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    scores[key] = scoresObj.optDouble(key, 0.0).toFloat()
                }
            }

            CoachFeedback(
                scores = scores,
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
