package top.hsyscn.opedrgent.mcp.editors

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.hsyscn.opedrgent.model.ChatMessage
import top.hsyscn.opedrgent.model.Role
import top.hsyscn.opedrgent.network.LlmClient
import top.hsyscn.opedrgent.settings.ApiConfig
import top.hsyscn.opedrgent.settings.ApiSettings
import top.hsyscn.opedrgent.utils.DebugLog

data class EditorResult(
    val role: EditorRole,
    val output: String,
    val tokensUsed: Int = 0,
    val durationMs: Long = 0,
    val error: String? = null,
) {
    val isSuccess: Boolean get() = error == null
}

data class PipelineResult(
    val steps: List<EditorResult>,
    finalOutput: String,
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

class EditorTeamService(
    private val apiSettings: ApiSettings,
    private val llmClient: LlmClient = LlmClient(),
) {

    private var isCancelled = false

    fun cancel() {
        isCancelled = true
        DebugLog.i("EditorTeamService: cancelled")
    }

    fun resetCancel() {
        isCancelled = false
    }

    /**
     * 调用指定角色处理内容
     */
    suspend fun consultRole(
        role: EditorRole,
        userInput: String,
        contextNotes: List<String> = emptyList(),
        extraInstructions: String = "",
    ): EditorResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        val config = apiSettings.getApiConfig()
        if (config == null) {
            return@withContext EditorResult(
                role = role,
                output = "",
                error = "未配置 API Key，请先在设置中配置",
                durationMs = System.currentTimeMillis() - startTime,
            )
        }

        if (isCancelled) {
            return@withContext EditorResult(
                role = role,
                output = "",
                error = "用户取消操作",
                durationMs = System.currentTimeMillis() - startTime,
            )
        }

        try {
            val systemPrompt = buildSystemPrompt(role, extraInstructions)
            val userMessage = buildUserMessage(userInput, contextNotes)

            DebugLog.i("EditorTeamService.consultRole → ${role.alias} input=${userInput.take(50)}...")

            val response = llmClient.chatCompletions(
                config = config,
                system = systemPrompt,
                messages = listOf(
                    ChatMessage(role = Role.USER, content = userMessage),
                ),
            )

            val duration = System.currentTimeMillis() - startTime
            DebugLog.i("EditorTeamService.consultRole ← ${role.alias} done ${duration}ms, ${response.length} chars")

            EditorResult(
                role = role,
                output = response.trim(),
                durationMs = duration,
            )
        } catch (e: Exception) {
            DebugLog.e("EditorTeamService.consultRole error: ${e.message}", e)
            EditorResult(
                role = role,
                output = "",
                error = e.message ?: "未知错误",
                durationMs = System.currentTimeMillis() - startTime,
            )
        }
    }

    /**
     * 完整的写作流水线：选题→素材→撰写→审稿→核查→排版
     */
    suspend fun fullWritingPipeline(
        userInput: String,
        targetPlatform: OutputPlatform = OutputPlatform.WECHAT,
        styleReference: String = "",
        onStepComplete: (EditorRole, String) -> Unit = { _, _ -> },
    ): PipelineResult = withContext(Dispatchers.IO) {
        resetCancel()
        val steps = mutableListOf<EditorResult>()
        var totalTokens = 0
        val startTime = System.currentTimeMillis()

        // 流水线步骤：每个步骤的输出会作为下一步的上下文
        val pipelineSequence = mutableListOf<EditorRole>().apply {
            add(EditorRole.ZHAO_XUAN_TI)
            add(EditorRole.ZHANG_SU_CAI)
            add(EditorRole.LI_WEN_ZHANG)
            add(EditorRole.ZHOU_SHEN_GAO)
            add(EditorRole.WU_CHA_CHA)
            add(EditorRole.CHEN_PAI_BAN)
        }

        // 构建逐步累积的上下文
        var accumulatedContext = userInput

        for ((index, role) in pipelineSequence.withIndex()) {
            if (isCancelled) break

            // 为当前步骤构建指令
            val stepInstructions = when (role) {
                EditorRole.ZHAO_XUAN_TI -> ""
                EditorRole.ZHANG_SU_CAI -> {
                    val prevResult = steps.lastOrNull { it.role == EditorRole.ZHAO_XUAN_TI }
                    "上一步【赵选题】的选题建议如下，请针对选定的选题方向收集素材：\n${prevResult?.output ?: ""}"
                }
                EditorRole.LI_WEN_ZHANG -> {
                    val topicResult = steps.lastOrNull { it.role == EditorRole.ZHAO_XUAN_TI }?.output ?: ""
                    val materialResult = steps.lastOrNull { it.role == EditorRole.ZHANG_SU_CAI }?.output ?: ""
                    "请基于以下信息撰写完整文章：\n\n## 选题方向\n$topicResult\n\n## 素材库\n$materialResult"
                }
                EditorRole.ZHOU_SHEN_GAO -> "请对以下文章进行审稿："
                EditorRole.WU_CHA_CHA -> "请对以下内容进行事实核查："
                EditorRole.CHEN_PAI_BAN -> {
                    val platformHint = "请将文章排版为「${targetPlatform.displayName}」格式。${targetPlatform.formatHint}"
                    if (styleReference.isNotEmpty()) {
                        "$platformHint\n\n风格参考：$styleReference"
                    } else {
                        platformHint
                    }
                }
            }

            // 确定每步的输入
            val stepInput = when (role) {
                EditorRole.LI_WEN_ZHANG,
                EditorRole.ZHOU_SHEN_GAO,
                EditorRole.WU_CHA_CHA,
                EditorRole.CHEN_PAI_BAN -> {
                    // 后续步骤使用李文章的输出作为主要输入
                    val articleDraft = steps.lastOrNull { it.role == EditorRole.LI_WEN_ZHANG }?.output
                        ?: accumulatedContext
                    articleDraft
                }
                else -> accumulatedContext
            }

            // 收集前面所有相关结果作为上下文笔记
            val contextNotes = steps.mapNotNull { result ->
                if (result.isSuccess && result.role != role) "${result.role.alias}的输出：\n${result.output.take(2000)}" else null
            }.takeLast(3)

            val result = consultRole(
                role = role,
                userInput = stepInput,
                contextNotes = contextNotes,
                extraInstructions = stepInstructions,
            )

            steps.add(result)
            totalTokens += result.tokensUsed

            if (result.isSuccess) {
                onStepComplete(role, result.output)
                // 更新累积上下文（使用最新输出）
                accumulatedContext = result.output
            } else {
                DebugLog.w("EditorTeamPipeline: step ${role.alias} failed: ${result.error}")
            }
        }

        // 最终输出取最后一步（排版）的结果，如果没有则取最近的成功结果
        val finalOutput = steps.lastOrNull { it.role == EditorRole.CHEN_PAI_BAN && it.isSuccess }?.output
            ?: steps.lastOrNull { it.isSuccess }?.output
            ?: ""

        PipelineResult(
            steps = steps.toList(),
            finalOutput = finalOutput,
            totalTokensUsed = totalTokens,
            totalDurationMs = System.currentTimeMillis() - startTime,
        )
    }

    /**
     * 单独调用某个角色（用于自由模式）
     */
    suspend fun singleRoleConsult(
        role: EditorRole,
        input: String,
    ): EditorResult {
        return consultRole(
            role = role,
            userInput = input,
        )
    }

    private fun buildSystemPrompt(role: EditorRole, extraInstructions: String): String {
        return if (extraInstructions.isNotBlank()) {
            "${role.systemPrompt}\n\n## 额外指令\n$extraInstructions"
        } else {
            role.systemPrompt
        }
    }

    private fun buildUserMessage(userInput: String, contextNotes: List<String>): String {
        return if (contextNotes.isEmpty()) {
            userInput
        } else {
            val notesSection = contextNotes.joinToString("\n\n---\n\n") { note ->
                "[参考笔记]\n$note"
            }
            """以下是相关的背景笔记/参考资料：

$notesSection

---

## 你的任务
$userInput"""
        }
    }
}
