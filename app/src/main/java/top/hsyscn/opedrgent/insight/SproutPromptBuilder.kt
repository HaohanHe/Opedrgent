package top.hsyscn.opedrgent.insight

object SproutPromptBuilder {

    fun buildPhase1Prompt(inputText: String, context: String? = null): String {
        val ctxSection = if (!context.isNullOrBlank()) {
            "\n\n【用户上下文参考】\n$context\n"
        } else ""

        return """你是一位认知科学分析师，擅长从文本中提取核心概念和潜在主题。

## 任务
分析以下文本，提取其中的**核心种子**（关键概念、观点、情感倾向、潜在主题）。

## 输入文本
$inputText$ctxSection## 输出要求
请提取 **2-3 个核心种子**，每个种子包含：
1. **概念名称**（简短精炼，2-6个字）
2. **描述**（1-2句话解释这个概念在文本中的含义）
3. **相关关键词**（3-5个关联词）

## 输出格式（严格遵守 JSON 格式）
```json
{
  "seeds": [
    {
      "concept": "概念名",
      "description": "描述",
      "keywords": ["关键词1", "关键词2"]
    }
  ]
}
```

请直接输出 JSON，不要添加其他说明文字。"""
    }

    fun buildPhase2Prompt(seedsJson: String, previousContext: String): String {
        return """你是一位跨学科研究专家，擅长在不同领域之间建立意想不到的联系。

## 已提取的种子
$seedsJson

## 前序上下文
$previousContext

## 任务
将上述种子映射到 **≥3 个不同领域**（从以下领域选择：历史、科学、哲学、心理学、经济学、文学、生物学、社会学、艺术、技术、商业），为每个领域提供一个：
1. **类比或真实案例**（具体、有画面感）
2. **深度分析解读**（解释这个案例如何与种子产生联系）

## 要求
- 寻找**反直觉的、出人意料的关联**，避免陈词滥调
- 每个领域的分析要有深度，不能泛泛而谈
- 优先选择能让人「恍然大悟」的角度

## 输出格式（严格遵守 JSON 格式）
```json
{
  "connections": [
    {
      "domain": "领域名称",
      "analogyOrCase": "具体的类比或案例（2-3句话）",
      "analysis": "深度分析解读（3-5句话）",
      "unexpectedness": 0.7
    }
  ]
}
```
`unexpectedness` 字段表示反直觉程度（0-1），越反直觉越接近 1。

请直接输出 JSON，不要添加其他说明文字。"""
    }

    fun buildPhase3Prompt(seedsAndConnections: String, previousContext: String): String {
        return """你是一位深度思考者和行为经济学家，擅长发现隐藏在表象之下的反直觉真相。

## 种子与跨领域关联
$seedsAndConnections

## 前序上下文
$previousContext

## 任务
基于以上所有信息，生成 **1-2 条原创的 Aha 洞察**。

## Aha 洞察的标准
- **简洁有力**：像金句一样可以独立传播（不超过 30 字）
- **反直觉**：挑战常识，打破固有认知
- **启发性**：引发读者重新思考
- **可记忆**：用词精准，意象鲜明

## 参考范例
- 「免费是最昂贵的价格——它让你付出的不是金钱，而是选择的标准和时间的尊严」
- 「协作的效率不取决于参与者的数量，而取决于他们之间交互的复杂度」
- 「当文字变成语音，失去的不是信息，而是那些无法被编码的、属于人类的温度」

## 输出格式（严格遵守 JSON 格式）
```json
{
  "insights": [
    {
      "content": "洞察内容（一句话金句）",
      "counterIntuitiveScore": 0.85,
      "tags": ["标签1", "标签2"]
    }
  ]
}
```

请直接输出 JSON，不要添加其他说明文字。"""
    }

    fun buildPhase4Prompt(allPreviousContext: String, preferredDomains: List<String>? = null): String {
        val domainHint = if (!preferredDomains.isNullOrEmpty()) {
            "\n**偏好领域**：${preferredDomains.joinToString("、")}\n"
        } else ""

        return """你是一位博学家和文学评论家，精通人类知识库并能将个人观点与经典智慧桥接。

## 前序所有阶段的结果
$allPreviousContext
$domainHint## 任务
生成 **1 条金句回响**，将上述洞察与人类经典著作或名人名言建立桥梁。

## 金句回响的结构
1. **原文引用**：一句经典名言或著作中的观点（注明出处和作者）
2. **延展思考**：基于该引用，结合当前话题生成的原创延展思考（2-3句话）

## 可引用的经典来源建议
- **文学**：加缪《堕落》《局外人》、卡夫卡《变形记》、奥威尔《1984》
- **哲学**：维特根斯坦《逻辑哲学论》、尼采、海德格尔
- **心理学**：保罗·艾克曼（情绪研究）、丹尼尔·卡尼曼《思考快与慢》
- **经济学**：丹·艾瑞里《怪诞行为学》、理查德·塞勒
- **科学**：费曼、道金斯《自私的基因》
- **社会学**：马尔科姆·格拉德威尔《异类》
- **东方思想**：老子《道德经》、庄子

## 输出格式（严格遵守 JSON 格式）
```json
{
  "quotes": [
    {
      "originalQuote": "经典原句",
      "source": "出处（书名/文章名）",
      "author": "作者名",
      "extension": "原创延展思考（2-3句话，桥接原句与当前话题）"
    }
  ]
}
```

请直接输出 JSON，不要添加其他说明文字。"""
    }

    fun buildFinalMarkdownReport(result: SproutResult): String {
        val sb = StringBuilder()

        sb.appendLine("🌱 **种子**")
        sb.appendLine()
        result.seeds.forEachIndexed { index, seed ->
            sb.appendLine("${index + 1}. **${seed.concept}**：${seed.description}")
            if (seed.keywords.isNotEmpty()) {
                sb.appendLine("   关键词：${seed.keywords.joinToString("、")}")
            }
            sb.appendLine()
        }

        sb.appendLine("---")
        sb.appendLine()
        sb.appendLine("✨ **Aha 瞬间**")
        sb.appendLine()
        result.insights.forEachIndexed { index, insight ->
            sb.appendLine("> ${insight.content}")
            if (insight.tags.isNotEmpty()) {
                sb.appendLine(" *${insight.tags.joinToString(" · ")}*")
            }
            sb.appendLine()
        }

        sb.appendLine("---")
        sb.appendLine()
        sb.appendLine("🔗 **跨领域联结**")
        sb.appendLine()
        result.connections.forEachIndexed { index, conn ->
            sb.appendLine("**${conn.domain}**：${conn.analogyOrCase}")
            sb.appendLine("> ${conn.analysis}")
            sb.appendLine()
        }

        sb.appendLine("---")
        sb.appendLine()
        sb.appendLine("💡 **金句回响**")
        sb.appendLine()
        result.quotes.forEach { quote ->
            sb.appendLine("> 「${quote.originalQuote}」——*${quote.author}《${quote.source}》*")
            sb.appendLine()
            sb.appendLine("${quote.extension}")
            sb.appendLine()
        }

        return sb.toString().trim()
    }
}
