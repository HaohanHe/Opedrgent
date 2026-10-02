package top.hsyscn.opedrgent.cultivation.model

/**
 * 常见认知偏差的「参考知识」目录——**仅供模型理解，绝不是判定词表**。
 *
 * === 最高纪律（务必随提示一并被模型读到） ===
 * 1. 这里列出的偏差名只是一份供模型查阅的“术语词典”，帮助它在**完整语境**中理解一种思维倾向，
 *    而**不是**任何关键词 / 触发词表。
 * 2. **严禁因为转写里出现了某个词，就判定用户有某种偏差。** 例如出现“永远”“完蛋”并不等于
 *    就是“非黑即白”或“灾难化”；必须通读上下文，判断这个思维方式是否**真的在支撑用户的某个结论**。
 * 3. 只有当一种思维方式确实在影响用户的判断、结论或情绪时，才在该条 issue 的 referenceName 里
 *    填写对应的偏差名；只是“像”但证据不足时，referenceName 留空，照常给出替代视角即可。
 * 4. 不贴标签、不硬套、不生搬：宁可 referenceName 留空，也不为了显得专业而对号入座。
 * 5. 若整段转写里找不到“正在支撑结论的、可商榷的思维方式”，就如实输出「本次未发现认知偏差」，
 *    绝不硬挑、绝不因为“总得说点什么”而编造。
 *
 * 工程侧同样不做任何匹配：本目录**只**通过 [renderForPrompt] 作为背景知识注入系统提示，
 * 没有任何代码会把用户转写与下列名字 / 例句做字符串比对。这些条目是死的参考文本，不是规则。
 *
 * @param name 偏差的通行参考名（仅当模型确信契合时，原样填进 MirrorIssue.referenceName）
 * @param plain 一句通俗说明：这种思维倾向大概长什么样（对事，不对人）
 * @param example 一个**泛化、不针对任何具体人**的示例，帮助模型理解该倾向，不得被当作用户本人的话引用
 */
data class CognitiveBiasEntry(
    val name: String,
    val plain: String,
    val example: String,
)

object CognitiveBiasCatalog {

    /**
     * 常见认知偏差参考条目。覆盖需求点列举的主要条目，并保留少量常见项；
     * 这是开放式参考，模型可使用目录之外、自己判断为更贴切的认知维度名（dimension），
     * referenceName 只在“确实契合下面某条”时才填，否则留空。
     */
    val entries: List<CognitiveBiasEntry> = listOf(
        CognitiveBiasEntry(
            name = "非黑即白（全或无）",
            plain = "只用两极看事情，没有中间地带：要么完美要么失败，要么全好要么全坏。",
            example = "一次没做好，就概括成“整件事彻底搞砸了”。",
        ),
        CognitiveBiasEntry(
            name = "过度概括",
            plain = "把单一负面事件当成会无限重复的规律，用“总是 / 从来 / 每次”之类全称判断。",
            example = "这次沟通不顺，就得出“我跟谁都处不好”。",
        ),
        CognitiveBiasEntry(
            name = "心理过滤（否定正面）",
            plain = "只盯住一个负面细节，把整件事的评价拉走，正面信息被自动过滤掉、不作数。",
            example = "整体反馈都不错，却只反复想那一句批评。",
        ),
        CognitiveBiasEntry(
            name = "读心（跳跃式结论）",
            plain = "在没有足够证据时，断定别人对自己有负面看法。",
            example = "对方只是没立刻回复，就认定“他肯定对我有意见”。",
        ),
        CognitiveBiasEntry(
            name = "算命式预言（跳跃式结论）",
            plain = "在没有依据时，预判事情会往糟的方向发展，并把预测当成事实。",
            example = "还没上场，就认定“我肯定会搞砸”。",
        ),
        CognitiveBiasEntry(
            name = "夸大与缩小",
            plain = "把自己的不足或坏事放大，把必要的能力或好的一面缩小到不值一提。",
            example = "一个小失误被看成致命，而平时的努力被说成“那不算什么”。",
        ),
        CognitiveBiasEntry(
            name = "情绪推理",
            plain = "把自己的感受当成事实：“我觉得是这样，所以就是这样。”",
            example = "“我觉得自己很没用，所以我确实是个没用的人。”",
        ),
        CognitiveBiasEntry(
            name = "应该陈述",
            plain = "用一堆“应该 / 必须 / 本该”要求自己或他人，做不到就自责或怨怼，忽视现实条件。",
            example = "“我应该永远精力充沛，累了就是我不够自律。”",
        ),
        CognitiveBiasEntry(
            name = "贴标签",
            plain = "用一次行为给整个人下一个固定标签，而不是描述这个具体行为本身。",
            example = "一次发言卡壳，就给自己贴上“我就是个社恐的失败者”。",
        ),
        CognitiveBiasEntry(
            name = "个人化与归因偏差",
            plain = "把与自己无关的事情揽到自己身上，或反之把责任全推给外部，忽视多方共同原因。",
            example = "团队结果不好，就认定“全是我的错”，忽略客观分工与外部条件。",
        ),
        CognitiveBiasEntry(
            name = "幸存者偏差",
            plain = "只看到经过某种筛选后留下来的样本，忽略被筛掉的部分，据此得出过度乐观或片面结论。",
            example = "只看到少数“裸辞成功”的故事，就以为这条路普遍可行。",
        ),
        CognitiveBiasEntry(
            name = "确认偏误",
            plain = "只留意、只记住支持自己已有看法的证据，自动忽视或贬低相反证据。",
            example = "认定“别人都不喜欢我”，于是只记得冷淡的瞬间，忽略友善的互动。",
        ),
        CognitiveBiasEntry(
            name = "锚定效应",
            plain = "过度依赖最先看到的那个信息，后续判断都围着它转，难以根据新证据调整。",
            example = "先听到一个偏高的报价，之后再合理的价格都显得便宜或被牵着走。",
        ),
        CognitiveBiasEntry(
            name = "沉没成本",
            plain = "因为已经投入了时间 / 精力 / 钱，明知不合适仍继续坚持，而不是看未来是否值得。",
            example = "“都坚持这么久了，现在放弃太可惜”，即使方向已被证明不适合。",
        ),
    )

    /**
     * 渲染为系统提示中的一段参考文本。仅作为背景知识随提示给出，
     * 配套的使用纪律（逐字证据、不命中、不硬套、没有就如实说）由 CognitivePromptBuilder 统一声明。
     */
    fun renderForPrompt(): String = buildString {
        appendLine("【常见认知偏差参考词条（供你理解，不是判定词表）】")
        appendLine("下面是一份开放式参考。它的唯一用途是：当你在完整语境中确信某种思维方式正在支撑用户的结论时，")
        appendLine("可以把对应的参考名填进 issue.referenceName；只是“有点像”而证据不足时，referenceName 必须留空。")
        appendLine("严禁因为转写里出现某个词就套用下面任何一条；也可以使用本目录之外、你判断更贴切的认知维度名。")
        entries.forEachIndexed { i, e ->
            appendLine("${i + 1}. ${e.name}：${e.plain}  泛化示例：${e.example}")
        }
    }
}
