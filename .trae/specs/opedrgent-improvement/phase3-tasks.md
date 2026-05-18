# Tasks - Phase 3: Prompt架构 + 工具标准化 + Skill外部化

## P1 高优先级

- [x] **Task 1: PromptSection 分段接口**
  - [x] 创建 `utils/PromptSection.kt`：定义 `PromptSection` 数据类和 `PromptSectionResolver.resolvePromptSections()`
  - [x] 实现静态/动态内容分离
  - [x] 与 PromptBuilder 兼容
  - [x] 编译验证

- [x] **Task 2: 工具调用计数**
  - [x] `toolExecutor` 记录工具调用次数
  - [x] 超过阈值输出警告日志
  - [x] 编译验证

- [x] **Task 3: Tool Prompt 标准化**
  - [x] 创建 `tools/prompts/SearchToolPrompt.kt`：网络搜索工具 prompt
  - [x] 创建 `tools/prompts/EditToolPrompt.kt`：文件编辑工具 prompt
  - [x] 与 ToolSet 注解体系对齐
  - [x] 编译验证

## P2 中优先级

- [x] **Task 4: 推理链日志**
  - [x] 记录完整推理过程到 DebugLog
  - [x] 编译验证

- [x] **Task 5: 多语言约束**
  - [x] 在 PromptBuilder 中增加多语言指令
  - [x] 编译验证

- [x] **Task 6: CLAUDE.md 加载**
  - [x] `ContextFileLoader.findCLAUDEMD()` 支持从工作目录加载
  - [x] CLAUDE.md 内容注入到 system prompt
  - [x] 编译验证

- [x] **Task 7: 工作目录递归**
  - [x] ContextFileLoader 递归扫描子目录（depth ≤ 3）
  - [x] 编译验证

- [x] **Task 8: Skill 外部化**
  - [x] `BuiltinSkillLoader.loadSkillFromAssets()` 从 assets/skills/*.json 加载
  - [x] `loadAllSkillsFromAssets()` 批量加载
  - [x] 编译验证

- [x] **Task 9: 模型响应评分**
  - [x] `ApiConfig` 增加 `responseScoring` 字段
  - [x] 用于评估模型输出质量
  - [x] 编译验证

- [x] **Task 10: Token 计数**
  - [x] `ApiConfig` 增加 `tokenCounter` 字段
  - [x] 用于 token 用量统计
  - [x] 编译验证

- [x] **Task 11: 工具结果过滤**
  - [x] `toolResultFilter` 函数过滤敏感信息
  - [x] 编译验证

## Task Dependencies

- Task 1-3 可并行
- Task 4-7 可并行于 Task 1-3
- Task 8-11 可并行于 Task 7
