package top.hsyscn.opedrgent.mcp.skills

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.File

class SkillCurator(
    private val registry: SkillRegistry,
    private val stateFile: File,
) {
    enum class SkillState {
        DRAFT, ACTIVE, ARCHIVED, PINNED
    }

    data class CuratorState(
        var lastRunAt: Long = 0L,
        var lastRunDurationMs: Long = 0L,
        var lastSummary: String? = null,
        var paused: Boolean = false,
        var runCount: Int = 0,
    )

    private var state = loadState()
    private var curatorJob: Job? = null

    companion object {
        private const val STALE_AFTER_MS = 7L * 24 * 60 * 60 * 1000  // 7 days
        private const val CHECK_INTERVAL_MS = 1L * 60 * 60 * 1000     // 1 hour
        private const val ARCHIVE_AFTER_MS = 90L * 24 * 60 * 60 * 1000 // 90 days
        private const val MAX_RUN_DURATION_MS = 5L * 60 * 1000         // 5 min max
    }

    fun start(scope: CoroutineScope) {
        curatorJob = scope.launch(Dispatchers.IO) {
            while (true) {
                delay(CHECK_INTERVAL_MS)
                if (!state.paused && isIdle()) {
                    maybeRunCurator()
                }
            }
        }
        DebugLog.i("SkillCurator: started (interval=${CHECK_INTERVAL_MS}ms, staleAfter=${STALE_AFTER_MS}ms)")
    }

    fun stop() {
        curatorJob?.cancel()
        curatorJob = null
        DebugLog.i("SkillCurator: stopped")
    }

    private suspend fun isIdle(): Boolean {
        // True if last run was more than STALE_AFTER_MS ago
        return System.currentTimeMillis() - state.lastRunAt > STALE_AFTER_MS
    }

    private suspend fun maybeRunCurator() {
        val start = System.currentTimeMillis()
        DebugLog.i("SkillCurator: starting maintenance run #${state.runCount + 1}")

        try {
            val summary = runCurator()
            state.lastRunAt = System.currentTimeMillis()
            state.lastRunDurationMs = state.lastRunAt - start
            state.lastSummary = summary
            state.runCount++
            saveState()
            DebugLog.i("SkillCurator: completed in ${state.lastRunDurationMs}ms - $summary")
        } catch (e: Exception) {
            DebugLog.e("SkillCurator: failed - ${e.message}")
        }
    }

    private suspend fun runCurator(): String {
        val skills = registry.getAllSkills()
        var archived = 0
        var cleaned = 0

        for (skill in skills) {
            val isPinned = skill.tags.contains("pinned")
            if (isPinned) continue

            val lastUsed = skill.metadata["lastUsedAt"]?.toLongOrNull() ?: state.lastRunAt
            val age = System.currentTimeMillis() - lastUsed

            when {
                age > ARCHIVE_AFTER_MS -> {
                    // ★ Never delete - only archive
                    archiveSkill(skill.id)
                    cleaned++
                    DebugLog.d("SkillCurator: cleaned skill '${skill.id}' (age=${age / 86400000}d)")
                }
                age > STALE_AFTER_MS -> {
                    archiveSkill(skill.id)
                    archived++
                    DebugLog.d("SkillCurator: archived skill '${skill.id}' (age=${age / 86400000}d)")
                }
            }
        }

        return "archived=$archived, cleaned=$cleaned"
    }

    private fun archiveSkill(skillId: String) {
        val skill = registry.getSkill(skillId) ?: return
        val updated = skill.copy(
            metadata = skill.metadata + ("archivedAt" to System.currentTimeMillis().toString())
        )
        registry.unregisterSkill(skillId)
        registry.registerSkill(updated)
    }

    fun pause() {
        state.paused = true
        saveState()
        DebugLog.i("SkillCurator: paused")
    }

    fun resume() {
        state.paused = false
        saveState()
        DebugLog.i("SkillCurator: resumed")
    }

    private fun loadState(): CuratorState {
        if (!stateFile.exists()) return CuratorState()
        return try {
            val json = stateFile.readText()
            kotlinx.serialization.json.Json.decodeFromString<CuratorStateSerializer>(json).toCuratorState()
        } catch (e: Exception) {
            DebugLog.w("SkillCurator: failed to load state - ${e.message}")
            CuratorState()
        }
    }

    private fun saveState() {
        try {
            stateFile.parentFile?.mkdirs()
            val serializer = CuratorStateSerializer.fromCuratorState(state)
            val json = kotlinx.serialization.json.Json { encodeDefaults = true }.encodeToString(CuratorStateSerializer.serializer(), serializer)
            stateFile.writeText(json)
        } catch (e: Exception) {
            DebugLog.e("SkillCurator: failed to save state - ${e.message}")
        }
    }
}

@kotlinx.serialization.Serializable
private data class CuratorStateSerializer(
    val lastRunAt: Long = 0L,
    val lastRunDurationMs: Long = 0L,
    val lastSummary: String? = null,
    val paused: Boolean = false,
    val runCount: Int = 0,
) {
    fun toCuratorState() = SkillCurator.CuratorState(lastRunAt, lastRunDurationMs, lastSummary, paused, runCount)
    companion object {
        fun fromCuratorState(s: SkillCurator.CuratorState) = CuratorStateSerializer(s.lastRunAt, s.lastRunDurationMs, s.lastSummary, s.paused, s.runCount)
    }
}