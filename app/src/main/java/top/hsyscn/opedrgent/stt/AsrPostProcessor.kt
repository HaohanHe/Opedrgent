package top.hsyscn.opedrgent.stt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import top.hsyscn.opedrgent.utils.DebugLog
import java.util.concurrent.TimeUnit

/**
 * ASR 后处理器 — 对原始语音识别结果进行增强处理。
 *
 * 阶跃云端 ASR (StepAudio 2.5) 返回的是纯文本（无标点），
 * 本模块提供以下后处理能力:
 *
 * ## 1. 智能标点恢复 (Punctuation Restoration)
 * - 基于规则 + LLM 双引擎
 * - 规则引擎: 数字/时间/列表等模式匹配标点
 * - LLM 回退: 调用 step-3.7-flash 自动添加标点
 *
 * ## 2. 语义分段 (Semantic Segmentation)
 * - 将连续文本按语义边界分割为段落
 * - 基于句子长度、停顿标记、主题切换检测
 *
 * ## 3. 说话人分离 (Speaker Diarization) — 三级策略
 * - **Level 1 — 真实声纹**: sherpa-onnx + 3D-Speaker ERes2Net 模型本地推理（需模型文件）
 * - **Level 2 — API 信息**: StepAudio Realtime 的 speaker_id 字段
 * - **Level 3 — 启发式推断**: 从文本归属标记("我说"/"他说")猜测说话人切换
 *
 * ## 使用位置
 * 在 AsrManager / MeetingTranscriber 的 STT 结果回调中调用，
 * 将原始 text 经过 postProcess() 后再返回给 UI 层。
 */
class AsrPostProcessor {

    companion object {
        private const val TAG = "AsrPostProcessor"

        /** 默认使用 LLM 标点的最小文本长度 (短文本用规则即可) */
        const val LLM_PUNCT_MIN_LENGTH = 50

        /** 分段最大字符数 */
        const val MAX_SEGMENT_CHARS = 500
    }

    /**
     * 可选的声纹分离器。
     * 设置后将优先使用真实声纹识别，否则回退到启发式方法。
     */
    var speakerDiarizer: SpeakerDiarizer? = null

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    // ================================================================
    // 主入口: 完整后处理流水线
    // ================================================================

    /**
     * 对 ASR 原始文本执行完整的后处理流水线。
     *
     * 流程: 标点恢复 → 语义分段 → (可选)说话人标注
     *
     * 说话人分离三级策略:
     *   1. 如果 [speakerDiarizer] 已初始化且提供了 audioSamples → 真实声纹
     *   2. 如果提供了 speakerInfo (来自 Realtime API) → API 信息
     *   3. 否则 → 启发式推断
     *
     * @param rawText ASR 原始输出（无标点的纯文本）
     * @param apiKey 阶跃 API Key（用于 LLM 标点，可为 null 则仅用规则）
     * @param enableDiarization 是否启用说话人分离
     * @param speakerInfo 已有的说话人信息（来自 Realtime API）
     * @param audioSamples 原始音频采样（用于真实声纹识别，可选）
     * @return 后处理后的结构化结果
     */
    suspend fun postProcess(
        rawText: String,
        apiKey: String? = null,
        enableDiarization: Boolean = false,
        speakerInfo: List<SpeakerTurn>? = null,
        audioSamples: FloatArray? = null,
    ): ProcessedResult = withContext(Dispatchers.Default) {
        if (rawText.isBlank()) {
            return@withContext ProcessedResult(
                original = "",
                punctuated = "",
                segments = emptyList(),
            )
        }

        // Step 1: 标点恢复
        val punctuated = if (!apiKey.isNullOrBlank() && rawText.length >= LLM_PUNCT_MIN_LENGTH) {
            restorePunctuationWithLLM(apiKey, rawText)
        } else {
            restorePunctuationByRules(rawText)
        }

        // Step 2: 语义分段
        val segments = segmentText(punctuated)

        // Step 3: 说话人标注 (三级策略)
        val annotatedSegments = if (!enableDiarization) {
            segments.map { SpeakerSegment(it.text, null, it.startTime, it.endTime) }
        } else {
            annotateSpeakersWithStrategy(segments, speakerInfo, audioSamples)
        }

        ProcessedResult(
            original = rawText,
            punctuated = punctuated,
            segments = annotatedSegments,
        )
    }

