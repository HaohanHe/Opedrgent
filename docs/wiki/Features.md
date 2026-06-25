# 功能特性

## 核心 AI 能力

### 多模型对话
- 支持多种 LLM API（OpenAI 兼容接口）
- 流式 SSE 输出 + Thinking Mode（思考过程可视化）
- JSON Mode 强制结构化输出
- 双协议支持：Chat Completions + Messages (Anthropic)

### 本地模型推理
- LiteRT-LM (v0.12.0+) 集成
- 支持 Gemma 4 等模型的端侧推理
- GPU/NPU 加速
- 离线模式降级

### 工具调用 (Tool Call)
- 可扩展的 Tool 架构，LLM 可动态调用 20+ 工具
- Function Calling Schema + System Prompt 双通道工具发现
- 工具安全护栏 (ToolCallGuardrail)

### 深度研究
- 多步搜索 + 网页阅读 + 自动总结
- HybridRankingEngine 混合排序
- SemanticScorer 语义评分 + DynamicAuthorityScorer 动态权威评分
- FreshnessCalculator 新鲜度 + ResultDeduplicator 去重

---

## 语音与音频

### 语音识别 (STT)
- 多引擎架构：Sherpa-ONNX（离线，Paraformer/SenseVoice）+ MiMO ASR + Android SpeechRecognizer
- 自动降级链：在线失败自动切换离线
- 会议转录：说话人分离 + 多人语音转文字

### 全双工语音通话 (Interview Mode)
- AudioRecord || AudioTrack + 硬件 AEC
- VAD（RMS 能量检测，800ms 超时，200f 阈值）
- BargeIn（用户打断检测）
- 5 状态有限状态机 (DuplexState)
- HippocampusMemory 目标锚定 + 漂移检测

### TTS 语音合成
- 三引擎路由：System TTS + StepAudio TTS (Global+Inline) + MiMo TTS
- StepRealtimeClient WebSocket 实时语音

---

## 知识与记忆

### 知识发芽 (Insight Sprout)
- 四阶段 AI 洞察引擎：种子提取 -> 跨领域关联 -> AHA 洞察 -> 金句回响
- 三层渐进式上下文注入：标签层 -> 索引层 -> 联网搜索验证
- 叙事式文章输出（非结构化数据）
- 发芽数据持久化，重启不丢失

### 海马记忆系统 (Hippocampus Memory)
- SQLite 全局索引，关键词提取 + LIKE 模糊匹配
- 自动索引：笔记、对话、录音、发芽、面试、用户偏好
- 三个作用域：GLOBAL / PROJECT / SESSION
- 面试模式：目标锚定 + 漂移检测 + 注意力注入

### 笔记系统
- 完整的 CRUD 笔记管理
- 文件夹分类 + 标签系统
- 知识图谱 (KnowledgeGraph) 关系可视化
- 笔记发芽/分享/图谱

---

## 日历与健康

### 日历 CRUD
- 通过 ContentProvider 直接操作系统日历事件
- 支持创建/查询/修改/删除（完整 CRUD）
- 自然语言时间解析（"明天下午3点"、"一小时后"、"下周一"）
- 无需用户二次确认
- 支持 Outlook/Exchange 等同步到系统日历的账号

### Health Connect 健康数据
- 读取步数、心率、卡路里、距离、睡眠
- 今日摘要自动注入 system prompt
- LLM 可通过 `health_read` 工具获取更详细数据
- 运行时权限链：ACTIVITY_RECOGNITION -> Health Connect 权限

---

## 浏览器与自动化

### WebView Agent
- 自动化引擎，支持网页内容抓取、搜索、截图
- 多模态交互点击

### 自动化工作流
- AutomationWorker + AutomationStore
- 可配置的定时/触发任务

---

## 技能系统 (V2)

- SKILL.md 标准 Frontmatter 元数据
- JS Skill 沙箱执行（WebView + JavaScript Bridge）
- 三种导入方式：URL 远程加载、本地文件导入、手动创建
- RequireSecret 三级授权：ALLOW / ASK / DENY
- 内置技能：calculate-hash、mood-tracker-lite、critical-inquiry 等
