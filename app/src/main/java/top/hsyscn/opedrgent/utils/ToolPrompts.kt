package top.hsyscn.opedrgent.utils

object ToolPrompts {

    private val toolPromptCache = mutableMapOf<String, String>()

    fun getToolPrompt(toolName: String): String {
        toolPromptCache[toolName]?.let { return it }

        val prompt = when (toolName) {
            "web_search" -> """
## web_search - 快速搜索

搜索互联网获取信息。支持中英文双语自动搜索。

**使用场景：**
- 需要查找最新信息、新闻、事实数据时
- 用户问题模糊需要探索性搜索时

**最佳实践：**
- 搜索词要短（3-6词），专有名词拆开
- 中文查询会自动构造英文关键词进行二次搜索
- 结果不相关时换关键词重试，不要重复搜一样的

**输出格式：**
返回5条结果，每条包含title、url、snippet、relevance_score
""".trimIndent()

            "deep_research" -> """
## deep_research - 深度研究

对复杂主题进行多轮深度研究，整合多篇全文内容。

**使用场景：**
- 需要全面了解某个主题时
- web_search结果不够深入时
- 用户需要详细分析报告时

**工作流程：**
1. 先用web_search快速扫描相关资源
2. 选取最相关的3-5个URL进行深度阅读
3. 整合多篇内容形成完整分析
4. 交叉验证关键事实

**注意事项：**
- 每次最多读取3-5篇全文，避免token浪费
- 关注核心论点和数据，忽略冗余描述
- 标注所有引用来源[S1][S2]
""".trimIndent()

            "read_url" -> """
## read_url - 精读网页

读取指定URL的完整正文内容。

**使用场景：**
- web_search找到相关链接后需要深入阅读时
- 用户提供了具体URL需要提取内容时

**最佳实践：**
- 先用web_search确认URL有效性和相关性
- 大页面可能被截断，关注核心内容即可
- 返回纯文本，去除HTML标签和广告

**错误处理：**
- URL无效或无法访问 → 返回错误并建议重试
- 内容为空 → 尝试用web_search找替代来源
""".trimIndent()

            "question" -> """
## question - 追问澄清

当用户问题模糊或不完整时，向用户追问以明确需求。

**使用场景：**
- 用户输入过于简短（如单字"查"）
- 问题存在多种可能的理解方向
- 缺少必要的上下文信息

**使用规范：**
- 提供2-4个具体选项供用户选择
- 每个选项简短明确，便于理解
- 不要一次性问太多问题（最多3个）

**示例格式：**
"您想了解哪个方面？
1. 技术原理和实现方式
2. 应用场景和使用案例
3. 性能对比和优缺点
4. 学习资源和入门指南"
""".trimIndent()

            "mimo_tts" -> """
## mimo_tts - MiMo V2.5 影视级语音合成

使用小米MiMo V2.5 TTS引擎生成高质量语音。这不是简单的TTS朗读，而是**AI表演引擎**，支持情感、角色、唱歌等高级能力。

**使用场景：**
- 需要生成有情感、有表现力的语音时（如配音、播客、故事讲述）
- 需要特定风格或情绪的语音输出时
- 需要唱歌、方言、角色扮演等特殊语音效果时
- 普通TTS无法满足表现力需求的场景

**核心能力（远超普通TTS）：**
1. **8个预置音色**：冰糖(活泼少女)、茉莉(知性女声)、苏打(阳光少年)、白桦(成熟男声)、Mia/Chloe/Milo/Dean(英文)
2. **自然语言风格控制**：一句话描述语气，如"用轻快上扬的语调向领导报喜"
3. **导演模式**：角色+场景+指导三维度精细控制（像给演员写剧本）
4. **音频标签**：(紧张)、[停顿]、(唱歌)等实时插入情感标签
5. **情绪混合**："压抑的愤怒"、"带着哽咽的笑意"等复合情绪
6. **唱歌模式**：(唱歌)歌词 即可合成歌曲
7. **方言切换**：(东北话)、(粤语)、(四川话)等
8. **音色设计**：文本描述自动生成独特音色
9. **音色克隆**：音频样本复刻任意声音

**参数说明：**
- `text` (必填): 要合成的文本内容
- `voice` (可选): 音色名称，默认"冰糖"。可选：茉莉/苏打/白桦/Mia/Chloe/Milo/Dean
- `model` (可选): 模型ID，默认"mimo-v2.5-tts"(预置音色)。也可用"mimo-v2.5-tts-voicedesign"(音色设计)或"mimo-v2.5-tts-voiceclone"(音色克隆)
- `style_instruction` (可选): 自然语言风格描述，放在user消息中
- `overall_style` (可选): 整体风格标签，如"(温柔)"、"(激动)"、"(磁性)"等
- `singing` (可选): 是否为唱歌模式，设为true启用

**最佳实践：**
- 简单朗读 → 不用此工具，用系统TTS即可
- 有情感/表演需求 → 用此工具，通过style_instruction或overall_style控制
- 唱歌 → 设置singing=true，文本以(唱歌)开头
- 角色扮演 → 使用导演模式（style_instruction详细描述角色+场景+指导）
- 方言 → 在overall_style中指定，如"(东北话)"

**示例调用：**
```
// 简单情感朗读
{"text": "今天天气真好", "overall_style": "(开心)", "voice": "冰糖"}

// 唱歌
{"text": "原谅我这一生不羁放纵爱自由", "singing": true, "voice": "苏打"}

// 导演模式（深度表演）
{"text": "你终于来了...我等了三百年", 
  "style_instruction": "角色：千年狐妖，妩媚而危险\\n场景：在月光下的古庙前，等待转世的爱人\\n指导：声音低沉魅惑，带着一丝颤抖和期待，语速极慢"}
```

**输出：**
返回WAV文件路径，保存在Download目录，文件名格式：mimo_tts_时间戳.wav
""".trimIndent()

            else -> ""
        }

        toolPromptCache[toolName] = prompt
        return prompt
    }

    fun getAllToolPrompts(): Map<String, String> {
        return mapOf(
            "web_search" to getToolPrompt("web_search"),
            "deep_research" to getToolPrompt("deep_research"),
            "read_url" to getToolPrompt("read_url"),
            "question" to getToolPrompt("question"),
            "mimo_tts" to getToolPrompt("mimo_tts"),
        ).filter { it.value.isNotBlank() }
    }

    fun clearCache() {
        toolPromptCache.clear()
    }
}
