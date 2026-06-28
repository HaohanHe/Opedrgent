package top.hsyscn.opedrgent.tools

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import top.hsyscn.opedrgent.model.ToolPart
import top.hsyscn.opedrgent.model.ToolStateType
import top.hsyscn.opedrgent.network.ToolResult
import top.hsyscn.opedrgent.network.emptyResult
import top.hsyscn.opedrgent.settings.ApiConfig
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * step_mobile_agent 工具 — 阶跃星辰手机操作 Agent。
 *
 * 基于 step-3.7-flash-mobile-agent 模型:
 * - 专为移动端 GUI 自动化设计
 * - 理解手机屏幕截图 → 生成操作动作序列
 * - 支持多步任务规划（点击/滑动/输入/返回等）
 *
 * ## 与现有工具的关系
 * - RunIntentTool: 执行具体原生操作（发邮件/短信/日历等）
 * - WebViewAgent: 浏览器自动化（搜索/抓取/JS执行）
 * - StepMobileAgent: **视觉理解 + 动作规划层** — 看截图、理解界面、规划动作
 *
 * ## 工作流程
 * 1. 截取当前屏幕或接收截图 base64
 * 2. 将截图 + 任务描述发送给 step-3.7-flash-mobile-agent
 * 3. 模型分析 UI 布局，输出结构化动作 JSON
 * 4. 返回动作计划供 LLM 决定是否通过 run_intent / webview_agent 执行
 */
