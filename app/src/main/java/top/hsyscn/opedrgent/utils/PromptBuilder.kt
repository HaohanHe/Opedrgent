package top.hsyscn.opedrgent.utils

import top.hsyscn.opedrgent.env.EnvironmentInfo
import top.hsyscn.opedrgent.model.ResearchSession
import top.hsyscn.opedrgent.model.Source
import top.hsyscn.opedrgent.mcp.skills.SkillLoader
import top.hsyscn.opedrgent.settings.ApiSettings
import top.hsyscn.opedrgent.storage.HippocampusIndex
import top.hsyscn.opedrgent.storage.MemoryStore

object PromptBuilder {

    const val PROMPT_CACHE_BOUNDARY = "\n---PROMPT_CACHE_BOUNDARY---\n"

    fun buildStaticPrompt(): String {
        return PromptCache.getOrComputeStaticPrompt {
            val layers = mutableListOf<String?>()

            layers += buildIdentitySection()
            layers += buildBaseRulesSection()
            layers += buildSkillsSection()

            layers.filterNotNull().joinToString("\n\n").trim()
        }
    }
    
    private fun buildSkillsSection(): String {
        return ""
    }

    /**
     * 构建可用 Skill 列表注入到系统 Prompt（Kilo 风格）。
     * LLM 看到列表后，可通过 load_skill 工具按需加载完整内容。
     * @param skillNames 预加载的技能名称列表（调用方需在协程中获取）
     */
    fun buildSkillsSection(skillNames: List<Pair<String, String>>): String {
        if (skillNames.isEmpty()) return ""

        val sb = StringBuilder("# 可用技能 (Skills)\n\n")
        sb.appendLine("以下技能可用。使用 `load_skill` 工具加载技能的完整指令。")
        sb.appendLine("仅在任务匹配技能描述时才加载，不要预加载。")
        sb.appendLine()

        for ((name, description) in skillNames) {
            sb.appendLine("- **$name**: $description")
        }

        sb.appendLine()
        sb.appendLine("调用方式: load_skill(name=\"技能名称\")")
        return sb.toString().trim()
    }

    suspend fun buildDynamicPrompt(
        apiSettings: ApiSettings,
        session: ResearchSession,
        memoryStore: MemoryStore? = null,
        envInfo: EnvironmentInfo? = null,
        modelInfo: ModelInfo? = null,
        platformCtx: PlatformContext? = null,
        skillNames: List<Pair<String, String>> = emptyList(),
        hippocampusIndex: HippocampusIndex? = null,
    ): String {
        val layers = mutableListOf<String?>()

        val resolvedModel = modelInfo ?: ModelInfo(
            modelId = apiSettings.getModel(),
            provider = inferProviderFromUrl(apiSettings.getBaseUrl())
        )

        val resolvedPlatform = platformCtx ?: PlatformContext()

        val conditionalLayer = buildConditionalLayer(resolvedModel, resolvedPlatform)
        if (conditionalLayer.isNotBlank()) {
            layers.add(conditionalLayer)
            DebugLog.d("PromptBuilder: conditional layer injected (${conditionalLayer.length} chars), model=${resolvedModel.modelId}, needsEnforcement=${resolvedModel.needsToolEnforcement}, hasTTS=${resolvedPlatform.hasTTS}")
        } else {
            DebugLog.w("PromptBuilder: conditional layer EMPTY, model=${resolvedModel.modelId}, provider=${resolvedModel.provider}, hasTTS=${resolvedPlatform.hasTTS}")
        }

        layers += PromptCache.getOrComputeSession("layer2_context_files") {
            buildContextFilesSection()
        }

        layers += PromptCache.getOrComputeSession("layer3_memory") {
            buildMemorySection(apiSettings, memoryStore, hippocampusIndex)
        }

        val maxTokens = ModelLimits.inferMaxContextTokens(resolvedModel.modelId)
        layers += PromptCache.getOrComputeSession("layer3_notes") {
            buildNotesSection(session, maxTokens)
        }

        layers += PromptCache.getOrComputeSession("layer3_sources") {
            buildSourcesSection(session)
        }

        layers += buildRuntimeLayer(envInfo, session)

        val skillsLayer = buildSkillsSection(skillNames)
        if (skillsLayer.isNotBlank()) {
            layers.add(skillsLayer)
        }

        return layers.filterNotNull().joinToString("\n\n").trim()
    }

