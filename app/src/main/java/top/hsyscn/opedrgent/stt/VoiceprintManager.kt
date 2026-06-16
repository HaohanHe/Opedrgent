package top.hsyscn.opedrgent.stt

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.File
import java.util.UUID
import kotlin.random.Random

/**
 * 声纹管理器：负责说话人注册、列表查询、删除和匹配。
 *
 * 当前实现使用 SharedPreferences 存储 speaker profiles（JSON），
 * matchSpeaker 为占位实现，随机返回已注册说话人或 null，
 * 后续可替换为真正的声纹特征提取与比对模型。
 */
class VoiceprintManager(private val context: Context) {

    data class SpeakerProfile(
        val id: String,
        val name: String,
        val samplePaths: List<String>,
    )

    companion object {
        private const val PREFS_NAME = "voiceprint_prefs"
        private const val KEY_SPEAKERS = "speakers"
        private const val TAG = "VoiceprintManager"
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * 注册说话人。
     *
     * @param name 说话人名称
     * @param samplePaths 录音样本文件路径列表
     * @return 注册后的 SpeakerProfile
     */
    fun enrollSpeaker(name: String, samplePaths: List<String>): SpeakerProfile {
        val profile = SpeakerProfile(
            id = UUID.randomUUID().toString(),
            name = name,
            samplePaths = samplePaths,
        )
        val speakers = listSpeakers().toMutableList()
        speakers.add(profile)
        saveSpeakers(speakers)
        DebugLog.i(TAG, "注册说话人: ${profile.name}, 样本数: ${samplePaths.size}")
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
     * 当前为占位实现：随机返回已注册说话人 ID 或 null。
     * 后续接入真实声纹模型后可替换为余弦相似度比对等算法。
     *
     * @param audioFeatures 音频特征向量
     * @return 匹配到的说话人 ID，未匹配到则返回 null
     */
    fun matchSpeaker(audioFeatures: FloatArray): String? {
        val speakers = listSpeakers()
        if (speakers.isEmpty()) return null

        // 占位逻辑：30% 概率不匹配，70% 概率随机匹配一个已注册说话人
        if (Random.nextFloat() < 0.3f) return null
        return speakers.random().id
    }

    /**
     * 根据 ID 获取说话人信息。
     */
    fun getSpeakerById(id: String): SpeakerProfile? {
        return listSpeakers().find { it.id == id }
    }

    private fun saveSpeakers(speakers: List<SpeakerProfile>) {
        val jsonArray = JSONArray()
        speakers.forEach { speaker ->
            val obj = JSONObject()
            obj.put("id", speaker.id)
            obj.put("name", speaker.name)
            val paths = JSONArray()
            speaker.samplePaths.forEach { paths.put(it) }
            obj.put("samplePaths", paths)
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
                result.add(SpeakerProfile(id, name, paths))
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
}
