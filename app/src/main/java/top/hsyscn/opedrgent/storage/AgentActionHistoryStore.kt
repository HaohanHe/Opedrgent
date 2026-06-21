package top.hsyscn.opedrgent.storage

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.File

/**
 * Mobile Agent 操作历史与任务模板持久化。
 *
 * ## 存储结构
 * - 文件: `agent_action_history.json` (应用私有目录)
 * - 格式: JSON 数组，每个元素是一个 [TaskRecord]
 *
 * ## 用途
 * 1. **操作历史**: 记录每次 Mobile Agent 任务执行的全过程，便于审计与调试
 * 2. **任务模板**: 用户可将成功任务保存为模板，后续按名称回放
 * 3. **失败分析**: 记录失败步骤，帮助优化 prompt 或识别不可达目标
 *
 * ## 线程安全
 * 所有公开方法通过 [synchronized] 加锁，可在多协程环境下安全调用。
 */
class AgentActionHistoryStore(context: Context) {

    private val file = File(context.filesDir, "agent_action_history.json")
    private val lock = Any()

    /**
     * 单次任务执行记录。
     */
    data class TaskRecord(
        val id: String,
        val task: String,
        val templateName: String? = null,   // 若保存为模板，记录模板名
        val createdAt: Long,
        val success: Boolean,
        val totalRounds: Int,
        val totalSteps: Int,
        val successfulSteps: Int,
        val actions: List<ActionRecord>,    // 每步动作记录
        val finalReport: String,
        val finalScreenDescription: String? = null,
    )

    /**
     * 单步动作记录。
     */
    data class ActionRecord(
        val round: Int,
        val step: Int,
        val action: String,
        val target: String,
        val detail: String,
        val success: Boolean,
        val message: String,
        val timestamp: Long,
    )

    /**
     * 保存一条任务记录。
     */
    fun save(record: TaskRecord) {
        synchronized(lock) {
            val all = loadAllInternal().toMutableList()
            all.add(record)
            // 限制历史最多 200 条，超出时删除最旧的
            if (all.size > MAX_HISTORY) {
                all.sortBy { it.createdAt }
                val toRemove = all.size - MAX_HISTORY
                repeat(toRemove) { all.removeAt(0) }
            }
            saveAllInternal(all)
        }
    }

    /**
     * 列出所有历史记录（按时间倒序）。
     */
    fun listAll(): List<TaskRecord> {
        synchronized(lock) {
            return loadAllInternal().sortedByDescending { it.createdAt }
        }
    }

    /**
     * 列出所有已保存为模板的任务（按名称排序）。
     */
    fun listTemplates(): List<TaskRecord> {
        synchronized(lock) {
            return loadAllInternal()
                .filter { it.templateName != null }
                .distinctBy { it.templateName }
                .sortedBy { it.templateName }
        }
    }

    /**
     * 按模板名查找记录。
     */
    fun findTemplate(name: String): TaskRecord? {
        synchronized(lock) {
            return loadAllInternal()
                .filter { it.templateName == name }
                .maxByOrNull { it.createdAt }
        }
    }

    /**
     * 将已有任务记录保存为模板（更新 templateName 字段）。
     */
    fun saveAsTemplate(recordId: String, templateName: String): Boolean {
        synchronized(lock) {
            val all = loadAllInternal().toMutableList()
            val idx = all.indexOfFirst { it.id == recordId }
            if (idx < 0) return false
            all[idx] = all[idx].copy(templateName = templateName)
            saveAllInternal(all)
            return true
        }
    }

    /**
     * 删除指定记录。
     */
    fun delete(recordId: String): Boolean {
        synchronized(lock) {
            val all = loadAllInternal().toMutableList()
            val filtered = all.filter { it.id != recordId }
            if (filtered.size == all.size) return false
            saveAllInternal(filtered)
            return true
        }
    }

    /**
     * 删除模板（仅清除 templateName 字段，不删除记录本身）。
     */
    fun deleteTemplate(name: String): Boolean {
        synchronized(lock) {
            val all = loadAllInternal().toMutableList()
            var changed = false
            for (i in all.indices) {
                if (all[i].templateName == name) {
                    all[i] = all[i].copy(templateName = null)
                    changed = true
                }
            }
            if (changed) saveAllInternal(all)
            return changed
        }
    }

    /**
     * 清空所有历史。
     */
    fun clear() {
        synchronized(lock) {
            file.delete()
        }
    }

    // ---- 序列化 ----

    private fun loadAllInternal(): List<TaskRecord> {
        if (!file.exists()) return emptyList()
        return try {
            val text = file.readText()
            if (text.isBlank()) return emptyList()
            val arr = JSONArray(text)
            (0 until arr.length()).map { i ->
                arr.getJSONObject(i).toTaskRecord()
            }
        } catch (e: Exception) {
            DebugLog.e(TAG, "加载历史失败: ${e.message}", e)
            emptyList()
        }
    }

    private fun saveAllInternal(records: List<TaskRecord>) {
        try {
            val arr = JSONArray()
            records.forEach { arr.put(it.toJson()) }
            file.writeText(arr.toString())
        } catch (e: Exception) {
            DebugLog.e(TAG, "保存历史失败: ${e.message}", e)
        }
    }

    private fun JSONObject.toTaskRecord(): TaskRecord {
        val actionsArr = optJSONArray("actions") ?: JSONArray()
        val actions = (0 until actionsArr.length()).map { i ->
            val a = actionsArr.getJSONObject(i)
            ActionRecord(
                round = a.optInt("round", 0),
                step = a.optInt("step", 0),
                action = a.optString("action", ""),
                target = a.optString("target", ""),
                detail = a.optString("detail", ""),
                success = a.optBoolean("success", false),
                message = a.optString("message", ""),
                timestamp = a.optLong("timestamp", 0),
            )
        }
        return TaskRecord(
            id = optString("id", ""),
            task = optString("task", ""),
            templateName = optString("templateName", "").ifBlank { null },
            createdAt = optLong("createdAt", 0),
            success = optBoolean("success", false),
            totalRounds = optInt("totalRounds", 0),
            totalSteps = optInt("totalSteps", 0),
            successfulSteps = optInt("successfulSteps", 0),
            actions = actions,
            finalReport = optString("finalReport", ""),
            finalScreenDescription = optString("finalScreenDescription", "").ifBlank { null },
        )
    }

    private fun TaskRecord.toJson(): JSONObject {
        val actionsArr = JSONArray()
        actions.forEach { a ->
            actionsArr.put(JSONObject().apply {
                put("round", a.round)
                put("step", a.step)
                put("action", a.action)
                put("target", a.target)
                put("detail", a.detail)
                put("success", a.success)
                put("message", a.message)
                put("timestamp", a.timestamp)
            })
        }
        return JSONObject().apply {
            put("id", id)
            put("task", task)
            if (templateName != null) put("templateName", templateName)
            put("createdAt", createdAt)
            put("success", success)
            put("totalRounds", totalRounds)
            put("totalSteps", totalSteps)
            put("successfulSteps", successfulSteps)
            put("actions", actionsArr)
            put("finalReport", finalReport)
            if (finalScreenDescription != null) put("finalScreenDescription", finalScreenDescription)
        }
    }

    companion object {
        private const val TAG = "AgentActionHistory"
        private const val MAX_HISTORY = 200
    }
}