    suspend fun buildSystemPrompt(
        apiSettings: ApiSettings,
        session: ResearchSession,
        memoryStore: MemoryStore? = null,
        envInfo: EnvironmentInfo? = null,
        modelInfo: ModelInfo? = null,
        platformCtx: PlatformContext? = null,
        skillNames: List<Pair<String, String>> = emptyList(),
        hippocampusIndex: HippocampusIndex? = null,
    ): String {
        val static = buildStaticPrompt()
        val dynamic = buildDynamicPrompt(apiSettings, session, memoryStore, envInfo, modelInfo, platformCtx, skillNames, hippocampusIndex)
        return "$static$PROMPT_CACHE_BOUNDARY\n$dynamic"
    }

    /**
     * 将系统提示词拆分为可缓存的静态前缀和动态后缀。
     *
     * 借鉴 Claude Code 的 SYSTEM_PROMPT_DYNAMIC_BOUNDARY 设计：
     * - 静态前缀（身份、规则、工具定义）在所有会话间共享，可被 LLM API 的 prompt cache 命中
     * - 动态后缀（记忆、上下文、环境信息）每次会话不同
     *
     * OpenAI 兼容 API 自动缓存 system message 的最长公共前缀（prefix caching），
     * 将静态内容放在前面可最大化缓存命中率，节省 prompt token 费用。
     *
     * @return Pair(静态前缀, 动态后缀)，如果无边界标记则返回 (完整提示词, "")
     */
    fun splitSystemPrompt(fullPrompt: String): Pair<String, String> {
        val idx = fullPrompt.indexOf(PROMPT_CACHE_BOUNDARY)
        return if (idx >= 0) {
            fullPrompt.substring(0, idx) to fullPrompt.substring(idx + PROMPT_CACHE_BOUNDARY.length).trimStart()
        } else {
            fullPrompt to ""
        }
    }

    /**
     * 获取缓存效率统计。
     *
     * @return Map 包含静态前缀长度、动态后缀长度、缓存比率
     */
    fun getCacheEfficiency(fullPrompt: String): Map<String, Any> {
        val (static, dynamic) = splitSystemPrompt(fullPrompt)
        val totalLen = fullPrompt.length
        val staticLen = static.length
        val dynamicLen = dynamic.length
        val cacheRatio = if (totalLen > 0) staticLen.toDouble() / totalLen else 0.0

        return mapOf(
            "total_length" to totalLen,
            "static_length" to staticLen,
            "dynamic_length" to dynamicLen,
            "cache_ratio" to "%.1f%%".format(cacheRatio * 100),
            "estimated_static_tokens" to (staticLen / 3), // 粗略估算：中文约3字符/token
        )
    }

    /**
     * 工具调用 guardrail 反思轮提示语。
     * 当检测到 Agent 可能陷入循环时，要求 LLM 暂停工具调用并分析策略。
     */
    fun buildReflectionPrompt(): String {
        return "[系统提示] 检测到当前研究策略可能陷入重复。请暂停工具调用，分析已获取的信息，并说明下一步将如何调整关键词、工具或 URL。如果认为已有信息足够，请直接输出阶段性结论。"
    }

    private fun buildIdentitySection(): String = """
# 你是 Opedrgent，一个自主研究助手

你帮助用户查找信息、分析问题、给出答案。
""".trimIndent()

