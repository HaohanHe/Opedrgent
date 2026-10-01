package top.hsyscn.opedrgent.tools

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import top.hsyscn.opedrgent.cloud.CloudCatalog
import top.hsyscn.opedrgent.model.ToolPart
import top.hsyscn.opedrgent.model.ToolStateType
import top.hsyscn.opedrgent.network.HttpClients
import top.hsyscn.opedrgent.network.ToolResult
import top.hsyscn.opedrgent.network.emptyResult
import top.hsyscn.opedrgent.settings.ApiConfig
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * siliconflow_image_generate 工具 — 硅基流动 (SiliconFlow) FLUX 文生图。
 *
 * ## 背景
 * 阶跃星辰 StepFun 文生图/改图模型 (step-2x-large、step-image-edit-2) 已于
 * 2026-10-10 停服且无继任型号，本工具为其替代方案之一。
 *
 * ## 接入
 * - 云厂商信息（baseUrl、鉴权头）一律通过冻结契约 [CloudCatalog] 按主机名解析：
 *   `CloudCatalog.findByHost("api.siliconflow.cn")`，不在本类硬编码 host/Bearer。
 * - 端点: POST {openAiBaseUrl}/images/generations（OpenAI 兼容图像接口）。
 *   注意：此处 openAiBaseUrl 取自 CloudProvider 的 CN 区域集群 openAiBaseUrl（含 /v1，
 *   如 https://api.siliconflow.cn/v1），并非 CloudProvider.baseUrl 主域名本身。
 *
 * ## 能力
 * - 纯文生图：通过 prompt 描述生成插图、概念图、头像、社交媒体配图等。
 * - 默认模型 black-forest-labs/FLUX.1-schnell（快速）；亦可使用
 *   black-forest-labs/FLUX.1-dev（高质量）等，具体以控制台 /models 列表为准。
 * - 注意：硅基流动 FLUX 为付费模型，生成图 URL 仅 1 小时有效，
 *   本工具在拿到 URL 后立即下载并回存到应用私有目录，结果中只返回本地路径。
 *
 * ## 模型驱动
 * 本类只提供工具名、中文描述与 JSON Schema，由模型 tool_calls 决定是否调用，
 * 不做任何关键词命中 / 意图判定。
 */
