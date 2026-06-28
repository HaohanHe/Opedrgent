package top.hsyscn.opedrgent.utils

/**
 * 模型感知的弹性限制配置中心
 *
 * 所有截断/限制值根据模型上下文窗口成比例计算，而非硬编码。
 * 用法：ModelLimits.forContext(maxTokens = 1_000_000)
 */
object ModelLimits {

    /** 默认上下文长度（当模型未知时使用） */
    const val DEFAULT_MAX_TOKENS = 32_000

    // ==================== 工具输出截断 ====================

    /** read_url / web_search 正文截断：占上下文窗口的 5% */
    fun toolOutputMaxChars(maxTokens: Int): Int =
        (maxTokens * 4 * 0.05).toInt().coerceIn(3_000, 50_000) // 4 chars/token

    /** 工具输出摘要预览截断 */
    fun toolOutputPreview(maxTokens: Int): Int =
        (maxTokens * 4 * 0.01).toInt().coerceIn(200, 5_000)

    // ==================== Prompt 记忆注入 ====================

    /** 通用记忆注入上限：占上下文的 5% */
    fun memoryMaxChars(maxTokens: Int): Int =
        (maxTokens * 4 * 0.05).toInt().coerceIn(1_000, 10_000)

    /** 笔记记忆注入上限：占上下文的 3% */
    fun noteMemoryMaxChars(maxTokens: Int): Int =
        (maxTokens * 4 * 0.03).toInt().coerceIn(500, 6_000)

    /** 对话历史记忆注入上限：占上下文的 3% */
    fun conversationMemoryMaxChars(maxTokens: Int): Int =
        (maxTokens * 4 * 0.03).toInt().coerceIn(500, 6_000)

    /** 海马体摘要截断 */
    fun hippocampusSummary(maxTokens: Int): Int =
        (maxTokens * 4 * 0.005).toInt().coerceIn(100, 1_000)

    // ==================== 上下文压缩 ====================

    /** 单条消息 token 上限：占上下文的 25% */
    fun perMessageTokenLimit(maxTokens: Int): Int =
        (maxTokens * 0.25).toInt().coerceAtLeast(1000)

    /** 摘要生成时消息内容截断 */
    fun summaryContentMax(maxTokens: Int): Int =
        (maxTokens * 4 * 0.02).toInt().coerceIn(200, 3_000)

    /** TLDR fallback 截断 */
    fun tldrFallbackMax(maxTokens: Int): Int =
        (maxTokens * 4 * 0.005).toInt().coerceIn(100, 1_000)

    // ==================== 搜索结果 ====================

    /** 搜索摘要截断 */
    fun searchSnippetMax(maxTokens: Int): Int =
        (maxTokens * 4 * 0.02).toInt().coerceIn(500, 5_000)

    // ==================== UI 截断（按屏幕宽度比例） ====================

    /** AI 消息 Markdown 渲染截断：大上下文模型不截断 */
    fun markdownMaxChars(maxTokens: Int): Int =
        if (maxTokens >= 100_000) Int.MAX_VALUE
        else (maxTokens * 4 * 0.3).toInt().coerceIn(2_000, 30_000)

    /** 流式完成后的展示截断 */
    fun streamingDisplayMax(maxTokens: Int): Int =
        if (maxTokens >= 100_000) Int.MAX_VALUE
        else (maxTokens * 4 * 0.3).toInt().coerceIn(2_000, 30_000)

    /** Markdown 渲染最大行数：按上下文比例 */
    fun markdownMaxLines(maxTokens: Int): Int =
        if (maxTokens >= 100_000) 1000
        else (maxTokens / 100).coerceIn(100, 500)

    // ==================== Agent 工具调用轮次 ====================