    private fun buildBaseRulesSection(): String = """
## 核心原则
1. **事实必须有来源支撑**，不确定标注[待验证]
2. **不编造** URL、数据、人物、事件
3. **自主决策**：自己决定搜什么、是否继续、何时回答
4. **效率优先**：快扫→深挖→回答，最多3轮搜索

## 工作方式
- 问题模糊 → 用 ask_question 追问（给2-5个选项）
- 需要用户授权 → 用 ask_confirmation 请求确认（30秒超时自动继续）
- 搜索词要短（3-6词），专有名词拆开
- **URL 识别规则**：如果用户输入包含 http:// 或 https:// 开头的 URL，必须立即使用 read_url 直接访问该 URL，不要使用 web_search
- 先 web_search 看结果，再决定是否 read_url 或 deep_research（仅当用户未提供具体 URL 时）
- 搜索结果不相关 → 换关键词，不要重复搜一样的
- 回答像百科/新闻稿：直接给结论，不说过程

## 禁止
- 不搜就瞎编事实
- 把一次结果当全部真相
- 输出空洞废话代替具体事实
- 暴露工具名、坐标、系统内部信息

## 可用工具

${buildToolsTable()}

### ★ 工具优先级（Claude Code 风格强制规则）

**绝对优先级链（从高到低）：**
1. **专用工具 > Bash/命令行 > 手动构造** — 始终选择最精确的专用工具完成操作
2. **web_search 是信息获取的首选方式** — 不要尝试手动构造URL或模拟搜索行为
3. **read_url 用于页面内容提取** — 当需要读取网页正文时使用，不要用 web_search 替代
4. **当专用工具不存在时才考虑降级方案** — 任何"我会..."的表述必须在同一响应中附带工具调用

**关键行为约束：**
- 你描述要执行的操作时，必须**立即在同一响应中调用对应工具**
- "让我检查"、"我将运行"、"我准备查看"这类话术后面必须紧接 tool_call
- 禁止只输出计划而不采取行动——计划本身不是交付物
- 多个独立操作应在单个响应中并行调用，而非串行等待
- 工具调用失败时先诊断原因再换策略，禁止盲目重试同一参数

Prefer dedicated tools over manual methods:
Use web_search for web queries. Use read_url to fetch page content.
Do NOT manually browse or construct URLs when a tool exists for that purpose.

## 工具规则
- 一次只调一个工具，拿到结果再决定下一步
- 中文查询自动双语搜索，也可主动构造英文关键词
- 搜索为空 ≠ 无解，是关键词不好，换词再试（最多换2次不同关键词）
- 多个独立操作可以并行调用
- **URL 处理优先级**：用户消息中的 URL → read_url（直接访问）；无 URL 的问题 → web_search（搜索）

### 工具调用提示
- 单个工具超时、限流或返回部分内容，不代表研究结束。
- 如果你认为已有信息足够，可以直接给出阶段性结论。
- 如果你认为还需要补充，请尝试其他关键词、其他工具或其他 URL，不要重复调用同一个失败参数。

### ★ 工具失败处理（重要）
- **同一工具连续失败 2 次后，立即停止调用该工具**，换用其他工具或基于已有知识回答
- **web_search 失败时**：不要无限重试搜索。如果搜索2次都失败或返回空结果，直接根据训练数据回答并标注[待验证]
- **read_url 失败时**：换用 web_search 搜索相关关键词作为替代
- **网络类错误**（超时、DNS失败、连接拒绝）：说明网络有问题，不要反复重试，告诉用户网络状况后基于已有知识回答
- **绝对禁止**：在同一个会话中对同一工具使用相同参数重复调用超过3次
- 如果上下文中包含之前的"工具执行记录"显示某工具已连续失败，**必须**避免再次调用该工具

${buildToolDetails()}

## 输出效率锚点（Output Efficiency Anchors）
- **工具调用间推理 ≤25 词**：在连续的工具调用之间仅保留极简推理，如"标题不匹配，换关键词重搜"
- **最终回复 ≤100 词**：除非用户明确要求详细分析，否则直接给出结论性答案
- **一句话原则**：If you can say it in one sentence, don't use three.
- **零铺垫原则**：直接给答案，不要过渡语和客套话（如"好的，我来帮你..."、"根据我的了解..."）
- **结构化输出**：简单问题直接答；复杂问题用「结论 → 分析 → 来源」三段式；事实后标 [S1][S2]，末尾列出来源
- **语音朗读适配**：语音朗读时自然流畅，少用符号和markdown格式

## Token Budget 与上下文管理
- 当前上下文窗口有限，系统会在接近上限时自动压缩历史消息
- **压缩策略**：较早的搜索结果和中间推理会被优先摘要或丢弃
- **高效利用预算**：避免在单次响应中输出冗长内容；将详细分析分散到多轮工具调用中
- **Scratchpad（临时草稿区）**：你可以在连续的多轮工具调用之间维护临时状态，
  将中间发现暂存为内部上下文，待所有信息收集完毕后再一次性整合输出。
  这比每步都输出部分结果更节省token且更连贯。
- 出错先诊断原因再换策略，不要盲目重试

## 输出规则
- 简单问题直接答，不需要固定结构
- 复杂问题可用：结论 → 分析 → 来源
- 事实后标 [S1][S2]，末尾列出来源
- Keep reasoning between tool calls concise (≤25 words).
- Go straight to the point. Try the simplest approach first.
- 直接给答案，不要铺垫和过渡语
""".trimIndent()

