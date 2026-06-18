package top.hsyscn.opedrgent.stt

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.File
import java.util.UUID
import kotlin.math.sqrt

/**
 * 声纹管理器：负责说话人注册、列表查询、删除和匹配。
 *
 * 支持两种嵌入向量来源:
 * 1. Sherpa-ONNX 声纹嵌入 (192 维) — 基于 3D-Speaker ERes2Net 模型的真实声纹特征
 * 2. 统计特征 (16 维) — 基于 RMS/过零率的简易声纹指纹 (fallback)
 *
 * 匹配策略:
 * - 只在相同维度的嵌入之间进行比较
 * - Sherpa-ONNX 嵌入使用余弦相似度，阈值 0.5
 * - 统计特征使用余弦相似度，阈值 0.75
 *
 * 工作流程:
 * 1. enrollSpeaker() / enrollWithSherpaEmbedding() — 注册
 * 2. matchSpeaker() — 匹配时自动选择正确的维度进行比较
 */
class VoiceprintManager(private val context: Context) {

    /**
     * @param embeddingType 嵌入类型: "sherpa_onnx" 或 "statistical"
     * @param embeddingDim 嵌入向量的实际维度（用于兼容不同来源）
     */
    data class SpeakerProfile(
        val id: String,
        val name: String,
        val samplePaths: List<String>,
        /** 注册时从样本中提取的特征向量 */
        val embedding: FloatArray = FloatArray(STATISTICAL_EMBEDDING_DIM),
        /** 嵌入类型标识 */
        val embeddingType: String = EMBEDDING_TYPE_STATISTICAL,
        /** 嵌入向量实际维度 */
        val embeddingDim: Int = STATISTICAL_EMBEDDING_DIM,
    )

