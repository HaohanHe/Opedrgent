# Opedrgent 1.0 更新日志 / Release Notes

## 1.0.0

### 新增 / New

- **Interview 访谈模式**：支持全双工语音对话、硬件 AEC、VAD 与打断检测。  
  **Interview Mode**: Full-duplex voice conversation with hardware AEC, VAD, and barge-in detection.
- **V2 Skills 系统**：支持动态加载 SKILL.md 技能、JS 沙箱执行与动态工具注册。  
  **V2 Skill System**: Dynamic SKILL.md loading, JS sandbox execution, and dynamic tool registration.
- **本地大模型 LiteRT-LM**：集成 Google 端侧推理框架，支持 Gemma 4 等模型。  
  **Local LLM LiteRT-LM**: Integrated Google's on-device inference framework, supporting Gemma 4 and related models.
- **笔记与知识图谱**：笔记 CRUD、图谱关系、知识萌发（Insight Sprout）四阶段洞察。  
  **Notes & Knowledge Graph**: Note CRUD, graph relationships, and four-stage Insight Sprout.
- **首页小组件**：Opedrgent Widget 快捷入口。  
  **Home Widget**: Quick-access Opedrgent Widget.

### 优化 / Improvements

- **核心性能优化**：工具调用改为并发执行，MMR 重排序引入 Top-K 截断，缓存改为无锁 ConcurrentHashMap。  
  **Core Performance**: Concurrent tool execution, Top-K truncated MMR reranking, lock-free ConcurrentHashMap cache.
- **聊天历史分页**：默认显示最近 10 轮对话，上滑时按块加载更早记录。  
  **Chat History Pagination**: Defaults to the latest 10 rounds, loading older rounds in chunks on scroll-up.
- **返回键与首页交互**：重写 BackHandler，子页面返回上一层、非首页 Tab 返回首页、首页双击退出；录音状态返回时弹确认框；首页内容上移。  
  **Back Navigation & Home UI**: Reworked BackHandler with nested back, home-tab fallback, double-tap exit, recording back confirmation, and raised home content.
- "探索者" 文案现在优先读取应用昵称或系统设备名。  
  The "Explorer" greeting now reads the in-app nickname or system device name first.

### 文档 / Documentation

- 新增用户手册（`docs/wiki/User-Manual.md`）、FAQ（`docs/wiki/FAQ.md`），README 增加文档入口链接。  
  Added User Manual (`docs/wiki/User-Manual.md`), FAQ (`docs/wiki/FAQ.md`), and documentation links in README.

### 修复 / Fixes

- 修复 Release 构建中 Sherpa-ONNX 类重复定义导致的 R8 打包失败，移除本地 7 个 Stub 类。  
  Fixed Release build R8 failure caused by duplicate Sherpa-ONNX classes; removed 7 local stub classes.
- 修复 `values-en` / `values-ja` 中存在但默认 `values` 缺失的 3 个翻译字符串（`settings_voice_memo`、`settings_meeting`、`settings_classroom`）。  
  Fixed 3 missing default-locale strings (`settings_voice_memo`, `settings_meeting`, `settings_classroom`) that existed only in `values-en` / `values-ja`.

---

**版本号 / Version**: 1.0.0  
**构建状态 / Build Status**: `./gradlew assembleRelease` 通过 / `BUILD SUCCESSFUL`