    private fun buildToolsTable(): String {
        val tools = ToolPrompts.getAllToolPrompts()
        if (tools.isEmpty()) return "| 工具 | 用途 |\n|------|------|\n| （无） | - |"

        val lines = mutableListOf("| 工具 | 用途 |")
        lines.add("|------|------|")

        for ((name, prompt) in tools) {
            val firstLine = prompt.lines().firstOrNull { it.isNotBlank() } ?: ""
            val shortDesc = if (firstLine.length > 40) firstLine.take(37) + "..." else firstLine
            lines.add("| $name | $shortDesc |")
        }

        return lines.joinToString("\n")
    }

    private fun buildToolDetails(): String {
        val tools = ToolPrompts.getAllToolPrompts()
        if (tools.isEmpty()) return ""

        val sections = mutableListOf<String>()

        for ((name, prompt) in tools) {
            if (prompt.isNotBlank()) {
                sections.add(prompt)
            }
        }

        return if (sections.isEmpty()) "" else sections.joinToString("\n\n")
    }

    private fun buildConditionalLayer(model: ModelInfo, ctx: PlatformContext): String {
        val sections = mutableListOf<String>()

        if (model.needsToolEnforcement) {
            sections.add(buildToolEnforcementGuidance(model))
        }

        if (model.needsPathGuidance) {
            sections.add(buildPathGuidance())
        }

        if (ctx.platform != Platform.ANDROID) {
            sections.add(buildPlatformHints(ctx))
        }

        val capabilities = buildCapabilityDeclarations(ctx)
        if (capabilities.isNotBlank()) {
            sections.add(capabilities)
        }

        return if (sections.isEmpty()) "" else sections.joinToString("\n\n")
    }

    private fun buildToolEnforcementGuidance(model: ModelInfo): String = when {
        model.isGPTFamily -> """
# 执行纪律（GPT/Codex模型专用）

你必须使用工具来执行操作——不要只描述你将要做什么或计划做什么。
当你说你要执行一个操作时（例如"我会运行测试"、"让我检查文件"、"我将创建项目"），
你必须**立即**在同一响应中调用相应的工具调用。
不要以对未来行动的承诺结束你的回合——现在就执行它。

持续工作直到任务真正完成。不要在任务总结处停止，
如果你有可以完成任务的工具可用，使用它们而不是告诉用户你会做什么。

每个响应应该要么：
(a) 包含推进工作的工具调用，或者
(b) 向用户交付最终结果。

只描述意图而不采取行动的响应是不可接受的。
""".trimIndent()

        model.isDeepSeekFamily -> """
# 工具使用强化

- 始终优先使用工具而非凭记忆或心算回答问题
- 如果工具返回空或部分结果，用不同的查询或策略重试，不要放弃
- 继续调用工具直到：(1) 任务完成，且 (2) 你已验证结果
- 不要过早停止——如果另一个工具调用能实质性地改善结果，就继续调用
""".trimIndent()

        else -> """
# 工具使用要求

使用你的工具来执行动作——不要描述你会怎么做或计划怎么做而不实际去做。
当你表示要执行动作时，必须立即进行相应的工具调用。
保持工作直到任务真正完成。不要以计划总结停止。
""".trimIndent()
    }

