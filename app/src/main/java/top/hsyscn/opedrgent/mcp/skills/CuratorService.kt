package top.hsyscn.opedrgent.mcp.skills

import android.content.Context
import org.json.JSONObject
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.File

class CuratorService(
    private val skillRegistry: SkillRegistry,
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

    private val stateFile: File = File(context.filesDir, CURATOR_STATE_FILE)
    private var state = loadState()

    // ── 空闲触发入口（非 cron） ──
    suspend fun maybeRunCurator(): CuratorResult {
        if (state.paused) {
            DebugLog.d("$TAG: curator is paused, skipping")
            return CuratorResult(ran = false, summary = "paused")
        }

        val now = System.currentTimeMillis()
        val elapsedSinceLastRun = now - state.lastRunAt

        // 空闲触发：距离上次运行超过 interval 才运行
        if (state.lastRunAt > 0 && elapsedSinceLastRun < DEFAULT_INTERVAL_MS) {
            DebugLog.d("$TAG: not yet due (elapsed=${elapsedSinceLastRun / 3600000}h < ${DEFAULT_INTERVAL_MS / 3600000}h)")
            return CuratorResult(ran = false, summary = "not_due")
        }

        return runCuratorInternal()
    }

    // ── 核心：审查所有 non-pinned skills ──
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

    private suspend fun reviewSkills(): CuratorResult {
        val allSkills = skillRegistry.getAllSkills()
        val archivedIds = mutableListOf<String>()
        val staleIds = mutableListOf<String>()
        var skippedPinned = 0

        for (skill in allSkills) {
            // ★ Pinned skill 跳过所有自动转换
            if (skill.isPinned()) {
                skippedPinned++
                continue
            }

            val lastUsed = skill.lastUsedAt()
            val age = System.currentTimeMillis() - lastUsed

            when {
                age > ARCHIVE_AFTER_MS -> {
                    // ★ Never auto-delete — only archive (recoverable)
                    skillRegistry.archiveSkill(skill.id)
                    archivedIds.add(skill.id)
                    DebugLog.d("$TAG: archived skill '${skill.id}' (age=${age / 86400000}d)")
                }
                age > STALE_AFTER_MS -> {
                    skillRegistry.markSkillStale(skill.id)
                    staleIds.add(skill.id)
                    DebugLog.d("$TAG: marked stale '${skill.id}' (age=${age / 86400000}d)")
                }
            }
        }

        val summary = buildString {
            append("archived=${archivedIds.size}, stale=${staleIds.size}")
            if (skippedPinned > 0) append(", pinned_skipped=$skippedPinned")
        }

        return CuratorResult(
            ran = false, // will be set to true by caller
            archivedIds = archivedIds,
            staleIds = staleIds,
            skippedPinned = skippedPinned,
            summary = summary,
        )
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
}
