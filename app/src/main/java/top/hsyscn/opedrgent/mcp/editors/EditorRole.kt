package top.hsyscn.opedrgent.mcp.editors

/**
 * 编辑角色 — 预设模板库。
 *
 * 这些是常用的编辑角色定义，作为"能力池"供 LLM 规划时选用。
 * LLM 也可以根据任务需要，动态生成不在列表中的新角色。
 */
enum class EditorRole(
    val code: String,
    val displayName: String,
    val alias: String,
    val icon: String,
    val description: String,
    val color: Long,
    val systemPrompt: String,
) {
    TOPIC_PLANNER(
        code = "topic_planner",
        displayName = "选题策划",
        alias = "选题",
        icon = "\uD83D\uDCA1",
        description = "结合用户笔记、话题趋势分析选题方向",
        color = 0xFF4A90D9,
        systemPrompt = """你是一位敏锐的选题策划师。根据用户提供的素材提出值得写的选题方向。

## 工作方式
1. 深度分析用户输入，理解核心意图和潜在价值点
2. 从不同维度（热点性、实用性、独特性）提出 3-5 个选题
3. 每个选题包含：标题/切入点、为什么有价值、建议结构、预估字数
4. 按推荐程度从高到低排列

## 输出格式
Markdown 格式，每个选题用二级标题 ## 选题N：[标题]
包含「价值说明」「切入角度」「建议结构」

## 注意事项
- 不写完整文章，只做选题策划
- 选题要具体可执行""",
    ),

    RESEARCHER(
        code = "researcher",
        displayName = "素材调研",
        alias = "调研",
        icon = "\uD83D\uDCDA",
        description = "为观点补充数据、故事、案例、论据",
        color = 0xFFE67E22,
        systemPrompt = """你是一位博学的素材调研员，擅长为任何观点找到强有力的支撑材料。

## 素材类型（保持多样性）
1. 数据与统计：权威报告中的关键数字
2. 真实案例：国内外典型案例、个人故事
3. 专家观点：知名学者/行业领袖的言论
4. 名言金句：经典引用、流行语
5. 类比与比喻：帮助读者理解的生动比喻
6. 反例与对比：增强论证说服力

## 输出格式
每条素材：类型 + 内容 + 来源 + 适用场景 + 可信度
提供 8-12 条高质量素材，按「核心→辅助→点缀」分层排列""",
    ),

    WRITER(
        code = "writer",
        displayName = "文章撰写",
        alias = "撰写",
        icon = "\u270D\uFE0F",
        description = "理清思路，零散内容→完整文章",
        color = 0xFF3498DB,
        systemPrompt = """你是一位才华横溢的文章撰写专家。将零散的想法、粗糙的草稿打磨成结构清晰、文笔流畅的完整文章。

## 写作流程
1. 先确认写作要素（主题/读者/效果/已有素材）
2. 构建章节大纲
3. 基于大纲撰写全文

## 原则
- 开门见山，一段一意，有理有据
- 长短句交替，善用小标题，结尾有力
- Markdown 格式输出""",
    ),

    FACT_CHECKER(
        code = "fact_checker",
        displayName = "事实核查",
        alias = "核查",
        icon = "\uD83D\uDD0D",
        description = "核实数据、数字、引用的准确性",
        color = 0xFFE74C3C,
        systemPrompt = """你是一位严谨的事实核查专家。检查文章中的事实性错误。

## 核查范围
数据准确性 / 时间线 / 名称称谓 / 引用核实 / 逻辑一致性 / 常识判断

## 输出格式
每处问题：位置 + 问题类型 + 原文 + 修正建议 + 置信度
最后给出总体评价：严重/一般/轻微问题数 + 可信度评分 X/10

## 原则
保守原则（无法确定则标记"需核实"），区分致命错误与轻微瑕疵""",
    ),

    REVIEWER(
        code = "reviewer",
        displayName = "审稿编辑",
        alias = "审稿",
        icon = "\uD83D\uDCCB",
        description = "高层视角审阅：结构、逻辑、读者感受",
        color = 0xFF9B59B6,
        systemPrompt = """你是一位资深的审稿编辑。从宏观视角审视文章整体质量。

## 审稿维度
1. 结构与架构：开头吸引力、篇幅比例、过渡流畅度、叙事弧线
2. 逻辑与论证：论点清晰度、支撑有效性、逻辑漏洞、反方回应
3. 读者体验：可读性、阅读疲劳点、情感节奏、收获感
4. 标题与包装：标题吸引力、小标题准确性

## 输出格式
总体评价 → 亮点 → 问题与建议（按优先级 P0/P1/P2 排序）→ 备选标题方案

## 原则
先肯定优点再提建议（三明治法），所有问题附带修改建议""",
    ),

    FORMATTER(
        code = "formatter",
        displayName = "排版设计",
        alias = "排版",
        icon = "\uD83C\uDFA8",
        description = "多平台排版输出",
        color = 0xFF1ABC9C,
        systemPrompt = """你是一位精通多平台内容排版的专家。将文章适配到不同平台。

支持平台：公众号（深度阅读）/ 小红书（emoji丰富短平快）/ 朋友圈（140字精炼）/ 抖音图文（节奏快冲击力强）/ PDF报告（正式严谨）

根据用户指定的平台输出对应格式的完整排版内容。
保持原文核心信息不变，只调整呈现方式和语言风格。""",
    ),

    ORGANIZER(
        code = "organizer",
        displayName = "整理归集",
        alias = "整理",
        icon = "\uD83D\uDCE6",
        description = "散乱内容统一整理为知识体系",
        color = 0xFF34495E,
        systemPrompt = """你是一位井井有条的信息管理专家。将散落的数字痕迹整合成有条理的知识体系。

## 整理流程
1. 分类打标：主题领域 / 内容类型 / 紧急程度 / 完成状态
2. 建立关联：找出内容之间的联系和合并机会
3. 输出整理结果：内容概览 + 分类整理 + 待办清单 + 关联发现 + 建议新建笔记

原则：不丢失原始信息，只优化组织形式""",
    ),

    STYLIST(
        code = "stylist",
        displayName = "风格打磨",
        alias = "风格",
        icon = "\uD83C\uDFAD",
        description = "学习并模仿特定写作风格",
        color = 0xFFF39C12,
        systemPrompt = """你是一位精通各种写作风格的模仿大师。将文字改写为目标风格同时保留核心意思。

掌握的风格库：学术派 / 故事派 / 极简派 / 深度派 / 金句派 / 温暖治愈派

工作流程：风格诊断 → 风格匹配 → 改写执行 → 对比展示

重要原则：保真（核心事实不改）、保意（观点不变）、只变形式""",
    );

    companion object {
        /** 所有可用角色模板 */
        val allRoles: List<EditorRole> get() = entries.toList()

        /** 默认推荐组合（常见场景） */
        val defaultPipeline: List<EditorRole>
            get() = listOf(WRITER, REVIEWER, FORMATTER)

        /** 完整创作流水线（长文场景） */
        val fullCreationPipeline: List<EditorRole>
            get() = listOf(TOPIC_PLANNER, RESEARCHER, WRITER, REVIEWER, FACT_CHECKER, FORMATTER)

        /** 快速润色流水线（已有草稿场景） */
        val quickPolishPipeline: List<EditorRole>
            get() = listOf(REVIEWER, FACT_CHECKER, FORMATTER)
    }
}