    private fun buildPathGuidance(): String = """
# 路径和文件操作规范

- **绝对路径优先**：始终构建和使用绝对路径进行所有文件系统操作
- **先验证后修改**：使用 read_file 或搜索工具检查文件内容和项目结构，然后再做更改
- **依赖检查**：不要假设某个库可用。检查 package.json、requirements.txt 等
- **简洁性**：解释性文本保持简短——几段话，不是整页。专注于行动和结果而非叙述
- **并行工具调用**：当需要执行多个独立操作时，在单个响应中调用所有工具调用，而不是顺序调用
- **非交互式命令**：使用 -y、--yes、--non-interactive 等标志防止CLI工具挂起等待提示
- **持续工作**：自主工作直到任务完全解决。不要停在计划上——执行它
""".trimIndent()

    private fun buildPlatformHints(ctx: PlatformContext): String = when (ctx.platform) {
        Platform.CLI -> """
# CLI环境适配

你是CLI AI Agent。尽量不使用markdown而是简单文本，使其可在终端内渲染。
文件传递：没有附件通道——用户在你的终端中直接阅读你的回复。
不要发出 MEDIA:/path 标签（这些只在消息平台如Telegram、Discord、Slack等被拦截；在CLI中它们渲染为字面文本）。
当你引用你创建或更改的文件时，只需在纯文本中说明其绝对路径；用户可以从那里打开它。
""".trimIndent()

        Platform.API -> """
# API环境适配

你通过API服务器响应。渲染层未知——假设为纯文本。
不要markdown格式化（无星号、无列表标题、无代码围栏）。
把它当作对话，而不是文档。保持回复简短自然。
""".trimIndent()

        else -> ""
    }

    private fun buildCapabilityDeclarations(ctx: PlatformContext): String {
        val caps = mutableListOf<String>()
        if (ctx.hasTTS) caps.add("- **MiMo V2.5 TTS语音合成引擎**：支持影视级语音生成")
        if (ctx.hasStt) caps.add("- **Sherpa-ONNX 离线语音识别(STT)**：支持离线语音转文字输入")
        if (ctx.hasVoiceInput) caps.add("- 语音输入(STT)：支持语音识别提问")
        if (ctx.hasLocation) caps.add("- 位置感知：可获取用户地理位置")
        if (ctx.hasBrowser) {
            caps.add("- **浏览器Agent**：可自动化浏览网页、填写表单、点击按钮等操作")
            caps.add("  - 使用 `open_browser` 工具打开网页")
            caps.add("  - 使用 `read_url` 工具读取网页内容")
            caps.add("  - 需要用户确认时使用 `ask_confirmation` 请求授权")
        }
        if (ctx.hasCalendar) caps.add("- 日历访问：可读取和管理日历事件")
        
        if (ctx.hasTTS && caps.isNotEmpty()) {
            caps.add("")
            caps.add("**MiMo TTS高级能力（非简单TTS）：**")
            caps.add("- 预置音色：冰糖/茉莉/苏打/白桦/Mia/Chloe/Milo/Dean（中英文各4个）")
            caps.add("- 自然语言风格控制：一句话描述语气、情绪、语速")
            caps.add("- 导演模式：角色+场景+指导三维度精细控制")
            caps.add("- 音频标签控制：(紧张)、[停顿]、(唱歌)等实时插入")
            caps.add("- 情绪混合：'压抑的愤怒'、'带着哽咽的笑意'等复合情绪")
            caps.add("- 唱歌模式：(唱歌)歌词 即可合成歌曲")
            caps.add("- 方言切换：(东北话)、(粤语)、(四川话)等")
            caps.add("- 音色设计：文本描述自动生成独特音色")
            caps.add("- 音色克隆：音频样本复刻任意声音")
        }
        
        if (caps.isNotEmpty()) {
            return "# 当前能力\n" + caps.joinToString("\n")
        }
        return ""
    }

    private fun buildContextFilesSection(): String? {
        val cwd = System.getProperty("user.dir") ?: return null
        val entry = ContextFileLoader.loadContextFiles(cwd) ?: return null
        return entry.content
    }

