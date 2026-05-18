package top.hsyscn.opedrgent.tools.prompts

object SearchToolPrompt {
    const val DESCRIPTION = "网络搜索：使用多个搜索引擎查询信息。当用户询问需要网络查询才能回答的问题时必须使用此工具。"

    const val USAGE_GUIDELINES = """
## web_search 工具使用规范

### 必填参数
- query: 搜索关键词（中文或英文均可）

### 可选参数
- method: 搜索方法
  - ddg（默认）: DuckDuckGo 搜索
  - webview: 内置浏览器搜索（更稳定但速度较慢）
  - provider_native: 使用LLM提供商的原生联网能力
  - mcp: JavaScript注入搜索
  - multimodal: 多模态虚拟点击搜索
- max_fetch: 最大抓取条数（默认3，最大5）

### 使用最佳实践
1. 搜索词要短（3-6个词），专有名词拆开
2. 中文查询自动双语搜索
3. 搜索结果不相关 → 换关键词，不要重复搜一样的
4. 优先使用 read_url 抓取详细页面内容

### 限制
- 不保证搜索结果实时性
- 部分网站可能抓取失败
"""

    fun getToolPrompt(): String = "$DESCRIPTION\n$USAGE_GUIDELINES"
}