class SiliconFlowImageGenTool(
    private val context: Context,
) : ToolSet {

    companion object {
        private const val TAG = "SiliconFlowImageGen"
        private const val CLOUD_HOST = "api.siliconflow.cn"
        private const val ENDPOINT_PATH = "/images/generations"

        /** 默认 FLUX 文生图模型（快速档）。可用型号以控制台 /models 列表为准。 */
        const val DEFAULT_MODEL = "black-forest-labs/FLUX.1-schnell"
        /** 高质量 FLUX 模型（更慢、效果更好），供模型按需选用。 */
        const val MODEL_DEV = "black-forest-labs/FLUX.1-dev"

        private const val DEFAULT_IMAGE_SIZE = "1024x1024"
    }

    override fun getTools(): Map<String, ToolBinding> = mapOf(
        "siliconflow_image_generate" to ToolBinding(
            name = "siliconflow_image_generate",
            description = """使用硅基流动 (SiliconFlow) 平台的 FLUX 系列开源模型进行文生图：根据文字提示词生成插图、概念设计图、头像、海报草图、社交媒体配图等。
生成结果会在云端返回临时图片链接（仅 1 小时有效），本工具会立即将图片下载并保存到应用私有目录 cloud-images 下，最终返回本地保存路径。
可用模型（以控制台模型列表为准）：black-forest-labs/FLUX.1-schnell（默认，快速）、black-forest-labs/FLUX.1-dev（高质量，较慢）。
注意：该服务为付费 API，需要有效的 SiliconFlow API Key，并完成账户实名认证；余额不足会返回错误。""",
            parameters = JSONObject("""{
                "type": "object",
                "properties": {
                    "prompt": {
                        "type": "string",
                        "description": "图片生成提示词，详细描述画面内容、主体、风格、构图、光线等。中文或英文均可，英文对 FLUX 模型通常效果更好"
                    },
                    "image_size": {
                        "type": "string",
                        "description": "输出尺寸，格式 宽x高，默认 1024x1024。常见取值：方形 1024x1024；竖版 768x1024 / 864x1152 / 720x1280；横版 1024x768 / 1152x864 / 1280x720"
                    },
                    "batch_size": {
                        "type": "integer",
                        "description": "一次生成的图片数量，取值 1-4，默认 1"
                    },
                    "model": {
                        "type": "string",
                        "description": "模型标识，默认 black-forest-labs/FLUX.1-schnell（快速）；需要更高质量时可用 black-forest-labs/FLUX.1-dev"
                    },
                    "seed": {
                        "type": "integer",
                        "description": "可选，随机种子。指定后配合相同参数可复现相同结果；不传则随机"
                    },
                    "negative_prompt": {
                        "type": "string",
                        "description": "可选，负向提示词，描述希望画面中避免出现的内容，如 '模糊, 低质量, 多余手指'"
                    },
                    "num_inference_steps": {
                        "type": "integer",
                        "description": "可选，推理步数，越大质量通常越高但越慢（schnell 默认约 4，dev 约 20-50）"
                    }
                },
                "required": ["prompt"]
            }"""),
            invoker = { toolPart, config, _, _ -> execute(toolPart, config) },
        ),
    )

    /**
     * 执行文生图：解析参数 → 调 /images/generations → 立即下载每张图到本地 → 返回本地路径。
     */
    private suspend fun execute(toolPart: ToolPart, config: ApiConfig): ToolResult {
        val input = toolPart.state.input
        DebugLog.i(TAG, "执行文生图 — input=${input.toString().take(200)}")

        return try {
            val args = JSONObject(input)
            val prompt = args.optString("prompt", "")
            if (prompt.isBlank()) return emptyResult(toolPart, "生成提示词 prompt 不能为空")

            val imageSize = args.optString("image_size", DEFAULT_IMAGE_SIZE).ifBlank { DEFAULT_IMAGE_SIZE }
            val batchSize = args.optInt("batch_size", 1).coerceIn(1, 4)
            val model = args.optString("model", DEFAULT_MODEL).ifBlank { DEFAULT_MODEL }
            val seed = args.optInt("seed", -1)
            val negativePrompt = args.optString("negative_prompt", "").ifBlank { null }
            val steps = args.optInt("num_inference_steps", -1)

            // 云厂商接入信息一律走 CloudCatalog 冻结契约，不硬编码 host / Bearer。
            val provider = CloudCatalog.findByHost(CLOUD_HOST)
                ?: return emptyResult(toolPart,
                    "[配置错误] CloudCatalog 中未找到 $CLOUD_HOST 的接入配置，请确认云厂商配置已就绪后重试")

            // CloudProvider.baseUrl 是不含路径后缀的主域名（如 https://api.siliconflow.cn），
            // 图像接口必须落在 OpenAI 兼容 v1 基址上：优先取 CN 区域集群的 openAiBaseUrl，
            // 缺失时回退 baseUrl + "/v1"，否则会请求到 https://api.siliconflow.cn/images/generations 导致 404。
            val openAiBase = provider.regionalClusters.firstOrNull { it.id == "cn" }?.openAiBaseUrl
                ?: (provider.baseUrl.trimEnd('/') + "/v1")

            val result = callGenerationsApi(
                baseUrl = openAiBase,
                authHeader = provider.authHeader(config.apiKey),
                model = model,
                prompt = prompt,
                imageSize = imageSize,
                batchSize = batchSize,
                seed = if (seed > 0) seed else null,
                negativePrompt = negativePrompt,
                steps = if (steps > 0) steps else null,
            )

            if (!result.success) {
                return emptyResult(toolPart, "文生图失败: ${result.errorMessage}")
            }

            // 生成图 URL 仅 1 小时有效，必须立即逐张下载回本地。
            val downloaded = mutableListOf<String>()
            val downloadFailures = mutableListOf<String>()
            result.urls.forEachIndexed { index, url ->
                val localPath = downloadOne(url, index + 1)
                if (localPath != null) downloaded += localPath
                else downloadFailures += "图片 ${index + 1}: 下载失败 ($url)"
            }

            if (downloaded.isEmpty()) {
                return emptyResult(toolPart,
                    "图片已生成但全部下载失败: ${downloadFailures.joinToString("; ")}")
            }

            successResult(toolPart, buildString {
                appendLine("[图片生成完成]")
                appendLine("模型: $model | 尺寸: $imageSize | 请求数量: $batchSize")
                appendLine("Prompt: $prompt")
                if (seed > 0) appendLine("种子: $seed")
                appendLine("本地保存数量: ${downloaded.size} / ${result.urls.size}")
                downloaded.forEachIndexed { i, path ->
                    appendLine("图片 ${i + 1}: $path")
                }
                if (downloadFailures.isNotEmpty()) {
                    appendLine("部分图片下载失败:")
                    downloadFailures.forEach { appendLine("- $it") }
                }
            })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.e("$TAG 异常: ${e.message}", e)
            emptyResult(toolPart, "文生图异常: ${e.message}")
        }
    }

    /**
     * POST {baseUrl}/images/generations。
     *
     * @param authHeader 由 CloudProvider.authHeader 提供的 (头名, 头值)。
     */
    private suspend fun callGenerationsApi(
        baseUrl: String,
        authHeader: Pair<String, String>,
        model: String,
        prompt: String,
        imageSize: String,
        batchSize: Int,
        seed: Int?,
        negativePrompt: String?,
        steps: Int?,
    ): GenResult = withContext(Dispatchers.IO) {
        try {
            val jsonBody = JSONObject().apply {
                put("model", model)
                put("prompt", prompt)
                put("image_size", imageSize)
                put("batch_size", batchSize)
                if (negativePrompt != null) put("negative_prompt", negativePrompt)
                if (seed != null) put("seed", seed)
                if (steps != null) put("num_inference_steps", steps)
            }

            val request = Request.Builder()
                .url("${baseUrl.trimEnd('/')}$ENDPOINT_PATH")
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .header(authHeader.first, authHeader.second)
                .header("Content-Type", "application/json")
                .build()

            DebugLog.i(TAG, "调用文生图 API: model=$model, size=$imageSize, batch=$batchSize")

            HttpClients.default.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    DebugLog.e(TAG, "文生图失败 (${response.code}): ${body.take(300)}")
                    return@withContext GenResult(success = false, errorMessage = classifyHttpError(response.code, body))
                }
                val dataArr = JSONObject(body).optJSONArray("data")
                    ?: return@withContext GenResult(success = false, errorMessage = "响应中无 data 数组")
                val urls = mutableListOf<String>()
                for (i in 0 until dataArr.length()) {
                    dataArr.optJSONObject(i)?.optString("url")?.let { u ->
                        if (u.isNotBlank()) urls += u
                    }
                }
                if (urls.isEmpty()) {
                    return@withContext GenResult(success = false, errorMessage = "响应 data 中未包含图片 url")
                }
                GenResult(success = true, urls = urls)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.e("$TAG 网络异常: ${e.message}", e)
            GenResult(success = false, errorMessage = "网络请求失败: ${e.message ?: "未知错误"}")
        }
    }

    /**
     * 立即下载单张生成图（URL 仅 1 小时有效），保存到 getExternalFilesDir(null)/cloud-images。
     */
    private suspend fun downloadOne(url: String, index: Int): String? = withContext(Dispatchers.IO) {
        try {
            val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
            val dir = File(baseDir, "cloud-images").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(dir, "flux_${stamp}_${index}.png")

            val request = Request.Builder().url(url).get().build()
            HttpClients.download.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    DebugLog.e(TAG, "下载图片失败 (${response.code}): $url")
                    return@withContext null
                }
                response.body?.byteStream()?.use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                } ?: return@withContext null
            }
            DebugLog.i(TAG, "图片已保存: ${file.absolutePath}")
            file.absolutePath
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.e("$TAG 下载异常: ${e.message}", e)
            null
        }
    }

    /**
     * 按 HTTP 状态码与响应体区分错误类型：鉴权/未实名、余额不足、其他。
     */
    private fun classifyHttpError(code: Int, body: String): String {
        val msg = extractError(body)
        return when (code) {
            401 -> "[鉴权失败] API Key 无效或未配置: $msg"
            403 -> "[无权限] 账户可能未完成实名认证或未开通图像生成权限: $msg"
            402 -> "[余额不足] 硅基流动账户余额不足，请充值后重试: $msg"
            else -> {
                if (body.contains("balance", ignoreCase = true) ||
                    body.contains("余额", ignoreCase = true) ||
                    body.contains("insufficient", ignoreCase = true)
                ) {
                    "[余额不足] HTTP $code: $msg"
                } else {
                    "HTTP $code: $msg"
                }
            }
        }
    }

    private fun extractError(body: String): String = try {
        val json = JSONObject(body)
        json.optJSONObject("error")?.optString("message")
            ?: json.optString("message")
            ?: json.optString("code")
            ?: body.take(200)
    } catch (_: Exception) {
        body.take(200)
    }

    private fun successResult(tp: ToolPart, text: String): ToolResult = ToolResult(
        toolPart = tp.copy(
            state = tp.state.copy(
                status = ToolStateType.COMPLETED,
                output = text,
                endTime = System.currentTimeMillis(),
            ),
        ),
    )

    // ---- 数据类 ----

    data class GenResult(
        val success: Boolean,
        val urls: List<String> = emptyList(),
        val errorMessage: String? = null,
    )
}