    private suspend fun buildMemorySection(apiSettings: ApiSettings, memoryStore: MemoryStore?, hippocampusIndex: HippocampusIndex? = null): String? {
        val memory = memoryStore?.getMemoryBlock()?.trim().orEmpty().ifBlank { apiSettings.getMemory() }

        // 弹性截断：根据模型上下文窗口按比例计算
        val maxTokens = ModelLimits.inferMaxContextTokens(apiSettings.getModel())
        val memMaxChars = ModelLimits.memoryMaxChars(maxTokens)
        val noteMaxChars = ModelLimits.noteMemoryMaxChars(maxTokens)
        val convMaxChars = ModelLimits.conversationMemoryMaxChars(maxTokens)
        val summaryMaxChars = ModelLimits.hippocampusSummary(maxTokens)

        // 笔记记忆段落
        val noteMemories = memoryStore?.getNoteMemories()
            ?.joinToString(separator = "\n") { e ->
                if (e.title.isNotBlank()) "- ${e.title}: ${e.content}" else "- ${e.content}"
            }
            ?.trim()
            .orEmpty()

        val sections = mutableListOf<String>()
        if (memory.isNotBlank()) {
            sections.add("# 记忆\n${PromptBlocks.wrapUntrustedBlock("M", memory, maxChars = memMaxChars)}")
        }
        if (noteMemories.isNotBlank()) {
            sections.add("[笔记记忆]\n${PromptBlocks.wrapUntrustedBlock("NM", noteMemories, maxChars = noteMaxChars)}")
        }

        // 海马体对话历史段落：注入最近对话的 summary 内容（不只是 title）
        val queryLimit = ModelLimits.hippocampusQueryLimit(maxTokens)
        val conversationMemories = hippocampusIndex?.let { hip ->
            hip.query("", limit = queryLimit)
                .takeIf { it.isNotEmpty() }
                ?.joinToString(separator = "\n") { item ->
                    val summary = item.summary.take(summaryMaxChars)
                    "- [${item.sourceType.label}] ${item.title}: $summary"
                }
                ?.trim()
        }.orEmpty()
        if (conversationMemories.isNotBlank()) {
            sections.add("[对话历史记忆]\n${PromptBlocks.wrapUntrustedBlock("HM", conversationMemories, maxChars = convMaxChars)}")
        }

        return if (sections.isEmpty()) null else sections.joinToString("\n\n")
    }

    private fun buildNotesSection(session: ResearchSession, maxTokens: Int = ModelLimits.DEFAULT_MAX_TOKENS): String? {
        return if (session.notes.isBlank()) null else "## 会话笔记\n${PromptBlocks.wrapUntrustedBlock("N", session.notes, maxChars = ModelLimits.noteMemoryMaxChars(maxTokens))}"
    }

    private fun buildSourcesSection(session: ResearchSession): String? {
        val sources = session.sources.filter { it.includeInContext }.take(8)
        if (sources.isEmpty()) return null

        val sb = StringBuilder("# 参考来源\n")
        sources.forEachIndexed { i, src ->
            sb.appendLine("[S${i + 1}] ${src.title ?: "未命名"} ${src.url ?: ""}")
        }
        return sb.toString().trim()
    }

    private fun buildRuntimeLayer(envInfo: EnvironmentInfo?, session: ResearchSession): String {
        val parts = mutableListOf<String>()

        envInfo?.let {
            parts.add("""
# 环境 [内部参考，勿提及]

时间：${it.dateTime} (${it.dayOfWeek})
设备：${it.platform}
语言：${it.language}（用户用什么语言问就答什么语言）
${if (it.location != null) "位置：${it.location}" else ""}
""".trimIndent())
        }

        if (parts.isNotEmpty()) {
            return parts.joinToString("\n\n")
        }
        return ""
    }

    private fun inferProviderFromUrl(baseUrl: String): String {
        return when {
            baseUrl.contains("openai.com", ignoreCase = true) -> "openai"
            baseUrl.contains("anthropic.com", ignoreCase = true) -> "anthropic"
            baseUrl.contains("dashscope", ignoreCase = true) || baseUrl.contains("aliyun", ignoreCase = true) -> "alibaba"
            baseUrl.contains("deepseek", ignoreCase = true) -> "deepseek"
            baseUrl.contains("zhipu", ignoreCase = true) || baseUrl.contains("bigmodel", ignoreCase = true) -> "zhipu"
            baseUrl.contains("localhost", ignoreCase = true) || baseUrl.contains("127.0.0.1") -> "local"
            else -> "custom"
        }
    }
}
