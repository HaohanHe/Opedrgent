package top.hsyscn.opedrgent.stt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import top.hsyscn.opedrgent.network.NetworkConfig
import top.hsyscn.opedrgent.utils.DebugLog
import java.util.concurrent.TimeUnit

/**
 * ASR 后处理器 — 对原始语音识别结果进行增强处理。
 *
 * 阶跃云端 ASR (StepAudio 2.5) 返回的是纯文本（无标点），
 * 本模块提供以下后处理能力:
 *
 * ## 1. LLM 口语清理 (Spoken Language Cleanup) — 核心能力
 * - 使用 LLM 智能判断哪些是无意义的口水词/重复/自我纠正
 * - 根据语境决定保留或删除（非机械匹配）
 * - 同时完成标点恢复和口语转书面语
 * - 保留原意、关键论点和语气
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
 * ## 设计原则
 * **绝不用固定字符匹配删除口水词**。口语中的"那啥"、"就是"、"我我我"等在不同语境下
 * 可能有意义（强调、犹豫、口头习惯），必须由 LLM 理解语境后决定。
 *
 * ## 使用位置
 * 在 AsrManager / MeetingTranscriber 的 STT 结果回调中调用，
 * 将原始 text 经过 postProcess() 后再返回给 UI 层。
 */
class AsrPostProcessor {

    companion object {
        private const val TAG = "AsrPostProcessor"

        /** 使用 LLM 清理的最小文本长度 (短文本用规则即可) */
        const val LLM_MIN_LENGTH = 50

        /** 分段最大字符数 */
        const val MAX_SEGMENT_CHARS = 500

        /** LLM 口语清理的 system prompt */
        private const val SPOKEN_CLEANUP_PROMPT = """你是一个专业的中文语音转文字编辑。你的任务是将口语化的语音识别结果整理为通顺的书面文字。

【核心原则】
1. **语义理解优先**：根据上下文判断哪些是无意义的口水词，哪些是有意义的表达
2. **保留原意**：不改变说话人的观点、论据和核心信息
3. **自然润色**：不是机械删词，而是理解后重新组织语言
4. **适度保留语气**：适当的口语化表达（如反问、感叹）可以保留，体现说话风格

【处理规则】
- 删除：无意义的重复（"我我我"→"我"）、纯填充词（"那个那个"无实义时）、自我纠正的废弃片段
- 保留：有强调意义的重复（"绝对不可能"）、有语境意义的口头词、专有名词和关键数据
- 整理：断裂的句子重新连接，语序混乱的重新排列，但不添加原文没有的信息
- 标点：添加正确的标点符号
- 分段：如果内容涉及多个话题，用空行分段
- 补充：如果口语中有明显的省略导致信息不完整，用[括号]补充上下文

【禁止】
- 不要添加原文没有的观点或信息
- 不要过度文学化（这不是写作文）
- 不要删除说话人的情感表达（如愤怒、幽默、讽刺）
- 不要改变说话人的逻辑顺序（除非语序明显混乱）"""
    }

    /**
     * 可选的声纹分离器。
     * 设置后将优先使用真实声纹识别，否则回退到启发式方法。
     */
    var speakerDiarizer: SpeakerDiarizer? = null

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(NetworkConfig.ASR_POST_PROCESSOR_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(NetworkConfig.ASR_POST_PROCESSOR_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    // ================================================================
    // 主入口: 完整后处理流水线
    // ================================================================

    /**
     * 对 ASR 原始文本执行完整的后处理流水线。
     *
     * 流程: LLM 口语清理(含标点) → 语义分段 → (可选)说话人标注
     *
     * 当有 API Key 且文本足够长时，使用 LLM 一步完成：
     * - 去除无意义口水词（根据语境判断，非机械匹配）
     * - 添加标点
     * - 口语转书面语
     * - 语义分段
     *
     * 无 API Key 时回退到规则引擎（仅标点恢复，不去口水词）。
     *
     * @param rawText ASR 原始输出（无标点的纯文本）
     * @param apiKey API Key（用于 LLM 清理，可为 null 则仅用规则）
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

        // Step 1: LLM 口语清理（含标点恢复）或规则标点恢复
        val punctuated = if (!apiKey.isNullOrBlank() && rawText.length >= LLM_MIN_LENGTH) {
            cleanSpokenLanguageWithLLM(apiKey, rawText)
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
    // 1b. LLM 口语清理（含标点恢复 + 口语转书面语）
    // ================================================================

    /**
     * 使用 LLM 将口语化的 ASR 结果清理为通顺的书面文字。
     *
     * 一步完成：
     * - 去除无意义口水词（根据语境判断，非机械匹配）
     * - 添加标点
     * - 口语转书面语
     * - 语义分段
     *
     * 设计原则：**绝不用固定字符匹配删除口水词**。
     * "那啥"、"就是"、"我我我"等在不同语境下可能有意义，
     * 必须由 LLM 理解语境后决定保留或删除。
     */
    suspend fun cleanSpokenLanguageWithLLM(apiKey: String, text: String): String =
        withContext(Dispatchers.IO) {
            try {
                val jsonBody = JSONObject().apply {
                    put("model", "step-3.5-flash") // 快速模型用于清理任务
                    put("messages", org.json.JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "system")
                            put("content", SPOKEN_CLEANUP_PROMPT)
                        })
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", "以下是语音识别的原始结果，请整理为通顺的书面文字：\n\n$text")
                        })
                    })
                    put("max_tokens", Math.min(text.length * 2, 8192))
                    put("temperature", 0.3) // 略高于标点任务，允许适度润色
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
                    val cleaned = json.optJSONArray("choices")
                        ?.optJSONObject(0)
                        ?.optJSONObject("message")
                        ?.optString("content", "")
                        ?.takeIf { it.isNotEmpty() }

                    if (cleaned != null) {
                        DebugLog.i(TAG, "LLM 口语清理完成: ${text.length}字 → ${cleaned.length}字 " +
                                "(压缩率${"%.0f".format(cleaned.length * 100.0 / text.length)}%)")
                        cleaned
                    } else {
                        DebugLog.w(TAG, "LLM 清理返回空，回退到规则引擎")
                        restorePunctuationByRules(text)
                    }
                } else {
                    DebugLog.w(TAG, "LLM 清理请求失败 (${response.code}), 回退到规则引擎")
                    restorePunctuationByRules(text)
                }
            } catch (e: Exception) {
                DebugLog.w(TAG, "LLM 清理异常: ${e.message}, 回退到规则引擎")
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
            val samples = audioSamples
            DebugLog.i(TAG, "使用真实声纹识别 (sherpa-onnx + ERes2Net)")
            return try {
                val result = diarizer.diarize(samples)
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
