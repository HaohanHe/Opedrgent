package top.hsyscn.opedrgent.cultivation.store

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import top.hsyscn.opedrgent.cultivation.model.PersonaProfile
import top.hsyscn.opedrgent.cultivation.model.PersonaTrait
import top.hsyscn.opedrgent.cultivation.model.PersonaTraitStatus
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.File

/**
 * 自动人格画像存储。
 *
 * 单份、持续演进：以 JSON 文件保存在应用私有目录（filesDir/persona/persona_profile.json），
 * 每次更新前把上一版备份为 persona_profile.prev.json 以便回滚。不涉及数据库迁移；
 * 编解码为纯函数（见伴生对象）、便于单元测试。画像只由模型经 persona 工具写入，全程不出设备。
 */
class PersonaProfileStore(context: Context) {

    private val dir = File(context.filesDir, "persona").apply { mkdirs() }
    private val file = File(dir, "persona_profile.json")
    private val prevFile = File(dir, "persona_profile.prev.json")

    @Volatile
    private var cached: PersonaProfile? = null

    /** 读取当前画像；不存在或解析失败返回 null（视为尚未建立）。 */
    suspend fun get(): PersonaProfile? = withContext(Dispatchers.IO) {
        cached?.let { return@withContext it }
        if (!file.exists()) return@withContext null
        runCatching { decode(file.readText()) }
            .onSuccess { cached = it }
            .onFailure { DebugLog.w(TAG, "画像解析失败：${it.message}") }
            .getOrNull()
    }

    /**
     * 保存模型给出的新画像：备份当前版本、版本号 +1、写入并刷新缓存，返回落盘后的画像。
     */
    suspend fun save(profile: PersonaProfile): PersonaProfile = withContext(Dispatchers.IO) {
        val current = if (cached != null) cached else readFromDisk()
        val now = System.currentTimeMillis()
        val nextVersion = profile.version.takeIf { it > 0 } ?: ((current?.version ?: 0) + 1)
        val toSave = profile.copy(
            version = nextVersion,
            createdAt = current?.createdAt ?: profile.createdAt,
            updatedAt = now,
        )
        if (file.exists()) runCatching { file.copyTo(prevFile, overwrite = true) }
        file.writeText(encode(toSave))
        cached = toSave
        toSave
    }

    private fun readFromDisk(): PersonaProfile? {
        if (!file.exists()) return null
        return runCatching { decode(file.readText()) }
            .onFailure { DebugLog.w(TAG, "画像读取失败：${it.message}") }
            .getOrNull()
    }

    companion object {
        private const val TAG = "PersonaProfileStore"

        /** 把画像编码为 JSON 文本（纯函数）。 */
        fun encode(p: PersonaProfile): String {
            val o = JSONObject()
                .put("version", p.version)
                .put("actual", JSONArray(p.actualSelf.map(::traitToJson)))
                .put("aspired", JSONArray(p.aspiredSelf.map(::traitToJson)))
                .put("openQuestions", JSONArray(p.openQuestions))
                .put("createdAt", p.createdAt)
                .put("updatedAt", p.updatedAt)
            return o.toString(2)
        }

        /** 从 JSON 文本解码画像（纯函数）；非法 JSON 由调用方处理。 */
        fun decode(raw: String): PersonaProfile {
            val o = JSONObject(raw)
            fun traits(key: String): List<PersonaTrait> =
                o.optJSONArray(key)?.let { arr ->
                    (0 until arr.length()).map { traitFromJson(arr.getJSONObject(it)) }
                } ?: emptyList()
            fun strings(key: String): List<String> =
                o.optJSONArray(key)?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList()
            return PersonaProfile(
                version = o.optInt("version", 1),
                actualSelf = traits("actual"),
                aspiredSelf = traits("aspired"),
                openQuestions = strings("openQuestions"),
                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
            )
        }

        private fun traitToJson(t: PersonaTrait): JSONObject = JSONObject()
            .put("text", t.text)
            .put("evidence", t.evidence)
            .put("source", t.sourceLabel)
            .put("observedAt", t.observedAt)
            .put("status", t.status.name)

        private fun traitFromJson(o: JSONObject): PersonaTrait = PersonaTrait(
            text = o.optString("text", ""),
            evidence = o.optString("evidence", ""),
            sourceLabel = o.optString("source", ""),
            observedAt = o.optLong("observedAt", System.currentTimeMillis()),
            status = PersonaTraitStatus.fromName(o.optString("status", "ACTIVE")),
        )
    }
}
