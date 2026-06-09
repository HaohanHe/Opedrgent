package top.hsyscn.opedrgent.mcp.skills

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import top.hsyscn.opedrgent.utils.DebugLog
import java.util.concurrent.TimeUnit

/**
 * Skill Curator — 空闲触发的技能生命周期管理器（对标 Hermes Agent）。
 *
 * ## 设计理念（来自 Hermes Agent）
 * Curator 不是 cron 定时任务，而是**空闲触发**的后台 Agent：
 * - 应用空闲超过阈值时自动运行一次
 * - 只归档（可恢复），绝不删除
 * - Pinned skill 跳过所有自动状态转换
 * - 归档后的 skill 可以手动恢复
 *
 * ## 状态机
 * ```
 * ACTIVE → STALE (闲置 > staleThreshold) → ARCHIVED (归档，可恢复)
 *                                          ↕ 手动恢复
 * ```
 *
 * @see StandardSkillDefinition 技能定义
 */
class SkillCurator(
    private val skillLoader: SkillLoader,
) {

    companion object {
        /** 闲置判定阈值（毫秒），默认 7 天 */
        val DEFAULT_STALE_THRESHOLD_MS = TimeUnit.DAYS.toMillis(7)

        /** 归档判定阈值（毫秒），默认 14 天（闲置超过此时间才归档） */
        val DEFAULT_ARCHIVE_THRESHOLD_MS = TimeUnit.DAYS.toMillis(14)

        /** 最小检查间隔（毫秒），防止频繁检查 */
        val MIN_CHECK_INTERVAL_MS = TimeUnit.HOURS.toMillis(1)

        /** Pinned skill 永远不会被自动归档 */
        const val PINNED_FLAG = "pinned"
    }

    /** Skill 状态枚举 */
    enum class SkillState {
        /** 活跃：正常可用 */
        ACTIVE,
        /** 闲置：超过 staleThreshold 未使用 */
        STALE,
        /** 已归档：超过 archiveThreshold 未使用（可恢复） */
        ARCHIVED,
    }

    /** Skill 元信息（含生命周期状态） */
    data class SkillMeta(
        val skillId: String,
        val name: String,
        val state: SkillState = SkillState.ACTIVE,
        val lastUsedAt: Long = System.currentTimeMillis(),
        val createdAt: Long = System.currentTimeMillis(),
        val archivedAt: Long? = null,
        val useCount: Int = 0,
        val isPinned: Boolean = false,
        val tags: Set<String> = emptySet(),
    ) {
        val isArchived: Boolean get() = state == SkillState.ARCHIVED
        val isActive: Boolean get() = state == SkillState.ACTIVE
        val idleDays: Long get() = TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - lastUsedAt)
    }

    /** Curator 配置 */
    data class Config(
        val staleThresholdMs: Long = DEFAULT_STALE_THRESHOLD_MS,
        val archiveThresholdMs: Long = DEFAULT_ARCHIVE_THRESHOLD_MS,
        val autoArchive: Boolean = true,
        val autoRestore: Boolean = false,  // 是否自动恢复被调用的归档 skill
        val maxArchivedPercent: Float = 0.5f, // 归档 skill 占比上限
    )

    private val config = Config()
    private val skillMetaMap = mutableMapOf<String, SkillMeta>()
    private var lastCheckTime = 0L
    private var isRunning = false

    // ==================== 公共 API ====================

    /**
     * 记录 Skill 被使用（每次调用 Skill 时触发）。
     */
    fun recordUsage(skillId: String) {
        val meta = skillMetaMap.getOrPut(skillId) {
            // 首次使用，从 SkillLoader 获取基本信息
            val def = skillLoader.getSkillById(skillId)
            SkillMeta(
                skillId = skillId,
                name = def?.name ?: skillId,
                isPinned = def?.tags?.contains(PINNED_FLAG) == true,
                tags = def?.tags?.toSet() ?: emptySet(),
            )
        }

        skillMetaMap[skillId] = meta.copy(
            state = SkillState.ACTIVE,  // 使用后恢复活跃
            lastUsedAt = System.currentTimeMillis(),
            useCount = meta.useCount + 1,
        )

        DebugLog.d("SkillCurator: recorded usage of $skillId (count=${meta.useCount + 1})")
    }

    /**
     * Pin/Unpin 一个 Skill（Pinned 的 skill 不会被自动归档）。
     */
    fun setPinned(skillId: String, pinned: Boolean) {
        skillMetaMap.computeIfPresent(skillId) { _, meta ->
            meta.copy(isPinned = pinned)
        }
        DebugLog.i("SkillCurator: $skillId pinned=$pinned")
    }

    /**
     * 手动恢复已归档的 Skill。
     */
    fun restoreSkill(skillId: String): Boolean {
        val meta = skillMetaMap[skillId] ?: return false
        if (!meta.isArchived) return true  // 已经是活跃状态

        skillMetaMap[skillId] = meta.copy(
            state = SkillState.ACTIVE,
            archivedAt = null,
            lastUsedAt = System.currentTimeMillis(),
        )
        DebugLog.i("SkillCurator: restored $skillId from archived")
        return true
    }

    /**
     * 手动归档 Skill。
     */
    fun archiveSkill(skillId: String): Boolean {
        val meta = skillMetaMap[skillId] ?: return false
        skillMetaMap[skillId] = meta.copy(
            state = SkillState.ARCHIVED,
            archivedAt = System.currentTimeMillis(),
        )
        DebugLog.i("SkillCurator: archived $skillId")
        return true
    }

    /**
     * 执行一次 Curator 检查（空闲触发）。
     *
     * 应该在应用空闲时调用（如 App 进入后台、用户长时间未操作）。
     *
     * @return 检查报告（变更统计）
     */
    suspend fun runCheck(): CuratorReport = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()

        // 防止频繁检查
        if (now - lastCheckTime < MIN_CHECK_INTERVAL_MS) {
            return@withContext CuratorReport(skipped = true, reason = "检查间隔太短")
        }
        lastCheckTime = now
        isRunning = true

        try {
            var staleCount = 0
            var archivedCount = 0
            var restoredCount = 0

            for ((skillId, meta) in skillMetaMap.toList()) {
                // Pinned skill 跳过所有自动状态转换
                if (meta.isPinned) continue

                val idleTime = now - meta.lastUsedAt

                when {
                    // 活跃 → 闲置
                    meta.isActive && idleTime > config.staleThresholdMs -> {
                        skillMetaMap[skillId] = meta.copy(state = SkillState.STALE)
                        staleCount++
                        DebugLog.d("SkillCurator: $skillId → STALE (idle=${meta.idleDays}d)")
                    }
                    // 闲置 → 归档
                    meta.state == SkillState.STALE && idleTime > config.archiveThresholdMs -> {
                        if (config.autoArchive) {
                            skillMetaMap[skillId] = meta.copy(
                                state = SkillState.ARCHIVED,
                                archivedAt = now,
                            )
                            archivedCount++
                            DebugLog.d("SkillCurator: $skillId → ARCHIVED (idle=${meta.idleDays}d)")
                        }
                    }
                }
            }

            // 检查归档比例上限
            enforceArchiveLimit()

            CuratorReport(
                totalSkills = skillMetaMap.size,
                markedStale = staleCount,
                archived = archivedCount,
                restored = restoredCount,
                activeSkills = skillMetaMap.values.count { it.isActive },
                archivedSkills = skillMetaMap.values.count { it.isArchived },
            )
        } finally {
            isRunning = false
        }
    }

    /**
     * 获取指定 Skill 的状态。
     */
    fun getSkillState(skillId: String): SkillState? = skillMetaMap[skillId]?.state

    /**
     * 获取所有 Skill 的元信息。
     */
    fun getAllMeta(): List<SkillMeta> = skillMetaMap.values.toList()

    /**
     * 获取活跃 Skill 列表。
     */
    fun getActiveSkills(): List<SkillMeta> =
        skillMetaMap.values.filter { it.isActive || it.isPinned }

    /**
     * 获取统计摘要。
     */
    fun getStats(): CuratorStats = CuratorStats(
        total = skillMetaMap.size,
        active = skillMetaMap.values.count { it.isActive },
        stale = skillMetaMap.values.count { it.state == SkillState.STALE },
        archived = skillMetaMap.values.count { it.isArchived },
        pinned = skillMetaMap.values.count { it.isPinned },
        totalUses = skillMetaMap.values.sumOf { it.useCount },
    )

    // ==================== 内部方法 ====================

    /**
     * 强制归档比例上限。
     * 如果归档 skill 占比超过限制，恢复最早归档的 skill。
     */
    private fun enforceArchiveLimit() {
        if (skillMetaMap.isEmpty()) return

        val archived = skillMetaMap.values.filter { it.isArchived }
        val ratio = archived.size.toFloat() / skillMetaMap.size

        if (ratio <= config.maxArchivedPercent) return

        // 按归档时间排序，恢复最早的
        val toRestore = archived
            .sortedBy { it.archivedAt ?: Long.MAX_VALUE }
            .take((archived.size - (skillMetaMap.size * config.maxArchivedPercent)).toInt())

        for (meta in toRestore) {
            restoreSkill(meta.skillId)
            DebugLog.d("SkillCurator: auto-restored ${meta.skillId} (archive limit)")
        }
    }

    /**
     * 检查报告。
     */
    data class CuratorReport(
        val totalSkills: Int = 0,
        val markedStale: Int = 0,
        val archived: Int = 0,
        val restored: Int = 0,
        val activeSkills: Int = 0,
        val archivedSkills: Int = 0,
        val skipped: Boolean = false,
        val reason: String = "",
    )

    /**
     * 统计快照。
     */
    data class CuratorStats(
        val total: Int,
        val active: Int,
        val stale: Int,
        val archived: Int,
        val pinned: Int,
        val totalUses: Int,
    ) {
        fun toDisplayText(): String = "SkillCurator: 总计=$total 活跃=$active 闲置=$stale 归档=$archived 固定=$pinned 总使用=$totalUses"
    }
}