class StepMobileAgentTool(
    private val context: Context,
) : ToolSet {

    companion object {
        private const val TAG = "StepMobileAgent"
        private const val BASE_URL = "https://api.stepfun.com/v1"

        /** 手机操作 Agent 模型 */
        const val MODEL_MOBILE_AGENT = "step-3.7-flash-mobile-agent"

        /** 备选通用模型（如果 mobile-agent 不可用） */
        const val MODEL_FALLBACK = "step-3.7-flash"
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS) // 视觉模型可能较慢
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    override fun getTools(): Map<String, ToolBinding> = mapOf(
        "step_mobile_agent" to ToolBinding(
            name = "step_mobile_agent",
            description = """使用阶跃星辰手机操作 AI 分析屏幕并规划操作步骤。
传入当前屏幕截图(base64)和任务描述，模型会识别界面元素、理解布局结构，
然后输出可执行的步骤序列。适用于: 自动化测试、无障碍辅助、复杂 App 操作引导。
支持的动作类型: tap(点击), long_press(长按), swipe(滑动), input_text(输入文字),
press_back(返回), press_home(主页), scroll(滚动), wait(等待) 等。""",
            parameters = JSONObject("""{
                "type": "object",
                "properties": {
                    "task": {
                        "type": "string",
                        "description": "要完成的任务描述。例如: '打开设置并关闭蓝牙' '在微信中找到张三的聊天窗口并发送文件' '打开相册选择最近的照片'"
                    },
                    "screenshot_base64": {
                        "type": "string",
                        "description": "当前屏幕截图的 Base64 编码 (PNG/JPEG)。模型会分析这个截图来理解当前界面"
                    },
                    "current_app": {
                        "type": "string",
                        "description": "当前正在使用的应用名称（可选，帮助模型定位上下文）"
                    },
                    "previous_actions": {
                        "type": "string",
                        "description": "已执行的历史动作（可选，用于多步任务的连续性）"
                    },
                    "model": {
                        "type": "string",
                        "description": "模型: step-3.7-flash-mobile-agent(推荐), step-3.7-flash(备选)"
                    }
                },
                "required": ["task"]
            }"""),
            invoker = { toolPart, config, _, _ -> execute(toolPart, config) },
        ),
    )

    /**
     * 执行手机 Agent 分析。
     */
    private suspend fun execute(toolPart: ToolPart, config: ApiConfig): ToolResult {
        val input = toolPart.state.input
        DebugLog.i("StepMobileAgentTool: 执行分析 — input=${input.toString().take(200)}")

        return try {
            val args = JSONObject(input)
            val task = args.getString("task")
            if (task.isBlank()) return emptyResult(toolPart, "任务描述 task 不能为空")

            val model = args.optString("model", MODEL_MOBILE_AGENT)
                .ifBlank { MODEL_MOBILE_AGENT }
            val currentApp = args.optString("current_app", "").ifBlank { null }
            val previousActions = args.optString("previous_actions", "").ifBlank { null }
            val screenshotB64 = args.optString("screenshot_base64", "").ifBlank { null }

            // 调用 Mobile Agent API
            val result = analyzeScreen(
                apiKey = config.apiKey,
                task = task,
                screenshotBase64 = screenshotB64,
                currentApp = currentApp,
                previousActions = previousActions,
                model = model,
            )

            if (result.success) {
                successResult(toolPart, buildString {
                    appendLine("[手机操作 Agent 分析结果]")
                    appendLine("任务: $task")
                    appendLine("模型: $model")
                    if (currentApp != null) appendLine("当前应用: $currentApp")
                    if (previousActions != null) appendLine("历史动作: $previousActions")
                    appendLine()
                    appendLine("--- 分析结论 ---")
                    if (result.screenDescription != null) {
                        appendLine("**界面描述:** ${result.screenDescription}")
                        appendLine()
                    }
                    if (result.actionPlan != null) {
                        appendLine("**操作步骤:**")
                        appendLine(result.actionPlan)
                    } else {
                        appendLine(result.rawResponse ?: "(无结构化输出)")
                    }
                    if (result.confidence != null) {
                        appendLine()
                        appendLine("置信度: ${result.confidence}")
                    }
                })
            } else {
                emptyResult(toolPart, "Mobile Agent 分析失败: ${result.errorMessage}")
            }
        } catch (e: Exception) {
            DebugLog.e("StepMobileAgentTool 异常: ${e.message}", e)
            emptyResult(toolPart, "Mobile Agent 异常: ${e.message}")
        }
    }

    /**
     * 直接调用 Agent 分析（供 [top.hsyscn.opedrgent.agent.MobileAgentOrchestrator] 编排使用）。
     *
     * 与 [execute] 不同，本方法返回原始 [AgentResult]，不包装为 ToolResult，
     * 方便编排器直接读取 actionPlan / screenDescription 等结构化字段。
     */
    suspend fun analyzeTask(
        apiKey: String,
        task: String,
        screenshotBase64: String?,
        currentApp: String? = null,
        previousActions: String? = null,
        model: String = MODEL_MOBILE_AGENT,
    ): AgentResult {
        return analyzeScreen(
            apiKey = apiKey,
            task = task,
            screenshotBase64 = screenshotBase64,
            currentApp = currentApp,
            previousActions = previousActions,
            model = model,
        )
    }

    /**
     * 调用阶跃 Mobile Agent API。
     *
     * 使用 chat completions 接口，将截图作为图像内容发送给 mobile-agent 模型。
     * 模型会分析截图并返回结构化的动作 JSON。
     */
    private suspend fun analyzeScreen(
        apiKey: String,
        task: String,
        screenshotBase64: String?,
        currentApp: String?,
        previousActions: String?,
        model: String,
    ): AgentResult = withContext(Dispatchers.IO) {
        try {
            // 构建 system prompt — 引导模型输出结构化动作
            val systemPrompt = buildString {
                appendLine("你是一个专业的手机操作助手。根据用户提供的屏幕截图和任务描述，分析当前界面并给出具体的操作步骤。")
                appendLine()
                appendLine("## 输出格式要求")
                appendLine("请严格按以下 JSON 格式输出（不要包含其他内容）:")
                appendLine("""{
  "screen_description": "对当前界面的简要描述",
  "detected_elements": ["检测到的UI元素列表"],
  "action_plan": [
    {"step": 1, "action": "tap|long_press|swipe|input_text|press_back|press_home|scroll|wait", "target": "目标元素描述", "detail": "详细说明"}
  ],
  "confidence": 0.0-1.0,
  "notes": "补充说明"
}""")
                appendLine()
                appendLine("## 支持的动作类型")
                appendLine("- tap: 点击指定位置或元素")
                appendLine("- long_press: 长按")
                appendLine("- swipe: 滑动 (需说明方向和距离)")
                appendLine("- input_text: 输入文字")
                appendLine("- press_back: 按返回键")
                appendLine("- press_home: 按主页键")
                appendLine("- scroll: 滚动 (需说明方向)")
                appendLine("- wait: 等待页面加载")
                appendLine()
                appendLine("## 重要提示")
                appendLine("- 如果无法确定元素位置，confidence 应设为较低值")
                appendLine("- action_plan 可以是空的（如果需要更多信息）")
                appendLine("- 保持简洁实用，每个步骤不超过一句话")
            }

            // 构建消息
            val messagesJson = buildString {
                append("[")
                append("{\"role\": \"system\", \"content\": ${JSONObject.quote(systemPrompt)}}")
                // 用户消息
                val userContent = buildString {
                    append("请帮我完成以下任务: ")
                    append(JSONObject.quote(task))
                    if (currentApp != null) {
                        append("\\n\\n当前应用: ")
                        append(JSONObject.quote(currentApp))
                    }
                    if (previousActions != null) {
                        append("\\n\\n已完成的步骤: ")
                        append(JSONObject.quote(previousActions))
                    }
                    append("\\n\\n请分析截图并给出操作方案。")
                }
                if (screenshotBase64 != null) {
                    // 多模态消息：图片 + 文字
                    append(", {\"role\": \"user\", \"content\": [")
                    append("{\"type\": \"image_url\", \"image_url\": {\"url\": \"data:image/png;base64,$screenshotBase64\"}}, ")
                    append("{\"type\": \"text\", \"text\": \"$userContent\"}")
                    append("]}")
                } else {
                    // 纯文本模式（无截图时依赖描述）
                    append(", {\"role\": \"user\", \"content\": \"$userContent (注意: 未提供截图，基于常见界面布局推断)\"}")
                }
                append("]")
            }

            val jsonBody = JSONObject().apply {
                put("model", model)
                put("messages", org.json.JSONArray(messagesJson))
                put("max_tokens", 2048)
                put("temperature", 0.1)   // 低温度保证输出稳定
                // 强制 JSON 输出
                put("response_format", JSONObject().apply { put("type", "json_object") })
            }

            val request = Request.Builder()
                .url("$BASE_URL/chat/completions")
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .build()

            DebugLog.i(TAG, "[mobile-agent] model=$model, task=${task.take(50)}..., hasScreenshot=${screenshotBase64 != null}")

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                DebugLog.e(TAG, "[mobile-agent] 失败 (${response.code}): $body")
                return@withContext AgentResult(false, errorMessage = "HTTP ${response.code}: ${extractError(body)}")
            }

            val json = JSONObject(body)
            val choices = json.optJSONArray("choices")
            if (choices == null || choices.length() == 0) {
                return@withContext AgentResult(false, errorMessage = "无返回 choices")
            }

            val content = choices.getJSONObject(0)
                .optJSONObject("message")?.optString("content", "") ?: ""

            if (content.isBlank()) {
                return@withContext AgentResult(false, errorMessage = "模型返回空内容")
            }

            // 尝试解析结构化 JSON
            val parsed = try {
                JSONObject(content)
            } catch (_: Exception) {
                null
            }

            AgentResult(
                success = true,
                rawResponse = content,
                screenDescription = parsed?.optString("screen_description"),
                actionPlan = parsed?.optJSONArray("action_plan")?.toString()
                    ?: parsed?.optString("action_plan"),
                confidence = parsed?.optDouble("confidence")?.let { "${(it * 100).toInt()}%" },
            )
        } catch (e: Exception) {
            DebugLog.e(TAG, "[mobile-agent] 异常: ${e.message}", e)
            AgentResult(false, errorMessage = e.message ?: "未知错误")
        }
    }

    // ---- 数据类 ----

    data class AgentResult(
        val success: Boolean,
        val rawResponse: String? = null,
        val screenDescription: String? = null,
        val actionPlan: String? = null,
        val confidence: String? = null,
        val errorMessage: String? = null,
    )

    // ---- 辅助方法 ----

    private fun extractError(body: String): String = try {
        val json = JSONObject(body)
        json.optJSONObject("error")?.optString("message") ?: json.optString("error", body.take(200))
    } catch (_: Exception) { body.take(200) }

    private fun successResult(tp: ToolPart, text: String): ToolResult = ToolResult(
        toolPart = tp.copy(state = tp.state.copy(status = ToolStateType.COMPLETED, output = text, endTime = System.currentTimeMillis())),
    )
}
