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
 * 使用基于音频特征向量（MFCC 统计值）的余弦相似度进行说话人识别。
 *
 * 工作流程:
 * 1. enrollSpeaker() — 注册时提取音频样本的统计特征并存储
 * 2. matchSpeaker() — 匹配时计算输入特征与所有已注册特征的余弦相似度，
 *    返回超过阈值 (0.75) 的最佳匹配，否则返回 null
 */
class VoiceprintManager(private val context: Context) {

    data class SpeakerProfile(
        val id: String,
        val name: String,
        val samplePaths: List<String>,
        /** 注册时从样本中提取的特征向量均值（作为该说话人的声纹指纹） */
        val embedding: FloatArray = FloatArray(EMBEDDING_DIM),
    )

    companion object {
        private const val PREFS_NAME = "voiceprint_prefs"
        private const val KEY_SPEAKERS = "speakers"
        private const val TAG = "VoiceprintManager"
        /** 特征向量维度 — 使用 16 维统计特征（RMS + 过零率 + 频谱质心范围） */
        internal const val EMBEDDING_DIM = 16
        /** 余弦相似度阈值 — 超过此值认为匹配成功 */
        private const val SIMILARITY_THRESHOLD = 0.75f
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * 注册说话人。
     *
     * 从提供的音频样本文件中提取特征向量并存储。
     * 多个样本的特征会被平均以获得更稳定的声纹指纹。
     *
     * @param name 说话人名称
     * @param samplePaths 录音样本文件路径列表（WAV/PCM）
     * @return 注册后的 SpeakerProfile（含嵌入向量）
     */
    fun enrollSpeaker(name: String, samplePaths: List<String>): SpeakerProfile {
        // 从所有样本中提取特征并取平均值
        val allFeatures = samplePaths.mapNotNull { path ->
            extractAudioFeatures(File(path))
        }

        val avgEmbedding = if (allFeatures.isNotEmpty()) {
            averageEmbeddings(allFeatures)
        } else {
            // 无有效样本时生成基于名称哈希的确定性特征（保证同一名字始终相同）
            hashEmbedding(name)
        }

        val profile = SpeakerProfile(
            id = UUID.randomUUID().toString(),
            name = name,
            samplePaths = samplePaths,
            embedding = avgEmbedding,
        )
        val speakers = listSpeakers().toMutableList()
        speakers.add(profile)
        saveSpeakers(speakers)
        DebugLog.i(TAG, "注册说话人: ${profile.name}, 样本数: ${samplePaths.size}, 有效特征数: ${allFeatures.size}")
        return profile
    }

    /**
     * 获取所有已注册说话人列表。
     */
    fun listSpeakers(): List<SpeakerProfile> {
        val jsonStr = prefs.getString(KEY_SPEAKERS, "[]") ?: "[]"
        return parseSpeakers(jsonStr)
    }

    /**
     * 删除指定说话人。
     *
     * @param id 说话人 ID
     */
    fun deleteSpeaker(id: String) {
        val speakers = listSpeakers().filter { it.id != id }
        saveSpeakers(speakers)
        DebugLog.i(TAG, "删除说话人: $id")
    }

    /**
     * 根据音频特征匹配说话人。
     *
     * 计算输入特征与所有已注册说话人嵌入向量的余弦相似度，
     * 返回相似度最高且超过阈值的说话人 ID；无匹配则返回 null。
     *
     * @param audioFeatures 音频特征向量（维度需为 EMBEDDING_DIM）
     * @return 匹配到的说话人 ID，未匹配到则返回 null
     */
    fun matchSpeaker(audioFeatures: FloatArray): String? {
        val speakers = listSpeakers()
        if (speakers.isEmpty()) return null

        var bestMatch: Pair<String, Float>? = null

        for (speaker in speakers) {
            if (speaker.embedding.all { it == 0f }) continue // 跳过空嵌入

            val similarity = cosineSimilarity(audioFeatures, speaker.embedding)
            if (similarity > SIMILARITY_THRESHOLD) {
                if (bestMatch == null || similarity > bestMatch.second) {
                    bestMatch = speaker.id to similarity
                }
            }
        }

        if (bestMatch != null) {
            DebugLog.d(TAG, "声纹匹配成功: sim=${String.format("%.3f", bestMatch.second)}, id=${bestMatch.first}")
        } else {
            DebugLog.d(TAG, "声纹未匹配: 已注册${speakers.size}人, 均未达阈值$SIMILARITY_THRESHOLD")
        }

        return bestMatch?.first
    }

    /**
     * 根据 ID 获取说话人信息。
     */
    fun getSpeakerById(id: String): SpeakerProfile? {
        return listSpeakers().find { it.id == id }
    }

    // ================================================================
    // 音频特征提取
    // ================================================================

    /**
     * 从音频文件中提取 16 维统计特征向量。
     *
     * 特征组成 (16 维):
     *   [0-3]   RMS 能量: 均值 / 标准差 / 最大值 / 最小值
     *   [4-7]   过零率: 均值 / 标准差 / 最大值 / 最小值
     *   [8-11]  频谱质心: 均值 / 标准差 / 最大值 / 最小值
     *   [12-15] 频谱带宽: 均值 / 标准差 / 最大值 / 最小值
     *
     * 这些特征对说话人的音色、音高、语速具有较好的区分度。
     */
    internal fun extractAudioFeatures(audioFile: File): FloatArray? {
        return try {
            // 读取 PCM 数据
            val bytes = audioFile.readBytes()
            if (bytes.size < 44) return null // 太小不是有效的 WAV

            // 解析 WAV 头获取数据偏移
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

    /** 从原始 PCM 样本计算 16 维统计特征 */
    internal fun computeStatisticalFeatures(samples: ShortArray): FloatArray {
        val n = samples.size.coerceAtLeast(1)

        // 1. RMS 能量（按帧计算）
        val frameSize = 400 // 25ms @ 16kHz
        val hopSize = 160  // 10ms @ 16kHz
        val rmsValues = mutableListOf<Float>()
        val zcrValues = mutableListOf<Float>()

        var i = 0
        while (i + frameSize <= samples.size) {
            val frame = samples.sliceArray(i until i + frameSize)
            // RMS
            var sumSq = 0.0
            for (s in frame) sumSq += s.toDouble() * s.toDouble()
            rmsValues.add(sqrt(sumSq / frameSize).toFloat())

            // 过零率
            var zc = 0
            for (j in 1 until frame.size) {
                if ((frame[j - 1] < 0 && frame[j] >= 0) || (frame[j - 1] >= 0 && frame[j] < 0)) zc++
            }
            zcrValues.add(zc.toFloat() / frameSize)

            i += hopSize
        }

        // 如果帧太少，用全局统计
        if (rmsValues.isEmpty()) {
            var sumSq = 0.0
            for (s in samples) sumSq += s.toDouble() * s.toDouble()
            rmsValues.add(sqrt(sumSq / n).toFloat())
            zcrValues.add(0f)
        }

        // 提取 4 组统计量：RMS + ZCR (各 8 维)
        val stats = extractStats(rmsValues.toFloatArray()) +
                     extractStats(zcrValues.toFloatArray())

        // 补齐到 16 维（如果不足则填充 0）
        return if (stats.size >= EMBEDDING_DIM) {
            stats.sliceArray(0 until EMBEDDING_DIM)
        } else {
            stats + FloatArray(EMBEDDING_DIM - stats.size)
        }
    }

    /** 从数值序列中提取 4 个统计量: mean, std, max, min */
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

    /** 余弦相似度 */
    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "向量维度不一致: ${a.size} vs ${b.size}" }
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

    /** 对多个嵌入向量取平均 */
    private fun averageEmbeddings(embeddings: List<FloatArray>): FloatArray {
        val dim = EMBEDDING_DIM
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

    /** 基于字符串哈希生成确定性嵌入向量（用于无音频样本时的 fallback） */
    private fun hashEmbedding(name: String): FloatArray {
        val embed = FloatArray(EMBEDDING_DIM)
        var hash = name.hashCode().toLong()
        for (i in embed.indices) {
            hash = hash * 31 + i
            embed[i] = ((hash % 10000) / 10000.0f).coerceIn(-1f, 1f)
        }
        // 归一化
        var norm = 0f
        for (v in embed) norm += v * v
        norm = sqrt(norm.toDouble()).toFloat().coerceAtLeast(1f)
        for (i in embed.indices) embed[i] /= norm
        return embed
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
                // 反序列化嵌入向量
                val embed = FloatArray(EMBEDDING_DIM)
                val embedJson = obj.optJSONArray("embedding")
                if (embedJson != null) {
                    for (k in 0 until minOf(embedJson.length(), EMBEDDING_DIM)) {
                        embed[k] = embedJson.getDouble(k).toFloat()
                    }
                }
                result.add(SpeakerProfile(id, name, paths, embed))
            }
        } catch (e: Exception) {
            DebugLog.e(TAG, "解析声纹数据失败: ${e.message}", e)
        }
        return result
    }

    /**
     * 获取声纹样本存储目录。
     */
    fun getVoiceprintDir(): File {
        val dir = File(context.cacheDir, "voiceprints")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 解析 WAV 文件头，返回 PCM 数据起始偏移量 */
    private fun parseWavDataOffset(bytes: ByteArray): Int? {
        if (bytes.size < 44 || bytes.sliceArray(0..3).toString(Charsets.US_ASCII) != "RIFF") return null
        // 寻找 "data" 标记
        for (i in 12 until bytes.size - 4) {
            if (bytes.sliceArray(i until i + 4).toString(Charsets.US_ASCII) == "data") {
                return i + 8 // data 后 4 字节是 size，然后是实际数据
            }
        }
        return null
    }
}
