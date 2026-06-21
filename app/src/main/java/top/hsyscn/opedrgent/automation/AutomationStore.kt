package top.hsyscn.opedrgent.automation

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class AutomationStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("opedrgent_automations", Context.MODE_PRIVATE)
    private val lock = Any()

    fun list(): List<Automation> {
        synchronized(lock) {
            val raw = prefs.getString("automations", null) ?: return emptyList()
            val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                parse(o)
            }.sortedByDescending { it.updatedAt }
        }
    }

    fun upsert(a: Automation) {
        val all = list().toMutableList()
        val idx = all.indexOfFirst { it.id == a.id }
        if (idx >= 0) all[idx] = a else all.add(a)
        saveAll(all)
    }

    fun delete(id: String) {
        saveAll(list().filterNot { it.id == id })
        cancelWork(id)
    }

    fun setEnabled(id: String, enabled: Boolean) {
        val all = list().toMutableList()
        val idx = all.indexOfFirst { it.id == id }
        if (idx < 0) return
        val cur = all[idx]
        val next = cur.copy(enabled = enabled, updatedAt = System.currentTimeMillis())
        all[idx] = next
        saveAll(all)
        if (enabled) scheduleWork(next) else cancelWork(id)
    }

    fun scheduleAllEnabled() {
        list().filter { it.enabled }.forEach { scheduleWork(it) }
    }

    /** 记录自动化执行结果 */
    fun recordExecution(id: String, success: Boolean, error: String? = null) {
        val all = list().toMutableList()
        val idx = all.indexOfFirst { it.id == id }
        if (idx < 0) return
        val cur = all[idx]
        all[idx] = cur.copy(
            lastExecutedAt = System.currentTimeMillis(),
            executionCount = cur.executionCount + 1,
            lastError = if (success) null else (error ?: "未知错误"),
            updatedAt = System.currentTimeMillis(),
        )
        saveAll(all)
    }

    fun createHeartbeat(name: String, intervalMinutes: Long, targetSessionId: String?): Automation {
        val now = System.currentTimeMillis()
        val a = Automation(
            name = name.ifBlank { "心跳整理" },
            enabled = true,
            intervalMinutes = intervalMinutes.coerceAtLeast(15),
            kind = AutomationKind.HEARTBEAT_NOTES,
            targetSessionId = targetSessionId,
            prompt = null,
            createdAt = now,
            updatedAt = now,
        )
        upsert(a)
        scheduleWork(a)
        return a
    }

    fun createPrompt(name: String, intervalMinutes: Long, targetSessionId: String?, prompt: String): Automation {
        val now = System.currentTimeMillis()
        val a = Automation(
            name = name.ifBlank { "定时任务" },
            enabled = true,
            intervalMinutes = intervalMinutes.coerceAtLeast(15),
            kind = AutomationKind.RUN_PROMPT,
            targetSessionId = targetSessionId,
            prompt = prompt,
            createdAt = now,
            updatedAt = now,
        )
        upsert(a)
        scheduleWork(a)
        return a
    }

    private fun saveAll(all: List<Automation>) {
        synchronized(lock) {
            val arr = JSONArray()
            all.forEach { arr.put(serialize(it)) }
            prefs.edit().putString("automations", arr.toString()).apply()
        }
    }

    private fun scheduleWork(a: Automation) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val req = PeriodicWorkRequestBuilder<AutomationWorker>(a.intervalMinutes, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .addTag("automation:${a.id}")
            .setInputData(AutomationWorker.inputData(a.id))
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "automation:${a.id}",
            ExistingPeriodicWorkPolicy.UPDATE,
            req,
        )
    }

    private fun cancelWork(id: String) {
        WorkManager.getInstance(context).cancelUniqueWork("automation:$id")
    }

    private fun parse(o: JSONObject): Automation? {
        val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
        val name = o.optString("name").trim().takeIf { it.isNotBlank() } ?: return null
        val enabled = o.optBoolean("enabled", false)
        val intervalMinutes = o.optLong("intervalMinutes", 60L)
        val kind = runCatching { AutomationKind.valueOf(o.optString("kind")) }.getOrNull() ?: return null
        val targetSessionId = o.optString("targetSessionId").trim().takeIf { it.isNotBlank() }
        val prompt = o.optString("prompt").takeIf { it.isNotBlank() }
        val createdAt = o.optLong("createdAt", 0L)
        val updatedAt = o.optLong("updatedAt", createdAt)
        return Automation(
            id = id,
            name = name,
            enabled = enabled,
            intervalMinutes = intervalMinutes,
            kind = kind,
            targetSessionId = targetSessionId,
            prompt = prompt,
            createdAt = createdAt,
            updatedAt = updatedAt,
            lastExecutedAt = o.optLong("lastExecutedAt", 0),
            executionCount = o.optInt("executionCount", 0),
            lastError = o.optString("lastError").takeIf { it.isNotBlank() },
        )
    }

    private fun serialize(a: Automation): JSONObject {
        return JSONObject()
            .put("id", a.id)
            .put("name", a.name)
            .put("enabled", a.enabled)
            .put("intervalMinutes", a.intervalMinutes)
            .put("kind", a.kind.name)
            .put("targetSessionId", a.targetSessionId)
            .put("prompt", a.prompt)
            .put("createdAt", a.createdAt)
            .put("updatedAt", a.updatedAt)
            .put("lastExecutedAt", a.lastExecutedAt)
            .put("executionCount", a.executionCount)
            .putOpt("lastError", a.lastError)
    }
}

