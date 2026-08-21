package top.hsyscn.opedrgent.mcp.editors

import top.hsyscn.opedrgent.R

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import org.json.JSONArray
import org.json.JSONObject
import top.hsyscn.opedrgent.model.ChatMessage
import top.hsyscn.opedrgent.model.Role
import top.hsyscn.opedrgent.network.LlmClient
import top.hsyscn.opedrgent.network.StreamDelta
import top.hsyscn.opedrgent.settings.ApiConfig
import top.hsyscn.opedrgent.settings.ApiSettings
import top.hsyscn.opedrgent.utils.DebugLog
import top.hsyscn.opedrgent.intelligence.FeaturePipeline
import top.hsyscn.opedrgent.intelligence.AgentContext
import top.hsyscn.opedrgent.intelligence.CostTrackerFeature
import android.content.Context
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** 单步执行结果 */
data class EditorResult(
    val role: RoleInstance,
    val output: String,
    val tokensUsed: Int = 0,
    val durationMs: Long = 0,
    val error: String? = null,
) {
    val isSuccess: Boolean get() = error == null
}

/** 流水线最终结果 */
data class PipelineResult(
    val steps: List<EditorResult>,
    val finalOutput: String,
    val plan: ExecutionPlan,
    val totalTokensUsed: Int,
    val totalDurationMs: Long,
)

/** 群聊讨论单条消息 */
data class DiscussionMessage(
    val role: RoleInstance,
    val content: String,
    val order: Int,
    val isFinalDraft: Boolean = false,
)

/** 群聊讨论结果 */
data class DiscussionResult(
    val messages: List<DiscussionMessage>,
    val finalDraft: String,
    val totalTokensUsed: Int,
    val totalDurationMs: Long,
)

enum class OutputPlatform(
    val displayName: String,
    val formatHint: String,
) {
    WECHAT("公众号", "适合微信公众号发布的深度文章格式"),
    XIAOHONGSHU("小红书", "小红书图文笔记，emoji丰富，段落短"),
    MOMENTS("朋友圈", "朋友圈文案，140字以内精炼表达"),
    DOUYIN("抖音图文", "短视频脚本/图文，节奏快，吸引眼球"),
    PDF_REPORT("PDF报告", "正式报告格式，结构严谨"),
}

/**
 * AI 编辑团服务 — 核心架构：LLM 规划 + 动态执行。
 *
 * 工作流程：
 * 1. 用户输入任务描述
 * 2. LLM（总编角色）分析任务，输出执行计划（需要几个角色、各做什么）
 * 3. 按计划逐步调用每个角色执行
 * 4. 每步结果传递给下一步作为上下文
 * 5. 输出最终成果
 */
