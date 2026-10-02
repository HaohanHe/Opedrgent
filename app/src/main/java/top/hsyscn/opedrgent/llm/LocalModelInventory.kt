package top.hsyscn.opedrgent.llm

import android.content.Context
import org.json.JSONObject

/**
 * 每个本地模型的本地元信息（明文 SharedPreferences，非敏感）。
 *
 * 持久化在独立明文 prefs 文件 `local_model_inventory` 中，键 `models` 存放 JSON：
 * { "<modelId>": { "downloadedAt": <epochMs>, "verified": <bool> } }。
 *
 * - 仅记录本 App 自己下载/删除的事实，不触网、不存储任何用户内容。
 * - verified 口径与下载器一致：仅当配置了可信上游 SHA-256 且校验通过才为 true；
 *   无上游哈希时（维持体积校验）如实为 false，绝不臆造哈希。
 */
class LocalModelInventory(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 记录一次成功下载落定。幂等：重复记录会刷新下载时间。 */
    @Synchronized
    fun recordDownloaded(id: String, verified: Boolean) {
        val root = readRoot()
        root.put(
            id,
            JSONObject()
                .put("downloadedAt", System.currentTimeMillis())
                .put("verified", verified),
        )
        writeRoot(root)
    }

    /** 删除模型后清理其元信息。 */
    @Synchronized
    fun remove(id: String) {
        val root = readRoot()
        root.remove(id)
        writeRoot(root)
    }

    fun get(id: String): LocalModelMeta? {
        val root = readRoot()
        val obj = root.optJSONObject(id) ?: return null
        return LocalModelMeta(
            downloadedAt = obj.optLong("downloadedAt", 0L),
            verified = obj.optBoolean("verified", false),
        )
    }

    fun all(): Map<String, LocalModelMeta> {
        val root = readRoot()
        val result = LinkedHashMap<String, LocalModelMeta>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            root.optJSONObject(k)?.let { o ->
                result[k] = LocalModelMeta(
                    downloadedAt = o.optLong("downloadedAt", 0L),
                    verified = o.optBoolean("verified", false),
                )
            }
        }
        return result
    }

    private fun readRoot(): JSONObject = runCatching {
        JSONObject(prefs.getString(KEY_MODELS, "{}") ?: "{}")
    }.getOrDefault(JSONObject())

    private fun writeRoot(root: JSONObject) {
        prefs.edit().putString(KEY_MODELS, root.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "local_model_inventory"
        private const val KEY_MODELS = "models"
    }
}

/** 单个本地模型的本地元信息。 */
data class LocalModelMeta(
    val downloadedAt: Long,
    val verified: Boolean,
)