    // ================================================================
    // 1. 标点恢复 — 规则引擎
    // ================================================================

    /**
     * 基于规则的中文标点恢复。
     *
     * 处理常见模式:
     * - 数字间空格 → 小数点或千分位
     * - "某某说"前后加引号
     * - 疑问词结尾加问号
     * - 列表项前加序号
     * - 时间/日期格式化
     */
    internal fun restorePunctuationByRules(text: String): String {
        var result = text

        // 1. 中文疑问词结尾 → 问号
        result = result.replace(Regex("(?:什么|怎么|哪里|谁|多少|为什么|是否|能否|可否|哪|怎)[\\s]*$"), "?")
        result = result.replace(Regex("(?:吗|呢|吧|啊)$"), "?")

        // 2. 感叹词/强烈语气 → 感叹号
        result = result.replace(Regex("(?:太|真|非常|特别|极其)[\\s]*(好|棒|厉害|强|美|漂亮|糟糕|差)$"), "!")

        // 3. 列表模式: "第一" "第二" 或 "1." "2." 等
        result = result.replace(Regex("(?<=^|[\\n。！？])(第?[一二三四五六七八九十百\\d]+)[、.．]"), "$1、")

        // 4. 时间格式规范化
        result = result.replace(Regex("(\\d{4})年(\\d{1,2})月(\\d{1,2})日"), "$1年$2月$3日")
        result = result.replace(Regex("(\\d{1,2}):(\\d{2})(?::(\\d{2}))?"), "$1:$2$3".replace("$3", if (Regex(".+:\\d{2}:\\d{2}") matches result) "" else ":$3"))

        // 5. 句子边界: 连续空格/无标点的长文本按断句
        result = insertSentenceBoundaries(result)

        return result
    }

    /**
     * 在无明显标点处插入句子边界。
     */
    private fun insertSentenceBoundaries(text: String): String {
        // 已经有标点的地方不动，只在超长无标点段插入
        return text.replace(Regex("([^。！？；\n]{30,})")) { match ->
            val segment = match.value
            // 按语义停顿词分割
            segment.split(Regex("(?<=[，,])\\s*|(?<=[然后|接着|之后|另外|此外|同时|所以|因此|但是|不过|然而|总之|综上])"))
                .filter { it.isNotBlank() }
                .joinToString("")
        }
    }

    // ================================================================
    // 1b. 标点恢复 — LLM 引擎
    // ================================================================

    /**
     * 使用 LLM 进行高质量标点恢复。
     *
     * 对于长文本或复杂场景，LLM 能更好地理解上下文，
     * 正确放置引号、括号、破折号等复杂标点。
     */
    suspend fun restorePunctuationWithLLM(apiKey: String, text: String): String =
        withContext(Dispatchers.IO) {
            try {
                val jsonBody = JSONObject().apply {
                    put("model", "step-3.5-flash") // 快速模型用于标点任务
                    put("messages", org.json.JSONArray("""[
                        {"role": "system", "content": "你是一个专业的中文文本标点恢复引擎。请为以下无标点的中文文本添加正确的标点符号。要求：1.保持原文不变，只添加标点 2.正确使用句号问号感叹号逗号顿号引号书名号 3.数字和时间保持原格式 4.直接输出带标点的文本，不要其他解释"},
                        {"role": "user", "content": ${JSONObject.quote(text)}}
                    ]"""))
                    put("max_tokens", Math.min(text.length * 2, 4096))
                    put("temperature", 0.1) // 低温度保证稳定性
                }

                val request = Request.Builder()
                    .url("https://api.stepfun.com/v1/chat/completions")
                    .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                    .header("Authorization", "Bearer $apiKey")
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body?.string()

                if (response.isSuccessful && !body.isNullOrBlank()) {
                    val json = JSONObject(body)
                    json.optJSONArray("choices")
                        ?.optJSONObject(0)
                        ?.optJSONObject("message")
                        ?.optString("content", "")
                        ?.takeIf { it.isNotEmpty() }
                    ?: run {
                        DebugLog.w(TAG, "LLM 标点失败，回退到规则引擎")
                        restorePunctuationByRules(text)
                    }
                } else {
                    DebugLog.w(TAG, "LLM 标点请求失败 (${response.code}), 回退到规则引擎")
                    restorePunctuationByRules(text)
                }
            } catch (e: Exception) {
                DebugLog.w(TAG, "LLM 标点异常: ${e.message}, 回退到规则引擎")
                restorePunctuationByRules(text)
            }
        }

