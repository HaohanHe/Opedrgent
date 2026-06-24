package top.hsyscn.opedrgent.utils

object ToolPrompts {

    private val toolPromptCache = mutableMapOf<String, String>()

    fun getToolPrompt(toolName: String): String {
        toolPromptCache[toolName]?.let { return it }

        val prompt = when (toolName) {
            "open_browser" -> """
## open_browser - 打开网页

在浏览器中打开指定的URL。

**使用场景：**
- 用户要求打开某个网页时
- 需要浏览网页内容时
- 验证URL是否可访问时

**参数：**
- `url` (必填): 要打开的网页URL

**调用示例：**
{"url": "https://example.com"}
""".trimIndent()

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

            "reverse_geocode" -> """
## reverse_geocode - 经纬度转地址

将GPS经纬度坐标转换为人类可读的地名地址。

**使用场景：**
- 用户提供经纬度需要转换成具体地址时
- 知道位置坐标但不知道地名时

**参数说明：**
- `lat` (必填): 纬度，范围 -90 到 90
- `lng` (必填): 经度，范围 -180 到 180

**返回：**
该坐标对应的城市、街道、地标等详细信息
""".trimIndent()

            "ask_question" -> """
## ask_question - 向用户提问选择题

当需要了解用户偏好、让用户做选择、或用户问题模糊不完整时，向用户追问选择题。

**使用场景：**
- 需要了解用户具体需求或偏好时
- 问题存在多种可能的理解方向时
- 用户需要从多个选项中做决定时
- 缺少必要的上下文信息时

**使用规范：**
- 提供2-5个具体选项供用户选择
- 每个选项包含 label（显示文字）和 description（详细说明）
- 单选题：multiple=false，用户选一个
- 多选题：multiple=true，用户可选多个
- 允许自定义：allowCustom=true，用户可输入自己的答案
- 不要一次性问太多问题（最多3个）

**调用示例：**
{"questions": [{"question": "您想了解哪个方面？", "header": "请选择", "options": [{"label": "技术原理", "description": "了解实现方式和原理"}, {"label": "应用场景", "description": "看看有哪些实际用途"}, {"label": "对比分析", "description": "和其他方案的优缺点对比"}], "multiple": false, "allowCustom": true}]}
""".trimIndent()

            "ask_confirmation" -> """
## ask_confirmation - 请求用户确认或授权

当模型需要用户明确授权或确认才能继续操作时，使用此工具。

**【重要】这不是追问问题，而是请求操作授权。**

**使用场景：**
- 自动化操作需要用户确认时（如"我来帮你接管浏览器"）
- 需要用户在多个操作中选择时（如"你想让我搜索英文还是中文？"）
- 耗时/不可逆操作需要用户确认时
- 用户提问但需要明确授权才能执行时

**核心规则：**
- 如果用户在30秒内没有响应，模型会收到 timeout=true，并**自动决定下一步**（不是等待，是继续！）
- 如果用户点击确认 → confirmed=true, selectedOption=用户选择的选项
- 如果用户点击取消 → confirmed=false
- 如果超时 → confirmed=false, timeout=true

**参数说明：**
- `message` (必填): 简要说明需要确认的内容
- `detail` (可选): 详细说明
- `options` (可选): 操作选项列表，每个选项有 label 和 description
- `timeoutSeconds` (可选): 超时秒数，默认30秒

**调用示例：**
{"message": "我来帮你接管浏览器完成验证码", "detail": "我将打开浏览器，请在验证码页面完成后点击确认", "options": [{"label": "我来输入", "description": "我自己输入验证码"}, {"label": "AI接管", "description": "让AI自动识别并填写"}], "timeoutSeconds": 30}
""".trimIndent()

            "mimo_tts" -> """
## mimo_tts - MiMo V2.5 影视级语音合成引擎

使用小米MiMo V2.5 TTS系列模型生成高质量语音。这不是简单的TTS朗读，而是**AI表演引擎**，支持情感、角色、唱歌、音色克隆等高级能力。

### 三种模式

| 模型 | model参数 | 用途 | 音色来源 |
|------|-----------|------|----------|
| 预置音色 | `mimo-v2.5-tts`（默认） | 快速生成、支持唱歌 | 8个内置精品音色 |
| 音色设计 | `mimo-v2.5-tts-voicedesign` | 文本描述生成独特音色 | 自然语言描述 |
| 音色克隆 | `mimo-v2.5-tts-voiceclone` | 复刻任意声音 | 音频样本(base64) |

### 预置音色列表

| 音色名 | 语言 | 性别 | 风格 |
|--------|------|------|------|
| 冰糖 | 中文 | 女性 | 活泼少女 |
| 茉莉 | 中文 | 女性 | 知性女声 |
| 苏打 | 中文 | 男性 | 阳光少年 |
| 白桦 | 中文 | 男性 | 成熟男声 |
| Mia | 英文 | Female | Lively girl |
| Chloe | 英文 | Female | Sweet Dreamy |
| Milo | 英文 | Male | Sunny boy |
| Dean | 英文 | Male | Steady Gentle |

### 核心能力

1. **自然语言风格控制**：一句话描述语气，如"用轻快上扬的语调向领导报喜"
2. **导演模式**：角色+场景+指导三维度精细控制（像给演员写剧本）
3. **音频标签**：(紧张)、[停顿]、(唱歌)等实时插入情感标签
4. **情绪混合**："压抑的愤怒"、"带着哽咽的笑意"等复合情绪
5. **唱歌模式**：(唱歌)歌词 即可合成歌曲
6. **方言切换**：(东北话)、(粤语)、(四川话)等
7. **音色设计**：文本描述自动生成独特音色（voicedesign模式）
8. **音色克隆**：音频样本复刻任意声音（voiceclone模式+voice_file_base64）

### 参数说明

- `text` (必填): 要合成的文本内容，建议2-5句一整段
- `voice` (可选): 音色名称，默认"冰糖"。预置音色见上表
- `model` (可选): 模型ID，默认"mimo-v2.5-tts"。可选 voicedesign/voiceclone
- `style_instruction` (可选): 自然语言风格描述（导演模式/整体风格）
- `overall_style` (可选): 整体风格标签，如"(温柔)"、"(激动)"、"(磁性)"
- `singing` (可选): 是否为唱歌模式，设为true启用
- `voice_file_base64` (可选): 仅voiceclone模式，音频样本的base64编码(mp3/wav，≤10MB)

### 常用风格标签

**基础情绪**: 开心 / 悲伤 / 愤怒 / 恐惧 / 惊讶 / 兴奋 / 委屈 / 平静 / 冷漠
**复合情绪**: 怅然 / 欣慰 / 无奈 / 愧疚 / 释然 / 嫉妒 / 厌倦 / 忐忑 / 动情
**语调**: 温柔 / 高冷 / 活泼 / 严肃 / 慵懒 / 俏皮 / 深沉 / 干练 / 凌厉
**音色**: 磁性 / 醇厚 / 清亮 / 空灵 / 稚嫩 / 苍老 / 甜美 / 沙哑 / 醇雅
**方言**: 东北话 / 四川话 / 河南话 / 粤语
**人设**: 夹子音 / 御姐音 / 正太音 / 大叔音 / 台湾腔

### 调用示例

// 情感朗读（必须调用工具！）
{"text": "今天天气真好", "overall_style": "(开心)", "voice": "冰糖"}

// 唱歌
{"text": "(唱歌)原谅我这一生不羁放纵爱自由", "singing": true, "voice": "苏打"}

// 导演模式（深度表演）
{"text": "你终于来了...我等了三百年",
  "style_instruction": "角色：千年狐妖，妩媚而危险\n场景：在月光下的古庙前\n指导：声音低沉魅惑，带着一丝颤抖和期待，语速极慢"}

// 音色克隆（需要音频样本）
{"model": "mimo-v2.5-tts-voiceclone", "text": "你好，这是我的声音", "voice_file_base64": "<base64编码的音频文件>"}

// 音色设计（文本描述生成新声音）
{"model": "mimo-v2.5-tts-voicedesign", "text": "欢迎收听今天的节目",
  "style_instruction": "中年女性，美食评论家风格，语调绵柔富感染力，偶尔闭眼吸气"}

### 最佳实践

- **【重要】当需要生成语音时，必须调用此工具**，不要只是在回复中描述"我为你朗读"
- 简单朗读 → 不用此工具，用系统TTS即可
- 有情感/表演需求 → 用此工具，通过style_instruction或overall_style控制
- 需要模仿某人的声音 → 用 voiceclone 模式 + voice_file_base64
- 需要独特的新声音 → 用 voicedesign 模式 + style_instruction 描述音色特质
- 文本长度 2-5 句最佳，超过2500字才需分段合成
- 标签是调味不是主菜，同一句话最多一个标签
- 标点有表演意义：省略号=停顿 / 破折号=被打断 / 大写=强调

### 输出

返回WAV文件路径，保存在Download目录，文件名格式：
- 预置音色: mimo_tts_时间戳.wav
- 音色设计: mimo_voicedesign_时间戳.wav
- 音色克隆: mime_voiceclone_时间戳.wav
""".trimIndent()

            "load_skill" -> """
## load_skill - 加载技能

按需加载技能的完整指令。仅在任务匹配系统提示中列出的某个技能描述时调用。

**使用场景：**
- 用户请求与某个技能描述匹配时
- 需要某个专业领域的能力时

**行为约束：**
- 不要预加载技能，只在需要时加载
- 加载后严格按照技能指令执行
- 加载失败时告知用户并继续正常回答
""".trimIndent()

            "todowrite" -> """
## todowrite - 任务跟踪

创建或更新结构化任务列表来跟踪多步骤工作进度。适用于3步以上的复杂任务。

**使用场景：**
- 复杂研究任务需要拆解步骤时
- 多个搜索/分析步骤需要跟踪进度时
- 用户要求完成一个需要多步操作的任务时

**参数：**
- `todos`: JSON 数组，每个元素包含：
  - `content`: 任务描述（必填）
  - `status`: pending/in_progress/completed/cancelled（必填）
  - `priority`: high/medium/low（可选，默认 medium）

**行为约束：**
- 在遇到3步以上任务时主动调用
- 完成一个步骤后更新状态
- 最多10个任务
- 不要为简单的单步问答创建任务列表

**调用示例：**
{"todos": [{"content": "搜索新能源汽车数据", "status": "completed", "priority": "high"}, {"content": "分析出口趋势", "status": "in_progress", "priority": "high"}, {"content": "生成分析报告", "status": "pending", "priority": "medium"}]}
""".trimIndent()

            "recall" -> """
## recall - 跨会话记忆

搜索或读取历史对话记录，实现跨会话记忆。当你需要回忆之前的讨论、查找之前的研究结果时使用。

**两种模式：**
1. **search** - 按关键词搜索历史会话标题和内容
   - `query`: 搜索关键词（可选）
   - `limit`: 结果数量上限（可选，默认10，最大20）

2. **read** - 根据会话ID读取完整对话记录
   - `session_id`: 会话ID（必填）

**使用场景：**
- 用户提到"上次讨论的..."、"之前说的..."
- 需要查找之前研究过的信息
- 建立跨会话的上下文关联

**行为约束：**
- 优先使用 search 模式找到相关会话
- 找到后用 read 模式读取详细内容
- 不要为每次回答都调用，只在确实需要回忆时使用
- 输出内容较长，注意不要塞满上下文窗口
""".trimIndent()

            "health_read" -> """
## health_read - 运动健康

读取用户的运动健康数据，包括步数、心率、睡眠、卡路里等。

**三种查询类型：**
1. **summary** - 今日运动摘要（步数、心率、距离、卡路里）
2. **steps** - 最近N天步数统计（可指定天数，默认7天）
3. **sleep** - 最近睡眠数据

**使用场景：**
- 用户询问运动、健康、步数相关问题
- 需要基于健康数据给出建议
- 用户要求查看今日运动量

**参数：**
- `query_type` (必填): summary / steps / sleep
- `days` (可选): 查询天数，仅 steps 模式有效

**调用示例：**
{"query_type": "summary"}
{"query_type": "steps", "days": 7}
{"query_type": "sleep"}
""".trimIndent()

            else -> ""
        }

        toolPromptCache[toolName] = prompt
        return prompt
    }

    fun getAllToolPrompts(): Map<String, String> {
        return mapOf(
            "open_browser" to getToolPrompt("open_browser"),
            "web_search" to getToolPrompt("web_search"),
            "deep_research" to getToolPrompt("deep_research"),
            "read_url" to getToolPrompt("read_url"),
            "reverse_geocode" to getToolPrompt("reverse_geocode"),
            "ask_question" to getToolPrompt("ask_question"),
            "ask_confirmation" to getToolPrompt("ask_confirmation"),
            "mimo_tts" to getToolPrompt("mimo_tts"),
            "load_skill" to getToolPrompt("load_skill"),
            "todowrite" to getToolPrompt("todowrite"),
            "recall" to getToolPrompt("recall"),
            "health_read" to getToolPrompt("health_read"),
        ).filter { it.value.isNotBlank() }
    }

    fun clearCache() {
        toolPromptCache.clear()
    }
}
