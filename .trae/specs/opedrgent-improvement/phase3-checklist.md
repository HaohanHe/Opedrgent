# Checklist - Phase 3: Prompt架构 + 工具标准化 + Skill外部化

## P1 高优先级

- [x] 1.1 `utils/PromptSection.kt` 存在且定义了 `PromptSection` 和 `PromptSectionResolver`
- [x] 1.2 `resolvePromptSections()` 正确分离静态/动态内容
- [x] 1.3 工具调用计数正确工作（DebugLog 输出）
- [x] 1.4 `tools/prompts/SearchToolPrompt.kt` 存在且包含搜索规范
- [x] 1.5 `tools/prompts/EditToolPrompt.kt` 存在且包含编辑规范

## P2 中优先级

- [x] 2.1 推理链日志记录正常
- [x] 2.2 多语言约束生效
- [x] 2.3 CLAUDE.md 加载功能正常
- [x] 2.4 工作目录递归扫描 depth ≤ 3
- [x] 2.5 `loadSkillFromAssets()` 能正确解析 assets/skills/*.json
- [x] 2.6 `responseScoring` 字段存在于 ApiConfig
- [x] 2.7 `tokenCounter` 字段存在于 ApiConfig
- [x] 2.8 `toolResultFilter` 过滤函数存在

## 全量验证

- [x] 3.1 `gradlew assembleDebug` BUILD SUCCESSFUL
- [x] 3.2 无新增 ERROR 级别编译错误