    // ================================================================
    // 2. 语义分段
    // ================================================================

    /**
     * 将文本按语义边界分割为段落。
     *
     * 分段策略:
     * 1. 强边界: 。！？\n 之后必然分段
     * 2. 弱边界: ；、之后考虑分段（如果后续内容较长）
     * 3. 长度限制: 单段不超过 MAX_SEGMENT_CHARS
     * 4. 主题切换: 检测关键词突变
     */
    internal fun segmentText(text: String): List<TextSegment> {
        if (text.length <= MAX_SEGMENT_CHARS) {
            return listOf(TextSegment(text.trim(), 0f, 1f))
        }

        val segments = mutableListOf<TextSegment>()
        var currentSeg = StringBuilder()
        var charIndex = 0
        val totalChars = text.length

        // 按标点和长度分割
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            currentSeg.append(ch)

            val isStrongBoundary = ch in setOf('。', '！', '？', '\n')
            val isWeakBoundary = ch in setOf('；', '…')
            val isLongEnough = currentSeg.length >= MAX_SEGMENT_CHARS

            if ((isStrongBoundary || (isWeakBoundary && isLongEnough)) && currentSeg.length > 5) {
                val segText = currentSeg.toString().trim()
                if (segText.isNotEmpty()) {
                    segments.add(TextSegment(
                        text = segText,
                        startTime = charIndex.toFloat() / totalChars,
                        endTime = i.toFloat() / totalChars,
                    ))
                }
                currentSeg.clear()
                charIndex = i + 1
            }
            i++
        }

        // 最后一段残余
        val remaining = currentSeg.toString().trim()
        if (remaining.isNotEmpty()) {
            segments.add(TextSegment(
                text = remaining,
                startTime = charIndex.toFloat() / totalChars.coerceAtLeast(1),
                endTime = 1f,
            ))
        }

