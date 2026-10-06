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
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 自动人格画像存储。
 *
 * 单份、持续演进：JSON 文件保存在应用私有目录（filesDir/persona/persona_profile.json）。
 * 画像只由模型经 persona 工具（persona_get / persona_update）写入，全程不出设备，用户只读。
 *
 * 可靠性约定：
 * - 版本号每次 save 真实自增（current.version + 1），不采信调用方传入的 version；
 * - 进程内全局共享缓存：无论由 PersonaTool 还是 CultivationEngine 构造本类，get/save 都读写同一份缓存，
 *   杜绝多实例读到陈旧画像；
 * - 整文件原子写：先写 .tmp 并 fsync，再把旧主文件降级为 prev 副本，最后原子 rename 替换主文件；
 *   主文件不可解析时回退读取 prev 副本；
 * - save 与上一版按层合并：模型本次未给出的层沿用旧值，单条损坏不影响整份画像；
 *   同 text 条目缺 evidence/observedAt 时沿用旧值，绝不伪造。
 */
class PersonaProfileStore(context: Context) {

    private val dir = File(context.filesDir, "persona").apply { mkdirs() }
    private val file = File(dir, "persona_profile.json")
    private val prevFile = File(dir, "persona_profile.prev.json")

    /** 读取当前画像；不存在或主/备均不可解析返回 null（视为尚未建立）。 */
    suspend fun get(): PersonaProfile? = withContext(Dispatchers.IO) {
        sharedCache?.let { return@withContext it }
        readFromDisk().also { sharedCache = it }
    }

    /**
     * 保存模型给出的画像：与上一版按层合并、版本号自增、原子写盘，返回落盘后的画像。
     */
    suspend fun save(profile: PersonaProfile): PersonaProfile = withContext(Dispatchers.IO) {
        synchronized(LOCK) {
            val current = sharedCache ?: readFromDisk()
            val merged = mergeWith(current, profile)
            val toSave = merged.copy(
                version = (current?.version ?: 0) + 1,
                createdAt = current?.createdAt ?: merged.createdAt,
                updatedAt = System.currentTimeMillis(),
            )
            atomicWrite(toSave)
            sharedCache = toSave
            toSave
        }
    }

    /** 读盘：主文件失败时回退 prev 副本；二者都不可用返回 null。 */
    private fun readFromDisk(): PersonaProfile? {
        fun tryParse(f: File): PersonaProfile? {
            if (!f.exists()) return null
            return runCatching { decode(f.readText()) }
                .onFailure { DebugLog.w(TAG, "画像解析失败 ${f.name}: ${it.message}") }
                .getOrNull()
        }
        return tryParse(file) ?: run {
            DebugLog.w(TAG, "主画像不可用，回退 prev 副本")
            tryParse(prevFile)
        }
    }

    /** 原子写：tmp + fsync -> 旧主文件降级为 prev -> rename 替换主文件。 */
    private fun atomicWrite(p: PersonaProfile) {
        val tmp = File(dir, "persona_profile.json.tmp")
        tmp.outputStream().use { fos ->
            fos.write(encode(p).toByteArray(Charsets.UTF_8))
            fos.fd.sync()
        }
        // 旧主文件先降级为回退副本（best-effort）；随后原子替换主文件。
        // 若替换中途崩溃：主文件缺失时 get() 会回退到 prev，不会整份丢失。
        if (file.exists()) {
            runCatching {
                Files.move(
                    file.toPath(), prevFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
                )
            }
        }
        Files.move(
            tmp.toPath(), file.toPath(),
            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
        )
    }

    /**
     * 与上一版合并：模型本次未给出的层（空列表）沿用旧层，避免全量快照误清空已有画像；
     * 同 text 条目的 evidence / sourceLabel / observedAt 在新值缺失时沿用旧值，不伪造、不改写证据时间。
     */
    private fun mergeWith(current: PersonaProfile?, incoming: PersonaProfile): PersonaProfile {
        if (current == null) return incoming
        return incoming.copy(
            actualSelf = mergeTraits(current.actualSelf, incoming.actualSelf),
            aspiredSelf = mergeTraits(current.aspiredSelf, incoming.aspiredSelf),
            openQuestions = incoming.openQuestions.ifEmpty { current.openQuestions },
        )
    }

    private fun mergeTraits(old: List<PersonaTrait>, new: List<PersonaTrait>): List<PersonaTrait> {
        if (new.isEmpty()) return old
        val byText = old.associateBy { it.text }
        return new.map { nt ->
            val ot = byText[nt.text] ?: return@map nt
            nt.copy(
                evidence = nt.evidence.ifBlank { ot.evidence },
                sourceLabel = nt.sourceLabel.ifBlank { ot.sourceLabel },
                observedAt = ot.observedAt,
                status = nt.status,
            )
        }
    }

    companion object {
        private const val TAG = "PersonaProfileStore"

        /** 进程级共享缓存：所有 PersonaProfileStore 实例共用，避免多实例读到陈旧画像。 */
        @Volatile
        private var sharedCache: PersonaProfile? = null

        /** save 的读-改-写临界区锁（跨实例）。 */
        private val LOCK = Any()

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

        /** 从 JSON 文本解码画像（纯函数）；逐条容错，单条损坏只丢该条、不丢整份。 */
        fun decode(raw: String): PersonaProfile {
            val o = JSONObject(raw)
            fun traits(key: String): List<PersonaTrait> =
                o.optJSONArray(key)?.let { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        val jo = arr.optJSONObject(i) ?: return@mapNotNull null
                        runCatching { traitFromJson(jo) }.getOrNull()
                    }
                } ?: emptyList()
            fun strings(key: String): List<String> =
                o.optJSONArray(key)?.let { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        arr.optString(i, "").trim().takeIf { it.isNotBlank() }
                    }
                } ?: emptyList()
            return PersonaProfile(
                version = o.optInt("version", 0),
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
