package top.hsyscn.opedrgent.llm

data class LocalModelInfo(
    val id: String,
    val displayName: String,
    val description: String,
    val sizeMb: Long,
    val downloadUrl: String,
    val fileName: String,
    val supportsFunctionCalling: Boolean,
    val maxTokens: Int = 512,
    val recommendedFor: String = "",
)

object AvailableLocalModels {
    val MODELS = listOf(
        LocalModelInfo(
            id = "gemma-4-e2b-it",
            displayName = "Gemma 4 E2B (2B)",
            description = "轻量高效，适合日常对话和简单工具调用。CPU内存约676MB",
            sizeMb = 2583,
            downloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
            fileName = "gemma-4-E2B-it.litertlm",
            supportsFunctionCalling = true,
            maxTokens = 512,
            recommendedFor = "日常对话、离线助手"
        ),
        LocalModelInfo(
            id = "gemma-4-e4b-it",
            displayName = "Gemma 4 E4B (4B)",
            description = "更强能力，支持复杂推理和多步工具调用。需要更多内存",
            sizeMb = 3654,
            downloadUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
            fileName = "gemma-4-E4B-it.litertlm",
            supportsFunctionCalling = true,
            maxTokens = 1024,
            recommendedFor = "复杂推理、Agent 任务"
        ),
        LocalModelInfo(
            id = "gemma-3-1b-it",
            displayName = "Gemma 3 1B",
            description = "超轻量级，低配设备可用。速度最快但能力有限",
            sizeMb = 1005,
            downloadUrl = "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/Gemma3-1B-IT.litertlm",
            fileName = "Gemma3-1B-IT.litertlm",
            supportsFunctionCalling = false,
            maxTokens = 256,
            recommendedFor = "快速响应、低内存设备"
        ),
    )

    fun findById(id: String): LocalModelInfo? = MODELS.find { it.id == id }
}