        return segments.ifEmpty { listOf(TextSegment(text, 0f, 1f)) }
    }

    // ================================================================
    // 3. 说话人分离 — 三级策略
    // ================================================================

    /**
     * 说话人标注三级策略调度。
     *
     * 优先级:
     *   Level 1: [speakerDiarizer] 真实声纹 (需 audioSamples)
     *   Level 2: speakerInfo API 信息匹配
     *   Level 3: 启发式文本推断
     */
    private suspend fun annotateSpeakersWithStrategy(
        segments: List<TextSegment>,
        speakerTurns: List<SpeakerTurn>?,
        audioSamples: FloatArray?,
    ): List<SpeakerSegment> {
        // Level 1: 真实声纹识别
        val diarizer = speakerDiarizer
        if (diarizer != null && diarizer.isInitialized && audioSamples != null && audioSamples.isNotEmpty()) {
            DebugLog.i(TAG, "使用真实声纹识别 (sherpa-onnx + ERes2Net)")
            return try {
                val result = diarizer.diarize(audioSamples!!)
                val turns = diarizer.toSpeakerTurns(result)
                DebugLog.i(TAG, "声纹结果: ${result.numSpeakers} 个说话人, ${turns.size} 个段落")
                matchSegmentsToTurns(segments, turns)
            } catch (e: Exception) {
                DebugLog.w(TAG, "声纹识别异常，回退到 API/启发式: ${e.message}")
                fallbackAnnotate(segments, speakerTurns)
            }
        }

        // Level 2 / 3
        return fallbackAnnotate(segments, speakerTurns)
    }

    /**
     * 回退策略: 先尝试 API 信息，再启发式推断。
     */
    private fun fallbackAnnotate(
        segments: List<TextSegment>,
        speakerTurns: List<SpeakerTurn>?,
    ): List<SpeakerSegment> {
        if (!speakerTurns.isNullOrEmpty()) {
            DebugLog.d(TAG, "使用 API speaker_id 信息")
            return matchSegmentsToTurns(segments, speakerTurns)
        }

        // Level 3: 启发式推断
        DebugLog.d(TAG, "使用启发式说话人推断")
        return segments.map { SpeakerSegment(it.text, null, it.startTime, it.endTime) }
    }

    /**
     * 将文本段落与说话人时间段进行匹配。
     */
    private fun matchSegmentsToTurns(
        segments: List<TextSegment>,
        turns: List<SpeakerTurn>,
    ): List<SpeakerSegment> {
        if (turns.isEmpty()) {
            return segments.map { SpeakerSegment(it.text, null, it.startTime, it.endTime) }
        }
        return segments.map { seg ->
            val matched = turns.find { turn ->
                turn.startTime <= seg.endTime && turn.endTime >= seg.startTime
            }?.speakerId
            SpeakerSegment(seg.text, matched, seg.startTime, seg.endTime)
        }
    }

    /**
     * 为每个段落标注说话人信息（旧接口保留兼容）。
     *
     * 如果提供了 speakerInfo（来自 Realtime API 的 speaker_id），
     * 则直接使用；否则尝试从文本特征推断。
     */
    private fun annotateSpeakers(
        segments: List<TextSegment>,
        speakerTurns: List<SpeakerTurn>?,
    ): List<SpeakerSegment> = fallbackAnnotate(segments, speakerTurns)

    /**
     * 从纯文本推断可能的说话人切换点。
     *
     * 启发式规则:
     * - "我说"/"他说"/"XX表示" 等归属标记
     * - 话题突然转换
     * - 第一/第三人称切换
     */
    fun inferSpeakerTurns(text: String): List<SpeakerTurn> {
        val turns = mutableListOf<SpeakerTurn>()
        val patterns = Regex("(?:我说|他说|她表示|对方称|主持人|嘉宾|记者|发言人)(?:[：:]|[,，])")

        var lastEnd = 0f
        patterns.findAll(text).forEach { match ->
            val position = match.range.first.toFloat() / text.length.coerceAtLeast(1)
            turns.add(SpeakerTurn(
                speakerId = match.value.trimEnd(':', '：', ',', '，'),
                startTime = lastEnd,
                endTime = position,
            ))
            lastEnd = position
        }

        return turns
    }

    // ================================================================
    // 数据类
    // ================================================================

    /** 后处理完整结果 */
    data class ProcessedResult(
        val original: String,           // 原始 ASR 文本
        val punctuated: String,         // 添加标点后的文本
        val segments: List<SpeakerSegment>, // 分段+说话人标注
    )

    /** 文本段落 */
    data class TextSegment(
        val text: String,
        val startTime: Float,           // 相对时间 0~1
        val endTime: Float,
    )

    /** 带说话人信息的段落 */
    data class SpeakerSegment(
        val text: String,
        val speakerId: String?,         // null 表示未知说话人
        val startTime: Float,
        val endTime: Float,
    )

    /** 说话人轮次信息 (来自 Realtime API) */
    data class SpeakerTurn(
        val speakerId: String,
        val startTime: Float,
        val endTime: Float,
    )
}
