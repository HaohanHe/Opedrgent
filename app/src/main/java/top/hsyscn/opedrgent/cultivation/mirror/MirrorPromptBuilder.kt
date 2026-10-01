package top.hsyscn.opedrgent.cultivation.mirror

import top.hsyscn.opedrgent.cultivation.model.FeedbackMode
import top.hsyscn.opedrgent.cultivation.model.VirtueBaseline

/**
 * 批判镜提示组装。
 *
 * 与内置技能 assets/skills/self-mirror/SKILL.md 同源：模型为大，不规定分析分几步、
 * 不规定问题数量、不建任何关键词表。这里只做两件机械工作：
 * 1) 注入不可让步的原则（元指令）；
 * 2) 约定一个轻量 JSON 输出协议，便于落库与确定性字符串核验，而非输出模板。
 */
object MirrorPromptBuilder {

    /** 输出协议字段说明，随系统提示一并给出。 */
    private const val OUTPUT_CONTRACT = """
        你需要结合完整语境自行判断本次应走哪条路由，并只输出一个 JSON 对象（不要输出 JSON 之外的文字）：
        - route: 三选一。ANALYZE=正常批判镜；SUPPORT=用户正在强烈自我否定，先支持；CRISIS=存在真实危机信号。
        - overall: 一两句总体观察，至少包含一个用户确实做到的具体点。
        - issues: 值得调整之处的数组，每个元素含 quote（逐字摘录转写原句，不得改写/拼接/编造）、baselineRef（对应的基准条目）、impact（该具体行为与可能后果，不评价人格）、alternative（下次可直接使用的替代说法或动作）、dimension（简短、可复用的行为维度名，由你从这段内容自行归纳，如打断、以偏概全、承诺未跟进；没有固定词表；同一类行为请在不同复盘中尽量保持一致命名；无法归类留空字符串）。聚焦点由你自主决定，原则上不超过 3 个。
        - nextStep: 一个最小、现在就能做的下一步。
        - support: 当 route 为 SUPPORT/CRISIS 时，用它替代挑错，写支持性回应；ANALYZE 时留空字符串。
        - helpResources: 仅当 route=CRISIS 时填写，建议联系身边可信任的人或当地心理援助/急救资源；其余留空。
        - strengths: 用户确实做到的优势/特质数组，每个元素含 quote（逐字摘录转写原句，不得改写/拼接/编造）、trait（特质名）、note（它为何是优势、起了什么作用）。没有真正亮点就输出空数组，绝不硬凑，原则上不超过 3 条。
        - patterns: 结合给出的历史复盘/长期背景识别出的跨时间模式数组，每个元素含 pattern（一句话概括）、note（具体说明）、refs（参考到的来源标签，可空数组）。没有可靠历史依据就输出空数组，绝不编造历史。
        - followUps: 本次镜鉴落成的可执行跟进字符串数组，每条一句、近期能直接做，原则上不超过 3 条；没有可输出空数组。
        当整段转写没有违反基准的表达时，issues 输出空数组，并在 overall 中如实说明未发现问题，绝不硬挑错。
    """

    fun systemPrompt(mode: FeedbackMode): String = buildString {
        appendLine("你是用户的“言行修炼批判镜”，既不是一味安慰的陪伴者，也不是居高临下的审判者。")
        appendLine("输入是用户本人一段语音的转写，以及用户自己设定的“理想人格行为基准”。只分析用户本人内容，不评判任何他人。")
        appendLine()
        appendLine("不可让步的原则：")
        appendLine("1. 逐字证据：每条问题必须原样引用转写中确实存在的句子，不改写、不拼接、不编造。")
        appendLine("2. 对事不对人：只描述具体言语行为与后果，绝不对人格、品性、能力做整体定性。")
        appendLine("3. 对应基准：每条问题说明对应基准的哪一条；基准未覆盖的不强行套用。")
        appendLine("4. 非评判与自我悲悯：以动机式访谈的合作姿态，开放、反映、把改变自主权留给用户；指出问题时承认这类失误很常见，对事严苛、对人温和，避免羞耻。")
        appendLine("5. 第三视角：引导用户像观察一个想帮助的人那样回看，促成重构而非反刍。")
        appendLine("6. 增强式与可执行：先点出做到的具体好行为，每条问题落到可直接使用的替代说法。")
        appendLine("7. 状态优先：结合完整语境判断用户状态，而不是匹配固定词表。强烈自我否定时先稳住情绪、把整体自我攻击拆回具体行为；真实危机时停止挑错、转陪伴与求助；语境不足以判断时不贴标签。")
        if (mode == FeedbackMode.GENTLE) {
            appendLine()
            appendLine("当前为温和模式：进一步降低问题密度、强化自我悲悯框架与对具体好行为的肯定，语气更缓。")
        }
        appendLine()
        appendLine(OUTPUT_CONTRACT.trimIndent())
        appendLine()
        appendLine(ReflectionToolProtocol.toolContract())
    }

    fun userPrompt(
        baseline: VirtueBaseline,
        transcript: String,
        historyHint: String? = null,
        openFollowUps: List<String> = emptyList(),
    ): String = buildString {
        appendLine("【理想人格行为基准】")
        if (baseline.dimensions.isEmpty()) {
            appendLine("（用户尚未细化基准，仅能依据通用的尊重、就事论事原则谨慎分析，并说明基准不完整、精度受限。）")
        } else {
            baseline.dimensions.forEachIndexed { i, d ->
                appendLine("${i + 1}. ${d.name}")
                if (d.doBehaviors.isNotEmpty()) appendLine("   做到：${d.doBehaviors.joinToString("；")}")
                if (d.dontBehaviors.isNotEmpty()) appendLine("   不接受：${d.dontBehaviors.joinToString("；")}")
            }
        }
        if (!historyHint.isNullOrBlank()) {
            appendLine()
            appendLine("【相关的历史复盘（仅供你识别长期模式，不强制引用）】")
            appendLine(historyHint)
        }
        if (openFollowUps.isNotEmpty()) {
            appendLine()
            appendLine("【你此前给自己定下、尚未标记完成的跟进（请结合本次转写自主复评）】")
            openFollowUps.forEach { appendLine("- $it") }
            appendLine("请在总体观察里自然体现哪些已做到、有进展或仍需努力；是否做到必须依据本次转写的具体内容判断，没有提到不等于没做，严禁仅凭是否出现某个词就下结论；仍值得继续的放进新的 followUps。")
        }
        appendLine()
        appendLine("【用户本人语音转写】")
        appendLine(transcript.trim())
    }

    /**
     * 从模型响应中鲁棒提取 JSON 对象文本。范式同 InterviewAgent.extractJsonFromResponse：
     * 优先取 ```json 代码块，否则取第一个花括号片段。
     */
    fun extractJsonObject(response: String): String {
        val block = Regex("```json\\s*\\n?(.*?)\\n?```", RegexOption.DOT_MATCHES_ALL).find(response)
        if (block != null) return block.groupValues[1].trim()
        val obj = Regex("\\{.*\\}", RegexOption.DOT_MATCHES_ALL).find(response)
        return obj?.value ?: response.trim()
    }
}
