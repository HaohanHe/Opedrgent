package top.hsyscn.opedrgent.mcp.skills

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.File

/**
 * 技能管家服务 — 基于 Gallery Skill 系统升级版
 *
 * 职责：
 * - 委托 SkillLoader 管理技能加载/存储/生命周期
 * - 定期审查技能使用情况，归档不活跃的用户导入技能
 * - 维护内置技能与用户导入技能的一致性
 *
 * 与旧版差异：
 * - 旧版直接操作 SkillRegistry（内存 + JSON 文件）
 * - 新版通过 SkillLoader 统一管理，支持 SKILL.md 标准格式
 */
class CuratorService(
    private val skillLoader: SkillLoader,
    private val context: Context,
) {
    companion object {
        private const val TAG = "Curator"
        private const val CURATOR_STATE_FILE = "curator_state.json"
        private const val DEFAULT_INTERVAL_MS = 24L * 7 * 60 * 60 * 1000   // 每周一次
        private const val STALE_AFTER_MS = 30L * 24 * 60 * 60 * 1000      // 30 天不活跃
        private const val ARCHIVE_AFTER_MS = 90L * 24 * 60 * 60 * 1000     // 90 天归档
    }

    data class CuratorState(
        val lastRunAt: Long = 0,
        val lastRunDurationSec: Long = 0,
        val lastRunSummary: String = "",
        val paused: Boolean = false,
        val runCount: Int = 0,
        val archivedCount: Int = 0,
    )

    data class CuratorResult(
        val ran: Boolean,
        val archivedIds: List<String> = emptyList(),
        val staleIds: List<String> = emptyList(),
        val skippedPinned: Int = 0,
        val summary: String = "",
        val durationMs: Long = 0,
    )

    /** 技能使用记录 — 用于追踪最后使用时间 */
    data class SkillUsageRecord(
        val skillName: String,
        val lastUsedAt: Long,
        val useCount: Int = 1,
        val isPinned: Boolean = false,
    )

    private val stateFile: File = File(context.filesDir, CURATOR_STATE_FILE)
    private val usageFile: File = File(context.filesDir, "skill_usage.json")
    private var state = loadState()
    private var usageRecords = loadUsageRecords()

    // ── 空闲触发入口（非 cron） ──
    suspend fun maybeRunCurator(): CuratorResult {
        if (state.paused) {
            DebugLog.d("$TAG: curator is paused, skipping")
            return CuratorResult(ran = false, summary = "paused")
        }

        val now = System.currentTimeMillis()
        val elapsedSinceLastRun = now - state.lastRunAt

        if (state.lastRunAt > 0 && elapsedSinceLastRun < DEFAULT_INTERVAL_MS) {
            DebugLog.d("$TAG: not yet due (elapsed=${elapsedSinceLastRun / 3600000}h < ${DEFAULT_INTERVAL_MS / 3600000}h)")
            return CuratorResult(ran = false, summary = "not_due")
        }

        return runCuratorInternal()
    }

    // ── 核心：审查所有 non-pinned 用户导入 skills ──
    private suspend fun runCuratorInternal(): CuratorResult {
        val start = System.currentTimeMillis()
        DebugLog.i("$TAG: starting maintenance run #${state.runCount + 1}")

        try {
            val result = reviewSkills()

            state = state.copy(
                lastRunAt = System.currentTimeMillis(),
                lastRunDurationSec = (System.currentTimeMillis() - start) / 1000,
                lastRunSummary = result.summary,
                runCount = state.runCount + 1,
                archivedCount = state.archivedCount + result.archivedIds.size + result.staleIds.size,
            )
            saveState(state)

            DebugLog.i("$TAG: completed in ${System.currentTimeMillis() - start}ms — ${result.summary}")
            return result.copy(ran = true, durationMs = System.currentTimeMillis() - start)
        } catch (e: Exception) {
            DebugLog.e("$TAG: failed — ${e.message}")
            return CuratorResult(ran = false, summary = "error: ${e.message}")
        }
    }

    private suspend fun reviewSkills(): CuratorResult = withContext(Dispatchers.IO) {
        val allSkills = skillLoader.loadAllSkills()
        val archivedIds = mutableListOf<String>()
        val staleIds = mutableListOf<String>()
        var skippedPinned = 0
        val now = System.currentTimeMillis()

        for (skill in allSkills) {
            // ★ 内置 skill 和 pinned skill 跳过所有自动转换
            if (skill.isBuiltIn) continue

            val isPinned = usageRecords[skill.skillName]?.isPinned == true
            if (isPinned) {
                skippedPinned++
                continue
            }

            val record = usageRecords[skill.skillName]
            val lastUsed = record?.lastUsedAt ?: skill.createdAtMs
            val age = now - lastUsed

            when {
                age > ARCHIVE_AFTER_MS -> {
                    // ★ Never auto-delete — only archive (disable)
                    skillLoader.setSkillEnabled(skill.skillName, false)
                    archivedIds.add(skill.skillName)
                    DebugLog.d("$TAG: disabled skill '${skill.skillName}' (age=${age / 86400000}d)")
                }
                age > STALE_AFTER_MS -> {
                    markSkillStale(skill.skillName)
                    staleIds.add(skill.skillName)
                    DebugLog.d("$TAG: marked stale '${skill.skillName}' (age=${age / 86400000}d)")
                }
            }
        }

        val summary = buildString {
            append("archived=${archivedIds.size}, stale=${staleIds.size}")
            if (skippedPinned > 0) append(", pinned_skipped=$skippedPinned")
            append(", builtin_skipped=${allSkills.count { it.isBuiltIn }}")
        }

        CuratorResult(
            ran = false,
            archivedIds = archivedIds,
            staleIds = staleIds,
            skippedPinned = skippedPinned,
            summary = summary,
        )
    }

    // ── 使用追踪 API（供外部调用） ──

    /**
     * 记录某个技能被使用了
     */
    fun touchSkill(skillName: String) {
        val now = System.currentTimeMillis()
        val existing = usageRecords[skillName]
        usageRecords[skillName] = SkillUsageRecord(
            skillName = skillName,
            lastUsedAt = now,
            useCount = (existing?.useCount ?: 0) + 1,
            isPinned = existing?.isPinned == true,
        )
        saveUsageRecords()
        DebugLog.d("$TAG: touched skill $skillName at $now")
    }

    /**
     * 设置/取消技能的 pinned 状态
     */
    fun setPinned(skillName: String, pinned: Boolean): Boolean {
        val existing = usageRecords[skillName] ?: SkillUsageRecord(skillName, System.currentTimeMillis())
        usageRecords[skillName] = existing.copy(isPinned = pinned)
        saveUsageRecords()
        DebugLog.i("$TAG: skill $skillName pinned=$pinned")
        return true
    }

    /**
     * 检查技能是否为 pinned
     */
    fun isPinned(skillName: String): Boolean {
        return usageRecords[skillName]?.isPinned == true
    }

    private fun markSkillStale(skillName: String) {
        // 记录 stale 标记到 usage records 的 metadata 中
        val existing = usageRecords[skillName]
        if (existing != null) {
            usageRecords[skillName] = existing.copy(lastUsedAt = System.currentTimeMillis())
            saveUsageRecords()
        }
        DebugLog.d("$TAG: marked skill $skillName as stale")
    }

    // ── 持久化状态 ──
    private fun loadState(): CuratorState {
        if (!stateFile.exists()) return CuratorState()
        return try {
            val json = JSONObject(stateFile.readText())
            CuratorState(
                lastRunAt = json.optLong("lastRunAt", 0),
                lastRunDurationSec = json.optLong("lastRunDurationSec", 0),
                lastRunSummary = json.optString("lastRunSummary", ""),
                paused = json.optBoolean("paused", false),
                runCount = json.optInt("runCount", 0),
                archivedCount = json.optInt("archivedCount", 0),
            )
        } catch (e: Exception) {
            DebugLog.w("$TAG: failed to load state — ${e.message}")
            CuratorState()
        }
    }

    private fun saveState(state: CuratorState) {
        try {
            stateFile.parentFile?.mkdirs()
            val json = JSONObject().apply {
                put("lastRunAt", state.lastRunAt)
                put("lastRunDurationSec", state.lastRunDurationSec)
                put("lastRunSummary", state.lastRunSummary)
                put("paused", state.paused)
                put("runCount", state.runCount)
                put("archivedCount", state.archivedCount)
            }
            stateFile.writeText(json.toString(2))
        } catch (e: Exception) {
            DebugLog.e("$TAG: failed to save state — ${e.message}")
        }
    }

    private fun loadUsageRecords(): Map<String, SkillUsageRecord> {
        if (!usageFile.exists()) return emptyMap()
        return try {
            val json = JSONObject(usageFile.readText())
            val keys = json.keys()
            val result = mutableMapOf<String, SkillUsageRecord>()
            while (keys.hasNext()) {
                val key = keys.next()
                val obj = json.optJSONObject(key) ?: continue
                result[key] = SkillUsageRecord(
                    skillName = key,
                    lastUsedAt = obj.optLong("lastUsedAt", 0L),
                    useCount = obj.optInt("useCount", 1),
                    isPinned = obj.optBoolean("isPinned", false),
                )
            }
            result
        } catch (e: Exception) {
            DebugLog.w("$TAG: failed to load usage records — ${e.message}")
            emptyMap()
        }
    }

    private fun saveUsageRecords() {
        try {
            usageFile.parentFile?.mkdirs()
            val json = JSONObject()
            for ((name, record) in usageRecords) {
                json.put(name, JSONObject().apply {
                    put("lastUsedAt", record.lastUsedAt)
                    put("useCount", record.useCount)
                    put("isPinned", record.isPinned)
                })
            }
            usageFile.writeText(json.toString(2))
        } catch (e: Exception) {
            DebugLog.e("$TAG: failed to save usage records — ${e.message}")
        }
    }

    // ── 公开 API ──
    fun pause() {
        state = state.copy(paused = true)
        saveState(state)
        DebugLog.i("$TAG: paused")
    }

    fun resume() {
        state = state.copy(paused = false)
        saveState(state)
        DebugLog.i("$TAG: resumed")
    }

    fun getState(): CuratorState = state

    fun resetState() {
        state = CuratorState()
        saveState(state)
        DebugLog.i("$TAG: state reset")
    }

    /**
     * 获取当前系统 Prompt 片段（委托给 SkillLoader）
     */
    fun buildSkillsSystemPrompt(): String = skillLoader.buildSkillsSystemPrompt()
}
