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
 * step_vision 工具 — 多模态图片理解。
 *
 * 基于 step-1o-turbo-vision 模型，支持:
 * - **OCR 文字识别**: 提取图片中的文字（支持中英文混合）
 * - **图表分析**: 理解柱状图/折线图/饼图/表格数据并提取数值
 * - **截图问答**: 对手机/网页截图进行问答（"这个按钮在哪？"）
 * - **图像描述**: 生成详细的图片内容描述
 * - **文档扫描**: 扫描证件/发票/名片并结构化提取信息
 *
 * ## 与 StepImageEditTool / StepImageGenTool 的区别
 * - StepImageEditTool: 编辑/修改图片（输入图片+指令 → 输出修改后图片）
 * - StepImageGenTool: 生成新图片（文字描述 → 输出图片）
 * - StepVisionTool: **理解**图片内容（输入图片+问题 → 输出文字分析结果）
 *
 * ## 模型选择
 * 默认使用 step-1o-turbo-vision (多模态旗舰)，
 * 可选 step-3.7-flash (也支持视觉但偏通用)
 */
class StepVisionTool(
    private val context: Context,
) : ToolSet {

    companion object {
        private const val TAG = "StepVision"
        private const val BASE_URL = "https://api.stepfun.com/v1"

        /** 视觉模型 */
        const val MODEL_VISION = "step-1o-turbo-vision"

        /** 备选通用模型 */
        const val MODEL_FALLBACK = "step-3.7-flash"

        /** 最大图片 Base64 大小限制 (~5MB PNG) */
        const val MAX_IMAGE_SIZE_BYTES = 5 * 1024 * 1024
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS) // 视觉模型可能较慢
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    override fun getTools(): Map<String, ToolBinding> = mapOf(
        "step_vision" to ToolBinding(
            name = "step_vision",
            description = """使用阶跃星辰多模态 AI 分析和理解图片。基于 step-1o-turbo-vision 模型。

支持的分析类型:
- ocr: OCR文字识别 — 提取图片中的所有文字（中英文、手写、印刷体）
- chart: 图表分析 — 解析图表(柱状图/折线图/饼图/表格)并提取数据和趋势
- screenshot_qa: 截图问答 — 对UI截图进行问答("设置按钮在哪里？""当前页面在做什么？")
- describe: 图像描述 — 详细描述图片内容（物体、场景、颜色、布局等）
- document_scan: 文档扫描 — 结构化提取证件/发票/名片/表单中的信息
- general: 通用分析 — 自由提问关于图片的任何问题""",

            parameters = JSONObject("""{
                "type": "object",
                "properties": {
                    "image_base64": { "type": "string", "description": "待分析的图片 Base64 编码 (PNG/JPEG/WebP)" },
                    "image_path": { "type": "string", "description": "待分析的图片本地文件路径（与 image_base64 二选一）" },
                    "question": {
                        "type": "string",
                        "description": "分析问题或指令。例如: '提取图中所有文字' '这张图表展示了什么数据？' '这是哪个App的界面？'"
                    },
                    "analysis_type": {
                        "type": "string",
                        "enum": ["ocr", "chart", "screenshot_qa", "describe", "document_scan", "general"],
                        "description": "分析类型，帮助模型优化输出格式"
                    },
                    "model": { "type": "string", "description": "模型: step-1o-turbo-vision(推荐), step-3.7-flash(备选)" }
                },
                "required": ["question"]
            }"""),
            invoker = { toolPart, config, _, _ -> execute(toolPart, config) },
        ),
    )

    /**
     * 执行视觉分析。
     */
    private suspend fun execute(toolPart: ToolPart, config: ApiConfig): ToolResult {
        val input = toolPart.state.input
        DebugLog.i("StepVisionTool: 执行视觉分析 — input=${input.toString().take(200)}")

        return try {
            val args = JSONObject(input)
            val question = args.getString("question")
            if (question.isBlank()) return emptyResult(toolPart, "问题 question 不能为空")

            val analysisType = args.optString("analysis_type", "general").ifBlank { "general" }
            val model = args.optString("model", MODEL_VISION).ifBlank { MODEL_VISION }

            // 解析图片
            val imageBase64 = resolveImage(args)
            if (imageBase64 == null) return emptyResult(toolPart, "需要提供 image_base64 或 image_path")

            // 根据分析类型构建优化的 prompt
            val optimizedPrompt = buildPromptForType(analysisType, question)

            // 调用视觉 API
            val result = callVisionApi(
                apiKey = config.apiKey,
                imageBase64 = imageBase64,
                prompt = optimizedPrompt,
                model = model,
                jsonMode = analysisType in setOf("ocr", "chart", "document_scan"),
            )

            if (result.success) {
                successResult(toolPart, buildString {
                    appendLine("[视觉分析完成]")
                    appendLine("模型: $model | 类型: $analysisType")
                    appendLine("问题: $question")
                    appendLine()
                    append(result.analysisText ?: result.rawResponse ?: "(无输出)")
                })
            } else {
                emptyResult(toolPart, "视觉分析失败: ${result.errorMessage}")
            }
        } catch (e: Exception) {
            DebugLog.e("StepVisionTool 异常: ${e.message}", e)
            emptyResult(toolPart, "视觉分析异常: ${e.message}")
        }
    }

    /**
     * 调用阶跃多模态视觉 API。
     *
     * 使用 chat completions 接口，将图片作为 image_url content 发送。
     */
    private suspend fun callVisionApi(
        apiKey: String,
        imageBase64: String,
        prompt: String,
        model: String,
        jsonMode: Boolean = false,
    ): VisionResult = withContext(Dispatchers.IO) {
        try {
            // 构建多模态消息
            val contentArray = buildString {
                append("[")
                append("{\"type\": \"image_url\", \"image_url\": {\"url\": \"data:image/png;base64,$imageBase64\"}}, ")
                append("{\"type\": \"text\", \"text\": ${JSONObject.quote(prompt)}}")
                append("]")
            }

            val messagesJson = "[{\"role\": \"user\", \"content\": $contentArray}]"

            val jsonBody = JSONObject().apply {
                put("model", model)
                put("messages", org.json.JSONArray(messagesJson))
                put("max_tokens", 4096)
                if (jsonMode) {
                    put("response_format", JSONObject().apply { put("type", "json_object") })
                }
            }

            val request = Request.Builder()
                .url("$BASE_URL/chat/completions")
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .build()

            DebugLog.i(TAG, "[vision] model=$model, type=视觉分析, jsonMode=$jsonMode")

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                DebugLog.e(TAG, "[vision] 失败 (${response.code}): $body")
                return@withContext VisionResult(false, errorMessage = "HTTP ${response.code}: ${extractError(body)}")
            }

            val json = JSONObject(body)
            val choices = json.optJSONArray("choices")
            if (choices == null || choices.length() == 0) {
                return@withContext VisionResult(false, errorMessage = "无返回结果")
            }

            val content = choices.getJSONObject(0)
                .optJSONObject("message")?.optString("content", "") ?: ""

            if (content.isBlank()) {
                return@withContext VisionResult(false, errorMessage = "模型返回空内容")
            }

            // 尝试解析 JSON 模式输出
            val parsedAnalysis = try {
                if (jsonMode || content.trimStart().startsWith("{")) {
                    JSONObject(content)
                } else null
            } catch (_: Exception) { null }

            VisionResult(
                success = true,
                rawResponse = content,
                analysisText = formatAnalysisOutput(parsedAnalysis, content),
            )
        } catch (e: Exception) {
            DebugLog.e(TAG, "[vision] 异常: ${e.message}", e)
            VisionResult(false, errorMessage = e.message ?: "未知错误")
        }
    }

    // ---- 针对性 Prompt 构建 ----

    /**
     * 根据分析类型构建优化的系统提示词。
     */
    private fun buildPromptForType(type: String, userQuestion: String): String {
        val basePrompt = when (type) {
            "ocr" -> """
                你是一个专业的 OCR 文字识别引擎。请仔细分析图片中的所有可见文字，
                并按以下 JSON 格式输出:
                {
                  "full_text": "完整的识别文本（保持原有排版和换行）",
                  "blocks": [
                    {"region": "区域描述", "text": "该区域的文字", "confidence": 0.95}
                  ],
                  "language": "检测到的主要语言",
                  "char_count": 总字符数
                }
                注意: 保持原文的换行和空格，不要遗漏任何文字。""".trimIndent()

            "chart" -> """
                你是一个专业的数据分析助手。请分析图片中的图表，按以下 JSON 格式输出:
                {
                  "chart_type": "图表类型(bar/line/pie/table/scatter)",
                  "title": "图表标题",
                  "axes": {"x_axis": "X轴含义", "y_axis": "Y轴含义"},
                  "data_series": [
                    {"name": "系列名称", "values": [数值列表], "color_hint": "颜色"}
                  ],
                  "key_findings": ["关键发现1", "关键发现2"],
                  "trend_summary": "趋势总结"
                }""".trimIndent()

            "screenshot_qa" -> """
                你是一个 UI/UX 分析助手。用户会针对一张 App 或网页截图提出问题，
                请准确回答。如果涉及界面元素位置，请用相对位置描述（如"顶部导航栏右侧"、"屏幕中央偏下"）。""".trimIndent()

            "document_scan" -> """
                你是一个文档扫描和结构化信息提取引擎。请从图片中的文档提取信息，
                按 JSON 格式输出文档类型对应的结构化字段。支持的文档类型:
                - 身份证: 姓名/性别/民族/出生/住址/号码/签发机关/有效期
                - 发票: 发票代码/号码/开票日期/金额/税额/购买方/销售方/商品明细
                - 名片: 姓名/公司/职位/电话/邮箱/地址/网站
                - 通用文档: 自动识别字段并提取
                """.trimIndent()

            "describe" -> """
                请详细描述这张图片的内容，包括:
                1. 主要对象和场景
                2. 颜色和构图
                3. 文字内容（如果有）
                4. 整体氛围和风格
                5. 值得注意的细节
                """.trimIndent()

            else -> "" // general: 不加额外引导
        }

        return if (basePrompt.isNotBlank()) "$basePrompt\n\n用户问题: $userQuestion"
               else userQuestion
    }

    /**
     * 格式化分析输出。
     */
    private fun formatAnalysisOutput(parsedJson: JSONObject?, rawText: String): String? {
        if (parsedJson != null) {
            // 对于结构化输出，格式化为可读文本
            return try {
                when {
                    parsedJson.has("full_text") -> {
                        // OCR 结果
                        buildString {
                            appendLine("**识别文字:**")
                            appendLine(parsedJson.getString("full_text"))
                            if (parsedJson.has("char_count")) {
                                appendLine("\n(共 ${parsedJson.getInt("char_count")} 个字符)")
                            }
                        }
                    }
                    parsedJson.has("chart_type") -> {
                        // 图表分析结果
                        buildString {
                            appendLine("**图表类型:** ${parsedJson.getString("chart_type")}")
                            if (parsedJson.has("title")) appendLine("**标题:** ${parsedJson.getString("title")}")
                            if (parsedJson.has("key_findings")) {
                                appendLine("\n**关键发现:**")
                                val findings = parsedJson.getJSONArray("key_findings")
                                for (i in 0 until findings.length()) {
                                    append("- ${findings.getString(i)}\n")
                                }
                            }
                            if (parsedJson.has("trend_summary")) {
                                appendLine("\n**趋势:** ${parsedJson.getString("trend_summary")}")
                            }
                            if (parsedJson.has("data_series")) {
                                appendLine("\n**数据:**")
                                append(parsedJson.getJSONArray("data_series").toString())
                            }
                        }
                    }
                    else -> {
                        // 其他结构化输出，直接美化 JSON
                        parsedJson.toString(2)
                    }
                }
            } catch (_: Exception) { rawText }
        }
        return rawText
    }

    // ---- 图片解析 ----

    private fun resolveImage(args: JSONObject): String? {
        // 优先 base64
        args.optString("image_base64", "")?.ifBlank { null }?.let {
            if (it.length < MAX_IMAGE_SIZE_BYTES * 4 / 3) return it // base64 ≈ 4/3 原始大小
        }
        // 回退文件路径
        val path = args.optString("image_path", "").ifBlank { null } ?: return null
        return try {
            val bitmap = android.graphics.BitmapFactory.decodeFile(path) ?: return null
            bitmapToBase64(bitmap)
        } catch (_: Exception) { null }
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    // ---- 数据类 ----

    data class VisionResult(
        val success: Boolean,
        val rawResponse: String? = null,
        val analysisText: String? = null,
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