    companion object {
        private const val PREFS_NAME = "voiceprint_prefs"
        private const val KEY_SPEAKERS = "speakers"
        private const val TAG = "VoiceprintManager"

        /** 统计特征维度 (RMS + 过零率) */
        internal const val STATISTICAL_EMBEDDING_DIM = 16
        /** 保留旧名以兼容 MeetingTranscriber 引用 */
        internal const val EMBEDDING_DIM = STATISTICAL_EMBEDDING_DIM

        /** 嵌入类型常量 */
        const val EMBEDDING_TYPE_SHERPA_ONNX = "sherpa_onnx"
        const val EMBEDDING_TYPE_STATISTICAL = "statistical"

        /** 统计特征余弦相似度阈值 */
        private const val STATISTICAL_THRESHOLD = 0.75f
        /** Sherpa-ONNX 嵌入余弦相似度阈值 (192 维已归一化，阈值可更低) */
        private const val SHERPA_THRESHOLD = 0.50f
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ================================================================
    // 注册 API
    // ================================================================

    /**
     * 使用统计特征注册说话人 (fallback 模式)。
     *
     * @param name 说话人名称
     * @param samplePaths 录音样本文件路径列表（WAV/PCM）
     * @return 注册后的 SpeakerProfile
     */
    fun enrollSpeaker(name: String, samplePaths: List<String>): SpeakerProfile {
        val allFeatures = samplePaths.mapNotNull { path ->
            extractAudioFeatures(File(path))
        }

        val avgEmbedding = if (allFeatures.isNotEmpty()) {
            averageEmbeddings(allFeatures, STATISTICAL_EMBEDDING_DIM)
        } else {
            hashEmbedding(name)
        }

        val profile = SpeakerProfile(
            id = UUID.randomUUID().toString(),
            name = name,
            samplePaths = samplePaths,
            embedding = avgEmbedding,
            embeddingType = EMBEDDING_TYPE_STATISTICAL,
            embeddingDim = STATISTICAL_EMBEDDING_DIM,
        )
        val speakers = listSpeakers().toMutableList()
        speakers.add(profile)
        saveSpeakers(speakers)
        DebugLog.i(TAG, "注册说话人(统计特征): ${profile.name}, 样本数: ${samplePaths.size}, 有效特征数: ${allFeatures.size}")
        return profile
    }

    /**
     * 使用 Sherpa-ONNX 声纹嵌入注册说话人 (高质量模式)。
     *
     * @param name 说话人名称
     * @param samplePaths 录音样本文件路径列表
     * @param embedding Sherpa-ONNX 提取的 192 维声纹嵌入向量
     * @return 注册后的 SpeakerProfile
     */
    fun enrollWithSherpaEmbedding(
        name: String,
        samplePaths: List<String>,
        embedding: FloatArray,
    ): SpeakerProfile {
        val profile = SpeakerProfile(
            id = UUID.randomUUID().toString(),
            name = name,
            samplePaths = samplePaths,
            embedding = embedding,
            embeddingType = EMBEDDING_TYPE_SHERPA_ONNX,
            embeddingDim = embedding.size,
        )
        val speakers = listSpeakers().toMutableList()
        speakers.add(profile)
        saveSpeakers(speakers)
        DebugLog.i(TAG, "注册说话人(Sherpa-ONNX): ${profile.name}, 样本数: ${samplePaths.size}, 嵌入维度: ${embedding.size}")
        return profile
    }

    // ================================================================
    // 查询 API
    // ================================================================

    fun listSpeakers(): List<SpeakerProfile> {
        val jsonStr = prefs.getString(KEY_SPEAKERS, "[]") ?: "[]"
        return parseSpeakers(jsonStr)
    }

    fun deleteSpeaker(id: String) {
        val speakers = listSpeakers().filter { it.id != id }
        saveSpeakers(speakers)
        DebugLog.i(TAG, "删除说话人: $id")
    }

    fun getSpeakerById(id: String): SpeakerProfile? {
        return listSpeakers().find { it.id == id }
    }

    /**
     * 获取声纹样本存储目录。
     */
    fun getVoiceprintDir(): File {
        val dir = File(context.cacheDir, "voiceprints")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    // ================================================================
    // 说话人匹配
    // ================================================================

    /**
     * 根据 Sherpa-ONNX 嵌入匹配说话人。
     *
     * @param sherpaEmbedding 192 维 Sherpa-ONNX 声纹嵌入
     * @return 匹配到的说话人 ID，未匹配到则返回 null
     */
    fun matchSpeakerByEmbedding(sherpaEmbedding: FloatArray): String? {
        val speakers = listSpeakers()
        if (speakers.isEmpty()) return null

        // 只与同类型的 Sherpa-ONNX 嵌入比较
        val sherpaSpeakers = speakers.filter {
            it.embeddingType == EMBEDDING_TYPE_SHERPA_ONNX && it.embeddingDim == sherpaEmbedding.size
        }
        if (sherpaSpeakers.isEmpty()) return null

        var bestMatch: Pair<String, Float>? = null

        for (speaker in sherpaSpeakers) {
            if (speaker.embedding.all { it == 0f }) continue
            if (speaker.embedding.size != sherpaEmbedding.size) continue

            val similarity = cosineSimilarity(sherpaEmbedding, speaker.embedding)
            if (similarity > SHERPA_THRESHOLD) {
                if (bestMatch == null || similarity > bestMatch.second) {
                    bestMatch = speaker.id to similarity
                }
            }
        }

        logMatchResult(bestMatch, sherpaSpeakers.size, SHERPA_THRESHOLD, "Sherpa-ONNX")
        return bestMatch?.first
    }

    /**
     * 根据音频特征匹配说话人 (兼容旧接口)。
     *
     * 自动检测输入维度，只与相同维度的已注册特征进行比较。
     *
     * @param audioFeatures 音频特征向量
     * @return 匹配到的说话人 ID，未匹配到则返回 null
     */
    fun matchSpeaker(audioFeatures: FloatArray): String? {
        val speakers = listSpeakers()
        if (speakers.isEmpty()) return null

        val inputDim = audioFeatures.size

        // 按维度分组匹配
        val matchingCandidates = speakers.filter {
            it.embeddingDim == inputDim && it.embedding.size == inputDim
        }
        if (matchingCandidates.isEmpty()) {
            DebugLog.d(TAG, "matchSpeaker: 无同维度候选 (inputDim=$inputDim, 已注册维度=${speakers.map { it.embeddingDim }.distinct()})")
            return null
        }

        val threshold = when (inputDim) {
            SpeakerEmbeddingExtractor.EMBEDDING_DIM -> SHERPA_THRESHOLD
            else -> STATISTICAL_THRESHOLD
        }
        val typeLabel = when (inputDim) {
            SpeakerEmbeddingExtractor.EMBEDDING_DIM -> "Sherpa-ONNX"
            else -> "统计特征"
        }

        var bestMatch: Pair<String, Float>? = null
        for (speaker in matchingCandidates) {
            if (speaker.embedding.all { it == 0f }) continue
            val similarity = cosineSimilarity(audioFeatures, speaker.embedding)
            if (similarity > threshold) {
                if (bestMatch == null || similarity > bestMatch.second) {
                    bestMatch = speaker.id to similarity
                }
            }
        }

        logMatchResult(bestMatch, matchingCandidates.size, threshold, typeLabel)
        return bestMatch?.first
    }

    // ================================================================
    // 统计特征提取 (fallback)
    // ================================================================

    /**
     * 从音频文件中提取 16 维统计特征向量。
     */
    internal fun extractAudioFeatures(audioFile: File): FloatArray? {
        return try {
            val bytes = audioFile.readBytes()
            if (bytes.size < 44) return null

            val dataOffset = parseWavDataOffset(bytes)
                ?: run { DebugLog.w(TAG, "无法解析 WAV 头: ${audioFile.name}"); return null }

            val pcmData = bytes.sliceArray(dataOffset until bytes.size)
            val samples = ShortArray(pcmData.size / 2)
            for (i in samples.indices) {
                samples[i] = ((pcmData[i * 2].toInt() and 0xFF) or (pcmData[i * 2 + 1].toInt() shl 8)).toShort()
            }

            if (samples.isEmpty()) return null
            computeStatisticalFeatures(samples)
        } catch (e: Exception) {
            DebugLog.w(TAG, "特征提取失败 (${audioFile.name}): ${e.message}")
            null
        }
    }

    internal fun computeStatisticalFeatures(samples: ShortArray): FloatArray {
        val n = samples.size.coerceAtLeast(1)

        val frameSize = 400 // 25ms @ 16kHz
        val hopSize = 160  // 10ms @ 16kHz
        val rmsValues = mutableListOf<Float>()
        val zcrValues = mutableListOf<Float>()

        var i = 0
        while (i + frameSize <= samples.size) {
            val frame = samples.sliceArray(i until i + frameSize)
            var sumSq = 0.0
            for (s in frame) sumSq += s.toDouble() * s.toDouble()
            rmsValues.add(sqrt(sumSq / frameSize).toFloat())

            var zc = 0
            for (j in 1 until frame.size) {
                if ((frame[j - 1] < 0 && frame[j] >= 0) || (frame[j - 1] >= 0 && frame[j] < 0)) zc++
            }
            zcrValues.add(zc.toFloat() / frameSize)

            i += hopSize
        }

        if (rmsValues.isEmpty()) {
            var sumSq = 0.0
            for (s in samples) sumSq += s.toDouble() * s.toDouble()
            rmsValues.add(sqrt(sumSq / n).toFloat())
            zcrValues.add(0f)
        }

        val stats = extractStats(rmsValues.toFloatArray()) +
                     extractStats(zcrValues.toFloatArray())

        return if (stats.size >= STATISTICAL_EMBEDDING_DIM) {
            stats.sliceArray(0 until STATISTICAL_EMBEDDING_DIM)
        } else {
            stats + FloatArray(STATISTICAL_EMBEDDING_DIM - stats.size)
        }
    }

    private fun extractStats(values: FloatArray): FloatArray {
        if (values.isEmpty()) return floatArrayOf(0f, 0f, 0f, 0f)
        val mean = values.average().toFloat()
        val variance = values.map { (it - mean) * (it - mean) }.average().toFloat()
        val std = sqrt(variance.coerceAtLeast(0f).toDouble()).toFloat()
        val max = values.maxOrNull() ?: 0f
        val min = values.minOrNull() ?: 0f
        return floatArrayOf(mean, std, max, min)
    }

    // ================================================================
    // 向量运算
    // ================================================================

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dotProduct = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dotProduct += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denominator = sqrt(normA.toDouble()) * sqrt(normB.toDouble())
        return if (denominator > 0) (dotProduct / denominator).toFloat() else 0f
    }

    private fun averageEmbeddings(embeddings: List<FloatArray>, dim: Int): FloatArray {
        val avg = FloatArray(dim)
        val count = embeddings.size.coerceAtLeast(1)
        for (emb in embeddings) {
            for (i in 0 until minOf(emb.size, dim)) {
                avg[i] += emb[i]
            }
        }
        for (i in avg.indices) avg[i] /= count
        return avg
    }

    private fun hashEmbedding(name: String): FloatArray {
        val dim = STATISTICAL_EMBEDDING_DIM
        val embed = FloatArray(dim)
        var hash = name.hashCode().toLong()
        for (i in embed.indices) {
            hash = hash * 31 + i
            embed[i] = ((hash % 10000) / 10000.0f).coerceIn(-1f, 1f)
        }
        var norm = 0f
        for (v in embed) norm += v * v
        norm = sqrt(norm.toDouble()).toFloat().coerceAtLeast(1f)
        for (i in embed.indices) embed[i] /= norm
        return embed
    }

    // ================================================================
    // 匹配结果日志
    // ================================================================

    private fun logMatchResult(
        bestMatch: Pair<String, Float>?,
        candidateCount: Int,
        threshold: Float,
        typeLabel: String,
    ) {
        if (bestMatch != null) {
            DebugLog.d(TAG, "声纹匹配成功($typeLabel): sim=${String.format("%.3f", bestMatch.second)}, id=${bestMatch.first}, 候选=$candidateCount")
        } else {
            DebugLog.d(TAG, "声纹未匹配($typeLabel): 候选=$candidateCount, 阈值=$threshold")
        }
    }

    // ================================================================
    // 持久化
    // ================================================================

    private fun saveSpeakers(speakers: List<SpeakerProfile>) {
        val jsonArray = JSONArray()
        speakers.forEach { speaker ->
            val obj = JSONObject()
            obj.put("id", speaker.id)
            obj.put("name", speaker.name)
            val paths = JSONArray()
            speaker.samplePaths.forEach { paths.put(it) }
            obj.put("samplePaths", paths)
            // 序列化嵌入向量
            val embedArr = JSONArray()
            speaker.embedding.forEach { embedArr.put(it) }
            obj.put("embedding", embedArr)
            // 新字段: 嵌入类型和维度
            obj.put("embeddingType", speaker.embeddingType)
            obj.put("embeddingDim", speaker.embeddingDim)
            jsonArray.put(obj)
        }
        prefs.edit().putString(KEY_SPEAKERS, jsonArray.toString()).apply()
    }

    private fun parseSpeakers(jsonStr: String): List<SpeakerProfile> {
        val result = mutableListOf<SpeakerProfile>()
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val id = obj.getString("id")
                val name = obj.getString("name")
                val pathsArray = obj.getJSONArray("samplePaths")
                val paths = mutableListOf<String>()
                for (j in 0 until pathsArray.length()) {
                    paths.add(pathsArray.getString(j))
                }
                // 反序列化嵌入向量 — 支持可变维度
                val embedJson = obj.optJSONArray("embedding")
                val embeddingType = obj.optString("embeddingType", EMBEDDING_TYPE_STATISTICAL)
                val embeddingDim = obj.optInt("embeddingDim", STATISTICAL_EMBEDDING_DIM)

                // 实际维度以 JSON 数组长度为准（最可靠）
                val actualDim = embedJson?.length() ?: embeddingDim
                val embed = FloatArray(actualDim)
                if (embedJson != null) {
                    for (k in 0 until actualDim) {
                        embed[k] = embedJson.getDouble(k).toFloat()
                    }
                }
                result.add(SpeakerProfile(id, name, paths, embed, embeddingType, actualDim))
            }
        } catch (e: Exception) {
            DebugLog.e(TAG, "解析声纹数据失败: ${e.message}", e)
        }
        return result
    }

    /** 解析 WAV 文件头，返回 PCM 数据起始偏移量 */
    private fun parseWavDataOffset(bytes: ByteArray): Int? {
        if (bytes.size < 44 || bytes.sliceArray(0..3).toString(Charsets.US_ASCII) != "RIFF") return null
        for (i in 12 until bytes.size - 4) {
            if (bytes.sliceArray(i until i + 4).toString(Charsets.US_ASCII) == "data") {
                return i + 8
            }
        }
        return null
    }
}
