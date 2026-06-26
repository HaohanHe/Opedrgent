package top.hsyscn.opedrgent.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
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
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 阶跃星辰图像工具集 — 图像编辑 + 图生图 (Image-to-Image)。
 *
 * ## 支持的 API 端点
 *
 * ### 1. 图像编辑 (Edits)
 * - 端点: POST /v1/images/edits (multipart form)
 * - 用途: 局部编辑、全图重绘、风格迁移、背景替换、物体移除
 * - 输入: base64/文件上传
 *
 * ### 2. 图生图 (Image-to-Image)
 * - 端点: POST /v1/images/image2image (JSON)
 * - 用途: 基于源图生成变体（风格化、融合、变换）
 * - 输入: URL 引用源图（无需上传）
 * - 特有参数: source_weight(融合度), cfg_scale, steps, seed
 *
 * ## 支持模型 (来自官方文档 curl 示例)
 *
 * | 模型 | 类型 | 特点 |
 * |------|------|------|
 * | `step-image-edit-2` | 编辑 | 极速 1-2s, 支持 text_mode, steps=8 |
 * | `step-1x-edit` | 编辑 | 免费模型 |
 * | `step-1x-medium` | 图生图/文生图 | 轻量快速 |
 * | `step-2x-large` | 高质量生成 | 支持 cfg_scale/steps/seed, 更高质量 |
 */
