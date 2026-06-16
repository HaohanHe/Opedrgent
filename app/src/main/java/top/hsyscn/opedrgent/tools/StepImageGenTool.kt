package top.hsyscn.opedrgent.tools

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
import top.hsyscn.opedrgent.settings.ApiConfig
import top.hsyscn.opedrgent.utils.DebugLog
import java.util.concurrent.TimeUnit

/**
 * step_image_generate 工具 — 阶跃星辰图片生成 (文生图 / 图生图)。
 *
 * 基于 StepFun Images API:
 * - 端点: POST https://api.stepfun.com/v1/images/generations
 *
 * ## 能力
 * - 文生图: 通过 prompt 描述生成图片
 * - 图生图: 传入参考图片 + prompt 进行风格化生成
 * - 支持多种尺寸和输出格式
 */
class StepImageGenTool : ToolSet {

    companion object {
        private const val TAG = "StepImageGen"
        private const val BASE_URL = "https://api.stepfun.com/v1"

        /** 轻量快速模型 (文生图/图生图) */
        const val MODEL_MEDIUM = "step-1x-medium"
        /** 高质量模型 (支持 cfg_scale, steps, seed) */
        const val MODEL_LARGE = "step-2x-large"
        /** 默认模型 */
        const val DEFAULT_MODEL = MODEL_MEDIUM
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    override fun getTools(): Map<String, ToolBinding> = mapOf(
        "step_image_generate" to ToolBinding(
            name = "step_image_generate",
            description = "使用阶跃星辰 AI 生成图片。支持文生图（纯文字描述）和图生图（参考图+描述）。适用于创建插图、设计稿、概念图、头像等。可用模型: step-1x-medium(轻量快速), step-2x-large(高质量, 支持 cfg_scale/steps/seed)",
            parameters = JSONObject("""{
                "type": "object",
                "properties": {
                    "prompt": {
                        "type": "string",
                        "description": "图片生成提示词，详细描述想要生成的画面内容、风格、构图等。中文或英文均可"
                    },
                    "reference_image_base64": {
                        "type": "string",
                        "description": "可选，参考图片的 Base64 编码。提供后进行图生图（风格迁移/重绘），不提供则为纯文生图"
                    },
                    "size": {
                        "type": "string",
                        "description": "输出尺寸: 1024x1024 (默认正方形), 768x1344 (竖版), 1344x768 (横版), 1440x960 等"
                    },
                    "n": {
                        "type": "integer",
                        "description": "生成数量 (1-4)，默认 1"
                    },
                    "quality": {
                        "type": "string",
                        "description": "质量: standard (标准/快速), hd (高清)"
                    }
                },
                "required": ["prompt"]
            }"""),
            invoker = { toolPart, config, _, _ -> execute(toolPart, config) },
        ),
    )

    /**
     * 执行图片生成。
     */
    private suspend fun execute(toolPart: ToolPart, config: ApiConfig): ToolResult {
        val input = toolPart.state.input
        DebugLog.i("StepImageGenTool: 执行图片生成 — input=${input.toString().take(200)}")

        return try {
            val args = JSONObject(input)
            val prompt = args.getString("prompt")
            if (prompt.isBlank()) return emptyResult(toolPart, "生成提示词 prompt 不能为空")

            val size = args.optString("size", "1024x1024").ifBlank { "1024x1024" }
            val n = args.optInt("n", 1).coerceIn(1, 4)
            val quality = args.optString("quality", "standard").ifBlank { "standard" }
            val model = args.optString("model", DEFAULT_MODEL).ifBlank { DEFAULT_MODEL }
            val seed = args.optInt("seed", -1)
            val cfgScale = args.optDouble("cfg_scale", -1.0)
            val steps = args.optInt("steps", -1)
            val refImageB64 = args.optString("reference_image_base64", "").ifBlank { null }

            // 调用 API
            val result = generateImage(
                apiKey = config.apiKey,
                prompt = prompt,
                model = model,
                refImageBase64 = refImageB64,
                size = size,
                n = n,
                quality = quality,
                seed = if (seed > 0) seed else null,
                cfgScale = if (cfgScale > 0) cfgScale else null,
                steps = if (steps > 0) steps else null,
            )

            if (result.success) {
                successResult(toolPart, buildString {
                    appendLine("[图片生成完成]")
                    appendLine("模型: $model | Prompt: $prompt")
                    appendLine("尺寸: $size x ${n}张 | 质量: $quality")
                    if (seed > 0) appendLine("种子: $seed")
                    if (refImageB64 != null) {
                        appendLine("模式: 图生图 (有参考图)")
                    } else {
                        appendLine("模式: 纯文生图")
                    }
                    result.images.forEachIndexed { index, img ->
                        appendLine()
                        appendLine("--- 图片 ${index + 1} ---")
                        if (img.b64Json != null) {
                            appendLine("[base64 图片数据, 长度: ${img.b64Json.length}]")
                        } else if (img.url != null) {
                            appendLine("URL: ${img.url}")
                        }
                    }
                    if (result.revisedPrompt != null) {
                        appendLine("修订 Prompt: ${result.revisedPrompt}")
                    }
                })
            } else {
                emptyResult(toolPart, "图片生成失败: ${result.errorMessage}")
            }
        } catch (e: Exception) {
            DebugLog.e("StepImageGenTool 异常: ${e.message}", e)
            emptyResult(toolPart, "图片生成异常: ${e.message}")
        }
    }

    /**
     * 调用阶跃图片生成 API。
     */
    private suspend fun generateImage(
        apiKey: String,
        prompt: String,
        model: String = DEFAULT_MODEL,
        refImageBase64: String?,
        size: String,
        n: Int,
        quality: String,
        seed: Int? = null,
        cfgScale: Double? = null,
        steps: Int? = null,
    ): GenResult = withContext(Dispatchers.IO) {
        try {
            val jsonBody = JSONObject().apply {
                put("model", model) // step-1x-medium 或 step-2x-large
                put("prompt", prompt)
                put("size", size)
                put("n", n)
                put("quality", quality)
                put("response_format", "b64_json")
                if (refImageBase64 != null) {
                    // 图生图：传入参考图
                    put("image", refImageBase64)
                }
                // 高级参数（主要对 step-2x-large 有效）
                if (seed != null && seed > 0) put("seed", seed)
                if (cfgScale != null && cfgScale > 0) put("cfg_scale", cfgScale)
                if (steps != null && steps > 0) put("steps", steps)
            }

            val request = Request.Builder()
                .url("$BASE_URL/images/generations")
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .build()

            DebugLog.i(TAG, "调用图片生成 API: model=$model, prompt=${prompt.take(50)}..., size=$size, n=$n")

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                DebugLog.e(TAG, "图片生成失败 (${response.code}): $body")
                return@withContext GenResult(false, errorMessage = "HTTP ${response.code}: ${extractError(body)}")
            }

            val json = JSONObject(body)
            val dataArr = json.optJSONArray("data") ?: return@withContext GenResult(false, errorMessage = "无返回数据")

            val images = mutableListOf<GeneratedImage>()
            for (i in 0 until dataArr.length()) {
                val item = dataArr.getJSONObject(i)
                images.add(GeneratedImage(
                    b64Json = item.optString("b64_json").ifBlank { null },
                    url = item.optString("url").ifBlank { null },
                ))
            }

            val revisedPrompt = json.optString("revised_prompt").ifBlank { null }

            GenResult(
                success = true,
                images = images,
                revisedPrompt = revisedPrompt,
            )
        } catch (e: Exception) {
            DebugLog.e(TAG, "图片生成异常: ${e.message}", e)
            GenResult(false, errorMessage = e.message ?: "未知错误")
        }
    }

    // ---- 数据类 ----

    data class GeneratedImage(
        val b64Json: String? = null,
        val url: String? = null,
    )

    data class GenResult(
        val success: Boolean,
        val images: List<GeneratedImage> = emptyList(),
        val revisedPrompt: String? = null,
        val errorMessage: String? = null,
    )

    // ---- 辅助方法 ----

    private fun extractError(body: String): String {
        return try {
            val json = JSONObject(body)
            json.optJSONObject("error")?.optString("message")
                ?: json.optString("error", body.take(200))
        } catch (_: Exception) { body.take(200) }
    }

    private fun successResult(tp: ToolPart, text: String): ToolResult {
        return ToolResult(
            toolPart = tp.copy(
                state = tp.state.copy(
                    status = ToolStateType.COMPLETED,
                    output = text,
                    endTime = System.currentTimeMillis(),
                ),
            ),
        )
    }

    private fun emptyResult(tp: ToolPart, msg: String): ToolResult {
        return ToolResult(
            toolPart = tp.copy(
                state = tp.state.copy(
                    status = ToolStateType.ERROR,
                    error = msg,
                    endTime = System.currentTimeMillis(),
                ),
            ),
        )
    }
}