    /**
     * Agent 工具循环的最大轮次（ResearchState 的 roundsUsed 维度）。
     * 由于 executeOneRound 里 advanceTo 会被调用两次（开始思考 + 工具执行后），
     * 实际允许的工具调用轮数 ≈ maxAgentRounds / 2。
     * 大上下文模型给更多轮次，避免 DeepSeek V4 Flash 等模型在复杂查询时被硬截断。
     */
    fun maxAgentRounds(maxTokens: Int): Int =
        if (maxTokens >= 1_000_000) 24      // 约 12 轮实际工具调用
        else if (maxTokens >= 200_000) 20   // 约 10 轮
        else if (maxTokens >= 128_000) 16   // 约 8 轮
        else 12                             // 约 6 轮

    // ==================== 海马体 / 记忆查询 ====================

    /** 海马体关键词最大数量 */
    fun maxHippocampusKeywords(maxTokens: Int): Int =
        if (maxTokens >= 100_000) 15 else 10

    /** 海马体查询 limit */
    fun hippocampusQueryLimit(maxTokens: Int): Int =
        if (maxTokens >= 100_000) 8 else 5

    /** 海马体索引摘要截断 */
    fun hippocampusIndexSummary(maxTokens: Int): Int =
        (maxTokens * 4 * 0.007).toInt().coerceIn(300, 2_000)

    // ==================== 上下文映射 ====================

