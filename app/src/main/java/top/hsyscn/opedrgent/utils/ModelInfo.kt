package top.hsyscn.opedrgent.utils

data class ModelInfo(
    val modelId: String,
    val provider: String,
) {
    val isGPTFamily: Boolean by lazy {
        modelId.contains("gpt", ignoreCase = true) ||
        modelId.contains("codex", ignoreCase = true)
    }

    val isClaudeFamily: Boolean by lazy {
        modelId.contains("claude", ignoreCase = true) ||
        modelId.contains("anthropic", ignoreCase = true)
    }

    val isQwenFamily: Boolean by lazy {
        modelId.contains("qwen", ignoreCase = true) ||
        modelId.contains("tongyi", ignoreCase = true) ||
        modelId.contains("dashscope", ignoreCase = true)
    }

    val isDeepSeekFamily: Boolean by lazy {
        modelId.contains("deepseek", ignoreCase = true)
    }

    val isGLMFamily: Boolean by lazy {
        modelId.contains("glm", ignoreCase = true) ||
        modelId.contains("zhipu", ignoreCase = true) ||
        modelId.contains("chatglm", ignoreCase = true)
    }

    val isMiMoFamily: Boolean by lazy {
        modelId.contains("mimo", ignoreCase = true) ||
        provider.contains("xiaomimimo", ignoreCase = true) ||
        provider.contains("mimo", ignoreCase = true)
    }

    val isOpenSource: Boolean by lazy {
        !isGPTFamily && !isClaudeFamily && !isMiMoFamily && (provider.equals("local", ignoreCase = true) ||
            provider.equals("ollama", ignoreCase = true) ||
            provider.equals("vllm", ignoreCase = true))
    }

    val needsToolEnforcement: Boolean by lazy {
        isGPTFamily || isDeepSeekFamily || isOpenSource || isMiMoFamily
    }

    val needsPathGuidance: Boolean by lazy {
        isQwenFamily || isGLMFamily || isOpenSource || isMiMoFamily
    }
}
