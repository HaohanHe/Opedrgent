package top.hsyscn.opedrgent.automation

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import top.hsyscn.opedrgent.model.ArtifactKind
import top.hsyscn.opedrgent.model.ChatMessage
import top.hsyscn.opedrgent.model.Role
import top.hsyscn.opedrgent.network.HttpClients
import top.hsyscn.opedrgent.network.LlmClient
import top.hsyscn.opedrgent.settings.ApiSettings
import top.hsyscn.opedrgent.storage.ResearchStore
import top.hsyscn.opedrgent.utils.PromptBuilder

class AutomationWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    private val http = HttpClients.default
    private val llm = LlmClient(http)
    private val apiSettings = ApiSettings(appContext)
    private val store = ResearchStore(appContext)
    private val automations = AutomationStore(appContext)

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_AUTOMATION_ID) ?: return Result.failure()
        val a = automations.list().firstOrNull { it.id == id } ?: return Result.success()
        if (!a.enabled) return Result.success()
        val config = apiSettings.getApiConfig() ?: return Result.success()

        val sessionId = a.targetSessionId ?: apiSettings.getLastSessionId() ?: return Result.success()
        val session = store.getSession(sessionId) ?: return Result.success()

        return try {
            val result = when (a.kind) {
                AutomationKind.HEARTBEAT_NOTES -> {
                    val prompt = """
输出 Markdown，不要写多余解释。
请更新“Session Notes”，结构固定：
- # Current State
- # Key Findings（尽量标注引用 [S1]/[S2]）
- # Open Questions
- # Next Actions
- # Sources（列出 [S1]..）
""".trimIndent()
                    val system = PromptBuilder.buildSystemPrompt(apiSettings, session)
                    val assistant = llm.chatCompletions(
                        config = config,
                        system = system,
                        messages = listOf(ChatMessage(role = Role.USER, content = prompt, createdAt = System.currentTimeMillis())),
                    ).trim()
                    if (assistant.isNotBlank()) {
                        store.setNotes(sessionId, assistant)
                        store.addArtifact(sessionId, ArtifactKind.NOTES, assistant)
                    }
                    Result.success()
                }
                AutomationKind.RUN_PROMPT -> {
                    val p = a.prompt?.trim().orEmpty()
                    if (p.isBlank()) return Result.success()
                    val system = PromptBuilder.buildSystemPrompt(apiSettings, session)
                    val assistant = llm.chatCompletions(
                        config = config,
                        system = system,
                        messages = listOf(ChatMessage(role = Role.USER, content = p, createdAt = System.currentTimeMillis())),
                    ).trim()
                    if (assistant.isNotBlank()) {
                        store.addMessage(sessionId, Role.USER, p)
                        store.addMessage(sessionId, Role.ASSISTANT, assistant)
                        store.addArtifact(sessionId, ArtifactKind.REPORT, assistant)
                    }
                    Result.success()
                }
            }
            automations.recordExecution(id, success = true)
            result
        } catch (e: Exception) {
            automations.recordExecution(id, success = false, error = e.message)
            Result.retry()
        }
    }

    companion object {
        private const val KEY_AUTOMATION_ID = "automationId"
        fun inputData(id: String): Data = Data.Builder().putString(KEY_AUTOMATION_ID, id).build()
    }
}
