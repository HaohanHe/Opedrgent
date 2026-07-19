package top.hsyscn.opedrgent.tools

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.hsyscn.opedrgent.model.MultimodalContent
import top.hsyscn.opedrgent.model.MultimodalMessage
import top.hsyscn.opedrgent.model.Role
import top.hsyscn.opedrgent.model.ToolPart
import top.hsyscn.opedrgent.network.LlmClient
import top.hsyscn.opedrgent.network.ToolResult
import top.hsyscn.opedrgent.network.emptyResult
import top.hsyscn.opedrgent.settings.ApiConfig
import top.hsyscn.opedrgent.settings.ApiSettings
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * step_video_summary 工具 — 视频摘要生成。
 *
 * 利用 step-1o-turbo-vision 模型的多模态视频理解能力，
 * 从视频中提取关键帧并生成结构化摘要。
 *
 * ## 两种模式
 *
 * 1. **远程视频 URL**: 直接将 URL 传给模型（模型原生支持 video_url 类型），
 *    模型内部自动抽帧分析，最高效。
 *
 * 2. **本地视频文件**: 使用 [MediaMetadataRetriever] 提取关键帧（均匀采样），
 *    将帧图片作为多张 image 发送给模型分析。
 *
 * ## 输出格式
 *
 * 生成结构化 Markdown 摘要，包含：
 * - 视频主题概述
 * - 关键内容分段描述
 * - 重要信息提取（人名/地名/数据/观点）
 * - 总结与要点
 */
