# Tasks

- [ ] Task 1: 在设置层添加 Ham 模式持久化开关
  - [ ] SubTask 1.1: 在 `ApiSettings.kt` 中新增 `isHamModeEnabled()` / `saveHamModeEnabled(enabled: Boolean)`，使用 `opedrgent_settings` SharedPreferences。
  - [ ] SubTask 1.2: 在 `MainViewModel.kt` 中新增对应的桥接方法 `isHamModeEnabled()` / `saveHamModeEnabled(enabled: Boolean)`。

- [ ] Task 2: 在设置 UI 添加 Ham 模式开关
  - [ ] SubTask 2.1: 在 `SettingsScreen.kt` 的“功能”分组中新增 `SettingSwitchRow`，标题“Ham 模式（业余卫星）”，副标题“开启后启用卫星过境工具”。
  - [ ] SubTask 2.2: 开关状态绑定 `hamEnabled` 本地状态与 `vm.isHamModeEnabled()`，变化时调用 `vm.saveHamModeEnabled(...)`。
  - [ ] SubTask 2.3: 保存按钮逻辑中加入 `vm.saveHamModeEnabled(hamEnabled)`。

- [ ] Task 3: 创建业余卫星基础数据库
  - [ ] SubTask 3.1: 在 `assets/ham_satellites.json` 中内置 10–15 颗常见业余卫星（含名称、NORAD ID、上行/下行频率、调制方式、备注）。
  - [ ] SubTask 3.2: 在 `tools/SatellitePassTool.kt` 同包或 `model/` 下新增 `HamSatellite` 数据类及 JSON 解析辅助方法。

- [ ] Task 4: 实现 TLE 获取与缓存
  - [ ] SubTask 4.1: 在 `tools/SatellitePassTool.kt` 中实现 `fetchTle(forceRefresh: Boolean)`：从 Celestrak `amateur.txt` 拉取，保存到应用私有缓存文件，记录下载时间。
  - [ ] SubTask 4.2: 实现缓存过期逻辑：超过 24 小时或强制刷新时重新拉取；网络失败时回退到缓存，完全无缓存返回错误。
  - [ ] SubTask 4.3: 实现 TLE 解析：按 NORAD ID 或卫星名称查找对应的两行轨道根数。

- [ ] Task 5: 实现卫星过境计算
  - [ ] SubTask 5.1: 在 `tools/SatellitePassTool.kt` 中实现基于 SGP4/SDP4 简化算法的过境计算，或引入纯 Kotlin 的轨道力学计算函数（不新增重量级依赖）。
  - [ ] SubTask 5.2: 使用 `ApiSettings.getLastLocation()` 或 `EnvironmentProvider.getCurrentLocation()` 获取观测站经纬度；若位置未开启或未缓存返回错误。
  - [ ] SubTask 5.3: 输出每个过境窗口：开始时间、最大仰角时间、结束时间、最大仰角度数、方位方向（升段/降段），按开始时间升序排列。

- [ ] Task 6: 实现 SatellitePassTool 工具类
  - [ ] SubTask 6.1: 在 `tools/SatellitePassTool.kt` 中创建类，实现 `ToolSet` 接口，提供 `satellite_pass` 工具。
  - [ ] SubTask 6.2: 工具参数 Schema 定义 `action`、`satellite`、`hours`。
  - [ ] SubTask 6.3: 处理 `action=list` 返回业余卫星列表；处理 `action=passes` 返回指定卫星的过境窗口；非法参数返回错误。

- [ ] Task 7: 条件化注册卫星过境工具
  - [ ] SubTask 7.1: 在 `ToolExecutor.kt` 中引入 `SatellitePassTool`。
  - [ ] SubTask 7.2: 仅在 `apiSettings.isHamModeEnabled()` 为 true 时 `register(SatellitePassTool(context, apiSettings))`。

- [ ] Task 8: 验证与测试
  - [ ] SubTask 8.1: 运行 `./gradlew assembleDebug` 确认无编译错误。
  - [ ] SubTask 8.2: 手动检查设置页开关状态、持久化、应用重启后工具注册状态。

# Task Dependencies
- Task 2 depends on Task 1
- Task 5 depends on Task 4
- Task 6 depends on Task 3 and Task 5
- Task 7 depends on Task 6
- Task 8 depends on Task 2 and Task 7