class EditorTeamService(
    private val apiSettings: ApiSettings,
    private val llmClient: LlmClient = LlmClient(),
    private val context: Context,
) {

    private val skillAdapter: EditorTeamSkillAdapter? = context.let { EditorTeamSkillAdapter(it) }

    private val isCancelled = AtomicBoolean(false)

    @Volatile
    private var currentCall: Call? = null

    // ==================== FeaturePipeline（拦截器链）====================

    /** 编辑团执行流水线，预装成本追踪 Feature（在 IO 线程延迟初始化） */
    private val _pipeline: FeaturePipeline = FeaturePipeline()
    private val pipelineInitialized = AtomicBoolean(false)

    private suspend fun initializePipeline() {
        if (!pipelineInitialized.compareAndSet(false, true)) return
        withContext(Dispatchers.IO) {
            _pipeline.install(CostTrackerFeature())
        }
    }

    /** 获取 Pipeline（供外部查询状态或安装额外 Feature） */
    fun getPipeline(): FeaturePipeline = _pipeline

    fun cancel() {
        isCancelled.set(true)
        runCatching { currentCall?.cancel() }
        DebugLog.i("EditorTeamService: cancelled")
    }

    fun resetCancel() { isCancelled.set(false) }

    // ==================== Workflow Storage（对标 Koog） ====================
    
    private val storage = WorkflowStorage()
    
    fun getStorage(): WorkflowStorage = storage
    
    /** 清理 storage（新任务开始时调用） */
    private fun resetStorage() { storage.clear() }

    // ==================== 核心：规划 + 执行 ====================

    /**
     * 主入口：让 LLM 规划并执行编辑任务。
     *
     * @param userInput 用户输入的任务/内容
     * @param targetPlatform 目标输出平台（可选）
     * @param styleReference 风格参考（可选）
     * @param onPlanReady 规划完成回调（UI 可展示计划给用户确认）
     * @param onStepComplete 每步完成回调
     */
    suspend fun planAndExecute(
        userInput: String,
        targetPlatform: OutputPlatform? = null,
        styleReference: String = "",
        contextNotes: List<String> = emptyList(),
        onPlanReady: (ExecutionPlan) -> Unit = {},
        onStepComplete: (RoleInstance, String) -> Unit = { _, _ -> },
    ): PipelineResult = withContext(Dispatchers.IO) {
        resetCancel()
        resetStorage()
        initializePipeline()
        val startTime = System.currentTimeMillis()

        // Step 1: LLM 规划（总编分析任务）
        val plan = if (isCancelled.get()) {
            ExecutionPlan(emptyList(), this@EditorTeamService.context.getString(R.string.editor_error_user_cancelled))
        } else {
            planPipeline(userInput, targetPlatform, styleReference)
        }

        onPlanReady(plan)

        if (plan.steps.isEmpty() || isCancelled.get()) {
            return@withContext PipelineResult(
                steps = emptyList(),
                finalOutput = "",
                plan = plan,
                totalTokensUsed = 0,
                totalDurationMs = System.currentTimeMillis() - startTime,
            )
        }

        // Step 2: 按计划逐步执行
        val steps = mutableListOf<EditorResult>()
        var totalTokens = 0
        var accumulatedContext = userInput

        for ((index, step) in plan.steps.withIndex()) {
            if (isCancelled.get()) break

            // 确定该步骤的输入
            val stepInput = if (step.dependsOnPrevious && steps.isNotEmpty()) {
                // 使用上一步的输出
                steps.last().output.ifBlank { accumulatedContext }
            } else {
                accumulatedContext
            }

            // 收集前面步骤的结果作为上下文
            val stepContext = steps.mapNotNull { r ->
                if (r.isSuccess) "【${r.role.alias}】的输出：\n${r.output.take(2000)}" else null
            }.takeLast(3)

            // 执行当前步骤
            val result = executeStep(
                role = step.role,
                input = stepInput,
                extraInstructions = step.instruction,
                contextNotes = stepContext + contextNotes,
            )

            steps.add(result)
            totalTokens += result.tokensUsed

            if (result.isSuccess) {
                onStepComplete(step.role, result.output)
                accumulatedContext = result.output
                
                // 自动记录到 storage（对标 Koog storage.set）
                storage.set("step_${index}_output", result.output)
                storage.set("step_${index}_role", step.role.alias)
                storage.set("step_${index}_tokens", result.tokensUsed)
                storage.set("last_output", result.output)
                storage.set("last_role", step.role.alias)
                
                // 记录元数据
                if (result.output.length > 500) {
                    storage.set("long_output_detected", true)
                }
            }
        }

        // 最终输出：取最后一步的成功结果
        val finalOutput = steps.lastOrNull { it.isSuccess }?.output ?: ""

        PipelineResult(
            steps = steps.toList(),
            finalOutput = finalOutput,
            plan = plan,
            totalTokensUsed = totalTokens,
            totalDurationMs = System.currentTimeMillis() - startTime,
        )
    }

    // ==================== 兼容旧接口 ====================

    /** 固定流水线（向后兼容） */
    suspend fun fullWritingPipeline(
        userInput: String,
        targetPlatform: OutputPlatform = OutputPlatform.WECHAT,
        styleReference: String = "",
        onStepComplete: (EditorRole, String) -> Unit = { _, _ -> },
    ): PipelineResult {
        // 将旧的固定流水线转为 ExecutionPlan 执行
        val roles = EditorRole.defaultPipeline.map { RoleInstance.Preset(it) }
        val lastIdx = roles.lastIndex
        val steps = roles.mapIndexed { idx, role ->
            PlanStep(
                index = idx,
                role = role,
                instruction = when (role.role) {
                    EditorRole.EDITOR_IN_CHIEF ->
                        "请将文章排版为「${targetPlatform.displayName}」格式。${targetPlatform.formatHint}" +
                                if (styleReference.isNotEmpty()) "\n\n风格参考：$styleReference" else ""
                    else -> ""
                },
                dependsOnPrevious = true,
            )
        }
        val fixedPlan = ExecutionPlan(steps, reasoning = "默认完整创作流水线")

        return planAndExecute(
            userInput = userInput,
            targetPlatform = targetPlatform,
            styleReference = styleReference,
            onPlanReady = {},
            onStepComplete = { role, output ->
                if (role is RoleInstance.Preset) onStepComplete(role.role, output)
            },
        )
    }

    /** 单角色咨询（自由模式，支持预设角色和动态角色） */
    suspend fun singleRoleConsult(
        role: EditorRole,
        input: String,
        dynamicSystemPrompt: String? = null,
    ): EditorResult {
        // 如果有动态系统提示词，使用动态角色包装
        val instance = if (dynamicSystemPrompt != null) {
            RoleInstance.Dynamic(DynamicRole(
                name = role.displayName,
                alias = role.alias,
                icon = role.icon,
                description = role.description,
                systemPrompt = dynamicSystemPrompt,
            ))
        } else {
            RoleInstance.Preset(role)
        }
        return executeStep(instance, input)
    }

    // ==================== 群聊式讨论（写作模式核心） ====================

    /**
     * 群聊式多Agent讨论 — 写作模式的核心执行方式。
     *
     * 模拟微信群聊：用户发一段话，多个AI角色依次发言（像真人打字一样），
     * 每个角色从自己的专业视角给出意见，最后主编综合定稿。
     *
     * 与 planAndExecute（串行流水线）的区别：
     * - 不需要LLM规划步骤，直接用固定阵容
     * - 每个角色看到的是用户的原始输入 + 前面角色的发言
     * - 角色之间可以"互相引用"（"我同意历史的观点，但..."）
     * - 最终产出是讨论过程 + 主编定稿
     */

    /**
     * 启动群聊讨论。
     *
     * @param userInput 用户的写作需求或待修改文本
     * @param roles 参与讨论的角色列表（默认用 defaultPipeline）
     * @param onEachMessage 每个角色发言后的回调（用于UI实时显示）
     */
    suspend fun groupDiscussion(
        userInput: String,
        roles: List<EditorRole> = EditorRole.defaultPipeline,
        includeSkillRoles: Boolean = false,
        discussionHistory: List<DiscussionMessage> = emptyList(),
        onEachMessage: (DiscussionMessage) -> Unit = {},
    ): DiscussionResult = withContext(Dispatchers.IO) {
        resetCancel()
        if (discussionHistory.isEmpty()) resetStorage()
        val startTime = System.currentTimeMillis()
        var totalTokens = 0
        val messages = mutableListOf<DiscussionMessage>()

        val participants: List<RoleInstance> = buildList {
            addAll(roles.map { RoleInstance.Preset(it) })
            if (includeSkillRoles) {
                addAll(skillAdapter?.getSkillBasedRoles()?.map { RoleInstance.Dynamic(it) }.orEmpty())
            }
        }

        val historyStrs = mutableListOf<String>()
        discussionHistory.forEach { msg ->
            historyStrs.add("【${msg.role.alias}】${msg.role.name}说：\n${msg.content}")
        }
        val startOrder = discussionHistory.size

        for ((index, instance) in participants.withIndex()) {
            if (isCancelled.get()) break

            val isFinalSpeaker = (index == participants.lastIndex)
            val discussionContext = buildDiscussionContext(
                userInput = userInput,
                history = historyStrs.toList(),
                isFinalSpeaker = isFinalSpeaker,
            )

            DebugLog.i("EditorTeamService.groupDiscussion → ${instance.alias} speaking...")

            val result = executeStep(
                role = instance,
                input = discussionContext,
                extraInstructions = if (isFinalSpeaker) {
                    "你是最后一位发言者。请综合前面所有同事的意见，给出最终定稿。"
                } else "",
            )

            if (result.isSuccess) {
                val msg = DiscussionMessage(
                    role = instance,
                    content = result.output,
                    order = startOrder + index,
                    isFinalDraft = isFinalSpeaker,
                )
                messages.add(msg)
                totalTokens += result.tokensUsed

                historyStrs.add("【${instance.alias}】${instance.name}说：\n${result.output}")

                onEachMessage(msg)

                storage.set("discussion_msg_${startOrder + index}", result.output)
                storage.set("last_discussion_role", instance.alias)
            } else {
                DebugLog.w("EditorTeamService.groupDiscussion: ${instance.alias} failed: ${result.error}")
                historyStrs.add("【${instance.alias}】（发言失败）")
            }
        }

        val finalDraft = messages.lastOrNull { it.isFinalDraft }?.content ?: ""
        val duration = System.currentTimeMillis() - startTime

        DebugLog.i("EditorTeamService.groupDiscussion done: ${messages.size} msgs, ${duration}ms")

        DiscussionResult(
            messages = messages.toList(),
            finalDraft = finalDraft,
            totalTokensUsed = totalTokens,
            totalDurationMs = duration,
        )
    }

    suspend fun groupDiscussionStreaming(
        userInput: String,
        roles: List<EditorRole> = EditorRole.defaultPipeline,
        includeSkillRoles: Boolean = false,
        discussionHistory: List<DiscussionMessage> = emptyList(),
        onRoleStart: (roleName: String) -> Unit = {},
        onRoleChunk: (roleName: String, chunk: String) -> Unit = { _, _ -> },
        onRoleComplete: (roleName: String, fullText: String) -> Unit = { _, _ -> },
        onEachMessage: (DiscussionMessage) -> Unit = {},
    ): DiscussionResult = withContext(Dispatchers.IO) {
        resetCancel()
        if (discussionHistory.isEmpty()) resetStorage()
        val startTime = System.currentTimeMillis()
        var totalTokens = 0
        val messages = mutableListOf<DiscussionMessage>()

        val participants: List<RoleInstance> = buildList {
            addAll(roles.map { RoleInstance.Preset(it) })
            if (includeSkillRoles) {
                addAll(skillAdapter?.getSkillBasedRoles()?.map { RoleInstance.Dynamic(it) }.orEmpty())
            }
        }

        val historyStrs = mutableListOf<String>()
        discussionHistory.forEach { msg ->
            historyStrs.add("【${msg.role.alias}】${msg.role.name}说：\n${msg.content}")
        }
        val startOrder = discussionHistory.size

        for ((index, instance) in participants.withIndex()) {
            if (isCancelled.get()) break

            val isFinalSpeaker = (index == participants.lastIndex)
            val discussionContext = buildDiscussionContext(
                userInput = userInput,
                history = historyStrs.toList(),
                isFinalSpeaker = isFinalSpeaker,
            )

            onRoleStart(instance.alias)
            DebugLog.i("EditorTeamService.groupDiscussionStreaming → ${instance.alias} speaking...")

            val extraInstructions = if (isFinalSpeaker) {
                "你是最后一位发言者。请综合前面所有同事的意见，给出最终定稿。"
            } else ""

            val fullText = try {
                streamStep(
                    role = instance,
                    input = discussionContext,
                    extraInstructions = extraInstructions,
                    onChunk = { chunk -> onRoleChunk(instance.alias, chunk) },
                )
            } catch (e: Exception) {
                DebugLog.w("EditorTeamService.groupDiscussionStreaming: ${instance.alias} failed: ${e.message}")
                historyStrs.add("【${instance.alias}】（发言失败：${e.message}）")
                onRoleComplete(instance.alias, "")
                continue
            }

            val msg = DiscussionMessage(
                role = instance,
                content = fullText,
                order = startOrder + index,
                isFinalDraft = isFinalSpeaker,
            )
            messages.add(msg)
            totalTokens += (fullText.length / 2) + (discussionContext.length / 2)

            historyStrs.add("【${instance.alias}】${instance.name}说：\n$fullText")

            onRoleComplete(instance.alias, fullText)
            onEachMessage(msg)

            storage.set("discussion_msg_${startOrder + index}", fullText)
            storage.set("last_discussion_role", instance.alias)
        }

        val finalDraft = messages.lastOrNull { it.isFinalDraft }?.content ?: ""
        val duration = System.currentTimeMillis() - startTime

        DebugLog.i("EditorTeamService.groupDiscussionStreaming done: ${messages.size} msgs, ${duration}ms")

        DiscussionResult(
            messages = messages.toList(),
            finalDraft = finalDraft,
            totalTokensUsed = totalTokens,
            totalDurationMs = duration,
        )
    }

    private fun buildDiscussionContext(
        userInput: String,
        history: List<String>,
        isFinalSpeaker: Boolean,
    ): String {
        if (history.isEmpty()) {
            return if (isFinalSpeaker) {
                "$userInput\n\n## 你的任务\n你是最后一位发言者。请综合前面所有同事的意见，给出最终定稿。"
            } else {
                userInput
            }
        }

        val fullHistory = history.joinToString("\n\n") { it }
        val totalChars = userInput.length + fullHistory.length

        val historySection = if (totalChars <= MAX_CONTEXT_CHARS) {
            fullHistory
        } else {
            val recent = history.takeLast(RECENT_FULL_COUNT)
            val older = history.dropLast(RECENT_FULL_COUNT)
            buildString {
                if (older.isNotEmpty()) {
                    appendLine("## 早期发言（已摘要）")
                    older.forEach { entry ->
                        val firstLine = entry.lineSequence().firstOrNull().orEmpty()
                        appendLine("- $firstLine")
                    }
                    appendLine()
                }
                appendLine("## 近期发言（完整）")
                append(recent.joinToString("\n\n") { it })
            }
        }

        val finalHint = if (isFinalSpeaker) {
            "\n\n---\n\n## 你的任务\n你是最后一位发言者。请综合前面所有同事的意见，给出最终定稿。"
        } else ""

        return """## 用户的需求
$userInput

---

## 前面同事的发言（你可以参考或反驳）
$historySection$finalHint"""
    }

    /**
     * 带条件边的执行入口（对标 Koog onCondition）。
     *
     * 支持动态决策：每步执行后评估条件，
     * 根据结果决定是否跳过后续步骤或走不同分支。
     *
     * @param plan V2 执行计划（含条件分支）
     * @param conditionEvaluator 条件评估器（接收 storage 和表达式，返回布尔值）
     */
    suspend fun executeWithCondition(
        plan: ExecutionPlanV2,
        userInput: String,
        targetPlatform: OutputPlatform? = null,
        styleReference: String = "",
        contextNotes: List<String> = emptyList(),
        conditionEvaluator: ((WorkflowStorage, String) -> Boolean)? = null,
        onPlanReady: (ExecutionPlanV2) -> Unit = {},
        onStepComplete: (RoleInstance, String) -> Unit = { _, _ -> },
        onBranchTaken: (ConditionalBranch, Boolean) -> Unit = { _, _ -> },
    ): PipelineResult = withContext(Dispatchers.IO) {
        resetCancel()
        resetStorage()
        val startTime = System.currentTimeMillis()
        
        onPlanReady(plan)
        
        if (plan.isEmpty || isCancelled.get()) {
            return@withContext PipelineResult(
                steps = emptyList(),
                finalOutput = "",
                plan = plan.toV1(),
                totalTokensUsed = 0,
                totalDurationMs = System.currentTimeMillis() - startTime,
            )
        }
        
        val allSteps = mutableListOf<EditorResult>()
        var totalTokens = 0
        var accumulatedContext = userInput
        
        // 执行主步骤序列
        for ((index, step) in plan.steps.withIndex()) {
            if (isCancelled.get()) break
            
            // 检查步骤级条件
            if (step.condition != null) {
                val shouldExecute = evaluateCondition(step.condition, conditionEvaluator)
                if (!shouldExecute) {
                    DebugLog.i("EditorTeamService: step $index skipped (condition: ${step.condition.expression})")
                    continue
                }
            }
            
            val stepInput = if (step.dependsOnPrevious && allSteps.isNotEmpty()) {
                allSteps.last().output.ifBlank { accumulatedContext }
            } else {
                accumulatedContext
            }
            
            val stepContext = allSteps.mapNotNull { r ->
                if (r.isSuccess) "【${r.role.alias}】的输出：\n${r.output.take(2000)}" else null
            }.takeLast(3)
            
            val result = executeStep(
                role = step.role,
                input = stepInput,
                extraInstructions = step.instruction,
                contextNotes = stepContext + contextNotes,
            )
            
            allSteps.add(result)
            totalTokens += result.tokensUsed
            
            if (result.isSuccess) {
                onStepComplete(step.role, result.output)
                accumulatedContext = result.output
                
                // 存储到 workflow storage
                storage.set("step_$index", result.output)
                storage.set("last_output", result.output)
            }
        }
        
        // 评估并执行条件分支
        for (branch in plan.branches) {
            if (isCancelled.get()) break
            
            val shouldTakeBranch = evaluateCondition(branch.condition, conditionEvaluator)
            onBranchTaken(branch, shouldTakeBranch)
            
            val branchSteps = if (shouldTakeBranch) branch.thenSteps else branch.elseSteps
            for (step in branchSteps) {
                if (isCancelled.get()) break
                
                val stepInput = allSteps.lastOrNull { it.isSuccess }?.output ?: accumulatedContext
                
                val result = executeStep(
                    role = step.role,
                    input = stepInput,
                    extraInstructions = step.instruction + "\n\n[上下文] " + storage.getOrDefault("last_output", ""),
                    contextNotes = contextNotes,
                )
                
                allSteps.add(result)
                totalTokens += result.tokensUsed
                
                if (result.isSuccess) {
                    onStepComplete(step.role, result.output)
                    accumulatedContext = result.output
                    storage.set("last_output", result.output)
                }
            }
        }
        
        val finalOutput = allSteps.lastOrNull { it.isSuccess }?.output ?: ""
        
        PipelineResult(
            steps = allSteps.toList(),
            finalOutput = finalOutput,
            plan = plan.toV1(),
            totalTokensUsed = totalTokens,
            totalDurationMs = System.currentTimeMillis() - startTime,
        )
    }
    
    /**
     * 评估条件表达式。
     * 
     * 支持简单表达式和 LLM 语义判断两种模式。
     */
    private suspend fun evaluateCondition(
        condition: StepCondition,
        customEvaluator: ((WorkflowStorage, String) -> Boolean)?,
    ): Boolean {
        // 优先使用自定义评估器
        customEvaluator?.let { return it(storage, condition.expression) }
        
        // 内置简单表达式求值
        return try {
            when {
                // 输出长度检查
                condition.expression.contains("output_length") -> {
                    val lastOutput = storage.getOrDefault("last_output", "")
                    val threshold = extractNumber(condition.expression) ?: 0
                    when {
                        condition.expression.contains(">=") -> lastOutput.length >= threshold
                        condition.expression.contains("<=") -> lastOutput.length <= threshold
                        condition.expression.contains(">") -> lastOutput.length > threshold
                        condition.expression.contains("<") -> lastOutput.length < threshold
                        else -> true // 无法解析则默认执行
                    }
                }
                // 错误检查
                condition.expression.contains("contains_error") || condition.expression.contains("has_error") -> {
                    val lastResult = storage.get<String>("last_output") ?: ""
                    !lastResult.lowercase().contains("error") && 
                    !lastResult.lowercase().contains("失败") &&
                    !lastResult.lowercase().contains("异常")
                }
                // 存储键存在性检查
                condition.expression.contains("storage.has") -> {
                    val key = condition.expression.substringAfter("storage.has(").substringBefore(")")
                    storage.contains(key.trim())
                }
                // 默认：让 LLM 判断
                else -> run { evaluateConditionWithLLM(condition) }
            }
        } catch (e: Exception) {
            DebugLog.w("EditorTeamService: condition eval error: ${e.message}, defaulting to true")
            true // 条件评估失败默认执行
        }
    }
    
    /**
     * 使用 LLM 评估复杂条件（语义级别判断）。
     */
    private suspend fun evaluateConditionWithLLM(condition: StepCondition): Boolean {
        val config = apiSettings.getApiConfig() ?: return true
        val lastOutput = storage.getOrDefault("last_output", "(无前序输出)")
        
        val prompt = """你是一个条件判断器。请严格按以下格式输出 ONLY "true" 或 "false"。

## 待判断的条件
${condition.description.ifBlank { condition.expression }}

## 当前工作流状态
- 上一步输出（前200字）：${lastOutput.take(200)}
- Storage 键：${storage.keys().joinToString(", ")}

## 判断规则
基于当前状态，判断条件是否满足。只能输出 true 或 false，不要输出其他内容。"""
        
        return try {
            val response = llmClient.chatCompletions(
                config = config,
                system = prompt,
                messages = listOf(ChatMessage(role = Role.USER, content = "请判断：${condition.expression}")),
            )
            response.trim().lowercase().contains("true")
        } catch (e: Exception) {
            DebugLog.w("EditorTeamService: LLM condition eval error: ${e.message}")
            true
        }
    }
    
    /** 从表达式中提取数字 */
    private fun extractNumber(expression: String): Int? {
        val regex = Regex("\\d+")
        return regex.find(expression)?.value?.toIntOrNull()
    }

    // ==================== 内部方法 ====================

    /**
     * 调用 LLM 规划执行计划。
     *
     * 让 LLM 扮演"总编"角色，分析用户需求后输出：
     * - 需要几个步骤（2-6 步）
     * - 每个步骤用什么角色（可选用预设或自创）
     * - 每步的具体指令
     */
    private suspend fun planPipeline(
        userInput: String,
        targetPlatform: OutputPlatform?,
        styleReference: String,
    ): ExecutionPlan {
        val config = apiSettings.getApiConfig()
        if (config == null) {
            return ExecutionPlan(emptyList(), reasoning = this@EditorTeamService.context.getString(R.string.editor_error_no_api_key))
        }

        val skillRoles = skillAdapter?.getSkillBasedRoles().orEmpty()

        val availableRolesDescription = buildString {
            appendLine("## 可用的预设角色模板")
            for (role in EditorRole.allRoles) {
                appendLine("- **${role.displayName}** (${role.alias})：${role.description}")
            }
            if (skillRoles.isNotEmpty()) {
                appendLine()
                appendLine("## 可用的技能角色（来自已加载的 Skill）")
                for (role in skillRoles) {
                    appendLine("- **${role.name}** (${role.alias})：${role.description}")
                }
            }
            appendLine()
            appendLine("你也可以根据任务需要，创建不在上述列表中的新角色。")
        }

        val platformHint = targetPlatform?.let { "\n- 目标平台：${it.displayName}" } ?: ""
        val styleHint = if (styleReference.isNotEmpty()) "\n- 风格参考：$styleReference" else ""

        val plannerPrompt = """你是一位资深的内容创作总编。你的任务是分析用户的写作需求，制定一个高效的执行计划。

## 你的工作方式
1. 分析用户想要做什么（写文章？整理笔记？润色草稿？其他？）
2. 判断需要几个处理步骤（建议 2-6 个步骤，不要太多也不要太少）
3. 为每个步骤分配合适的编辑角色

$availableRolesDescription

## 用户需求
$userInput
$platformHint
$styleHint

## 输出要求
你必须严格按以下 JSON 格式输出（不要输出任何其他文字）：

```json
{
  "reasoning": "简要说明为什么这样规划",
  "steps": [
    {
      "step": 1,
      "role_name": "角色名称（如：选题策划 / 文章撰写 / 或自定义名称）",
      "role_alias": "简称（2-3字）",
      "system_prompt": "该角色的完整系统提示词（详细描述职责、工作方式、输出格式）",
      "instruction": "给这个步骤的具体指令（基于用户需求的上下文）"
    }
  ]
}
```

### 规划原则
- 步骤数要合理：简单任务 2-3 步，复杂任务 4-6 步
- 第一步通常是理解/分析/策划类角色
- 最后一步通常是输出/排版/总结类角色
- 中间步骤根据需要安排：调研、撰写、核查、审稿等
- 如果用户只是想润色已有内容，不需要选题和调研步骤"""

        try {
            val response = llmClient.chatCompletions(
                config = config,
                system = plannerPrompt,
                messages = listOf(ChatMessage(role = Role.USER, content = "请为以下需求制定执行计划：\n\n$userInput")),
            )

            return parsePlanFromResponse(response)
        } catch (e: Exception) {
            DebugLog.e("EditorTeamService.planPipeline error: ${e.message}", e)
            // 规划失败时回退到默认流水线
            DebugLog.w("EditorTeamService: plan failed, fallback to default pipeline")
            return createFallbackPlan(targetPlatform, styleReference)
        }
    }

    /**
     * 解析 LLM 返回的 JSON 规划。
     */
    private fun parsePlanFromResponse(response: String): ExecutionPlan {
        return try {
            // 提取 JSON（可能被 ```json 包裹）
            val jsonStr = response.trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()

            val json = JSONObject(jsonStr)
            val reasoning = json.optString("reasoning", "")
            val stepsArray = json.optJSONArray("steps") ?: JSONArray()

            val steps = (0 until stepsArray.length()).map { i ->
                val stepJson = stepsArray.getJSONObject(i)
                val index = stepJson.optInt("step", i + 1)
                val roleName = stepJson.getString("role_name")
                val alias = stepJson.optString("role_alias", roleName.take(3))
                val systemPrompt = stepJson.getString("system_prompt")
                val instruction = stepJson.optString("instruction", "")

                // 尝试匹配预设角色
                val matchedPreset = findBestMatch(roleName, alias)
                val roleInstance = if (matchedPreset != null) {
                    // 用预设角色的系统提示词 + 可能的自定义增强
                    val enhancedPrompt = if (instruction.isNotBlank()) {
                        "${matchedPreset.systemPrompt}\n\n## 本次任务指令\n$instruction"
                    } else matchedPreset.systemPrompt
                    RoleInstance.Preset(matchedPreset)
                } else {
                    // 完全动态创建的角色
                    DynamicRole(
                        name = roleName,
                        alias = alias,
                        icon = pickIconForName(roleName),
                        description = "",
                        systemPrompt = systemPrompt,
                        inputHint = instruction,
                    ).let { RoleInstance.Dynamic(it) }
                }

                PlanStep(index = index, role = roleInstance, instruction = instruction)
            }

            ExecutionPlan(steps = steps, reasoning = reasoning)
        } catch (e: Exception) {
            DebugLog.w("EditorTeamService: failed to parse plan JSON: ${e.message}")
            createFallbackPlan(null, "")
        }
    }

    /**
     * 执行单个步骤（通过 FeaturePipeline 拦截器链）。
     *
     * 执行流程：
     * 1. Pipeline beforeExecute → 成本追踪/审批检查等
     * 2. 实际 LLM 调用
     * 3. Pipeline afterExecute → 记忆写入/情绪更新/成本记录
     */
    private suspend fun executeStep(
        role: RoleInstance,
        input: String,
        extraInstructions: String = "",
        contextNotes: List<String> = emptyList(),
    ): EditorResult {
        val startTime = System.currentTimeMillis()
        val stepId = UUID.randomUUID().toString()

        // 构建 AgentContext（在 Pipeline 各 Feature 间传递的共享状态）
        val context = AgentContext(
            sessionId = "editor_${System.currentTimeMillis()}",
            userInput = input,
            systemPrompt = buildSystemPrompt(role, extraInstructions),
            messages = mutableListOf(),
            metadata = mutableMapOf(
                "role_alias" to role.alias,
                "role_name" to role.name,
                "step_id" to stepId,
            ),
        )

        // 通过 Pipeline 执行
        return try {
            _pipeline.execute(context) { ctx ->
                // === 实际 LLM 调用（Agent Block）===
                withContext(Dispatchers.IO) {
                    val config = apiSettings.getApiConfig()
                    if (config == null) {
                        return@withContext EditorResult(
                            role = role, output = "", error = this@EditorTeamService.context.getString(R.string.editor_error_no_api_key),
                            durationMs = System.currentTimeMillis() - startTime,
                        )
                    }

                    if (isCancelled.get()) {
                        return@withContext EditorResult(
                            role = role, output = "", error = this@EditorTeamService.context.getString(R.string.editor_error_user_cancelled),
                            durationMs = System.currentTimeMillis() - startTime,
                        )
                    }

                    try {
                        val userMessage = buildUserMessage(input, contextNotes)

                        DebugLog.i("EditorTeamService.executeStep → ${role.alias} input=${input.take(50)}...")

                        val response = llmClient.chatCompletions(
                            config = config,
                            system = ctx.systemPrompt,
                            messages = listOf(ChatMessage(role = Role.USER, content = userMessage)),
                        )

                        val duration = System.currentTimeMillis() - startTime
                        DebugLog.i("EditorTeamService.executeStep ← ${role.alias} done ${duration}ms, ${response.length} chars")

                        // 将 token 信息写入 context，供 afterExecute 的 CostTracker 使用
                        // （简化估算：按字符数粗略估算 token）
                        val estimatedTokens = (response.length / 2) + (input.length / 2)
                        ctx["input_tokens"] = input.length / 2
                        ctx["output_tokens"] = response.length / 2

                        EditorResult(role = role, output = response.trim(), tokensUsed = estimatedTokens, durationMs = duration)
                    } catch (e: Exception) {
                        DebugLog.e("EditorTeamService.executeStep error: ${e.message}", e)
                        EditorResult(role = role, output = "", error = e.message ?: this@EditorTeamService.context.getString(R.string.error_unknown_error),
                            durationMs = System.currentTimeMillis() - startTime)
                    }
                }
            }
        } catch (e: Exception) {
            // Pipeline 中止异常（如审批拒绝）
            if (e is top.hsyscn.opedrgent.intelligence.PipelineAbortedException) {
                EditorResult(role = role, output = "", error = this@EditorTeamService.context.getString(R.string.editor_error_execution_interrupted, e.featureName),
                    durationMs = System.currentTimeMillis() - startTime)
            } else {
                DebugLog.e("EditorTeamService.pipeline execute error: ${e.message}", e)
                EditorResult(role = role, output = "", error = e.message ?: this@EditorTeamService.context.getString(R.string.editor_error_pipeline),
                    durationMs = System.currentTimeMillis() - startTime)
            }
        }
    }

    private suspend fun streamStep(
        role: RoleInstance,
        input: String,
        extraInstructions: String = "",
        onChunk: (String) -> Unit,
    ): String {
        val config = apiSettings.getApiConfig()
            ?: throw RuntimeException(this@EditorTeamService.context.getString(R.string.editor_error_no_api_key))
        if (isCancelled.get()) throw RuntimeException(this@EditorTeamService.context.getString(R.string.editor_error_user_cancelled))

        val systemPrompt = buildSystemPrompt(role, extraInstructions)
        val accumulated = StringBuilder()
        val done = CompletableDeferred<String>()

        val call = llmClient.streamChatCompletions(
            config = config,
            system = systemPrompt,
            messages = listOf(ChatMessage(role = Role.USER, content = input)),
            onDelta = { delta ->
                if (delta is StreamDelta.TextDelta) {
                    accumulated.append(delta.text)
                    onChunk(delta.text)
                }
            },
            onDone = { result ->
                val text = accumulated.toString().ifBlank { result.content }.trim()
                done.complete(text)
            },
            onError = { err ->
                done.completeExceptionally(RuntimeException(err))
            },
        )
        currentCall = call
        return try {
            done.await()
        } finally {
            currentCall = null
        }
    }

    private fun buildSystemPrompt(role: RoleInstance, extraInstructions: String): String {
        return if (extraInstructions.isNotBlank()) {
            "${role.systemPrompt}\n\n## 额外指令\n$extraInstructions"
        } else role.systemPrompt
    }

    private fun buildUserMessage(userInput: String, contextNotes: List<String>): String {
        return if (contextNotes.isEmpty()) userInput else {
            val notesSection = contextNotes.joinToString("\n\n---\n\n") { "[参考资料]\n$it" }
            """以下是相关的背景资料：

$notesSection

---

## 你的任务
$userInput"""
        }
    }

    // ==================== 辅助方法 ====================

    /** 根据角色名找最匹配的预设角色 */
    private fun findBestMatch(name: String, alias: String): EditorRole? {
        for (preset in EditorRole.allRoles) {
            if (name.contains(preset.displayName) || preset.displayName.contains(name)) return preset
            if (alias == preset.alias || name.contains(preset.alias)) return preset
        }
        return EditorRole.allRoles.minByOrNull {
            dist(name, it.displayName) +
            dist(alias, it.alias)
        }?.takeIf { role ->
            dist(name, role.displayName) < 5 ||
            dist(alias, role.alias) < 3
        }
    }

    /** 为动态角色选一个合适的图标（纯文字标识，无emoji） */
    private fun pickIconForName(name: String): String {
        val keywords = mapOf(
            "选题" to "P", "调研" to "R", "撰写" to "W",
            "核查" to "C", "审稿" to "V", "排版" to "F",
            "整理" to "O", "风格" to "S", "翻译" to "X",
            "分析" to "A", "设计" to "D", "校对" to "K",
            "数据" to "D", "创意" to "I", "优化" to "^",
        )
        for ((kw, icon) in keywords) { if (name.contains(kw) || kw.contains(name)) return icon }
        return "?" // 默认
    }

    /** 回退方案：规划失败时使用默认流水线 */
    private fun createFallbackPlan(platform: OutputPlatform?, styleRef: String): ExecutionPlan {
        val roles = EditorRole.defaultPipeline.map { RoleInstance.Preset(it) }
        val steps = roles.mapIndexed { idx, role ->
            PlanStep(idx + 1, role, dependsOnPrevious = true)
        }
        return ExecutionPlan(steps, reasoning = "使用默认推荐组合（规划回退）")
    }

    companion object {
        private const val MAX_CONTEXT_CHARS = 8000
        private const val RECENT_FULL_COUNT = 3
    }
}


