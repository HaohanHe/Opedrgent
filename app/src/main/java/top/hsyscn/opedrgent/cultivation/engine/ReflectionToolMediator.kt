package top.hsyscn.opedrgent.cultivation.engine

import org.json.JSONArray
import top.hsyscn.opedrgent.cultivation.mirror.ReflectionToolCall
import top.hsyscn.opedrgent.cultivation.mirror.ReflectionToolProtocol
import top.hsyscn.opedrgent.cultivation.model.PersonaProfile
import top.hsyscn.opedrgent.cultivation.model.PersonaTrait
import top.hsyscn.opedrgent.cultivation.model.PersonaTraitStatus
import top.hsyscn.opedrgent.cultivation.model.ReflectionLens
import top.hsyscn.opedrgent.cultivation.store.PersonaProfileStore
import top.hsyscn.opedrgent.cultivation.store.ReflectionStore
import top.hsyscn.opedrgent.storage.HippocampusIndex
import top.hsyscn.opedrgent.storage.IndexedItem
import top.hsyscn.opedrgent.storage.SourceType
import top.hsyscn.opedrgent.utils.DebugLog

/**
 * 本地工具执行器。
 *
 * 执行两类工具，数据全部来自本机存储，不发起任何网络请求：
 * - 只读工具（[ReflectionToolProtocol.RECENT]/[ReflectionToolProtocol.MEMORY]）：把模型意图翻译成确定性的本地查询，
 *   结果回填给模型；
 * - 画像写入（[ReflectionToolProtocol.PERSONA_UPDATE]）：把模型随报告提交的画像静默落库。
 *
 * 关键纪律：任何工具失败都只说明、绝不抛断主流程——模型仍可只依据本次转写完成分析，
 * 保证“工具可选、工程兜底、不更差”。
 */
class ReflectionToolMediator(
    private val store: ReflectionStore,
    private val personaStore: PersonaProfileStore,
    private val hippocampusProvider: () -> HippocampusIndex?,
) {
    /** 执行一批只读工具调用，返回拼好的工具结果文本；无有效结果时返回空串。 */
    suspend fun execute(calls: List<ReflectionToolCall>, transcript: String): String {
        val sb = StringBuilder()
        calls.forEach { call ->
            runCatching { dispatch(call, transcript, sb) }
                .onFailure {
                    DebugLog.w(TAG, "工具 ${call.name} 取数失败：${it.message}")
                    sb.appendLine("【工具 ${call.name} 取数失败，忽略它并只依据本次转写分析】")
                }
        }
        return sb.toString()
    }

    /** 应用模型随报告提交的画像更新，返回确认信息；失败不阻断报告。 */
    suspend fun applyPersona(call: ReflectionToolCall): String = runCatching {
        val a = call.args
        val actual = traits(a.optJSONArray("actual_self"))
        val aspired = traits(a.optJSONArray("aspired_self"))
        val open = strings(a.optJSONArray("open_questions"))
        if (actual.isEmpty() && aspired.isEmpty() && open.isEmpty()) {
            return@runCatching "画像无内容、未更新"
        }
        val saved = personaStore.save(
            PersonaProfile(actualSelf = actual, aspiredSelf = aspired, openQuestions = open),
        )
        "画像已更新到版本 ${saved.version}"
    }.getOrElse {
        DebugLog.w(TAG, "画像更新失败：${it.message}")
        "画像更新失败，已忽略：${it.message}"
    }

    private suspend fun dispatch(call: ReflectionToolCall, transcript: String, sb: StringBuilder) {
        when (call.name) {
            ReflectionToolProtocol.RECENT -> recallRecent(call, sb)
            ReflectionToolProtocol.MEMORY -> recallMemory(call, transcript, sb)
        }
    }

    private suspend fun recallRecent(call: ReflectionToolCall, sb: StringBuilder) {
        val limit = ReflectionToolProtocol.clampLimit(call.args.optInt("limit", 0), 1, 8, 5)
        val rows = store.listRecent(limit, ReflectionLens.CRITIQUE)
        sb.appendLine("【recall_recent：最近 ${rows.size} 次自我复盘结论】")
        if (rows.isEmpty()) {
            sb.appendLine("（暂无历史复盘，这可能是第一次）")
            return
        }
        rows.forEach { record ->
            record.critique?.overall?.take(140)?.takeIf { it.isNotBlank() }?.let { sb.appendLine("- $it") }
        }
    }

    private suspend fun recallMemory(call: ReflectionToolCall, transcript: String, sb: StringBuilder) {
        val limit = ReflectionToolProtocol.clampLimit(call.args.optInt("limit", 0), 1, 5, 4)
        val hip = hippocampusProvider()
        if (hip == null) {
            sb.appendLine("【recall_memory：长期记忆当前不可用，忽略即可】")
            return
        }
        val given = call.args.optString("keyword", "").trim()
        val keywords = if (given.isNotBlank()) listOf(given)
        else hip.extractKeywords("", transcript).split(",").map { it.trim() }.filter { it.isNotBlank() }.take(6)
        if (keywords.isEmpty()) {
            sb.appendLine("【recall_memory：未提取到可用检索词，忽略即可】")
            return
        }
        val picked = LinkedHashMap<String, IndexedItem>()
        for (token in keywords) {
            hip.query(token, limit = limit).forEach { item ->
                // 排除修炼自身，避免与 recall_recent 重复
                if (item.sourceType != SourceType.CULTIVATION && item.id !in picked) picked[item.id] = item
            }
            if (picked.size >= 5) break
        }
        sb.appendLine("【recall_memory：与本次表达相关的长期背景，仅供识别模式、不要逐条复述】")
        if (picked.isEmpty()) {
            sb.appendLine("（未检索到相关长期记忆）")
            return
        }
        picked.values.take(5).forEach { item ->
            val brief = item.summary.replace("\n", " ").take(80)
            sb.appendLine("- 【${item.sourceType.label}】${item.title}：$brief")
        }
    }

    private fun traits(arr: JSONArray?): List<PersonaTrait> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val text = o.optString("text", "").trim()
            if (text.isBlank()) return@mapNotNull null
            PersonaTrait(
                text = text,
                evidence = o.optString("evidence", "").trim(),
                sourceLabel = o.optString("source", "").trim(),
                observedAt = o.optLong("observedAt", System.currentTimeMillis()),
                status = PersonaTraitStatus.fromName(o.optString("status", "active")),
            )
        }
    }

    private fun strings(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { arr.getString(it) }
            .map { it.trim() }.filter { it.isNotBlank() }
    }

    companion object {
        private const val TAG = "ReflectionToolMediator"
    }
}