// ==================== 动态角色（LLM 自生成）====================

/**
 * 动态角色 — LLM 根据任务需求实时生成的角色定义。
 *
 * 与 [EditorRole]（预设枚举）不同，DynamicRole 是 LLM 在规划阶段自由创建的，
 * 可以是预设角色的变体，也可以是完全新的角色。
 */
data class DynamicRole(
    val name: String,           // 角色名称，如"数据分析专家"
    val alias: String,          // 简称，如"分析"
    val icon: String = "\u2604", // 默认图标
    val description: String = "", // 一句话描述职责
    val systemPrompt: String,   // 该角色的系统提示词
    val inputHint: String = "",  // 该步骤期望什么输入（传给下一步的上下文提示）
) {
    /** 是否匹配某个预设角色（用于 UI 显示颜色等） */
    fun matchedPreset(): EditorRole? {
        return EditorRole.allRoles.minByOrNull { role ->
            levenshteinDistance(name.lowercase(), role.displayName.lowercase()) +
            levenshteinDistance(alias.lowercase(), role.alias.lowercase())
        }?.takeIf { dist(name, it.displayName) < 3 }
    }

    /** 获取显示颜色（优先匹配预设，否则随机分配） */
    val displayColor: Long
        get() = matchedPreset()?.color ?: DEFAULT_COLORS[name.hashCode() % DEFAULT_COLORS.size]

    companion object {
        private val DEFAULT_COLORS = longArrayOf(
            0xFF4A90D9, 0xFFE67E22, 0xFF3498DB, 0xFFE74C3C,
            0xFF9B59B6, 0xFF1ABC9C, 0xFF34495E, 0xFFF39C12,
            0xFF16A085, 0xFFC0392B, 0xFF8E44AD, 0xFF27AE60,
        )
    }
}

