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
    val recommandedFor: String = "",
    val minMemoryMb: Long = 0,
    val maxContextLength: Int = 4096,
    val supportsImage: Boolean = false,
    val supportsAudio: Boolean = false,
    val supportsThinking: Boolean = false,
    val supportsSpecDec: Boolean = false,
    val preferGpu: Boolean = true,
    val fallbackUrl: String? = null,
    /**
     * 完整文件的可信上游 SHA-256（十六进制，大小写不敏感）。
     * 为 null 时维持现有“体积 90%”校验；当前清单暂无可靠上游哈希，一律保持 null，严禁臆造。
     */
    val expectedSha256: String? = null,
)

object AvailableLocalModels {
    val MODELS = listOf(
        LocalModelInfo(
            id = "gemma-4-e2b-it",
            displayName = "Gemma 4 E2B (2B)",
            description = "轻量高效，适合日常对话和简单工具调用。支持GPU加速、SpecDec",
            sizeMb = 2583,
            // ModelScope resolve/master/ 会 302 到 cdn-lfs-cn-1.modelscope.cn，该 CDN 支持 HTTP Range 断点续传
            downloadUrl = "https://www.modelscope.cn/models/litert-community/gemma-4-E2B-it-litert-lm/resolve/master/gemma-4-E2B-it.litertlm",
            fallbackUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
            fileName = "gemma-4-E2B-it.litertlm",
            supportsFunctionCalling = true,
            maxTokens = 2048,
            recommandedFor = "日常对话、离线助手、Agent 任务",
            minMemoryMb = 4000,
            maxContextLength = 32000,
            supportsImage = true,
            supportsAudio = true,
            supportsThinking = true,
            supportsSpecDec = true,
            preferGpu = true,
        ),
        LocalModelInfo(
            id = "gemma-4-e4b-it",
            displayName = "Gemma 4 E4B (4B)",
            description = "更强能力，支持复杂推理和多步工具调用。支持GPU加速、SpecDec",
            sizeMb = 3654,
            // ModelScope resolve/master/ 会 302 到 cdn-lfs-cn-1.modelscope.cn，该 CDN 支持 HTTP Range 断点续传
            downloadUrl = "https://www.modelscope.cn/models/litert-community/gemma-4-E4B-it-litert-lm/resolve/master/gemma-4-E4B-it.litertlm",
            fallbackUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
            fileName = "gemma-4-E4B-it.litertlm",
            supportsFunctionCalling = true,
            maxTokens = 4096,
            recommandedFor = "复杂推理、Agent 任务",
            minMemoryMb = 6000,
            maxContextLength = 32000,
            supportsImage = true,
            supportsAudio = true,
            supportsThinking = true,
            supportsSpecDec = true,
            preferGpu = true,
        ),
        LocalModelInfo(
            id = "gemma-3-1b-it",
            displayName = "Gemma 3 1B (int4)",
            description = "超轻量级，低配设备可用。int4量化版，仅557MB",
            sizeMb = 557,
            // 国内优先走 ModelScope；HuggingFace 作为海外备用源
            downloadUrl = "https://www.modelscope.cn/models/litert-community/Gemma3-1B-IT/resolve/master/gemma3-1b-it-int4.litertlm",
            fallbackUrl = "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.litertlm",
            fileName = "gemma3-1b-it-int4.litertlm",
            supportsFunctionCalling = false,
            maxTokens = 2048,
            recommandedFor = "快速响应、低内存设备",
            minMemoryMb = 2000,
            maxContextLength = 4096,
        )
    )

    fun findById(id: String): LocalModelInfo? = MODELS.find { it.id == id }

    fun buildInferenceConfig(info: LocalModelInfo): LlmInferenceConfig {
        return LlmInferenceConfig(
            backend = if (info.preferGpu) com.google.ai.edge.litertlm.Backend.GPU() else com.google.ai.edge.litertlm.Backend.CPU(),
            maxContextLength = info.maxContextLength,
            maxTokens = info.maxTokens,
            enableThinking = info.supportsThinking,
            enableSpeculativeDecoding = info.supportsSpecDec,
            supportsImage = info.supportsImage,
            supportsAudio = info.supportsAudio,
        )
    }
}
