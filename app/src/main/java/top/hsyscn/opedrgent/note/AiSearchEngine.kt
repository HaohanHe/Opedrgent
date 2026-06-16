package top.hsyscn.opedrgent.note

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.hsyscn.opedrgent.model.ChatMessage
import top.hsyscn.opedrgent.model.Role
import top.hsyscn.opedrgent.network.LlmClient
import top.hsyscn.opedrgent.settings.ApiSettings
import top.hsyscn.opedrgent.utils.DebugLog

class AiSearchEngine(
    private val noteDao: NoteDao,
    private val llmClient: LlmClient,
    private val apiSettings: ApiSettings,
) {
    suspend fun search(query: String): List<AiSearchResult> = withContext(Dispatchers.IO) {
        val allNotes = noteDao.getAllNotes()
        if (allNotes.isEmpty()) {
            return@withContext emptyList()
        }

        val apiConfig = apiSettings.getApiConfig()
        if (apiConfig == null) {
            DebugLog.w("AiSearchEngine: API config not available, falling back to text search")
            return@withContext emptyList()
        }

        val prompt = buildString {
            appendLine("用户搜索问题：$query")
            appendLine()
            appendLine("以下是所有笔记，请判断每条笔记与搜索问题的相关程度（0-100），并返回最相关的笔记ID列表：")
            allNotes.forEach { note ->
                val preview = note.content.take(200).replace("\n", " ")
                appendLine("[${note.id}] ${note.title}: $preview")
            }
            appendLine()
            appendLine("请只返回相关笔记的ID，格式：ID1,ID2,ID3（按相关度从高到低排序）")
        }

        try {
            val response = llmClient.chatCompletions(
                config = apiConfig,
                system = "你是一个笔记搜索助手，只返回笔记ID列表，不要有任何解释。",
                messages = listOf(
                    ChatMessage(
                        role = Role.USER,
                        content = prompt,
                        createdAt = System.currentTimeMillis(),
                    ),
                ),
            )

            val relevantIds = response.trim()
                .replace(Regex("[^0-9,]"), "")
                .split(",")
                .mapNotNull { it.trim().toLongOrNull() }
                .distinct()

            relevantIds.mapNotNull { id ->
                allNotes.find { it.id == id }?.let { note ->
                    val relevance = (100 - relevantIds.indexOf(id) * 10).coerceAtLeast(10)
                    AiSearchResult(note, relevance)
                }
            }
        } catch (e: Exception) {
            DebugLog.e("AiSearchEngine failed: ${e.message}", e)
            emptyList()
        }
    }
}

data class AiSearchResult(
    val note: Note,
    val relevance: Int,
)