class StepImageEditTool(
    private val context: Context,
) : ToolSet {

    companion object {
        private const val TAG = "StepImageEdit"
        private const val BASE_URL = "https://api.stepfun.com/v1"

        // ---- 编辑模型 ----
        const val MODEL_EDIT_FAST = "step-image-edit-2"     // 极速编辑 (1-2s)
        const val MODEL_EDIT_FREE = "step-1x-edit"          // 免费编辑

        // ---- 图生图/文生图模型 ----
        const val MODEL_MEDIUM = "step-1x-medium"            // 轻量快速
        const val MODEL_LARGE = "step-2x-large"              // 高质量

        /** 全部支持的图像操作模型 */
        val ALL_MODELS = listOf(
            MODEL_EDIT_FAST, MODEL_EDIT_FREE,
            MODEL_MEDIUM, MODEL_LARGE,
        )

        /** 默认编辑模型 */
        const val DEFAULT_EDIT_MODEL = MODEL_EDIT_FAST

        /** 默认图生图模型 */
        const val DEFAULT_I2I_MODEL = MODEL_MEDIUM

        /** 支持的输出尺寸 */
        val SUPPORTED_SIZES = listOf(
            "1024x1024", "768x1344", "832x1216", "1216x832",
            "1440x960", "1344x768", "4096x4096",
        )
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    override fun getTools(): Map<String, ToolBinding> = mapOf(
        // ---- 工具1: 图像编辑 (multipart form 上传) ----
        "step_image_edit" to ToolBinding(
            name = "step_image_edit",
            description = """使用阶跃星辰 AI 编辑图片。支持局部编辑、风格迁移、背景替换、物体添加/移除等。
传入原始图片(base64或本地路径)和编辑指令prompt即可。
可用模型: step-image-edit-2(推荐极速1-2s), step-1x-edit(免费)""",
            parameters = JSONObject("""{
                "type": "object",
                "properties": {
                    "image_base64": { "type": "string", "description": "待编辑图片的 Base64 编码 (PNG/JPEG/WebP)" },
                    "image_path": { "type": "string", "description": "待编辑图片的本地文件路径（与 image_base64 二选一）" },
                    "prompt": { "type": "string", "description": "编辑指令。例如: '将背景换成海滩' '移除图中的人物' '改为赛博朋克风格' '将猫变成老虎'" },
                    "model": { "type": "string", "description": "模型: step-image-edit-2(推荐极速), step-1x-edit(免费)" },
                    "size": { "type": "string", "description": "输出尺寸: 1024x1024(默认), 768x1344, 1440x960, 4096x4096 等" }
                },
                "required": ["prompt"]
            }"""),
            invoker = { toolPart, config, _, _ -> executeEdit(toolPart, config) },
        ),

        // ---- 工具2: 图生图 Image-to-Image (URL引用源图) ----
        "step_image_to_image" to ToolBinding(
            name = "step_image_to_image",
            description = """基于源图进行图像变换/风格化/融合。通过 URL 引用源图，支持精确控制融合度和生成质量。
适合风格迁移、人物换装、场景变换等需要保持原图构图的操作。
可用模型: step-1x-medium(轻量快速), step-2x-large(高质量)""",
            parameters = JSONObject("""{
                "type": "object",
                "properties": {
                    "source_url": { "type": "string", "description": "源图的 URL 地址" },
                    "source_base64": { "type": "string", "description": "源图的 Base64 编码（如果无法提供 URL）" },
                    "prompt": { "type": "string", "description": "变换描述。例如: '换成宫崎骏风格' '变为油画效果' '转为黑白素描' '改为水彩画'" },
                    "model": { "type": "string", "description": "模型: step-1x-medium(轻量), step-2x-large(高质量)" },
                    "source_weight": { "type": "number", "description": "源图融合度 0.0-1.0，越高越接近原图 (默认 0.5)" },
                    "cfg_scale": { "type": "number", "description": "提示词引导强度，越高越严格遵循 prompt (默认 6.0, step-2x-large 可用)" },
                    "steps": { "type": "integer", "description": "推理步数，越多质量越好但越慢 (默认 20, step-2x-large 可用 50)" },
                    "seed": { "type": "integer", "description": "随机种子，相同种子+参数可复现结果" },
                    "size": { "type": "string", "description": "输出尺寸: 1024x1024(默认), 768x1344 等" }
                },
                "required": ["prompt", "source_url"]
            }"""),
            invoker = { toolPart, config, _, _ -> executeImageToImage(toolPart, config) },
        ),
    )

    // ================================================================
    // 工具1: 图像编辑 (multipart form upload → /v1/images/edits)
    // ================================================================

    private suspend fun executeEdit(toolPart: ToolPart, config: ApiConfig): ToolResult {
        val input = toolPart.state.input
        DebugLog.i("StepImageEditTool: 执行图像编辑 — input=${input.toString().take(200)}")

        return try {
            val args = JSONObject(input)
            val prompt = args.getString("prompt")
            if (prompt.isBlank()) return emptyResult(toolPart, "编辑指令 prompt 不能为空")

            val model = args.optString("model", DEFAULT_EDIT_MODEL).ifBlank { DEFAULT_EDIT_MODEL }
            val size = args.optString("size", "1024x1024").ifBlank { "1024x1024" }
            val imageBase64 = resolveImageData(args)
            if (imageBase64 == null) return emptyResult(toolPart, "需要提供 image_base64 或 image_path")

            val result = callEditsApi(config.apiKey, imageBase64, prompt, model, size)

            if (result.success) formatSuccess(toolPart, result, "图像编辑", prompt, model, size)
            else emptyResult(toolPart, "图像编辑失败: ${result.errorMessage}")
        } catch (e: Exception) {
            DebugLog.e("StepImageEditTool 异常: ${e.message}", e)
            emptyResult(toolPart, "图像编辑异常: ${e.message}")
        }
    }

    /**
     * 调用图像编辑 API (multipart form upload).
     *
     * POST /v1/images/edits
     */
    private suspend fun callEditsApi(
        apiKey: String, imageBase64: String, prompt: String,
        model: String, size: String,
    ): EditResult = withContext(Dispatchers.IO) {
        try {
            val imageBytes = Base64.decode(imageBase64, Base64.DEFAULT)
            val fileBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("image", "edit_input.png",
                    imageBytes.toRequestBody("image/png".toMediaType()))
                .addFormDataPart("model", model)
                .addFormDataPart("prompt", prompt)
                .addFormDataPart("size", size)
                .addFormDataPart("n", "1")
                .addFormDataPart("response_format", "b64_json")
                .build()

            val request = Request.Builder()
                .url("$BASE_URL/images/edits")
                .post(fileBody)
                .header("Authorization", "Bearer $apiKey")
                .build()

            DebugLog.i(TAG, "[edits] model=$model, prompt=${prompt.take(50)}...")

            val response = client.newCall(request).execute()
            parseImageResponse(response, "edits")
        } catch (e: Exception) {
            DebugLog.e(TAG, "[edits] 异常: ${e.message}", e)
            EditResult(false, errorMessage = e.message ?: "未知错误")
        }
    }

    // ================================================================
    // 工具2: 图生图 (JSON → /v1/images/image2image)
    // ================================================================

    private suspend fun executeImageToImage(toolPart: ToolPart, config: ApiConfig): ToolResult {
        val input = toolPart.state.input
        DebugLog.i("StepImageEditTool: 执行图生图 — input=${input.toString().take(200)}")

        return try {
            val args = JSONObject(input)
            val prompt = args.getString("prompt")
            if (prompt.isBlank()) return emptyResult(toolPart, "变换描述 prompt 不能为空")

            val model = args.optString("model", DEFAULT_I2I_MODEL).ifBlank { DEFAULT_I2I_MODEL }
            val size = args.optString("size", "1024x1024").ifBlank { "1024x1024" }
            val sourceWeight = args.optDouble("source_weight", 0.5).coerceIn(0.0, 1.0)
            val cfgScale = args.optDouble("cfg_scale", 6.0).coerceIn(1.0, 20.0)
            val steps = args.optInt("steps", 20).coerceIn(1, 100)
            val seed = args.optInt("seed", -1)

            // 源图：优先 URL，回退 base64
            val sourceUrl = args.optString("source_url", "").ifBlank { null }
            val sourceB64 = args.optString("source_base64", "").ifBlank { null }

            if (sourceUrl == null && sourceB64 == null) {
                return emptyResult(toolPart, "需要提供 source_url 或 source_base64")
            }

            val result = callImage2ImageApi(
                apiKey = config.apiKey,
                prompt = prompt,
                model = model,
                size = size,
                sourceUrl = sourceUrl,
                sourceB64 = sourceB64,
                sourceWeight = sourceWeight,
                cfgScale = cfgScale,
                steps = steps,
                seed = seed,
            )

            if (result.success) {
                formatSuccess(toolPart, result, "图生图", prompt, model, size,
                    extraInfo = buildString {
                        if (sourceWeight != 0.5) appendLine("源图融合度: $sourceWeight")
                        if (cfgScale != 6.0) appendLine("引导强度: $cfgScale")
                        if (steps != 20) appendLine("推理步数: $steps")
                        if (seed > 0) appendLine("随机种子: $seed")
                    })
            } else {
                emptyResult(toolPart, "图生图失败: ${result.errorMessage}")
            }
        } catch (e: Exception) {
            DebugLog.e("StepImageEditTool[图生图] 异常: ${e.message}", e)
            emptyResult(toolPart, "图生图异常: ${e.message}")
        }
    }

    /**
     * 调用图生图 API (JSON body).
     *
     * POST /v1/images/image2image
     *
     * 支持两种输入方式:
     * 1. source_url: 直接引用远程图片 URL
     * 2. source_b64: 本地图片转 base64 作为 data URI
     */
    private suspend fun callImage2ImageApi(
        apiKey: String, prompt: String, model: String, size: String,
        sourceUrl: String?, sourceB64: String?,
        sourceWeight: Double, cfgScale: Double, steps: Int, seed: Int,
    ): EditResult = withContext(Dispatchers.IO) {
        try {
            val jsonBody = JSONObject().apply {
                put("model", model)
                put("prompt", prompt)
                put("size", size)
                put("response_format", "b64_json")

                // 源图：URL 优先
                if (sourceUrl != null) {
                    put("source_url", sourceUrl)
                } else if (sourceB64 != null) {
                    // base64 转 data URI
                    put("source_url", "data:image/png;base64,$sourceB64")
                }

                // 高级参数（主要对 step-2x-large 有效）
                put("source_weight", sourceWeight)

                // step-2x-large 专属参数
                if (model == MODEL_LARGE || steps != 20) {
                    put("steps", steps)
                }
                if (model == MODEL_LARGE || cfgScale != 6.0) {
                    put("cfg_scale", cfgScale)
                }
                if (seed > 0) {
                    put("seed", seed)
                }
            }

            val request = Request.Builder()
                .url("$BASE_URL/images/image2image")
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .build()

            DebugLog.i(TAG, "[image2image] model=$model, prompt=${prompt.take(50)}..., weight=$sourceWeight, steps=$steps")

            val response = client.newCall(request).execute()
            parseImageResponse(response, "image2image")
        } catch (e: Exception) {
            DebugLog.e(TAG, "[image2image] 异常: ${e.message}", e)
            EditResult(false, errorMessage = e.message ?: "未知错误")
        }
    }

    // ================================================================
    // 公共辅助方法
    // ================================================================

    /**
     * 解析图像 API 的统一响应格式。
     */
    private suspend fun parseImageResponse(response: okhttp3.Response, endpoint: String): EditResult {
        return try {
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                DebugLog.e(TAG, "[$endpoint] 失败 (${response.code}): $body")
                return EditResult(false, errorMessage = "HTTP ${response.code}: ${extractError(body)}")
            }

            val json = JSONObject(body)
            val dataArr = json.optJSONArray("data") ?: return EditResult(false, errorMessage = "无返回数据")
            if (dataArr.length() == 0) return EditResult(false, errorMessage = "返回数据为空")

            val firstItem = dataArr.getJSONObject(0)
            EditResult(
                success = true,
                dataB64 = firstItem.optString("b64_json").ifBlank { null },
                dataUrl = firstItem.optString("url").ifBlank { null },
                revisedPrompt = firstItem.optString("revised_prompt").ifBlank { null },
                finishReason = firstItem.optString("finish_reason", "").ifBlank { null },
                seed = firstItem.optLong("seed", -1).takeIf { it > 0L }?.toInt(),
            )
        } catch (e: Exception) {
            DebugLog.e(TAG, "[$endpoint] 解析响应异常: ${e.message}", e)
            EditResult(false, errorMessage = e.message ?: "解析异常")
        }
    }

    private fun resolveImageData(args: JSONObject): String? {
        // 优先 base64
        args.optString("image_base64", "")?.ifBlank { null }?.let { return it }
        // 回退文件路径
        val path = args.optString("image_path", "").ifBlank { null } ?: return null
        return try {
            val file = File(path)
            if (!file.exists()) null
            else {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return null
                bitmapToBase64(bitmap, "png")
            }
        } catch (_: Exception) { null }
    }

    private fun formatSuccess(tp: ToolPart, r: EditResult, operation: String,
                               prompt: String, model: String, size: String,
                               extraInfo: String? = null): ToolResult {
        return successResult(tp, buildString {
            appendLine("[$operation 完成]")
            appendLine("模型: $model | 尺寸: $size")
            appendLine("指令: $prompt")
            extraInfo?.let { if (it.isNotEmpty()) { appendLine(); append(it) } }
            appendLine()
            if (r.dataB64 != null) appendLine("[生成图片 — base64 长度: ${r.dataB64.length}]")
            else if (r.dataUrl != null) appendLine("[图片 URL: ${r.dataUrl}]")
            if (r.revisedPrompt != null) appendLine("修订 Prompt: ${r.revisedPrompt}")
            if (r.seed != null && r.seed > 0) appendLine("种子: ${r.seed}")
            if (r.finishReason != null) appendLine("完成原因: ${r.finishReason}")
        })
    }

    // ---- 数据类 ----

    data class EditResult(
        val success: Boolean,
        val dataB64: String? = null,
        val dataUrl: String? = null,
        val revisedPrompt: String? = null,
        val finishReason: String? = null,
        val seed: Int? = null,
        val errorMessage: String? = null,
    )

    // ---- 底层方法 ----

    private fun bitmapToBase64(bitmap: Bitmap, format: String): String {
        val quality = if (format == "png") 100 else 90
        val compressFormat = when (format) {
            "jpeg", "jpg" -> Bitmap.CompressFormat.JPEG
            "webp" -> Bitmap.CompressFormat.WEBP
            else -> Bitmap.CompressFormat.PNG
        }
        val stream = ByteArrayOutputStream()
        bitmap.compress(compressFormat, quality, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    private fun extractError(body: String): String = try {
        val json = JSONObject(body)
        json.optJSONObject("error")?.optString("message") ?: json.optString("error", body.take(200))
    } catch (_: Exception) { body.take(200) }

    private fun successResult(tp: ToolPart, text: String): ToolResult = ToolResult(
        toolPart = tp.copy(state = tp.state.copy(status = ToolStateType.COMPLETED, output = text, endTime = System.currentTimeMillis())),
    )

    suspend fun validateApiKey(apiKey: String): Boolean = withContext(Dispatchers.IO) {
        try { client.newCall(Request.Builder().url("$BASE_URL/models").get().header("Authorization", "Bearer $apiKey").build()).execute().isSuccessful }
        catch (_: Exception) { false }
    }
}
