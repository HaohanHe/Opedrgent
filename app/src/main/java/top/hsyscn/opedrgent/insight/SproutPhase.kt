package top.hsyscn.opedrgent.insight

/**
 * 发芽思考空间中的"声音"——每个声音代表一种独特的分析视角
 *
 * 不同于旧的固定阶段(Phase1-4)，声音是并发的、可选的、可组合的。
 * 用户可以选择不同模板来组合不同的声音。
 */
enum class SproutVoice(
    val displayName: String,
    val description: String,
    val iconChar: String,       // 单字母标识，用于UI显示（严禁emoji）
    val defaultEnabled: Boolean = true,
) {
    INSIGHT(
        displayName = "洞察者",
        description = "寻找反直觉的关联和意想不到的角度",
        iconChar = "I",
    ),
    CRITIQUE(
        displayName = "批判者",
        description = "挑战假设，找出漏洞和盲点",
        iconChar = "C",
    ),
    SUPPORT(
        displayName = "支持者",
        description = "肯定价值，找到可行的路径",
        iconChar = "S",
    ),
    HISTORIAN(
        displayName = "历史学家",
        description = "古今中外，找到历史镜像和文明参照",
        iconChar = "H",
    ),
    FUTURIST(
        displayName = "未来派",
        description = "推演趋势，想象可能的后果",
        iconChar = "F",
    );

    companion object {
        /** 根据名称查找声音（大小写不敏感） */
        fun fromName(name: String): SproutVoice? =
            entries.find { it.name.equals(name, ignoreCase = true) }
    }
}

/**
 * 分析模板 —— 预设的声音组合方案
 *
 * 不同模板适用于不同的分析场景。模板决定了哪些声音会参与讨论。
 */
enum class SproutTemplate(
    val displayName: String,
    val description: String,
    val voices: List<SproutVoice>,
) {
    PANORAMA(
        displayName = "全景模式",
        description = "五个声音全部参与，最全面的分析",
        voices = SproutVoice.entries.toList(),
    ),
    DEBATE(
        displayName = "辩论模式",
        description = "批判与支持的交锋，适合理清决策",
        voices = listOf(SproutVoice.CRITIQUE, SproutVoice.SUPPORT),
    ),
    TIME_TRAVEL(
        displayName = "穿越模式",
        description = "历史参照与未来推演的结合",
        voices = listOf(SproutVoice.HISTORIAN, SproutVoice.FUTURIST, SproutVoice.INSIGHT),
    ),
    QUICK(
        displayName = "快速模式",
        description = "只听洞察和批判，快速获得核心观点",
        voices = listOf(SproutVoice.INSIGHT, SproutVoice.CRITIQUE),
    );

    companion object {
        fun fromName(name: String): SproutTemplate? =
            entries.find { it.name.equals(name, ignoreCase = true) }
    }
}

/**
 * 声音的状态 —— 用户可以看到每个声音当前在做什么
 */
enum class SproutVoiceStatus {
    PENDING,      // 等待中
    SPEAKING,     // 正在发言（LLM调用中）
    DONE,         // 已完成
    SKIPPED,      // 已跳过（超时或错误）
}

/**
 * 兼容旧代码的阶段枚举（保留但不推荐使用）
 * 新代码应使用 SproutVoice 和 SproutTemplate
 */
@Deprecated("Use SproutVoice instead")
enum class SproutPhase(
    val label: String,
) {
    SEED_EXTRACTION("种子提取"),
    CROSS_DOMAIN("跨领域关联"),
    WEB_ENHANCE("联网增强"),
    SHOCKING_INSIGHT("震惊瞬间洞察"),
    QUOTE_RESONANCE("金句回响"),
}