class StepVideoSummaryTool(
    private val context: Context,
    private val llm: LlmClient,
    private val apiSettings: ApiSettings,
) : ToolSet {

    companion object {
        private const val TAG = "StepVideoSummary"
        const val MODEL_VISION = "step-1o-turbo-vision"
        const val DEFAULT_MAX_FRAMES = 8
        const val MAX_FRAME_LONG_EDGE = 1280
    }

    override fun getTools(): Map<String, ToolBinding> = mapOf(
        "step_video_summary" to ToolBinding(
            name = "step_video_summary",
            description = """视频摘要工具 — 上传视频 URL 或本地文件，生成结构化摘要。

支持两种输入方式:
1. video_url: 远程视频 URL（模型原生支持，自动抽帧分析，最高效）
2. video_path: 本地视频文件路径（自动提取关键帧发送给模型）

输出包含: 视频主题概述、关键内容分段描述、重要信息提取、总结要点。""",

            parameters = JSONObject("""{
                "type": "object",
                "properties": {
                    "video_url": {
                        "type": "string",
                        "description": "远程视频 URL（http/https）。与 video_path 二选一。"
                    },
                    "video_path": {
                        "type": "string",
                        "description": "本地视频文件路径（如 /sdcard/video.mp4）。与 video_url 二选一。"
                    },
                    "question": {
                        "type": "string",
                        "description": "针对视频的具体问题（可选）。不填则生成通用摘要。"
                    },
                    "max_frames": {
                        "type": "integer",
                        "description": "本地视频提取的最大帧数（默认 8，范围 4-16）。仅对 video_path 有效。"
                    }
                }
            }"""),
            invoker = { toolPart, config, _, _ -> execute(toolPart, config) },
        ),
    )

    private suspend fun execute(toolPart: ToolPart, config: ApiConfig): ToolResult {
        val input = toolPart.state.input
        DebugLog.i(TAG, "执行视频摘要 — input=${input.toString().take(200)}")

        return try {
            val args = JSONObject(input)
            val videoUrl = args.optString("video_url", "").trim()
            val videoPath = args.optString("video_path", "").trim()
            val question = args.optString("question", "").trim()
            val maxFrames = args.optInt("max_frames", DEFAULT_MAX_FRAMES).coerceIn(4, 16)

            val prompt = question.ifBlank {
                "请对这个视频进行详细的内容分析和摘要。包括：\n" +
                "1. 视频主题概述\n" +
                "2. 关键内容分段描述（按时间顺序）\n" +
                "3. 重要信息提取（人名、地名、数据、观点等）\n" +
                "4. 总结与核心要点"
            }

            val result = when {
                videoUrl.isNotBlank() -> summarizeRemoteVideo(config, videoUrl, prompt)
                videoPath.isNotBlank() -> summarizeLocalVideo(config, videoPath, prompt, maxFrames)
                else -> return emptyResult(toolPart, "需要提供 video_url 或 video_path 参数")
            }

            if (result.success) {
                successResult(toolPart, buildString {
                    appendLine("[视频摘要完成]")
                    if (videoUrl.isNotBlank()) appendLine("来源: $videoUrl")
                    else appendLine("来源: $videoPath")
                    appendLine()
                    append(result.text)
                })
            } else {
                emptyResult(toolPart, "视频摘要失败: ${result.errorMessage}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.e(TAG, "视频摘要异常: ${e.message}", e)
            emptyResult(toolPart, "视频摘要异常: ${e.message}")
        }
    }

    /**
     * 远程视频 URL — 直接传给模型（模型原生支持 video_url 多模态输入）。
     */
    private suspend fun summarizeRemoteVideo(
        config: ApiConfig,
        videoUrl: String,
        prompt: String,
    ): SummaryResult = withContext(Dispatchers.IO) {
        try {
            DebugLog.i(TAG, "[remote] 视频URL=$videoUrl, 模型=${config.model}")

            val messages = listOf(
                MultimodalMessage(
                    role = Role.USER,
                    content = listOf(
                        MultimodalContent.VideoUrl(url = videoUrl, fps = 2),
                        MultimodalContent.Text(text = prompt),
                    ),
                ),
            )

            val response = llm.multimodalChat(
                config = config.copy(model = MODEL_VISION),
                system = "你是一个专业的视频内容分析师。请仔细观看视频并根据用户要求生成详细的结构化分析。使用 Markdown 格式输出。",
                messages = messages,
            )

            SummaryResult(success = true, text = response)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.e(TAG, "[remote] 失败: ${e.message}", e)
            SummaryResult(success = false, errorMessage = e.message)
        }
    }

    /**
     * 本地视频文件 — 提取关键帧后作为多张图片发送给模型。
     */
    private suspend fun summarizeLocalVideo(
        config: ApiConfig,
        videoPath: String,
        prompt: String,
        maxFrames: Int,
    ): SummaryResult = withContext(Dispatchers.IO) {
        val file = File(videoPath)
        if (!file.exists()) {
            return@withContext SummaryResult(success = false, errorMessage = "视频文件不存在: $videoPath")
        }

        // content:// URI 也支持
        val isContentUri = videoPath.startsWith("content://")

        val frames = if (isContentUri) {
            extractFramesFromUri(Uri.parse(videoPath), maxFrames)
        } else {
            extractFramesFromFile(videoPath, maxFrames)
        }

        if (frames.isEmpty()) {
            return@withContext SummaryResult(success = false, errorMessage = "无法从视频中提取关键帧（文件可能损坏或格式不支持）")
        }

        DebugLog.i(TAG, "[local] 提取了 ${frames.size} 帧, 发送给视觉模型")

        try {
            // 构建多模态消息：多帧图片 + 文本提示
            val content = mutableListOf<MultimodalContent>()
            frames.forEachIndexed { idx, frameBase64 ->
                content.add(MultimodalContent.ImageBase64(base64 = frameBase64, mimeType = "image/jpeg"))
            }
            content.add(MultimodalContent.Text(
                text = "以上是从视频中均匀提取的 ${frames.size} 个关键帧（按时间顺序排列）。\n\n$prompt"
            ))

            val messages = listOf(
                MultimodalMessage(role = Role.USER, content = content),
            )

            val response = llm.multimodalChat(
                config = config.copy(model = MODEL_VISION),
                system = "你是一个专业的视频内容分析师。用户会给你一个视频的多个关键帧（按时间顺序排列），请根据这些帧的内容进行详细的视频分析和摘要。使用 Markdown 格式输出。",
                messages = messages,
            )

            SummaryResult(success = true, text = response)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.e(TAG, "[local] 模型调用失败: ${e.message}", e)
            SummaryResult(success = false, errorMessage = e.message)
        }
    }

    /**
     * 从本地视频文件提取关键帧（均匀采样）。
     *
     * 使用 [MediaMetadataRetriever.getFrameAtTime] 按时间均匀采样，
     * 返回 Base64 编码的 JPEG 图片列表。
     */
    private fun extractFramesFromFile(filePath: String, maxFrames: Int): List<String> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(filePath)
            extractFrames(retriever, maxFrames)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.e(TAG, "extractFramesFromFile 失败: ${e.message}", e)
            emptyList()
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    /**
     * 从 content:// URI 提取关键帧。
     */
    private fun extractFramesFromUri(uri: Uri, maxFrames: Int): List<String> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            extractFrames(retriever, maxFrames)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.e(TAG, "extractFramesFromUri 失败: ${e.message}", e)
            emptyList()
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    /**
     * 从 [MediaMetadataRetriever] 中均匀提取帧并转为 Base64。
     */
    private fun extractFrames(retriever: MediaMetadataRetriever, maxFrames: Int): List<String> {
        val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
        val durationMs = durationStr?.toLongOrNull() ?: 0L
        if (durationMs <= 0) return emptyList()

        // 采样间隔：跳过首尾各 1 秒，中间均匀采样
        val safeStartMs = 1000L
        val safeEndMs = (durationMs - 1000L).coerceAtLeast(safeStartMs + 1)
        val intervalMs = (safeEndMs - safeStartMs) / maxFrames

        val frames = mutableListOf<String>()
        for (i in 0 until maxFrames) {
            val timeUs = (safeStartMs + i * intervalMs) * 1000 // 微秒
            val bitmap = retriever.getFrameAtTime(
                timeUs,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
            ) ?: continue

            // 缩放：限制长边不超过 MAX_FRAME_LONG_EDGE
            val scaled = scaleBitmap(bitmap)
            val base64 = bitmapToBase64(scaled)
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()

            if (base64 != null) {
                frames.add(base64)
            }
        }

        DebugLog.i(TAG, "提取帧: 时长=${durationMs}ms, 采样间隔=${intervalMs}ms, 实际帧数=${frames.size}")
        return frames
    }

    /**
     * 缩放 Bitmap，长边不超过 [MAX_FRAME_LONG_EDGE]。
     */
    private fun scaleBitmap(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val maxDim = maxOf(w, h)
        if (maxDim <= MAX_FRAME_LONG_EDGE) return bitmap

        val scale = MAX_FRAME_LONG_EDGE.toFloat() / maxDim
        val newW = (w * scale).toInt().coerceAtLeast(1)
        val newH = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
    }

    /**
     * Bitmap → Base64 JPEG 字符串。
     */
    private fun bitmapToBase64(bitmap: Bitmap): String? {
        return try {
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos)
            Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.e(TAG, "bitmapToBase64 失败: ${e.message}")
            null
        }
    }

    private fun successResult(tp: ToolPart, text: String): ToolResult = ToolResult(
        toolPart = tp.copy(state = tp.state.copy(status = top.hsyscn.opedrgent.model.ToolStateType.COMPLETED, output = text, endTime = System.currentTimeMillis())),
    )

    private data class SummaryResult(
        val success: Boolean,
        val text: String = "",
        val errorMessage: String? = null,
    )
}