/** 统一的角色接口（可以是预设枚举或动态生成） */
sealed class RoleInstance {
    abstract val name: String
    abstract val alias: String
    abstract val icon: String
    abstract val displayColor: Long
    abstract val systemPrompt: String
    abstract val inputHint: String

    data class Preset(val role: EditorRole) : RoleInstance() {
        override val name: String get() = role.displayName
        override val alias: String get() = role.alias
        override val icon: String get() = role.icon
        override val displayColor: Long get() = role.color
        override val systemPrompt: String get() = role.systemPrompt
        override val inputHint: String get() = ""
    }

    data class Dynamic(val dynamicRole: DynamicRole) : RoleInstance() {
        override val name: String get() = dynamicRole.name
        override val alias: String get() = dynamicRole.alias
        override val icon: String get() = dynamicRole.icon
        override val displayColor: Long get() = dynamicRole.displayColor
        override val systemPrompt: String get() = dynamicRole.systemPrompt
        override val inputHint: String get() = dynamicRole.inputHint
    }
}

// ==================== 规划结果 =====================

/**
 * 步骤规划 — LLM 输出的执行计划中的一步。
 */
data class PlanStep(
    val index: Int,
    val role: RoleInstance,
    val instruction: String = "",      // 给该步骤的具体指令
    val dependsOnPrevious: Boolean = true, // 是否依赖上一步输出
)

/**
 * 执行计划 — LLM 分析任务后输出的完整规划。
 */
data class ExecutionPlan(
    val steps: List<PlanStep>,
    val reasoning: String = "",          // 为什么这样规划
    val estimatedSteps: Int = steps.size,
)

// ==================== 编辑斯距离 =====================

private fun levenshteinDistance(a: String, b: String): Int {
    val dp = Array(a.length + 1) { IntArray(b.length + 1) }
    for (i in dp.indices) dp[i][0] = i
    for (j in dp[0].indices) dp[0][j] = j
    for (i in a.indices) {
        for (j in b.indices) {
            dp[i + 1][j + 1] = minOf(
                dp[i][j + 1] + 1,
                dp[i + 1][j] + 1,
                dp[i][j] + if (a[i] == j[j]) 0 else 1
            )
        }
    }
    return dp[a.length][b.length]
}

private fun dist(a: String, b: String): Int = levenshteinDistance(a.lowercase(), b.lowercase())
