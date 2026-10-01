package top.hsyscn.opedrgent.cultivation.engine

import top.hsyscn.opedrgent.cultivation.mirror.ReflectionToolCall
import top.hsyscn.opedrgent.cultivation.mirror.ReflectionToolProtocol
import top.hsyscn.opedrgent.cultivation.model.ReflectionLens
import top.hsyscn.opedrgent.cultivation.store.ReflectionStore
import top.hsyscn.opedrgent.storage.HippocampusIndex
import top.hsyscn.opedrgent.storage.IndexedItem
import top.hsyscn.opedrgent.storage.SourceType
import top.hsyscn.opedrgent.utils.DebugLog

/**
 * 本地工具执行器（逻辑重设计 P1）。
 *
 * 只执行 [ReflectionToolProtocol] 声明的两个只读工具，数据全部来自本机存储与海马索引，
 * 不发起任何网络请求。它把模型的工具意图翻译成确定性的本地查询，并把结果拼成可回填给模型的文本。
 *
 * 关键纪律：任何工具取数失败都只在结果里说明、绝不抛断主流程——模型仍可只依据本次转写完成分析，
 * 保证“工具可选、工程兜底、不更差”。
 */
class ReflectionToolMediator(
    private val store: ReflectionStore,
    private val hippocampusProvider: () -> HippocampusIndex?,
) {
    /** 执行一批工具调用，返回拼好的工具结果文本；无有效结果时返回空串。 */
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

    companion object {
        private const val TAG = "ReflectionToolMediator"
    }
}