    /**
     * 根据模型名推断上下文窗口大小（token 数）。
     * 数据来源：2026年6月各厂商官方文档 + benchlm.ai + tokencalculator.com
     */
    fun inferMaxContextTokens(modelName: String): Int {
        val lower = modelName.lowercase()
        return when {
            // === OpenAI GPT 系列（2026年6月） ===
            lower.contains("gpt-5.5") -> 1_000_000           // GPT-5.5 旗舰，128K output
            lower.contains("gpt-5.4") -> 256_000
            lower.contains("gpt-5.2") || lower.contains("gpt-5-codex") -> 400_000
            lower.contains("gpt-5") -> 256_000                // GPT-5 通用
            lower.contains("gpt-4o") || lower.contains("gpt-4-turbo") -> 128_000
            lower.contains("gpt-4") -> 32_000
            lower.contains("gpt-3.5") -> 16_000

            // === Anthropic Claude 系列（2026年6月） ===
            lower.contains("claude-opus-4.7") || lower.contains("claude-opus-4-7") -> 1_000_000
            lower.contains("claude-sonnet-4.7") || lower.contains("claude-sonnet-4-7") -> 1_000_000
            lower.contains("claude-opus-4.6") || lower.contains("claude-opus-4-6") -> 200_000
            lower.contains("claude-sonnet-4.6") || lower.contains("claude-sonnet-4-6") -> 1_000_000
            lower.contains("claude-opus-4.5") || lower.contains("claude-4.5-opus") -> 200_000
            lower.contains("claude-sonnet-4.5") || lower.contains("claude-sonnet-4-5") -> 200_000
            lower.contains("claude-haiku-4.5") || lower.contains("claude-4.5-haiku") -> 200_000
            lower.contains("claude-opus") -> 200_000
            lower.contains("claude-sonnet") -> 1_000_000
            lower.contains("claude-haiku") -> 200_000
            lower.contains("claude") -> 200_000

            // === Google Gemini 系列（2026年6月） ===
            lower.contains("gemini-3.1") || lower.contains("gemini-3.5") -> 2_000_000  // Gemini 3.1/3.5 = 2M
            lower.contains("gemini-3") -> 1_000_000
            lower.contains("gemini-2.5") -> 1_000_000
            lower.contains("gemini") -> 1_000_000

            // === xAI Grok 系列 ===
            lower.contains("grok-4") -> 256_000
            lower.contains("grok-3.5") || lower.contains("grok-3") -> 256_000
            lower.contains("grok") -> 256_000

            // === DeepSeek 系列 ===
            lower.contains("deepseek-v4-pro") -> 1_000_000    // V4-Pro = 1M, 384K output
            lower.contains("deepseek-v4-flash") -> 1_000_000  // V4-Flash = 1M
            lower.contains("deepseek-v4") -> 1_000_000
            lower.contains("deepseek-v3.2") -> 128_000
            lower.contains("deepseek-v3") -> 128_000
            lower.contains("deepseek-r1") -> 128_000
            lower.contains("deepseek") -> 128_000

            // === Meta Llama 系列 ===
            lower.contains("llama-4-scout") -> 10_000_000   // Scout = 10M!
            lower.contains("llama-4-maverick") || lower.contains("llama-4") -> 1_000_000
            lower.contains("llama-3.3") || lower.contains("llama-3.2") || lower.contains("llama-3.1") -> 128_000
            lower.contains("llama-3") || lower.contains("llama-2") -> 128_000
            lower.contains("llama") -> 8_000

            // === Alibaba Qwen 系列（2026年6月） ===
            lower.contains("qwen3.7") -> 1_000_000             // qwen3.7-max/plus = 1M
            lower.contains("qwen3.6-plus") || lower.contains("qwen3.6-flash") -> 1_000_000  // qwen3.6-plus = 1M
            lower.contains("qwen3.6") -> 256_000               // qwen3.6 base/35b = 256K
            lower.contains("qwen3.5-plus") || lower.contains("qwen3.5-flash") -> 1_000_000
            lower.contains("qwen3.5") -> 256_000
            lower.contains("qwen-long") -> 1_000_000
            lower.contains("qwen3-max") || lower.contains("qwen-max") -> 256_000
            lower.contains("qwen3-plus") || lower.contains("qwen-plus") -> 131_000
            lower.contains("qwen3-turbo") || lower.contains("qwen-turbo") -> 131_000
            lower.contains("qwen3") -> 128_000
            lower.contains("qwen") -> 32_000

            // === Xiaomi MiMo 系列 ===
            lower.contains("mimo-v2.5") || lower.contains("mimo-v2.5-pro") -> 1_000_000
            lower.contains("mimo") -> 256_000

            // === Zhipu GLM 系列（2026年6月） ===
            lower.contains("glm-5.2") -> 1_000_000              // GLM-5.2 = 1M (IndexShare)
            lower.contains("glm-5v-turbo") || lower.contains("glm-5v") -> 200_000
            lower.contains("glm-5-turbo") -> 200_000
            lower.contains("glm-5") || lower.contains("glm-5.1") -> 200_000
            lower.contains("glm-4-plus") || lower.contains("glm-4") -> 200_000
            lower.contains("glm") -> 128_000

            // === Moonshot Kimi 系列（2026年6月） ===
            lower.contains("kimi-k2.7") || lower.contains("kimi-k2-7") -> 256_000  // K2.7 Code = 256K
            lower.contains("kimi-k2.6") || lower.contains("kimi-k2-6") -> 256_000  // K2.6 = 256K
            lower.contains("kimi-k2.5") || lower.contains("kimi-k2-5") -> 256_000
            lower.contains("kimi-k2") -> 256_000
            lower.contains("kimi") -> 256_000

            // === ByteDance Doubao/Seed 系列（2026年6月） ===
            lower.contains("doubao-seed-2.1") -> 256_000        // Seed 2.1 Pro/Turbo = 256K
            lower.contains("doubao-seed-2.0") -> 256_000        // Seed 2.0 = 256K
            lower.contains("doubao-seed") -> 256_000
            lower.contains("doubao") || lower.contains("seed") -> 256_000

            // === MiniMax 系列（2026年6月） ===
            lower.contains("minimax-m3") -> 1_000_000           // M3 = 1M (MSA)
            lower.contains("minimax-m2.7") || lower.contains("minimax-m2") -> 200_000  // M2.7 = 200K
            lower.contains("minimax") -> 200_000

            // === Google Gemma 系列 ===
            lower.contains("gemma-4") || lower.contains("gemma-3") -> 256_000
            lower.contains("gemma") -> 8_000

            // === 其他 ===
            lower.contains("mistral") || lower.contains("mixtral") -> 128_000
            lower.contains("phi-4") -> 16_000
            lower.contains("yi-") -> 200_000
            lower.contains("o3") || lower.contains("o4") -> 200_000  // OpenAI o3/o4 系列
            else -> DEFAULT_MAX_TOKENS
        }
    }
}
