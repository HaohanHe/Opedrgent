package top.hsyscn.opedrgent.tools.prompts

object InsightSproutToolPrompt {
    const val DESCRIPTION = "知识发芽：对输入文本进行深度多维度分析，发芽衍生出结构化的洞察报告。"

    const val USAGE_GUIDELINES = """
## insight_sprout 工具使用规范

### 必填参数
- text: 待发芽的原始文本内容（必填，建议 100 字以上以获得更好的发芽效果）

### 可选参数
- length: 发芽报告的目标长度（默认 2000，范围 100-10000）
- domains: 发芽领域列表，逗号分隔（如 "tech,business,psychology"）
  - 不指定时引擎自动判断最相关的分析维度
  - 支持的典型领域: tech, business, psychology, science, culture, education, health
- use_context: 是否结合上下文信息辅助发芽（默认 true）

### 使用场景
1. 用户需要对一段文本进行深度解读和扩展分析时
2. 需要从多个维度（技术、商业、心理等）发散思考时
3. 用户希望获得结构化的洞察报告而非简单摘要时
4. 对研究笔记、会议记录、文章草稿等进行知识提炼时

### 输出格式
返回 Markdown 格式的结构化报告，包含：
- 核心洞察摘要
- 多维度分析（根据 domains 参数或自动识别）
- 关键发现与推论
- 延伸思考与建议

### 最佳实践
1. 输入文本过短（<50字）时建议先收集更多上下文
2. 明确指定 domains 可提高分析的针对性
3. length 参数用于控制输出粒度，复杂主题建议增大值
4. 发芽结果适合作为进一步讨论或写作的基础素材

### 限制
- 单次输入文本上限由引擎内部限制决定
- 发芽质量依赖于输入文本的信息密度
- 极短文本可能产生有限的分析结果
"""

    fun getToolPrompt(): String = "$DESCRIPTION\n$USAGE_GUIDELINES"
}
