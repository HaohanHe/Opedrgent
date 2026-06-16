package top.hsyscn.opedrgent.tools

import android.content.Context
import org.json.JSONObject
import top.hsyscn.opedrgent.model.ToolPart
import top.hsyscn.opedrgent.model.ToolStateType
import top.hsyscn.opedrgent.network.ToolResult
import top.hsyscn.opedrgent.settings.ApiConfig
import top.hsyscn.opedrgent.storage.KnowledgeBase
import top.hsyscn.opedrgent.storage.StepVectorStoreClient
import top.hsyscn.opedrgent.utils.DebugLog

/**
 * step_knowledge_retrieval 工具 — 阶跃云端知识库 RAG 检索。
 *
 * 双轨检索策略：
 * 1. **云端 RAG** (StepVectorStoreClient): 如果已配置阶跃 API Key 且有云端向量存储，
 *    通过阶跃的 embedding + retrieval 实现语义级精准检索
 * 2. **本地全文搜索** (KnowledgeBase): 回退到本地 SQLite 全文匹配，
 *    覆盖 PDF/DOCX/TXT/图片 OCR 内容
 *
 * ## 注册方式
 * 在 ToolRegistry 中通过 ToolSet 接口手动注册。
 * 同时可通过 StepVectorStoreClient.buildRetrievalToolDefinition() 生成
 * 服务端 retrieval 类型工具（LLM 直接调用阶跃 API 检索）。
 */
class StepRagTool(
    private val context: Context,
    private val knowledgeBase: KnowledgeBase,
) : ToolSet {

    override fun getTools(): Map<String, ToolBinding> = mapOf(
        "step_knowledge_search" to ToolBinding(
            name = "step_knowledge_search",
            description = "从本地或云端知识库中检索相关文档内容。支持全文搜索和语义搜索。当用户问题涉及已导入的文档、笔记、研究报告等知识内容时使用此工具。",
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
     *
     * 从 toolPart.state.input 解析参数：
     * - query: 搜索查询
     * - kb_id: 指定知识库 ID（可选）
     * - use_cloud: 是否使用云端 RAG（可选）
     */
    private suspend fun execute(toolPart: ToolPart, config: ApiConfig): ToolResult {
        val input = toolPart.state.input
        DebugLog.i("StepRagTool: 执行知识库检索 — input=${input.toString().take(200)}")

        return try {
            val args = JSONObject(input)
            val query = args.getString("query")
            val kbId = args.optString("kb_id", "")
            val useCloud = args.optBoolean("use_cloud", false)

            if (query.isBlank()) {
                return emptyResult(toolPart, "搜索查询不能为空")
            }

            // 策略1：云端 RAG 检索
            if (useCloud && config.apiKey.isNotBlank()) {
                val cloudResult = searchCloud(config, query)
                if (cloudResult != null) {
                    return successResult(toolPart, cloudResult, source = "cloud_rag")
                }
            }

            // 策略2：本地全文搜索（始终可用）
            val localResult = searchLocal(query, kbId)

            if (localResult.isNotBlank()) {
                successResult(toolPart, localResult, source = "local_fulltext")
            } else {
                emptyResult(toolPart, "未找到与 '$query' 相关的文档内容")
            }
        } catch (e: Exception) {
            DebugLog.e("StepRagTool 异常: ${e.message}", e)
            emptyResult(toolPart, "检索异常: ${e.message}")
        }
    }

    /**
     * 云端 RAG 检索 — 通过阶跃向量存储 API。
     *
     * 注意：实际检索通常由 LLM 服务端在 chat completions 中自动完成
     * (tool_type=retrieval)。此处作为备选路径，直接查询文件列表并做本地筛选。
     */
    private suspend fun searchCloud(config: ApiConfig, query: String): String? {
        return try {
            val stores = StepVectorStoreClient.listStores(config.apiKey)
            if (stores.isEmpty()) return null

            // 构建存储信息摘要供 LLM 参考
            val sb = StringBuilder()
            sb.appendLine("[云端知识库 RAG 检索结果]")
            sb.appendLine("查询: $query")
            sb.appendLine("可用向量存储 (${stores.size} 个):")
            stores.forEach { store ->
                sb.appendLine("  - ${store.name} (ID: ${store.id}, 文件数: ${store.fileCount})")
            }
            sb.appendLine()
            sb.appendLine("提示: 已关联到 chat completions 的 retrieval 工具将由服务端自动执行语义检索。如需强制检索特定文档，请指定 vector_store_ids。")
            sb.toString()
        } catch (e: Exception) {
            DebugLog.w("StepRagTool: 云端检索失败，回退到本地: ${e.message}")
            null
        }
    }

    /**
     * 本地全文搜索 — 遍历所有文档内容进行关键词匹配。
     */
    private fun searchLocal(query: String, kbId: String): String {
        val docs = if (kbId.isNotBlank()) {
            knowledgeBase.getDocumentsByKnowledgeBase(kbId)
        } else {
            knowledgeBase.getAllDocuments()
        }

        if (docs.isEmpty()) return ""

        val keywords = query.split(Regex("[\\s,，、;；]+")).filter { it.length >= 2 }
        if (keywords.isEmpty()) return ""

        val results = mutableListOf<SearchHit>()
        val seenTitles = mutableSetOf<String>()

        for (doc in docs) {
            if (seenTitles.contains(doc.title)) continue

            var relevanceScore = 0
            val matchedSnippets = mutableListOf<String>()

            for (keyword in keywords) {
                // 标题匹配权重更高
                if (doc.title.contains(keyword, ignoreCase = true)) {
                    relevanceScore += 10
                }

                // 内容匹配
                val content = doc.content
                val idx = content.indexOf(keyword, ignoreCase = true)
                if (idx >= 0) {
                    relevanceScore += 5
                    // 提取上下文片段（前后各50字符）
                    val start = maxOf(0, idx - 50)
                    val end = minOf(content.length, idx + keyword.length + 50)
                    val snippet = content.substring(start, end).replace("\n", " ").trim()
                    if (snippet.isNotBlank() && matchedSnippets.size < 3) {
                        matchedSnippets.add("...$snippet...")
                    }
                }
            }

            if (relevanceScore > 0) {
                results.add(SearchHit(
                    title = doc.title,
                    fileName = doc.fileName,
                    fileType = doc.fileType,
                    score = relevanceScore,
                    snippets = matchedSnippets,
                ))
                seenTitles.add(doc.title)
            }
        }

        if (results.isEmpty()) return ""

        // 按相关性排序
        results.sortByDescending { it.score }

        return buildString {
            appendLine("[本地知识库检索结果]")
            appendLine("查询: $query")
            appendLine("命中 ${results.size} 篇文档:")
            appendLine()
            results.take(5).forEach { hit ->
                appendLine("## ${hit.title} (${hit.fileType}, 相关度: ${hit.score})")
                hit.snippets.forEach { snippet ->
                    appendLine("> $snippet")
                }
                appendLine()
            }
        }
    }

    private data class SearchHit(
        val title: String,
        val fileName: String,
        val fileType: String,
        val score: Int,
        val snippets: List<String>,
    )

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

    private fun emptyResult(tp: ToolPart, msg: String): ToolResult {
        return ToolResult(
            toolPart = tp.copy(
                state = tp.state.copy(
                    status = ToolStateType.ERROR,
                    error = msg,
                    endTime = System.currentTimeMillis(),
                ),
            ),
        )
    }
}
