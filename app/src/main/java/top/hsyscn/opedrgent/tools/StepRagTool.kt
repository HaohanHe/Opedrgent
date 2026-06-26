package top.hsyscn.opedrgent.tools

import android.content.Context
import org.json.JSONObject
import top.hsyscn.opedrgent.model.ToolPart
import top.hsyscn.opedrgent.model.ToolStateType
import top.hsyscn.opedrgent.network.ToolResult
import top.hsyscn.opedrgent.network.emptyResult
import top.hsyscn.opedrgent.settings.ApiConfig
import top.hsyscn.opedrgent.storage.HybridSearchEngine
import top.hsyscn.opedrgent.storage.KnowledgeBase
import top.hsyscn.opedrgent.utils.DebugLog

/**
 * step_knowledge_search 工具 — 知识库混合检索 (BM25 + 云端 RAG)。
 *
 * 双轨检索策略：
 * 1. **本地 BM25** (HybridSearchEngine): 自实现 BM25 关键词检索 + RRF 融合排序，
 *    覆盖 PDF/DOCX/TXT/图片 OCR 内容，无需网络
 * 2. **云端 RAG** (StepVectorStoreClient): 如果已配置阶跃 API Key 且有云端向量存储，
 *    通过阶跃的 embedding + retrieval 实现语义级精准检索
 *
 * ## 检索流程
 * ```
 * 用户查询
 *   ├─→ BM25 本地关键词搜索 (HybridSearchEngine)
 *   │    → 得分: bm25_score (0~1)
 *   │
 *   └─→ 云端 RAG 向量检索 (StepVectorStoreClient)
 *        → 得分: relevance_score (0~1)
 *
 *              ↓ Reciprocal Rank Fusion (RRF)
 *         最终融合结果 (按综合得分排序)
 * ```
 *
 * ## 注册方式
 * 在 ToolRegistry 中通过 ToolSet 接口手动注册。
 */
class StepRagTool(
    private val context: Context,
    private val knowledgeBase: KnowledgeBase,
) : ToolSet {

    private val hybridSearchEngine = HybridSearchEngine(knowledgeBase)

    override fun getTools(): Map<String, ToolBinding> = mapOf(
        "step_knowledge_search" to ToolBinding(
            name = "step_knowledge_search",
            description = "从本地或云端知识库中检索相关文档内容。使用 BM25 关键词匹配 + 云端 RAG 语义检索的混合排序策略。当用户问题涉及已导入的文档、笔记、研究报告等知识内容时使用此工具。",
            parameters = JSONObject("""{
                "type": "object",
                "properties": {
                    "query": {
                        "type": "string",
                        "description": "搜索查询关键词或问题"
                    },
                    "kb_id": {
                        "type": "string",
                        "description": "指定知识库 ID（可选，默认搜索全部）"
                    },
                    "use_cloud": {
                        "type": "boolean",
                        "description": "是否优先使用云端 RAG（需要配置阶跃 API Key）"
                    }
                },
                "required": ["query"]
            }"""),
            invoker = { toolPart, config, _, _ -> execute(toolPart, config) },
        ),
    )

    /**
     * 执行知识库检索。
     */
    private suspend fun execute(toolPart: ToolPart, config: ApiConfig): ToolResult {
        val input = toolPart.state.input
        DebugLog.i("StepRagTool: 执行知识库检索 — input=${input.toString().take(200)}")

        return try {
            val args = JSONObject(input)
            val query = args.getString("query")
            val kbId = args.optString("kb_id", "").ifBlank { null }
            val useCloud = args.optBoolean("use_cloud", false)

            if (query.isBlank()) {
                return emptyResult(toolPart, "搜索查询不能为空")
            }

            // 使用混合搜索引擎 (BM25 + RRF 融合)
            val apiKey = if (useCloud) config.apiKey else null
            val summary = hybridSearchEngine.hybridSearch(
                apiKey = apiKey,
                query = query,
                kbId = kbId,
                topK = HybridSearchEngine.DEFAULT_TOP_K,
            )

            if (summary.results.isEmpty()) {
                return emptyResult(toolPart, "未找到与 '$query' 相关的文档内容")
            }

            // 格式化结果供 LLM 使用
            val output = formatSearchResults(query, summary)

            successResult(toolPart, output, source = summary.searchMode)
        } catch (e: Exception) {
            DebugLog.e("StepRagTool 异常: ${e.message}", e)
            emptyResult(toolPart, "检索异常: ${e.message}")
        }
    }

    /**
     * 格式化搜索结果为 LLM 可读的文本。
     */
    private fun formatSearchResults(
        query: String,
        summary: HybridSearchEngine.SearchSummary,
    ): String = buildString {
        appendLine("[知识库混合检索结果]")
        appendLine("查询: $query")
        appendLine("检索模式: ${summary.searchMode}")
        appendLine("本地命中: ${summary.localCount} 篇, 云端命中: ${summary.cloudCount} 篇")
        appendLine("融合结果: ${summary.totalMatched} 篇 (耗时 ${summary.executionTimeMs}ms)")
        appendLine()

        summary.results.forEachIndexed { idx, result ->
            appendLine("## ${idx + 1}. ${result.title}")
            appendLine("   来源: ${result.source} | BM25: ${"%.2f".format(result.bm25Score)} | RAG: ${"%.2f".format(result.ragScore)} | 综合: ${"%.2f".format(result.fusedScore)}")
            if (result.highlights.isNotEmpty()) {
                appendLine("   高亮片段:")
                result.highlights.forEach { snippet ->
                    appendLine("   > $snippet")
                }
            }
            appendLine()
        }
    }

    private fun successResult(tp: ToolPart, text: String, source: String): ToolResult {
        return ToolResult(
            toolPart = tp.copy(
                state = tp.state.copy(
                    status = ToolStateType.COMPLETED,
                    output = text,
                    endTime = System.currentTimeMillis(),
                ),
            ),
        )
    }
}
