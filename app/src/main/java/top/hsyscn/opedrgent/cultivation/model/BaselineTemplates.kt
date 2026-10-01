package top.hsyscn.opedrgent.cultivation.model

/**
 * 理想人格基准的「起始模板」。
 *
 * 定位声明（务必保持）：这里只是一个开箱即用的脚手架，不是判定词表。
 * - 用户可任意勾选、改写、删除其中任何维度与行为条目，它不参与任何自动判定；
 * - 实际分析由模型结合完整语境与逐字证据完成，工程代码不会把下列文案当作关键词去命中用户文本；
 * - 与长期趋势统计严格分离：趋势只统计模型在复盘中自行归纳产出的 issue.dimension，
 *   绝不对这些模板文案做计数或归并。
 */
object BaselineTemplates {

    /**
     * 四个起步维度，每条行为均写成可观察的具体动作（而非抽象口号）。
     * 仅作默认示例，用户第一步即可增删改。
     */
    val STARTER: List<VirtueDimension> = listOf(
        VirtueDimension(
            name = "尊重与倾听",
            doBehaviors = listOf(
                "等对方说完再开口",
                "用自己的话复述确认",
            ),
            dontBehaviors = listOf(
                "中途打断",
                "对方说话时分心做别的",
            ),
        ),
        VirtueDimension(
            name = "对事不对人",
            doBehaviors = listOf(
                "只谈具体行为与事实",
                "分歧时指向这件事而非这个人",
            ),
            dontBehaviors = listOf(
                "给人贴标签",
                "上升到人格或能力定性",
            ),
        ),
        VirtueDimension(
            name = "情绪与措辞",
            doBehaviors = listOf(
                "情绪上来先停顿一下",
                "用我感到……的方式表达",
            ),
            dontBehaviors = listOf(
                "用指责性措辞",
                "翻旧账",
            ),
        ),
        VirtueDimension(
            name = "承诺与跟进",
            doBehaviors = listOf(
                "答应的事记下来并给出时间",
                "做不到提前说明",
            ),
            dontBehaviors = listOf(
                "随口承诺后无下文",
                "被问起才解释",
            ),
        ),
    )
}
