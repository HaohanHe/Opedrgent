package top.hsyscn.opedrgent.llm

/**
 * 本地模型更新检测（纯本地、不触网）。
 *
 * 仅依据内置目录 [AvailableLocalModels] 的自有 id 做确定性的「品牌 + 世代」解析，
 * 与磁盘已下载集合比对：若同品牌目录中存在比已下载模型更新的世代，则给出克制提示。
 *
 * 约束：只提示、不强制、不自动下载、不发起任何网络请求。
 */
object LocalModelUpdateChecker {

    data class UpdateHint(
        val downloadedId: String,
        val message: String,
        val availableIds: List<String>,
    )

    /**
     * @param downloadedIds 磁盘上已完成下载的模型 id 集合（由调用方从下载器注入）。
     * @return 可更新提示列表；无可更新项时返回空列表。
     */
    fun findUpdates(downloadedIds: Set<String>): List<UpdateHint> {
        if (downloadedIds.isEmpty()) return emptyList()

        // 品牌分组：取 id 中第一个 '-' 之前的字母前缀（如 gemma / functiongemma）。
        val byBrand = AvailableLocalModels.MODELS.groupBy { brandOf(it.id) }

        val hints = mutableListOf<UpdateHint>()
        for ((_, models) in byBrand) {
            val catalogGen = models.mapNotNull { generationOf(it.id) }
            val maxGen = catalogGen.maxOrNull() ?: continue

            // 已下载且世代落后于目录最高世代的模型 → 提示存在更新世代
            for (downloadedId in downloadedIds) {
                val gen = generationOf(downloadedId) ?: continue
                if (gen >= maxGen) continue
                val newer = models.filter { (generationOf(it.id) ?: -1) > gen }
                if (newer.isEmpty()) continue
                hints += UpdateHint(
                    downloadedId = downloadedId,
                    message = "检测到更新世代：已下载 $downloadedId，目录中已有第 ${maxGen} 代模型" +
                        "（${newer.joinToString("、") { it.id }}）。如需可自行前往下载，本提示不会自动下载。",
                    availableIds = newer.map { it.id },
                )
            }
        }
        return hints
    }

    /** 品牌前缀：id 第一个 '-' 之前的部分。 */
    internal fun brandOf(id: String): String =
        id.substringBefore('-').lowercase()

    /**
     * 确定性世代解析：匹配 `<brand>-<n>-...` 形式，返回 n；
     * 无法解析（如 gemma-sprint / functiongemma）返回 null，不参与世代比较。
     * 这是对 App 自有目录 id 的结构化解析，不是对用户内容的关键词判定。
     */
    internal fun generationOf(id: String): Int? {
        val m = Regex("""^[a-z]+-(\d+)-""").find(id.lowercase()) ?: return null
        return m.groupValues[1].toIntOrNull()
    }
}
